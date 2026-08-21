# 远端连接路线（实验性）

扫码中的地址或手动填写的地址可以指向家中电脑或远程服务器上运行的 DSH。`dsh-links` 当前正式支持的是可信局域网 Android Beta；本页说明两种由你自己管理的实验性跨网络路径，以及正在建设中的 DSH Links Relay。它们都不是已经发布的“云端连接”产品。

| 方式 | 适合什么 | 公网入口 | 手机添加地址 |
|---|---|---|---|
| Tailscale | 个人电脑和自己的手机 | 无 | `https://100.x.y.z:18640` |
| Cloudflare Tunnel | 自己的域名、跨网络直连 | 有，由你的 Tunnel 管理 | `https://dsh.example.com` |
| DSH Links Relay | 面向跨网络设备连接 | Relay，建设中 | 暂不可用 |

## Tailscale 私网

这是优先推荐的个人自用路径：电脑和手机加入同一个 tailnet，不开放路由器端口。

1. 在电脑和 Android 手机上安装 Tailscale，并登录同一个 tailnet。
2. 让 dsh 与插件运行，电脑执行 `tailscale ip -4`，得到 `100.x.y.z` 地址。
3. 在手机 DSH Links App 选择“手动添加”，填写 `https://100.x.y.z:18640` 和电脑「手机连接 → 远端连接」页的当前 6 位配对码。
4. 首次连接时，App 会显示 TLS 指纹；只在它与该页的指纹一致时继续。

App 会将 Tailscale 默认的 `100.64.0.0/10` 节点地址按私网自签证书处理并钉扎指纹。换 Wi-Fi 不会改变该连接地址或已固定的主机身份。

## Cloudflare Tunnel

这条路径使用你自己的 Cloudflare 账号与域名。Tunnel 从电脑主动建立出站连接，把 `https://你的域名` 转发到本机 `https://127.0.0.1:18640`，不需要路由器端口转发。

```bash
cloudflared tunnel login
cloudflared tunnel create dsh-links
cloudflared tunnel route dns dsh-links dsh.example.com
cloudflared tunnel run dsh-links
```

配置 Tunnel 时，只发布 18640 的手机 API；绝不要一并发布 DSH Web 管理台 3080。

```yaml
ingress:
  - hostname: dsh.example.com
    service: https://127.0.0.1:18640
    originRequest:
      noTLSVerify: true
  - service: http_status:404
```

在 App“手动添加”中输入 `https://dsh.example.com` 和电脑端当前配对码即可。域名侧 HTTPS 由 Cloudflare 提供，插件仍要求配对后发放的设备 Token。

### 当前 Cloudflare Access 限制

Android App 当前不会执行 Cloudflare Access 的网页登录，也不会携带 `Cf-Access-Jwt-Assertion`。因此配置 `originRequest.access.required: true` 会拒绝 App 的配对与运行请求；不要把该配置误认为当前可用。

## DSH Links Relay（规划中）

```
手机 App  ⇄  DSH Links Relay 服务  ⇄  电脑 local-relay  ⇄  127.0.0.1:18640
```

DSH Links Relay 正在建设中。计划形态是电脑 `local-relay` 与手机 App 均主动连接 Relay，电脑不接受公网入站连接。Relay 仅实时转发已配对设备的请求与响应，不持久化存储会话内容、文件、工作区数据或设备内容；设备吊销会切断后续远端请求。

协议、Relay 实现、安装包和端到端验收尚未发布，当前不可配置或下载。
