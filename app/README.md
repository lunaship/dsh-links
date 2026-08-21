# app（Android）

DSH Links 手机客户端。本目录是 **Android Studio / Gradle 工程根**。

```bash
cd app
./gradlew :app:assembleDebug
```

- 应用模块：[`app/`](app/)（package `dev.dsh.mobile`）
- 品牌资源：[`branding/`](branding/)、[`logo/`](logo/)
- 签名材料不进 git；正式包只通过 GitHub Releases 分发

源码不对公开仓贡献；公开用户只安装签名 APK。
