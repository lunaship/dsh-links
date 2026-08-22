# dsh-links-relay

DLR/1 自研 Relay — 电脑 `Agent` 与手机 `App` 均主动出站连接 Relay，Relay 仅在外层 TLS 内转发 DLR 控制帧与 App↔插件的内层 TLS 密文。

> 与同级项目
> - [`../dsh-links`](../dsh-links) 插件 `v0.2.0` — 局域网 `18640` + Relay Agent
> - [`../dsh-links-app`](../dsh-links-app) `v0.6.0` — 多 Endpoint Host + Relay Dialer
> - `dsh-links-relay` `v0.1.0` — DLR/1、数据面、管控面（本仓库）

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

当前形态是单机主自托管：一个人部署一台 Relay，用控制台生成邀请码，把自家电脑接进去。管理口只绑回环。

```bash
CGO_ENABLED=0 go test ./... -count=1

go run ./cmd/dsh-links-relay init --dir .local
go run ./cmd/dsh-links-relay control --config .local/config.toml
go run ./cmd/dsh-links-relay relay   --config .local/config.toml
```

`init` 会打印一次性管理密码。浏览器打开 `http://127.0.0.1:8080/`，用 `admin` 和该密码登录。先起 control，再起 relay。

VPS 部署见 `docs/RUNBOOK.md` 与 `deploy/systemd/`。容量数字尚未实测，不要把旧目标当成保证。

## 协议

- `docs/DLR1.md` — 冻结合同 (帧、HMAC、Capability、Enrollment)
- `testdata/dlr1-vectors.json` — Golden Vectors (HKDF/HMAC/ENROLL/REGISTER/Capability)
- `docs/THREAT_MODEL.md` — 威胁模型
- `docs/RUNBOOK.md` — 部署、备份、吊销、回滚

## 管控

- 仅 `127.0.0.1:8080`，浏览器用 `admin` + `admin_password` 登录；API 也可 `Authorization: Bearer <admin-token>`。建议 `ssh -L 8080:127.0.0.1:8080 relay`
- UI: `http://127.0.0.1:8080/` (邀请、Host、概览，Secret 不显示)
- API: `POST /v1/invites`, `GET /v1/hosts`, `POST /v1/hosts/:id/revoke`, `GET /v1/overview`

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
- 先用 `dsh-links-relay init` 生成密钥与配置；容量需实测后才能写进承诺

## 兼容

| 插件 | App | Relay | 结果 |
|---|---|---|---|
| v1 QR | 新 App | 无 | LAN 正常 |
| 新插件 Relay关 | 新 App | 无 | LAN 正常 |
| 新插件 | 旧 App | 有 | 旧 App 忽略 v2 |
| 新插件 | 新 App | 离线 | AUTO 回退 LAN |
| 新插件 | 新 App | 在线 | LAN 优先、Relay 兜底 |

## License

MIT — 见 `LICENSE`.
