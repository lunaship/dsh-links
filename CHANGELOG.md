# Changelog

## Unreleased

## dsh-links 0.1.0-beta.6 — 2026-08-23

- 远端连接面板不再展示默认 Relay 端口（8444/8443）；只填主机名即可接入，自定义端口仍可用。
- Android 客户端仍为 `0.5.0-beta.11`，本次无 App 变更。

## dsh-links 0.1.0-beta.5 — 2026-08-22

- 安全加固：设备 token 落盘哈希由裸 SHA-256 换为每安装 HMAC-SHA256；旧哈希在下次认证时自动迁移，手机无需重新配对。
- 安全加固：面板二维码 URL 不再携带配对码（服务端本就不读该参数），配对码不再进入 web 访问日志；配对码轮换改为重挂载取图。
- 安全加固：本机 RPC 方法抽为 `RPC_METHOD_ALLOWLIST` 闭集并在 `callLocalRpc` 内发出请求前强制校验，新增单测锁定闭集与全部调用点，防止未来重构引入开放转发。
- 防御性收口：SSE 补历史期间 mux 排队事件加 2000 条上限，超限丢弃并保留强制轮询兜底。
- 文档：标明云端二维码内含 Relay 路由凭据（`routeSecret`）、与接入码同等敏感；补充手机端审批 5 分钟超时行为说明。
- Android 客户端 `0.5.0-beta.10`：更新启动图标。

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
