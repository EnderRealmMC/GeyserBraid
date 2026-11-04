# GeyserBraid

**两股线，编成一股绳。** —— RakNet 与 NetherNet 共用一个 UDP 端口。

[English](README.md) | 简体中文

GeyserBraid 是一个**非官方**的 [Geyser](https://github.com/GeyserMC/Geyser) 扩展：让 Geyser 的
`transport: both` 模式只占用**一个公网 UDP 端口**。RakNet（国际版基岩）与 NetherNet（WebRTC）
的流量在同一端口上按 RFC 7983 首字节标准分流——无需为 NetherNet 额外开放第二个 UDP 端口，
也不引入任何新的配置项。

> ⚠️ 非官方项目，与 GeyserMC / CubeCraft 无关。
>
> 🚧 **非常早期阶段**（0.1.0-SNAPSHOT）：已在单一部署上完成生产验证（Geyser
> `2.12.1-b1280`）——两种传输（RakNet 与 NetherNet）确认在共享端口上并行工作，
> 26.60 客户端经中继以 NetherNet 进入游戏实测通过。接口与行为可能随时破坏性
> 变更。
>
> 📖 完整研究链与生产验证记录见 [RESEARCH.zh-CN.md](RESEARCH.zh-CN.md)。

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
   └── STUN / DTLS 包 ─► 回环转发 leg ─► libjuice（webrtc-port 上的现有 ICE socket，
                                        经回环到达）
                                     └─ 应答经 RakNet 公网 socket 回写，
                                        源端口保持不变
```

候选地址由扩展内置的镜像机制指向共享端口：它把共享端口写到 Geyser 自己的动态地址上
（实时的 STUN 发现结果，或内网环境下的本机网卡地址），注入信令的通告列表——库在
每次应答时都会重新读取它。零配置，公网、NAT、纯内网测试三种环境同样成立。

### 配置

零配置路径覆盖：直连公网的主机、标准 NAT（外部端口与内部一致）、纯内网测试。

只有一种场景需要手动配置一行：服务商面板**重映射了公网端口**（例如外部 `35132`
转发到内部 `19132`）。这个映射关系从服务器内部原理上不可探测——STUN 只能探测到
被探测 socket 自己的映射——所以把 `bedrock.signaling` 下的 `advertise-addresses`
指向公网端点，镜像会自动让位：

```yaml
bedrock:
  signaling:
    advertise-addresses: ["mc.enderrealm.cn:35132"]
```

### frp 场景

frp 本质就是一个端口映射由你自己控制的 NAT——配方完全相同：同一个 `remote_port`
建两条 frp 代理（一条 `tcp` 走信令、一条 `udp` 走游戏数据），都指向内网 `19132`，
`advertise-addresses` 填 VPS 地址加远程端口。

要保住真实玩家地址，给两条 frp 代理都开
`transport.proxyProtocolVersion = "v2"`，并把 frp 客户端来源地址（通常是
`127.0.0.1`）加进 `advanced.bedrock` 的 `haproxy-protocol-whitelisted-ips`。
分流器会自动挂载到 RakNet 的 PROXY 处理器之后；信令读取转发的真实地址，再由
Geyser 原有的 IP 转发链带到 Java 端。不开 PROXY protocol 也能连通，但所有玩家
都会显示为 frp 客户端的地址。

| 模块 | 职责 |
|------|------|
| `demux` | RFC 7983 首字节分类器 + 挂载在每条 RakNet 监听通道管线最前的 Netty 处理器 |
| `relay` | 每客户端回环转发 leg，指向现有 ICE socket，带空闲回收与计数 |
| `integration` | 对 Geyser 内部的反射集成（`RaknetServer` 监听通道）、通告地址镜像、启动自检——升级 Geyser 时唯一需要跟进的包 |

## 使用要求

- Geyser **2.12.x**，`transport: both`
- 一个公网 UDP 端口 + 一个公网 TCP 端口（内置信令）
- 无需任何额外配置（NAT 端口重映射场景见[配置](#配置)一节）

## 现状

已在 Geyser `2.12.1-b1280`（`git-master-64bb399`）上实测。反射面刻意收得很小——
`RaknetServer` 监听通道与内置信令的通告列表——升级 Geyser 时只需跟进这一处。
NXS 信令模式不在支持范围：其候选由外部服务管理。

## 命令

扩展会注册一个 `/braid` 根命令：

| 子命令 | 输出 |
|---|---|
| `status` | 装配状态、端口、镜像状态、中继与媒体计数 |
| `list` | 在线玩家按传输分列（NetherNet / RakNet） |
| `legs` | 每条开放中继 leg 一行（客户端、空闲时长、就绪状态） |
| `advertise` | 当前向加入客户端通告的地址 |
| `version` | 扩展与 Geyser 构建对齐 |
| `help` | 以上列表 |

每个子命令都在 `braid.command.<名>` 权限之后（默认不授予任何人——通过你的
权限配置授予；控制台始终可用）。

## 已测试平台

| 平台 | 状态 |
|---|---|
| BungeeCord（插件） | ✅ 生产验证（Geyser `2.12.1-b1280`） |
| Spigot / Paper（插件） | 未测试——所挂载的接口位于 Geyser core，与平台无关 |
| Velocity（插件） | 未测试——同上 |
| Standalone | 未测试——同上 |
| ViaProxy / mod 加载器（Fabric、NeoForge） | 未测试——同上 |

## 部署

构建后把 jar 放入 Geyser 的 `extensions/` 文件夹，设置 `transport: both` 与
`webrtc-port`，重启后检查 `[GeyserBraid] Assembled:` 日志行。完整分步指南：
[DEPLOY.zh-CN.md](DEPLOY.zh-CN.md) / [DEPLOY.md](DEPLOY.md)。

## 构建

```bash
./gradlew build
# 产物：build/libs/GeyserBraid-<version>.jar
```

CI 在每次 push 与每周定时构建——快照 jar 在 Actions 页（Build 运行 → Artifacts）
下载；正式版本随 `v*` 标签发布并挂 Release。

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
