# GeyserBraid 部署指南

> 语言：[English](DEPLOY.md) | 简体中文

## 前提条件

- Geyser **2.12.x**，以插件形式运行于任意平台（BungeeCord / Spigot / Paper /
  Velocity / Standalone——平台状态见 README 的平台表）
- `bedrock.transport: both`，且 `webrtc-port` 为与 `bedrock.port` 不同的空闲端口
- 端口规划：
  - 一个**公网 UDP 端口**（基岩端口）：RakNet 与 NetherNet/WebRTC 共用——这正是
    Braid 提供的能力；
  - 同一端口号的 **TCP 侧**：内置信令；
  - `webrtc-port` 仅内网使用——建议防火墙禁止其公网入站，强制走单端口链路。
- ⚠️ 国内服务商注意：内置信令是明文 HTTP，未备案域名的 HTTP 请求会被运营商
  DPI 用拦截页替答，客户端表现为 `Terracotta` 错误。规避：裸 IP 加入、为信令
  配置 HTTPS（`builtin.https`）、或完成备案。

## 构建

```bash
./gradlew build
# 产物：build/libs/GeyserBraid-<version>.jar
```

## 安装

把 jar 放入 Geyser 平台配置目录下的 `extensions/` 文件夹：

| 平台 | 扩展目录 |
|---|---|
| BungeeCord | `plugins/Geyser-BungeeCord/extensions/` |
| Spigot / Paper | `plugins/Geyser-Spigot/extensions/` |
| Velocity | Geyser 数据目录下的 `extensions/` 文件夹 |
| Standalone | `<Geyser 数据目录>/extensions/` |

## Geyser 配置变更

```yaml
bedrock:
  transport: both
  webrtc-port: 19133        # 任一空闲端口，≠ bedrock.port
  signaling:
    mode: builtin
    advertise-addresses: []  # 默认留空：镜像自动使用 STUN/网卡地址 + 共享端口
```

**端口重映射型主机**（外网端口 ≠ 内网端口，如 `35132` 转发到 `19132`）需手动
加一行，镜像自动让位：

```yaml
    advertise-addresses: ["mc.example.com:35132"]
```

重启宿主后，Geyser 日志应依次出现：

```
[Geyser-BungeeCord] 已启用扩展 GeyserBraid
[GeyserBraid] Assembled: demultiplexer mounted on 1 listener channel(s) (UDP 19132), NetherNet media relayed to localhost/127.0.0.1:19133
[GeyserBraid] Advertising NetherNet candidates on the shared port: [/x.x.x.x:19132]
```

（第三行是镜像生效的标志；手动设置了 `advertise-addresses` 时，该行替换为
"镜像保持关闭"的提示。）

## 客户端验证

1. **NetherNet**：26.60+ 客户端从公网地址加入——能进服即单端口闭环
   （信令走 TCP，WebRTC 走共享 UDP 端口）。
2. **RakNet 回归**：旧版客户端照常加入——分流器对其流量原样放行。
3. **地址**：Java 端 `Player.getAddress()` 走 Geyser 原有转发链，与不装
   Braid 时一致。

## 常见问题

**加入报 `Terracotta`，或服务器列表刷不出来**

1. 日志里有没有 `Assembled:`？没有 → 看 `Assembly failed` 的具体原因
   （多为 Geyser 版本与反射面不匹配）。
2. 打开 `advanced.bedrock.nethernet-debug: true`，重启复测：日志会显示信令
   请求是否到达、应答下发了哪些候选、ICE 全过程。
3. 国内服务商：未备案域名的明文 HTTP 信令会被拦截页替答——先用裸 IP 加入
   测试，或切换 HTTPS 信令。
4. 防火墙：放行 TCP 信令端口与共享 UDP 端口的入站；`webrtc-port` 不得有
   公网入站规则。

**有 `Assembled:` 但没有 `Advertising` 行**

- 检查 `advertise-addresses` 是否被手动设置（设置即镜像关闭）；
- 内网环境下 STUN 不可达时，镜像回退为网卡地址。

**frp 部署**：见 README 的「Behind frp / frp 场景」——同一 `remote_port` 建
`tcp`（信令）+ `udp`（游戏）两条代理；PROXY protocol v2 可选，用于保住真实地址。
