package dev.deeplinks.native

import dev.deeplinks.core.DshType

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import dev.deeplinks.native.ui.ChatLoadingSkeleton
import dev.deeplinks.native.util.ChatCanvasKind
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.deeplinks.core.Dsh
import androidx.compose.foundation.layout.Arrangement
import dev.deeplinks.native.util.MessageGroup
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import dev.deeplinks.core.dshRipple
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.stateDescription
import dev.deeplinks.native.util.compactTokens
import dev.deeplinks.core.L
import dev.deeplinks.native.ui.DshBanner
import dev.deeplinks.native.ui.DshBannerTone
import dev.deeplinks.native.util.StreamBannerKind
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.foundation.layout.navigationBarsPadding
import dev.deeplinks.native.ui.DshSheetGrabber
import androidx.compose.ui.draw.shadow

/**
 * Workspace 主界面抽出的独立 chrome（COM-001 拆解）。
 * 与 WorkspaceScreen 同包，通过 internal 复用；不持有业务状态。
 */

/**
 * 工具调用查找条（从 WorkspaceScreen 抽出，COM-001 拆解）。
 * 客户端过滤当前会话工具消息，瞬态状态不持久化。
 */
@Composable
internal fun ToolSearchBar(
    visible: Boolean,
    query: String,
    onQueryChange: (String) -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        enter = expandVertically(animationSpec = tween(motionDuration(180))) + fadeIn(animationSpec = tween(motionDuration(150))),
        exit = shrinkVertically(animationSpec = tween(motionDuration(160))) + fadeOut(animationSpec = tween(motionDuration(120)))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = COMPOSER_SIDE_CLEARANCE, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(DshRadius.md))
                    .background(Dsh.bgInput)
                    .border(1.dp, Dsh.borderSubtle, RoundedCornerShape(DshRadius.md))
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(SearchOutline16, contentDescription = null, tint = Dsh.labelTertiary, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    textStyle = DshType.bodyDense.copy(color = Dsh.labelPrimary),
                    cursorBrush = SolidColor(Dsh.brand400),
                    modifier = Modifier.weight(1f),
                    decorationBox = { inner ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (query.isEmpty()) {
                                Text(L.toolSearchPlaceholder, color = Dsh.labelTertiary, style = DshType.t13, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            inner()
                        }
                    }
                )
                if (query.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .semantics {
                                role = Role.Button
                                contentDescription = L.clearSearch
                            }
                            .clickable { onQueryChange("") },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            CloseOutline16,
                            contentDescription = null,
                            tint = Dsh.labelTertiary,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * 断线重连横幅（从 WorkspaceScreen 抽出，COM-001 拆解）。
 * SSE 断开时提示，客户端自动退避重连；复用 [DshBanner]。
 */
@Composable
internal fun StreamReconnectBanner(
    kind: StreamBannerKind,
    onRetry: () -> Unit,
) {
    AnimatedVisibility(
        visible = kind != StreamBannerKind.Hidden,
        enter = expandVertically(animationSpec = tween(motionDuration(200))) + fadeIn(animationSpec = tween(motionDuration(200))),
        exit = shrinkVertically(animationSpec = tween(motionDuration(180))) + fadeOut(animationSpec = tween(motionDuration(180)))
    ) {
        DshBanner(
            text = when (kind) {
                StreamBannerKind.Connecting -> L.connecting
                StreamBannerKind.Failed -> L.connectionFailedReconnecting
                else -> L.disconnectedReconnecting
            },
            tone = if (kind == StreamBannerKind.Failed)
                DshBannerTone.Error else DshBannerTone.Info,
            actionLabel = L.retry,
            onAction = onRetry,
            leading = {
                val reconnRotation = rememberMotionSpin(900, label = "reconnRot")
                Icon(
                    RefreshOutline16,
                    contentDescription = null,
                    tint = Dsh.labelTertiary,
                    modifier = Modifier
                        .size(12.dp)
                        .rotate(reconnRotation ?: 0f)
                )
            },
            contentDescription = when (kind) {
                StreamBannerKind.Connecting -> L.connecting
                StreamBannerKind.Failed -> L.connectionFailedReconnecting
                else -> L.disconnectedReconnectingContentDescription
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

/**
 * 设备不可达横幅（方案 §4.6）：最近设备离线时不强制跳回设备页，
 * Workspace 仍然打开，内容区顶部给出「重试 / 设备与配对」两个明确下一步。
 */
@Composable
internal fun DeviceUnreachableBanner(
    hostName: String,
    visible: Boolean,
    onRetry: () -> Unit,
    onOpenDevice: () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        enter = expandVertically(animationSpec = tween(motionDuration(200))) +
            fadeIn(animationSpec = tween(motionDuration(200))),
        exit = shrinkVertically(animationSpec = tween(motionDuration(180))) +
            fadeOut(animationSpec = tween(motionDuration(180))),
    ) {
        val message = L.cannotConnectHost.format(hostName)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp)
                .clip(RoundedCornerShape(DshRadius.md))
                .background(Dsh.bgCard)
                .border(1.dp, Dsh.borderSubtle, RoundedCornerShape(DshRadius.md))
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .semantics { contentDescription = message },
        ) {
            Text(
                message,
                color = Dsh.labelSecondary,
                style = DshType.captionRelaxed,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BannerAction(label = L.deviceAndPairing, onClick = onOpenDevice)
                Spacer(Modifier.width(4.dp))
                BannerAction(label = L.retry, primary = true, onClick = onRetry)
            }
        }
    }
}

@Composable
private fun BannerAction(label: String, primary: Boolean = false, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(DshRadius.full))
            // 实心主按钮统一 brand500（brand400 底配白色小字在暗色下不足 AA）
            .background(if (primary) Dsh.brand500 else Color.Transparent)
            .clickable(onClick = onClick)
            .semantics {
                role = Role.Button
                contentDescription = label
            }
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (primary) Dsh.onBrand else Dsh.labelPrimary,
            style = DshType.microRelaxed,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
internal fun HeroShell() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 品牌图形：与设备页空态同一枚 mark（ic_dsh_mark），弱蓝底 + 发丝描边
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(Dsh.brand400.copy(alpha = 0.12f))
                .border(1.dp, Dsh.brand400.copy(alpha = 0.28f), RoundedCornerShape(20.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(dev.deeplinks.R.drawable.ic_dsh_mark),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Spacer(Modifier.height(20.dp))
        Text(
            L.heroSlogan,
            color = Dsh.labelPrimary,
            // 字重/行高由 token 自带，不在调用点重复覆盖（避免 token 演进被拽回旧值）
            style = DshType.t20x28SB,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            L.heroHint,
            color = Dsh.labelTertiary,
            style = DshType.t13,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
internal fun ChatHistoryError(
    message: String,
    onRetry: () -> Unit,
    title: String = L.loadConversationFailed,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
            Text(
                title,
                color = Dsh.labelPrimary,
                style = DshType.t16SB,
                fontWeight = FontWeight(600),
                lineHeight = 22.sp,
            )
        Spacer(Modifier.height(6.dp))
        Text(
            message,
            color = Dsh.labelTertiary,
            style = DshType.titleSmall,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        Box(
            modifier = Modifier
                .heightIn(min = 48.dp)
                .clip(RoundedCornerShape(DshRadius.full))
                .background(Dsh.brand400)
                .clickable(onClick = onRetry)
                .padding(horizontal = 20.dp)
                .semantics { role = Role.Button },
            contentAlignment = Alignment.Center,
        ) {
            Text(L.retry, color = Dsh.onBrand, style = DshType.t13M, fontWeight = FontWeight(500))
        }
    }
}

@Composable
internal fun SearchStatusBanner(message: String, onRetry: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(DshRadius.md))
            .background(Dsh.bgCard)
            .border(1.dp, Dsh.borderSubtle, RoundedCornerShape(DshRadius.md))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            message,
            color = Dsh.labelSecondary,
            style = DshType.captionRelaxed,
            modifier = Modifier.weight(1f),
        )
        Box(
            modifier = Modifier
                .heightIn(min = 48.dp)
                .clip(RoundedCornerShape(DshRadius.full))
                .background(Dsh.brand400)
                .semantics {
                    role = Role.Button
                    contentDescription = L.retry
                }
                .clickable(onClick = onRetry)
                .padding(horizontal = 12.dp)
                .wrapContentHeight(Alignment.CenterVertically),
        ) {
            Text(L.retry, color = Dsh.onBrand, style = DshType.t12M, fontWeight = FontWeight(500))
        }
    }
}


@Composable
internal fun ContextMeterButton(
    stats: MobileSessionStats,
    running: Boolean = false,
    showPercent: Boolean = true,
) {
    var expanded by remember { mutableStateOf(false) }
    val used = stats.contextPressureTokens
    val window = stats.contextWindow
    if (window <= 0) return
    val percent = ((used * 100) / window).toFloat().coerceIn(0f, 100f)
    // 明细占比（系统/工具/对话消息，按 breakdown 分段）
    val breakdownTotal = stats.systemTokens + stats.toolsTokens + stats.messageTokens
    val hasBreakdown = breakdownTotal > 0
    val systemRatio = if (hasBreakdown) stats.systemTokens.toFloat() / breakdownTotal else 0f
    val toolsRatio = if (hasBreakdown) stats.toolsTokens.toFloat() / breakdownTotal else 0f
    val messagesRatio = if (hasBreakdown) stats.messageTokens.toFloat() / breakdownTotal else 0f

    Box {
        // 环形按钮（DSH：28px trigger，14px viewBox 圆环，2px stroke，后面跟百分比）
        val interaction = remember { MutableInteractionSource() }
        val pressed by interaction.collectIsPressedAsState()
        val borderL3Color = Dsh.borderStrong
        val fillColor = if (running) Dsh.labelTertiary.copy(alpha = 0.55f) else Dsh.labelTertiary
        Row(
            modifier = Modifier
                .height(48.dp)
                .clip(RoundedCornerShape(DshRadius.full))
                .background(if (pressed) Dsh.pressed else Color.Transparent)
                .semantics {
                    role = Role.Button
                    contentDescription = L.contextUsed
                    stateDescription = "${percent.toInt()}%"
                }
                .clickable(interactionSource = interaction, indication = dshRipple()) { expanded = true }
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Canvas(modifier = Modifier.size(14.dp)) {
                val stroke = 2.dp.toPx()
                val arcSize = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke)
                drawArc(
                    color = borderL3Color,
                    startAngle = 0f,
                    sweepAngle = 360f,
                    useCenter = false,
                    style = Stroke(width = stroke),
                    topLeft = androidx.compose.ui.geometry.Offset(stroke / 2, stroke / 2),
                    size = arcSize
                )
                if (used > 0) {
                    drawArc(
                        color = fillColor,
                        startAngle = -90f,
                        sweepAngle = 360f * percent / 100f,
                        useCenter = false,
                        style = Stroke(width = stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round),
                        topLeft = androidx.compose.ui.geometry.Offset(stroke / 2, stroke / 2),
                        size = arcSize
                    )
                }
            }
            if (showPercent) {
                Text(
                    text = "${percent.toInt()}%",
                    color = Dsh.labelTertiary,
                    style = DshType.t12x20,
                    lineHeight = 20.sp,
                    maxLines = 1,
                )
            }
        }

        // 用量面板（DSH：240dp 宽、radius 12、上方弹出）
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = Dsh.bgSubtle,
            shape = RoundedCornerShape(DshRadius.lg)
        ) {
            Column(modifier = Modifier.width(240.dp).padding(12.dp)) {
                // header：上下文已用 + 百分比 + 用量数字
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(L.contextUsed, color = Dsh.labelTertiary, style = DshType.t12x20, lineHeight = 20.sp)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "${percent.toInt()}%",
                        color = Dsh.labelPrimary,
                        style = DshType.t12x20M,
                        lineHeight = 20.sp,
                        fontWeight = FontWeight(500)
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        "~${compactTokens(used)} / ${compactTokens(window)}",
                        color = Dsh.labelPrimary,
                        style = DshType.t12x20M,
                        lineHeight = 20.sp,
                        fontWeight = FontWeight(500),
                        fontFamily = FontFamily.Monospace
                    )
                }
                Spacer(Modifier.height(10.dp))
                // 分段条（DSH：4px 高、系统/工具/消息按占比分段）
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Dsh.pressed)
                ) {
                    if (hasBreakdown) {
                        // 段色与下方明细行的色块一一对应（systemAccent / toolsAccent / brand400）
                        val segments = listOf(
                            systemRatio to Dsh.systemAccent,
                            toolsRatio to Dsh.toolsAccent,
                            messagesRatio to Dsh.brand400,
                        )
                        segments.forEach { (ratio, color) ->
                            if (ratio > 0f) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxHeight()
                                        .fillMaxWidth(ratio)
                                        .background(color)
                                )
                            }
                        }
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(percent / 100f)
                                .background(Dsh.labelTertiary)
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                // 明细行（系统提示词/工具/对话消息 + 色块 + tok 数）
                ContextMeterRow(L.systemPrompt, compactTokens(stats.systemTokens), Dsh.systemAccent)
                Spacer(Modifier.height(4.dp))
                ContextMeterRow(L.tools, compactTokens(stats.toolsTokens), Dsh.toolsAccent)
                Spacer(Modifier.height(4.dp))
                ContextMeterRow(L.chatMessages, compactTokens(stats.messageTokens), Dsh.brand400)
            }
        }
    }
}

