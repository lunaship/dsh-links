# dsh-links

DSH 电脑端插件（npm 包 `dsh-links`）：给 [DeepSeek Harness](https://github.com/deepseek-ai) 增加局域网手机接入。

**Beta** · **LAN only** · **Unofficial**

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

## 开发

```bash
node build-client.mjs
node --test test/*.mjs
```

改 `src/module2.js` 后必须重新生成 `src/client.js`。

## License

[MIT](LICENSE)。Android 客户端不在本仓库。第三方见 [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md)。
