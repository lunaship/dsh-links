# dsh-links-relay

DLR/1 自研 Relay — 电脑 `Agent` 与手机 `App` 均主动出站连接 Relay，Relay 仅在外层 TLS 内转发 DLR 控制帧与 App↔插件的内层 TLS 密文。

> 三仓版本基线、发布状态和已验证组合统一见
> [`dsh-links/docs/COMPATIBILITY.md`](https://github.com/lunaship/dsh-links/blob/main/docs/COMPATIBILITY.md)。

## 架构

```
App (8443 TLS) → Relay 数据面 ← Agent (8444 TLS) → 127.0.0.1:18640 (插件)
                       ↕ Unix Socket
                  Control (127.0.0.1:8080 + SQLite)
```

- 外层 TLS: 公共 CA，防公网窃听
- 内层 TLS: 插件自签指纹固定，Relay 不解密
- 业务认证: `x-dsh-link-token` 仅在内层
- 路由认证: `routeSecret` (HKDF) + `Capability` (EdDSA) + Host proof

详见 `docs/DLR1.md` 与 `docs/THREAT_MODEL.md`。

## 快速开始（本机自托管）

当前 Relay 代码用于维护者发放接入码的私测。公开支持路径仍是可信局域网；
一个人自托管一台 Relay 只属于私测范围，管理口只绑回环。

```bash
CGO_ENABLED=0 go test ./... -count=1

go run ./cmd/dsh-links-relay init --dir .local
go run ./cmd/dsh-links-relay control --config .local/config.toml
go run ./cmd/dsh-links-relay relay   --config .local/config.toml
```

`init` 会打印一次性管理密码。浏览器打开 `http://127.0.0.1:8080/`，用 `admin` 和该密码登录。先起 control，再起 relay。控制台创建接入码，贴进插件「远端」即可。

VPS 推荐使用加固双容器部署，见 `deploy/docker/README.md`；systemd 分用户方案保留在 `deploy/`。容量数字尚未实测，不要把旧目标当成保证。

## 协议

- `docs/DLR1.md` — 冻结合同 (帧、HMAC、Capability、Enrollment)
- `testdata/dlr1-vectors.json` — Golden Vectors (HKDF/HMAC/ENROLL/REGISTER/Capability)
- `docs/THREAT_MODEL.md` — 威胁模型
- `docs/RUNBOOK.md` — 部署、备份、吊销、回滚

## 管控

- 仅 `127.0.0.1:8080`，浏览器用 `admin` + `admin_password` 登录；API 也可 `Authorization: Bearer <admin-token>`。建议 `ssh -L 8080:127.0.0.1:8080 relay`
- UI: `http://127.0.0.1:8080/` (邀请、Host、概览，Secret 不显示)
- API: `POST /v1/invites`, `GET /v1/hosts`, `POST /v1/hosts/:id/revoke`, `POST /v1/hosts/:id/delete`, `POST /v1/hosts/purge`, `POST /v1/invites/:id/revoke`, `POST /v1/invites/:id/delete`, `POST /v1/invites/purge`, `GET /v1/overview`

## 安全不变量

1. Relay 不解析内层 HTTP 2. Token 仅内层 3. routeSecret 仅对 route 4. 旧 challenge 拒绝 5. 假 Agent 无私钥不能注册 6. 跨 Host/generation 串线拒绝 7. 正式 Agent 仅 127.0.0.1:18640 8. 手机吊销不依赖 Relay 9. Host 吊销 1秒关连接 10. 有界队列/超时 11. 日志脱敏 12. 旧连接不删新 generation 13. 指纹不匹配失败 14. 非幂等不自动重试

## 测试

```bash
CGO_ENABLED=0 go test ./... -race -count=1 -timeout 60s
CGO_ENABLED=0 go test ./internal/ingress -run TestIntegration -v
CGO_ENABLED=0 go test ./internal/ingress -run TestSequential -v
```

需覆盖: 双 Host 各 8 路并发 10k 建连不串线、跨 Host、旧 generation、重放、吊销、边界帧 (0,767,768,769,2047,2048,2049)、慢首帧、半开、取消、BIND 超时、`go test -race`、fuzz、24h soak。

## 部署

- `deploy/config.toml.example` — 配置模板
- `deploy/systemd/` — `dsh-links-relay-control.service`, `dsh-links-relay-relay.service`
- `docker-compose.yml` + `deploy/docker/` — 推荐的公网加固双容器部署
- `docker-compose.openship.yml` — 仅个人 OpenShip 实验；Control 与 Relay 分卷，Relay 读不到签发密钥。公网生产禁止用它
- 先用 `dsh-links-relay init` 生成密钥与配置；容量需实测后才能写进承诺

## 兼容性

三仓唯一兼容矩阵、source baseline、发布状态和已验证范围见
[`dsh-links/docs/COMPATIBILITY.md`](https://github.com/lunaship/dsh-links/blob/main/docs/COMPATIBILITY.md)。
本仓库不再维护另一份版本表；Relay 的私测不等于公开生产支持。

## License

MIT — 见 `LICENSE`.

## 阶段二：匿名自助接入（仅服务端实验能力）

默认 `anonymous_enroll = false`（保持邀请制）。开启后：

```toml
anonymous_enroll                 = true    # 匿名发行总开关（也可在控制台运行时切换）
anonymous_max_hosts_per_device   = 2       # 每设备最多同时 Host 数
anonymous_max_streams_per_route  = 2       # 匿名 route 并发流上限
anonymous_daily_bytes            = 536870912  # 匿名 route 每日流量预算（0=不限制）
capability_ttl                   = "168h"  # 新能力凭证有效期（7 天）
ipv6_prefix_len                  = 64      # 按 IP 限流的 IPv6 聚合前缀（0=禁用）
```

- 设备本地生成 Ed25519 身份 → `BOOTSTRAP` 帧换 10 分钟 token → `ENROLL` 拿 route（hostId 服务端分配）。
- 控制台「匿名设备」面板：列表 / 禁用（级联吊销）/ 启用 / 删除；「匿名自助接入」总开关持久化。
- 当前 Plugin/App 不暴露匿名扫码入口：匿名设备 route 不能标识目标 Plugin，无法完成 App → Relay → Plugin 路由。对外服务必须保持邀请制；恢复匿名扫码前需先定义并验证目标 Host 绑定的一次性配对能力。
- 撤销：设备侧 `REVOKE_SELF`；运营侧设备禁用/删除。
