package dev.dsh.mobile.native.ui

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
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dsh.mobile.core.Dsh

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
// DiffTable —— 侧边对比表格（Diff Table，WI-007）
// 显示两列内容对比：原内容 vs 修订内容，支持行高亮
// 语义角色：Table；适用于代码差异、 markdown 表格差异展示
// ============================================================
@Composable
fun DiffTable(
    original: String,
    revised: String,
    modifier: Modifier = Modifier,
    blockSize: Int = 8,
) {
    val originalBlocks = parseMarkdown(original)
    val revisedBlocks = parseMarkdown(revised)

    var scrollState by remember { ScrollState() }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(scrollState),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(blockSize * 28.dp)
                .background(Dsh.bgLayer1)
                .border(1.dp, Dsh.borderL1, RoundedCornerShape(8.dp)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 原内容标题
            Text(
                text = "原内容",
                color = Dsh.labelSecondary,
                fontSize = 11.sp,
                fontWeight = FontWeight(600),
                modifier = Modifier
                    .fillMaxWidth(0.25f)
                    .padding(8.dp)
            )
            // 修订内容标题
            Spacer(Modifier.width(4.dp))
            Text(
                text = "修订",
                color = Dsh.labelSecondary,
                fontSize = 11.sp,
                fontWeight = FontWeight(600),
                modifier = Modifier
                    .fillMaxWidth(0.25f)
                    .padding(8.dp)
            )
        }

        // 内容区：左右两列
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Dsh.bgLayer1)
                .border(1.dp, Dsh.borderL1, RoundedCornerShape(8.dp)),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            // 左侧：原内容
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                originalBlocks.forEach { block ->
                    renderBlock(block, isOriginal = true)
                }
            }

            // 右侧：修订内容
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                revisedBlocks.forEach { block ->
                    renderBlock(block, isOriginal = false)
                }
            }
        }
    }
}

private fun parseMarkdown(text: String): List<MarkdownBlock> {
    val lines = text.lines()
    val blocks = mutableListOf<MarkdownBlock>()
    var currentRows: List<List<String>> = emptyList() // 每行是 cell 列表
    var inTable = false

    for (line in lines) {
        if (line.startsWith("|") && line.endsWith("|") && line.split("|").size >= 3) {
            // 表格行（至少 3 个管道：| cell | cell |）
            val cells = line
                .drop(1)
                .takeIf { it.endsWith("|") }
                ?.dropLast(1)
                ?.split("|")
                ?.map { it.trim() } ?: emptyList()
            if (cells.size >= 2) {
                currentRows.add(cells)
                inTable = true
            }
        } else if (inTable && line.isNotEmpty() && !line.startsWith("|")) {
            // 表格结束：保存表格块
            if (currentRows.size >= 2) {
                blocks.add(MarkdownBlock(MarkdownBlockType.TABLE, rows = currentRows))
            }
            currentRows = emptyList()
            inTable = false
        } else if (inTable && line.isEmpty()) {
            // 表格间的空行也算结束
            if (currentRows.size >= 2) {
                blocks.add(MarkdownBlock(MarkdownBlockType.TABLE, rows = currentRows))
            }
            currentRows = emptyList()
            inTable = false
        }
    }

    // 文件末尾未闭合的表格
    if (inTable && currentRows.size >= 2) {
        blocks.add(MarkdownBlock(MarkdownBlockType.TABLE, rows = currentRows))
    }

    // 扫描剩余的非表格行（属于段落而非表格）
    // 这里保持简单：仅在表格块之间插入段落标记
    return blocks
}

private sealed class MarkdownBlock(
    val type: MarkdownBlockType,
    val rows: List<List<String>> = emptyList(), // 二维：每行是 cell 列表
    val content: String = ""
)

private enum class MarkdownBlockType { PARAGRAPH, HEADING, LIST, CODE, QUOTE, TABLE, HR, IMAGE, MATH, EMPTY }

