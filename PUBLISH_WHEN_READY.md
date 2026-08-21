# Beta 发布核对

GitHub `lunaship/dsh-links` = **仅插件源码与文档**。Android App 保持私有，只发布官方签名 APK；DSH Links Relay 仍在建设中，不属于本次 Beta。

## 发布前

- [ ] 本仓 `git ls-files` 不得出现 Android/`app/src`、keystore、token、`state.json`、`local.properties` 或任何私密配置。
- [ ] `npm test` 通过，`npm pack --dry-run` 的文件清单仅包含声明的插件发布文件。
- [ ] npm 已登录，包名与版本正确；发布后在干净 profile 以 `dsh plugin --profile web add dsh-links@<version>` 成功安装。
- [ ] 用真实 Android 设备完成扫码配对、会话/SSE、审批、吊销、重启后重连验收。
- [ ] APK 是正式签名产物；在 GitHub Release 附版本号、SHA-256、最低 Android 版本和安装说明。

## 对外口径

- **Beta / Android only / Trusted LAN only / DSH rc.8**。
- 用户自行使用内网穿透仅为实验性个人部署，不是支持路径，也不提供安全或兼容承诺。
- 不得将 `18640` 直接暴露到公网；DSH Links Relay 完成独立安全与端到端验收前，不宣传其为已可用的云端连接。
