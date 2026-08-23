# Runbook — dsh-links-relay 运维手册

## 1. 部署

公网服务器优先使用 `deploy/docker/README.md` 的 Rootless 双容器方案。
以下 systemd 步骤作为高级手动部署方式保留。

### 1.1 单机要求
- 2C2G, 10Mbps 起, Ubuntu 22.04+, Go 1.22+
- 开放 8443 (App), 8444 (Agent), 22 (SSH, 仅管理员), 8080 仅回环

### 1.2 初始化密钥

优先用二进制生成一份可启动目录（本机或 VPS 试验均可）：

```bash
dsh-links-relay init --dir /opt/dsh-links-relay-test
# 记下打印出的 admin 密码。管理口仍是 127.0.0.1:8080。
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
# 得到 inviteCode, 复制给插件 Agent (有效 30分钟, 一次性)
```

插件完成 ENROLL 后会在 Control UI 看到 Host, Relay 日志显示 `agent registered`.

## 4. 监控

### 4.1 指标
- 通过 Control `/v1/overview` 查看在线 Host、邀请数
- Relay 日志: `journalctl -u dsh-links-relay-relay -f` (已脱敏)
- 容量数字以实测为准；在出现测量数据前不要承诺并发 Agent 数量

### 4.2 告警
- FD 使用率 >70%
- 活跃 stream 持续 >900
- 错误率 >0.1%
- Control DB 磁盘 >80%

## 5. 备份与恢复
- **Control DB**: `sqlite3 /var/lib/dsh-links-relay/control.db ".backup /backup/control-$(date +%F).db"` 每日
- **密钥**: routeMasterKey、issuer.key、admin.token 加密备份到离线存储, 权限 0600
- **恢复**: 停止服务, 恢复 DB 与密钥, `systemctl start`

## 6. 吊销
- **手机**: 在插件 UI 吊销设备, 后续 Token 401, 已有 SSE 关闭
- **Host**: `curl -X POST -H "Authorization: Bearer ..." -H "Content-Type: application/json" -d '{}' http://127.0.0.1:8080/v1/hosts/:id/revoke` — 1秒内关闭该 route 的 Agent 控制连接与全部 stream；LAN 仍可用
- **邀请**: `POST /v1/invites/:id/revoke` 仅对未消费有效

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
