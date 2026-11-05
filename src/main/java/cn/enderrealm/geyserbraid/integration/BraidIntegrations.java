/*
 * Copyright (c) 2026 EnderRealmMC
 * SPDX-License-Identifier: LGPL-3.0-only
 */

package cn.enderrealm.geyserbraid.integration;

import cn.enderrealm.geyserbraid.GeyserBraidExtension;
import cn.enderrealm.geyserbraid.relay.MediaRelay;
import io.netty.channel.Channel;
import org.geysermc.geyser.GeyserImpl;
import org.geysermc.geyser.GeyserLogger;
import org.geysermc.geyser.configuration.GeyserConfig;
import org.geysermc.geyser.network.bedrock.nethernet.NetherNetServer;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;

/**
 * The assembler: wires the demultiplexer and the media relay onto Geyser's
 * already-running network stack.
 *
 * <p>The NetherNet signaling and its ICE socket are left completely untouched.
 * v1 closed and re-bound the built-in signaling channel with a wrapped instance;
 * the library refused a second bind of a signaling instance that the channel
 * close had already marked closed ("Signaling cannot be bound again once
 * closed"). Rather than duplicating Geyser's whole signaling construction
 * (identity, HTTPS, MOTD provider, proxies), candidate rewriting is delegated to
 * {@link AdvertisedMirror}: it mirrors the shared port onto Geyser's own dynamic
 * addresses — the live STUN discovery results, or the host's own interfaces on a
 * LAN — by writing the signaling's advertised list, which the library re-reads
 * on every answer. Clients then send their ICE checks to the shared UDP port,
 * where the demultiplexer hands them to the relay, which ferries them to the
 * existing ICE socket on the webrtc-port (reachable over loopback). An operator
 * who prefers static control can set "advertise-addresses" by hand and the
 * mirror steps aside.</p>
 */
public final class BraidIntegrations {

    /** Upper bound on relay legs. Far above any normal server's concurrency; it caps hostile scanning. */
    private static final int MAX_LEGS = 1024;

    /** The relay from the last successful assembly; null before or after a failure. */
    private static volatile MediaRelay relay;
    /** The advertised-address mirror from the last successful assembly; null when inactive. */
    private static volatile AdvertisedMirror mirror;
    /** Whether a full assembly has completed. */
    private static volatile boolean assembled;
    private static volatile int sharedPort;
    private static volatile int webrtcPort;
    private static volatile int signalingPort;

    /** @return the relay from the last successful assembly, or null */
    public static MediaRelay relay() {
        return relay;
    }

    /** @return the advertised-address mirror, or null when inactive */
    public static AdvertisedMirror mirror() {
        return mirror;
    }

    /** @return true when a full assembly has completed */
    public static boolean assembled() {
        return assembled;
    }

    /** @return the shared public UDP port */
    public static int sharedPort() {
        return sharedPort;
    }

    /** @return the internal ICE port the relay legs target */
    public static int webrtcPort() {
        return webrtcPort;
    }

    /** @return the TCP signaling port */
    public static int signalingPort() {
        return signalingPort;
    }

    private BraidIntegrations() {
    }

