package dev.dsh.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dsh.mobile.Dsh

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
        selected -> Dsh.bgNavActive
        pressed -> Dsh.hover
        else -> Color.Transparent
    }
    val textColor = when {
        !enabled -> Dsh.labelDimmed
        selected -> Dsh.labelPrimary
        else -> Dsh.labelSecondary
    }
    Box(
        modifier = modifier
            .height(28.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(bg)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick
            )
            .padding(horizontal = 12.dp)
            .semantics {
                role = Role.Tab
                if (contentDescription != null) {
                    this.contentDescription = contentDescription
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                color = textColor,
                fontSize = 13.sp,
                fontWeight = FontWeight(500),
                lineHeight = 20.sp,
            )
            if (count != null) {
                Spacer(Modifier.width(4.dp))
                Text(
                    count.toString(),
                    color = if (selected) Dsh.labelPrimary.copy(alpha = 0.7f) else Dsh.labelTertiary,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
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
    color: Color = Dsh.bgLayer3,
    contentColor: Color = Dsh.labelSecondary,
    borderColor: Color? = null,
    shape: Shape = RoundedCornerShape(6.dp),
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
        fontSize = 11.sp,
        lineHeight = 16.sp,
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
    contentColor: Color = Dsh.labelPrimary,
    count: Int? = null,
    dot: Boolean = false,
    contentDescription: String? = null,
) {
    if (dot && (count == null || count <= 0)) {
        Box(
            modifier = modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color)
                .semantics {
                    this.contentDescription = contentDescription ?: "状态点"
                }
        )
        return
    }
    val showCount = count != null && count > 0
    val label = when {
        showCount -> if (count!! > 99) "99+" else count.toString()
        else -> ""
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(color)
            .padding(horizontal = if (showCount) 6.dp else 0.dp, vertical = if (showCount) 2.dp else 0.dp)
            .semantics {
                this.contentDescription = contentDescription
                    ?: (if (showCount) "未读 $label" else "状态点")
            },
        contentAlignment = Alignment.Center,
    ) {
        if (showCount) {
            Text(
                text = label,
                color = contentColor,
                fontSize = 10.sp,
                lineHeight = 14.sp,
                fontWeight = FontWeight(600),
            )
        }
    }
}

// ============================================================
// DshBanner —— 横条提示（断线横幅、审批等待、状态广播）
// 语义角色：默认无（仅公告）；可选 onAction 时宣读动作
// ============================================================
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
        DshBannerTone.Info -> Triple(Dsh.bgLayer1, Dsh.labelSecondary, Dsh.brand400)
        DshBannerTone.Warn -> Triple(Dsh.warn.copy(alpha = 0.12f), Dsh.warnLabel, Dsh.warn)
        DshBannerTone.Error -> Triple(Dsh.errorBg, Dsh.error, Dsh.error)
        DshBannerTone.Success -> Triple(Dsh.success.copy(alpha = 0.12f), Dsh.success, Dsh.success)
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .semantics {
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
            fontSize = 13.sp,
            lineHeight = 20.sp,
            modifier = Modifier.weight(1f),
        )
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.width(8.dp))
            val interaction = remember { MutableInteractionSource() }
            val pressed by interaction.collectIsPressedAsState()
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (pressed) Dsh.hover else Color.Transparent)
                    .clickable(interactionSource = interaction, indication = null, onClick = onAction)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .semantics {
                        role = Role.Button
                        this.contentDescription = actionLabel
                    },
            ) {
                Text(
                    text = actionLabel,
                    color = accent,
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                    fontWeight = FontWeight(500),
                )
            }
        }
    }
}

enum class DshBannerTone { Info, Warn, Error, Success }

// ============================================================
// ChatLoadingSkeleton —— 消息流加载骨架屏（WI-005）
// 三条圆角条形占位：用户气泡（短，右对齐）/ 助手文本（宽，左对齐）/ 代码块（mono，窄）
// 语义角色：默认无；宣读为"加载中"
// ============================================================
@Composable
fun ChatLoadingSkeleton(
    modifier: Modifier = Modifier,
    lineCount: Int = 4,
    contentDescription: String = "加载中",
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .semantics { this.contentDescription = contentDescription },
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
                    .clip(RoundedCornerShape(6.dp))
                    .background(Dsh.bgLayer3)
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
                    .clip(RoundedCornerShape(14.dp))
                    .background(Dsh.bgLayer3)
            )
        }
        // 代码块占位（mono 字号）
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Dsh.bgCode)
        )
    }
    // 默认内容色：默认即可（每个占位都有自己的颜色）
    CompositionLocalProvider(LocalContentColor provides Dsh.labelPrimary) {}
}