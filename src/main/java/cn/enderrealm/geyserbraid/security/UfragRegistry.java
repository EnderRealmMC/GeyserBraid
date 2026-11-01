/*
 * Copyright (c) 2026 EnderRealmMC
 * SPDX-License-Identifier: LGPL-3.0-only
 */

package cn.enderrealm.geyserbraid.security;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The ufrag whitelist: the relay only opens forwarding legs for sessions its
 * signaling actually answered.
 *
 * <p>How it works: the signaling wrapper registers the local ice-ufrag taken
 * from the SDP whenever an answer leaves ({@link #expect}); when the first STUN
 * Binding Request carrying that ufrag arrives on the shared port, the
 * demultiplexer opens a leg on that credential ({@link #isExpected}). Entries
 * carry a TTL and are swept by {@link #sweep()}, so a client that fails or
 * abandons its handshake loses its credential.</p>
 *
 * <p>This is the gate of the shared-port mode: without it, any scanner that
 * knows the port could forge STUN requests and make the server open forwarding
 * legs toward arbitrary addresses.</p>
 */
public final class UfragRegistry {

    /** Default credential lifetime (seconds): covers the window from the answer leaving to the first STUN arriving, with handshake headroom. */
    private static final long DEFAULT_TTL_SECONDS = 90;

    /** ufrag → expiry instant (System.nanoTime() based). */
    private final Map<String, Long> expiryByUfrag = new ConcurrentHashMap<>();
    private final long ttlNanos;

    /** Creates a registry with the default TTL (90 seconds). */
    public UfragRegistry() {
        this(DEFAULT_TTL_SECONDS);
    }

    /**
     * @param ttlSeconds the credential lifetime in seconds
     */
    public UfragRegistry(long ttlSeconds) {
        this.ttlNanos = ttlSeconds * 1_000_000_000L;
    }

    /**
     * Registers a local ufrag awaiting its handshake. Null or empty values are ignored.
     *
     * @param ufrag the local ice-ufrag taken from the answer's SDP
     */
    public void expect(String ufrag) {
        if (ufrag == null || ufrag.isEmpty()) {
            return;
        }
        expiryByUfrag.put(ufrag, System.nanoTime() + ttlNanos);
    }

    /**
     * Checks whether a ufrag was registered by the signaling and is still valid.
     *
     * @param ufrag the local ufrag extracted from a STUN request
     * @return true when a leg may be opened for it
     */
    public boolean isExpected(String ufrag) {
        if (ufrag == null) {
            return false;
        }
        Long expiry = expiryByUfrag.get(ufrag);
        return expiry != null && System.nanoTime() - expiry < 0;
    }

    /**
     * Sweeps expired credentials. Called periodically by the relay's sweep task.
     */
    public void sweep() {
        long now = System.nanoTime();
        expiryByUfrag.values().removeIf(expiry -> now - expiry >= 0);
    }

    /**
     * Drops every credential (called when the relay shuts down).
     */
    public void clear() {
        expiryByUfrag.clear();
    }
}
