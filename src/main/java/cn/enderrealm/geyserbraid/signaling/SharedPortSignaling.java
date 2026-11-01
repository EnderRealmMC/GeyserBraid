/*
 * Copyright (c) 2026 EnderRealmMC
 * SPDX-License-Identifier: LGPL-3.0-only
 */

package cn.enderrealm.geyserbraid.signaling;

import cn.enderrealm.geyserbraid.relay.MediaRelay;
import org.cloudburstmc.netty.channel.nethernet.signaling.NetherNetHTTPServerSignaling;
import org.cloudburstmc.netty.channel.nethernet.signaling.NetherNetServerSignaling;
import org.cloudburstmc.netty.channel.nethernet.signaling.PongData;
import org.cloudburstmc.netty.util.nethernet.OperatorIdentity;

import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.function.UnaryOperator;

/**
 * The shared-port signaling wrapper: a pure delegating decorator over the
 * signaling instance Geyser built, intercepting exactly two exits:
 *
 * <ul>
 *     <li>{@link #sendDescription} — the answer SDP leaving the server has its
 *         loopback candidates rewritten to the shared public port, and its local
 *         ufrag registered as a relay credential;</li>
 *     <li>{@link #setNewConnectionHandler} — the offer payload handed to the
 *         library loses its candidates: the loopback-bound media socket cannot
 *         reach any of them, and the peer arrives as a peer-reflexive candidate
 *         through the relay leg instead.</li>
 * </ul>
 *
 * <p>Everything else passes straight through, so the wrapped instance keeps
 * serving with the identity, HTTPS settings and MOTD provider Geyser configured.</p>
 */
public final class SharedPortSignaling implements NetherNetServerSignaling {

    private final MediaRelay relay;
    private final NetherNetHTTPServerSignaling delegate;
    private final UnaryOperator<String> answerRewriter;

    /**
     * @param relay          the relay whose ufrag registry answers get registered in
     * @param delegate       the signaling instance built by Geyser
     * @param answerRewriter applied to every answer SDP before it leaves
     */
    public SharedPortSignaling(MediaRelay relay, NetherNetHTTPServerSignaling delegate,
                               UnaryOperator<String> answerRewriter) {
        this.relay = relay;
        this.delegate = delegate;
        this.answerRewriter = answerRewriter;
    }

    @Override
    public void sendDescription(String targetNetworkId, String sdp) {
        // The answer is also the moment the session becomes real: its local ufrag
        // is what the first STUN request will present to open a leg
        relay.expectUfrag(SdpRewriter.iceUfrag(sdp));
        delegate.sendDescription(targetNetworkId, answerRewriter.apply(sdp));
    }

    @Override
    public void setNewConnectionHandler(NewConnectionHandler handler) {
        delegate.setNewConnectionHandler((connectionId, remoteNetworkId, payload, clientAddress, player) ->
                handler.onConnect(connectionId, remoteNetworkId, SdpRewriter.stripCandidates(payload),
                        clientAddress, player));
    }

    @Override
    public void bind(SocketAddress localAddress, InetSocketAddress advertisedAddress,
                     io.netty.channel.EventLoop eventLoop) throws ConnectException {
        delegate.bind(localAddress, advertisedAddress, eventLoop);
    }

    @Override
    public void setAdvertisementData(PongData pongData) {
        delegate.setAdvertisementData(pongData);
    }

    @Override
    public OperatorIdentity serverIdentity() {
        return delegate.serverIdentity();
    }

    @Override
    public boolean allowsIceOnLocalPort() {
        return delegate.allowsIceOnLocalPort();
    }

    @Override
    public boolean usesTrickleIce() {
        return delegate.usesTrickleIce();
    }

    @Override
    public void sendSignal(String targetNetworkId, String data) {
        delegate.sendSignal(targetNetworkId, data);
    }

    @Override
    public void setSignalHandler(String connectionId, SignalHandler handler) {
        delegate.setSignalHandler(connectionId, handler);
    }

    @Override
    public void removeSignalHandler(String connectionId) {
        delegate.removeSignalHandler(connectionId);
    }

    @Override
    public String getLocalNetworkId() {
        return delegate.getLocalNetworkId();
    }

    @Override
    public boolean isActive() {
        return delegate.isActive();
    }

    @Override
    public void close() {
        delegate.close();
    }
}