@Composable
internal fun ContextMeterRow(label: String, value: String, swatchColor: Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(swatchColor)
        )
        Spacer(Modifier.width(6.dp))
        Text(label, color = Dsh.labelSecondary, style = DshType.captionRelaxed, lineHeight = 18.sp, modifier = Modifier.weight(1f))
        Text(value, color = Dsh.labelPrimary, style = DshType.captionRelaxed, lineHeight = 18.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
internal fun CommandSuggestions(
    query: String,
    onPick: (PaletteCommand) -> Unit,
) {
    val grouped = remember(query) { filterPalette(DSH_PALETTE, query) }
    // 兜底：query 与输入文本同步；只有至少一组有结果才显示 picker
    if (grouped.isEmpty()) return
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = COMPOSER_SIDE_CLEARANCE, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(DshRadius.lg))
                .background(Dsh.bgCard)
                .border(1.dp, Dsh.borderSubtle, RoundedCornerShape(DshRadius.lg))
                .padding(vertical = 6.dp)
        ) {
            grouped.forEach { (group, entries) ->
                Text(
                    group.displayName,
                    color = Dsh.labelTertiary,
                    style = DshType.captionRelaxed,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp)
                )
                entries.forEach { entry ->
                    val interaction = remember { MutableInteractionSource() }
                    val pressed by interaction.collectIsPressedAsState()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .clip(RoundedCornerShape(DshRadius.sm))
                            .background(if (pressed) Dsh.pressed else Color.Transparent)
                            .clickable(interactionSource = interaction, indication = dshRipple()) { onPick(entry.command) }
                            .padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            entry.command.trigger,
                            color = Dsh.labelPrimary,
                            style = DshType.bodyDense,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            // 触发词与描述分列：固定 220dp 会把 412dp 上的描述压到两三个字
                            modifier = Modifier
                                .widthIn(max = 160.dp)
                                .weight(0.45f, fill = false)
                        )
                        Text(
                            entry.command.description,
                            color = Dsh.labelTertiary,
                            style = DshType.captionRelaxed,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}

/** 导出会话为纯文本（分享用；无独立 log 下载 API 时的替代方案）。 */
internal fun loadSessionMessagesForExport(client: MobileApiClient, sessionId: String): List<MobileMessage> {
    val pages = mutableListOf<MobileMessage>()
    var before: Long? = null
    repeat(20) {
        val page = client.getSessionHistory(sessionId, beforeSeq = before, maxMessages = 80)
        pages.addAll(0, page.messages)
        before = page.nextBeforeSeq
        if (!page.hasMore || before == null) return@repeat
    }
    return pages
}

internal fun exportSessionTranscript(client: MobileApiClient, sessionId: String, title: String): String {
    val body = loadSessionMessagesForExport(client, sessionId).mapNotNull { msg ->
        val role = when (msg.role) {
            "user" -> L.userRole
            "assistant" -> L.assistantRole
            "reasoning" -> L.reasoningRole
            "tool_call" -> "${L.tools}:${msg.toolName ?: "?"}"
            "tool_result" -> L.resultRole
            else -> msg.role
        }
        val text = msg.text.ifBlank { msg.toolArgs.orEmpty() }.trim()
        if (text.isBlank()) null else "## $role\n$text"
    }.joinToString("\n\n")
    return "# $title\n\n$body"
}

/** 聚合 header：轻量行（对齐思考条），默认收起；展开后左侧细轨 + 明细。 */
@Composable
internal fun ToolGroupHeader(
    group: MessageGroup.ToolGroup,
    sweepingId: String?,
) {
    var expanded by remember(group.groupKey) { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val first = group.items.first()
    val last = group.items.last()
    val totalDuration = if (first.time > 0 && last.time >= first.time) last.time - first.time else null
    val groupRunning = sweepingId != null && group.items.any { it.id == sweepingId }
    val pressTint = Dsh.pressed
    val rail = Dsh.borderStrong
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .heightIn(min = 48.dp)
                .clip(RoundedCornerShape(DshRadius.sm))
                .clickable(interactionSource = interaction, indication = dshRipple()) { expanded = !expanded }
                .semantics {
                    role = Role.Button
                    contentDescription = L.toolCallCount.format(group.items.size)
                    stateDescription = if (expanded) L.collapse else L.expand
                }
                .then(if (pressed) Modifier.drawBehind { drawRect(pressTint) } else Modifier)
                .padding(horizontal = 6.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (groupRunning) {
                CircularProgressIndicator(
                    modifier = Modifier.size(13.dp),
                    color = Dsh.brand500,
                    strokeWidth = 1.5.dp,
                )
                Spacer(Modifier.width(10.dp))
            } else {
                Icon(
                    CodeOutline16,
                    contentDescription = null,
                    tint = Dsh.labelTertiary,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(8.dp))
            }
            Text(
                L.toolCallCount.format(group.items.size),
                color = if (groupRunning) Dsh.labelSecondary else Dsh.labelTertiary,
                style = DshType.t13M,
                fontWeight = FontWeight(500),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.width(8.dp))
            if (groupRunning) {
                ShimmerLabel(text = L.executing.trimEnd('…', '.'), working = true)
                Spacer(Modifier.width(8.dp))
            } else if (totalDuration != null) {
                Text(
                    formatTraceDuration(totalDuration),
                    color = Dsh.labelTertiary,
                    style = DshType.t11,
                    fontFamily = FontFamily.Monospace,
                )
                Spacer(Modifier.width(6.dp))
            }
            Icon(
                if (expanded) ChevronUpOutline14 else ChevronDownOutline14,
                // 展开状态已在行的 stateDescription 里播报，图标不再重复报一遍
                contentDescription = null,
                tint = Dsh.labelTertiary,
                modifier = Modifier.size(14.dp),
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(animationSpec = tween(motionDuration(200), easing = FastOutSlowInEasing)) + fadeIn(animationSpec = tween(motionDuration(150))),
            exit = shrinkVertically(animationSpec = tween(motionDuration(180), easing = FastOutSlowInEasing)) + fadeOut(animationSpec = tween(motionDuration(150))),
        ) {
            Box(
                modifier = Modifier
                    .padding(start = 7.dp, top = 2.dp)
                    .drawBehind {
                        val x = 3.5.dp.toPx()
                        drawLine(rail, Offset(x, 0f), Offset(x, size.height), 1.dp.toPx())
                    }
                    .padding(start = 16.dp, top = 4.dp, bottom = 4.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    group.items.forEach { item ->
                        MessageItem(
                            msg = item,
                            running = sweepingId != null && item.id == sweepingId,
                            onCopy = {},
                            onQuote = {},
                            onFork = {},
                        )
                    }
                }
            }
        }
    }
}

/**
 * 单行顶栏（原生化）：≡ 侧栏按钮 + 标题▾（点按开会话抽屉）+ 对话/轨迹分段 + 更多菜单。
 * 纯展示：菜单项由调用方通过 [workspaceHeaderMenuItems] 构建后传入。
 */
@Composable
internal fun WorkspaceTopBar(
    running: Boolean,
    title: String,
    onOpenDrawer: () -> Unit,
    viewMode: String,
    onSelectViewMode: (String) -> Unit,
    menuExpanded: Boolean,
    onMenuExpandedChange: (Boolean) -> Unit,
    menuItems: List<DshMenuItem>,
    /** 本会话最新一轮改动；null 时不出入口（旧插件 / 没改过文件）。 */
    latestChanges: WorkspaceChangesSummary? = null,
    onOpenChanges: () -> Unit = {},
    /** 活动目标文本；非空时在标题旁显示一个紧凑的目标胶囊。 */
    goalSummary: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .padding(start = 4.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // ≡ 侧栏按钮（始终可见，作为抽屉/侧栏的主入口）
        val sidebarInteraction = remember { MutableInteractionSource() }
        val sidebarPressed by sidebarInteraction.collectIsPressedAsState()
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(if (sidebarPressed) Dsh.pressed else Color.Transparent)
                .clickable(interactionSource = sidebarInteraction, indication = dshRipple()) { onOpenDrawer() }
                .semantics {
                    role = Role.Button
                    contentDescription = L.sessionMenu
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                PanelLeftOutline16,
                contentDescription = null,
                tint = Dsh.labelSecondary,
                modifier = Modifier.size(18.dp),
            )
        }

        // 标题 = 会话切换入口（点按开抽屉，充分释放横向阅读空间）
        val titleInteraction = remember { MutableInteractionSource() }
        Row(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(DshRadius.md))
                .clickable(interactionSource = titleInteraction, indication = dshRipple()) { onOpenDrawer() }
                .semantics {
                    role = Role.Button
                    contentDescription = title
                }
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (running) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(Dsh.brand400),
                )
                Spacer(Modifier.width(6.dp))
            }
            Text(
                title,
                color = Dsh.labelPrimary,
                style = DshType.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }

        // 改动入口：左滑手势的显式替身（手势不能是唯一入口），只写最新一轮的文件数
        if (latestChanges != null) {
            Box(
                modifier = Modifier
                    .heightIn(min = 44.dp)
                    .clip(RoundedCornerShape(DshRadius.full))
                    .clickable(indication = dshRipple(), interactionSource = null, onClick = onOpenChanges)
                    .semantics {
                        role = Role.Button
                        contentDescription = "${ChangesL.viewChanges}: ${ChangesL.cardTitle(latestChanges)}"
                    }
                    .padding(horizontal = 6.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(EditOutline16, contentDescription = null, tint = Dsh.labelSecondary, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(3.dp))
                    Text("${latestChanges.total}", color = Dsh.labelSecondary, style = DshType.t12M, maxLines = 1)
                }
            }
        }

        // 活动目标摘要胶囊：仅运行中且 goal 非空时出现
        val activeGoal = goalSummary?.takeIf { running && it.isNotBlank() }
        if (activeGoal != null) {
            Box(
                modifier = Modifier
                    .heightIn(min = 28.dp)
                    .clip(RoundedCornerShape(DshRadius.full))
                    .background(Dsh.brand400.copy(alpha = 0.1f))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
                contentAlignment = Alignment.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    Icon(
                        GoalOutline16,
                        contentDescription = null,
                        tint = Dsh.brand400,
                        modifier = Modifier.size(13.dp),
                    )
                    Text(
                        text = activeGoal.take(28) + if (activeGoal.length > 28) "…" else "",
                        color = Dsh.brand400,
                        style = DshType.t11M,
                        maxLines = 1,
                    )
                }
            }
        }

        // 对话 / 轨迹：紧凑微胶囊切换（图标+文字明确语义，触控48dp，视觉仅~56dp，不挤占标题）
        val isTrace = viewMode == "trace"
        val viewModeInteraction = remember { MutableInteractionSource() }
        val viewModePressed by viewModeInteraction.collectIsPressedAsState()
        Box(
            modifier = Modifier
                .heightIn(min = 44.dp)
                .clip(RoundedCornerShape(DshRadius.full))
                .clickable(interactionSource = viewModeInteraction, indication = dshRipple()) {
                    onSelectViewMode(if (isTrace) "chat" else "trace")
                }
                .semantics {
                    role = Role.Button
                    contentDescription = if (isTrace) L.tabChat else L.tabTrace
                }
                .padding(horizontal = 4.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(DshRadius.full))
                    .background(
                        when {
                            viewModePressed -> Dsh.pressed
                            isTrace -> Dsh.brandTint
                            else -> Dsh.bgTrack
                        }
                    )
                    .border(
                        1.dp,
                        if (isTrace) Dsh.brand400.copy(alpha = 0.35f) else Dsh.borderSubtle,
                        RoundedCornerShape(DshRadius.full)
                    )
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Icon(
                    if (isTrace) Icons.Outlined.ChatBubbleOutline else CodeOutline16,
                    contentDescription = null,
                    tint = if (isTrace) Dsh.brand500 else Dsh.labelSecondary,
                    modifier = Modifier.size(13.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = if (isTrace) L.tabChat else L.tabTrace,
                    color = if (isTrace) Dsh.brand500 else Dsh.labelSecondary,
                    style = DshType.t12M,
                    fontWeight = if (isTrace) FontWeight.SemiBold else FontWeight.Medium,
                    maxLines = 1,
                )
            }
        }

        Spacer(Modifier.width(2.dp))

        Box {
            val moreInteraction = remember { MutableInteractionSource() }
            val morePressed by moreInteraction.collectIsPressedAsState()
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(if (morePressed) Dsh.pressed else Color.Transparent)
                    .clickable(interactionSource = moreInteraction, indication = dshRipple()) { onMenuExpandedChange(true) },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    EllipsisOutline16,
                    contentDescription = L.moreActions,
                    tint = Dsh.labelSecondary,
                    modifier = Modifier.size(18.dp),
                )
            }
            DshMenu(
                expanded = menuExpanded,
                onDismiss = { onMenuExpandedChange(false) },
                items = menuItems,
            )
        }
    }
}
/**
 * 空会话画布（从 WorkspaceScreen 的 LazyColumn 抽出，COM-001 拆解）：
 * 加载 / 运行 / 失败 / 空态四选一，属于 LazyListScope 所以做成扩展。
 */
