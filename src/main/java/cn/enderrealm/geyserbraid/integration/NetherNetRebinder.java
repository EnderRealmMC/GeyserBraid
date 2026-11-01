/*
 * Copyright (c) 2026 EnderRealmMC
 * SPDX-License-Identifier: LGPL-3.0-only
 */

package cn.enderrealm.geyserbraid.integration;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import org.cloudburstmc.netty.channel.nethernet.NetherNetChannelFactory;
import org.cloudburstmc.netty.channel.nethernet.config.NetherChannelOption;
import org.cloudburstmc.netty.util.nethernet.TrustedProxies;
import org.geysermc.geyser.GeyserImpl;
import org.geysermc.geyser.network.bedrock.nethernet.NetherNetServer;

import java.net.InetSocketAddress;

/**
 * Re-builds Geyser's built-in NetherNet signaling listener: closes the original
 * channel, re-binds on the same TCP signaling port with the wrapped signaling,
 * and moves ICE (libjuice) to the loopback media port.
 *
 * <p>Geyser's {@code NetherNetServer.startInbuilt()} bakes the signaling object
 * into the channel factory, so the SDP cannot be rewritten at runtime; this
 * class therefore closes the original channel and re-binds in place. Everything
 * else is reused from Geyser itself — the signaling instance (carrying the
 * operator identity, HTTPS settings and MOTD provider), the channel initializer,
 * and the event loop group. This class only closes, wraps and re-binds.</p>
 *
 * <p>Known risk: re-binding an {@code NetherNetHTTPServerSignaling} instance to a
 * second channel is not verified at the library level. If the re-bind fails, the
 * assembler falls back as a whole (see the failure handling in
 * {@code BraidIntegrations#install}).</p>
 */
final class NetherNetRebinder {

    private NetherNetRebinder() {
    }

    /**
     * Returns the {@link NetherNetServer} instance held by GeyserImpl.
     *
     * @throws ReflectiveOperationException the field does not exist (incompatible Geyser version)
     */
    static NetherNetServer netherNetServer() throws ReflectiveOperationException {
        Object value = ReflectAccess.getField(GeyserImpl.getInstance(), "netherNetServer");
        if (!(value instanceof NetherNetServer server)) {
            throw new IllegalStateException("netherNetServer is not a NetherNetServer: " + value);
        }
        return server;
    }

    /**
     * Closes Geyser's original built-in signaling channel and re-binds on the
     * same signaling port: the TCP side keeps serving signaling, and ICE binds
     * to the loopback media address via {@code NETHER_SERVER_ICE_ADDRESS}.
     *
     * @param geyser        the Geyser instance, read for advanced settings (HAProxy whitelist etc.)
     * @param wrapped       the wrapped signaling (original instance + offer/answer interception)
     * @param loopbackMedia the loopback media address libjuice's ICE should bind
     * @param signalingPort the signaling TCP port (already resolved during Geyser startup)
     * @return the new built-in signaling channel
     * @throws ReflectiveOperationException Geyser's internals do not match the expected layout
     * @throws IllegalStateException        the original channel does not exist — e.g. with
     *                                      {@code transport: both} and no {@code webrtc-port} set,
     *                                      Geyser's NetherNet side gave up at startup and there
     *                                      is nothing to re-bind
     */
    static Channel rebind(GeyserImpl geyser, Object wrapped, InetSocketAddress loopbackMedia, int signalingPort)
            throws ReflectiveOperationException {
        NetherNetServer server = netherNetServer();

        Channel old = (Channel) ReflectAccess.getField(server, "inbuiltChannel");
        if (old == null) {
            throw new IllegalStateException("Geyser's built-in NetherNet signaling did not start"
                    + " (with \"both\" transport, \"webrtc-port\" must be set explicitly and must not"
                    + " collide with the RakNet port)");
        }
        // The original channel carries both the signaling TCP and the ICE UDP side;
        // it is closed here and re-bound immediately below, leaving a millisecond-scale gap
        old.close().syncUninterruptibly();

        // Reuse everything Geyser created: initializer, event loop group, and the
        // original signaling instance entering the factory through the wrapper
        ChannelInitializer<?> initialiser =
                (ChannelInitializer<?>) ReflectAccess.getField(server, "inbuiltInitialiser");
        EventLoopGroup group = (EventLoopGroup) ReflectAccess.getField(server, "inbuiltEventLoopGroup");

        var advanced = geyser.config().advanced().bedrock();
        ServerBootstrap bootstrap = new ServerBootstrap()
                .group(group)
                .channelFactory(NetherNetChannelFactory.server(wrapped))
                .option(NetherChannelOption.NETHER_SERVER_PROXY_PROTOCOL,
                        TrustedProxies.parse(advanced.haproxyProtocolWhitelistedIps()))
                .option(NetherChannelOption.NETHER_SERVER_PREFER_PROXIED_ADDRESS,
                        advanced.preferSignalingProxyAddress())
                .option(NetherChannelOption.NETHER_SERVER_ICE_ADDRESS, loopbackMedia)
                .childHandler(initialiser);

        String address = geyser.config().bedrock().address();
        Channel channel = bootstrap.bind(new InetSocketAddress(address, signalingPort)).sync().channel();
        // Write the channel back: an active inbuiltChannel is the precondition for
        // NetherNetServer's own status reporting (advertised())
        ReflectAccess.setField(server, "inbuiltChannel", channel);
        return channel;
    }
}
