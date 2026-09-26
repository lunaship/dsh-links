# deeplinks

DSH Links Android 客户端（私有）。

- 工程根：本目录（Android Studio 打开这里）
- 应用模块：`app/`
- 配对的电脑插件：同级目录 [`../dsh-links`](../dsh-links)（npm 包 `dsh-links`）
- 远程中继（维护者内测，接入码不入库）：公开仓 [`../dsh-links/relay`](../dsh-links/relay)

版本基线、发布状态和已验证组合统一维护在
[`dsh-links 的兼容矩阵`](https://github.com/lunaship/dsh-links/blob/main/docs/COMPATIBILITY.md)，本仓库不维护另一份版本表。当前源码 `versionName` 为 `0.5.0-beta.19`（versionCode 29）；与插件 `0.1.0-beta.17` 搭配可使用重同步、多题校验并跟随 Web 归档集合，旧组合忽略新字段、不破坏既有行为。

支持边界：公开支持为可信局域网；Relay 为邀请制私测（自建用 admin 发码，托管由维护者开通租户后自行发码；App 与插件配对，不登录 Control）；
自管 Tailscale / Cloudflare Tunnel 仅为实验路径。

生产级 UI 对照（DeepSeek 官方 App / Grok Bot / GitHub 同类原生 agent）见 [`docs/ui-parity.md`](docs/ui-parity.md)。

```bash
./gradlew :app:assembleDebug
```

真机设备测试走 debug 变体（`applicationIdSuffix = ".debug"`，与签名 release 共存、互不覆盖）：

```bash
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
adb install -r -g app/build/outputs/apk/debug/app-debug.apk
adb install -r -g app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w dev.deeplinks.debug.test/androidx.test.runner.AndroidJUnitRunner
adb uninstall dev.deeplinks.debug.test; adb uninstall dev.deeplinks.debug
```

HyperOS/MIUI 真机还需在 安全中心 → 应用详情 → 权限管理 → 其他权限 → 后台弹出界面 → 始终允许，否则 Compose UI 测试的 Activity 被拦、进程被冻结强杀（表现为 "Process crashed"）。

**不要**把本仓库推到公开的 `lunaship/dsh-links`。正式包只发签名 APK。
