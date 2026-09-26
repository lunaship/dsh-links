package dev.deeplinks.native.ui

import dev.deeplinks.core.DshType

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.deeplinks.core.dshRipple
import dev.deeplinks.core.readableTextColor
import dev.deeplinks.core.Dsh
import dev.deeplinks.core.DshS
import dev.deeplinks.native.DshDuration
import dev.deeplinks.native.DshEasing
import dev.deeplinks.native.DshHaptic
import dev.deeplinks.native.DshRadius
import dev.deeplinks.native.motionDuration
import dev.deeplinks.native.rememberDshHaptic

/**
 * DSH 设计系统语义组件（WI-005 / WI-006）—— 通用 filter chip、tag、badge、banner。
 * 所有 token 均来自 [Dsh]（`LocalDshColors`），深浅色自动适配。
 * 每个组件都内建 TalkBack 语义（contentDescription / role），不再依赖外部 label。
 */

// ============================================================
// DshFilterChip —— 通用筛选/分段胶囊
// 语义角色：Button；选中状态通过 [selected] 控制
// ============================================================
@Composable
fun DshFilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    count: Int? = null,
    enabled: Boolean = true,
    contentDescription: String? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val bg = when {
        !enabled -> Color.Transparent
        selected -> Dsh.bgSelected
        pressed -> Dsh.pressed
        else -> Color.Transparent
    }
    val textColor = when {
        !enabled -> Dsh.labelDimmed
        selected -> Dsh.labelPrimary
        else -> Dsh.labelSecondary
    }
    // 视觉 32dp 胶囊 / 外层 48dp 触摸热区：可点面积不缩，观感收紧
    Box(
        modifier = modifier
            .heightIn(min = 48.dp)
            .selectable(
                selected = selected,
                interactionSource = interaction,
                indication = dshRipple(),
                enabled = enabled,
                role = Role.Tab,
                onClick = onClick,
            )
            .semantics {
                if (contentDescription != null) {
                    this.contentDescription = contentDescription
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .height(32.dp)
                .clip(RoundedCornerShape(DshRadius.full))
                .background(bg)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                label,
                color = textColor,
                style = DshType.t13x20M,
                fontWeight = FontWeight(500),
                lineHeight = 20.sp,
            )
            if (count != null) {
                Spacer(Modifier.width(4.dp))
                Text(
                    count.toString(),
                    color = if (selected) Dsh.labelPrimary.copy(alpha = 0.7f) else Dsh.labelTertiary,
                    style = DshType.captionRelaxed,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

// ============================================================
// DshSheetGrabber —— 底部弹层拖拽指示条（自绘 dragHandle=null 时的统一把手）
// ============================================================

// ---------- 顶栏胶囊分段（对话 / 轨迹） ----------

/** 胶囊基准尺寸（fontScale 1.0）：轨道 30dp、药丸 26dp、四边内缩 2dp；实际高度跟字号缩放。 */
private val TopSegmentTrackHeight = 30.dp
private val TopSegmentPillHeight = 26.dp
private val TopSegmentInset = 2.dp

/**
 * 单行顶栏的胶囊分段：一条圆角轨道 + 选中段上的药丸。
 *
 * 尺寸是「合适的胶囊」而不是把热区当轨道：轨道视觉 30dp、药丸 26dp，
 * 触控热区仍是整段 48dp（M3 下限）——上一版把 48dp 直接画成轨道，
 * 整块控件被撑到 52dp 高，观感笨重。
 *
 * 药丸贴合各自文字宽度（不做等分），选中/取消是 100ms 的短交叉淡入，
 * 文字本身不位移；药丸由每段自绘，不依赖跨段测量，首帧（含截图基线）就正确。
 * 选中态再叠字重，弱视/动态取色下也能分辨。
 */
@Composable
fun DshTopSegment(
    labels: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (labels.isEmpty()) return
    val safeIndex = selectedIndex.coerceIn(0, labels.lastIndex)
    val haptic = rememberDshHaptic()
    val trackColor = Dsh.bgSubtle
    // 药丸比轨道亮一档（浅色取白面、深色取高亮槽），避开动态取色里同档撞色
    val pillColor = if (Dsh.isDark) Dsh.bgSelected else Dsh.bgCard
    val pressTint = Dsh.pressed
    // 文字是 sp、药丸是 dp：fontScale 1.3+ 不放大会把字顶出胶囊，
    // 所以按「标签行高 × fontScale」撑大药丸/轨道，1.0 时仍是 26/30dp。
    val labelStyle = DshType.t13M
    val lineSp = if (labelStyle.lineHeight.isSp) {
        labelStyle.lineHeight.value
    } else {
        labelStyle.fontSize.value * 1.5f
    }
    val textLineHeight = (lineSp * LocalDensity.current.fontScale).dp
    val pillHeight = maxOf(TopSegmentPillHeight, textLineHeight + TopSegmentInset * 2)
    val trackHeight = maxOf(TopSegmentTrackHeight, pillHeight + TopSegmentInset * 2)
    Row(
        modifier = modifier
            .heightIn(min = 48.dp)
            .drawBehind {
                val track = trackHeight.toPx()
                drawRoundRect(
                    color = trackColor,
                    topLeft = Offset(0f, (size.height - track) / 2f),
                    size = Size(size.width, track),
                    cornerRadius = CornerRadius(track / 2f),
                )
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        labels.forEachIndexed { index, label ->
            val selected = index == safeIndex
            val interaction = remember { MutableInteractionSource() }
            val pressed by interaction.collectIsPressedAsState()
            val pillAlpha by animateFloatAsState(
                targetValue = if (selected) 1f else 0f,
                animationSpec = tween(motionDuration(DshDuration.fast), easing = DshEasing.out),
                label = "topSegmentPill",
            )
            Box(
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(DshRadius.full))
                    .drawBehind {
                        // 药丸与按压反馈都只画在胶囊尺寸内（不用 ripple：48dp 热区的水波会盖过胶囊）；
                        // 四边内缩 2dp，是浮在轨道上的药丸，不是贴边的色块。
                        val inset = TopSegmentInset.toPx()
                        val pill = pillHeight.toPx()
                        val top = (size.height - pill) / 2f
                        val pillRect = Size(size.width - inset * 2f, pill)
                        val radius = CornerRadius(pill / 2f)
                        if (pillAlpha > 0f) {
                            drawRoundRect(
                                color = pillColor,
                                alpha = pillAlpha,
                                topLeft = Offset(inset, top),
                                size = pillRect,
                                cornerRadius = radius,
                            )
                        }
                        if (pressed) {
                            drawRoundRect(
                                color = pressTint,
                                topLeft = Offset(inset, top),
                                size = pillRect,
                                cornerRadius = radius,
                            )
                        }
                    }
                    .selectable(
                        selected = selected,
                        interactionSource = interaction,
                        indication = null,
                        role = Role.Tab,
                        onClick = {
                            if (index != safeIndex) haptic(DshHaptic.Tick)
                            onSelect(index)
                        },
                    )
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    color = if (selected) Dsh.labelPrimary else Dsh.labelSecondary,
                    style = DshType.t13M,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    maxLines = 1,
                )
            }
        }
    }
}

// ---------- 文字标签页（无底框） ----------

/**
 * 文字标签页：纯文字 + 选中下划线，无底框、无药丸。
 * 比胶囊 / 分段控件更安静——适合设置分区这类次要位置；顶栏已改用 [DshTopSegment]。
 * 视觉：选中 labelPrimary + Medium + 品牌色 2dp 下划线；未选中 labelTertiary + Normal。
 */
@Composable
fun DshTextTabs(
    labels: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (labels.isEmpty()) return
    val safeIndex = selectedIndex.coerceIn(0, labels.lastIndex)
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        labels.forEachIndexed { index, label ->
            val selected = index == safeIndex
            val interaction = remember { MutableInteractionSource() }
            Box(
                modifier = Modifier
                    // M3 触控目标 ≥48dp（原 44dp 不达标）
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(DshRadius.sm))
                    .clickable(
                        interactionSource = interaction,
                        indication = dshRipple(),
                        role = Role.Tab,
                        onClick = { onSelect(index) },
                    )
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    // IntrinsicSize.Max：让下划线的 fillMaxWidth 恰好等于单行文字宽度（Min 会取到单字、Max 外溢会撑满整行）
                    modifier = Modifier.width(androidx.compose.foundation.layout.IntrinsicSize.Max),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        label,
                        color = if (selected) dev.deeplinks.core.Dsh.labelPrimary else dev.deeplinks.core.Dsh.labelTertiary,
                        style = DshType.t13,
                        fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                        letterSpacing = 0.sp,
                        maxLines = 1,
                    )
                    Spacer(Modifier.height(3.dp))
                    // 下划线：宽度跟随文字（Column 宽 = 文字宽），未选中时透明避免跳动
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.dp)
                            .clip(RoundedCornerShape(1.dp))
                            .background(if (selected) dev.deeplinks.core.Dsh.brand400 else Color.Transparent),
                    )
                }
            }
        }
    }
}

