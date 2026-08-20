# dsh-links

DSH 手机一体化插件（npm 包名 `dsh-links`，App 品牌 **DSH Links**）：**一个插件**同时提供

1. **扫码连接**：网页界面右下角「📱 手机连接」按钮 → 二维码 + 一次性配对码（默认 10 分钟有效）
2. **自带手机接入代理**：监听 `0.0.0.0:18640`，校验连接 token 并重写 Host/Origin 转发到 dsh 本体（127.0.0.1:<web 端口>），手机 App 无需任何其它穿透工具即可接入

## 安装（每个 dsh 实例执行一次）

```bash
# 本地目录方式（开发）
dsh plugin --profile web add /path/to/dsh-links

# npm 方式（发布后）
dsh plugin --profile web add dsh-links
```

然后重启 `dsh web`，网页右下角出现「📱 手机连接」按钮即成功。

## 配置（profile 配置里的 dsh-links 段）

| 键 | 默认 | 说明 |
| --- | --- | --- |
| `port` | `18640` | 手机接入代理端口（0.0.0.0 监听，token 保护） |
| `autoApprove` | `true` | 扫码配对自动批准 |
| `pairingTtlSeconds` | `600` | 配对码有效期 |
| `extraUrls` | `[]` | 额外写进二维码的可达地址（如 frp 公网地址） |

## 接口约定（手机 App 使用）

- `GET  /dsh-link/pair-info`、`GET /dsh-link/qr.png`、`GET /dsh-link/devices`、`POST /dsh-link/revoke`：仅本机主 web 端口（回环同源），18640 上为 404
- `POST /dsh-link/pair` body `{code, deviceName}` → `{token, deviceId, name, urls}`（18640 HTTPS）
- 后续所有请求带头 `x-dsh-link-token`
- 手机 API 仅 `/dsh-link/health`、`/dsh-link/pair`、`/dsh-link/mobile/*`

## 安全说明

- 18640 使用自签 TLS；局域网配对会固定证书指纹。请勿把该端口暴露给不受信任的网络
- 配对码一次性且有时效；可随时在面板里吊销已配对设备
- dsh 具有代码执行能力；吊销设备会断开其已建立的 SSE 连接

## License

[MIT](LICENSE)
