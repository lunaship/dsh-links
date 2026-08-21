package dev.dsh.mobile.native

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.StartOffsetType
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dsh.mobile.core.Dsh

/** 像素格加载变体（对齐参考 LoadingState；不做 Surfer 视频）。 */
enum class PixelLoaderVariant {
    /** 方格 + 右向 chevron 波前 */
    Drive,
    /** 同波前，圆点 */
    Dots,
    /** 沿九宫格外圈绕行；中心格保持暗淡 */
    Orbit,
}

private data class PixelPattern(
    val delaysMs: List<Int?>,
    val durationMs: Int,
    val round: Boolean,
)

private val ChevronDelays: List<Int?> = List(9) { i ->
    val r = i / 3
    val c = i % 3
    (c + kotlin.math.abs(r - 1)) * 90
}

private val OrbitOrder = intArrayOf(0, 1, 2, 5, 8, 7, 6, 3)

private val OrbitDelays: List<Int?> = List(9) { i ->
    val k = OrbitOrder.indexOf(i)
    if (k < 0) null else k * 110
}

private fun patternFor(variant: PixelLoaderVariant): PixelPattern = when (variant) {
    PixelLoaderVariant.Drive -> PixelPattern(ChevronDelays, 650, round = false)
    PixelLoaderVariant.Dots -> PixelPattern(ChevronDelays, 650, round = true)
    PixelLoaderVariant.Orbit -> PixelPattern(OrbitDelays, 950, round = false)
}

/**
 * 长任务加载态：3×3 像素波前 + 扫光文案 + 等宽计时。
 * reduce-motion 时格子停在暗态，计时仍走。
 */
@Composable
internal fun PixelLoadingState(
    label: String,
    elapsedLabel: String,
    modifier: Modifier = Modifier,
    variant: PixelLoaderVariant = PixelLoaderVariant.Drive,
) {
    val pattern = remember(variant) { patternFor(variant) }
    Row(
        modifier = modifier.semantics(mergeDescendants = true) {
            liveRegion = LiveRegionMode.Polite
        },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        PixelLoaderGridImpl(
            delaysMs = pattern.delaysMs,
            durationMs = pattern.durationMs,
            round = pattern.round,
        )
        ShimmerLabel(text = label, working = true)
        Text(
            elapsedLabel,
            fontSize = 12.sp,
            color = Dsh.labelTertiary,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight(500),
        )
    }
}

/** 仅 3×3 像素格（嵌进工具行等紧凑位）。 */
@Composable
internal fun PixelLoaderGrid(
    modifier: Modifier = Modifier,
    variant: PixelLoaderVariant = PixelLoaderVariant.Drive,
    tint: Color = Dsh.labelPrimary,
) {
    val pattern = remember(variant) { patternFor(variant) }
    PixelLoaderGridImpl(
        delaysMs = pattern.delaysMs,
        durationMs = pattern.durationMs,
        round = pattern.round,
        modifier = modifier,
        tint = tint,
    )
}

@Composable
private fun PixelLoaderGridImpl(
    delaysMs: List<Int?>,
    durationMs: Int,
    round: Boolean,
    modifier: Modifier = Modifier,
    tint: Color = Dsh.labelPrimary,
) {
    val reduce = isReduceMotionEnabled()
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(1.5.dp),
    ) {
        for (row in 0 until 3) {
            Row(horizontalArrangement = Arrangement.spacedBy(1.5.dp)) {
                for (col in 0 until 3) {
                    val delay = delaysMs[row * 3 + col]
                    PixelCell(
                        delayMs = delay,
                        durationMs = durationMs,
                        round = round,
                        tint = tint,
                        animate = !reduce && delay != null,
                    )
                }
            }
        }
    }
}

@Composable
private fun PixelCell(
    delayMs: Int?,
    durationMs: Int,
    round: Boolean,
    tint: Color,
    animate: Boolean,
) {
    val shape = if (round) CircleShape else RoundedCornerShape(1.dp)
    val baseAlpha = if (delayMs == null) 0.07f else 0.15f
    val alpha = if (!animate) {
        baseAlpha
    } else {
        val transition = rememberInfiniteTransition(label = "pixel-$delayMs")
        val a by transition.animateFloat(
            initialValue = 0.15f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = keyframes {
                    durationMillis = durationMs
                    0.15f at 0
                    1f at durationMs / 2 using FastOutSlowInEasing
                    0.15f at durationMs using FastOutSlowInEasing
                },
                repeatMode = RepeatMode.Restart,
                initialStartOffset = StartOffset(
                    offsetMillis = delayMs ?: 0,
                    offsetType = StartOffsetType.Delay,
                ),
            ),
            label = "pixelAlpha",
        )
        a
    }
    Box(
        modifier = Modifier
            .size(4.dp)
            .clip(shape)
            .background(tint.copy(alpha = alpha)),
    )
}

/** 把秒数格式成参考组件同款计时。 */
internal fun formatPixelElapsed(elapsedSec: Long): String {
    if (elapsedSec < 60) return "${elapsedSec}s"
    return "${elapsedSec / 60}m ${elapsedSec % 60}s"
}
