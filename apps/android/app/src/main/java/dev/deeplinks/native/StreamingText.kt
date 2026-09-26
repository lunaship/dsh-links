package dev.deeplinks.native

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import dev.deeplinks.core.Dsh
import kotlinx.coroutines.delay
import kotlin.math.min
import kotlin.math.roundToInt

/*
 * 流式正文：与定稿渲染是**同一个** Text（同一 AnnotatedString 构建、同一 TextStyle），
 * 只在尾部新到达的字符上叠一层淡入 alpha。因此：
 * - 不会出现「稳定段 + 动画词」两段分行、换行点随流式后移的跳动；
 * - 流式结束切到定稿时版式完全一致（不再从 16/28 跳到 16/26、不再从原文 `**` 跳到粗体）；
 * - 中文不再逐字拆成独立 Text，避头尾等断行规则照常生效。
 */

/** 新字符淡入时长；档位量化后约每 40ms 才重排一次尾段。 */
private const val STREAM_FADE_MS = 220L
private const val STREAM_FADE_TICK_MS = 40L
private const val STREAM_FADE_STEPS = 5

internal const val STREAM_CARET_ID = "dsh-stream-caret"

/** 尾部一段仍在淡入的字符（AnnotatedString 坐标，end 不含）。 */
internal data class StreamFadeSpan(val start: Int, val end: Int, val alpha: Float)

private class StreamFadeChunk(val start: Int, val end: Int, val bornAt: Long)

/** 年龄 → 量化 alpha（ease-out，[STREAM_FADE_STEPS] 档：只有跨档才触发重排）。 */
internal fun streamFadeAlpha(ageMs: Long, durationMs: Long = STREAM_FADE_MS): Float {
    if (ageMs <= 0L) return 0f
    if (ageMs >= durationMs) return 1f
    val t = ageMs.toFloat() / durationMs
    val eased = 1f - (1f - t) * (1f - t)
    return ((eased * STREAM_FADE_STEPS).roundToInt().toFloat() / STREAM_FADE_STEPS).coerceIn(0f, 1f)
}

/**
 * 追踪一段流式文本的增长，返回尾部仍在淡入的区间。
 * 首次组合时已有的文本视为已稳定（打开进行中的会话不会整段重播淡入）；
 * [enabled] 为 false（reduce-motion）时恒为空。
 */
@Composable
internal fun rememberStreamFade(length: Int, enabled: Boolean): List<StreamFadeSpan> {
    val chunks = remember { mutableStateListOf<StreamFadeChunk>() }
    var settled by remember { mutableIntStateOf(length) }
    var now by remember { mutableLongStateOf(SystemClock.uptimeMillis()) }
    if (!enabled) return emptyList()
    LaunchedEffect(length) {
        val born = SystemClock.uptimeMillis()
        when {
            length > settled -> chunks.add(StreamFadeChunk(settled, length, born))
            length < settled -> chunks.clear()
        }
        settled = length
        while (chunks.isNotEmpty()) {
            now = SystemClock.uptimeMillis()
            chunks.removeAll { now - it.bornAt >= STREAM_FADE_MS }
            if (chunks.isEmpty()) break
            delay(STREAM_FADE_TICK_MS)
        }
    }
    val spans = ArrayList<StreamFadeSpan>(chunks.size + 1)
    chunks.forEach { c ->
        val end = min(c.end, length)
        val alpha = streamFadeAlpha(now - c.bornAt)
        if (c.start < end && alpha < 1f) spans += StreamFadeSpan(c.start, end, alpha)
    }
    // 本帧新到、LaunchedEffect 还没登记的字符：先按 0 渲染，避免「整亮一帧再变暗」的闪烁
    if (length > settled) spans += StreamFadeSpan(settled, length, 0f)
    return spans
}

/** 在定稿 AnnotatedString 上叠加尾部淡入与行内光标（光标作为 inline content 跟随最后一个字）。 */
internal fun AnnotatedString.withStreamTail(
    spans: List<StreamFadeSpan>,
    color: Color,
    caret: Boolean,
): AnnotatedString {
    if (spans.isEmpty() && !caret) return this
    val base = this
    return buildAnnotatedString {
        append(base)
        spans.forEach { s ->
            val end = min(s.end, base.length)
            if (s.start < end) {
                addStyle(SpanStyle(color = color.copy(alpha = color.alpha * s.alpha)), s.start, end)
            }
        }
        if (caret) appendInlineContent(STREAM_CARET_ID, "▍")
    }
}

/** 行内光标：宽 0.5em 的占位，里面画 2dp 竖条，随文本一起换行。 */
@Composable
internal fun rememberStreamCaretContent(): InlineTextContent {
    val ink = Dsh.labelPrimary
    return remember(ink) {
        InlineTextContent(
            placeholder = Placeholder(
                width = 0.5.em,
                height = 1.em,
                placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter,
            ),
        ) {
            Box(
                modifier = Modifier
                    .padding(start = 2.dp)
                    .width(2.dp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(99.dp))
                    .background(ink),
            )
        }
    }
}

/**
 * 流式期间把尚未闭合的行内标记补齐，让半截的 `**粗体`、`` `code `` 立刻按样式显示，
 * 而不是先露出原文标记、闭合时再整段重排。单个 `*`（斜体）不补：`2*3` 这类算式会被误判。
 */
internal fun closeStreamingMarkdown(text: String): String {
    if (text.count { it == '`' } % 2 == 1) return "$text`"
    val outsideCode = text.replace(CodeSpanRegex, "")
    val sb = StringBuilder(text)
    if (outsideCode.windowedCount("**") % 2 == 1) sb.append("**")
    if (outsideCode.windowedCount("~~") % 2 == 1) sb.append("~~")
    return sb.toString()
}

private val CodeSpanRegex = Regex("`[^`]*`")

/** 不重叠计数（与行内解析器 indexOf 推进方式一致）。 */
private fun String.windowedCount(marker: String): Int {
    var count = 0
    var i = indexOf(marker)
    while (i != -1) {
        count++
        i = indexOf(marker, i + marker.length)
    }
    return count
}

/** 块级光标（代码块、表格、公式等非段落块的流式尾部）。 */
@Composable
internal fun StreamCaret(modifier: Modifier = Modifier) {
    val ink = Dsh.labelPrimary
    Box(
        modifier = modifier
            .padding(start = 2.dp, top = 8.dp)
            .width(2.dp)
            .height(12.dp)
            .clip(RoundedCornerShape(99.dp))
            .background(ink),
    )
}
