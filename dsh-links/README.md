# dsh-links

DSH 手机一体化插件（npm 包名 `dsh-links`，App 品牌 **DSH Links**）：**一个插件**同时提供

1. **扫码连接**：设置 →「手机连接」→ **局域网**（二维码 + 一次性配对码，默认 10 分钟有效）。**云端连接**页为预告（敬请期待）。
2. **自带手机接入代理**：监听 `0.0.0.0:18640`，校验连接 token 并重写 Host/Origin 转发到 dsh 本体（127.0.0.1:<web 端口>），手机 App 无需任何其它穿透工具即可接入

## 安装（每个 dsh 实例执行一次）

```bash
# 本地目录方式（开发）
dsh plugin --profile web add /path/to/dsh-links

# npm 方式（发布后）
dsh plugin --profile web add dsh-links
```

然后重启 `dsh web`，在设置里打开「手机连接」即可。

## 配置（profile 配置里的 dsh-links 段）

| 键 | 默认 | 说明 |
| --- | --- | --- |
| `port` | `18640` | 手机接入代理端口（0.0.0.0 监听，token 保护） |
| `autoApprove` | `true` | 扫码配对自动批准 |
| `pairingTtlSeconds` | `600` | 配对码有效期 |
| `extraUrls` | `[]` | 额外写进二维码的可达地址（如 Tailscale IP、frp 地址） |

## 接口约定（手机 App 使用）

- `GET  /dsh-link/pair-info`、`GET /dsh-link/qr.png`、`GET /dsh-link/devices`、`POST /dsh-link/revoke`：仅本机主 web 端口（回环同源），18640 上为 404
- `POST /dsh-link/pair` body `{code, deviceName}` → `{token, deviceId, name, urls}`（18640 HTTPS）
- 后续所有请求带头 `x-dsh-link-token`
- 手机 API 仅 `/dsh-link/health`、`/dsh-link/pair`、`/dsh-link/mobile/*`

## 跨网络（自建穿透，可选）

当前产品主路径是**同一局域网**扫码。若手机和电脑不在同一 Wi‑Fi，有基础的用户可自行组网，例如：

- [Tailscale](https://tailscale.com/) / Headscale：两边装上客户端后，用 Tailscale IP 访问 `https://100.x.x.x:18640`
- 其它 VPN、frp、WireGuard 等：把 18640 映射到手机可达的地址

然后把该地址写进配置的 `extraUrls`，二维码与「可访问地址」里会一并出现。插件**不托管中继**；云端连接仍是后续能力。

请勿把裸 18640 直接暴露到公网；穿透层应有访问控制。

## TLS 指纹是什么？

18640 使用自签证书。**TLS 指纹**是该证书的 SHA-256，用来固定「连的就是这台电脑」，防止中间人顶替。

| 方式 | 要不要填指纹 |
| --- | --- |
| **扫码** | 不用。二维码里已含地址、配对码和指纹，App 自动钉死 |
| **手动添加** | 不用手打。填地址 + 配对码后，App 会弹出读到的指纹，对照面板上的一串即可 |

面板上展示指纹是为了核对；扫码用户可忽略。

## 安全说明

- 18640 使用自签 TLS；配对会固定证书指纹。请勿把该端口暴露给不受信任的网络
- 配对码一次性且有时效；可随时在面板里吊销已配对设备
- dsh 具有代码执行能力；吊销设备会断开其已建立的 SSE 连接

## License

[MIT](LICENSE)
