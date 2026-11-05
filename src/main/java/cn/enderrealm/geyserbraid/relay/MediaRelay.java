/*
 * Copyright (c) 2026 EnderRealmMC
 * SPDX-License-Identifier: LGPL-3.0-only
 */

package cn.enderrealm.geyserbraid.relay;

import cn.enderrealm.geyserbraid.demux.DemuxHandler;
import cn.enderrealm.geyserbraid.demux.PacketKind;
import cn.enderrealm.geyserbraid.demux.Rfc7983;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFactory;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoop;
import io.netty.channel.epoll.EpollDatagramChannel;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.SocketProtocolFamily;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.channel.socket.DatagramPacket;
import io.netty.buffer.ByteBuf;
import org.cloudburstmc.netty.handler.codec.raknet.server.RakProxyServerHandler;
import org.geysermc.geyser.GeyserImpl;
import org.geysermc.geyser.GeyserLogger;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.PortUnreachableException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

/**
 * The loopback media relay: the heart of the shared-port mode.
 *
 * <p>libjuice (the WebRTC ICE implementation) is bound to a loopback address, so
 * it can neither receive traffic from the public port nor send to clients. This
 * relay bridges the gap:</p>
 *
 * <ul>
 *     <li>the demultiplexer ({@code DemuxHandler}, mounted on every RakNet
 *         listener) hands STUN/DTLS datagrams to {@link #forward};</li>
 *     <li>the first well-formed STUN request of a session opens a per-client
 *         {@link Leg} — a loopback channel connected to libjuice's media endpoint;</li>
 *     <li>replies coming back on a leg are written out through the listener
 *         channel ({@link #handleLegReply}), so the source port stays the shared
 *         public port and the client's 5-tuple never changes;</li>
 *     <li>a sweep task closes idle legs.</li>
 * </ul>
 *
 * <p>The leg gate is deliberately shallow: a plausibility check on the STUN
 * USERNAME (the ufrag registry of v1 needed to intercept signaling, which the
 * library forbids after a close). Real credential enforcement happens inside
 * libjuice — ICE short-term secrets — and leg limits plus the idle sweep bound
 * what hostile scanning can hold open.</p>
 */
public final class MediaRelay {

    /** Legs one client address may hold; small on purpose — a NAT behind one address is rare and bounded. */
    private static final int MAX_LEGS_PER_ADDRESS = 8;
    /** Idle legs are swept after this much inactivity; WebRTC consent checks keep live legs fresh. */
    private static final long LEG_IDLE_TTL_NANOS = TimeUnit.MINUTES.toNanos(5);
    /** How often the sweep task runs. */
    private static final long SWEEP_INTERVAL_MILLIS = 30_000;

    private final InetSocketAddress mediaEndpoint;
    private final int maxLegs;
    /** Whether legs frame their datagrams with a PROXY protocol v2 header (frp/HAProxy-style front). */
    private final boolean proxyFraming;

    private final LongAdder legsOpened = new LongAdder();
    private final LongAdder packetsIn = new LongAdder();
    private final LongAdder bytesIn = new LongAdder();
    private final LongAdder packetsOut = new LongAdder();
    private final LongAdder bytesOut = new LongAdder();
    private final LongAdder rejectedUfrag = new LongAdder();
    private final LongAdder rejectedLimit = new LongAdder();
    private final LongAdder droppedNoLeg = new LongAdder();
    private final LongAdder rejectedIpv6 = new LongAdder();

    private final Map<InetSocketAddress, Leg> legsByClient = new ConcurrentHashMap<>();
    private final Map<Integer, Leg> legsByLocalPort = new ConcurrentHashMap<>();
    private final Map<InetAddress, Integer> legsPerAddress = new ConcurrentHashMap<>();
    private final List<Channel> listeners = new ArrayList<>();
    private volatile ScheduledFuture<?> sweepTask;
    private volatile boolean closed;
    /** Warns once when a leg's PROXY framing is skipped over a non-IPv4 address; further skips stay debug. */
    private volatile boolean warnedFramingSkip;

    /**
     * @param mediaEndpoint the loopback address libjuice's ICE is bound on
     * @param maxLegs       the total number of legs allowed at once
     * @param proxyFraming  whether legs prepend a PROXY protocol v2 header per
     *                      datagram (requires the nethernet library to expect
     *                      headers from this relay, via the
     *                      {@code webrtc-proxy-protocol-relays} config)
     */
    public MediaRelay(InetSocketAddress mediaEndpoint, int maxLegs, boolean proxyFraming) {
        this.mediaEndpoint = mediaEndpoint;
        this.maxLegs = maxLegs;
        this.proxyFraming = proxyFraming;
    }

