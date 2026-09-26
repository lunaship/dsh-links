# Release 核对

GitHub `lunaship/dsh-links` = **插件源码、Relay 源码（`relay/`）、Android 源码（`android/`）与文档**。Relay 为维护者内测：文档可以说明流程，但不得提交接入码、Relay 主机凭据或 `state.json`。npm 包仍只包含插件文件，不含 `relay/` 或 `android/`。

## 发布前

- [ ] 本仓 `git ls-files` 不得出现 keystore、token、`state.json`、接入码、Relay 主机凭据、`local.properties` 或任何私密配置。
- [ ] `npm test` 通过，`npm pack --dry-run` 的文件清单仅包含声明的插件发布文件。
- [ ] npm 已登录，包名与版本正确；发布后在干净 profile 以 `dsh plugin --profile web add dsh-links@<version>` 成功安装。
- [ ] 用真实 Android 设备完成扫码配对、会话/SSE、审批、吊销、重启后重连验收。
- [ ] APK 是正式签名产物；在 GitHub Release（`app-v*` tag）附版本号、SHA-256、最低 Android 版本和安装说明。
- [ ] `android/scripts/release-apk.sh` 输出校验和与当前 tag 一致。

## npm 自动发布

本仓的 [`.github/workflows/publish-npm.yml`](.github/workflows/publish-npm.yml) 使用 npm Trusted Publishing（GitHub OIDC），不使用也不读取 `NPM_TOKEN`。只有发布 GitHub Release 时才会触发；手动重试必须显式输入既有 tag。

首次启用需要在 npm 完成一次性配置：

1. 在发布环境先用 `npm view dsh-links versions --json` 和
   `npm view dsh-links dist-tags --json` 核对 registry 当前状态。不要根据
   本地仓库推断包是否已存在；若包尚不存在，必须由包所有者明确执行首个
   `npm publish --access public --tag beta`。
2. 在 npmjs.com 的 `dsh-links` → **Settings** → **Trusted Publisher** 添加 GitHub Actions：Owner `lunaship`、Repository `dsh-links`、Workflow filename `publish-npm.yml`，并允许 `npm publish`。
3. 创建与 `package.json` 版本完全一致的 tag `v<package.json 的 version>` 并发布 GitHub Release。工作流会运行锁定依赖安装、测试、版本校验，随后发布。

预发布版本会按预发布标识发布到对应 npm dist-tag（例如 `0.1.0-beta.1` → `beta`）；非预发布版本发布到 `latest`。如 npm 侧尚未建立可信发布关系，工作流会失败，不会退回到长期 token。

## App 发版流程

1. 确认 `android/` 版本号已更新，CI 全绿。
2. 在 `android/` 目录执行 `./gradlew :app:assembleRelease`，使用维护者本机密钥签名。
3. 运行 `apksigner verify --print-certs android/app/build/outputs/apk/release/app-release.apk | grep SHA-256`，把指纹更新到根目录 `README.md` 和 `SECURITY.md`。
4. 创建 tag `app-v<versionName>`，打 GitHub Release，上传签名 APK、`app-v<versionName>.apk.asc` 和校验和。
5. 更新 `docs/COMPATIBILITY.md` 记录新组合。

## 对外口径

- **Beta / Android only / Trusted LAN / Relay 内测（需接入码）/ DSH `0.1.5-rc.1`**。
- 用户自行使用内网穿透仅为实验性个人部署，不是支持路径，也不提供安全或兼容承诺。
- 不得将 `18640` 直接暴露到公网。Relay 已跑通但仍是邀请制内测；不要把接入码写进 README、Release 说明或 npm 包。
