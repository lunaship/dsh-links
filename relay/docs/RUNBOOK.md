# Runbook — dsh-links-relay 运维手册

## 1. 部署

公网服务器优先使用 `deploy/docker/README.md` 的 Rootless 双容器方案。
`docker-compose.openship.yml` 只用于个人 OpenShip 实验，Control 与 Relay 必须分卷；公网生产不要用它。
以下 systemd 步骤作为高级手动部署方式保留。

### 1.1 单机要求
- 2C2G, 10Mbps 起, Ubuntu 22.04+, Go 1.22+
- 开放 8443 (App), 8444 (Agent), 22 (SSH, 仅管理员), 8080 仅回环

### 1.2 初始化密钥

优先用二进制生成一份可启动目录（本机或 VPS 试验均可）：

```bash
dsh-links-relay init --dir /opt/dsh-links-relay-test
# 记下打印出的 admin 密码和 TLS SHA-256 指纹。管理口仍是 127.0.0.1:8080。
```

需要绑定全部网卡时再加 `--listen-all`，并按需 `--host <公网IP或域名>` 写入自签证书 SAN。正式证书仍建议 Let's Encrypt，替换 `relay.crt` / `relay.key`。

手工生成也可以：

```bash
# 生成 routeMasterKey (32B)
head -c 32 /dev/urandom | base64 -w0 > /etc/dsh-links-relay/route-master.key
chmod 0600 /etc/dsh-links-relay/route-master.key

# 生成 Issuer 密钥对 (Ed25519)
python3 -c "import os,base64; print(base64.urlsafe_b64encode(os.urandom(32)).decode().rstrip('='))" > /etc/dsh-links-relay/issuer.key
# 派生公钥: 用工具从私钥生成，或用 Go 生成后导出
chmod 0600 /etc/dsh-links-relay/issuer.key

# 生成 admin token
head -c 32 /dev/urandom | base64 -w0 > /etc/dsh-links-relay/admin.token
chmod 0600 /etc/dsh-links-relay/admin.token

# 生成 Control↔Relay Unix Socket 认证 token
head -c 32 /dev/urandom | base64 -w0 > /etc/dsh-links-relay/ipc.auth
chmod 0600 /etc/dsh-links-relay/ipc.auth

# TLS 证书 (Let's Encrypt)
certbot certonly --standalone -d relay.example.com
cp /etc/letsencrypt/live/relay.example.com/fullchain.pem /etc/dsh-links-relay/relay.crt
cp /etc/letsencrypt/live/relay.example.com/privkey.pem /etc/dsh-links-relay/relay.key
chmod 0600 /etc/dsh-links-relay/relay.key
```

### 1.3 配置
```bash
cp deploy/config.toml.example /etc/dsh-links-relay/config.toml
# 编辑 tls_cert、tls_key、issuer keys、routeMasterKey、admin_token_file、ipc_auth_token_file
```

### 1.4 启动
```bash
go build -o /usr/local/bin/dsh-links-relay ./cmd/dsh-links-relay

# systemd
cp deploy/systemd/*.service /etc/systemd/system/
systemctl daemon-reload
systemctl enable --now dsh-links-relay-control
systemctl enable --now dsh-links-relay-relay
systemctl status dsh-links-relay-*
```

## 2. 密钥轮换
- **TLS 证书**: 先部署新证书到 relay.crt, 再 `systemctl reload` 或 `restart` relay；若启用 SPKI pin, 需在 App/Agent 同时预置当前与下一枚 pin
- **routeMasterKey**: 更换会导致所有 routeSecret 失效, 需通知所有 Host 重新 Enrollment；必须从加密备份恢复, 禁止静默重建
- **Issuer 私钥**: 同上, 需备份；轮换时旧 Capability 在过期前仍有效, 新签发用新密钥

## 3. Enrollment
```bash
# 通过 SSH 隧道打开 Control
ssh -N -L 8080:127.0.0.1:8080 user@relay.example.com &
curl -H "Authorization: Bearer $(cat /etc/dsh-links-relay/admin.token)" -H "Content-Type: application/json" -d '{}' http://127.0.0.1:8080/v1/invites
# 得到 enroll（接入信息）和 inviteCode。把 enroll 整段贴进插件（默认有效 8 小时, 一次性）
```

插件完成 ENROLL 后会在 Control UI 看到 Host, Relay 日志显示 `agent registered`.

## 3.5 托管租户（维护者发账号，对方自己签发）

