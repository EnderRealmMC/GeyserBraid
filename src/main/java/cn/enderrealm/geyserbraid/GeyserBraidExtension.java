/*
 * Copyright (c) 2026 EnderRealmMC
 * SPDX-License-Identifier: LGPL-3.0-only
 */

package cn.enderrealm.geyserbraid;

import cn.enderrealm.geyserbraid.integration.BraidIntegrations;
import org.geysermc.geyser.api.event.lifecycle.GeyserPostInitializeEvent;
import org.geysermc.geyser.api.extension.Extension;

import org.geysermc.event.subscribe.Subscribe;

/**
 * GeyserBraid main entry point: single-port dual transport on top of Geyser's
 * {@code transport: both} mode. RakNet and NetherNet share one public UDP port,
 * with no additional configuration options.
 *
 * <p>The project is organized into three modules, assembled by
 * {@link BraidIntegrations#install()}:</p>
 *
 * <ul>
 *     <li>{@code demux} — the RFC 7983 first-byte demultiplexer, mounted at the head
 *         of every RakNet listener pipeline;</li>
 *     <li>{@code relay} — the loopback media relay, ferrying STUN/DTLS traffic to
 *         libjuice's ICE socket on the webrtc-port, reached over loopback;</li>
 *     <li>{@code integration} — reflective wiring into Geyser internals
 *         (the RakNet listener channels) plus startup sanity checks.</li>
 * </ul>
 *
 * <p>Client candidates are pointed at the shared port by Geyser's own
 * {@code advertise-addresses} option, which natively honors {@code address:port}
 * entries — no signaling interception is involved.</p>
 *
 * <p>The integration technique follows the precedent set by GeyserNetease: an
 * extension shell plus version-pinned integration into Geyser internals. All
 * reflection is confined to the {@code integration} package, which is the only
 * package that needs to be revisited when Geyser is upgraded.</p>
 */
public class GeyserBraidExtension implements Extension {

    /** Log prefix used by every module's console output. */
    public static final String LOG_PREFIX = "[GeyserBraid] ";

    /**
     * Invoked once Geyser finishes initializing. By this point Geyser's own
     * listeners are already bound; Braid only attaches to existing network
     * objects, it never restarts them.
     *
     * <p>Requires {@code transport: both}. Any other transport leaves no RakNet
     * listener channel to attach to, so the extension logs an error and installs
     * nothing rather than leaving a half-assembled state.</p>
     *
     * @param event the Geyser lifecycle event
     */
    @Subscribe
    public void onPostInitialize(GeyserPostInitializeEvent event) {
        var transport = org.geysermc.geyser.GeyserImpl.getInstance().config().bedrock().transport();
        if (!transport.raknet()) {
            logger().error(LOG_PREFIX + "\"transport\" must be \"both\" (current: " + transport.name().toLowerCase()
                    + "). Braid needs the RakNet listener channels and the built-in NetherNet signaling to exist "
                    + "side by side. The extension is NOT installed.");
            return;
        }
        BraidIntegrations.install();
    }
}