    /**
     * Performs the assembly. Any failed step aborts the remaining ones.
     *
     * <p>Port semantics match Geyser's own startup resolution: a
     * {@code webrtc-port} of 0 follows the Bedrock port (valid when NetherNet
     * owns the UDP side alone).</p>
     */
    public static void install() {
        GeyserImpl geyser = GeyserImpl.getInstance();
        GeyserLogger logger = geyser.getLogger();
        GeyserConfig.BedrockConfig bedrock = geyser.config().bedrock();
        int sharedPort = bedrock.port();
        int webrtcPort = bedrock.webrtcPort() == 0 ? sharedPort : bedrock.webrtcPort();
        int signalingPort = bedrock.signaling().port();
        BraidIntegrations.sharedPort = sharedPort;
        BraidIntegrations.webrtcPort = webrtcPort;
        BraidIntegrations.signalingPort = signalingPort;

        try {
            List<Channel> listeners = RakNetListenerAccess.listeners();
            if (listeners.isEmpty()) {
                throw new IllegalStateException("no bound RakNet listener channels to mount the demultiplexer on");
            }

            // Geyser's ICE socket stays bound on the Bedrock address at the webrtc-port;
            // a wildcard bind includes the loopback, so relay legs can reach it there
            InetSocketAddress mediaEndpoint = new InetSocketAddress(InetAddress.getLoopbackAddress(), webrtcPort);
            boolean proxyFraming = !geyser.config().advanced().bedrock().webrtcProxyProtocolRelays().isEmpty();
            MediaRelay mediaRelay = new MediaRelay(mediaEndpoint, MAX_LEGS, proxyFraming);
            listeners.forEach(mediaRelay::attach);
            relay = mediaRelay;

            checkNetherNetSide(logger);
            if (bedrock.signaling().advertiseAddresses().isEmpty()) {
                // Mirror the shared port onto Geyser's dynamic addresses (STUN discovery
                // results, else the host's own interfaces) — zero configuration, works
                // on public hosts, behind NAT and on LAN-only test setups alike
                mirror = AdvertisedMirror.start(geyser, sharedPort);
            } else {
                logger.info(GeyserBraidExtension.LOG_PREFIX
                        + "\"advertise-addresses\" is set manually; the advertised-address mirror stays off.");
            }

            logger.info(GeyserBraidExtension.LOG_PREFIX + "Assembled: demultiplexer mounted on " + listeners.size()
                    + " listener channel(s) (UDP " + sharedPort + "), NetherNet media relayed to " + mediaEndpoint);
            assembled = true;
        } catch (Exception e) {
            assembled = false;
            relay = null;
            mirror = null;
            // A failed assembly leaves Geyser's original listeners untouched; the
            // transports keep working in the vanilla dual-port layout
            logger.severe(GeyserBraidExtension.LOG_PREFIX
                    + "Assembly failed, single-port demultiplexing is NOT enabled (Geyser's original listeners are"
                    + " unaffected): " + e.getMessage(), e);
        }
    }

    /**
     * Tears down everything the assembly started: unmounts the demultiplexers
     * and closes the relay legs, stops the advertised-address mirror, and
     * resets the assembly state so the commands report honestly afterwards.
     * Called on Geyser's shutdown event; safe to call when nothing assembled.
     */
    public static void shutdown() {
        MediaRelay mediaRelay = relay;
        relay = null;
        if (mediaRelay != null) {
            mediaRelay.shutdown();
        }
        AdvertisedMirror advertisedMirror = mirror;
        mirror = null;
        if (advertisedMirror != null) {
            advertisedMirror.stop();
        }
        assembled = false;
    }

    /**
     * Non-fatal sanity check: warns when neither the built-in signaling channel
     * nor the NXS provider channel is active, since the relay would then have
     * nothing to forward to. Reflection is confined here and is allowed to fail
     * quietly on a future Geyser refactor.
     */
    private static void checkNetherNetSide(GeyserLogger logger) {
        try {
            Object value = ReflectAccess.getField(GeyserImpl.getInstance(), "netherNetServer");
            if (value instanceof NetherNetServer server) {
                Object inbuilt = ReflectAccess.getField(server, "inbuiltChannel");
                Object provider = ReflectAccess.getField(server, "netherNetChannel");
                boolean active = (inbuilt instanceof Channel builtinChannel && builtinChannel.isActive())
                        || (provider instanceof Channel providerChannel && providerChannel.isActive());
                if (!active) {
                    logger.warning(GeyserBraidExtension.LOG_PREFIX
                            + "No active NetherNet channel (neither built-in signaling nor the NXS provider);"
                            + " the relay has nothing to forward to until one starts.");
                }
            }
        } catch (ReflectiveOperationException e) {
            logger.warning(GeyserBraidExtension.LOG_PREFIX
                    + "Could not inspect Geyser's NetherNet state (harmless): " + e.getMessage());
        }
    }

    /**
     * The candidate rewrite towards the shared port is driven by Geyser's
     * {@code advertise-addresses}; without it clients keep aiming at the
     * webrtc-port and need both UDP ports open.
     */
}
