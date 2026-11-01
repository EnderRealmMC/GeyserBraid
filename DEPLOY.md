# GeyserBraid Deployment Guide

> Language: English | [简体中文](DEPLOY.zh-CN.md)

## Prerequisites

- Geyser **2.12.x** running as a plugin on any platform (BungeeCord / Spigot /
  Paper / Velocity / Standalone — see the platform table in the README)
- `bedrock.transport: both` and a free `bedrock.webrtc-port` that differs from
  `bedrock.port`
- Port planning:
  - one **public UDP port** (the Bedrock port): RakNet and NetherNet/WebRTC
    share it — this is what Braid provides;
  - the **TCP side of the same port number**: built-in signaling;
  - the `webrtc-port` stays internal — block it from public inbound traffic to
    force the single-port path.
- ⚠️ CN hosting note: built-in signaling is plain HTTP. On unlicensed (no ICP
  filing) domains, provider DPI replaces the HTTP response with a block page —
  clients then fail with `Terracotta`. Workarounds: join by raw IP, configure
  HTTPS signaling (`builtin.https`), or file the domain.

## Build

```bash
./gradlew build
# artifact: build/libs/GeyserBraid-<version>.jar
```

## Install

Drop the jar into the `extensions/` folder inside the Geyser platform config
directory:

> Snapshot jars from CI Artifacts install identically, but they are compiled
> against the Geyser `2.12.1-SNAPSHOT` of their build day — match the Geyser
> build on the server, or prefer tagged releases for production.

| Platform | Extensions folder |
|---|---|
| BungeeCord | `plugins/Geyser-BungeeCord/extensions/` |
| Spigot / Paper | `plugins/Geyser-Spigot/extensions/` |
| Velocity | the Geyser data directory's `extensions/` folder |
| Standalone | `<Geyser data directory>/extensions/` |

## Geyser configuration

```yaml
bedrock:
  transport: both
  webrtc-port: 19133        # any free port, different from bedrock.port
  signaling:
    mode: builtin
    advertise-addresses: []  # leave empty: the mirror uses STUN/interface
                             # addresses plus the shared port automatically
```

For **port-remap hosts** (external port differs from the internal one, e.g.
`35132` forwards to `19132`), add one manual line and the mirror steps aside:

```yaml
    advertise-addresses: ["mc.example.com:35132"]
```

Restart the host. The Geyser log should show, in order:

```
[Geyser-BungeeCord] 已启用扩展 GeyserBraid
[GeyserBraid] Assembled: demultiplexer mounted on 1 listener channel(s) (UDP 19132), NetherNet media relayed to localhost/127.0.0.1:19133
[GeyserBraid] Advertising NetherNet candidates on the shared port: [/x.x.x.x:19132]
```

(The third line is the mirror working; with a manual `advertise-addresses` it is
replaced by the "mirror stays off" notice.)

## Client verification

1. **NetherNet**: join with a 26.60+ client on the public address — reaching the
   server closes the single-port loop (signaling over TCP, WebRTC over the
   shared UDP port).
2. **RakNet regression**: legacy clients join as usual — the demultiplexer
   passes their traffic through untouched.
3. **Addresses**: `Player.getAddress()` on the Java side follows the ordinary
   Geyser forwarding chain, as without Braid.

## Troubleshooting

**Join fails with `Terracotta`, or the server list never refreshes**

1. Does `Assembled:` appear in the log? If not, read the `Assembly failed`
   reason — usually a Geyser version mismatch with the reflective surface.
2. Set `advanced.bedrock.nethernet-debug: true`, restart, retry: the log then
   shows whether signaling requests arrive, which candidates the answers carry,
   and the full ICE progress.
3. CN hosting: plain-HTTP signaling on an unlicensed domain is replaced by the
   provider block page — test a raw-IP join, or switch to HTTPS signaling.
4. Firewall: inbound allow for the TCP signaling port and the shared UDP port;
   `webrtc-port` must have no public inbound rule.

**`Assembled:` present but no `Advertising` line**

- Check whether `advertise-addresses` is set manually (the mirror stays off when
  it is);
- with STUN unreachable on a LAN, the mirror falls back to interface addresses.

**frp**: see "Behind frp" in the README — same `remote_port` for a `tcp`
(signaling) and a `udp` (game) proxy pair; PROXY protocol v2 optional for real
addresses.
