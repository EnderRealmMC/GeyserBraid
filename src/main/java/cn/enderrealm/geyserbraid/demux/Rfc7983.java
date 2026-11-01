/*
 * Copyright (c) 2026 EnderRealmMC
 * SPDX-License-Identifier: LGPL-3.0-only
 */

package cn.enderrealm.geyserbraid.demux;

import io.netty.buffer.ByteBuf;

import java.nio.charset.StandardCharsets;

/**
 * RFC 7983 first-byte classification plus minimal STUN parsing.
 *
 * <p>Datagrams arriving on the shared port can only be one of three things:
 * RakNet (international Bedrock), NetherNet's WebRTC traffic (STUN + DTLS), or
 * scan noise. The rules live in {@link #classify(ByteBuf)};
 * {@link #localUfrag(ByteBuf)} extracts the local ufrag from the USERNAME
 * attribute of a STUN Binding Request, which the relay uses as the credential
 * for opening a leg.</p>
 */
public final class Rfc7983 {

    /** STUN header length (RFC 5389 §6). */
    public static final int STUN_HEADER_LENGTH = 20;
    /** The STUN magic cookie, a fixed value (RFC 5389 §6). */
    public static final int STUN_MAGIC_COOKIE = 0x2112A442;
    /** STUN Binding Request message type (RFC 5389 §18.2.1). */
    public static final int STUN_BINDING_REQUEST = 0x0001;
    /** USERNAME attribute type (RFC 5389 §18.5.1); under ICE it reads "local-ufrag:remote-ufrag". */
    public static final int STUN_ATTR_USERNAME = 0x0006;
    /** USERNAME attribute value length cap (RFC 5389 §15.3). */
    public static final int STUN_MAX_USERNAME_LENGTH = 513;

    private Rfc7983() {
    }

    /**
     * Classifies a datagram by its first byte.
     *
     * <p>First bytes 0-3 are the STUN range, but the magic cookie at offset 4
     * must be verified — RakNet's 0x01/0x02 offline pings share the range while
     * carrying a timestamp there. First bytes 20-63 are DTLS (RFC 7983 §7).
     * Everything else is {@link PacketKind#OTHER} and returns to the RakNet
     * pipeline.</p>
     *
     * @param buf the datagram content (read-only; reader/writer indices are not moved)
     * @return the classification, never null
     */
    public static PacketKind classify(ByteBuf buf) {
        int readable = buf.readableBytes();
        if (readable == 0) {
            return PacketKind.OTHER;
        }
        int first = buf.getUnsignedByte(buf.readerIndex());
        if (first <= 3) {
            // The discriminator between RakNet 0x01/0x02 pings and STUN: the magic cookie at offset 4
            return readable >= STUN_HEADER_LENGTH && buf.getInt(buf.readerIndex() + 4) == STUN_MAGIC_COOKIE
                    ? PacketKind.STUN : PacketKind.OTHER;
        }
        if (first >= 20 && first <= 63) {
            return PacketKind.DTLS;
        }
        return PacketKind.OTHER;
    }

    /**
     * Extracts the local ufrag from the USERNAME attribute of a STUN Binding
     * Request (USERNAME reads "local-ufrag:remote-ufrag"; the part before the
     * colon is returned).
     *
     * @param buf the datagram content (read-only; reader/writer indices are not moved)
     * @return the local ufrag, or null when this is not a Binding Request, has no
     *         USERNAME attribute, or the attribute is malformed
     */
    public static String localUfrag(ByteBuf buf) {
        int start = buf.readerIndex();
        int readable = buf.readableBytes();
        // Message type sits in the first two bytes; only Binding Requests carry a usable USERNAME
        if (readable < STUN_HEADER_LENGTH || buf.getUnsignedShort(start) != STUN_BINDING_REQUEST) {
            return null;
        }
        int length = buf.getUnsignedShort(start + 2);
        if (STUN_HEADER_LENGTH + length > readable) {
            return null;
        }
        // Linear scan over the TLV attribute area: type(2) + length(2) + value, 4-byte aligned
        int offset = start + STUN_HEADER_LENGTH;
        int end = offset + length;
        while (offset + 4 <= end) {
            int type = buf.getUnsignedShort(offset);
            int valueLength = buf.getUnsignedShort(offset + 2);
            offset += 4;
            if (offset + valueLength > end) {
                return null;
            }
            if (type == STUN_ATTR_USERNAME) {
                if (valueLength > STUN_MAX_USERNAME_LENGTH) {
                    return null;
                }
                String username = buf.toString(offset, valueLength, StandardCharsets.UTF_8);
                int colon = username.indexOf(':');
                return colon <= 0 ? null : username.substring(0, colon);
            }
            offset += (valueLength + 3) & ~3;
        }
        return null;
    }
}
