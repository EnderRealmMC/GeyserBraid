# GeyserBraid

**两股线，编成一股绳。** —— RakNet 与 NetherNet 共用一个 UDP 端口。

[English](README.md) | 简体中文

GeyserBraid 是一个**非官方**的 [Geyser](https://github.com/GeyserMC/Geyser) 扩展：让 Geyser 的
`transport: both` 模式只占用**一个公网 UDP 端口**。RakNet（国际版基岩）与 NetherNet（WebRTC）
的流量在同一端口上按 RFC 7983 首字节标准分流——无需为 NetherNet 额外开放第二个 UDP 端口，
也不引入任何新的配置项。

> ⚠️ 非官方项目，与 GeyserMC / CubeCraft 无关。

## 为什么需要它

自基岩版 26.60 起，客户端逐步从 RakNet 转向 NetherNet（TCP 信令 + WebRTC/UDP 媒体）。
Geyser 的 `both` 模式同时运行两个监听器，但每个传输各自占用独立的 UDP 端口——对只能映射
单个 UDP 端口的主机环境很不友好。GeyserBraid 在不修改 Geyser 本体的前提下，把两个传输
"编"进同一个端口。

## 工作原理

```
公网 UDP 端口（如 19132，由 RakNet 监听器持有）
   │  RFC 7983 首字节分流（STUN magic cookie / DTLS content type）
   ├── RakNet 包 ──────────────► 原有 RakNet 管线
   └── STUN / DTLS 包 ─► 回环转发 leg ─► libjuice（绑定 127.0.0.1）
                                     └─ 应答经 RakNet 公网 socket 回写，
                                        源端口保持不变
```

| 模块 | 职责 |
|------|------|
| `demux` | RFC 7983 首字节分类器 + 挂载在每条 RakNet 监听通道管线最前的 Netty 处理器 |
| `relay` | 每客户端回环转发 leg，凭 ufrag 白名单开启，带空闲回收与计数 |
| `signaling` | 信令包装器：offer 剥掉候选，应答改写为通告共享端口 |
| `security` | ufrag 白名单——只有信令真正应答过的会话才能开 leg |
| `integration` | 对 Geyser 内部的反射集成（`RaknetServer` 监听通道、`NetherNetServer` 内置信令）——升级 Geyser 时唯一需要跟进的包 |

## 使用要求

- Geyser **2.12.x**，`transport: both`
- 一个公网 UDP 端口 + 一个公网 TCP 端口（内置信令）
- 无需任何额外配置

## 构建

```bash
./gradlew build
# 产物：build/libs/GeyserBraid-<version>.jar
```

## 许可证

以 [LGPL-3.0](LICENSE) 许可发布。

- 分流与中继架构参考 [MemoriesOfTime/Nukkit-MOT](https://github.com/MemoriesOfTime/Nukkit-MOT)
  （同为 LGPL-3.0）；
- 受 [LoHJG/GeyserNetease](https://github.com/LoHJG/GeyserNetease) 启发——它证明了扩展壳
  同样可以深入集成 Geyser 内部。

## 致谢

- [GeyserMC](https://geysermc.org) — Geyser 与扩展 API
- [MemoriesOfTime](https://github.com/MemoriesOfTime) — 共享端口双传输的开创性实现
- [CloudburstMC](https://github.com/CloudburstMC) — Protocol / Network / NetherNet 库
