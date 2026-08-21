# Third-party notices

DSH Links is an independent unofficial community project. It is not affiliated with, authorized by, or endorsed by DeepSeek. DeepSeek Harness names and related marks belong to their respective owners.

This file covers source and runtime attribution for the Android app and the `dsh-links` plugin. Project code is MIT; see [LICENSE](LICENSE).

## DeepSeek Harness

- License: MIT
- Copyright: Copyright (c) 2026 DeepSeek
- Upstream: https://github.com/deepseek-ai

`app/src/main/java/dev/dsh/mobile/native/DshIcons.kt` and parts of the workspace copy, color tokens, and panel language are derived from the DeepSeek Harness Web UI. The MIT copyright notice and permission text of DeepSeek Harness apply to those derived portions.

## Fonts

- Plus Jakarta Sans, SIL Open Font License 1.1
- Copyright 2020 The Plus Jakarta Sans Project Authors
- Upstream: https://github.com/tokotype/PlusJakartaSans
- Local copy: [branding/FONT-LICENSE-PlusJakartaSans.txt](branding/FONT-LICENSE-PlusJakartaSans.txt)

## Project branding

- The chibi orca in `branding/logo-orca.png` and the app launcher icon are original to this project.
- `branding/logo-01-harness-orbit.svg` includes a leaping whale and DeepSeek-blue palette. Treat that whale mark as derived from DeepSeek visual language, not as an original DSH Links trademark.

## Android app direct runtime dependencies

Licenses below are as declared by the upstream projects at the versions pinned in this repository.

| Component | Version | License | Upstream |
|---|---|---|---|
| AndroidX Core KTX | 1.15.0 | Apache-2.0 | https://developer.android.com/jetpack/androidx |
| AndroidX AppCompat | 1.7.0 | Apache-2.0 | https://developer.android.com/jetpack/androidx |
| Material Components | 1.12.0 | Apache-2.0 | https://github.com/material-components/material-components-android |
| Jetpack Compose BOM | 2024.12.01 | Apache-2.0 | https://developer.android.com/jetpack/compose |
| Activity Compose | 1.10.0 | Apache-2.0 | https://developer.android.com/jetpack/androidx |
| Material Icons Extended | 1.7.6 | Apache-2.0 | https://developer.android.com/jetpack/compose |
| ZXing Android Embedded | 4.3.0 | Apache-2.0 | https://github.com/journeyapps/zxing-android-embedded |
| OkHttp | 4.12.0 | Apache-2.0 | https://github.com/square/okhttp |
| Coil Compose | 2.7.0 | Apache-2.0 | https://github.com/coil-kt/coil |
| JLaTeXMath Android | 0.2.0 | GPL-2.0 (upstream JLaTeXMath) | https://github.com/noties/jlatexmath-android |

JLaTeXMath is used only to render mathematical formulas in the chat transcript.

## Plugin direct runtime dependencies

| Component | Version | License | Upstream |
|---|---|---|---|
| @deepseek-ai/schemastery | 3.18.1 | MIT | https://www.npmjs.com/package/@deepseek-ai/schemastery |
| qrcode | 1.5.4 | MIT | https://github.com/soldair/node-qrcode |
| selfsigned | 5.5.0 | MIT | https://github.com/jfromaniello/selfsigned |
| @deepseek-ai/cordis (peer) | ^4.0.1 | MIT | https://www.npmjs.com/package/@deepseek-ai/cordis |
