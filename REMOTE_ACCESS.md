# 远端连接路线（实验性）

扫码中的地址或手动填写的地址可以指向家中电脑或远程服务器上运行的 DSH。`dsh-links` 当前正式支持的是可信局域网 Android Beta。本页说明两种由你自己管理的实验性跨网络路径，以及维护者内测中的 DSH Links Relay。它们都不是已经对公众开放的自助“云端连接”产品。

| 方式 | 适合什么 | 公网入口 | 手机怎么加 |
|---|---|---|---|
| Tailscale | 个人电脑和自己的手机 | 无 | 手动填写 `https://100.x.y.z:18640` |
| Cloudflare Tunnel | 自己的域名、跨网络直连 | 有，由你的 Tunnel 管理 | 手动填写 `https://dsh.example.com` |
| DSH Links Relay | 维护者内测的跨网络中继 | Relay | 电脑先填接入码；接入成功后再扫「远端连接」里的第二张云端码 |

**接入码不会出现在本仓库、GitHub Release 或 npm 包里。** 只有维护者可以发放。局域网配对不需要接入码。

## Tailscale 私网

这是优先推荐的个人自用路径：电脑和手机加入同一个 tailnet，不开放路由器端口。

1. 在电脑和 Android 手机上安装 Tailscale，并登录同一个 tailnet。
2. 让 dsh 与插件运行，电脑执行 `tailscale ip -4`，得到 `100.x.y.z` 地址。
3. 在手机 DSH Links App 选择“手动添加”，填写 `https://100.x.y.z:18640` 和电脑「手机连接」局域网页的当前 6 位配对码。
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

## DSH Links Relay（维护者内测）

```
手机 App  ⇄  DSH Links Relay  ⇄  电脑插件（主动出站）  ⇄  127.0.0.1:18640
```

端到端远端配对已经在测试环境跑通，但仍处于内测：

1. 维护者发放一次性接入码（不公开、不入库）。
2. 电脑在「手机连接 → 远端连接」填写 Relay 地址和接入码。没有接入码无法接入。
3. 接入成功后才会出现云端二维码。局域网码和云端码是两张码、两个设备。
4. 手机只扫云端码；二维码里没有接入码。

Relay 只实时转发已配对设备的请求与响应，不持久化存储会话内容、文件、工作区数据或设备内容。设备吊销会切断后续远端请求。电脑不接受公网入站连接。

公开文档不提供 Relay 主机地址或接入码。未获接入码的用户请继续使用局域网，或自行配置 Tailscale / Cloudflare Tunnel。
