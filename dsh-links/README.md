# dsh-links

DSH 手机一体化插件（npm 包名 `dsh-links`，当前版本 **0.1.0-beta.1**）：给 [DeepSeek Harness](https://github.com/deepseek-ai) 增加局域网手机接入。

**Beta** · **LAN only** · **Unofficial**。这是独立的非官方社区项目，与 DeepSeek 官方不存在隶属、授权或背书关系。

当前公开 Beta 只支持可信局域网。[`dsh-links-relay/`](../dsh-links-relay/) 为后续自建中继占位（含实验笔记），不属于受支持产品路径。

1. **扫码连接**：设置 →「手机连接」→ 二维码 + 一次性配对码（默认 10 分钟有效）。
2. **自带手机接入代理**：监听 `0.0.0.0:18640`，校验连接 token 并重写 Host/Origin 转发到 dsh 本体。

完整 App 安装步骤见仓库根目录 [README](https://github.com/lunaship/dsh-links#readme)。

## 安装（每个 dsh 实例执行一次）

```bash
dsh plugin --profile web add dsh-links@0.1.0-beta.1
dsh web
```

开发期可用本地目录：

```bash
dsh plugin --profile web add /path/to/dsh-links
```

然后重启 `dsh web`，在设置里打开「手机连接」。

已验证组合：DSH `0.1.0-rc.8` + 本插件 `0.1.0-beta.1`。不要假定未验证的 DSH 版本兼容。

## 配置（profile 配置里的 dsh-links 段）

| 键 | 默认 | 说明 |
| --- | --- | --- |
| `port` | `18640` | 手机接入代理端口（0.0.0.0 监听，token 保护） |
| `autoApprove` | `true` | 扫码配对自动批准 |
| `pairingTtlSeconds` | `600` | 配对码有效期 |
| `extraUrls` | `[]` | 额外写进二维码的可达地址（高级用途；当前 Beta 仍只承诺局域网） |

## 接口约定（手机 App 使用）

- `GET  /dsh-link/pair-info`、`GET /dsh-link/qr.png`、`GET /dsh-link/devices`、`POST /dsh-link/revoke`：仅本机主 web 端口（回环同源），18640 上为 404
- `POST /dsh-link/pair` body `{code, deviceName}` → `{token, deviceId, name, urls}`（18640 HTTPS）
- 后续所有请求带头 `x-dsh-link-token`
- 手机 API 仅 `/dsh-link/health`、`/dsh-link/pair`、`/dsh-link/mobile/*`

## TLS 指纹是什么？

18640 使用自签证书。**TLS 指纹**是该证书的 SHA-256，用来固定「连的就是这台电脑」，防止中间人顶替。

| 方式 | 要不要填指纹 |
| --- | --- |
| **扫码** | 不用。二维码里已含地址、配对码和指纹，App 自动钉死 |
| **手动添加** | 不用手打。填地址 + 配对码后，App 会弹出读到的指纹，对照面板上的一串即可 |

面板上展示指纹是为了核对；扫码用户可忽略。

## 安全说明

- 当前 Beta 只支持同一可信局域网。请勿把 18640 裸暴露到公网。
- 配对码一次性且有时效；丢失手机请立即在面板里吊销设备。
- dsh 具有代码执行能力；吊销设备会断开其已建立的 SSE 连接。
- 改 `src/module2.js` 后必须运行 `npm run build:client`，不要手改 `src/client.js`。

## License

[MIT](LICENSE)
