# 远程访问 DeepSeek Harness（Cloudflare Tunnel）

手机 App 目前通过局域网直连（`http://<局域网IP>:18640`）。离开局域网后需要一条
外网通道。本方案用 **Cloudflare Tunnel**（免费、不需要公网 IP、不需要 VPS），
把 Mac 上 dsh 的 18640 代理端口暴露成自己的 HTTPS 域名。

> 备选：如果你想完全自建（不依赖 Cloudflare），可以仿照 dsh-mobile 项目的
> `dsh-relay`（Go 写的 cloud-relay + local-relay 隧道 + 口令认证），需要一台 VPS。

## 前置条件

1. 一个 Cloudflare 账号 + 一个域名（把域名的 DNS 托管到 Cloudflare）。
2. Mac 上 `dsh web` 已启动，插件 `dsh-links` 已安装（18640 端口监听在 `0.0.0.0`）。

## 步骤

### 1. 安装 cloudflared 并登录

```bash
brew install cloudflared
cloudflared tunnel login          # 打开浏览器授权你的域名
```

### 2. 创建隧道

```bash
cloudflared tunnel create dsh
# 生成 隧道ID，配置文件会写 ~/.cloudflared/<隧道ID>.json
```

### 3. 写配置（复制本目录的 cloudflared.yml.example）

```bash
cp remote/cloudflared.yml.example ~/.cloudflared/config.yml
# 把 <TUNNEL_ID> 替换成上一步的隧道 ID
```

### 4. 绑定 DNS 并启动

```bash
cloudflared tunnel route dns dsh dsh.example.com   # 换成你的域名
cloudflared tunnel run dsh                          # 前台跑（验证）
# 稳定运行：brew services start cloudflared
```

### 5. 手机添加主机

1. 电脑浏览器打开 dsh Web UI → 设置 → 「手机连接」面板，记下**配对码**。
2. 手机 App 设备页 → 手动添加：
   - 名称：任意（如 `远程 dsh`）
   - 地址：`https://dsh.example.com`
   - 配对码：面板上的码
3. 连接成功后在手机工作台顶部可切换主机（局域网主机与远程主机并存）。

## 安全说明

- **token 仍然有效**：所有手机 API 都要求 `x-dsh-link-token` 配对 token，
  隧道本身不提供任何业务豁免。token 泄露风险面 = 你域名被爆破的难度。
- 推荐再加一层 **Cloudflare Access**（Zero Trust 免费额度）给 `dsh.example.com`
  加邮箱/一次性验证码登录，双保险：
  ```bash
  # 网页控制台 Zero Trust → Access → Applications 添加该域名即可
  ```
- 插件侧无需改动：18640 代理会重写 Host/Origin 为 `127.0.0.1:3080`，
  对 dsh 而言请求永远是"本机回环"，**不需要 --trusted-host / --web-token**。
- 不要用 Cloudflare 的「页面规则缓存」缓存 `/dsh-link/` 路径（动态 API）。

## 已知限制

- **SSE 实时流**：App 的实时推送走 SSE（每 15s 一条心跳注释帧）。
  Cloudflare 对响应流式传输没有短连接那种 100s 限制，15s 心跳足够保活；
  如果实测发现断流，把 `SessionStreamClient` 的读超时调大（当前 60s）或改走
  WebSocket 通道（DSH 原生 `/api/events.mux` + ws-ticket，与 dsh-mobile 同协议）。
- **清退「非安全上下文」**：`https` 下 WebView/网络层不再有混合内容问题；
  但 App 内 `usesCleartextTraffic="true"` 同时放行 http/https，不冲突。
- 划掉 App / 强制停止后 SSE 随之断开（Android 进程级硬边界），重开后自动重连补发。
