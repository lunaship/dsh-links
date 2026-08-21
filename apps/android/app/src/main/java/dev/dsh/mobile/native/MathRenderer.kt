package dev.dsh.mobile.native
import dev.dsh.mobile.core.Dsh

import android.graphics.Rect
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.noties.jlatexmath.JLatexMathDrawable
import java.util.LinkedHashMap

/**
 * LaTeX 数学公式渲染（ru.noties:jlatexmath-android 原生绘制，对标 Web UI 的 KaTeX）。
 * - 行内公式 `$...$`：与 Markdown 文本流内联（InlineTextContent 占位，尺寸由公式决定）
 * - 块级公式 `$$...$$`：独立居中块，字号更大
 * 非法 LaTeX 一律回退原文，保证消息流不崩。
 */

/** 公式 → Drawable 的 LRU 缓存（流式消息反复渲染同一公式时避免重复解析）。 */
object MathCache {
    private const val MAX_ENTRIES = 96

    private val cache = object : LinkedHashMap<String, JLatexMathDrawable>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, JLatexMathDrawable>?): Boolean =
            size > MAX_ENTRIES
    }

    /** 构建（带缓存）；非法公式返回 null。 */
    fun drawable(latex: String, textSizePx: Float, colorArgb: Int): JLatexMathDrawable? {
        val key = "$latex\u0000$textSizePx\u0000$colorArgb"
        synchronized(this) { cache[key]?.let { return it } }
        val built = runCatching {
            JLatexMathDrawable.builder(latex).textSize(textSizePx).color(colorArgb).build()
        }.getOrNull()
        if (built != null) synchronized(this) { cache[key] = built }
        return built
    }
}

/** 行内公式占位（尺寸 px → dp，由调用方在 buildAnnotatedString 时同步量好）。 */
fun buildInlineMath(
    latex: String,
    textSizePx: Float,
    colorArgb: Int,
    density: Density,
): Pair<JLatexMathDrawable, DpSize>? {
    val d = MathCache.drawable(latex, textSizePx, colorArgb) ?: return null
    val size = with(density) {
        DpSize(
            width = d.intrinsicWidth.toDp(),
            height = d.intrinsicHeight.toDp(),
        )
    }
    return d to size
}

/** 在指定尺寸画布内绘制公式（InlineTextContent 占位内容 / 块级公式共用）。 */
@Composable
fun LatexMathCanvas(drawable: JLatexMathDrawable, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        drawable.bounds = Rect(0, 0, drawable.intrinsicWidth, drawable.intrinsicHeight)
        drawable.draw(drawContext.canvas.nativeCanvas)
    }
}

/** 块级公式（$$...$$）：居中，20sp 字号。解析失败回退等宽原文。 */
@Composable
fun LatexDisplayBlock(latex: String, modifier: Modifier = Modifier) {
    val color = Dsh.labelPrimary
    val density = LocalDensity.current
    val textSizePx = with(density) { 20.sp.toPx() }
    val drawable = remember(latex, textSizePx, color) {
        MathCache.drawable(latex, textSizePx, color.toArgb())
    }
    if (drawable == null) {
        Text(
            latex,
            color = Dsh.labelTertiary,
            fontSize = 14.sp,
            lineHeight = 22.sp,
            fontFamily = FontFamily.Monospace,
            modifier = modifier.fillMaxWidth()
        )
        return
    }
    val w = with(density) { drawable.intrinsicWidth.toDp() }
    val h = with(density) { drawable.intrinsicHeight.toDp() }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        contentAlignment = Alignment.Center
    ) {
        LatexMathCanvas(drawable, Modifier.size(w, h))
    }
}
