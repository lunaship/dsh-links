package dev.deeplinks.native

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.ui.unit.dp
import android.provider.Settings
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

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
    /** 输入卡圆角（签名形状：比卡片软、比弹层收）。 */
    val composer = 22.dp
    val sheet = 28.dp   // 底部弹层顶部圆角：M3 bottom sheet 标准 28dp（原 14dp iOS 观感偏"浮层化"）
    val dialog = 28.dp  // 对话框卡片圆角：M3 dialog 标准 28dp
    val full = 999.dp
}

/** 底部弹层通用 shape：顶部两角 [DshRadius.sheet]（M3 28dp）。 */
val DshSheetShape = RoundedCornerShape(topStart = DshRadius.sheet, topEnd = DshRadius.sheet)

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

/** Dialog 进出场动画的渲染层状态：只读值走 graphicsLayer，关闭走 [requestDismiss]。 */
class DshDialogMotion(
    val alpha: State<Float>,
    val scale: State<Float>,
    /**
     * 请求关闭：先反向播完出场动画（alpha→0、scale→0.96），播完才回调真正的
     * onDismissRequest——Dialog 在出场期间保持挂载，避免"点击后瞬间消失"的硬切。
     * 重复调用幂等。
     */
    val requestDismiss: () -> Unit,
)

/**
 * Dialog 进出场动画状态，只走渲染层（graphicsLayer），不触发重组。
 * 入场：遮罩与卡片从 0 淡入，卡片带 0.96→1 缩放；
 * 出场：见 [DshDialogMotion.requestDismiss]；reduce-motion 时出入场均为瞬时。
 *
 * 用法：所有 dismiss 入口（系统返回、遮罩点击、取消按钮）都改走
 * `motion.requestDismiss`，真正收尾的 [onDismissRequest] 只负责翻转调用方的
 * 显示状态（此时画面已在 alpha=0，卸载无视觉跳变）。
 */
@Composable
fun dialogMotionState(onDismissRequest: () -> Unit): DshDialogMotion {
    var shown by remember { mutableStateOf(false) }
    var exiting by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val exitMs = motionDuration(DshDuration.normal)
    val currentOnDismiss by rememberUpdatedState(onDismissRequest)
    LaunchedEffect(exiting) {
        if (exiting) {
            delay(exitMs.toLong())
            currentOnDismiss()
        }
    }
    val alpha = animateFloatAsState(
        targetValue = if (shown && !exiting) 1f else 0f,
        animationSpec = tween(motionDuration(150)),
        label = "dialogAlpha"
    )
    val scale = animateFloatAsState(
        targetValue = if (shown && !exiting) 1f else 0.96f,
        animationSpec = tween(motionDuration(200), easing = FastOutSlowInEasing),
        label = "dialogScale"
    )
    return DshDialogMotion(alpha, scale) { exiting = true }
}

// ===== 触觉词表（Material expressive haptics）=====
// 应用内只在「状态被确认/改变」的时刻给触觉：选择、开关、复制成功、提交决策、
// 危险操作确认。导航点击不给触觉（避免过度打扰）。全部经 [HapticFeedbackType]，
// 跟随系统触觉开关。

/** 语义触觉：对应一次可确认的状态变化。 */
enum class DshHaptic {
    /** 轻点确认（选中一项、发送成功）。 */
    Tick,

    /** 正向结果（复制成功、非危险确认）。 */
    Confirm,

    /** 负向/危险确认（吊销、拒绝审批）。 */
    Reject,

    /** 开关打开。 */
    ToggleOn,

    /** 开关关闭。 */
    ToggleOff,

    /** 长按手势（呼出菜单）。 */
    LongPress,
}

/** 返回语义触觉发射器；同一实例可复用，无需按调用点记忆。 */
@Composable
fun rememberDshHaptic(): (DshHaptic) -> Unit {
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    return remember(haptic) {
        { kind ->
            val type = when (kind) {
                DshHaptic.Tick -> androidx.compose.ui.hapticfeedback.HapticFeedbackType.ContextClick
                DshHaptic.Confirm -> androidx.compose.ui.hapticfeedback.HapticFeedbackType.Confirm
                DshHaptic.Reject -> androidx.compose.ui.hapticfeedback.HapticFeedbackType.Reject
                DshHaptic.ToggleOn -> androidx.compose.ui.hapticfeedback.HapticFeedbackType.ToggleOn
                DshHaptic.ToggleOff -> androidx.compose.ui.hapticfeedback.HapticFeedbackType.ToggleOff
                DshHaptic.LongPress -> androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress
            }
            haptic.performHapticFeedback(type)
        }
    }
}

// ===== 按压缩放（用户触发动效优先 spring：天然处理中断）=====

/**
 * 自定义可点击面的按压缩放反馈（Material：卡片/图标按钮 0.96–0.98）。
 * 仅改渲染层（graphicsLayer），不触发布局；reduce-motion 时不动。
 * 只用于没有 ripple indication 的面；有 ripple 的 Material 组件不需要它。
 */
fun Modifier.dshPressScale(
    interactionSource: MutableInteractionSource,
    pressedScale: Float = 0.97f,
): Modifier = composed {
    if (isReduceMotionEnabled()) return@composed this
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = androidx.compose.animation.core.spring(
            dampingRatio = androidx.compose.animation.core.Spring.DampingRatioNoBouncy,
            stiffness = androidx.compose.animation.core.Spring.StiffnessMedium,
        ),
        label = "dshPressScale",
    )
    graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}
