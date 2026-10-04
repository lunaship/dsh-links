# iOS 视觉规则（不可违反）

> 适用：DeepLinks iOS（`apps/ios/`）。页面编号与 `docs/redesign-v4/design-v4.html`、`docs/ios/PLAN.md` 附录 A 一致。
> 与设计稿 PNG（`docs/design/`）冲突时以 PLAN 为准；PNG 只定布局与层级，禁止取色、禁止按像素复刻。

## 1. 唯一方向

**Apple HIG + 系统组件 + 单一品牌色。** 内容层实色；功能层（导航栏、工具栏、输入区、决策栏、弹层）由系统提供玻璃。

## 2. 颜色

只用系统语义色 + `AccentColor` + `BrandFill`。状态色：`systemOrange`（等待 / 风险）、`systemGreen`（完成 / 增）、`systemRed`（失败 / 删 / 危险）。禁止写死其他色值。

| Token | 浅色 | 深色 | 用途 |
|---|---|---|---|
| `AccentColor` | `#3F5BD6` | `#8B9DFF` | 文字按钮、图标、选中、tint |
| `BrandFill` | `#3F5BD6` | `#4C66E6`（暂定） | 品牌实心按钮底，白字 |

同屏最多一个品牌实心按钮。首页给「新任务」；列表里的「允许一次」用 `.bordered` + accent tint。

## 3. 文字

只用动态字体文字样式：`.largeTitle` `.title3` `.headline` `.body` `.subheadline` `.footnote` `.caption`。代码、路径、命令、模型 ID 用 `.monospaced()`。禁止写死字号。

## 4. 形状

控件用胶囊；容器用同心圆角（`ConcentricRectangle` / `containerShape`）；行内代码与小标签固定 6pt。不自定义其他圆角。

## 5. 间距

系统默认边距优先；自定义间距只用 4 的倍数（4–32）。触控 ≥ 44pt。

## 6. 玻璃规则

**允许**

- 系统导航栏、工具栏、`.sheet`、`Menu`、`alert`、`confirmationDialog`（自动带玻璃，不额外处理）。
- 自定义玻璃只有两处：输入区（`DLComposerView`）与决策栏（`DLDecisionBar`），共用一个容器。
- 浮在相机画面上的关闭按钮。
- 按钮样式：独立浮在内容上的主操作用 `.glassProminent`（BrandFill），次操作用 `.glass`；**放在玻璃容器内部**（输入区、决策栏）的按钮用 `.borderedProminent`（BrandFill）/ `.bordered` 实色，不叠玻璃。
- 同屏最多一个品牌实心按钮：首页给「新任务」，列表里的「允许一次」用 `.bordered` + accent tint。

**禁止**

- 消息气泡、卡片、列表行、代码块、diff、横幅、状态槽使用玻璃。
- 玻璃里再套玻璃。
- 自己写模糊或半透明背景去模仿玻璃。
- 依赖玻璃的具体透明度。必须在以下设置下都清晰可用：降低透明度、增强对比度、iOS 27 玻璃着色滑杆的两端。

## 7. 组件清单

封装组件（`DLUI`）：`DLStatusSlot` `DLInboxRow` `DLComposerView` `DLDecisionBar` `DLChip` `DLProcessLine` `DLCodeBlock` `DLEmptyState` `DLBanner`。

1.x 配对页面直接使用 `PhotosPicker`、`DataScannerViewController`（不支持时 `AVCaptureSession`）、系统 `alert` 与改名 `Form` sheet；扫描器桥接留在 App，不新增 DLUI 组件。

系统组件直接用：`NavigationStack` `List` `Form` `.sheet` `confirmationDialog` `alert` `Menu` `Picker` `Toggle` `contextMenu` `swipeActions` `searchable`。新增封装组件先改本文件。

## 8. 动效

系统弹簧动画；决策栏 / 输入区用 `glassEffectID`（或 UIKit 对应）形变。尊重「减弱动态效果」。

## 9. 辅助功能

触控 ≥ 44pt；VoiceOver 标签齐全；降低透明度、增强对比度、粗体文本、最大辅助字号下无截断与重叠。

## 10. 截图矩阵

- 设备：iPhone 17 Pro（iOS 26.x）基线；iPad / 宽屏在 I4.8 验收。型号与系统版本改动需维护者批准。
- 主页面：浅 / 深 × 中 / 英 × 默认 / 大字号共 8 张，外加浅色、中文、默认字号的「降低透明度」1 张。
- I4.1 其余状态各 1 张（浅色、中文、默认字号）；同名 / 改名各加 1 张英文，完整登记见 page-mapping.md。
- 基线只允许由 `ios-regen-screenshots.yml` 生成；禁止提交本地基线。

## 11. 禁止清单

自制模糊；玻璃叠玻璃；内容层玻璃；写死字号与色值；从设计稿 PNG 取色或按像素复刻；同屏两个品牌实心按钮；审批做滑动手势；引入统计 / 崩溃 SDK；复制 AGPL / GPL 源码。
