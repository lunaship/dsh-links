# DeepHarness 薄原生壳 + Web 工作台 执行方案 v2

> 版本：2.0（2026-08-18）
> 基于：《DeepHarness-薄原生壳-Web工作台-具体实施方案》v1
> 状态：Gate 0 已完成，进入 Gate 1
> 项目：`/Volumes/Space/Dev/DeepHarness`

---

## 0. 决策摘要（四条，不可动摇）

| # | 决策 | 与 v1 的差异 |
|---|---|---|
| D-1 | **坚定走 Web 单线**，不搞双引擎赛马。双引擎开关仅作为观察期回滚保险，观察结束后删除原生工作台 | v1 是"赛马 + 数据决定"，本版改为"确认后拆桥"（确认 = 14 天真机观察） |
| D-2 | **移动布局由 App 注入**（WebActivity + assets），插件**不注入任何页面** | v1 让插件 client.js 持有布局。本版改为 App 持有：桌面 100% 无污染、注入物理隔离 |
| D-3 | **插件纯后端**：只做配对、设备管理、18640 代理、手机 API、ticket/session、轻量通知事件流 | 与 v1 一致，边界更清晰 |
| D-4 | **安全改造优先**：凭据分层（token → ticket → 短期 session），ticket 改造完成前 Web 主路径不得发布 | 与 v1 一致 |

**为什么 D-2（布局归 App）而不是 v1 的插件持有：**
- 用户曾因插件注入导致桌面网页也变移动端（激活条件 `max-width:1023px` 太宽，桌面缩窗即触发）；
- 注入发生在 App 的 WebView 进程内 = **物理隔离**，桌面浏览器永远加载不到移动布局代码；
- 符合"DSH Web UI 一行都不改"的原则：桌面端是永不触碰的黑盒；
- 代价：布局更新随 APK 发布。单人维护、自行打包，可接受。

---

## 1. 现状与已完成工作

### 1.1 已完成（Gate 0 全部通过）

- [x] Git 基线：仓库 `lunaship/dsh-deepharness`（private），`main` 分支
- [x] 基线 tag：`native-alpha-0.4.1`（三线并存版本，回滚点）
- [x] `.gitignore`：排除 `local.properties`、`build/`、`node_modules/`、签名材料、token/state 文件（已修复 `token*` 误伤 `TokenCrypto.kt`）
- [x] 代码按簇分包：`core/`（共享）、`devices/`（设备中心）、`web/`（薄壳）、`native/`（原生工作台冻结区）
- [x] 根目录截图归档至 `evidence/screenshots/`
- [x] README 文件地图
- [x] 构建验证：`assembleDebug` + `testDebugUnitTest` 通过

### 1.2 现状风险（迁移要解决的）

| ID | 级别 | 风险 | 状态 |
|---|---|---|---|
| F-001 | 高 | 三线并存（原生工作台 / WebActivity / NativeShell） | 已分包，仍并存 |
| F-002 | 高 | 长期设备 token 直接写入 WebView Cookie（`WebActivity.kt` `applyAuthCookie`，无 HttpOnly/有效期） | 待修 |
| F-003 | 高 | 18640 匿名暴露 `pair-info` / `qr.png`，持 token 可访问完整 DSH（`forwardHttp`） | 待修 |
| F-004 | 中 | 移动布局双所有权：App `injectMobileLayer`+`mobile_override.css` 与插件 `client.js` 同时注入 | 布局归 App 后消除 |
| F-005 | 中 | 通知深链固定打开原生工作台（`DshNotifier`） | 待改 |
| F-006 | 高 | 缺少 Web 主路径完整真机证据 | 观察期解决 |

---

## 2. 目标架构（谁管什么）