自建：SSH 隧道打开 `127.0.0.1:8080`，只用 `admin`。不要给租户 SSH。

公网维护者 Relay：Control 仍然只出现在主机回环。在主机上用 TLS 反代（Caddy / nginx）指到 `127.0.0.1:8080`，在 Control 配置里填写 `public_control_url`（租户打开的 HTTPS 地址），开通租户时控制台和 `tenant create` 会交出该地址，签发的接入串也会带上（`c=`），电脑插件可打开控制台。满额 ENROLL 失败后插件仍记住该公网地址，刷新面板不必再贴。不要把 8080 直接绑到公网，也不要开放注册。手机 App 不登录 Control。若反代对 Control 走 **HTTP**（而不是 Control 自己的 `admin_tls_*`），在 Control 配置里打开 `admin_secure_cookies = true`，否则浏览器拿到的登录 Cookie 没有 Secure。自建 SSH 隧道继续用 `http://127.0.0.1:8080`，保持该选项关闭。反代应带上 `X-Forwarded-Proto` / `X-Forwarded-For`（Caddy `reverse_proxy` 默认会带）：Control 只在对端是回环时采信，用来匹配 HTTPS Origin 并按真实客户端限登录，而不是把所有租户算成 `127.0.0.1`。

```bash
dsh-links-relay tenant create --config /etc/dsh-links-relay/config.toml --login alice --name Alice --password-file /root/alice.pass
dsh-links-relay tenant list   --config /etc/dsh-links-relay/config.toml
dsh-links-relay tenant hosts  --config /etc/dsh-links-relay/config.toml --login alice
dsh-links-relay tenant disable --config /etc/dsh-links-relay/config.toml --login alice
dsh-links-relay tenant enable  --config /etc/dsh-links-relay/config.toml --login alice
```

停用会作废未用邀请并吊销 Host。恢复只恢复登录，不会把已吊销 Host 救回来。新开或重置密码的租户必须先改控制台密码，才能签发或吊销。`tenant list` 把已接入或未用码满额的标成 `host-full` / `invite-full` 并排到前面；维护者不开户内机器，对方自己登录去吊销或签发。控制台台账可再复制登录地址（未配置且本页是回环时不会把 127.0.0.1 外发）。台账「查看电脑」只看该户的 Host / 邀请 / 记录；SSH 上用 `tenant hosts --login alice` 列出占名额电脑（`--all` 含已吊销）。清理已吊销仍作用于全部租户。未用邀请满额时，控制台确认后可作废最早那张未用码再签发；不带确认的 API 仍返回 409。自建 `admin` 不限额。

## 3.6 Bridge 空闲与最大寿命

Bridge 按**整条连接**判断空闲：任一方向成功读写都会刷新活动时间。默认 idle 5 分钟，与 `bridge_max_lifetime`（默认 30 分钟）不是同一件事。

- 手机 SSE 长时间只有下行字节时，不再因为上行方向没有应用层数据而单独被 5 分钟读超时切断。
- 双向都无数据时仍按 idle 回收；单次写仍有 30 秒慢写保护。
- 最大寿命到期、Host 吊销、对端关闭时照常拆桥。
- 回滚：换回本修复前的 Relay 二进制即可；DLR/1 帧格式未改。
- 本轮验证：`go test ./internal/bridge` 覆盖单向下行/上行与真正空闲回收。授权环境中真实 SSE 至少 10 分钟、以及最大寿命附近的恢复仍待单独记录，不能用单测替代。

## 4. 监控

### 4.1 指标
- 通过 Control `/v1/overview` 查看 Host、邀请数。心跳在线来自 Agent `REGISTER`/`PING` 写入的 `last_seen_at`（约 90 秒窗口）；插件断开后立即离线。插件「断开」保留凭据可重连。配额已接入是未吊销名额，断开不释放；插件「释放名额」发送 `REVOKE_SELF` 会立刻空出名额。会话仍只在插件本机。
- Relay 日志: `journalctl -u dsh-links-relay-relay -f` (已脱敏)
- 容量数字以实测为准；在出现测量数据前不要承诺并发 Agent 数量

### 4.2 告警
- FD 使用率 >70%
- 活跃 stream 持续 >900
- 错误率 >0.1%
- Control DB 磁盘 >80%

### 4.3 本机诊断导出

