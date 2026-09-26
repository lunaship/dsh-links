package dev.deeplinks.core

import android.content.Context
import android.os.Build
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme

/**
 * Material You 动态取色（对照 t3code materialYouTheme.android.ts）：
 * 把系统动态色的关键槽位映射进 DshColors 的**语义角色**，而不是直接用 M3 槽位，
 * 这样所有组件无需改动即可跟随壁纸取色。气泡类角色做轻微 hand-tune。
 *
 * 品牌强调色（DeepSeek Blue）永远保留静态基线：壁纸只动表面 / 灰阶文字，
 * 不劫持 `brand400` / `brand500` / `brandTint` / 推理轨与用户气泡蓝系底。
 *
 * Android 12（S）以下没有系统动态色，返回 null，由调用方回退静态调色板。
 */
fun dynamicDshColors(context: Context, dark: Boolean): DshColors? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
    val scheme = if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    val base = if (dark) DarkDshColors else LightDshColors
    // base.copy：未覆盖的字段（brand* / brandTint / toolsAccent / bgRecessed /
    // traceReasoning / bubbleBg / bubbleHighlight 等）保持 DeepSeek 静态色。
    return base.copy(
        bgBase = scheme.background,
        bgSidePanel = scheme.surfaceContainerLow,
        bgCard = scheme.surfaceContainer,
        bgSurface = scheme.surfaceContainer,
        bgInput = scheme.surfaceContainerHigh,
        bgSubtle = scheme.surfaceContainerHigh,
        bgTrack = scheme.surfaceContainerHigh,
        bgSelected = scheme.surfaceContainerHighest,
        bgPressed = scheme.surfaceContainerHigh,
        bgDrawer = scheme.surfaceContainerLow,
        bgNavSelected = scheme.secondaryContainer,
        labelPrimary = scheme.onSurface,
        // secondary 取 onSurface 与 onSurfaceVariant 的中点：两者都用 onSurfaceVariant 时
        // 灰阶塌成一级；tertiary 保持 onSurfaceVariant（M3 保证其对表面达 AA）。
        labelSecondary = androidx.compose.ui.graphics.lerp(scheme.onSurface, scheme.onSurfaceVariant, 0.5f),
        labelTertiary = scheme.onSurfaceVariant,
        error = scheme.error,
        errorBg = scheme.errorContainer.copy(alpha = 0.30f),
        buttonElevated = scheme.surfaceContainerHighest,
        buttonFloating = scheme.surfaceContainerHigh,
    )
}