@Composable
fun DshSheetGrabber() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .width(32.dp)
                .height(4.dp)
                .clip(CircleShape)
                .background(Dsh.borderStrong),
        )
    }
}

// ============================================================
// DshTag —— 小型语义标签（轨迹视图 chip / 会话元数据 / 分类标签）
// 语义角色：默认 None；可由 contentDescription 覆盖
// ============================================================
@Composable
fun DshTag(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Dsh.bgSubtle,
    contentColor: Color = Dsh.labelSecondary,
    borderColor: Color? = null,
    shape: Shape = RoundedCornerShape(DshRadius.sm),
    contentDescription: String? = null,
) {
    val mod = modifier
        .clip(shape)
        .background(color)
        .let { if (borderColor != null) it.border(1.dp, borderColor, shape) else it }
        .padding(horizontal = 8.dp, vertical = 2.dp)
        .semantics {
            if (contentDescription != null) this.contentDescription = contentDescription
        }
    Text(
        text = text,
        color = contentColor,
        style = DshType.microRelaxed,
        fontWeight = FontWeight(500),
        modifier = mod,
    )
}

// ============================================================
// DshBadge —— 圆点 / 数字徽章（导航项、状态点）
// 语义角色：默认 None（用于装饰时）；count 形式宣读为 "X"
// ============================================================
@Composable
fun DshBadge(
    modifier: Modifier = Modifier,
    color: Color = Dsh.error,
    contentColor: Color = Dsh.onBrand,
    count: Int? = null,
    dot: Boolean = false,
    contentDescription: String? = null,
) {
    val s = DshS
    if (dot && (count == null || count <= 0)) {
        Box(
            modifier = modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color)
                .semantics {
                    this.contentDescription = contentDescription ?: s.statusDot
                }
        )
        return
    }
    val showCount = count != null && count > 0
    val label = when {
        showCount -> if (count > 99) "99+" else count.toString()
        else -> ""
    }
    val fallbackDescription = if (showCount) s.unreadCount.format(label) else s.statusDot
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(DshRadius.full))
            .background(color)
            .padding(horizontal = if (showCount) 6.dp else 0.dp, vertical = if (showCount) 2.dp else 0.dp)
            .semantics {
                this.contentDescription = contentDescription ?: fallbackDescription
            },
        contentAlignment = Alignment.Center,
    ) {
        if (showCount) {
            Text(
                text = label,
                // 徽章底色任意（默认 error）：内容色朝底色的高对比侧收敛，保证 AA
                color = readableTextColor(contentColor, listOf(color)),
                style = DshType.t11x14SB,
                lineHeight = 14.sp,
                fontWeight = FontWeight(600),
            )
        }
    }
}

