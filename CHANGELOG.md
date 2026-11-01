# Changelog

All notable changes to this project are documented here. The format is based on
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versioning follows
semver. Formal releases are attached to `v*` tags (GitHub Release + Modrinth);
`Unreleased` tracks in-progress work on main.

## [Unreleased]

(none)

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
