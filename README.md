# DeepHarness

一个手机 App 管理多个 dsh（DeepSeek Harness）实例的完整方案：**纯原生 Android（Kotlin + Jetpack Compose）1:1 复刻 DSH Web UI**，配合一个 dsh 服务端插件提供手机 API。

## 目录

```
DeepHarness/
├── app/                    # Android 原生 App（Kotlin + Compose，包名 dev.dsh.mobile）
│   └── src/main/java/dev/dsh/mobile/
│       ├── WorkspaceActivity.kt   # 工作台：会话流 / 输入卡 / 侧边栏 / 轨迹视图 / 审批 / 公式渲染
│       ├── DevicesActivity.kt     # 设备管理 Hub（扫码配对 / 手动添加 / 在线状态）
│       ├── SettingsActivity.kt    # 设置 5 tab：通用 / 模型 / 插件 / Agent 预设 / 关于
│       ├── MobileApi.kt           # 手机 API 客户端（token 认证）
│       ├── SessionStreamClient.kt # SSE 实时流（断点续传 / 去重 / 自动重连）
│       ├── DshNotifier.kt         # 审批 / 任务完成系统通知
│       ├── HostStore.kt           # 主机凭据（Android Keystore AES-GCM 加密存储）
│       └── ...
├── dsh-deepharness/        # dsh 插件（npm 包，装到每个 dsh 实例）
├── remote/                 # 远程访问（Cloudflare Tunnel）配置与文档
└── README.md
```

## 特性

- **原生工作台**：消息流（Markdown 表格/代码块/引用/**LaTeX 公式**）、思考折叠、工具卡、todo 面板、上下文压缩、轨迹视图、ContextMeter、QueueDock、审批卡
- **会话管理**：按工作区分组侧边栏（组内 5 条预览 + 显示全部）、重命名/分叉/归档/删除、会话搜索、加载更早、已停止标记
- **实时推送**：SSE 长连接（15s 心跳、断点续传、seq 去重、1.5s→15s 退避重连、断线横幅）
- **通知**：后台时审批请求 / 任务完成 / 会话停止 → 系统通知，点击直达对应会话
- **多主机**：扫码 / 手动配对，主机切换，局域网 + 远程（Cloudflare Tunnel）并存
- **设计**：1:1 复刻 DSH dsw 设计系统（`#151517` 底 / `#679EFE` 品牌蓝，深浅色两套 token），见 `UI-CHECKLIST.md`

## 安装插件到某个 dsh

```bash
dsh plugin --profile web add /Volumes/Space/Dev/DeepHarness/dsh-deepharness
# 然后重启 dsh web（插件改动也必须重启才生效）
```

重启后 dsh Web UI 设置面板出现「手机连接」分区：二维码 + 一次性配对码（10 分钟有效）、地址列表、设备吊销。插件同时在本机 `0.0.0.0:18640` 起一个带 token 校验的手机接入代理（Host/Origin 重写为本机回环，远程访问无需 `--trusted-host`）。

## 编译与测试

```bash
./gradlew :app:assembleDebug        # 产物 app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest    # 单元测试（HostStore 去重/序列化、SSE 去重/退避）
```

> 注意：改 Kotlin 后若怀疑打进旧 dex，用 `./gradlew :app:clean :app:assembleDebug`。

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
- `POST /dsh-link/mobile/revoke` — 吊销设备（仅主 web 端口）

## 远程访问

手机离开局域网后：见 [`remote/README.md`](remote/README.md)（Cloudflare Tunnel 免费方案，
或仿 dsh-mobile 的 Go relay 自建 VPS 隧道）。
