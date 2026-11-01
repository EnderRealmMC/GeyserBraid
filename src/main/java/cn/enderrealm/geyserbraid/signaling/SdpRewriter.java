/*
 * Copyright (c) 2026 EnderRealmMC
 * SPDX-License-Identifier: LGPL-3.0-only
 */

package cn.enderrealm.geyserbraid.signaling;

import io.netty.util.NetUtil;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * SDP rewriting for the shared-port mode.
 *
 * <p>libjuice is bound to loopback, so the answer only carries a
 * {@code 127.0.0.1:<internal port>} host candidate no client can reach.
 * {@link #rewriteAnswer} swaps it for synthesized candidates on the server's
 * reachable addresses at the shared (RakNet) port and shifts the {@code c=} and
 * {@code m=} lines to match. {@link #stripCandidates} drops the offer's
 * candidates for the reverse direction: a loopback socket cannot send to them,
 * and the peer arrives as a peer-reflexive candidate through the relay leg.</p>
 */
public final class SdpRewriter {

    /** The prefix of every ICE candidate line. */
    private static final String CANDIDATE_PREFIX = "a=candidate:";
    /** The prefix of the local ice-ufrag line. */
    private static final String UFRAG_PREFIX = "a=ice-ufrag:";
    /** RFC 8445 §5.1.2.1 type preference for host candidates. */
    private static final long HOST_TYPE_PREFERENCE = 126;
    /** Foundation base for synthesized candidates, clear of the library's own foundation ranges. */
    private static final long SYNTHESIZED_FOUNDATION_BASE = 4_000_000_000L;
    /** Candidates cost the client a round of connectivity checks each; keep the list short. */
    private static final int MAX_ADDRESSES_PER_FAMILY = 4;

    private SdpRewriter() {
    }

    /**
     * Rewrites an answer SDP: host candidates sitting on loopback or the internal
     * port are replaced once by synthesized candidates on the published addresses
     * at the external port; {@code c=} connection lines are swapped for a
     * reachable same-family address and the {@code m=} media port follows.
     *
     * <p>With no reachable address available, a fallback host candidate on
     * {@code 0.0.0.0} at the external port is kept, so the library's
     * advertised-address translation still has a same-family host line to build on.</p>
     *
     * @param sdp          the answer SDP produced by libjuice
     * @param internalPort the loopback media port the answer's candidates point at
     * @param published    the addresses to advertise (never null)
     * @param externalPort the shared public UDP port
     * @return the rewritten SDP, CRLF line endings
     */
    public static String rewriteAnswer(String sdp, int internalPort, List<InetAddress> published, int externalPort) {
        List<String> synthesized = synthesizedCandidates(published, externalPort);
        StringBuilder out = new StringBuilder(sdp.length() + synthesized.size() * 64);
        boolean inserted = false;
        boolean dropped = false;
        for (String line : sdp.split("\r\n|\n")) {
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("m=")) {
                out.append(shiftMediaPort(line, internalPort, externalPort)).append("\r\n");
                continue;
            }
            if (line.startsWith("c=")) {
                out.append(rewriteConnectionLine(line, published)).append("\r\n");
                continue;
            }
            if (line.startsWith(CANDIDATE_PREFIX)) {
                ParsedCandidate candidate = parse(line);
                boolean unreachable = candidate == null || candidate.port() == internalPort
                        || candidate.address() == null || candidate.address().isLoopbackAddress();
                if (unreachable) {
                    dropped = true;
                    if (!inserted) {
                        appendAll(out, synthesized);
                        inserted = true;
                    }
                    continue;
                }
                out.append(line).append("\r\n");
                continue;
            }
            out.append(line).append("\r\n");
        }
        if (dropped && !inserted) {
            // No reachable address to synthesize from: keep a host line on the external
            // port so the library's advertised-address translation has a base
            long priority = candidatePriority(0);
            out.append(CANDIDATE_PREFIX).append(SYNTHESIZED_FOUNDATION_BASE).append(" 1 UDP ").append(priority)
                    .append(" 0.0.0.0 ").append(externalPort).append(" typ host\r\n");
        }
        return out.toString();
    }

    /**
     * Drops every candidate line: the loopback-bound socket cannot reach any of
     * them, and the relay leg brings the peer in as a prflx candidate instead.
     *
     * @param sdp an offer SDP
     * @return the SDP without candidate lines, CRLF line endings
     */
    public static String stripCandidates(String sdp) {
        StringBuilder out = new StringBuilder(sdp.length());
        for (String line : sdp.split("\r\n|\n")) {
            if (line.isEmpty() || line.startsWith(CANDIDATE_PREFIX)) {
                continue;
            }
            out.append(line).append("\r\n");
        }
        return out.toString();
    }

    /**
     * Extracts the answer's local ice-ufrag, the credential the relay admits the
     * first STUN request by.
     *
     * @param sdp an answer SDP
     * @return the local ufrag, or null when the line is missing or empty
     */
    public static String iceUfrag(String sdp) {
        for (String line : sdp.split("\r\n|\n")) {
            if (line.startsWith(UFRAG_PREFIX)) {
                String ufrag = line.substring(UFRAG_PREFIX.length()).trim();
                return ufrag.isEmpty() ? null : ufrag;
            }
        }
        return null;
    }

    /**
     * Resolves the addresses to publish: a concrete Bedrock bind address is
     * published alone (even loopback — that is the operator's call, say behind a
     * local proxy); a wildcard bind enumerates the host's interfaces. The result
     * is filtered down to addresses a peer could actually reach, IPv4 first.
     *
     * @param boundAddress the Bedrock listener bind address from the Geyser config
     * @return the addresses to advertise, possibly empty
     */
    public static List<InetAddress> publishedAddresses(String boundAddress) {
        InetAddress configured = concreteAddress(boundAddress);
        List<InetAddress> published = new ArrayList<>(2);
        if (configured != null && !(configured instanceof Inet6Address)) {
            published.add(configured);
        }
        boolean needV4 = published.stream().noneMatch(address -> address instanceof Inet4Address);
        List<InetAddress> enumerated = new ArrayList<>();
        if (needV4) {
            for (InetAddress address : interfaceAddresses()) {
                if (!(address instanceof Inet6Address)) {
                    enumerated.add(address);
                }
            }
        }
        for (InetAddress address : selectAddresses(enumerated)) {
            if (!published.contains(address)) {
                published.add(address);
            }
        }
        return published;
    }

    /**
     * Builds one host candidate line per address, local preference descending in
     * list order (RFC 8445 §5.1.2.1), each with its own foundation.
     */
    private static List<String> synthesizedCandidates(List<InetAddress> addresses, int externalPort) {
        List<String> lines = new ArrayList<>(addresses.size());
        int index = 0;
        for (InetAddress address : addresses) {
            lines.add(CANDIDATE_PREFIX + (SYNTHESIZED_FOUNDATION_BASE + index) + " 1 UDP "
                    + candidatePriority(index) + " " + address.getHostAddress() + " " + externalPort + " typ host");
            index++;
        }
        return lines;
    }

    /** RFC 8445 §5.1.2.1 priority layout: type preference, local preference, component. */
    private static long candidatePriority(int index) {
        return (HOST_TYPE_PREFERENCE << 24) | ((long) (65535 - index) << 8) | 255;
    }

    /** Swaps a loopback {@code c=} line for the first same-family reachable address, else the wildcard. */
    private static String rewriteConnectionLine(String line, List<InetAddress> addresses) {
        boolean ipv6 = line.startsWith("c=IN IP6 ");
        String prefix = ipv6 ? "c=IN IP6 " : "c=IN IP4 ";
        InetAddress current = NetUtil.createInetAddressFromIpAddressString(line.substring(prefix.length()).trim());
        if (current == null || !current.isLoopbackAddress()) {
            return line;
        }
        for (InetAddress address : addresses) {
            if ((address instanceof Inet6Address) == ipv6) {
                return prefix + address.getHostAddress();
            }
        }
        return prefix + (ipv6 ? "::" : "0.0.0.0");
    }

    /** Shifts the port of an {@code m=} line from the internal to the external media port. */
    private static String shiftMediaPort(String line, int internalPort, int externalPort) {
        String[] parts = line.split("\\s+");
        if (parts.length < 2) {
            return line;
        }
        try {
            if (Integer.parseInt(parts[1]) == internalPort) {
                parts[1] = String.valueOf(externalPort);
            }
        } catch (NumberFormatException ignored) {
            // not a numeric media port; leave the line alone
        }
        return String.join(" ", parts);
    }

    /** Parses {@code a=candidate:<foundation> <component> <transport> <priority> <address> <port> typ <type>}. */
    private record ParsedCandidate(InetAddress address, int port) {
    }

    private static ParsedCandidate parse(String line) {
        // strip the "a=candidate:" prefix before tokenizing
        String[] parts = line.substring(CANDIDATE_PREFIX.length()).trim().split("\\s+");
        if (parts.length < 6) {
            return null;
        }
        try {
            InetAddress address = NetUtil.createInetAddressFromIpAddressString(parts[4]);
            return new ParsedCandidate(address, Integer.parseInt(parts[5]));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Parses a concrete address literal; wildcard or malformed input yields null. */
    private static InetAddress concreteAddress(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        InetAddress address = NetUtil.createInetAddressFromIpAddressString(text.trim());
        return address == null || address.isAnyLocalAddress() ? null : address;
    }

    /** Enumerates the addresses of every up, non-loopback interface. */
    private static List<InetAddress> interfaceAddresses() {
        List<InetAddress> addresses = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces == null) {
                return addresses;
            }
            while (interfaces.hasMoreElements()) {
                NetworkInterface networkInterface = interfaces.nextElement();
                if (!networkInterface.isUp() || networkInterface.isLoopback()) {
                    continue;
                }
                Enumeration<InetAddress> ifaceAddresses = networkInterface.getInetAddresses();
                while (ifaceAddresses.hasMoreElements()) {
                    addresses.add(ifaceAddresses.nextElement());
                }
            }
        } catch (SocketException e) {
            org.geysermc.geyser.GeyserImpl.getInstance().getLogger()
                    .warning("GeyserBraid unable to enumerate network interfaces for the shared-port answer: " + e);
        }
        return addresses;
    }

    /**
     * Keeps only addresses a peer could reach: no loopback, link-local, multicast
     * or wildcard, de-duplicated in order, IPv4 first, capped per family.
     */
    private static List<InetAddress> selectAddresses(Collection<InetAddress> candidates) {
        Set<InetAddress> v4 = new LinkedHashSet<>();
        for (InetAddress address : candidates) {
            if (address == null || address.isLoopbackAddress() || address.isLinkLocalAddress()
                    || address.isMulticastAddress() || address.isAnyLocalAddress()) {
                continue;
            }
            if (address instanceof Inet6Address) {
                // IPv6 advertisement is deferred until the relay speaks IPv6 end to end
                continue;
            }
            if (v4.size() < MAX_ADDRESSES_PER_FAMILY) {
                v4.add(address);
            }
        }
        return v4.isEmpty() ? Collections.emptyList() : new ArrayList<>(v4);
    }

    private static void appendAll(StringBuilder out, List<String> lines) {
        for (String line : lines) {
            out.append(line).append("\r\n");
        }
    }
}
