# Changelog

All notable changes to this project are documented here. The format is based on
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versioning follows
semver. Formal releases are attached to `v*` tags (GitHub Release + Modrinth);
`Unreleased` tracks in-progress work on main.

## [Unreleased]

(none)

## [0.1.0-rc.3] - 2026-11-05

### Fixed

- NetherNet players no longer appear as the relay's loopback address
  (127.0.0.1): relay legs frame their datagrams with a PROXY protocol v2
  header carrying the player's real address, which the nethernet library
  reads - so Geyser, and the Java server behind it, see the player instead of
  the relay. Activated by `advanced.bedrock.webrtc-proxy-protocol-relays:
  ["127.0.0.1"]`

### Added

- `/braid` command tree reworked to the framework's root-command shape:
  `status`, `list` (players grouped by transport), `legs`, `advertise`,
  `version` and the framework-provided `help` - each subcommand under its own
  `braid.command.<name>` permission
- frp/HAProxy fronts: the leg's PROXY header carries the player's address
  resolved through the RakNet proxy mapping
- `/braid version` includes the build's git commit

### Changed

- The extension version is single-sourced from the Gradle build (injected
  into `extension.yml` and the jar manifest at build time)

## [0.1.0-rc.2] - 2026-11-05

### Added

- `/braid` command tree: `status`, `list` (online players grouped by
  transport), `legs`, `advertise`, `version` and `help` - operator status
  and diagnostics for the single-port setup
- Version single-sourcing: `extension.yml` and the jar manifest are injected
  from the Gradle version, and the manifest carries the build's git commit
- PROXY protocol v2 media framing: relay legs prepend the real player
  address per datagram when `webrtc-proxy-protocol-relays` is configured -
  player addresses then reach Geyser and the Java server instead of the
  relay's loopback address

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
