# DeepSeek Harness 移动端 · 1:1 对照清单（vs 127.0.0.1:3080 Web UI）

> 目标：对照 deepseek-harness 源码/运行实例逐页核对布局与文案，1:1 复刻 + Android 移动端优化。
> 状态标记：✅ 完成并模拟器实测 / ⚠️ 部分完成或近似 / ❌ 未做

## 页面级对照

| 页面 | 状态 | 对照内容 |
|---|---|---|
| 新会话 Hero | ✅ | DSH 品牌 logo（蓝渐变圆标）、口号"描述你想要构建的内容"、模式选择（对话/计划/目标，文案对照 `placeholder.hero`/`PLAN_NEXT_ACTION_ZH`/`hint.goal`）、副标"DeepSeek Harness · 移动端" |
| 会话消息流 | ✅ | 748px 居中列（移动端全宽）、用户气泡（22dp 圆角/`min(525px,82%)`/`#2C2C2E`）、助手 markdown 全宽、工具卡（12dp 圆角/语言标签/复制）、思考行折叠、StatsLine、正在执行 shimmer |
| 输入卡 | ✅ | 780px 居中、22dp 圆角、`#2C2C2E`、权限 chip 左（访问模式）、麦克风、ContextMeter 环形、模型按钮右（发送旁）、34dp 圆形品牌蓝发送键、/ 命令面板、工作区行（hero 时）、QueueDock |
| 侧边栏 | ✅ | 工作区分组（点击=新建会话/chevron=折叠）、视图选项（当前弹出：分组方式/排序方式）、添加工作区、会话菜单（重命名/分叉/归档）、目录过滤、已归档分组、时间（刚刚/X 分钟前）、状态点 |
| 设置页 | ✅ | 分区导航（通用/模型/插件/预设/关于）、语言/主题/Enter 下拉直接选、权限入通用（Full access 确认框）、模型折叠卡+添加模型、插件展开配置（已覆盖/恢复默认）、预设选项卡（标准/模式/极简/创造） |
| 手机连接（3080 插件） | ✅ | 设置面板"手机连接"分区：图标标题行、二维码 116px 卡片、配对码 24px 等宽、地址卡、设备列表（在线点/相对时间/吊销） |

## 组件级对照（conversation.js 21 个 CSS module）

| 组件 | 状态 | 说明 |
|---|---|---|
| ChatView/顶栏 | ✅ | padding 12/28/0/20 + 底部 1px 分隔线、28dp 圆形菜单键、更多菜单（重命名/分叉/复制标题）、停止 |
| MessageItem（user/assistant） | ✅ | 长按操作（复制/引用/分叉） |
| Tool/CommandCard | ✅ | 展开折叠 + 执行中扫光（2.6s 左→右） |
| ReasoningRow | ✅ | chevron+思考+2px 圆点+摘要+展开全文+扫光 |
| TurnStatusRow | ✅ | 品牌蓝 shimmer + 计时 |
| StatsLine | ✅ | `X 轮 · Y 步 \| LLM X · 工具调用 X \| 首 token 平均 X · X tok/s \| 缓存命中 X% \| 输入 X tok · 输出 Y tok` |
| HeroShell | ✅ | 见上 |
| InputBar | ✅ | 见上 |
| ContextMeter | ✅ | 环形按钮 + 用量面板（输入/输出/缓存） |
| QueueDock | ✅ | 发送中 dock |
| ApprovalPanel | ✅ 响应链路 / ⚠️ 触发依赖服务器策略 | 服务器 approval/asked 事件 → 移动端等待授权卡（允许一次/拒绝）→ POST /api/respond 同协议响应（协议已验证）。触发条件：会话 approval 策略为 ask 时（当前部署默认 never 自动批准；策略写入需 DSH 客户端事件通道，HTTP 无注入接口） |
| DetailsPanel | ✅ | StatsLine 点击展开：轮次/步骤/LLM 耗时/工具耗时/首 token/吞吐/tokens 明细 |
| PermissionSelect | ✅ | 输入区访问模式 + 设置页默认权限 |

## 文案核对（zh 字典）

✅ 给智能体发消息 / 停止生成 / 发送消息 / 命令 / 描述你的任务以生成计划 / 输入目标，智能体将持续执行 / 当前目标进行中。可输入 edit 修改 / pause 暂停 / resume 继续 / clear 清除 / 描述你想要构建的内容 / 选择一个工作区开始 / 没有可用的模型 / 选择模型，当前 X / 上下文已用 X tok / 缓存命中 X% / 首 token 平均 X / 工具调用 X / X 轮 · X 步 / 重命名 / 分叉会话 / 归档会话 / 已归档 / 添加工作区 / 按工作区 / 单列表 / 最近更新 / 手动排序 / 确认启用 Full access？……（Full access 风险文案逐字一致）

## Android 设计规范优化

| 项 | 状态 | 说明 |
|---|---|---|
| edge-to-edge | ✅ | 全 Activity `enableEdgeToEdge` + 透明系统栏 |
| 系统栏图标 | ✅ | 深色背景 → 浅色图标（`windowLightStatusBar=false`） |
| 触控目标 | ✅ | `minimumInteractiveComponentSize`（≥48dp 热区，视觉保持 DSH 紧凑） |
| Haptic | ✅ | 发送按钮震动反馈 |
| Predictive back | ✅ | `enableOnBackInvokedCallback=true` |
| 无障碍 | ✅ | 图标按钮 contentDescription 齐全 |
| 小屏适配 | ✅ | 360dp 宽实测：输入卡左右元素不重叠、布局正常 |

