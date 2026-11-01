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

        try {
            List<Channel> listeners = RakNetListenerAccess.listeners();
            if (listeners.isEmpty()) {
                throw new IllegalStateException("no bound RakNet listener channels to mount the demultiplexer on");
            }

            // Geyser's ICE socket stays bound on the Bedrock address at the webrtc-port;
            // a wildcard bind includes the loopback, so relay legs can reach it there
            InetSocketAddress mediaEndpoint = new InetSocketAddress(InetAddress.getLoopbackAddress(), webrtcPort);
            MediaRelay relay = new MediaRelay(mediaEndpoint, MAX_LEGS);
            listeners.forEach(relay::attach);

            checkNetherNetSide(logger);
            if (bedrock.signaling().advertiseAddresses().isEmpty()) {
                // Mirror the shared port onto Geyser's dynamic addresses (STUN discovery
                // results, else the host's own interfaces) — zero configuration, works
                // on public hosts, behind NAT and on LAN-only test setups alike
                AdvertisedMirror.start(geyser, sharedPort);
            } else {
                logger.info(GeyserBraidExtension.LOG_PREFIX
                        + "\"advertise-addresses\" is set manually; the advertised-address mirror stays off.");
            }

            logger.info(GeyserBraidExtension.LOG_PREFIX + "Assembled: demultiplexer mounted on " + listeners.size()
                    + " listener channel(s) (UDP " + sharedPort + "), NetherNet media relayed to " + mediaEndpoint);
        } catch (Exception e) {
            // A failed assembly leaves Geyser's original listeners untouched; the
            // transports keep working in the vanilla dual-port layout
            logger.severe(GeyserBraidExtension.LOG_PREFIX
                    + "Assembly failed, single-port demultiplexing is NOT enabled (Geyser's original listeners are"
                    + " unaffected): " + e.getMessage(), e);
        }
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