```
┌─────────────────────────────────────────────────────────┐
│  Android App（薄原生壳）                                 │
│  ├─ devices/   设备中心（保留，唯一入口）                 │
│  ├─ web/       WebActivity（工作台载体，核心）            │
│  │   └─ 注入移动适配层（布局 + 深链桥 + 会话定位）        │
│  ├─ core/      HostStore/TokenCrypto/PairClient/DshNotifier/DshTheme
│  └─ native/    原生工作台（Gate 5 后归档删除）            │
└─────────────────────────────────────────────────────────┘
                          │ WebView + WebSocket（ticket → 短期 session）
                          ▼
┌─────────────────────────────────────────────────────────┐
│  DeepHarness 插件（纯后端）                              │
│  ├─ 配对：配对码 / deviceId / token 签发与吊销            │
│  ├─ 18640 代理：web-tickets → web-bootstrap → session    │
│  ├─ 手机 API：/dsh-link/mobile/*（会话/审批/设置/模型）   │
│  ├─ 轻量通知事件流（approval-required / completed / stopped）
│  └─ 「手机连接」设置面板（module2.js，唯一前端入口）      │
└─────────────────────────────────────────────────────────┘
                          │ 认证后转发
                          ▼
              DSH Web UI（127.0.0.1:3080）
              桌面原样，一行不改
```

**边界铁律：**
- 插件**不得**向 DSH 页面注入布局代码（移除 `inject: ["webServer"]` 中对 client.js 的注入）；
- App **不得**把移动适配代码塞进插件；
- DSH Web UI **永远不被修改**（桌面、手机浏览器、App 三端同一份，互不干扰）；
- 移动布局只有 App 一个 owner。

---

## 3. 移动布局方案（本版核心修订）

### 3.1 移动适配层内容（全部由 App 注入）

| 能力 | 载体 | 说明 |
|---|---|---|
| 布局 CSS | `assets/mobile-override.css`（现有 182 行，扩展）| 安全区、抽屉、输入卡、弹层、会话行 |
| 布局 JS | `assets/mobile-client.js`（由插件 client.js 迁移，2270 行）| FAB、抽屉开合、会话行 tap 自动关闭、网格锁定 |
| 会话定位桥 | `assets/mobile-client.js` 内 | 监听 `dsh-mobile-open-session` CustomEvent，用 DSH 自身路由打开会话 |
| 视图 meta | `WebActivity` 注入 | `width=device-width, initial-scale=1`（如页面缺失） |

### 3.2 迁移步骤（Gate 1 工作项）

1. **摘除插件注入**：`dsh-deepharness/src/index.js` 移除 `inject: ["webServer"]` 及 client.js 的注入逻辑；`client.js`、`build-client.mjs`、`vendor/` 移出插件目录；
2. **迁入 App**：`client.js` → `app/src/main/assets/mobile-client.js`，由 `WebActivity.injectMobileLayer()` 改为注入移动适配层（CSS + JS 统一在 `onPageFinished` / `shouldInterceptRequest` 注入）；
3. **激活条件**（注入后仍判断）：WebView 自定义 UA 加标记 `DshMobile/1.0`，移动适配层只在该 UA 下激活，宽度 ≤768px 为第二条件（防平板横屏）；桌面浏览器物理上不可能加载到注入代码，此判断仅为 App 内自控；
4. **删除双所有权**：移除 App CSS 与插件 JS 的重复/冲突规则，mobile_override.css 收敛为移动适配层唯一 CSS；
5. **验证**：电脑浏览器任意宽度打开 DSH → 桌面 UI 原样；App WebView → 移动布局完整。

### 3.3 深链与会话定位

通知点击路径：

```
通知(hostId+sessionId) → WorkspaceLauncher → WebActivity
→ 获取 ticket → web-bootstrap → 注入层 dispatchEvent('dsh-mobile-open-session', {sessionId})
→ 注入层用 DSH 路由打开会话 → 回传 open-session-result
```

- 注入层收到事件后校验 sessionId 格式与存在性，用 DSH 当前客户端路由打开目标会话；
- 失败必须回传失败原因，不能静默。

### 3.4 布局更新流程

