/*
 * Copyright (c) 2026 EnderRealmMC
 * SPDX-License-Identifier: LGPL-3.0-only
 */

package cn.enderrealm.geyserbraid.relay;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.socket.DatagramPacket;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;

/**
 * One forwarding leg: a loopback datagram channel connected to libjuice's media
 * endpoint, carrying the traffic of exactly one client.
 *
 * <p>Frames of the path:</p>
 *
 * <pre>
 * inbound:  client → shared port → demultiplexer → leg → libjuice (loopback)
 * outbound: libjuice → leg → listener channel → client (source port stays the shared port)
 * </pre>
 *
 * <p>Datagrams that arrive before the leg's {@code connect()} completes are held
 * in {@link #pending} and written out by {@link #ready()}. A leg that goes idle
 * is closed by the relay's sweep task.</p>
 */
public final class Leg {

    final Channel listener;
    final InetSocketAddress client;
    Channel channel;
    /** PROXY protocol v2 header prepended to every datagram toward libjuice; null when framing is off. */
    byte[] proxyHeaderBytes;
    volatile long lastActivityNanos = System.nanoTime();

    private final MediaRelay relay;
    private final List<ByteBuf> pending = new ArrayList<>(4);
    private volatile boolean ready;

    /**
     * @param relay    the owning relay, which counts and sweeps legs
     * @param listener the listener channel replies are written back through
     * @param client   the real client address this leg serves
     */
    Leg(MediaRelay relay, Channel listener, InetSocketAddress client) {
        this.relay = relay;
        this.listener = listener;
        this.client = client;
    }

    /**
     * Queues or writes a datagram toward libjuice. Called on the listener's event
     * loop before {@code connect()} completes; everything arriving early is held
     * in {@link #pending}.
     *
     * @param payload the datagram content (ownership transferred to the leg)
     */
    void send(ByteBuf payload) {
        lastActivityNanos = System.nanoTime();
        if (!ready) {
            if (!channel.isOpen()) {
                payload.release();
                return;
            }
            pending.add(payload);
            return;
        }
        channel.write(frame(payload));
    }

    /** Wraps the payload with the leg's PROXY v2 header when framing is enabled. */
    private ByteBuf frame(ByteBuf payload) {
        if (proxyHeaderBytes == null) {
            return payload;
        }
        return io.netty.buffer.Unpooled.compositeBuffer(2)
                .addComponent(true, io.netty.buffer.Unpooled.wrappedBuffer(proxyHeaderBytes))
                .addComponent(true, payload);
    }

    /** Flushes the leg channel; a no-op while the leg is not ready yet. Called by the demultiplexer. */
    public void flush() {
        if (ready) {
            channel.flush();
        }
    }

    /**
     * Marks the leg ready and writes out everything that queued before the
     * {@code connect()} completed.
     */
    void ready() {
        ready = true;
        for (ByteBuf payload : pending) {
            channel.write(frame(payload));
        }
        pending.clear();
        channel.flush();
    }

    /** Releases datagrams still queued before readiness (called when the leg dies early). */
    void releasePending() {
        for (ByteBuf payload : pending) {
            payload.release();
        }
        pending.clear();
    }

    /** Closes the leg channel; the relay's close listener performs the bookkeeping. */
    void close() {
        channel.close();
    }

    /**
     * The reply handler on a leg: libjuice → leg → listener socket → client.
     * Replies are written back through the listener channel so the source port
     * stays the shared public port, keeping the client's 5-tuple consistent.
     */
    private final class LegHandler extends io.netty.channel.ChannelInboundHandlerAdapter {

        @Override
        public void channelRead(io.netty.channel.ChannelHandlerContext ctx, Object msg) {
            if (!(msg instanceof DatagramPacket packet)) {
                io.netty.util.ReferenceCountUtil.release(msg);
                return;
            }
            try {
                ByteBuf content = packet.content();
                lastActivityNanos = System.nanoTime();
                relay.handleLegReply(Leg.this, content);
            } finally {
                packet.release();
            }
        }

        @Override
        public void channelReadComplete(io.netty.channel.ChannelHandlerContext ctx) {
            listener.flush();
        }

        @Override
        public void exceptionCaught(io.netty.channel.ChannelHandlerContext ctx, Throwable cause) {
            if (cause instanceof java.net.PortUnreachableException) {
                // The media socket is gone (libjuice closed); this leg has nothing left to talk to
                Leg.this.close();
                return;
            }
            relay.logger().debug("GeyserBraid relay leg for " + client + " failed: " + cause);
        }
    }

    /**
     * Creates the handler instance installed on the leg channel; package-visible
     * so only the relay can wire legs up.
     */
    io.netty.channel.ChannelInboundHandlerAdapter newLegHandler() {
        return new LegHandler();
    }
}
