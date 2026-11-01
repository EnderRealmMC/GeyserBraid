/*
 * Copyright (c) 2026 EnderRealmMC
 * SPDX-License-Identifier: LGPL-3.0-only
 */

package cn.enderrealm.geyserbraid.integration;

import cn.enderrealm.geyserbraid.GeyserBraidExtension;
import cn.enderrealm.geyserbraid.relay.MediaRelay;
import cn.enderrealm.geyserbraid.signaling.SdpRewriter;
import cn.enderrealm.geyserbraid.signaling.SharedPortSignaling;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import org.cloudburstmc.netty.channel.nethernet.NetherNetChannelFactory;
import org.cloudburstmc.netty.channel.nethernet.config.NetherChannelOption;
import org.cloudburstmc.netty.channel.nethernet.signaling.NetherNetHTTPServerSignaling;
import org.cloudburstmc.netty.util.nethernet.TrustedProxies;
import org.geysermc.geyser.GeyserImpl;
import org.geysermc.geyser.GeyserLogger;
import org.geysermc.geyser.configuration.GeyserConfig;
import org.geysermc.geyser.network.bedrock.nethernet.NetherNetServer;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;

/**
 * The assembler: wires Braid's modules onto Geyser's already-running network
 * stack.
 *
 * <p>Assembly runs after {@code GeyserPostInitializeEvent}, when Geyser's own
 * listeners are already bound. It does exactly three things:</p>
 *
 * <ol>
 *     <li>Fetches the RakNet listener channels via {@link RakNetListenerAccess}
 *         and mounts the demultiplexer on each of them;</li>
 *     <li>Reflects Geyser's built-in signaling instance out of
 *         {@code NetherNetServer}, wraps it via {@link MediaRelay#decorate}, and
 *         has {@link NetherNetRebinder} re-bind the listener on the same
 *         signaling port with ICE moved to a loopback address;</li>
 *     <li>Logs the assembled layout.</li>
 * </ol>
 *
 * <p>Every reflection failure ends in a clear "incompatible Geyser version"
 * error with nothing installed. A half-assembled state — demultiplexer mounted
 * but signaling not rebuilt, or the reverse — would break both transports and
 * is strictly worse than not assembling at all.</p>
 */
public final class BraidIntegrations {

    /** Upper bound on relay legs. Far above any normal server's concurrency; it caps hostile scanning. */
    private static final int MAX_LEGS = 1024;

    private BraidIntegrations() {
    }

    /**
     * Performs the full assembly. Any failed step aborts the remaining ones.
     *
     * <p>Port semantics match Geyser's own startup resolution: a
     * {@code webrtc-port} of 0 follows the Bedrock port (valid when NetherNet
     * owns the UDP side alone), and the signaling port has already been
     * resolved to a concrete value by Geyser at startup.</p>
     */
    public static void install() {
        GeyserImpl geyser = GeyserImpl.getInstance();
        GeyserLogger logger = geyser.getLogger();
        GeyserConfig.BedrockConfig bedrock = geyser.config().bedrock();
        int sharedPort = bedrock.port();
        int webrtcPort = bedrock.webrtcPort() == 0 ? sharedPort : bedrock.webrtcPort();
        int signalingPort = bedrock.signaling().port();

        try {
            List<Channel> listeners = RakNetListenerAccess.listeners();
            if (listeners.isEmpty()) {
                throw new IllegalStateException("no bound RakNet listener channels to mount the demultiplexer on");
            }

            // Hide libjuice's ICE on loopback: only relay legs can reach it, the public internet cannot
            InetSocketAddress mediaEndpoint = new InetSocketAddress(InetAddress.getLoopbackAddress(), webrtcPort);
            MediaRelay relay = new MediaRelay(mediaEndpoint, MAX_LEGS);
            listeners.forEach(relay::attach);

            // Wrap the signaling Geyser already built: strip offer candidates,
            // rewrite answers, register local ufrags as relay credentials
            NetherNetServer netherNetServer = NetherNetRebinder.netherNetServer();
            NetherNetHTTPServerSignaling original =
                    (NetherNetHTTPServerSignaling) ReflectAccess.getField(netherNetServer, "signaling");
            SharedPortSignaling wrapped = relay.decorate(original,
                    sdp -> SdpRewriter.rewriteAnswer(sdp, webrtcPort,
                            SdpRewriter.publishedAddresses(bedrock.address()), sharedPort));

            Channel channel = NetherNetRebinder.rebind(geyser, wrapped, mediaEndpoint, signalingPort);

            logger.info(GeyserBraidExtension.LOG_PREFIX + "Assembled: demultiplexer mounted on " + listeners.size()
                    + " listener channel(s) (UDP " + sharedPort + "), built-in signaling re-bound (TCP "
                    + signalingPort + "), ICE moved to " + mediaEndpoint + ", new channel " + channel.localAddress());
        } catch (Exception e) {
            // A failed assembly means the single-port feature is unavailable as a whole;
            // Geyser's original both-mode listeners keep working on separate ports
            logger.severe(GeyserBraidExtension.LOG_PREFIX
                    + "Assembly failed, single-port dual transport is NOT enabled (Geyser's original listeners are "
                    + "unaffected; you can fall back to separate ports): " + e.getMessage(), e);
        }
    }
}
