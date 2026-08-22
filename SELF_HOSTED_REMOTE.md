# 自管远端接入

本指南让你在当前 Beta 期间，用自己管理的网络路径从远端访问 `dsh-links`。这不是已发布的原生云端连接，也不改变插件的配对、设备 Token 与吊销机制。

不要直接把 `18640` 做路由器端口转发或映射到公网。

| 方式 | 适合什么 | 公网入口 | 当前 App 连接地址 |
|---|---|---|---|
| Tailscale | 个人设备、自己的手机 | 无 | `https://100.x.y.z:18640` |
| Cloudflare Tunnel | 自己的域名、需要跨网络直连 App | 有，受你的域名和 Tunnel 管理 | `https://dsh.example.com` |

## 方式一：Tailscale（推荐）

这条路径是私网覆盖网络，不需要开放路由器端口。

1. 在运行 dsh 的电脑和 Android 手机上安装 Tailscale，并登录同一个 tailnet。
2. 保持 dsh 与 `dsh-links` 运行，在电脑执行：

   ```bash
   tailscale ip -4
   ```

3. 电脑端 DSH 设置 →「手机连接」→「远端连接」会显示本次 6 位配对码与 TLS 指纹。
4. 手机 DSH Links App → 添加设备 → 手动添加，填入：

   - 地址：`https://<电脑的 100.x.y.z 地址>:18640`
   - 配对码：电脑端当前显示的 6 位码

5. App 首次连接会显示证书指纹。只在它和电脑「远端连接」页显示的值一致时继续。

Tailscale 的默认节点地址位于 `100.64.0.0/10`。App 将该范围视为私网插件地址，首次确认后固定自签证书指纹；换 Wi-Fi 不会改变这项信任关系。

## 方式二：Cloudflare Tunnel（实验性）

这条路径使用你自己的 Cloudflare 账号和域名。Cloudflare 提供域名侧的公开 TLS；Tunnel 在电脑上主动建立出站连接，因此仍然不需要路由器端口转发。

### 前置条件

- 你拥有 Cloudflare 账号和已托管到 Cloudflare 的域名。
- 电脑上 `dsh web` 与插件已运行，`https://127.0.0.1:18640` 可用。
- 已安装 `cloudflared`。

### 建立 Tunnel

```bash
cloudflared tunnel login
cloudflared tunnel create dsh-links
cloudflared tunnel route dns dsh-links dsh.example.com
```

把 [`cloudflared.yml.example`](cloudflared.yml.example) 复制到 cloudflared 的配置位置，替换 Tunnel UUID、凭据文件和域名。然后前台验证：

```bash
cloudflared tunnel run dsh-links
```

确认 `https://dsh.example.com/dsh-link/health` 能到达后，再把它设置为常驻服务。

### 在手机上配对

1. 电脑端 DSH 设置 →「手机连接」→「远端连接」，取得当前 6 位配对码。
2. 手机 App → 添加设备 → 手动添加。
3. 地址填 `https://dsh.example.com`，配对码填电脑显示的值。

Cloudflare 边缘向手机提供受系统 CA 信任的证书；插件仍然用配对码签发设备 Token，之后每个手机 API 请求都需要该 Token。

### Cloudflare Access 的当前限制

当前 Android App 不会执行 Cloudflare Access 的浏览器登录，也不能附带 `Cf-Access-Jwt-Assertion`。因此，若在 Tunnel ingress 中启用 `originRequest.access.required: true`，App 的配对和运行请求都会被拒绝。

不要把 Access 配置误认为已经可用。原生 Relay 会把远端身份认证、访问控制与设备 Token 协调为一个完整流程；在它完成前，Cloudflare Tunnel 只适合你明确接受其公网边界的个人实验部署。

## 计划中的原生 Relay

```
手机 App  ⇄  你的 VPS Relay  ⇄  电脑 local-relay  ⇄  127.0.0.1:18640
```

`local-relay` 从电脑主动连向你的 Relay，Relay 无法主动打开电脑端口。手机通过 Relay 请求指定的已配对主机；Relay 只承载经过远端认证和设备 Token 校验的会话。设备吊销会同时切断后续远端请求。

这套协议、Relay 实现、安装包与端到端验收尚未发布；当前不要把它当作可下载或可配置的功能。
