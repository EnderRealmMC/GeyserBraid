/*
 * Copyright (c) 2026 EnderRealmMC
 * SPDX-License-Identifier: LGPL-3.0-only
 */

package cn.enderrealm.geyserbraid.relay;

import cn.enderrealm.geyserbraid.demux.DemuxHandler;
import cn.enderrealm.geyserbraid.demux.PacketKind;
import cn.enderrealm.geyserbraid.demux.Rfc7983;
import cn.enderrealm.geyserbraid.security.UfragRegistry;
import cn.enderrealm.geyserbraid.signaling.SharedPortSignaling;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFactory;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoop;
import io.netty.channel.epoll.EpollDatagramChannel;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.SocketProtocolFamily;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.channel.socket.DatagramPacket;
import io.netty.buffer.ByteBuf;
import org.cloudburstmc.netty.channel.nethernet.signaling.NetherNetHTTPServerSignaling;
import org.geysermc.geyser.GeyserImpl;
import org.geysermc.geyser.GeyserLogger;

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
import java.util.function.UnaryOperator;

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
 *     <li>the first STUN request of a session opens a per-client {@link Leg} — a
 *         loopback channel connected to libjuice's media endpoint — gated by the
 *         {@link UfragRegistry} whitelist;</li>
 *     <li>replies coming back on a leg are written out through the listener
 *         channel ({@link #handleLegReply}), so the source port stays the shared
 *         public port and the client's 5-tuple never changes;</li>
 *     <li>a sweep task closes idle legs and expires stale ufrag credentials.</li>
 * </ul>
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
    private final UfragRegistry ufrags = new UfragRegistry();

    private final LongAdder legsOpened = new LongAdder();
    private final LongAdder packetsIn = new LongAdder();
    private final LongAdder bytesIn = new LongAdder();
    private final LongAdder packetsOut = new LongAdder();
    private final LongAdder bytesOut = new LongAdder();
    private final LongAdder rejectedUfrag = new LongAdder();
    private final LongAdder rejectedLimit = new LongAdder();
    private final LongAdder droppedNoLeg = new LongAdder();

    private final Map<InetSocketAddress, Leg> legsByClient = new ConcurrentHashMap<>();
    private final Map<Integer, Leg> legsByLocalPort = new ConcurrentHashMap<>();
    private final Map<InetAddress, Integer> legsPerAddress = new ConcurrentHashMap<>();
    private final List<Channel> listeners = new ArrayList<>();
    private volatile ScheduledFuture<?> sweepTask;
    private volatile boolean closed;

    /**
     * @param mediaEndpoint the loopback address libjuice's ICE is bound on
     * @param maxLegs       the total number of legs allowed at once
     */
    public MediaRelay(InetSocketAddress mediaEndpoint, int maxLegs) {
        this.mediaEndpoint = mediaEndpoint;
        this.maxLegs = maxLegs;
    }

    /**
     * Mounts the demultiplexer on a listener channel.
     *
     * <p>Handlers added from off the event loop get their {@code handlerAdded}
     * deferred to a later event-loop task, and datagrams dispatched before it runs
     * skip the handler straight into RakNet's arms — so the mount is submitted to
     * the event loop and awaited.</p>
     *
     * <p>Caveat: with RakNet's PROXY protocol enabled, the demultiplexer should
     * sit after the proxy handler so the sender is the real client (and replies
     * get mapped back to the proxy). Locating that handler by type is deferred
     * until first verified against Geyser's pipeline; until then the handler is
     * mounted first unconditionally, and the PROXY protocol + shared-port
     * combination is not yet supported.</p>
     *
     * @param listener a bound RakNet listener channel
     */
    public void attach(Channel listener) {
        Runnable mount = () -> {
            ChannelPipeline pipeline = listener.pipeline();
            if (pipeline.get(DemuxHandler.NAME) == null) {
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
     * Wraps a signaling instance so offers lose their candidates (a loopback
     * socket cannot reach them) and answers are rewritten to advertise the shared
     * port; every answered session registers its local ufrag as a relay
     * credential.
     *
     * @param delegate       the signaling instance built by Geyser
     * @param answerRewriter applied to every answer SDP leaving the server
     * @return the wrapped signaling for the re-built channel factory
     */
    public SharedPortSignaling decorate(NetherNetHTTPServerSignaling delegate, UnaryOperator<String> answerRewriter) {
        return new SharedPortSignaling(this, delegate, answerRewriter);
    }

    /**
     * Registers a local ufrag as a relay credential. Called by the signaling
     * wrapper when an answer leaves.
     *
     * @param ufrag the local ice-ufrag of the answered session
     */
    public void expectUfrag(String ufrag) {
        ufrags.expect(ufrag);
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
        return new RelaySnapshot(legsByClient.size(), legsOpened.sum(), packetsIn.sum(), bytesIn.sum(),
                packetsOut.sum(), bytesOut.sum(), rejectedUfrag.sum(), rejectedLimit.sum(), droppedNoLeg.sum());
    }

    /**
     * Handles one datagram classified as NetherNet traffic: forwards it into the
     * client's leg, opening the leg on a whitelisted ufrag when needed.
     *
     * <p>Only STUN requests may open legs; DTLS and later traffic simply count as
     * dropped when no leg exists yet (the STUN check always comes first on a
     * healthy flow).</p>
     *
     * @param listener the listener the datagram arrived on (the new leg inherits its event loop)
     * @param client   the real client address
     * @param content  the datagram content (not released here; ownership stays with the caller)
     * @param kind     the classification verdict, never {@link PacketKind#OTHER}
     * @param touched  legs written during the current read loop, flushed together by the demultiplexer
     */
    public void forward(Channel listener, InetSocketAddress client, ByteBuf content, PacketKind kind,
                        java.util.Set<Leg> touched) {
        Leg leg = legsByClient.get(client);
        if (leg == null) {
            if (kind != PacketKind.STUN) {
                droppedNoLeg.increment();
                return;
            }
            String ufrag = Rfc7983.localUfrag(content);
            if (ufrag == null || !ufrags.isExpected(ufrag)) {
                // No credential, expired credential, or a forged request: never open a leg
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
    private Leg openLeg(Channel listener, InetSocketAddress client) {
        if (closed || legsByClient.size() >= maxLegs
                || legsPerAddress.getOrDefault(client.getAddress(), 0) >= MAX_LEGS_PER_ADDRESS) {
            return null;
        }

        Leg leg = new Leg(this, listener, client);
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
        ufrags.sweep();
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
        ufrags.clear();
    }

    /** The Geyser logger, shared by the relay and its legs. */
    GeyserLogger logger() {
        return GeyserImpl.getInstance().getLogger();
    }
}
