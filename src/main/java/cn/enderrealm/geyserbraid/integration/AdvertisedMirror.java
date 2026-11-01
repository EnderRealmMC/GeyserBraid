/*
 * Copyright (c) 2026 EnderRealmMC
 * SPDX-License-Identifier: LGPL-3.0-only
 */

package cn.enderrealm.geyserbraid.integration;

import cn.enderrealm.geyserbraid.GeyserBraidExtension;
import org.cloudburstmc.netty.channel.nethernet.signaling.NetherNetHTTPServerSignaling;
import org.geysermc.geyser.GeyserImpl;
import org.geysermc.geyser.GeyserLogger;
import org.geysermc.geyser.configuration.GeyserConfig;
import org.geysermc.geyser.network.bedrock.nethernet.NetherNetServer;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Keeps the built-in signaling's advertised list pointing at the shared port
 * while the address half stays whatever Geyser's own dynamic sources currently
 * say: the STUN discovery results when they are fresh, else this host's own
 * interface addresses.
 *
 * <p>Why reflection onto a volatile field works here: the library reads the
 * advertised list on every answer ({@code NetherNetHTTPServerSignaling#announced})
 * and writes it exactly once, at bind — after startup a reflective writer owns
 * the field without racing anything. Every advertised entry is emitted by the
 * library as a translated srflx candidate whose port comes from the entry
 * ({@code SdpUtil#translatedCandidates}: an entry port of 0 keeps the gathered
 * candidate's own port, any other port wins), so entries of the form
 * {@code <current address>:<shared port>} make clients check the shared UDP
 * port — where the demultiplexer and relay answer. The ICE host candidates on
 * the webrtc-port stay in the answer untouched and keep working whenever that
 * port is reachable.</p>
 *
 * <p>Consequences per network shape, with no configuration anywhere:</p>
 *
 * <ul>
 *     <li>public host — the STUN-discovered or interface address carries the shared port;</li>
 *     <li>behind NAT — the live STUN-discovered public mapping carries the shared
 *         port (port-forward the shared public UDP port to the webrtc-port);</li>
 *     <li>LAN-only testing — interface addresses carry the shared port, so a server
 *         without any public address still serves single-port on its LAN.</li>
 * </ul>
 *
 * <p>Disabled when the operator set "advertise-addresses" by hand: Geyser's own
 * static list wins and nothing is mirrored. The NXS signaling mode is not
 * mirrored either — its candidates are managed by the external service.</p>
 */
public final class AdvertisedMirror {

    /** How often the mirror re-reads Geyser's dynamic addresses. STUN results update on this cadence. */
    private static final long PERIOD_SECONDS = 10;
    /** Interface addresses kept per refresh; every candidate costs the client a connectivity check. */
    private static final int MAX_ADDRESSES = 4;

    private final GeyserLogger logger;
    private final NetherNetServer server;
    private final NetherNetHTTPServerSignaling signaling;
    private final int sharedPort;
    /** Set once the first successful write happened, so the announcement is logged exactly once. */
    private volatile boolean announced;

    private AdvertisedMirror(GeyserLogger logger, NetherNetServer server,
                             NetherNetHTTPServerSignaling signaling, int sharedPort) {
        this.logger = logger;
        this.server = server;
        this.signaling = signaling;
        this.sharedPort = sharedPort;
    }

    /**
     * Starts the mirror when the built-in signaling instance exists. Returns null
     * (with a log line) when there is nothing to mirror — most notably in the NXS
     * signaling mode, whose candidates the external service manages.
     *
     * @param geyser     the Geyser instance, read for the signaling config
     * @param sharedPort the shared public UDP port candidates should carry
     * @return the mirror's scheduled task, or null when the mirror is inactive
     */
    public static ScheduledFuture<?> start(GeyserImpl geyser, int sharedPort) {
        GeyserLogger logger = geyser.getLogger();
        try {
            Object value = ReflectAccess.getField(GeyserImpl.getInstance(), "netherNetServer");
            if (!(value instanceof NetherNetServer server)) {
                return null;
            }
            Object signaling = ReflectAccess.getField(server, "signaling");
            if (!(signaling instanceof NetherNetHTTPServerSignaling builtin)) {
                logger.warning(GeyserBraidExtension.LOG_PREFIX
                        + "No built-in signaling instance found; the advertised-address mirror stays off"
                        + " (with NXS signaling the candidates are managed by the external service).");
                return null;
            }
            AdvertisedMirror mirror = new AdvertisedMirror(logger, server, builtin, sharedPort);
            ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "GeyserBraid-AdvertiseMirror");
                thread.setDaemon(true);
                return thread;
            });
            // First pass shortly after assembly, then on the STUN refresh cadence
            return executor.scheduleWithFixedDelay(mirror::refresh, 2, PERIOD_SECONDS, TimeUnit.SECONDS);
        } catch (ReflectiveOperationException e) {
            logger.warning(GeyserBraidExtension.LOG_PREFIX
                    + "Could not start the advertised-address mirror: " + e.getMessage());
            return null;
        }
    }

    /**
     * One mirror pass: collects the current dynamic addresses, rewrites their port
     * to the shared port, and writes the list into the signaling instance when it
     * changed. Every failure is logged quietly and retried on the next pass.
     */
    private void refresh() {
        try {
            GeyserConfig.BedrockConfig bedrock = geyserConfig();
            if (!bedrock.signaling().advertiseAddresses().isEmpty()) {
                return; // manual configuration wins; nothing to mirror
            }
            if (server.advertised().builtin() == null) {
                return; // built-in signaling is not serving right now
            }
            List<InetSocketAddress> targets = currentEndpoints();
            if (targets.isEmpty()) {
                return;
            }
            Object current = ReflectAccess.getField(signaling, "advertisedAddresses");
            if (targets.equals(current)) {
                return;
            }
            ReflectAccess.setField(signaling, "advertisedAddresses", targets);
            if (!announced) {
                announced = true;
                logger.info(GeyserBraidExtension.LOG_PREFIX
                        + "Advertising NetherNet candidates on the shared port: " + targets);
            }
        } catch (Exception e) {
            // Quiet by design: the next pass retries, and the first success logs loudly
            logger.debug(GeyserBraidExtension.LOG_PREFIX + "Advertised mirror pass failed: " + e);
        }
    }

    /**
     * Collects the addresses to advertise: the live STUN discovery results when
     * present, else this host's own IPv4 interface addresses.
     */
    private List<InetSocketAddress> currentEndpoints() {
        List<InetAddress> bases = new ArrayList<>();
        NetherNetServer.Advertised.Builtin builtin = server.advertised().builtin();
        if (builtin != null) {
            for (InetSocketAddress discovered : builtin.discovered()) {
                bases.add(discovered.getAddress());
            }
        }
        if (bases.isEmpty()) {
            bases.addAll(interfaceAddresses());
        }
        // The ICE socket is IPv4-bound (0.0.0.0), so only IPv4 translations can work
        Set<InetSocketAddress> targets = new LinkedHashSet<>();
        for (InetAddress base : bases) {
            if (base instanceof Inet4Address && !base.isAnyLocalAddress() && !base.isLoopbackAddress()) {
                targets.add(new InetSocketAddress(base, sharedPort));
            }
        }
        return new ArrayList<>(targets);
    }

    /** The IPv4 addresses of every up, non-loopback interface, capped at {@value #MAX_ADDRESSES}. */
    private static List<InetAddress> interfaceAddresses() {
        List<InetAddress> addresses = new ArrayList<>();
        try {
            for (NetworkInterface networkInterface : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!networkInterface.isUp() || networkInterface.isLoopback()) {
                    continue;
                }
                for (InetAddress address : Collections.list(networkInterface.getInetAddresses())) {
                    if (address instanceof Inet4Address && !address.isLinkLocalAddress()
                            && addresses.size() < MAX_ADDRESSES) {
                        addresses.add(address);
                    }
                }
            }
        } catch (Exception e) {
            // Enumeration failures yield an empty list; the caller falls back quietly
        }
        return addresses;
    }

    /** Read fresh on every pass so config reloads are honored without restarting the mirror. */
    private GeyserConfig.BedrockConfig geyserConfig() {
        return GeyserImpl.getInstance().config().bedrock();
    }
}