@Composable
fun DshBanner(
    text: String,
    modifier: Modifier = Modifier,
    tone: DshBannerTone = DshBannerTone.Info,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    contentDescription: String? = null,
) {
    val (bg, fg, accent) = when (tone) {
        // Info 用 bgSubtle：浅色 bgCard 与白画布同色，横幅会整块隐形（暗色下却是一张卡）。
        DshBannerTone.Info -> Triple(Dsh.bgSubtle, Dsh.labelSecondary, Dsh.brand400)
        DshBannerTone.Warn -> Triple(Dsh.warn.copy(alpha = 0.12f), Dsh.warnLabel, Dsh.warn)
        DshBannerTone.Error -> Triple(Dsh.errorBg, Dsh.error, Dsh.error)
        // 文字用 successContent：success 绿字压 12% 绿底只有 ≈2:1。
        DshBannerTone.Success -> Triple(Dsh.success.copy(alpha = 0.12f), Dsh.successContent, Dsh.success)
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(DshRadius.md))
            .background(bg)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .semantics {
                liveRegion = LiveRegionMode.Polite
                if (contentDescription != null) this.contentDescription = contentDescription
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // tone 指示条
        Box(
            modifier = Modifier
                .size(width = 3.dp, height = 16.dp)
                .background(accent, RoundedCornerShape(2.dp))
        )
        Spacer(Modifier.width(8.dp))
        if (leading != null) {
            leading()
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text = text,
            color = fg,
            style = DshType.bodyDense,
            modifier = Modifier.weight(1f),
        )
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.width(8.dp))
            val interaction = remember { MutableInteractionSource() }
            val pressed by interaction.collectIsPressedAsState()
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(DshRadius.sm))
                    .background(if (pressed) Dsh.pressed else Color.Transparent)
                    .clickable(interactionSource = interaction, indication = dshRipple(), onClick = onAction)
                    .heightIn(min = 48.dp)
                    .widthIn(min = 48.dp)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .semantics {
                        role = Role.Button
                        this.contentDescription = actionLabel
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = actionLabel,
                    color = accent,
                    style = DshType.bodyDense,
                    fontWeight = FontWeight(500),
                )
            }
        }
    }
}

