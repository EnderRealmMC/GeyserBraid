# Changelog

All notable changes to this project are documented here. The format is based on
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versioning follows
semver. Formal releases are attached to `v*` tags (GitHub Release + Modrinth);
`Unreleased` tracks in-progress work on main.

## [Unreleased]

(none)

## [0.1.0-rc.4] - 2026-11-06

### Added

- Shutdown wiring: Geyser's shutdown event now tears down the relay and the
  advertised-address mirror and resets the assembly state, so nothing keeps
  ticking against the dead network stack and `/braid` reports honestly after
  a plugin-level reload
- The advertised-address mirror escalates to a warning after three failed
  passes and logs recovery, so reflection breakage after a Geyser upgrade is
  visible instead of silent
- NetherNet relay legs from IPv6 sources are skipped up front (the relay is
  IPv4-only, matching Geyser's default `0.0.0.0` bind): such clients fall back
  to vanilla behavior — the ICE host candidates on the webrtc-port — while
  RakNet traffic of any family stays untouched; `/braid status` reports the
  skipped count

### Fixed

- PROXY media framing: a leg whose resolved identity or target is not IPv4 now
  skips framing instead of emitting a corrupt AF_INET header carrying a
  garbage address

## [0.1.0-rc.3] - 2026-11-05

### Fixed

- frp/HAProxy fronts: the relay leg's PROXY header now carries the player's
  address resolved through the RakNet proxy mapping, so the address reaching
  Geyser is the player's, not the front's

## [0.1.0-rc.2] - 2026-11-05

### Added

- `/braid` command tree: `status`, `list` (online players grouped by
  transport), `legs`, `advertise`, `version` and `help` - operator status
  and diagnostics for the single-port setup
- PROXY protocol v2 media framing: relay legs prepend the real player
  address per datagram when `webrtc-proxy-protocol-relays` is configured -
  player addresses then reach Geyser and the Java server instead of the
  relay's loopback address
- Version single-sourcing: `extension.yml` and the jar manifest are injected
  from the Gradle version, and the manifest carries the build's git commit

## [0.1.0-rc.1] - 2026-11-01

First prerelease (release-flow shakedown). The feature set is what 0.1.0 ships:

### Added

- RFC 7983 first-byte demultiplexer: RakNet and NetherNet (STUN/DTLS) share a
  single public UDP port
- Loopback media relay: per-client forwarding legs, replies written back
  through the RakNet socket (source port unchanged), idle sweeping and counters
- Advertised-address mirror: live STUN discovery / interface addresses plus the
  shared port, re-read on every answer; manual `advertise-addresses` wins
  (NAT port-remap hosts)
- PROXY protocol front compatibility: the demultiplexer auto-mounts behind
  `rak-proxy-server-handler` (frp/HAProxy setups)
- CI: build gate (push / weekly, jar artifacts) and tag-driven release flow
  (GitHub Release + Modrinth, version from the tag)
- Docs: bilingual README, deployment guide (English/Chinese), research record
  (Chinese)

### Verified

- Geyser `2.12.1-b1280` (BungeeCord plugin), NAT deployment with a remapped
  public port (external 35132 -> internal 19132)
- A 26.60 client joined over NetherNet and played in-game: ICE connected ->
  SCTP connected -> into the game
- RakNet regression clean: both transports run in parallel on the shared port
- Known limitations: built-in signaling only (NXS unsupported); plain-HTTP
  signaling on unlicensed domains is intercepted by CN provider DPI (raw-IP
  join works, HTTPS/filing are the long-term fixes)
