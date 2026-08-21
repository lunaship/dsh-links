# Beta 发布核对

GitHub `lunaship/dsh-links` = **仅插件源码与文档**。Android App 保持私有，只发布官方签名 APK；DSH Links Relay 仍在建设中，不属于本次 Beta。

## 发布前

- [ ] 本仓 `git ls-files` 不得出现 Android/`app/src`、keystore、token、`state.json`、`local.properties` 或任何私密配置。
- [ ] `npm test` 通过，`npm pack --dry-run` 的文件清单仅包含声明的插件发布文件。
- [ ] npm 已登录，包名与版本正确；发布后在干净 profile 以 `dsh plugin --profile web add dsh-links@<version>` 成功安装。
- [ ] 用真实 Android 设备完成扫码配对、会话/SSE、审批、吊销、重启后重连验收。
- [ ] APK 是正式签名产物；在 GitHub Release 附版本号、SHA-256、最低 Android 版本和安装说明。

## npm 自动发布

本仓的 [`.github/workflows/publish-npm.yml`](.github/workflows/publish-npm.yml) 使用 npm Trusted Publishing（GitHub OIDC），不使用也不读取 `NPM_TOKEN`。只有发布 GitHub Release 时才会触发；手动重试必须显式输入既有 tag。

首次启用需要在 npm 完成一次性配置：

1. 包 `dsh-links` 目前尚未存在于 npm。先由包所有者在本机手动发布首个版本，例如 beta 使用 `npm publish --access public --tag beta`。
2. 在 npmjs.com 的 `dsh-links` → **Settings** → **Trusted Publisher** 添加 GitHub Actions：Owner `lunaship`、Repository `dsh-links`、Workflow filename `publish-npm.yml`，并允许 `npm publish`。
3. 之后创建 tag `v<package.json 的 version>` 并发布 GitHub Release。例如版本为 `0.1.0-beta.1` 时，tag 必须是 `v0.1.0-beta.1`；工作流会运行锁定依赖安装、测试、版本校验，随后发布。

预发布版本会按预发布标识发布到对应 npm dist-tag（例如 `0.1.0-beta.1` → `beta`）；非预发布版本发布到 `latest`。如 npm 侧尚未建立可信发布关系，工作流会失败，不会退回到长期 token。

## 对外口径

- **Beta / Android only / Trusted LAN only / DSH rc.8**。
- 用户自行使用内网穿透仅为实验性个人部署，不是支持路径，也不提供安全或兼容承诺。
- 不得将 `18640` 直接暴露到公网；DSH Links Relay 完成独立安全与端到端验收前，不宣传其为已可用的云端连接。
