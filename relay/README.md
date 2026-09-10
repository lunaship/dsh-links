# Relay

本目录是 DSH Links Relay（DLR/1）源码，现位于公开仓库 [`lunaship/dsh-links`](https://github.com/lunaship/dsh-links) 的 `relay/`。Go module 为 `github.com/lunaship/dsh-links/relay`。

电脑 `Agent` 与手机 `App` 均主动出站连接 Relay，Relay 仅在外层 TLS 内转发 DLR 控制帧与 App↔插件的内层 TLS 密文。使用仍为维护者接入码私测；本目录不含接入码或主机凭据。

> 版本基线、发布状态和已验证组合统一见
> [`docs/COMPATIBILITY.md`](../docs/COMPATIBILITY.md)。

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

- 仅 `127.0.0.1:8080`。自托管浏览器用 `admin` + `admin_password` 登录；API 也可 `Authorization: Bearer <admin-token>`（全权管理员）。建议 `ssh -L 8080:127.0.0.1:8080 relay`
- 托管 Relay（如 `relay.dshlinks.com`）：维护者在控制台开通 **管控租户**，或用 `dsh-links-relay tenant create`（连本机 Control）。租户只能看到自己签发的邀请与登记的 Host；没有公开注册，Android App 不登录 Control。停用租户会作废未用邀请、吊销已接入 Host，并立即踢掉该租户的控制台会话；`tenant enable` / 控制台「恢复」可重新登录，已吊销 Host 不会自动回来。租户默认最多 4 个未用邀请、8 台已接入 Host（`tenant_max_unused_invites` / `tenant_max_live_hosts`）；自建 `admin` 不限额。配额「已接入」是未吊销名额，插件断开不释放，需吊销。租户控制台 Host 列表主表只显示占名额的电脑，已吊销折起。邀请主表是未用码和仍占名额的已接入记录，失效折起；清理不会删掉还能对照吊销电脑的已用码。租户控制台概览大数显示已接入占名额与未用邀请（`GET /v1/overview` 的 `quota` 对照上限），不是含已吊销的历史总数，管理员台账列出各户用量，已接入或未用码满额的标「已满」并排到前面（`tenant list` 同理），维护者不开户内机器，对方自己登录去吊销或签发；台账可再复制登录地址（回环不外发）。台账「查看电脑」只列出该户 Host / 邀请 / 记录，优先让对方自己登录吊销；SSH 用 `tenant hosts --login` 列出占名额电脑（`--all` 含已吊销，不含路由密钥）；未用邀请满额时不带 `replaceOldestUnused` 的签发仍 409，控制台确认后作废最早未用码再签发。已接入名额满时 ENROLL 返回 `QUOTA_EXCEEDED`，插件提示先吊销，无效接入码仍是 `AUTH_FAILED`；满额不消耗该码，吊销后同一张码可再接入。已接入满额且仍有未用码时控制台不把「创建邀请码」当主操作，避免作废已贴的码；吊销后若仍有未用码则让新电脑再点接入；接入串若带控制台地址，满额时也可从插件打开控制台；满额失败后插件仍记住该地址，刷新不必再贴。租户控制台满额时显示横幅，离线 Host 标「占名额」，列表把离线占名额的排在前面、最久未见的标「建议吊销」，横幅可一键吊销那台。换到别的 Relay 不必先在控制台吊销。`GET /v1/events` 记录谁签发/吊销了邀请与 Host，以及控制台登录成败（不含接入码、密码、会话）。未知登录名的失败不落库。邀请记录标出消费该码的电脑（删除 Host 后仍保留当时的电脑名），电脑仍占名额时可从该行吊销。Host 列表显示插件本机主机名；在线/离线来自 Agent `REGISTER` 与节流后的 `PING`（写入 `last_seen_at`，约 90 秒窗口）；插件断开后立即离线。插件「断开」可重连，不用新码；名额仍占到吊销或插件点「释放名额」（`REVOKE_SELF`）。同一电脑贴新码换新路由、不占额外名额；换路由后插件提示同一网络下的手机下次打开即可跟上，纯远程请重新扫云端配对码（已有有效路由即显示云端码，换路由后刷新；App 设备列表留下本机「扫码恢复云端」，不登录 Control）。换到别的 Relay 会先确认，插件会尝试对原 Relay 发 `REVOKE_SELF` 空出名额，原 Relay 不可达时仍要到原控制台吊销；若原接入串带有控制台地址，插件可打开原控制台。控制台吊销或同一电脑换新码后，手机 CONNECT 收到 `REVOKED`，清掉失效云端路由并保留局域网配对，恢复云端需重新扫码；设备列表探测不得把该错误当成暂时离线。插件「断开」仍是 `AGENT_OFFLINE`。匿名日流量挂起返回可重试的 `RATE_LIMITED`，不是 `REVOKED`，插件与手机保留凭据和配对。控制台吊销 Host 后插件停止重连并提示签发新接入码，不会每 3 秒再 REGISTER。签发后「复制」优先给出完整接入串（含主机），插件直接粘贴即可；裸邀请码只作为官方 Relay 的简写。新开或被重置的租户必须先改控制台密码才能发码或吊销；自建 `admin` 仍只用 config 密码。租户可自己改控制台密码；管理员可重置。`admin` 密码仍只在 config。租户可清理自己的失效邀请和已吊销 Host；Host 列表用 ID 与接入时间区分同名电脑。接入码默认 8 小时、最长 24 小时，签发时可选择 30 分钟 / 2 / 8 / 24 小时。满额不消耗该码，短码会续到默认时长（不超过签发后 24 小时），吊销后再贴同一张即可。开通租户后把 `public_control_url`（HTTPS 反代地址）交给对方，不要发 `127.0.0.1`。配置后接入串带 `c=`，插件可打开控制台；手机配对不含该地址。
- 自托管不需要租户：一个人、一个 `admin`、邀请/接入码即可。控制台只绑回环，用 SSH 隧道。
- 托管租户要从自己的浏览器进控制台：在主机上把 **TLS 反代** 指到 `127.0.0.1:8080`（不要把 8080 直接暴露到公网）。若反代对 Control 走 HTTP，打开 `admin_secure_cookies`；Control 自己上 TLS 时 Cookie 已是 Secure。反代应转发 `X-Forwarded-Proto`（Caddy 默认会带），否则浏览器的 HTTPS Origin 会被当成跨站。数据面仍是 8443/8444。App 不走这条登录。
- UI: `http://127.0.0.1:8080/`（邀请、Host、概览；管理员另有租户台账与匿名设备。Secret 不显示）
- API: `POST /v1/invites`, `GET /v1/hosts`, `POST /v1/hosts/:id/revoke`, `POST /v1/hosts/:id/delete`, `POST /v1/hosts/purge`, `POST /v1/invites/:id/revoke`, `POST /v1/invites/:id/delete`, `POST /v1/invites/purge`, `GET /v1/overview`, `GET /v1/events`, `GET|POST /v1/tenants`, `POST /v1/tenants/:id/disable`, `POST /v1/tenants/:id/enable`, `POST /v1/tenants/:id/password`, `POST /v1/account/password`

DLR/1 仍是嵌套 TLS 字节管道。会话在 Mac（`~/.dsh/sessions`），手机和插件配对，不和 Relay 建账号。

## 安全不变量

1. Relay 不解析内层 HTTP 2. Token 仅内层 3. routeSecret 仅对 route 4. 旧 challenge 拒绝 5. 假 Agent 无私钥不能注册 6. 跨 Host/generation 串线拒绝 7. 正式 Agent 仅 127.0.0.1:18640 8. 手机吊销不依赖 Relay 9. Host 吊销 1秒关连接 10. 有界队列/超时 11. 日志脱敏 12. 旧连接不删新 generation 13. 指纹不匹配失败 14. 非幂等不自动重试

## 测试

```bash
go test ./... -race -count=1 -timeout 60s
go test ./internal/ingress -run TestIntegration -v
go test ./internal/ingress -run TestSequential -v
```

`-race` 需要 cgo（GitHub CI 的 race 门禁也不再设 `CGO_ENABLED=0`）。无 race 的冒烟可用 `CGO_ENABLED=0 go test ./... -count=1`；发布构建仍是 `CGO_ENABLED=0 go build`。

需覆盖: 双 Host 各 8 路并发 10k 建连不串线、跨 Host、旧 generation、重放、吊销、边界帧 (0,767,768,769,2047,2048,2049)、慢首帧、半开、取消、BIND 超时、`go test -race`、fuzz、24h soak。

## 部署

- `deploy/config.toml.example` — 配置模板
- `deploy/systemd/` — `dsh-links-relay-control.service`, `dsh-links-relay-relay.service`
- `docker-compose.yml` + `deploy/docker/` — 推荐的公网加固双容器部署
- `docker-compose.openship.yml` — 仅个人 OpenShip 实验；Control 与 Relay 分卷，Relay 读不到签发密钥。公网生产禁止用它
- 先用 `dsh-links-relay init` 生成密钥与配置；容量需实测后才能写进承诺

## 兼容性

版本基线、发布状态和已验证范围见
[`docs/COMPATIBILITY.md`](../docs/COMPATIBILITY.md)。
本目录不再维护另一份版本表；Relay 的私测不等于公开生产支持。

OpenShip 请以 `lunaship/dsh-links` 的 `relay/` 为源码根（本目录的 `openship.json`）。旧私有仓 `lunaship/dsh-links-relay` 已删除，不要再跟踪。未获维护者确认前不要改生产 OpenShip 指向。

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
- 控制台「匿名设备」面板：列表 / 禁用（级联吊销）/ 启用 / 删除；「匿名自助接入」总开关持久化。超日流量时 Host 显示「挂起（日流量）」，CONNECT 为 `RATE_LIMITED`，不是吊销。
- 当前 Plugin/App 不暴露匿名扫码入口：匿名设备 route 不能标识目标 Plugin，无法完成 App → Relay → Plugin 路由。对外服务必须保持邀请制；恢复匿名扫码前需先定义并验证目标 Host 绑定的一次性配对能力。
- 撤销：设备侧 `REVOKE_SELF`；运营侧设备禁用/删除。