internal fun LazyListScope.chatEmptyCanvas(
    kind: ChatCanvasKind,
    elapsedSec: Long,
    historyLoadError: String?,
    onRetry: () -> Unit,
) {
    when (kind) {
        ChatCanvasKind.Loading -> item(key = "chat-loading-skeleton") {
            ChatLoadingSkeleton()
        }
        ChatCanvasKind.Working -> item(key = "turn-status") {
            ThinkingStatusRow(elapsedSec)
        }
        ChatCanvasKind.Error -> item(key = "chat-load-error") {
            ChatHistoryError(
                message = historyLoadError ?: L.loadConversationFailed,
                onRetry = onRetry,
            )
        }
        ChatCanvasKind.Empty, ChatCanvasKind.Content -> item(key = "empty-hero") {
            Box(
                modifier = Modifier
                    .fillParentMaxSize()
                    .padding(horizontal = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                HeroShell()
            }
        }
    }
}

/**
 * 悬浮「回到底部」：40dp 圆形 + 阴影，盖在消息流右下角，不占布局高度。
 * 位置与原生聊天 App 一致（右下角），出现/消失不会让消息流串位；
 * 用户停在历史时新到达的消息条数挂在右上角。
 */
@Composable
internal fun ScrollToBottomButton(unread: Int, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        modifier = Modifier
            .size(48.dp)
            .clickable(interactionSource = interaction, indication = dshRipple(), onClick = onClick)
            .semantics {
                role = Role.Button
                contentDescription = L.scrollToBottom
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .shadow(6.dp, CircleShape)
                .clip(CircleShape)
                .background(if (pressed) Dsh.bgPressed else Dsh.bgCard),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Default.KeyboardArrowDown,
                contentDescription = null,
                tint = Dsh.labelPrimary,
                modifier = Modifier.size(22.dp),
            )
        }
        if (unread > 0) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .defaultMinSize(minWidth = 18.dp)
                    .clip(CircleShape)
                    .background(Dsh.brand500)
                    .padding(horizontal = 5.dp, vertical = 2.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (unread > 99) "99+" else unread.toString(),
                    color = Dsh.onBrand,
                    style = DshType.microRelaxed,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

/**
 * 会话级统计（输入框下方）：累计信息在此处紧凑显示，避免挂在某一条历史回复上。
 * 居中胶囊（轮次·步骤 / Token / 速率，分段圆点分隔），点按展开 Paseo 式用量与性能看板。
 */
@Composable
internal fun SessionStatsLine(stats: MobileSessionStats?) {
    val s = stats ?: return
    var detailOpen by remember { mutableStateOf(false) }

    val inputTokens = s.uncachedInputTokens + s.cacheReadTokens
    val totalTokens = inputTokens + s.outputTokens
    val parts = buildList {
        if (s.turns > 0 || s.steps > 0) add(L.statsTurnsSteps.format(s.turns, s.steps))
        if (totalTokens > 0) {
            add("${compactTokens(totalTokens)} tok")
        } else if (inputTokens > 0 || s.outputTokens > 0) {
            add(L.inputOutputTokens.format(compactTokens(inputTokens), compactTokens(s.outputTokens)))
        }
        if (s.decodeMs > 0 && s.decodeTokens > 0) {
            add(String.format(java.util.Locale.US, "%.0f tok/s", s.decodeTokens * 1000.0 / s.decodeMs))
        }
    }
    if (parts.isEmpty()) return

    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = COMPOSER_SIDE_CLEARANCE, vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(DshRadius.full))
                .background(if (pressed) Dsh.pressed else Dsh.bgTrack.copy(alpha = 0.6f))
                .semantics {
                    role = Role.Button
                    contentDescription = L.statsViewDetails
                }
                .clickable(
                    interactionSource = interaction,
                    indication = dshRipple(),
                    onClick = { detailOpen = true },
                )
                .padding(horizontal = 12.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            parts.forEachIndexed { index, part ->
                if (index > 0) {
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 7.dp)
                            .size(2.5.dp)
                            .clip(CircleShape)
                            .background(Dsh.labelTertiary.copy(alpha = 0.45f)),
                    )
                }
                Text(
                    text = part,
                    color = Dsh.labelTertiary,
                    style = DshType.captionRelaxed,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(6.dp))
            Icon(
                Icons.Default.Info,
                contentDescription = null,
                tint = Dsh.labelTertiary.copy(alpha = 0.6f),
                modifier = Modifier.size(11.dp),
            )
        }
    }

    if (detailOpen) {
        SessionStatsDetailSheet(
            stats = s,
            onDismiss = { detailOpen = false },
        )
    }
}

