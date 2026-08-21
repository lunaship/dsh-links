# dsh-links

DSH 电脑端插件（npm 包 `dsh-links`）：给 [DeepSeek Harness](https://github.com/deepseek-ai) 增加局域网手机接入。

**Beta** · **Android only** · **Trusted LAN only** · **Unofficial**

## Beta support boundary

This release is a **trusted-LAN Android Beta**, not a public remote-access product.

- **Supported:** Android phone and DSH `0.1.0-rc.8` on the same trusted LAN.
- **Experimental, at your own risk:** a Tailscale or Cloudflare Tunnel path you operate yourself. It is not a supported Beta path and is not covered by the security or compatibility promise.
- **Not supported:** exposing port `18640` directly to the public Internet, frp, or any hosted relay configuration.
- **Planned:** `dsh-links-relay` will be released separately only after its self-hosted remote-access security model and end-to-end acceptance tests are complete.

The Android APK is distributed only as an official signed release. Verify the version and SHA-256 published with that release; do not install repackaged APKs.

同级仓库（已拆开，互不混推）：

| 目录 | 用途 |
|---|---|
| **本仓库 `dsh-links`** | 插件 + 手机 API（可公开） |
| [`../dsh-links-app`](../dsh-links-app) | Android 客户端（**私有**，只发签名 APK） |
| [`../dsh-links-relay`](../dsh-links-relay) | 自建远程中继（规划中） |

## 安装

```bash
dsh plugin --profile web add dsh-links@0.1.0-beta.1
dsh web
```

开发期本地目录（本仓库根目录含 `package.json`）：

```bash
dsh plugin --profile web add /path/to/dsh-links
```

然后重启 `dsh web`，设置 →「手机连接」扫码或手动配对。

已验证：DSH `0.1.0-rc.8` + 本插件 `0.1.0-beta.1`。

## 能力摘要

- 扫码 / 配对码 → 设备 token（`x-dsh-link-token`）
- `0.0.0.0:18640` HTTPS 手机接入代理
- `/dsh-link/mobile/*` 会话、SSE、审批、吊销

详情见 [`SECURITY.md`](SECURITY.md)、[`PRIVACY.md`](PRIVACY.md)。远程中继见同级 `dsh-links-relay`（非 Beta 支持路径）。

## 自管远端接入（实验性）

公开 Beta 的正式支持范围仍是可信局域网；如果你只为自己使用，可通过 Tailscale 私网或 Cloudflare Tunnel 建立远端网络路径。它们不改变配对码、设备 Token 或吊销机制，但也不属于兼容性与安全承诺范围。

- [Tailscale / Cloudflare Tunnel / DSH Links Relay 路线说明](REMOTE_ACCESS.md)
- 不要把 `18640` 直接做路由器端口转发。
- DSH Links Relay 尚未发布：将由 DSH Links 自建并运营公共中继服务。电脑 `local-relay` 和手机 App 均主动连接 Relay；Relay 仅实时转发已配对设备的请求与响应，不持久化存储会话内容、文件、工作区数据或设备内容，电脑不接受公网入站连接。

## 开发

```bash
node build-client.mjs
node --test test/*.mjs
```

改 `src/module2.js` 后必须重新生成 `src/client.js`。

## License

[MIT](LICENSE)。Android 客户端不在本仓库。第三方见 [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md)。
