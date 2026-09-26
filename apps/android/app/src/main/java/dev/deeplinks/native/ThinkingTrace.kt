package dev.deeplinks.native

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.deeplinks.core.dshRipple
import dev.deeplinks.core.Dsh
import dev.deeplinks.core.L
import dev.deeplinks.core.DshType

/** 对齐参考组件 `cubic-bezier(0.23, 1, 0.32, 1)`。 */
private val ThinkingEase = CubicBezierEasing(0.23f, 1f, 0.32f, 1f)

/**
 * 四角星（思考条图标）。不复用 DSH 原子轨图标，避免和官方 Chat 思考标识撞车。
 */
private val StarFour16: ImageVector
    get() {
        val cached = _starFour16
        if (cached != null) return cached
        return ImageVector.Builder(
            name = "StarFour16",
            defaultWidth = 16.dp,
            defaultHeight = 16.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(12f, 2f)
                lineTo(14.4f, 9.2f)
                lineTo(22f, 12f)
                lineTo(14.4f, 14.8f)
                lineTo(12f, 22f)
                lineTo(9.6f, 14.8f)
                lineTo(2f, 12f)
                lineTo(9.6f, 9.2f)
                close()
            }
        }.build().also { _starFour16 = it }
    }

private var _starFour16: ImageVector? = null

/**
 * DeepSeek 签名思考轨迹：凹进面板 + 左侧 2dp 品牌蓝竖条；
 * 进行中扫光标题，正文默认收起，用户点击后展开。
 */
@Composable
internal fun ThinkingTrace(
    working: Boolean,
    activeLabel: String,
    doneLabel: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    body: @Composable ColumnScope.() -> Unit,
) {
    val rail = Dsh.brand500
    val panelShape = RoundedCornerShape(DshRadius.md)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(panelShape)
            .background(if (expanded) Dsh.bgRecessed else Color.Transparent)
            .drawBehind {
                if (expanded) {
                    drawLine(
                        color = rail,
                        start = Offset(2.dp.toPx(), 6.dp.toPx()),
                        end = Offset(2.dp.toPx(), size.height - 6.dp.toPx()),
                        strokeWidth = 2.dp.toPx(),
                        cap = StrokeCap.Round,
                    )
                }
            }
            .padding(start = if (expanded) 10.dp else 0.dp, end = 8.dp, top = 2.dp, bottom = 2.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            ThinkingHeader(
                working = working,
                activeLabel = activeLabel,
                doneLabel = doneLabel,
                expanded = expanded,
                onToggle = onToggle,
            )
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(
                    animationSpec = tween(motionDuration(400), easing = ThinkingEase),
                ) + fadeIn(animationSpec = tween(motionDuration(240), easing = ThinkingEase)),
                exit = shrinkVertically(
                    animationSpec = tween(motionDuration(280), easing = FastOutSlowInEasing),
                ) + fadeOut(animationSpec = tween(motionDuration(180))),
            ) {
                Column(
                    modifier = Modifier.padding(start = 4.dp, top = 2.dp, bottom = 6.dp),
                    content = body,
                )
            }
        }
    }
}

/** 等待首 token：四角星 + 扫光「思考中」+ 计时（安静 chrome，不用像素格）。 */
@Composable
internal fun ThinkingStatusRow(
    elapsedSec: Long,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            StarFour16,
            contentDescription = null,
            tint = Dsh.brand400,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(8.dp))
        ShimmerLabel(text = L.thinkingActive, working = true)
        Spacer(Modifier.width(8.dp))
        Text(
            formatThinkingElapsed(elapsedSec),
            style = DshType.t12M,
            color = Dsh.labelTertiary,
            maxLines = 1,
        )
    }
}

internal fun formatThinkingElapsed(elapsedSec: Long): String {
    if (elapsedSec < 60) return "${elapsedSec}s"
    return "${elapsedSec / 60}m ${elapsedSec % 60}s"
}

@Composable
private fun ThinkingHeader(
    working: Boolean,
    activeLabel: String,
    doneLabel: String,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(motionDuration(300), easing = ThinkingEase),
        label = "thinkingChevron",
    )
    val expandLabel = if (expanded) L.collapse else L.expand
    val pressTint = Dsh.pressed
    val labelFadeIn = motionDuration(350)
    val labelFadeOut = motionDuration(180)
    Row(
        modifier = Modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(DshRadius.sm))
            .clickable(
                interactionSource = interaction,
                indication = dshRipple(),
                onClick = onToggle,
            )
            .semantics {
                role = Role.Button
                stateDescription = expandLabel
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .then(
                    if (pressed) Modifier.drawBehind { drawRect(pressTint) }
                    else Modifier,
                )
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                StarFour16,
                contentDescription = null,
                tint = if (working) Dsh.brand400 else Dsh.labelTertiary,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(8.dp))
            AnimatedContent(
                targetState = working,
                transitionSpec = {
                    fadeIn(tween(labelFadeIn)) togetherWith fadeOut(tween(labelFadeOut))
                },
                label = "thinkingLabel",
            ) { isWorking ->
                if (isWorking) {
                    ShimmerLabel(text = activeLabel, working = true)
                } else {
                    Text(
                        doneLabel,
                        style = DshType.t13M,
                        fontWeight = FontWeight(600),
                        color = Dsh.labelPrimary,
                        maxLines = 1,
                    )
                }
            }
            Spacer(Modifier.width(6.dp))
            Icon(
                ChevronDownOutline14,
                contentDescription = expandLabel,
                tint = Dsh.labelTertiary,
                modifier = Modifier
                    .size(14.dp)
                    .graphicsLayer { rotationZ = rotation },
            )
        }
    }
}

/**
 * 文字扫光：动画值只在 Canvas 绘制阶段读取，避免每帧重组。
 * reduce-motion 时退化为静态次要色文本。
 */
@Composable
internal fun ShimmerLabel(text: String, working: Boolean) {
    val style = DshType.t13M
    if (!working || isReduceMotionEnabled()) {
        Text(text, style = style, color = Dsh.labelSecondary, maxLines = 1)
        return
    }
    val transition = rememberInfiniteTransition(label = "thinkingShimmer")
    val offset = transition.animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "thinkingShimmerOffset",
    )
    val textMeasurer = rememberTextMeasurer()
    val ink = Dsh.labelPrimary
    val muted = Dsh.labelTertiary
    val textLayout = remember(text, style) { textMeasurer.measure(text, style) }
    Canvas(
        modifier = Modifier
            .width(with(LocalDensity.current) { textLayout.size.width.toDp() })
            .height(with(LocalDensity.current) { textLayout.size.height.toDp() }),
    ) {
        drawText(
            textLayout,
            brush = Brush.linearGradient(
                colorStops = arrayOf(
                    0.35f to muted,
                    0.50f to ink,
                    0.65f to muted,
                ),
                start = Offset(offset.value * size.width, 0f),
                end = Offset(offset.value * size.width + size.width, 0f),
            ),
        )
    }
}

internal fun thoughtDoneLabel(durationMs: Long?, elapsedSec: Long?): String {
    val seconds = when {
        durationMs != null && durationMs > 0 -> (durationMs / 1000L).coerceAtLeast(1L)
        elapsedSec != null && elapsedSec > 0 -> elapsedSec
        else -> null
    }
    return if (seconds != null) L.thoughtForSeconds.format(seconds) else L.thoughtDone
}