enum class DshBannerTone { Info, Warn, Error, Success }

// ============================================================
// DshBanner —— 横条提示（断线横幅、审批等待、状态广播）
// 语义角色：默认无（仅公告）；可选 onAction 时宣读动作
// ============================================================
@Composable
fun ChatLoadingSkeleton(
    modifier: Modifier = Modifier,
    lineCount: Int = 4,
    contentDescription: String? = null,
) {
    val loadingLabel = contentDescription ?: DshS.loading
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .semantics { this.contentDescription = loadingLabel },
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // 助手文本行（宽，左对齐）
        repeat(lineCount) { idx ->
            val widthFrac = when (idx % 4) {
                0 -> 0.85f
                1 -> 0.65f
                2 -> 0.78f
                else -> 0.45f
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth(widthFrac)
                    .height(14.dp)
                    .clip(RoundedCornerShape(DshRadius.sm))
                    .background(Dsh.bgSubtle)
            )
        }
        // 用户气泡（短，右对齐）
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            Box(
                modifier = Modifier
                    .width(120.dp)
                    .height(28.dp)
                    .clip(RoundedCornerShape(DshRadius.lg))
                    .background(Dsh.bgSubtle)
            )
        }
        // 代码块占位（mono 字号）
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .clip(RoundedCornerShape(DshRadius.md))
                .background(Dsh.bgCode)
        )
    }
    // 默认内容色：默认即可（每个占位都有自己的颜色）
    CompositionLocalProvider(LocalContentColor provides Dsh.labelPrimary) {}
}

// ============================================================
// DshHeaderAction —— 卡片/弹窗头部的小号文字动作（复制 / 下载 / 关闭…）
// 语义角色：Button；原三份私有副本（Mermaid / Table / SelectText）合并至此
// ============================================================
@Composable
fun DshHeaderAction(
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        modifier = modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(DshRadius.sm))
            .background(if (pressed) Dsh.pressed else Color.Transparent)
            .semantics {
                role = Role.Button
                contentDescription = label
            }
            .clickable(interactionSource = interaction, indication = dshRipple(), onClick = onClick)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Dsh.labelTertiary, style = DshType.microRelaxed, lineHeight = 16.sp)
    }
}
