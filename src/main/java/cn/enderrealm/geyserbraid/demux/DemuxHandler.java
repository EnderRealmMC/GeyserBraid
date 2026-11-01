/*
 * Copyright (c) 2026 EnderRealmMC
 * SPDX-License-Identifier: LGPL-3.0-only
 */

package cn.enderrealm.geyserbraid.demux;

import cn.enderrealm.geyserbraid.relay.Leg;
import cn.enderrealm.geyserbraid.relay.MediaRelay;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.socket.DatagramPacket;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The inbound demultiplexer on the shared port: NetherNet datagrams go to the
 * relay, everything else passes through to RakNet untouched.
 *
 * <p>It must sit at the head of the listener pipeline (see {@link MediaRelay#attach}
 * for the PROXY protocol caveat). Legs written during one read loop are flushed
 * together in {@link #channelReadComplete} to amortize syscalls.</p>
 */
public final class DemuxHandler extends ChannelInboundHandlerAdapter {

    /** The handler name in the Netty pipeline; mounting and unmounting locate it by this name. */
    public static final String NAME = "geyserbraid-demux";

    private final MediaRelay relay;
    private final Channel listener;
    /** Legs written during the current read loop, flushed together on readComplete. Touched on the read loop only. */
    private final Set<Leg> touched = new LinkedHashSet<>();

    /**
     * @param relay    the owning relay, responsible for leg creation, forwarding and counters
     * @param listener the listener channel carrying this handler (the Netty entry of the UDP socket)
     */
    DemuxHandler(MediaRelay relay, Channel listener) {
        this.relay = relay;
        this.listener = listener;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (!(msg instanceof DatagramPacket packet)) {
            // Not a UDP datagram (unexpected on a listener channel); pass it on as usual
            ctx.fireChannelRead(msg);
            return;
        }
        PacketKind kind = Rfc7983.classify(packet.content());
        if (kind == PacketKind.OTHER) {
            // RakNet (and all noise) passes through untouched
            ctx.fireChannelRead(msg);
            return;
        }
        try {
            relay.forward(listener, packet.sender(), packet.content(), kind, touched);
        } finally {
            packet.release();
        }
    }

    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) {
        for (Leg leg : touched) {
            leg.flush();
        }
        touched.clear();
        ctx.fireChannelReadComplete();
    }
}
