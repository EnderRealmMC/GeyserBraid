/*
 * Copyright (c) 2026 EnderRealmMC
 * SPDX-License-Identifier: LGPL-3.0-only
 */

package cn.enderrealm.geyserbraid.relay;

/**
 * Immutable snapshot of the relay's counters, for status reporting and
 * diagnostics (a future operator command, dump entries, tests).
 *
 * @param legs           legs currently open
 * @param legsOpened     legs opened since startup
 * @param packetsIn      datagrams forwarded into libjuice
 * @param bytesIn        bytes forwarded into libjuice
 * @param packetsOut     reply datagrams written back to clients
 * @param bytesOut       reply bytes written back to clients
 * @param rejectedUfrag  datagrams refused because their ufrag was unknown or expired
 * @param rejectedLimit  STUN requests refused because the leg limit was reached
 * @param droppedNoLeg   non-STUN datagrams dropped because the client had no leg
 */
public record RelaySnapshot(
        long legs,
        long legsOpened,
        long packetsIn,
        long bytesIn,
        long packetsOut,
        long bytesOut,
        long rejectedUfrag,
        long rejectedLimit,
        long droppedNoLeg
) {
}