改布局 → 改 `assets/mobile-client.js` 或 `mobile-override.css` → 发 APK。DSH DOM 结构性变化时，移动适配层同步更新并**在 capabilities 中声明适配的 DSH 版本**；找不到关键 DOM 时显示"当前 DSH 版本尚未适配"，不静默破坏布局。

---

## 4. 凭据与认证设计（与 v1 一致）

### 4.1 凭据分层

| 凭据 | 存储 | 生命周期 | 权限 | 是否进 WebView |
|---|---|---|---|---|
| 配对码 | 仅本机 DSH 管理界面 | 5 分钟、一次有效 | 换设备身份 | 否 |
| 设备 token | Android Keystore | 长期、可轮换/吊销 | Mobile API、换 ticket | 否 |
| 启动 ticket | 插件内存 | 30 秒、单次 | 换 Web session | 仅一次性 URL |
| Web session | CookieStore + 服务端 session 表 | 30 分钟滑动续期 | 代理 DSH Web | 是（HttpOnly） |

### 4.2 配对流程

```
用户 → 本机 DSH Web「手机连接」面板 → 显示一次性配对码/二维码
App 扫码 → POST /dsh-link/pair → 校验一次性/有效期/限流 → 返回 deviceId+deviceToken
App 存 Keystore → 插件立即使配对码失效
```

规则：
- 18640 **不提供**匿名 `pair-info` / `qr.png`（移除现有匿名路由）；
- 配对码只能从本机 3080 管理界面获取；成功即失效；同来源 5 次失败冷却 15 分钟；
- 设备以 `deviceId` 标识，同名不能静默替换；token 只存哈希。

### 4.3 Web 工作台启动

```
App(Keystore 读 token) → POST /dsh-link/mobile/web-tickets (x-dsh-link-token)
→ 插件签发单次 ticket(30s) → App 打开 /dsh-link/web-bootstrap?ticket=...
→ 插件消费 ticket → Set-Cookie dsh_web_session(HttpOnly, SameSite=Strict, Max-Age=1800) + 302 /
→ WebView 用 Cookie 请求页面/API/WebSocket → 插件认证后转发 127.0.0.1:3080
```

### 4.4 接口

- `POST /dsh-link/mobile/web-tickets`：请求头 `x-dsh-link-token`；响应 `{ticket, expiresAt, bootstrapPath}`；ticket ≥256bit 随机、只存哈希、30s 过期、单次消费、绑定 deviceId+host；
- `GET /dsh-link/web-bootstrap?ticket=`：成功 302 + Set-Cookie（HTTPS 加 Secure）；失败 400/401/410/429；
- session：30 分钟滑动续期、单设备最多 3 个活跃 session、吊销即清、切换主机清 Cookie、401 退出到设备中心。

---

## 5. WebView 硬化（与 v1 一致）

- `WebActivity` 只接收 `hostId`（可选 `sessionId`/`routeIntent`），**禁止 Intent 传 token**；
- Release 关闭调试；HTTPS 主机 `MIXED_CONTENT_NEVER_ALLOW`；`allowFileAccess=false`；默认拒绝第三方 Cookie；Safe Browsing；
- 禁止 `file://`、`content://`、`intent://`、外部域名静默导航；外链交系统浏览器并提示；
- 相机/麦克风权限只授予页面实际请求且用户已批准的；
- 原生↔Web 通信：origin 受限的 WebMessage 通道或一次性 `evaluateJavascript`，**禁止宽泛 addJavascriptInterface**；消息白名单：`ready` / `open-session-result` / `request-file` / `download` / `notification-subscription-state` / `auth-expired`，全部校验 origin/类型/字段/长度/hostId。

---

## 6. 系统桥（与 v1 一致）

