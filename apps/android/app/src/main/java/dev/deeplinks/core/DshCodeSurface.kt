package dev.deeplinks.core

import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/**
 * 代码/密集等宽面的单一几何源（对照 t3code MOBILE_CODE_SURFACE）：
 * 字号、行高、行号字号、行高、行号沟槽、内边距只在这里定义一次，
 * 所有渲染路径按同一份缩放，避免彼此差 1–2px。
 */
data class CodeSurfaceMetrics(
    val fontSize: TextUnit,
    val lineHeight: TextUnit,
    val lineNumberFontSize: TextUnit,
    val rowHeightDp: Int,
    val gutterWidthDp: Int,
    val paddingDp: Int,
)

object DshCodeSurface {
    const val DEFAULT_FONT_SIZE_SP = 13f
    const val DEFAULT_LINE_HEIGHT_SP = 20f
    const val DEFAULT_LINE_NUMBER_FONT_SIZE_SP = 11f
    const val ROW_HEIGHT_DP = 22
    const val GUTTER_WIDTH_DP = 34
    const val PADDING_DP = 8

    val default: CodeSurfaceMetrics get() = resolve()

    fun resolve(fontSizeSp: Float = DEFAULT_FONT_SIZE_SP): CodeSurfaceMetrics {
        val safe = fontSizeSp.coerceIn(9f, 22f)
        val scale = safe / DEFAULT_FONT_SIZE_SP
        return CodeSurfaceMetrics(
            fontSize = safe.sp,
            lineHeight = (DEFAULT_LINE_HEIGHT_SP * scale).sp,
            lineNumberFontSize = (DEFAULT_LINE_NUMBER_FONT_SIZE_SP * scale).coerceAtLeast(8f).sp,
            rowHeightDp = (ROW_HEIGHT_DP * scale).toInt().coerceAtLeast(14),
            gutterWidthDp = GUTTER_WIDTH_DP,
            paddingDp = PADDING_DP,
        )
    }
}
