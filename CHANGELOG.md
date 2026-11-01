# 更新日志

格式遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)；版本号遵循语义化版本。
正式版本随 `v*` 标签发布（GitHub Release 挂 jar）；`未发布` 一节记录 main 分支的进行中变更。

## [未发布]

（暂无）

## [0.1.0-rc.1] - 2026-11-01

首个预发布版本（发布流程首航）。功能集即为 0.1.0 的功能集：

### 新增

- RFC 7983 首字节分流器：RakNet 与 NetherNet（STUN/DTLS）共用单个公网 UDP 端口
- 回环媒体中继：每客户端转发 leg、应答经 RakNet socket 回写（源端口不变）、
  空闲回收与计数快照
- 通告地址镜像：实时 STUN 发现/本机网卡地址 + 共享端口，每应答生效；
  手动 `advertise-addresses` 配置优先（NAT 端口重映射场景）
- PROXY protocol 前端兼容：frp/HAProxy 场景下分流器自动挂载到
  `rak-proxy-server-handler` 之后
- CI：构建门禁（push / 每周定时，产物挂 Artifacts）与标签发布流程
  （Release 挂 jar，版本号取自标签）
- 双语文档：README、部署指南（DEPLOY）、研究记录（RESEARCH）

### 验证

- Geyser `2.12.1-b1280`（BungeeCord 插件），服务商 NAT 端口重映射部署
  （外网 35132 → 内网 19132）
- 26.60 客户端 NetherNet 加入并完成游戏内实测：ICE connected → SCTP
  connected → 进入游戏（RakNet 共存回归无异常）
- RakNet 回归无异常：两种传输在共享端口上并行工作
- 已知限制：仅内置信令（NXS 不支持）；未备案域名的明文 HTTP 信令会被国内
  服务商 DPI 拦截（裸 IP 加入可用，HTTPS/备案为长期方案）