| 能力 | 现状 | 目标 |
|---|---|---|
| 文件选择/相机 | WebActivity 已有 `onShowFileChooser` | 保留，走 `content://` + FileProvider |
| 下载 | 已有 `DownloadListener` | 保留；文件名防目录穿越 |
| 通知 | `DshNotifier` 深链原生工作台 | 深链改 Launcher → WebActivity |
| 后台通知 | 无 | 插件轻量事件流（approval-required / session-completed / session-stopped），App 订阅生成系统通知；V1 限定"App 进程存活时有效" |
| 返回键 | 已有 `handleBack` | 保留 |
| 认证失效 | 无 | 401 → 退出到设备中心，要求重新配对 |
| 网络切换/进程重建 | 部分 | 补全 |

---

## 7. 执行阶段（Gate）

### Gate 0 ✅ 已通过（见 1.1）

### Gate 1：移动布局收归 App（1-2 天）

**工作项：**
1. 插件移除 webServer 注入；client.js/build-client.mjs/vendor 移出插件；
2. client.js → `app/src/main/assets/mobile-client.js`；WebActivity 注入管道改造（CSS+JS 统一注入）；
3. WebView 自定义 UA 加 `DshMobile/1.0`；激活条件（UA + ≤768px）；
4. 合并去重 mobile_override.css 与 client.js 样式规则；
5. 深链桥 `dsh-mobile-open-session` 落地。

**验收：**
- [ ] 桌面浏览器任意宽度打开 DSH → 桌面 UI 原样（截图对照）
- [ ] App WebView → 移动布局完整可操作（抽屉/输入/会话行/审批弹层）
- [ ] 插件目录不再包含 client.js；`rg 'dsh-mobile-hanui' dsh-deepharness/` 无结果
- [ ] `assembleDebug` 通过
- [ ] 通知点击可定位到正确会话（web 模式）

### Gate 2：安全改造（2-3 天）

**工作项：**
1. 移除 18640 匿名 pair-info/QR；
2. 配对码一次性、限流、按 deviceId；
3. 插件新增 web-tickets / web-bootstrap / session 存储；
4. WebActivity 不再写 token Cookie（删除 `applyAuthCookie` 的 token 逻辑）；
5. 插件按 session 认证 HTTP + WebSocket；吊销即断开；
6. 日志脱敏。

**自动化测试（node --test）：**
- [ ] 匿名读 pair-info 被拒；配对重放失败；
- [ ] ticket 30s 后失败；二次消费失败；
- [ ] session 能加载页面/插件资源/RPC/WebSocket；
- [ ] 过期 401；吊销立即失效；
- [ ] 日志无 token/ticket/Cookie 值。

**停止条件：** 若 rc.7 Web 资源在短期 session 下无法完整加载，不得恢复长期 token Cookie，先定位缺失认证路径。

### Gate 3：WorkspaceLauncher + 默认 Web（半天）

- `WorkspaceLauncher`：读 `workspace_engine`（web/native），统一打开 WebActivity 或回退原生；
- 默认 `web`；`native` 仅作故障回退入口（开发设置可见）；
- DevicesActivity / ScanActivity / DshNotifier 深链全部改走 Launcher；
- Web 启动失败显示"返回设备中心"+"使用原生回退"（用户显式选择，不自动降级）。

### Gate 4：真机验收（见第 8 节清单）

### Gate 5：14 天观察期（与 v1 差异：7 天 → 14 天 + 数据阈值）

**记录：** 启动失败 / 白屏 / 输入问题 / 错主机 / 认证失效 / DOM 适配失败 / 回退次数 / 崩溃 / 会话长度。

**切换阈值（数据驱动）：**
- 14 天内 P0 场景事故 = 0，且回退率 < 5% → 进入 Gate 6；
- 出现 P0 事故或回退率 ≥ 5% → 默认切回 native，继续修 Web，不扩展原生功能；
- 观察期覆盖：7 个连续自然日日常使用、3 个不同长度真实会话、2 台主机、1 次 DSH 重启、1 次吊销重配对、1 次插件更新、1 次 App 升级安装、低端机（3GB RAM）长会话。

### Gate 6：归档原生工作台（观察通过后）

