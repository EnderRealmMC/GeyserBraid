/*
 * Copyright (c) 2026 EnderRealmMC
 * SPDX-License-Identifier: LGPL-3.0-only
 */

package cn.enderrealm.geyserbraid.command;

import cn.enderrealm.geyserbraid.GeyserBraidExtension;
import cn.enderrealm.geyserbraid.integration.AdvertisedMirror;
import cn.enderrealm.geyserbraid.integration.BraidIntegrations;
import cn.enderrealm.geyserbraid.relay.MediaRelay;
import cn.enderrealm.geyserbraid.relay.RelaySnapshot;
import org.cloudburstmc.netty.channel.nethernet.NetherNetChildChannel;
import org.cloudburstmc.netty.channel.raknet.RakChildChannel;
import org.geysermc.geyser.GeyserImpl;
import org.geysermc.geyser.api.command.CommandSource;
import org.geysermc.geyser.session.GeyserSession;

import java.util.List;
import java.util.Locale;

/**
 * Handlers for the /braid command tree: operator status and diagnostics for
 * the single-port dual transport.
 *
 * <p>The command tree stems from the extension's root command ({@code /braid});
 * each handler below is registered as its own subcommand carrying its own
 * {@code braid.command.<name>} permission, and the framework adds a help
 * subcommand listing all of them automatically.</p>
 *
 * <p>Output is operator-facing and may contain player addresses; keep the
 * {@code braid.command.*} permissions out of untrusted hands.</p>
 */
public final class BraidCommand {

    private BraidCommand() {
    }

    /** One-screen summary: assembly state, ports, mirror and relay counters. */
    public static void status(CommandSource source) {
        MediaRelay relay = BraidIntegrations.relay();
        if (!BraidIntegrations.assembled() || relay == null) {
            source.sendMessage("GeyserBraid is NOT assembled (assembly failed or shut down) - check the startup log.");
            return;
        }
        RelaySnapshot snap = relay.snapshot();
        source.sendMessage("GeyserBraid " + braidVersion() + " - assembled on shared UDP port "
                + BraidIntegrations.sharedPort());
        source.sendMessage("  Signaling: TCP " + BraidIntegrations.signalingPort()
                + " | ICE (internal): UDP " + BraidIntegrations.webrtcPort());
        source.sendMessage("  Mirror: " + mirrorState());
        source.sendMessage("  Relay legs: " + snap.legs() + " active (" + snap.legsOpened() + " opened total)");
        source.sendMessage("  Media: " + snap.packetsIn() + " in / " + snap.packetsOut() + " out ("
                + formatBytes(snap.bytesIn()) + " / " + formatBytes(snap.bytesOut()) + ")");
        source.sendMessage("  Rejected: " + snap.rejectedUfrag() + " bad ufrag, " + snap.rejectedLimit()
                + " over limit, " + snap.droppedNoLeg() + " without a leg, " + snap.rejectedIpv6() + " IPv6 skipped");
    }

    /** Online players grouped by the transport they arrived on. */
    public static void list(CommandSource source) {
        List<GeyserSession> sessions = GeyserImpl.getInstance().onlineConnections();
        if (sessions.isEmpty()) {
            source.sendMessage("No Bedrock players are online.");
            return;
        }
        source.sendMessage("Online Bedrock players: " + sessions.size());
        for (GeyserSession session : sessions) {
            var channel = session.getUpstream().getSession().getPeer().getChannel();
            if (channel instanceof NetherNetChildChannel netherNet) {
                var reported = netherNet.reportedAddress();
                source.sendMessage("  [NetherNet] " + session.name() + " - " + reported.address()
                        + " (via " + reported.source().name() + ")");
            } else if (channel instanceof RakChildChannel rak) {
                source.sendMessage("  [RakNet] " + session.name() + " - " + rak.remoteAddress());
            } else {
                source.sendMessage("  [Unknown transport] " + session.name() + " - " + channel.remoteAddress());
            }
        }
    }

    /** One line per open relay leg, for per-player media diagnostics. */
    public static void legs(CommandSource source) {
        MediaRelay relay = BraidIntegrations.relay();
        if (relay == null) {
            source.sendMessage("GeyserBraid is not assembled.");
            return;
        }
        var views = relay.legViews();
        if (views.isEmpty()) {
            source.sendMessage("No relay legs are open (no NetherNet clients are connected through the relay).");
            return;
        }
        source.sendMessage("Relay legs: " + views.size());
        for (var view : views) {
            source.sendMessage("  " + view.client() + " - idle " + view.idleSeconds() + "s, "
                    + (view.ready() ? "ready" : "connecting"));
        }
    }

    /** The addresses currently advertised to joining clients. */
    public static void advertise(CommandSource source) {
        var manual = GeyserImpl.getInstance().config().bedrock().signaling().advertiseAddresses();
        if (!manual.isEmpty()) {
            source.sendMessage("Manual advertise-addresses override is active: " + manual);
        }
        AdvertisedMirror mirror = BraidIntegrations.mirror();
        if (mirror == null) {
            source.sendMessage("The advertised-address mirror is not running.");
            return;
        }
        var advertised = mirror.currentAdvertised();
        if (advertised.isEmpty()) {
            source.sendMessage("No addresses advertised yet (the mirror writes after its first refresh pass).");
        } else {
            source.sendMessage("Advertised NetherNet candidates:");
            advertised.forEach(address -> source.sendMessage("  " + address.getHostString() + ":" + address.getPort()));
        }
    }

    /** Extension version plus the detected Geyser build, Paper-style. */
    public static void version(CommandSource source) {
        source.sendMessage("This server is running GeyserBraid version " + braidVersion());
        source.sendMessage("This proxy is running Geyser version " + GeyserImpl.VERSION);
    }

    /**
     * The mirror's operational state as a one-liner: a manual override wins,
     * then the running mirror with its current list, then inactive.
     */
    private static String mirrorState() {
        var manual = GeyserImpl.getInstance().config().bedrock().signaling().advertiseAddresses();
        if (!manual.isEmpty()) {
            return "manual advertise-addresses override";
        }
        AdvertisedMirror mirror = BraidIntegrations.mirror();
        if (mirror == null) {
            return "inactive";
        }
        var advertised = mirror.currentAdvertised();
        return advertised.isEmpty() ? "running, no addresses advertised yet" : "advertising on " + advertised;
    }

    /** The extension version from the jar manifest, or "unknown" for unpacked builds. */
    private static String braidVersion() {
        Package pkg = GeyserBraidExtension.class.getPackage();
        return pkg != null && pkg.getImplementationVersion() != null
                ? pkg.getImplementationVersion() : "unknown";
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
        }
        return String.format(Locale.ROOT, "%.2f MB", bytes / (1024.0 * 1024.0));
    }
}
