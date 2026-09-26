# UI 贡献规则（对照 t3code 的工程门禁）

> 目的：让「设计一致性」和「巨型文件」从**约定**变成**可执行的检查**。
> 背景见 `docs/t3code-mobile-comparison.md`；执行进度见 `docs/t3code-improvement-execution-report.md`。

## 1. 设计 token 规则（硬性）

1. **禁止裸字号**：业务代码不得写 `fontSize = N.sp` / `lineHeight = N.sp`。
   - 用语义排版入口 `DshType.*`（`core/DshTypography.kt`）：
     `caption(12/16)` `label(12/16)` `titleSmall(13/18)` `body(15/22)` `title(15/22)`
     `bodyLarge(16/26)` `titleLarge(17/24)` `headline(18/24)` `headlineMedium(20/26)`
     `display(28/34)` `displayLarge(34/40)`。
   - `DshTheme` 已把 `LocalTextStyle` 设为 `bodyMedium`（15/22），因此
     `fontSize = 15.sp, lineHeight = 22.sp` 是**冗余**的，直接删掉即可。
   - 存量以 `app/src/test/resources/design-token-baseline.txt` 登记为**每文件上限**。
2. **禁止裸色值**：不得写 `Color(0x...)`。颜色一律走 `Dsh.*`（`core/DshTheme.kt`）；
   语法高亮走 `DshSyntaxPalette`。唯一允许字面量的文件是 token 定义文件
   （`DshTheme.kt` / `DshTypography.kt` / `DshSyntaxPalette.kt`）。
3. **只允许下调**：以上两份预算文件只允许把数字改小。若因结构性改动必须一次性上调，
   必须在预算文件里写明原因与下调计划（参见 `SettingsActivity.kt` 的导航迁移）。

由 `DesignTokenUsageTest` 强制：新增违规即让 `testDebugUnitTest` 失败。

## 2. 文件体积规则

- `app/src/test/resources/code-hygiene-baseline.txt` 登记超大文件的行数预算
  （`WorkspaceActivity` / `DshIcons` / `DevicesActivity` / `SettingsActivity` / `AppLocale`）；
  未登记文件默认上限 1500 行。
- 拆解时**必须把代码移到新文件**才有效——同文件内抽函数不改善该指标。
- 由 `CodeHygieneTest` 强制。

## 3. 门禁命令

```bash
./gradlew testDebugUnitTest lintDebug :app:assembleDebug validateDebugScreenshotTest
```

CI（`.github/workflows/ci.yml`）按顺序跑：DLR 向量检查 → JVM 测试 + lint → **截图校验** → debug APK → 空白检查。

## 4. 截图基线工作流（AGP Compose Preview Screenshot Testing）

- 预览在 `app/src/screenshotTest/kotlin/.../DesignSystemScreenshotTest.kt`（`@PreviewTest`）。
- **改动 UI 后**：`./gradlew updateDebugScreenshotTest` 更新基准图，随 PR 一起提交。
- 基准图目录：`app/src/screenshotTestDebug/reference/**`（务必提交，否则 CI 会失败）。
- 覆盖矩阵：亮/暗 × 中/英 × 1.0/1.3 字号、412dp 宽。新增屏幕时补一个 `@PreviewTest`。
- 预览刻意不经 `DshTheme`（避开 SharedPreferences 初始化），直接提供
  `LocalDshColors` / `LocalDshStrings`，保证宿主端渲染稳定。

## 5. PR 证据

- 任何**可见 UI 变更**：附前后对比图。
- 涉及**动效/时序/交互**：附短视频。
- 证据上传到 PR，不要提交到仓库。
- PR 尽量小、单一关注点。

## 6. 参考

| 主题 | 文件 |
| --- | --- |
| t3code 对照与差距 | `docs/t3code-mobile-comparison.md` |
| 改造方案 | `docs/t3code-mobile-improvement-plan.md` |
| 执行进度 | `docs/t3code-improvement-execution-report.md` |
| 能力对照基线 | `docs/ui-parity.md` |
