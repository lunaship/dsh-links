# Changelog

## dsh-links 0.1.0-beta.4 — 2026-08-22

- 电脑插件可凭维护者发放的接入码接入 DSH Links Relay；接入成功后才显示单独的云端配对码。
- 局域网设备与云端设备分开列出、分开吊销。
- Android 客户端 `0.5.0-beta.9`：扫云端码走 Relay，与局域网入口并存。
- 公开文档标明 Relay 仍为内测；接入码、Relay 凭据和 `state.json` 不入库、不随 Release / npm 发布。

## dsh-links 0.1.0-beta.3 — 2026-08-22

- Web ↔ 手机实时同步：mux `session/event` 直推到手机 SSE，绕开 1s `session.history` 轮询与「文件未变化」短路。
- mux 桥优先 WebSocket（新版 apiproxy `/api/events.mux` 对 SSE GET 返回 426），失败回退 SSE；握手超时后自动重试。
- 连号快路径直推，跳号走强制补洞轮询；连接补历史完成前不接直推，避免乱序与游标跳号。

## dsh-links 0.1.0-beta.2 — 2026-08-21

- 手机 SSE 转发 Web 澄清卡（`ask_user_question` / mux `question/requested`），并支持 `/question` 回传答案。
- Android 客户端 `0.5.0-beta.2`：会话竞态、审批确认、澄清卡 UI 等修复。

## 0.5.0-beta.1 — 2026-08-21

Android App 局域网 Beta。

- 原生工作台：多主机配对、会话、SSE、审批、通知。
- Release 构建可过 R8；正式签名通过仓库外的环境变量配置。
- 最小权限：删除未使用的旧存储权限，相机为可选硬件。
- 备份与设备迁移排除 Token / HostStore。
- 关于页提供 MIT 与第三方声明入口。
- 产品口径限定为可信局域网；不把远程连接写成可用能力。
- 分发口径：插件 / 文档 MIT 开源；Android 客户端闭源，仅 GitHub Releases 正式签名 APK。

## dsh-links 0.1.0-beta.1 — 2026-08-21

- 局域网扫码配对、一次性配对码、设备吊销、18640 手机 API。
- 「手机连接」面板只保留局域网路径。
- npm metadata、`prepack` 生成校验与 Beta 版本号。
