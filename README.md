# GeyserBraid

**Two strands, one cord.** — RakNet and NetherNet over a single UDP port.

English | [简体中文](README.zh-CN.md)

GeyserBraid is an **unofficial** [Geyser](https://github.com/GeyserMC/Geyser) extension that lets
Geyser's `transport: both` mode run on a **single public UDP port**: RakNet (international Bedrock)
and NetherNet (WebRTC) traffic share one port, demultiplexed by the RFC 7983 first-byte standard —
no second UDP port for NetherNet, and no new configuration options.

> ⚠️ Unofficial project, not affiliated with GeyserMC or CubeCraft.
>
> 🚧 **Early stage** (0.1.0-SNAPSHOT): verified in production on a single
> deployment against a single Geyser build (`2.12.1-b1280`) — both transports
> (RakNet and NetherNet) confirmed working on the shared port, including a
> 26.60 client joining and playing over NetherNet through the relay. Expect
> breaking changes.
>
> 📖 Full research chain and production verification notes (Chinese):
> [RESEARCH.zh-CN.md](RESEARCH.zh-CN.md).

## Why

Since Bedrock 26.60, clients are switching from RakNet to NetherNet (TCP signaling + WebRTC/UDP
media). Geyser's `both` mode runs both listeners side by side, but each transport owns its own UDP
port — a problem for hosts that can only forward one UDP port. GeyserBraid braids both transports
into one port without modifying Geyser itself.

## How it works

```
public UDP port (e.g. 19132, held by the RakNet listener)
   │  RFC 7983 first-byte demultiplexing (STUN magic cookie / DTLS content type)
   ├── RakNet packets ──────────────► the untouched RakNet pipeline
   └── STUN / DTLS packets ─► loopback leg ─► libjuice (the existing ICE socket on webrtc-port)
                                          └─ replies written back through the RakNet
                                             socket, so the source port never changes
```

Candidates are pointed at the shared port by a mirror inside the extension: it
writes the shared port onto Geyser's own dynamic addresses (the live STUN
discovery results, or the host's interface addresses on a LAN) into the
signaling's advertised list, which the library re-reads on every answer. No
configuration, and it works on public hosts, behind NAT and on LAN-only test
setups alike.

### Configuration

The zero-configuration path covers direct-IP hosts, standard NAT (public port
equals the internal one) and LAN-only setups.

One case needs a manual line: hosting panels that **remap the public port**
(e.g. external `35132` forwards to internal `19132`). The mapping is
unobservable from inside the machine — STUN can only reveal the mapping of the
socket it probes — so point `advertise-addresses` under `bedrock.signaling` at
the public endpoint, and the mirror steps aside automatically:

```yaml
bedrock:
  signaling:
    advertise-addresses: ["mc.enderrealm.cn:35132"]
```

### Behind frp

frp is just a NAT whose port mapping you control — the same recipe applies. Run
two frp proxies on the same `remote_port` (one `tcp` for signaling, one `udp`
for game traffic), both pointing at the internal `19132`, and set
`advertise-addresses` to the VPS address plus the remote port.

To keep real player addresses, also set `transport.proxyProtocolVersion = "v2"`
on both frp proxies and put the frp client's address (usually `127.0.0.1`) into
`haproxy-protocol-whitelisted-ips` under `advanced.bedrock`. The demultiplexer
then mounts behind RakNet's PROXY handler automatically; the signaling reads the
forwarded address, and from there the usual Geyser IP forwarding carries it to
the Java server. Without PROXY protocol everything still connects, but every
player appears under the frp client's address.

| Module | Responsibility |
|--------|----------------|
| `demux` | RFC 7983 first-byte classifier + the Netty handler mounted at the head of every RakNet listener pipeline |
| `relay` | Per-client loopback forwarding legs to the existing ICE socket, with idle sweeping and counters |
| `integration` | Reflective wiring into Geyser internals (the `RaknetServer` listener channels), the advertised-address mirror, and startup sanity checks — the only package to revisit on a Geyser upgrade |

## Requirements

- Geyser **2.12.x** with `transport: both`
- One public UDP port + one public TCP port (built-in signaling)
- No additional configuration (see [Configuration](#configuration) for the NAT port-remap case)

## Status

Tested against Geyser `2.12.1-b1280` (`git-master-64bb399`). The reflective
surface is deliberately small — the `RaknetServer` listener channels and the
built-in signaling's advertised list — and is the only code to revisit on a
Geyser upgrade. The NXS signaling mode is out of scope: its candidates are
managed by the external service.

## Tested platforms

| Platform | Status |
|---|---|
| BungeeCord (plugin) | ✅ verified in production (Geyser `2.12.1-b1280`) |
| Spigot / Paper (plugin) | untested — the hooked surface lives in Geyser core and is platform-independent |
| Velocity (plugin) | untested — same note |
| Standalone | untested — same note |
| ViaProxy / mod loaders (Fabric, NeoForge) | untested — same note |

## Deployment

Build, drop the jar into the Geyser `extensions/` folder, set `transport: both`
plus a `webrtc-port`, restart, and check the `[GeyserBraid] Assembled:` log line.
Full step-by-step guide: [DEPLOY.md](DEPLOY.md) / [DEPLOY.zh-CN.md](DEPLOY.zh-CN.md).

## Building

```bash
./gradlew build
# artifact: build/libs/GeyserBraid-<version>.jar
```

CI builds on every push and weekly — snapshot jars live under the Actions tab
(Build runs → Artifacts); formal releases are attached to `v*` tags.

## License

Released under the [GNU Lesser General Public License v3.0](LICENSE).

- The demultiplexing and relay architecture follows
  [MemoriesOfTime/Nukkit-MOT](https://github.com/MemoriesOfTime/Nukkit-MOT) (LGPL-3.0 as well);
- Inspired by [LoHJG/GeyserNetease](https://github.com/LoHJG/GeyserNetease), which proved that an
  extension shell can integrate deeply with Geyser internals.

## Thanks

- [GeyserMC](https://geysermc.org) — Geyser and its extension API
- [MemoriesOfTime](https://github.com/MemoriesOfTime) — the pioneering shared-port dual transport
- [CloudburstMC](https://github.com/CloudburstMC) — the Protocol / Network / NetherNet libraries
