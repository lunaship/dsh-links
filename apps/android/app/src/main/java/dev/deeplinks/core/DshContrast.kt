package dev.deeplinks.core

import androidx.compose.ui.graphics.Color
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * 对比度/半透明合成工具（对照 t3code lib/mobileTheme.ts）：
 * 把「人肉核算写在注释里」变成算法 + 测试，动态主题/未来主题也能自动达标。
 */

private fun channelToLinear(channel: Float): Double {
    val value = channel.toDouble()
    return if (value <= 0.04045) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
}

/** WCAG 相对亮度。 */
fun relativeLuminance(color: Color): Double =
    0.2126 * channelToLinear(color.red) +
        0.7152 * channelToLinear(color.green) +
        0.0722 * channelToLinear(color.blue)

/** WCAG 对比度（1.0–21.0）。 */
fun contrastRatio(a: Color, b: Color): Double {
    val first = relativeLuminance(a)
    val second = relativeLuminance(b)
    return (max(first, second) + 0.05) / (min(first, second) + 0.05)
}

/** 把半透明色按真实背景合成为不透明色（原生绘制/取色器只吃不透明值）。 */
fun flattenThemeColor(color: Color, surface: Color): Color {
    if (color.alpha >= 1f) return color
    val alpha = color.alpha
    return Color(
        red = color.red * alpha + surface.red * (1f - alpha),
        green = color.green * alpha + surface.green * (1f - alpha),
        blue = color.blue * alpha + surface.blue * (1f - alpha),
        alpha = 1f,
    )
}

/**
 * 保留色相，把强调色调整到在所有 [surfaces] 上都 >= [minimumContrast]。
 * 已达标则原样返回；否则朝黑/白中对比度更高的一侧做 12 轮二分，
 * 取「最接近原色且仍达标」的结果。
 */
fun readableTextColor(
    accent: Color,
    surfaces: List<Color>,
    minimumContrast: Double = 4.5,
): Color {
    if (surfaces.isEmpty()) return accent
    fun worst(candidate: Color): Double = surfaces.minOf { contrastRatio(candidate, it) }
    if (worst(accent) >= minimumContrast) return accent

    val black = Color.Black
    val white = Color.White
    val target = if (worst(black) >= worst(white)) black else white

    var best = target
    var low = 0f
    var high = 1f
    repeat(12) {
        val amount = (low + high) / 2f
        val candidate = Color(
            red = accent.red + (target.red - accent.red) * amount,
            green = accent.green + (target.green - accent.green) * amount,
            blue = accent.blue + (target.blue - accent.blue) * amount,
            alpha = 1f,
        )
        if (worst(candidate) >= minimumContrast) {
            best = candidate
            high = amount
        } else {
            low = amount
        }
    }
    return best
}