在测试者明确同意后执行 `deploy/collect-diagnostics.sh <relay-pid> [output]`。
它只导出时间、进程资源、工作目录、磁盘和 FD 数；不读取配置、环境变量、
SQLite、日志、请求体或凭据。不要把原始日志追加到该文件。若需要开发者
分析日志，先在 Relay 代码边界使用 `internal/logutil.RedactDiagnosticText`
并通过 `go test ./internal/logutil -run Redact` 验证 Token、`routeSecret`、
邀请码、私钥、明文凭据和消息正文均已移除。

## 5. 备份与恢复
- **Control DB**: `sqlite3 /var/lib/dsh-links-relay/control.db ".backup /backup/control-$(date +%F).db"` 每日
- **密钥**: routeMasterKey、issuer.key、admin.token 加密备份到离线存储, 权限 0600
- **恢复**: 停止服务, 恢复 DB 与密钥, `systemctl start`

### 5.1 RC1 单实例演练

`deploy/soak-single-instance.sh -- <relay command>` 默认要求完整 86400 秒，
每 60 秒采集进程、FD 和磁盘快照；设置 `ALLOW_SHORT_SOAK=1` 才能运行短时
探索，产物会明确标记为不可替代 24h 证据。`deploy/collect-capacity.sh
<relay-pid> [output]` 只做快照并打印阈值目标，目标不是容量承诺。脚本均在
本机执行，不会连接或改动外部服务器。soak 目录权限为仅当前用户可读；其中
`process.log` 是未经脱敏的原始进程输出，不得直接作为共享证据，分享前必须
单独审查并脱敏。

## 6. 吊销
- **手机**: 在插件 UI 吊销设备, 后续 Token 401, 已有 SSE 关闭
- **Host**: `POST /v1/hosts/:id/revoke` 断开该 route；`POST /v1/hosts/:id/delete` 吊销并删除记录；`POST /v1/hosts/purge` 清理已吊销 Host（租户只清自己的）。插件收到 `REVOKED` 后停止重连并提示签发新接入码，不会每 3 秒再 REGISTER
- **邀请**: `POST /v1/invites/:id/revoke` 仅对未消费有效；`POST /v1/invites/:id/delete` 删除记录；`POST /v1/invites/purge` 清理已过期未用、已吊销、以及电脑已不占名额的已用邀请（仍占名额的已接入记录保留；租户只清自己的）。`GET /v1/invites` 对已消费的码带 `consumedHostId` / `consumedHostName`（电脑展示名，不是接入码），电脑未吊销时 `consumedHostLive` 为 true，控制台可从该行吊销 Host。控制台「复制」优先复制完整接入串（含主机）；裸邀请码只作为官方 Relay 的简写

## 7. 回滚
- 插件默认 `relay.enabled=false`, 关闭后仅 LAN
- App 保留 LAN_ONLY, 配置损坏不删 Token
- Relay 故障不影响插件 18640 或 DSH Web

## 8. 排障
- `curl -vk https://relay.example.com:8443` 检查外层 TLS
- `journalctl -u dsh-links-relay-relay | grep -i error`
- 检查挑战重放: 日志 `REPLAY_REJECTED` 表示 nonce 重复
- 检查串线: 测试 Marker 是否出现在抓包或日志
- 重启顺序: 先 Control, 再 Relay (Relay 重启后 Agent 自动重连)

## 9. 升级
- 备份 DB: `cp /var/lib/dsh-links-relay/control.db /var/lib/dsh-links-relay/control.db.bak.$(date +%s)`
- 替换二进制, `systemctl restart`
- 验证: 模拟 App/Agent 贯通, LAN 回归

### 匿名自助接入的日常运维

> 当前仅保留服务端实验能力，Plugin/App 匿名扫码入口已关闭。没有目标 Host 绑定的一次性配对能力与真实端到端证据时，不得作为公共接入方式开放。

1. **实验环境开启发行**：控制台「匿名设备」面板点「开启匿名接入」（持久化，重启不丢）；不得在公共生产服务启用。
2. **封禁**：设备列表「禁用」→ 该设备全部 Host 立即吊销并广播断开；「删除」→ 同时抹除身份。
3. **流量超限**：`stats_daily` 当日累计超 `anonymous_daily_bytes` 的匿名 route 自动挂起至次日 UTC 零点；控制台 Host 列表显示「挂起（日流量）」，CONNECT 返回 `RATE_LIMITED`，不会吊销凭据或丢掉 App 配对。
4. **紧急停止**：一键关闭匿名总开关后，新 BOOTSTRAP/匿名 ENROLL 全部拒绝，现有匿名 host 不受影响（如需同时清场，批量禁用设备）。
