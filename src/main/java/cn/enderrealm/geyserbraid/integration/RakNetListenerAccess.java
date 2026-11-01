/*
 * Copyright (c) 2026 EnderRealmMC
 * SPDX-License-Identifier: LGPL-3.0-only
 */

package cn.enderrealm.geyserbraid.integration;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import org.geysermc.geyser.GeyserImpl;
import org.geysermc.geyser.network.RaknetServer;

import java.util.ArrayList;
import java.util.List;

/**
 * Read-only access to the internal state of Geyser's {@link RaknetServer}:
 * the list of listener {@link Channel}s already bound by {@code bind()} —
 * the Netty entry points of the UDP socket.
 *
 * <p>These channels are the mount points for the demultiplexer ({@code demux}).
 * {@code bootstrapFutures} is a private field holding every ChannelFuture
 * produced by {@code bind()}; with SO_REUSEPORT enabled
 * ({@code Geyser.ListenCount > 1}) the array holds more than one entry, and the
 * demultiplexer must be mounted on every one of them — otherwise NetherNet
 * traffic arriving on the unmounted channels leaks straight into RakNet.</p>
 */
final class RakNetListenerAccess {

    /** The field name in RaknetServer; the first thing a Geyser refactor is likely to change. */
    private static final String FUTURES_FIELD = "bootstrapFutures";
    /** The field name GeyserImpl holds the server under (older versions expose getGeyserServer(); both are tried). */
    private static final String SERVER_FIELD = "geyserServer";

    private RakNetListenerAccess() {
    }

    /**
     * Returns every bound RakNet listener channel.
     *
     * @return the listener channels (at least one; empty only if none bound successfully)
     * @throws ReflectiveOperationException Geyser's internals do not match the expected layout
     *                                      (incompatible version)
     */
    static List<Channel> listeners() throws ReflectiveOperationException {
        RaknetServer server = server();
        Object futures = ReflectAccess.getField(server, FUTURES_FIELD);
        if (!(futures instanceof ChannelFuture[] array)) {
            throw new IllegalStateException(FUTURES_FIELD + " is not a ChannelFuture[]: " + futures);
        }
        List<Channel> channels = new ArrayList<>(array.length);
        for (ChannelFuture future : array) {
            if (future != null && future.isSuccess()) {
                channels.add(future.channel());
            }
        }
        return channels;
    }

    /**
     * Returns the {@link RaknetServer} instance held by GeyserImpl. Prefers the
     * public getter (more stable across refactors) and falls back to the private
     * field via reflection.
     */
    private static RaknetServer server() throws ReflectiveOperationException {
        GeyserImpl geyser = GeyserImpl.getInstance();
        try {
            return (RaknetServer) ReflectAccess.invokeNoArg(geyser, "getGeyserServer");
        } catch (NoSuchMethodException e) {
            Object value = ReflectAccess.getField(geyser, SERVER_FIELD);
            if (!(value instanceof RaknetServer server)) {
                throw new IllegalStateException(SERVER_FIELD + " is not a RaknetServer: " + value);
            }
            return server;
        }
    }
}