private fun renderBlock(block: MarkdownBlock, isOriginal: Boolean) {
    when (block.type) {
        MarkdownBlockType.TABLE -> {
            // 计算列宽：平均分配或基于内容
            val colCount = if (block.rows.isNotEmpty()) block.rows[0].size else 0
            val useWidth = if (colCount > 0) 1f / colCount else 1f

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(if (isOriginal) Dsh.bgLayer3 else Dsh.bgLayer1)
                    .border(1.dp, if (isOriginal) Dsh.borderL2 else Dsh.borderL1, RoundedCornerShape(8.dp))
            ) {
                block.rows.forEachIndexed { rowIdx, cells ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                        verticalAlignment = if (rowIdx == 0) Alignment.CenterVertically else Alignment.Start
                    ) {
                        cells.forEachIndexed { colIdx, cell ->
                            Box(
                                modifier = Modifier
                                    .weight(useWidth)
                                    .background(
                                        if (rowIdx == 0) (if (isOriginal) Dsh.bgLayer3 else Dsh.bgLayer1)
                                            else Color.Transparent
                                    )
                                    .border(
                                        0.5.dp,
                                        if (rowIdx == 0) (if (isOriginal) Dsh.borderL2 else Dsh.borderL1)
                                            else Dsh.borderL1,
                                        RoundedCornerShape(4.dp)
                                    )
                                    .padding(horizontal = 6.dp, vertical = 3.dp)
                            ) {
                                Text(
                                    text = cell,
                                    color = if (rowIdx == 0) Dsh.labelPrimary else Dsh.labelSecondary,
                                    fontSize = if (rowIdx == 0) 11.sp else 10.sp,
                                    lineHeight = 16.sp,
                                    fontWeight = if (rowIdx == 0) FontWeight(500) else FontWeight(400),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                    // 行分隔线（非首行显示）
                    if (rowIdx < block.rows.size - 1) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(0.5.dp)
                                .background(if (isOriginal) Dsh.borderL2 else Dsh.borderL1)
                        )
                    }
                }
            }
        }
        MarkdownBlockType.PARAGRAPH -> {
            Text(
                text = block.content,
                color = if (isOriginal) Dsh.labelPrimary else Dsh.labelSecondary,
                fontSize = 13.sp,
                lineHeight = 20.sp,
            )
        }
        else -> InlineText(block.content)
    }
}

private fun InlineText(text: String) {
    Text(text = text, color = Dsh.labelPrimary, fontSize = 13.sp, lineHeight = 20.sp)
}
// ============================================================
// End DiffTable
// ============================================================
// RecommendationCard — 推荐卡片（Recommendation Card，WI-008）
// 基于上下文统计提供快捷操作建议：重新规划、继续任务、查看洞察等
// 语义角色：无；在 running 状态下根据 sessionStats 条件渲染
// ============================================================
@Composable
fun RecommendationCard(
    sessionStats: MobileSessionStats?,
    running: Boolean,
    modifier: Modifier = Modifier,
) {
    if (!running || sessionStats == null) return

    val used = sessionStats.contextPressureTokens
    val window = sessionStats.contextWindow
    if (window <= 0) return
    val percent = ((used * 100) / window).toInt()

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp)
            .padding(vertical = 4.dp)
            .background(Dsh.bgLayer3)
            .border(1.dp, Dsh.borderL2, RoundedCornerShape(8.dp))
            .semantics {
                this.contentDescription = "推荐操作：基于当前上下文压力"
            },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            // 左侧：上下文压力概览
            Box(
                modifier = Modifier
                    .width(8.dp)
                    .height(8.dp)
                    .clip(CircleShape)
                    .background(
                        if (percent > 80) Dsh.error
                        else if (percent > 50) Dsh.warn
                        else Dsh.success
                    )
            )

            // 右侧：推荐文本
            Text(
                text = buildAnnotatedString {
                    if (percent > 80) {
                        append("⚠️ 上下文压力较高，建议：")
                        append("\n  •  /clear - 清除上下文")
                        append("\n  •  /goal - 设定持续目标")
                    } else if (percent > 50) {
                        append("💡 上下文压力中等，建议：")
                        append("\n  •  /plan - 生成执行计划")
                        append("\n  •  /pause - 暂停当前任务")
                    } else {
                        append("✅ 上下文充足，建议：")
                        append("\n  •  /resume - 继续任务")
                        append("\n  •  /skills - 查看可用技能")
                    }
                },
                color = Dsh.labelPrimary,
                fontSize = 11.sp,
                lineHeight = 16.sp,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
// ============================================================
// End RecommendationCard
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
// DshBanner —— 横条提示（断线横幅、审批等待、状态广播）
// 语义角色：默认无（仅公告）；可选 onAction 时宣读动作
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