# dsh-links

DSH Links 是 [DeepSeek Harness](https://github.com/deepseek-ai) 的 Android 配套方案：让运行在电脑、家中主机或远程服务器上的 DSH，在手机上拥有一个经过配对的原生入口。

**Beta** · **Android only** · **Trusted LAN only** · **Unofficial**

![DSH Links 的电脑端「手机连接」面板：二维码、配对码、TLS 指纹、可访问地址与已配对设备。动态数据已脱敏。](docs/images/phone-connection-sanitized.png)

*电脑端「手机连接」面板。截图中的二维码、配对码、证书指纹、地址和设备信息均已脱敏。*

## 这是什么

这不是远程桌面，也不是把 DSH Web 页面塞进手机浏览器。DSH Links 将手机作为 DSH 的一个已配对客户端：电脑继续运行 DSH、工具和工作区；Android App 负责在手机上查看会话、发送消息、接收实时事件和处理审批。

| 组成 | 作用 | 发布方式 |
|---|---|---|
| **本仓库 `dsh-links`** | DSH 插件、手机 HTTPS 接入代理、电脑端配对与设备管理面板 | 开源 npm 插件 |
| **DSH Links Android App** | 扫码/手动配对、设备入口、原生会话工作台、实时流与审批 | 私有源码；仅发布官方签名 APK |
| **DSH Links Relay** | 面向跨网络连接的中继路线 | 正在建设中，当前不可用 |

## Android App 能做什么

- 扫描电脑端二维码，或手动输入地址和一次性配对码添加 DSH。
- 保存多个已配对设备，显示连接状态，并可随时移除本机记录。
- 在原生工作台中浏览会话与历史、继续对话、查看工具/思考事件，并通过 SSE 接收实时更新。
- 在手机上处理 DSH 的审批请求；丢失设备时，可从电脑端立即吊销该设备。
- 使用手机本地加密保存配对 Token 与 TLS 证书指纹；App 禁用云备份和明文 HTTP。完整说明见 [`PRIVACY.md`](PRIVACY.md)。

Android App 当前最低支持 Android 8.0（API 26）。源码不在本仓库；请只安装 GitHub Release 随版本号和 SHA-256 发布的官方签名 APK。

## 三步开始

1. 在运行 DSH 的电脑或服务器安装本插件，并启动 `dsh web`。
2. 打开「设置 → 手机连接」，使用二维码或当前 6 位配对码添加设备。
3. 在 Android App 选择已添加的 DSH，进入会话工作台；设备管理和吊销始终在电脑端可见。

## 界面预览

<table>
  <tr>
    <td width="33%" valign="top"><img src="docs/images/android-device-list-sanitized.png" alt="Android App 的我的设备页面，展示在线设备、局域网连接状态和添加设备入口。"><br><sub><b>设备管理</b>：查看在线状态，扫码或手动添加设备。</sub></td>
    <td width="33%" valign="top"><img src="docs/images/android-workspace-light.jpg" alt="Android App 的原生会话工作台，含对话、轨迹、历史和消息输入区。"><br><sub><b>原生工作台</b>：在手机上继续对话，浏览轨迹和历史。</sub></td>
    <td width="33%" valign="top"><img src="docs/images/android-settings-light.jpg" alt="Android App 的设置页面，展示通用设置、语言、外观和繁忙时发送行为。"><br><sub><b>设置</b>：按设备调整语言、外观和会话默认行为。</sub></td>
  </tr>
</table>

*以上均为 Android App 实机截图；设备名和内网地址已脱敏。*

## Beta support boundary

This release is a **trusted-LAN Android Beta**, not a public remote-access product.

- **Supported:** Android phone and DSH `0.1.0-rc.8` on the same trusted LAN.
- **Experimental, at your own risk:** a Tailscale or Cloudflare Tunnel path you operate yourself. It is not a supported Beta path and is not covered by the security or compatibility promise.
- **Not supported:** exposing port `18640` directly to the public Internet or using frp. DSH Links Relay is not available in this Beta.
- **Planned:** DSH Links Relay is currently in development. It will be released only after its security model and end-to-end acceptance tests are complete.

The Android APK is distributed only as an official signed release. Verify the version and SHA-256 published with that release; do not install repackaged APKs. This repository contains the plugin and its documentation only; the Android source and Relay implementation are not included here.

## 安装

```bash
dsh plugin --profile web add dsh-links@0.1.0-beta.1
dsh web
```

开发期本地目录（本仓库根目录含 `package.json`）：

```bash
dsh plugin --profile web add /path/to/dsh-links
```

然后重启 `dsh web`，设置 →「手机连接」扫码或手动配对。扫码中的地址或手动填写的地址可以指向家中电脑或远程服务器上运行的 DSH；当前 Beta 正式支持同一可信局域网，跨网络访问需要你自行配置 Tailscale / Cloudflare Tunnel，或等待正在建设中的 DSH Links Relay。

已验证：DSH `0.1.0-rc.8` + 本插件 `0.1.0-beta.1`。

## 能力摘要

- 扫码 / 配对码 → 设备 token（`x-dsh-link-token`）
- `0.0.0.0:18640` HTTPS 手机接入代理
- `/dsh-link/mobile/*` 会话、SSE、审批、吊销

详情见 [`SECURITY.md`](SECURITY.md)、[`PRIVACY.md`](PRIVACY.md) 与 [`REMOTE_ACCESS.md`](REMOTE_ACCESS.md)。

## 远端连接路线（实验性）

公开 Beta 的正式支持范围仍是可信局域网；如果你只为自己使用，可通过 Tailscale 私网或 Cloudflare Tunnel 建立远端网络路径。它们不改变配对码、设备 Token 或吊销机制，但也不属于兼容性与安全承诺范围。

- [Tailscale / Cloudflare Tunnel / DSH Links Relay 路线说明](REMOTE_ACCESS.md)
- 不要把 `18640` 直接做路由器端口转发。
- DSH Links Relay 正在建设中：计划中电脑 `local-relay` 和手机 App 均主动连接 Relay；Relay 仅实时转发已配对设备的请求与响应，不持久化存储会话内容、文件、工作区数据或设备内容，电脑不接受公网入站连接。

## 开发

```bash
node build-client.mjs
node --test test/*.mjs
```

改 `src/module2.js` 后必须重新生成 `src/client.js`。

## License

[MIT](LICENSE)。Android 客户端不在本仓库。第三方见 [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md)。
