package dev.dsh.mobile.native

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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dsh.mobile.core.Dsh
import dev.dsh.mobile.core.L

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
 * 可展开的思考轨迹：进行中只显示扫光标题，正文默认收起，用户点击后才展开。
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
    Column(modifier = modifier.fillMaxWidth()) {
        ThinkingHeader(
            working = working,
            activeLabel = activeLabel,
            doneLabel = doneLabel,
            expanded = expanded,
            onToggle = onToggle,
        )
        val lineColor = Dsh.borderL3
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(
                animationSpec = tween(motionDuration(400), easing = ThinkingEase),
            ) + fadeIn(animationSpec = tween(motionDuration(240), easing = ThinkingEase)),
            exit = shrinkVertically(
                animationSpec = tween(motionDuration(280), easing = FastOutSlowInEasing),
            ) + fadeOut(animationSpec = tween(motionDuration(180))),
        ) {
            Box(
                modifier = Modifier
                    .padding(start = 7.dp, top = 4.dp)
                    .drawBehind {
                        val x = 3.5.dp.toPx()
                        drawLine(
                            color = lineColor,
                            start = Offset(x, 0f),
                            end = Offset(x, size.height),
                            strokeWidth = 1.dp.toPx(),
                        )
                    }
                    .padding(start = 16.dp, top = 4.dp, bottom = 4.dp),
            ) {
                Column(content = body)
            }
        }
    }
}

/** 等待首 token：只有扫光标题，没有可展开正文。 */
@Composable
internal fun ThinkingStatusRow(
    elapsedSec: Long,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.height(26.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            StarFour16,
            contentDescription = null,
            tint = Dsh.labelSecondary,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(8.dp))
        ShimmerLabel(text = L.thinkingActive, working = true)
        Spacer(Modifier.width(8.dp))
        Text(
            "${elapsedSec}s",
            fontSize = 12.sp,
            color = Dsh.labelTertiary,
            fontFamily = FontFamily.Monospace,
        )
    }
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
    val hover = Dsh.hover
    val labelFadeIn = motionDuration(350)
    val labelFadeOut = motionDuration(180)
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(DshRadius.sm))
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onToggle,
            )
            .semantics {
                role = Role.Button
                stateDescription = expandLabel
            }
            .then(
                if (pressed) Modifier.drawBehind { drawRect(hover) }
                else Modifier,
            )
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            StarFour16,
            contentDescription = null,
            tint = if (working) Dsh.labelSecondary else Dsh.labelTertiary,
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
                    fontSize = 13.sp,
                    fontWeight = FontWeight(500),
                    color = Dsh.labelSecondary,
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

/**
 * 文字扫光：动画值只在 Canvas 绘制阶段读取，避免每帧重组。
 * reduce-motion 时退化为静态次要色文本。
 */
@Composable
private fun ShimmerLabel(text: String, working: Boolean) {
    val style = remember {
        TextStyle(fontSize = 13.sp, fontWeight = FontWeight(500))
    }
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