1. 为原生版本打 tag + 归档 APK/测试/截图 → `docs/archive/native-workspace/`；
2. 删除：`native/WorkspaceActivity`、`SettingsActivity`（原生复刻部分）、`DshIcons`、`DshMotion`、`MathRenderer`、`CommandPalette`、`HistoryMerge`、`ui/`、`util/`；
3. 收缩：`MobileApi` 只留容器所需（capabilities/通知）；`SessionStreamClient` 改轻量通知流或删；
4. 清理 Manifest、资源、测试；UI-CHECKLIST 改为 Web 移动适配验收表；
5. 全量构建 + Lint + 真机验收。

**禁止：** 同一 commit 同时切默认与删回退；无 tag 就删；用"看起来能用"代替 Gate 4/5。

---

## 8. 真机验收清单（P0 必过）

| # | 场景 | P0 | 必须结果 |
|---|---|---|---|
| A-01 | 两台不同主机 | ✅ | 主机与 Cookie 隔离 |
| A-02 | 创建/切换会话 | ✅ | 无错主机、错会话 |
| A-03 | 发送文本并停止 | ✅ | 实际会话收到并停止 |
| A-04 | 长会话滚动加载（含 3GB 低端机） | | 不卡死、不跳位置 |
| A-05 | reasoning/工具/todo/统计 | | DSH 原生组件正确显示 |
| A-06 | 手机审批允许/拒绝 | ✅ | 会话继续或停止 |
| A-07 | Web 先响应审批 | | 手机状态同步 |
| A-08 | 图片/文件上传 | ✅ | DSH 实际收到 |
| A-09 | 相机拍摄上传 | | 权限与 FileProvider 正常 |
| A-10 | 下载产物 | | 认证有效、系统下载成功 |
| A-11 | 模型/权限/Agent 预设 | | 原生设置生效 |
| A-12 | 插件页面 | | 能加载并操作 |
| A-13 | 键盘/侧边栏/弹层/返回 | ✅ | 无遮挡穿透 |
| A-14 | Wi-Fi 断开恢复 | ✅ | 报错并可恢复 |
| A-15 | DSH 重启 | | session 失效可重启 |
| A-16 | 通知点击目标会话 | ✅ | 正确 hostId/sessionId |
| A-17 | 吊销当前手机 | | 立即失效 |
| A-18 | 320/360/375/414/768dp | | 无不可操作布局 |

---

## 9. 回滚设计

| 层 | 手段 |
|---|---|
| 代码 | `native-alpha-0.4.1` 基线 tag；每 Gate 独立 commit；任一步可回到上一 Gate |
| 运行时 | Gate 1-3：开发设置切 native；观察期：默认切回 native |
| 数据 | HostStore 升级带版本号+迁移测试；旧 token 一次性迁移后轮换；Web session 不持久化；删原生不删 DSH 会话数据 |
| 认证 | 异常时关闭 web-tickets 入口，设备管理仍可用；服务端撤销全部 session 不撤 token |

---

## 10. 完成定义

- [ ] 插件无任何页面注入，纯后端（配对/API/代理/ticket/session/通知事件流）
- [ ] 移动适配层由 App 注入，桌面浏览器打开 DSH 与改前完全一致
- [ ] 设备 token 从未进入 WebView/Cookie/URL/JS；WebView 用一次性 ticket 换短期 HttpOnly session
- [ ] 吊销立即关闭 session；多主机 Cookie/页面/通知完全隔离
- [ ] 通知能打开正确主机与会话
- [ ] Gate 4 的 P0 全过真机；观察期 14 天无 P0、回退率 < 5%
- [ ] 原生工作台有 tag、归档 APK、恢复说明后才删除
- [ ] 测试/Lint/签名 release/发布证据完成；公网保持关闭

---

## 11. 下一步（获得授权后）

**只执行 Gate 1**：插件摘除注入 → client.js 迁入 App assets → WebActivity 注入改造 → UA 标记 → 深链桥。不碰认证、不碰原生工作台、不碰 DSH 本体。Gate 1 验收后再执行 Gate 2。