    /**
     * Mounts the demultiplexer on a listener channel.
     *
     * <p>Handlers added from off the event loop get their {@code handlerAdded}
     * deferred to a later event-loop task, and datagrams dispatched before it runs
     * skip the handler straight into RakNet's arms — so the mount is submitted to
     * the event loop and awaited.</p>
     *
     * <p>Position: when RakNet's PROXY protocol handling is on (a non-empty
     * {@code haproxy-protocol-whitelisted-ips}, say for an frp or HAProxy front),
     * the demultiplexer is mounted right after {@code rak-proxy-server-handler} so
     * it classifies the stripped payload and sees the physical sender — which is
     * the transport return path anyway: legs are keyed on it and replies are
     * written back to it, so an frp tunnel simply becomes part of the round trip.
     * Without PROXY handling the demultiplexer is mounted first, unchanged.</p>
     *
     * @param listener a bound RakNet listener channel
     */
    public void attach(Channel listener) {
        Runnable mount = () -> {
            ChannelPipeline pipeline = listener.pipeline();
            if (pipeline.get(DemuxHandler.NAME) != null) {
                return;
            }
            ChannelHandlerContext proxy = pipeline.context(RakProxyServerHandler.class);
            if (proxy != null) {
                pipeline.addAfter(proxy.name(), DemuxHandler.NAME, new DemuxHandler(this, listener));
            } else {
                pipeline.addFirst(DemuxHandler.NAME, new DemuxHandler(this, listener));
            }
        };
        if (listener.eventLoop().inEventLoop()) {
            mount.run();
        } else {
            listener.eventLoop().submit(mount).syncUninterruptibly();
        }
        listeners.add(listener);
        if (sweepTask == null) {
            EventLoop loop = listener.eventLoop();
            sweepTask = loop.scheduleAtFixedRate(this::sweep,
                    SWEEP_INTERVAL_MILLIS, SWEEP_INTERVAL_MILLIS, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * @return the loopback address libjuice's ICE is bound on
     */
    public InetSocketAddress mediaEndpoint() {
        return mediaEndpoint;
    }

    /**
     * @return an immutable snapshot of the relay counters
     */
    public RelaySnapshot snapshot() {
        return new RelaySnapshot(this.legsByClient.size(), this.legsOpened.sum(), this.packetsIn.sum(), this.bytesIn.sum(),
                this.packetsOut.sum(), this.bytesOut.sum(), this.rejectedUfrag.sum(), this.rejectedLimit.sum(),
                this.droppedNoLeg.sum(), this.rejectedIpv6.sum());
    }

    /** One relay leg as an operator sees it: who it serves and how fresh its traffic is. */
    public record LegView(InetSocketAddress client, long idleSeconds, boolean ready) {
    }

    /**
     * A view of every open leg, one entry per client. Safe from any thread: the
     * underlying map is concurrent and each read is independent.
     */
    public List<LegView> legViews() {
        long now = System.nanoTime();
        List<LegView> views = new ArrayList<>(this.legsByClient.size());
        for (Leg leg : this.legsByClient.values()) {
            views.add(new LegView(leg.client,
                    TimeUnit.NANOSECONDS.toSeconds(now - leg.lastActivityNanos),
                    leg.channel.isActive()));
        }
        return views;
    }

    /**
     * Handles one datagram classified as NetherNet traffic: forwards it into the
     * client's leg, opening the leg on a whitelisted ufrag when needed.
     *
     * <p>Only STUN requests may open legs; DTLS and later traffic simply count as
     * dropped when no leg exists yet (the STUN check always comes first on a
     * healthy flow).</p>
     *
     * <p>The relay is IPv4-only, matching Geyser's default {@code 0.0.0.0} bind
     * where IPv6 cannot arrive in the first place: datagrams from IPv6 sources
     * (only possible when the operator rebinds Geyser onto {@code ::}) are
     * skipped, leaving such clients with vanilla behavior — the ICE host
     * candidates on the webrtc-port, reachable or not. RakNet traffic of any
     * family never reaches this method.</p>
     *
     * @param listener the listener the datagram arrived on (the new leg inherits its event loop)
     * @param client   the real client address
     * @param content  the datagram content (not released here; ownership stays with the caller)
     * @param kind     the classification verdict, never {@link PacketKind#OTHER}
     * @param touched  legs written during the current read loop, flushed together by the demultiplexer
     */
    public void forward(Channel listener, InetSocketAddress client, ByteBuf content, PacketKind kind,
                        java.util.Set<Leg> touched) {
        if (!(client.getAddress() instanceof Inet4Address)) {
            rejectedIpv6.increment();
            return;
        }
        Leg leg = legsByClient.get(client);
        if (leg == null) {
            if (kind != PacketKind.STUN) {
                droppedNoLeg.increment();
                return;
            }
            String ufrag = Rfc7983.localUfrag(content);
            if (ufrag == null || ufrag.length() < 4 || ufrag.length() > 255) {
                // Not a plausible ICE check; libjuice enforces the real credentials
                // (ICE short-term secrets) for whatever still gets through
                rejectedUfrag.increment();
                return;
            }
            leg = openLeg(listener, client);
            if (leg == null) {
                rejectedLimit.increment();
                return;
            }
        }
        packetsIn.increment();
        bytesIn.add(content.readableBytes());
        leg.send(content.retain());
        touched.add(leg);
    }

    /**
     * Handles a reply arriving on a leg from libjuice: writes it back to the real
     * client through the listener channel, keeping the shared port as source.
     *
     * @param leg     the leg the reply arrived on
     * @param content the reply datagram content (ownership stays with the caller)
     */
    void handleLegReply(Leg leg, ByteBuf content) {
        packetsOut.increment();
        bytesOut.add(content.readableBytes());
        leg.listener.write(new DatagramPacket(content.retain(), leg.client));
    }

    /**
     * Creates and wires a new leg for a client: binds a loopback channel on the
     * listener's event loop, connects it to the media endpoint, and registers it.
     *
     * @return the leg, or null when the relay is closing or a limit was hit
     */
    /**
     * PROXY protocol v2 header for one datagram from {@code client} toward the
     * media endpoint: the nethernet library reads it to learn the real player
     * address instead of the relay leg's loopback address. IPv4 only — the
     * header format is AF_INET, so {@link #openLeg} guarantees both endpoints
     * are IPv4 (IPv6 clients never open legs; a non-IPv4 identity skips
     * framing entirely rather than emitting a corrupt frame).
     */
    private static byte[] proxyV2Header(InetSocketAddress client, InetSocketAddress target) {
        byte[] header = new byte[28];
        header[0] = 0x0D; header[1] = 0x0A; header[2] = 0x0D; header[3] = 0x0A;
        header[4] = 0x00; header[5] = 0x0D; header[6] = 0x0A;
        header[7] = 0x51; header[8] = 0x55; header[9] = 0x49; header[10] = 0x54; header[11] = 0x0A;
        header[12] = 0x21;                   // version 2, command PROXY
        header[13] = 0x12;                   // AF_INET, DGRAM
        header[14] = 0x00; header[15] = 12;  // address section length
        byte[] src = client.getAddress().getAddress();
        byte[] dst = target.getAddress().getAddress();
        System.arraycopy(src, src.length - 4, header, 16, 4);
        System.arraycopy(dst, dst.length - 4, header, 20, 4);
        header[24] = (byte) (client.getPort() >> 8);
        header[25] = (byte) client.getPort();
        header[26] = (byte) (target.getPort() >> 8);
        header[27] = (byte) target.getPort();
        return header;
    }

    private Leg openLeg(Channel listener, InetSocketAddress client) {
        if (closed || legsByClient.size() >= maxLegs
                || legsPerAddress.getOrDefault(client.getAddress(), 0) >= MAX_LEGS_PER_ADDRESS) {
            return null;
        }

        Leg leg = new Leg(this, listener, client);
        if (proxyFraming) {
            // The header is constant per leg: same client, same destination.
            // The embedded source is the player's real address — resolved through
            // the RakNet PROXY mapping when a frp/HAProxy front is in play — so
            // the nethernet library reports the player, not the relay front.
            InetSocketAddress identity = (listener instanceof org.cloudburstmc.netty.channel.raknet.RakServerChannel rakServer)
                    ? rakServer.getClientAddress(client) : client;
            InetSocketAddress target = (InetSocketAddress) listener.localAddress();
            if (identity.getAddress() instanceof Inet4Address && target.getAddress() instanceof Inet4Address) {
                leg.proxyHeaderBytes = proxyV2Header(identity, target);
            } else {
                // The header format is IPv4-only; a wrong-family source would be
                // emitted as a corrupt AF_INET frame with a silently garbage
                // address, so skip the framing instead and let the leg send raw
                if (warnedFramingSkip) {
                    logger().debug("GeyserBraid skipping PROXY framing for the leg of " + client
                            + ": a non-IPv4 address is involved");
                } else {
                    warnedFramingSkip = true;
                    logger().warning("GeyserBraid skipping PROXY framing for the leg of " + client
                            + ": a non-IPv4 address is involved (the player's real address will not reach Geyser)");
                }
            }
        }
        // The loopback may be ::1 under preferIPv6Addresses; the leg must match its family to bind/connect
        SocketProtocolFamily family = mediaEndpoint.getAddress() instanceof Inet6Address
                ? SocketProtocolFamily.INET6 : SocketProtocolFamily.INET;
        ChannelFactory<? extends DatagramChannel> factory = listener instanceof EpollDatagramChannel
                ? () -> new EpollDatagramChannel(family)
                : () -> new NioDatagramChannel(family);
        ChannelFuture bound = new Bootstrap()
                .group(listener.eventLoop())
                .channelFactory(factory)
                .handler(new ChannelInitializer<Channel>() {
                    @Override
                    protected void initChannel(Channel channel) {
                        channel.pipeline().addLast(leg.newLegHandler());
                    }
                })
                .bind(new InetSocketAddress(family == SocketProtocolFamily.INET6 ? "::1" : "127.0.0.1", 0));
        leg.channel = bound.channel();
        // Registration on the listener's loop is synchronous; an unregistered channel means
        // creation failed (fd exhaustion, say) and a closeFuture listener must not be attached
        if (!leg.channel.isRegistered()) {
            logger().warning("GeyserBraid unable to create a NetherNet relay leg for " + client
                    + (bound.cause() == null ? "" : ": " + bound.cause()));
            leg.channel.close();
            return null;
        }
        legsPerAddress.merge(client.getAddress(), 1, Integer::sum);
        legsByClient.put(client, leg);
        legsOpened.increment();
        leg.channel.closeFuture().addListener(future -> onLegClosed(leg));

        bound.addListener(bind -> {
            if (!bind.isSuccess()) {
                logger().warning("GeyserBraid unable to open a NetherNet relay leg for " + client
                        + ": " + bind.cause());
                leg.close();
                return;
            }
            InetSocketAddress local = (InetSocketAddress) leg.channel.localAddress();
            legsByLocalPort.put(local.getPort(), leg);
            leg.channel.connect(mediaEndpoint).addListener(connect -> {
                if (!connect.isSuccess()) {
                    logger().warning("GeyserBraid unable to connect a NetherNet relay leg to " + mediaEndpoint
                            + ": " + connect.cause());
                    leg.close();
                    return;
                }
                leg.ready();
            });
        });
        return leg;
    }

    /** Removes a closed leg from the bookkeeping and releases anything still queued. */
    private void onLegClosed(Leg leg) {
        legsByClient.remove(leg.client, leg);
        if (leg.channel.localAddress() instanceof InetSocketAddress local) {
            legsByLocalPort.remove(local.getPort(), leg);
        }
        legsPerAddress.computeIfPresent(leg.client.getAddress(), (address, held) -> held <= 1 ? null : held - 1);
        leg.releasePending();
    }

    /** Closes idle legs and expires stale ufrag credentials. Runs on a listener event loop. */
    private void sweep() {
        long now = System.nanoTime();
        for (Leg leg : legsByClient.values()) {
            if (now - leg.lastActivityNanos > LEG_IDLE_TTL_NANOS) {
                leg.close();
            }
        }
    }

    /**
     * Shuts the relay down: unmounts every demultiplexer, closes all legs, drops
     * the sweep task and clears credentials.
     */
    public void shutdown() {
        closed = true;
        ScheduledFuture<?> task = sweepTask;
        if (task != null) {
            task.cancel(false);
        }
        for (Channel listener : listeners) {
            ChannelPipeline pipeline = listener.pipeline();
            if (pipeline.get(DemuxHandler.NAME) != null) {
                try {
                    pipeline.remove(DemuxHandler.NAME);
                } catch (Exception ignored) {
                    // the listener channel is already on its way down
                }
            }
        }
        listeners.clear();
        for (Leg leg : new ArrayList<>(legsByClient.values())) {
            leg.close();
        }
    }

    /** The Geyser logger, shared by the relay and its legs. */
    GeyserLogger logger() {
        return GeyserImpl.getInstance().getLogger();
    }
}
