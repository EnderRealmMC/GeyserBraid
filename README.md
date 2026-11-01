# GeyserBraid

**Two strands, one cord.** — RakNet and NetherNet over a single UDP port.

English | [简体中文](README.zh-CN.md)

GeyserBraid is an **unofficial** [Geyser](https://github.com/GeyserMC/Geyser) extension that lets
Geyser's `transport: both` mode run on a **single public UDP port**: RakNet (international Bedrock)
and NetherNet (WebRTC) traffic share one port, demultiplexed by the RFC 7983 first-byte standard —
no second UDP port for NetherNet, and no new configuration options.

> ⚠️ Unofficial project, not affiliated with GeyserMC or CubeCraft.

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
   └── STUN / DTLS packets ─► loopback leg ─► libjuice (bound on 127.0.0.1)
                                          └─ replies written back through the RakNet
                                             socket, so the source port never changes
```

| Module | Responsibility |
|--------|----------------|
| `demux` | RFC 7983 first-byte classifier + the Netty handler mounted at the head of every RakNet listener pipeline |
| `relay` | Per-client loopback forwarding legs, ufrag-gated, with idle sweeping and counters |
| `signaling` | Signaling wrapper: offers lose their candidates, answers are rewritten to advertise the shared port |
| `security` | The ufrag whitelist — only sessions the signaling actually answered may open a leg |
| `integration` | Reflective wiring into Geyser internals (`RaknetServer` listener channels, `NetherNetServer` built-in signaling) — the only package to revisit on a Geyser upgrade |

## Requirements

- Geyser **2.12.x** with `transport: both`
- One public UDP port + one public TCP port (built-in signaling)
- No additional configuration

## Building

```bash
./gradlew build
# artifact: build/libs/GeyserBraid-<version>.jar
```

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
