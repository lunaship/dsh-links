package dev.dsh.mobile.native
import dev.dsh.mobile.core.Dsh

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.ui.unit.dp
import android.provider.Settings
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * DSH 设计系统：排版、圆角、动效 token。
 * 排版对齐 DSH Web `--dsw-font-family` 体系；
 * 圆角统一为 4 档 + pill（取代散布的 5/6/7/8/10/12/13/14/24dp）；
 * 动效时长与 easing 对齐 `--ds-transition-duration*` 系列。
 */
object DshRadius {
    val sm = 6.dp
    val md = 10.dp
    val lg = 12.dp
    val xl = 18.dp
    val full = 999.dp
}

object DshDuration {
    const val fast = 100
    const val normal = 200
    const val slow = 300
}

object DshEasing {
    val inOut = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)
    val out = CubicBezierEasing(0.32f, 0.72f, 0f, 1f)
}

/**
 * 全局动效工具：对应 Web 端 `@media(prefers-reduced-motion: reduce)`。
 * 系统「移除动画」（设置-无障碍，或开发者选项 animator_duration_scale = 0）开启时，
 * 过渡时长压为 0（瞬时完成），无限循环动画退化为静态。
 */

/** 系统「移除动画」开启时返回 true。 */
@Composable
fun isReduceMotionEnabled(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f
        ) == 0f
    }
}

/** reduce-motion 开启时返回 0（瞬时完成），否则返回 [ms]。 */
@Composable
fun motionDuration(ms: Int): Int = if (isReduceMotionEnabled()) 0 else ms

/**
 * 无限旋转角度：reduce-motion 时返回 null（调用方渲染静态图标）。
 * 对应 Web CSS `animation: spin .8s linear infinite`。
 */
@Composable
fun rememberMotionSpin(periodMs: Int, label: String = "spin"): Float? {
    if (isReduceMotionEnabled()) return null
    val transition = rememberInfiniteTransition(label = label)
    return transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(periodMs, easing = LinearEasing)),
        label = label
    ).value
}

/**
 * Dialog 入场动画状态：(alpha, scale) 两个 [State]，只走渲染层（graphicsLayer），不触发重组。
 * 遮罩与卡片从 0 淡入，卡片带 0.96→1 缩放；reduce-motion 时直接为终值。
 * 退出保持瞬时（与 Web 一致：仅有进入过渡，见 CSS `_fade-in .16s ease-out`）。
 */
@Composable
fun dialogEnterState(): Pair<State<Float>, State<Float>> {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val alpha = animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = tween(motionDuration(150)),
        label = "dialogAlpha"
    )
    val scale = animateFloatAsState(
        targetValue = if (shown) 1f else 0.96f,
        animationSpec = tween(motionDuration(200), easing = FastOutSlowInEasing),
        label = "dialogScale"
    )
    return alpha to scale
}

/**
 * 脉动点的 alpha 进度：每个点独立 delay，按 [RepeatMode.Reverse] 来回摆动。
 * reduce-motion 时每个点返回 1f（静态满 alpha，不闪烁）。
 */
@Composable
fun rememberMotionDots(count: Int = 3, periodMs: Int = 900, minAlpha: Float = 0.3f): List<Float> {
    if (isReduceMotionEnabled()) {
        return List(count) { 1f }
    }
    val transition = rememberInfiniteTransition(label = "dots")
    return (0 until count).map { i ->
        transition.animateFloat(
            initialValue = minAlpha,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(periodMs, easing = LinearEasing, delayMillis = i * (periodMs / count)),
                repeatMode = RepeatMode.Reverse
            ),
            label = "dot-$i"
        ).value
    }
}

/**
 * 横向 3 个 4dp 脉动点，alpha 由 [rememberMotionDots] 驱动。
 * 替代 [RunningSweep] 用于 CommandCard running 态，更轻量、视觉更聚焦。
 */
@Composable
fun RunningDots(tint: Color = Dsh.brand400, count: Int = 3) {
    val alphas = rememberMotionDots(count = count)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        alphas.forEach { a ->
            androidx.compose.foundation.layout.Box(
                modifier = Modifier
                    .size(4.dp)
                    .clip(CircleShape)
                    .background(tint.copy(alpha = a))
            )
        }
    }
}
