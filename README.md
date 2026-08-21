# DSH Links

Android 原生 DSH 多实例局域网控制台。

**Beta** · **LAN only** · **Unofficial**

DSH Links 是独立的非官方社区项目，与 DeepSeek 官方不存在隶属、授权或背书关系。DeepSeek Harness 名称及相关标识归其权利人所有。

用原生 Android App 在可信局域网内管理一个或多个 [DeepSeek Harness](https://github.com/deepseek-ai) 实例：扫码配对、会话、消息流、工具审批和设备吊销。会话内容由手机直接从你自己的 DSH 主机读取，不经过本项目的云。

> 配对手机等同特权控制台，可驱动具有工具 / 代码执行能力的主机。使用前请阅读 [`SECURITY.md`](SECURITY.md) 与 [`PRIVACY.md`](PRIVACY.md)。

## 开源范围

本仓库是**混合分发**：

| 部分 | 是否公开源码 | 许可 / 获取方式 |
|---|---|---|
| `dsh-links/` 插件、手机 API、文档、`remote/` 实验资料 | 是 | [MIT](LICENSE)（见 LICENSE 中 Open scope） |
| Android App | 否 | 仅通过 GitHub **Releases 正式签名 APK** 安装；客户端源码不开放、不接受外部贡献 |

这样做的原因：插件与 API 需要可审计、可自建；手机端是高权限控制台，用固定签名 APK 分发，避免任意自编译包混入升级链。信任边界以 Release 签名与 [`SECURITY.md`](SECURITY.md) / [`PRIVACY.md`](PRIVACY.md) 为准。

请只从本仓库 Releases 安装 APK；不要安装来路不明的重打包。

## 快速安装

已安装 DSH 的电脑执行：

```bash
dsh plugin --profile web add dsh-links@0.1.0-beta.1
dsh web
```

然后：

1. 手机与电脑连接同一个可信 Wi-Fi。
2. Android 8.0 及以上安装 GitHub Release 中的正式签名 APK。
3. 电脑打开 DSH 设置 → **手机连接**。
4. 手机点「扫码连接」，扫描二维码。
5. 首次连接核对并固定证书指纹；成功后打开工作台。
6. 丢失手机时在电脑端立即吊销设备。

开发期也可从本仓库目录安装插件：`dsh plugin --profile web add /path/to/dsh-links/dsh-links`，改完后必须重启 `dsh web`。

## 兼容矩阵（首发已验证）

| 组件 | 首发验证版本 |
|---|---|
| DSH | `0.1.0-rc.8` |
| dsh-links 插件 | `0.1.0-beta.1` |
| Android App（Release APK） | API 26–35 |
| Node 插件测试环境 | 22 |

App 通过 `dsh-links` 的移动 API 隔离 DSH Web UI 变化；DSH 仍处于 Developer Preview，跨版本兼容性以本表和 CI 结果为准。不要假定未验证的 DSH 版本可用。

## 当前 Beta 承诺

当前公开 Beta **只支持可信局域网**。仓库中的 [`remote/`](remote/) 为实验性研究资料，不属于受支持产品路径。

本轮覆盖：多主机保存与切换、创建 / 搜索 / 重命名 / 分叉 / 归档会话、发送 Prompt、停止任务、切换模型与权限预设、SSE 流与断线重连、工具审批、设备吊销。

本轮不承诺：离开局域网后仍可用、Cloudflare Tunnel / VPN / frp / Relay 的官方支持、手机端设置模型密钥、完整插件管理、远程桌面、Google Play、自动更新或崩溃上报。

## 安全警告

- 配对 token 是访问主机的凭证。丢失设备请立即在 DSH「手机连接」面板吊销。
- `18640` 为自签 TLS；局域网会固定证书指纹。不要把该端口裸暴露到不可信公网。
- 卸载 App 会删除手机本地主机与 Token；仍建议在电脑端吊销设备。

完整说明：[`SECURITY.md`](SECURITY.md)。第三方许可：[`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md)。

## 故障排查

- **设置里没有「手机连接」**：插件未安装、`--profile` 不是 `web`，或安装后未重启 `dsh web`。
- **二维码无法连接**：手机与电脑不在同一局域网、路由器开了 AP 隔离、或防火墙阻止 `18640`。
- **证书已变更**：插件状态目录被删除或主机重装，必须重新核对指纹并配对。
- **返回 401**：Token 已吊销或 App 本地状态失效，重新扫码。旧 Token 不会复活。
- **SSE 断开**：网络切换后等待自动重连；持续失败时重新进入会话。
- **Android 13+ 没有通知**：系统设置里授予通知权限。拒绝通知不影响配对和工作台。
- **DSH 升级后异常**：回到兼容矩阵中的版本，并提交包含 Android / DSH / 插件版本的问题。

## 目录

```
dsh-links/
├── dsh-links/              # 开源：dsh 插件（npm 包 dsh-links）+ 手机 API
├── branding/               # logo 与字体许可（供 Release / 文档使用）
├── remote/                 # 实验性远程访问资料（不受支持）
├── LICENSE                 # 混合许可：插件/文档 MIT；App 闭源
├── PRIVACY.md
├── SECURITY.md
├── THIRD_PARTY_NOTICES.md
└── CHANGELOG.md
```

维护者私有树中另有 Android 工程（包名 `dev.dsh.mobile`）；公开仓库以插件与文档为主，手机端以 Release APK 交付。

## 架构

```
电脑：dsh web + dsh-links 插件（18640 HTTPS / 配对 / mobile API）
手机：正式签名 APK（设备中心 → 工作台：会话 / SSE / 审批）
```

- App 无 WebView；只调用插件定义的 `/dsh-link/mobile/*`，不解析 DSH Web UI。

## 特性

- **原生工作台**：消息流（Markdown 表格/代码块/引用/**LaTeX 公式**）、思考折叠、工具卡、todo 面板、上下文压缩、轨迹视图、ContextMeter、QueueDock、审批卡
- **会话管理**：按工作区分组侧边栏、重命名/分叉/归档/删除、会话搜索、加载更早、已停止标记
- **实时推送**：SSE 长连接（心跳、断点续传、seq 去重、退避重连、断线横幅）
- **通知**：后台时审批请求 / 任务完成 / 会话停止 → 系统通知（需授权）
- **设计**：DSH dsw 设计系统风格（`#151517` 底 / `#679EFE` 品牌蓝，深浅色两套 token）

## 编译与测试（插件）

公开仓库可复现的是 **插件**，不是 App：

```bash
cd dsh-links
node build-client.mjs
git diff --exit-code -- src/client.js
node --test test/*.mjs
```

改 `src/module2.js` 后必须重新生成 `src/client.js`。

Android App 仅由维护者私有工程构建；正式签名材料不进 git，产物只通过 GitHub Releases 发布。

## 手机 API（插件 18640 代理，token 认证）

- `GET  /dsh-link/mobile/bootstrap` — 主机与设备信息
- `GET  /dsh-link/mobile/sessions` — 会话列表（含 origin/agentPreset）
- `GET  /dsh-link/mobile/sessions/search?q=` — 会话搜索
- `POST /dsh-link/mobile/sessions` — 创建会话
- `GET  /dsh-link/mobile/sessions/:id/history?beforeSeq=&maxMessages=` — 历史（分页 + 投影 stats）
- `GET  /dsh-link/mobile/sessions/:id/stream` — SSE 实时流（ready/message/stats + 心跳）
- `POST /dsh-link/mobile/sessions/:id/prompt|cancel|rename|fork|model|approval`
- `GET/POST /dsh-link/mobile/workspaces` — 工作区列表/创建
- `GET  /dsh-link/mobile/models|llm-models` — 模型目录
- `GET  /dsh-link/mobile/devices` — 已配对设备列表
- `POST /dsh-link/mobile/revoke` — 吊销已配对设备
- `POST /dsh-link/mobile/sessions/:id/permission` — 按会话设置权限预设

## 已知限制（Beta）

- 消息「喜欢 / 不喜欢」仅本机提示，尚未上报服务端
- 「重新生成」会重发上一条用户消息（非独立 regenerate RPC）
- 「导出对话」为历史拉取后的文本分享，非官方 session log 文件
- 归档与删除均调用服务端 `workspace.archiveSession`
- 不支持离开局域网后继续使用

## License

- **插件与文档**：[MIT](LICENSE)（LICENSE 中的 Open scope）
- **Android App**：闭源；仅分发正式签名 APK
- 第三方归属：[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)
