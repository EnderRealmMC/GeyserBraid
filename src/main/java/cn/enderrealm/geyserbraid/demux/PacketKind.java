/*
 * Copyright (c) 2026 EnderRealmMC
 * SPDX-License-Identifier: LGPL-3.0-only
 */

package cn.enderrealm.geyserbraid.demux;

/**
 * The RFC 7983 §7 first-byte verdict: which transport owns a datagram arriving
 * on the shared port.
 *
 * <ul>
 *     <li>{@link #STUN} — ICE connectivity checks (first byte 0-3 with the STUN
 *         magic cookie at offset 4);</li>
 *     <li>{@link #DTLS} — WebRTC media-layer handshake and data (first byte 20-63);</li>
 *     <li>{@link #OTHER} — everything else, including all RakNet traffic, passed
 *         through to the RakNet pipeline untouched.</li>
 * </ul>
 *
 * <p>RakNet's 0x01/0x02 offline pings share the 0-3 first-byte range, but they
 * carry a timestamp at the cookie offset; the magic cookie is the only reliable
 * discriminator between the two.</p>
 */
public enum PacketKind {
    /** ICE connectivity checks (STUN Binding and friends). */
    STUN,
    /** WebRTC media traffic (DTLS handshake and application data). */
    DTLS,
    /** Non-NetherNet traffic, passed through untouched. */
    OTHER
}