/**
 * 会话用量与执行性能看板（Paseo 式 HUD BottomSheet）：
 * 完整结构化展示交互轮次、耗时、缓存命中、解码吞吐速率与 Token 明细，彻底解决单行挤占与截断问题。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SessionStatsDetailSheet(
    stats: MobileSessionStats,
    onDismiss: () -> Unit,
) {
    val s = stats
    val inputTokens = s.uncachedInputTokens + s.cacheReadTokens
    val totalTokens = inputTokens + s.outputTokens
    val cacheHitPercent = if (inputTokens > 0) ((s.cacheReadTokens * 100) / inputTokens).toInt() else 0
    val speedToks = if (s.decodeMs > 0 && s.decodeTokens > 0) {
        s.decodeTokens * 1000.0 / s.decodeMs
    } else 0.0

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Dsh.bgCard,
        contentColor = Dsh.labelPrimary,
        shape = DshSheetShape,
        dragHandle = null,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
        ) {
            DshSheetGrabber()

            Text(
                text = L.sessionStatsSheetTitle,
                style = DshType.titleLarge,
                color = Dsh.labelPrimary,
                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
            )
            Text(
                text = L.statsTurnsCount.format(s.turns) + " · " + L.statsStepsCount.format(s.steps),
                style = DshType.captionRelaxed,
                color = Dsh.labelSecondary,
                modifier = Modifier.padding(bottom = 16.dp),
            )

            // ===== 核心概览卡片 (2x2 Grid) =====
            Text(
                text = L.statsOverview,
                style = DshType.t14SB,
                color = Dsh.labelPrimary,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StatsMetricCard(
                    title = "${s.turns} / ${s.steps}",
                    label = L.statsTurnsStepsLabel,
                    sub = if (s.llmMs > 0 || s.toolMs > 0) {
                        L.statsTotalTimeSeconds.format((s.llmMs + s.toolMs) / 1000.0)
                    } else L.statsInteractionTotal,
                    modifier = Modifier.weight(1f),
                )
                StatsMetricCard(
                    title = if (cacheHitPercent > 0) "$cacheHitPercent%" else "--",
                    label = L.statsCacheHitLabel,
                    sub = if (s.cacheReadTokens > 0) L.statsCacheHitSub.format(compactTokens(s.cacheReadTokens)) else L.statsCacheMiss,
                    accent = cacheHitPercent > 0,
                    modifier = Modifier.weight(1f),
                )
            }

            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StatsMetricCard(
                    title = if (speedToks > 0) String.format(java.util.Locale.US, "%.1f", speedToks) else "--",
                    label = L.statsDecodeSpeedLabel,
                    sub = if (s.outputTokens > 0) L.statsOutputSub.format(compactTokens(s.outputTokens)) else L.statsDecodeSpeedHint,
                    modifier = Modifier.weight(1f),
                )
                StatsMetricCard(
                    title = compactTokens(totalTokens),
                    label = L.statsTotalTokensLabel,
                    sub = L.statsInputPlusOutput,
                    modifier = Modifier.weight(1f),
                )
            }

            Spacer(Modifier.height(16.dp))

            // ===== Token 消耗明细 =====
            Text(
                text = L.statsTokensBreakdown,
                style = DshType.t14SB,
                color = Dsh.labelPrimary,
                modifier = Modifier.padding(bottom = 8.dp),
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(DshRadius.md))
                    .background(Dsh.bgBase)
                    .border(1.dp, Dsh.borderSubtle, RoundedCornerShape(DshRadius.md))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                StatsDetailRow(
                    label = L.statsUncachedInput,
                    value = "${s.uncachedInputTokens} tok",
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = Dsh.borderSubtle, thickness = 0.5.dp)
                StatsDetailRow(
                    label = L.statsCachedInput,
                    value = "${s.cacheReadTokens} tok",
                    tag = if (cacheHitPercent > 0) "$cacheHitPercent%" else null,
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = Dsh.borderSubtle, thickness = 0.5.dp)
                StatsDetailRow(
                    label = L.statsOutputTokens,
                    value = "${s.outputTokens} tok",
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = Dsh.borderSubtle, thickness = 0.5.dp)
                StatsDetailRow(
                    label = L.statsTotalTokens,
                    value = "$totalTokens tok",
                    highlight = true,
                )
            }

            // ===== 上下文窗口气压 =====
            if (s.contextWindow > 0) {
                Spacer(Modifier.height(16.dp))
                val used = s.contextPressureTokens
                val window = s.contextWindow
                val windowPercent = ((used * 100) / window).toInt().coerceIn(0, 100)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = L.statsContextWindow,
                        style = DshType.t14SB,
                        color = Dsh.labelPrimary,
                    )
                    Text(
                        text = "${compactTokens(used)} / ${compactTokens(window)} ($windowPercent%)",
                        style = DshType.captionRelaxed,
                        color = if (windowPercent > 80) Dsh.warn else Dsh.labelSecondary,
                    )
                }

                Spacer(Modifier.height(8.dp))

                // 进度条
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(Dsh.bgTrack)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth((windowPercent / 100f).coerceIn(0f, 1f))
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(3.dp))
                            .background(if (windowPercent > 80) Dsh.warn else Dsh.brand500)
                    )
                }

                val breakdownTotal = s.systemTokens + s.toolsTokens + s.messageTokens
                if (breakdownTotal > 0) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = L.statsContextBreakdownLine.format(
                            compactTokens(s.systemTokens),
                            compactTokens(s.toolsTokens),
                            compactTokens(s.messageTokens),
                        ),
                        style = DshType.microRelaxed,
                        color = Dsh.labelTertiary,
                    )
                }
            }
        }
    }
}

@Composable
private fun StatsMetricCard(
    title: String,
    label: String,
    sub: String,
    accent: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(DshRadius.md))
            .background(Dsh.bgBase)
            .border(1.dp, Dsh.borderSubtle, RoundedCornerShape(DshRadius.md))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(
            text = label,
            style = DshType.microRelaxed,
            color = Dsh.labelSecondary,
            maxLines = 1,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = title,
            style = DshType.title,
            color = if (accent) Dsh.brand500 else Dsh.labelPrimary,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = sub,
            style = DshType.microRelaxed,
            color = Dsh.labelTertiary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun StatsDetailRow(
    label: String,
    value: String,
    tag: String? = null,
    highlight: Boolean = false,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = if (highlight) DshType.t13SB else DshType.bodyDense,
                color = if (highlight) Dsh.labelPrimary else Dsh.labelSecondary,
            )
            if (tag != null) {
                Spacer(Modifier.width(6.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(DshRadius.full))
                        .background(Dsh.brandTint)
                        .padding(horizontal = 6.dp, vertical = 1.dp)
                ) {
                    Text(
                        text = tag,
                        style = DshType.microRelaxed,
                        color = Dsh.brand500,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
        Text(
            text = value,
            style = if (highlight) DshType.t14SB else DshType.bodyDense,
            color = if (highlight) Dsh.brand500 else Dsh.labelPrimary,
        )
    }
}
