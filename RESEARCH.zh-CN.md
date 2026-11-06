# GeyserBraid 研究记录

> 单端口双传输（RakNet + NetherNet）的完整研究链与最终结果。
> 研究与验证日期：2026-11-01。作者：EnderRealmMC。

---

## 一、背景与目标

Mojang 自基岩版 26.40 起灰度 NetherNet（TCP 信令 + WebRTC/UDP 媒体），并在
26.60（2026-10-27）让客户端停止走 RakNet 连接。Geyser 的 `transport: both`
模式同时运行两个监听器，但强制要求**两个不同的 UDP 端口**（`port` 归 RakNet、
`webrtc-port` 归 NetherNet，同端口时直接拒绝启动）。

**目标**：以 Geyser 扩展的形式，让 `transport: both` 只占用一个公网 UDP 端口，
不引入新配置项、不修改 Geyser 本体。

## 二、研究链

### 1. Geyser master 源码分析（commit `64bb399`）

- NetherNet 传输栈：`netty-transport-nethernet`（CloudburstMC/Network 的
  nethernet 分支，与 RakNet 同一构建）+ `libdatachannel-java`（打包 libjuice）。
- 信令三模式：`builtin`（Geyser 自跑 HTTP 信令，绑基岩端口 TCP 侧）、
  `nxs`（外部信令服务，默认 `agent.warden.cloud`）、`hybrid`。
- 端口语义：TCP 侧信令与 UDP 侧 RakNet 可共用基岩端口号；`both` 模式下
  `webrtc-port` 必须与 RakNet 端口不同，同端口直接报错拒绝启动。
- 扩展 API 的网络面为零：`api/network` 只有配置接口，无监听器/传输注册能力。

### 2. GeyserNetease 先例（LoHJG/GeyserNetease）

证明"扩展壳 + 编译目标指向 core 内部类 + 反射"可以完成监听器级手术（它整体
替换 Geyser 的 RakNet 监听器以注入网易协议管线），但也暴露了该路线对 Geyser
版本内部结构变更的强耦合。

### 3. Nukkit-MOT 的共享端口双传输（MemoriesOfTime，LGPL-3.0）

决定性参考。它在自己服务端内实现了完整的单端口方案，四个组件：

1. **RFC 7983 首字节分流**：RakNet（其余全部放行）、STUN（首字节 0-3 且偏移 4
   为 magic cookie——RakNet 的 0x01/0x02 ping 同区间但放的是时间戳）、
   DTLS（首字节 20-63）。
2. **libjuice 绑回环**：ICE 藏在内网回环端口，公网不可达。
3. **每客户端回环 leg**：STUN 携带的 ufrag 通过白名单校验后开 leg（connect 到
   libjuice），应答经 leg 返回后**从 RakNet 公网 socket 写回客户端**——源端口
   保持共享端口，客户端视角五元组不变，ICE 检查照常通过。
4. **SDP 重写**：应答里的回环候选替换为公网共享端口合成候选，offer 剥掉客户端
   候选。

**关键洞察：libjuice 不需要拥有公网端口**——它只需要 SDP 通告公网地址 + 应答
从公网 socket 回写。这推翻了"必须改 CloudburstMC/Network 或 libjuice native"的
早期判断。

### 4. 库源码级验证（CloudburstMC/Network nethernet 分支）

- `SdpUtil.translatedCandidates`：通告条目**带端口就用该端口**生成 srflx 候选，
  端口为 0 才保留原端口；条目与已收集候选不匹配时作为附加候选追加，不破坏原有
  host 候选。
- `NetherNetHTTPServerSignaling.advertisedAddresses` 是 volatile 字段，仅在
  bind 时写入一次，之后**每次应答重新读取**——运行期反射写入无竞争。
- `RakProxyServerHandler`（`rak-proxy-server-handler`）：RakNet 的 PROXY
  protocol 处理器，维护"物理来源 → 真实地址"映射，出站自动回映射。

## 三、方案演进（三次迭代）

### v1：关闭重建信令 + 包装拦截 —— 被生产环境否决

设计：反射关闭 Geyser 的内置信令通道，用包装后的信令（SDP 重写 + ufrag 白名单）
在同一端口重建，ICE 改绑回环。

**实测结果**：`IllegalStateException: Signaling cannot be bound again once
closed`——关闭通道时库把信令实例连带标记为已关闭，拒绝二次 bind。风险预判在
部署前已标注，实测命中。**教训：信令实例的构造（身份证书/HTTPS/MOTD）无法在
运行期复用，也不值得复制。**

### v2：分流 + 中继 + 静态通告 —— 场景覆盖不足

只保留分流器与中继，候选改写交给 Geyser 自带的 `advertise-addresses` 配置。
缺陷：静态地址方案在无公网、内网测试、动态 NAT 三种场景下不成立，且会关闭
STUN discovery。**教训：地址必须是动态的。**

### v3（最终）：分流 + 中继 + 通告地址镜像

新增 `AdvertisedMirror`：每 10 秒把"共享端口 + Geyser 当前动态地址"（优先实时
STUN 发现结果，回退本机网卡地址）写入信令实例的 volatile 通告列表。依据第二、
四节的源码验证，该写入无竞争且立即对后续所有应答生效。手动配置
`advertise-addresses` 时镜像自动让位。