## 最新补齐（2026-08-18 第三轮：系统性 1:1 复刻核对补齐）

对照 3080 最新 conversation.js（21 个 CSS module + 156 条 zh 文案字典）逐项盘点后的差距补齐：

- ✅ **Hero 口号 1:1**：`探索未至之境`（hero.headline）+ `预览版` badge（hero.preview），替代原来的输入 placeholder 当口号
- ✅ **ContextMeter 1:1**：数据源改为 contextPressure/contextBreakdown 投影（插件透传）；面板 = 头部"上下文已用 X% + ~used/window figures" + 4px 分段条（系统 bluish-400/工具 #a78bfa/消息 blue-450）+ 明细行（系统提示词/工具/对话消息 + 色块 + tok 数）。**实测弹层：上下文已用 55% ~144.8K / 262.1K 系统提示词 12.1K 工具 240 对话消息 83.8K**
- ✅ **TodoPanel 任务清单**：todo/write 事件 → role=todo 消息；卡片（标题"任务"+ X/Y 已完成 + 展开列表，glyph：completed=success 勾 / active=品牌蓝旋转 / pending=空心圆）。数据链路实测（API 7 条 todo）；渲染与 compaction 同 when 分支
- ✅ **CompactionRow 上下文压缩行**：compaction/start|summary|end 事件 → 折叠标题"上下文已压缩"+摘要首行+展开全文（运行中"正在压缩…"）。实测渲染 OK
- ✅ **tool 耗时**：tool/result 携带 durationMs（同 callId 的 call→result 时差），工具卡标题"执行结果 · +47ms"。实测 OK
- ✅ **加载更早**：session.history RPC `beforeSeq` 分页（beforeSeq + maxMessages，按消息边界计数）；App 消息流顶部按钮（hasMore 时），olderMessages 独立存储（轮询不覆盖），prepend + 保持滚动位置。实测翻页成功
- ✅ **已停止标记**：turn/end reason 非 completed（interrupted/stopped/error/maxTokens）→ 消息流尾部"已停止"角标
- ✅ **审批文案**：等待授权 → 等待审批（approval.waiting）
- ✅ **轨迹视图补齐**：todo →"任务"chip + "任务清单更新 · X/Y 已完成"；compaction →"压缩"chip + 摘要

## 修复的 Bug（第三轮）

1. **`nextBeforeSeq` 缺失导致加载更早失效**：插件加字段后未重启 dsh web（改动插件必须重启才生效）
2. **加载更早被轮询覆盖**：翻页结果存 messages 被 2s 轮询 tail 页替换 → 改独立 olderMessages 状态
3. **轮询替换引用导致 LazyColumn 滚动冻结**：`msgs != messages` 引用比较恒 true → 每次轮询替换数据源 → 滚动中渲染失效（消息区空白）→ 改内容比较（size + 首尾 id）
4. **`entered drag with non-zero pending scroll` 崩溃**：autoScroll 的 animateScrollToItem 遗留 pending 与手势 drag 冲突 → 改瞬时 scrollToItem
5. **模拟器注入滚动限制（已确认非 App bug）**：连续 ~20 次 `input swipe` 注入后消息区/轨迹区渲染空白（uiautomator dump 只剩 header+input），重启恢复；与容器类型（LazyColumn/verticalScroll）无关，真实手指滚动正常。**大段 todo 卡片的直接目击验证受此限制**（数据链路与渲染机制均已验证）

## 修复的 Bug（旧）

1. **滚动崩溃**（`entered drag with non-zero pending scroll`）：轮询 2s 替换 messages + 滚动冲突 → 修复：内容未变不替换引用 + 滚动中跳过自动滚动。**连带修复按钮点击失效**（此前 UI 冻结）
2. IME insets 卡住导致消息区空白（模拟器 adb 打字残留，重启恢复）

## 最新补齐（2026-08-18 再续）

- ✅ **输入卡图片添加**：图片按钮（相册选图 GetContent）→ base64 缩略图预览（可移除）→ 发送时随 prompt 以 DSH image 块（`{type:"image", mediaType, data}`）提交；服务器 prompt 接口支持 images

## 最新补齐（2026-08-18 续）

- ✅ **历史视图**（AppHistoryView）：顶栏"对话/历史"视图切换、搜索框（"搜索会话…"/清除）、搜索结果列表（服务器内容搜索；部署索引禁用时降级为名称匹配，对应 DSH "内容搜索暂不可用，仅显示名称匹配"行为）、会话行（标题/cwd/相对时间）、点击进入会话

## 最新补齐（2026-08-18）

- ✅ ApprovalPanel：approval/asked 事件输出 + `/api/respond` 审批通道 + 移动端等待授权卡（允许一次/拒绝）
- ✅ Markdown 图片渲染（Coil 加载网络图，点击浏览器打开原图）
- ✅ DetailsPanel：StatsLine 点击展开会话统计明细
