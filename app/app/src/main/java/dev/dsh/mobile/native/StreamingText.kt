package dev.dsh.mobile.native

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dsh.mobile.core.Dsh
import kotlinx.coroutines.delay

/** 对齐参考 `stream-in 420ms cubic-bezier(0.22, 0.61, 0.25, 1)`。 */
private val StreamInEasing = CubicBezierEasing(0.22f, 0.61f, 0.25f, 1f)

/** 同时做去模糊动画的词数上限，更早的词合并成一段静态文本。 */
private const val LIVE_TOKEN_CAP = 24

/**
 * 流式正文：新词从模糊里清晰出来。英文按词切，中日韩按字切。
 * 不渲染来源芯片或追问，排版对齐 Web 助手正文（16sp / 28sp）。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun StreamingParagraph(
    text: String,
    showCaret: Boolean,
    modifier: Modifier = Modifier,
    color: Color = Dsh.labelPrimary,
    fontSize: TextUnit = 16.sp,
    lineHeight: TextUnit = 28.sp,
    fontWeight: FontWeight = FontWeight(400),
) {
    val tokens = remember(text) { tokenizeStreaming(text) }
    val splitAt = (tokens.size - LIVE_TOKEN_CAP).coerceAtLeast(0)
    val stable = if (splitAt > 0) tokens.take(splitAt).joinToString("") else ""
    val live = if (splitAt > 0) tokens.drop(splitAt) else tokens
    val reduceMotion = isReduceMotionEnabled()

    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.Start,
        verticalArrangement = Arrangement.Top,
    ) {
        if (stable.isNotEmpty()) {
            Text(
                stable,
                color = color,
                fontSize = fontSize,
                lineHeight = lineHeight,
                fontWeight = fontWeight,
            )
        }
        live.forEachIndexed { idx, token ->
            androidx.compose.runtime.key(splitAt + idx) {
                StreamInWord(
                    text = token,
                    color = color,
                    fontSize = fontSize,
                    lineHeight = lineHeight,
                    fontWeight = fontWeight,
                    animate = !reduceMotion,
                    delayMillis = (idx.coerceAtMost(6)) * 35,
                )
            }
        }
        if (showCaret) {
            StreamCaret()
        }
    }
}

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

@Composable
private fun StreamInWord(
    text: String,
    color: Color,
    fontSize: TextUnit,
    lineHeight: TextUnit,
    fontWeight: FontWeight,
    animate: Boolean,
    delayMillis: Int,
) {
    val progress = remember { Animatable(if (animate) 0f else 1f) }
    val duration = motionDuration(420)
    val stagger = motionDuration(delayMillis)
    LaunchedEffect(Unit) {
        if (progress.value >= 1f) return@LaunchedEffect
        if (stagger > 0) delay(stagger.toLong())
        progress.animateTo(1f, tween(duration, easing = StreamInEasing))
    }
    val p = progress.value
    val blurDp = ((1f - p) * 6f).dp
    Text(
        text,
        color = color,
        fontSize = fontSize,
        lineHeight = lineHeight,
        fontWeight = fontWeight,
        modifier = Modifier
            .graphicsLayer {
                alpha = p
                translationY = (1f - p) * 4f
            }
            .then(if (p < 0.99f) Modifier.blur(blurDp) else Modifier),
    )
}

/**
 * 把流式文本切成可动画的 token：拉丁词保留尾随空白，CJK 按码点切。
 */
internal fun tokenizeStreaming(text: String): List<String> {
    if (text.isEmpty()) return emptyList()
    val tokens = ArrayList<String>()
    var i = 0
    val n = text.length
    while (i < n) {
        val c = text[i]
        if (c.isWhitespace()) {
            if (tokens.isEmpty()) tokens.add(c.toString())
            else tokens[tokens.lastIndex] = tokens.last() + c
            i++
            continue
        }
        if (c.isHighSurrogate() && i + 1 < n && text[i + 1].isLowSurrogate()) {
            tokens.add(text.substring(i, i + 2))
            i += 2
            continue
        }
        if (isCjk(c)) {
            tokens.add(c.toString())
            i++
            continue
        }
        val start = i
        i++
        while (i < n) {
            val ch = text[i]
            if (ch.isWhitespace() || isCjk(ch) || ch.isHighSurrogate()) break
            i++
        }
        var token = text.substring(start, i)
        if (i < n && text[i].isWhitespace()) {
            token += text[i]
            i++
        }
        tokens.add(token)
    }
    return tokens
}

private fun isCjk(c: Char): Boolean {
    val code = c.code
    return code in 0x2E80..0x9FFF ||
        code in 0xF900..0xFAFF ||
        code in 0x3040..0x30FF ||
        code in 0xAC00..0xD7AF
}