| 网络形态 | 行为 | 配置 |
|---|---|---|
| 直连公网 | STUN/网卡地址 + 共享端口 | 无 |
| 标准 NAT | STUN 实时映射地址 + 共享端口 | 无 |
| 端口重映射 NAT（如 35132→19132） | 镜像无法得知映射（原理上不可探测），`advertise-addresses` 手动指定 | 一行 |
| 纯内网测试 | 网卡地址 + 共享端口 | 无 |

## 四、生产验证（2026-11-01）

**部署形态**：BungeeCord 插件（Geyser `2.12.1-b1280`，git-master-`64bb399`），
服务商 NAT（外网 35132 → 内网 19132，无独立公网 IP），`transport: both`，
`webrtc-port: 19133`，`advertise-addresses: ["mc.enderrealm.cn:35132"]`。

**实测（26.60 客户端，外网接入）**：

```
18:02:14  rtc::PeerConnection: Changed signaling state to stable
18:02:14  rtc::IceTransport: juice: Changing state to gathering → connecting
          （libjuice 自身探测报 Send failed errno=10051 ×3 —— 预期行为，
           其 socket 在内网本就够不着客户端候选，无害）
18:02:19  juice: Changing state to connected → completed
18:02:19  rtc::SctpTransport: SCTP connected
18:02:19  rtc::PeerConnection: Changed state to connected
18:02:22  [/127.0.0.1:0] <-> InitialHandler has connected
```

ICE connected → SCTP connected → 玩家抵达 BungeeCord 登录处理器 → 进入游戏。
**26.60 客户端进游戏实测完成**（RakNet 共存的 `both` 模式下进行）。整条链路：

```
客户端(26.60, 外网) → <公网地址>:35132 → 服务商 NAT → 内网 19132/UDP
  → RFC 7983 分流器 → 回环 leg → libjuice(19133)
  → ICE/DTLS/SCTP → 基岩登录包 → BungeeCord
应答原路返回，源端口始终为 35132。
```

**结论：UDP 单端口双传输在生产环境验证通过，26.60 客户端已进入游戏实测。**

**回归验证**：RakNet 客户端先行测试，结论无异常——分流器对非 STUN/DTLS 流量
原样放行，两种传输在同一 UDP 端口上并行工作，互不干扰。

## 五、生产环境踩坑记录

### 未备案 DPI 拦截（域名加入失败，错误码 Terracotta）

拦截的根因是域名未进行 ICP 备案：服务商对明文 HTTP 做深度包检测，
`Host: mc.enderrealm.cn` 未备案 → 用拦截页替答。三类流量中只有内置信令是
明文 HTTP（RakNet 是 UDP 二进制、MC Java 协议无 Host 头），因此只死信令。服务器日志佐证：拦截发生期间信令请求零到达。

- **Terracotta** = 官方"未指定错误"，NetherNet 连接失败的通用兜底码，不含阶段
  信息（help.minecraft.net《Named Error Code Troubleshooting》）。
- **规避**：裸 IP 加入可用（Host 拦截仅针对域名）；长期方案为完成 ICP 备案。
- **可观测性**：Geyser `advanced.bedrock.nethernet-debug: true` 是定位此类问题
  的关键开关（信令请求、SDP 内容、ICE 全过程），排障时打开。

## 六、范围决策记录

1. **TCP 侧分流：主动放弃。** 全平台统一变体（前端 splice）在 BungeeCord 上
   丢失真实玩家地址（stock BC 不支持 PROXY protocol）；按平台注入管线（Paper
   走 NMS、BC 走 PipelineUtils、Velocity 另一套）违反"多平台单实现"约束。
   单 TCP 端口场景的替代：`clone-remote-port` + NXS（纯配置，代价是信令依赖
   外部服务）。
2. **NXS 模式：不在支持范围。** `external-signaling` 库模块只有客户端实现，
   broker 服务端无公开版本（默认 warden.cloud 为 Ziax/CubeCraft 闭源服务）；
   加入地址由服务管理，镜像与单端口整合不适用；状态上报为周期性且含健康/
   位置/游戏结果等元数据。

## 七、最终架构

```
公网 UDP 端口（RakNet 监听器持有）
   ├─ RakNet 包 ─────────────► 原有 RakNet 管线（含 PROXY protocol）
   └─ STUN/DTLS 包 ─► 分流器(RFC 7983) ─► 回环 leg ─► libjuice(webrtc-port)
                                                   └─ 应答经 RakNet socket 回写

公网 TCP 端口（内置信令 HTTP(S)）—— 未备案 HTTP 会被部分国内服务商 DPI 拦截
候选地址 ── AdvertisedMirror（动态地址 + 共享端口）── 手动 advertise-addresses 优先
```

代码结构（`cn.enderrealm.geyserbraid`）：`demux`（分类器+处理器）、`relay`
（中继+leg+快照）、`integration`（装配器、监听通道访问、反射封装、地址镜像）、
入口扩展。反射面收敛于 `integration` 包，升级 Geyser 时仅需跟进该包。
