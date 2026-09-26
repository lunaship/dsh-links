# 视觉规则（不可违反）

面向 DSH Links Android 客户端（本仓 `deeplinks`）。对照基线：DeepSeek Harness / DeepSeek Chat。  
Paseo 只借鉴「安静 chrome、少实心 CTA、灰阶/字重分层」——**不是**视觉主轴。

## 十条

1. **色源唯一**：运行时颜色只走 Compose `DshTheme` / `DshColors`（及代理 `Dsh.*`）。XML `values/colors.xml` 与 `values-night/colors.xml` 必须与同一套 DSH 色对齐，禁止再养一套「设备页墨色」。**XML 运行时色名一律 `dsh_*`；禁止再引入 `ink_*` 作为第二套色系统。**
2. **DeepSeek 色主轴**：深色画布近黑（`#0E0E10` 族）；唯一高饱和强调色钉死 DeepSeek Blue（主色 `#4D6BFE` / `brand500`）。用户气泡、发送键、选中会话、链接、实心主按钮用这一族。Material You 动态取色只动表面 / 灰阶文字，**不得**用壁纸色替换 `brand400` / `brand500` / `brandTint` / `traceReasoning` 等品牌 token。
   **发送槽规格（Mic / Send / Stop 同一槽）**：空态语音 = `bgTrack` 圆钮（与输入条「+ / 设置」同规格，只是大一号）；可发送 / 运行 / 录音 = `brand500` 实心；禁用 = `brand500` 55%；出错 = `error`。状态只换图标，不靠填色重复报状态；**禁止**给这个槽上墨黑/反白实心。
3. **禁墨色主轴**：禁止墨黑（`#333`）或反白近白当主按钮色；禁止把旧 HStudio 墨色体系当产品主语言。Splash / Devices / Settings / 聊天必须像同一产品。
4. **文字灰阶**：高对比正文（`labelPrimary`）+ muted 次要（`labelSecondary` / `labelTertiary`）；层级靠灰阶与字重，不靠第二套高饱和色。
5. **ThinkingTrace 规格**：面板背景比画布更深一档（`bgRecessed`）；左侧约 **2dp** DeepSeek Blue 竖条；CoT / 思考正文 **dimmed + italic**；折叠标题清晰（「思考 · Ns」/ `Thought · Ns`）；最终回答 upright 高对比；助手消息无气泡全宽。
6. **轨迹色降噪**：推理用蓝系弱强调（`traceReasoning`）；禁止亮紫与品牌蓝抢同一层级；审批/警告保留语义色但降噪。
7. **单 CTA**：每个表面最多一个实心 accent 主按钮；其余 ghost / outline / 文字按钮。不要为了「像 Paseo」把 accent 改成灰黑。
8. **Chrome 安静**：Devices / Settings / 侧栏少装饰、少抢戏像素风与第二强调色；状态写进操作本身，不堆 chrome（见 `ui-parity.md`）。
9. **插件不在本仓改**：配对插件、协议、SSE、Relay 在 `../dsh-links`；本仓只改 Android App 视觉与客户端体验。禁止改插件仓。
10. **版本与发布边界**：本视觉迭代不升 `versionName` / `versionCode`；不做大翻导航信息架构；正式签名与推送规则见仓库根 `CLAUDE.md`。

## 快速对照

| 角色 | Token | 深色示例 | 浅色示例 |
| --- | --- | --- | --- |
| 画布 | `bgBase` | `#0E0E10` | `#FFFFFF` |
| 凹进面板 | `bgRecessed` | `#08080A` | `#F3F4F6` |
| 主强调 | `brand500` | `#4D6BFE` | `#4D6BFE` |
| 链接/次强调 | `brand400` | `#6B86FE` | `#3B5BDB` |
| 推理轨 | `traceReasoning` | `#7B93F8` | `#4D6BFE` |

配套阅读：[`ui-parity.md`](ui-parity.md)、[`ui-redesign-plan.md`](ui-redesign-plan.md)。
