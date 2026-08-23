# Threat Model — DLR/1 Relay

> 版本: 1.0  日期: 2026-08-21

## 1. 信任边界

| 层 | 对端 | 信任假设 |
|---|---|---|
| 外层 TLS (8443/8444) | App/Agent ↔ Relay | 信任公共 CA；防公网窃听、篡改、重放；Relay 持有私钥仅见 DLR 元数据与内层密文 |
| 内层 TLS (18640) | App ↔ Plugin | 信任插件自签指纹 (SHA-256)；Relay 无法解密 |
| 业务认证 | App ↔ Plugin | 信任 x-dsh-link-token；吊销由插件执行 |
| 路由认证 | App/Agent ↔ Relay | 信任 routeSecret (HKDF) + Capability (EdDSA) + Host proof |

**关键不变量**: 即使 Relay 外层 TLS 私钥泄露，攻击者仅能看到 DLR 控制帧与内层密文，无法得知 Token、Prompt、文件、审批内容。

## 2. Assets & Attackers

**Assets**: 插件证书指纹、私钥、Token、routeSecret、Capability、用户 Prompt/会话/文件

**Attackers**:
- 公网被动监听者
- 主动 MITM (公网)
- 恶意 Relay 租户 (跨 Host 串线)
- 恶意 App 仿冒 (无 Token)
- 恶意 Agent 仿冒 (无 Host 私钥)
- 日志/抓包泄露

## 3. 威胁与缓解

### 3.1 窃听与篡改
- 缓解: 外层 TLS 1.2/1.3 + 内层 pinned TLS 双层加密；HMAC 覆盖 route/generation/ts/nonce/challenge

### 3.2 重放
- 每 TLS 连接随机 32B challenge，HMAC 绑定 challenge；同一连接 nonce 去重；ts ±60s 窗口；Relay 重启 challenge 轮换

### 3.3 路由猜测与跨租户
- routeId 128bit 随机；routeSecret HKDF 派生；未认证 CONNECT 对不存在与错误 MAC 统一返回 AUTH_FAILED 且响应等长；Registry 主键 routeId，BIND 校验 route+stream+generation+当前 Agent 连接

### 3.4 假 Agent
- REGISTER proof 需 Host 私钥对 `DLR/1\x00REGISTER\x00` + SHA256(capability) + ts + nonce + challenge 签名；Capability 由 Issuer 私钥签发，含 host_pk、generation、有效期

### 3.5 任意端口代理
- 正式 Agent 硬编码仅允许 `127.0.0.1:18640`；模拟 Agent 仅用于测试；Relay 不接受 Agent 提供的目标地址

### 3.6 吊销失效
- 三种吊销分离：手机 clientId 由插件 401；Host route 由 Control 提升 generation 并通过 Registry 关闭控制连接与全部 stream (1秒)；邀请码仅一次

### 3.7 拒绝服务
- 握手前连接上限、等待 BIND 上限、活跃 stream (1000)、每 route (8)、每 IP/route token bucket、所有 channel 有界、写缓冲 32KiB、空闲 5min

### 3.8 日志泄密
- 日志仅含截断 route 标识、Capability 长度、错误码和连接计数；不含 routeSecret、Capability 原文或前缀、Token、业务 Marker；崩溃报告与指标同样脱敏

### 3.9 Relay 进程失陷
- Relay 不挂载 Control SQLite、Issuer 私钥、管理员凭据或 routeMasterKey；Host lookup 与 route MAC 验证经有界认证 IPC fail-closed；容器部署额外使用独立 UID、只读根文件系统、零 capabilities 与独立网络

### 3.10 旧连接误删新 generation
- Registry Unregister 仅当 generation 匹配才删除；旧连接关闭不影响新 generation 的 Agent 或 stream

## 4. 残留风险与不做事项
- 不做 VPN/任意端口转发；不终止内层 TLS；不保存业务数据；不做多地域/K8s/离线队列
- 若 SPKI pin 启用，需同时携带当前与下一枚 pin，避免证书轮换导致锁定
- routeMasterKey 与 Issuer 私钥需加密备份，丢失需灾难恢复

## 5. 验证方法
- 模糊测试、竞态 (`go test -race`)、24h soak、无泄密扫描 (Marker 不应出现在日志/journal/SQLite/抓包)
- 双 Host 各 8 路并发 10k 建连不串线；跨 Host/generation 串线拒绝；重放拒绝；吊销 1秒生效
