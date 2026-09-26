package dev.deeplinks.native

import dev.deeplinks.core.DshType

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Feedback
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.deeplinks.core.dshRipple
import dev.deeplinks.core.Dsh
import dev.deeplinks.core.L
import dev.deeplinks.native.ui.DshTag
import dev.deeplinks.native.util.answerMetaSummary
import dev.deeplinks.native.util.buildAnswerMeta
import dev.deeplinks.native.util.formatClockTime
import dev.deeplinks.native.util.loadOlderKind
import dev.deeplinks.native.util.LoadOlderKind
import dev.deeplinks.native.util.contextInjectionLabels
import dev.deeplinks.native.util.decodeHtmlEntities
import dev.deeplinks.native.util.goalRoundObjective
import dev.deeplinks.native.util.goalRoundProgress
import dev.deeplinks.native.util.isContextInjectionText
import dev.deeplinks.native.util.isGoalRoundText
import org.json.JSONObject

// ---------- 消息渲染 ----------

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun MessageItem(
    msg: MobileMessage,
    running: Boolean = false,
    onAnswerApproval: ((String, String, (Boolean) -> Unit) -> Unit)? = null,
    onAnswerQuestion: ((String, org.json.JSONObject, (Boolean) -> Unit) -> Unit)? = null,
    onCopy: () -> Unit = {},
    onQuote: () -> Unit = {},
    onFork: () -> Unit = {},
    onRegenerate: (() -> Unit)? = null,
    onFeedback: (() -> Unit)? = null,
    feedbackRating: String? = null,
    onRate: ((String) -> Unit)? = null,
    onRetract: (() -> Unit)? = null,
    onFetchProducedFile: ((String) -> Pair<String, ByteArray>)? = null,
    onOpenChanges: ((seq: Long, fileIndex: Int?) -> Unit)? = null,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var selectOpen by remember { mutableStateOf(false) }
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val dshHaptic = rememberDshHaptic()
    val context = androidx.compose.ui.platform.LocalContext.current
    // 已完成的文本消息：长按出菜单。选择文字走独立对话框，避免和列表滚动抢手势。
    val canSelectText = (msg.role == "user" || msg.role == "assistant") &&
        msg.running != true && !running && msg.text.isNotBlank()
    val longPressModifier = if (canSelectText) {
        Modifier.combinedClickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = {},
            onLongClick = {
            haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
            menuOpen = true
        }
        )
    } else {
        Modifier
    }

    Box {
        when {
            msg.role == "context_injection" || isContextInjectionText(msg.text) -> {
                if (isGoalRoundText(msg.text)) GoalRoundRow(msg.text) else ContextInjectionRow(msg.text)
            }
            msg.role == "user" -> {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.End,
                ) {
                    UserBubble(msg.text, longPressModifier)
                    Spacer(Modifier.height(4.dp))
                    MessageActionRow(
                        onCopy = onCopy,
                        onFork = null,
                        onRegenerate = null,
                    )
                }
            }
            msg.role == "reasoning" -> ReasoningRow(msg.text, running || msg.running == true, msg.durationMs)
            msg.role == "approval" -> ApprovalCard(
                msg = msg,
                onAnswer = { approvalId, outcome, onDone ->
                    onAnswerApproval?.invoke(approvalId, outcome, onDone) ?: onDone(false)
                }
            )
            msg.role == "question" -> QuestionCard(
                msg = msg,
                onAnswer = { rpcId, answer, onDone ->
                    onAnswerQuestion?.invoke(rpcId, answer, onDone) ?: onDone(false)
                }
            )
            msg.role == "tool_call" -> CommandCard(msg.toolName ?: L.toolCallRole, msg.toolArgs, running)
            msg.role == "tool_result" -> CommandCard(
                title = L.executionResultRole + (msg.durationMs?.let { " · ${formatTraceDuration(it)}" } ?: ""),
                body = msg.text.take(4000),
                running = running,
                runningLabel = L.executing
            )
            msg.role == "compaction" -> CompactionRow(msg.text, msg.running ?: false)
            msg.role == "todo" -> TodoPanel(msg.todos)
            msg.role == "goal" -> GoalPanel(msg.text, msg.goalSummary)
            msg.role == ROLE_WORKSPACE_CHANGES && msg.changes != null -> WorkspaceChangesCard(
                summary = msg.changes,
                onOpen = { fileIndex -> onOpenChanges?.invoke(msg.changes.seq, fileIndex) },
            )
            msg.role == "produced_files" -> ProducedFilesRow(
                files = msg.files.ifEmpty { msg.text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList() },
                onFetchFile = onFetchProducedFile,
            )
            else -> {
                Column(modifier = Modifier.fillMaxWidth()) {
                    if (isRawFallback(msg)) {
                        // 降级中心：无文本内容的未知消息类型（多为 dsh 新增的结构化类型）
                        // 渲染为可折叠原始 JSON，避免空白/崩溃，便于排查与按需补洞。
                        RawMessageCard(msg)
                    } else {
                        AssistantMarkdown(
                            text = msg.text,
                            longPress = longPressModifier,
                            streaming = msg.running == true,
                        )
                    }
                    // 助手消息底部：复制 / 分叉 / 重新生成（流式结束后淡入，对齐 Web）
                    if (msg.role == "assistant") {
                        AnimatedVisibility(
                            visible = msg.running != true,
                            enter = fadeIn(animationSpec = tween(motionDuration(400))),
                            exit = fadeOut(animationSpec = tween(motionDuration(150))),
                        ) {
                            val meta = remember(msg.durationMs) {
                                buildAnswerMeta(msg.durationMs)
                            }
                            Row(
                                modifier = Modifier.padding(top = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                    MessageActionIcon(
                                        icon = CopyOutline16,
                                        contentDescription = L.copy,
                                        onClick = onCopy,
                                    )
                                    // 赞 / 踩（对齐 Web：复制与分叉之间；已标记显示实心，点按取消）
                                    if (onRate != null) {
                                        val positive = feedbackRating == "positive"
                                        MessageActionIcon(
                                            icon = if (positive) LikeFill16 else LikeOutline16,
                                            contentDescription = if (positive) L.feedbackRetract else L.feedbackLike,
                                            onClick = { if (positive) onRetract?.invoke() else onRate("positive") },
                                        )
                                        val negative = feedbackRating == "negative"
                                        MessageActionIcon(
                                            icon = if (negative) DislikeFill16 else DislikeOutline16,
                                            contentDescription = if (negative) L.feedbackRetract else L.feedbackDislike,
                                            onClick = { if (negative) onRetract?.invoke() else onRate("negative") },
                                        )
                                    }
                                    // 行尾轻量 meta：钟点时间 + 这条回复自己的耗时（对齐 Web 助手行末尾）；
                                    // 会话累计用量只在输入框下方，不在每条回复里重复。
                                    val clock = formatClockTime(msg.time)
                                    val tail = listOfNotNull(
                                        clock.takeIf { it.isNotBlank() },
                                        meta?.let { answerMetaSummary(it) }?.takeIf { it.isNotBlank() },
                                    ).joinToString(" · ")
                                    if (tail.isNotBlank()) {
                                        Text(
                                            tail,
                                            color = Dsh.labelTertiary,
                                            style = DshType.microRelaxed,
                                            maxLines = 1,
                                            modifier = Modifier.padding(start = 6.dp),
                                        )
                                    }
                                }
                            }
                    }
                }
            }
        }

        DshMenu(
            expanded = menuOpen,
            onDismiss = { menuOpen = false },
            items = buildList {
                add(DshMenuItem(CopyOutline16, L.copy) {
                    menuOpen = false
                    dshHaptic(DshHaptic.Confirm)
                    onCopy()
                })
                add(DshMenuItem(Icons.Default.TextFields, L.selectText) {
                    menuOpen = false
                    selectOpen = true
                })
                add(DshMenuItem(Icons.Default.Share, L.shareMessage) {
                    menuOpen = false
                    ShareIntents.shareText(context, msg.text, L.shareMessage)
                })
                add(DshMenuItem(Icons.Default.FormatQuote, L.quote) {
                    menuOpen = false
                    onQuote()
                })
                add(DshMenuItem(BranchOutline16, L.forkSession) {
                    menuOpen = false
                    onFork()
                })
                if (onRegenerate != null) {
                    add(DshMenuItem(RefreshOutline16, L.regenerate) {
                        menuOpen = false
                        onRegenerate()
                    })
                }
                if (onFeedback != null) {
                    add(DshMenuItem(Icons.Default.Feedback, L.messageFeedback) {
                        menuOpen = false
                        onFeedback()
                    })
                }
            }
        )
        if (selectOpen) {
            SelectTextDialog(text = msg.text, onDismiss = { selectOpen = false })
        }
    }
}

/** 消息底栏：复制 / 分叉 / 重新生成（按需显示） */
@Composable
private fun MessageActionRow(
    onCopy: () -> Unit,
    onFork: (() -> Unit)? = null,
    onRegenerate: (() -> Unit)? = null,
    onFeedback: (() -> Unit)? = null,
) {
    val haptic = rememberDshHaptic()
    Row(
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MessageActionIcon(
            icon = CopyOutline16,
            contentDescription = L.copy,
            onClick = {
                haptic(DshHaptic.Confirm)
                onCopy()
            },
        )
        if (onFork != null) {
            MessageActionIcon(
                icon = BranchOutline16,
                contentDescription = L.forkSession,
                onClick = onFork,
            )
        }
        if (onRegenerate != null) {
            MessageActionIcon(
                icon = RefreshOutline16,
                contentDescription = L.regenerate,
                onClick = onRegenerate,
            )
        }
        if (onFeedback != null) {
            MessageActionIcon(
                icon = Icons.Default.Feedback,
                contentDescription = L.messageFeedback,
                onClick = onFeedback,
            )
        }
    }
}

@Composable
private fun MessageActionIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        modifier = Modifier
            .size(48.dp)
            .semantics {
                role = Role.Button
                this.contentDescription = contentDescription
            }
            .clickable(interactionSource = interaction, indication = dshRipple(), onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(DshRadius.sm))
                .background(if (pressed) Dsh.pressed else Color.Transparent),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = Dsh.labelSecondary,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/** 降级判定：无文本内容的消息（多为 dsh 新增的结构化类型）渲染为可折叠原始 JSON。 */
fun isRawFallback(msg: MobileMessage): Boolean =
    msg.role != "produced_files" && msg.role != ROLE_WORKSPACE_CHANGES && msg.text.isBlank()

// 降级渲染：未知/未支持的消息类型 → 可折叠原始 JSON（不崩、不空白、可排查）
@Composable
private fun RawMessageCard(msg: MobileMessage) {
    var expanded by remember { mutableStateOf(false) }
    val detail = remember(msg) {
        buildString {
            append("role: ").append(msg.role).append('\n')
            append("type: ").append(msg.type).append('\n')
            append("id: ").append(msg.id).append('\n')
            if (msg.toolName != null) append("toolName: ").append(msg.toolName).append('\n')
            append("text: ").append(msg.text.take(2000))
        }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(DshRadius.md))
            .background(Dsh.bgCard)
            .border(1.dp, Dsh.borderSubtle, RoundedCornerShape(DshRadius.md))
            .padding(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .semantics {
                    role = Role.Button
                    contentDescription = L.unsupportedMessageType.format(msg.role)
                }
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = dshRipple()) { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                if (expanded) Icons.Default.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = Dsh.labelTertiary,
                modifier = Modifier.size(14.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                L.unsupportedMessageType.format(msg.role),
                color = Dsh.labelSecondary,
                style = DshType.bodyDense,
                lineHeight = 20.sp
            )
        }
        if (expanded) {
            Spacer(Modifier.height(6.dp))
            Text(
                detail,
                fontFamily = FontFamily.Monospace,
                style = DshType.caption,
                color = Dsh.labelTertiary,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

// 上下文压缩行（DSH CompactionRow：折叠标题 + 可展开摘要）
@Composable
private fun CompactionRow(summary: String, running: Boolean) {
    var expanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(DshRadius.sm))
            .semantics {
                role = Role.Button
                stateDescription = if (expanded) L.collapse else L.expand
            }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = dshRipple()) { expanded = !expanded }
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 16px leading 图标（上下文图标）
        Box(
            modifier = Modifier
                .size(16.dp),
            contentAlignment = Alignment.Center
        ) {
            if (running) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(Dsh.brand400)
                )
            } else {
                Icon(
                    Icons.Default.Compress,
                    contentDescription = null,
                    tint = Dsh.labelSecondary,
                    modifier = Modifier.size(14.dp)
                )
            }
        }
        Spacer(Modifier.width(6.dp))
        Text(
            if (running) L.compressing else L.contextCompressed,
            color = Dsh.labelPrimary.copy(alpha = 0.85f),
            style = DshType.t14x24,
            lineHeight = 24.sp
        )
        if (!running) {
            // 分隔点：padding 在 size 外面（原来写在固定 2dp 盒内不生效，只剩一粒贴字的小点）
            Box(
                modifier = Modifier
                    .padding(horizontal = 6.dp)
                    .size(3.dp)
                    .clip(CircleShape)
                    .background(Dsh.labelTertiary)
            )
            Text(
                summary.lineSequence().firstOrNull().orEmpty(),
                color = Dsh.labelTertiary,
                style = DshType.t14x24,
                lineHeight = 24.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }
        if (!running) {
            Icon(
                if (expanded) ChevronUpOutline14 else ChevronDownOutline14,
                contentDescription = null,
                tint = Dsh.labelTertiary,
                modifier = Modifier.size(16.dp)
            )
        }
    }
    AnimatedVisibility(
        visible = expanded && summary.isNotEmpty(),
        enter = expandVertically(animationSpec = tween(motionDuration(200), easing = FastOutSlowInEasing)),
        exit = shrinkVertically(animationSpec = tween(motionDuration(150), easing = FastOutSlowInEasing))
    ) {
        Text(
            summary,
            color = Dsh.labelTertiary,
            style = DshType.t14x24,
            lineHeight = 24.sp,
            modifier = Modifier.padding(start = 22.dp, top = 4.dp, bottom = 4.dp)
        )
    }
}

// goal 模式每轮注入的续跑提示：折叠为一行「目标轮次」，展开看 Objective 与轮次，不铺开整段系统指令
@Composable
private fun GoalRoundRow(text: String) {
    var expanded by remember { mutableStateOf(false) }
    val objective = remember(text) { goalRoundObjective(text) }
    val progress = remember(text) { goalRoundProgress(text) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(DshRadius.md))
            .semantics {
                role = Role.Button
                stateDescription = if (expanded) L.collapse else L.expand
            }
            .clickable { expanded = !expanded }
            .padding(horizontal = 4.dp, vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                GoalOutline16,
                contentDescription = null,
                tint = Dsh.labelTertiary,
                modifier = Modifier.size(13.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                L.goalInjection,
                color = Dsh.labelTertiary,
                style = DshType.t12x18M,
                fontWeight = FontWeight(500),
            )
            progress?.let {
                Text(
                    " · " + L.goalRoundLabel.format(it),
                    color = Dsh.labelTertiary,
                    style = DshType.captionRelaxed,
                    maxLines = 1,
                )
            }
            Spacer(Modifier.weight(1f))
            Icon(
                if (expanded) ChevronUpOutline14 else ChevronDownOutline14,
                contentDescription = if (expanded) L.collapseInjectionContent else L.expandInjectionContent,
                tint = Dsh.labelTertiary,
                modifier = Modifier.size(14.dp)
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(animationSpec = tween(motionDuration(200), easing = FastOutSlowInEasing)) + fadeIn(animationSpec = tween(motionDuration(150))),
            exit = shrinkVertically(animationSpec = tween(motionDuration(180), easing = FastOutSlowInEasing)) + fadeOut(animationSpec = tween(motionDuration(150)))
        ) {
            Text(
                objective ?: text.trim(),
                color = Dsh.labelTertiary,
                style = DshType.captionRelaxed,
                maxLines = 8,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(top = 6.dp)
                    .clip(RoundedCornerShape(DshRadius.md))
                    .background(Dsh.bgSubtle.copy(alpha = 0.6f))
                    .padding(horizontal = 10.dp, vertical = 8.dp)
            )
        }
    }
}

// 上下文注入行（对标 Web：上下文注入 · skill-catalog，默认折叠）
@Composable
private fun ContextInjectionRow(text: String) {
    var expanded by remember { mutableStateOf(false) }
    val sources = remember(text) { contextInjectionLabels(text) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(DshRadius.md))
            .semantics {
                role = Role.Button
                stateDescription = if (expanded) L.collapse else L.expand
            }
            .clickable { expanded = !expanded }
            .padding(horizontal = 4.dp, vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.Description,
                contentDescription = null,
                tint = Dsh.labelTertiary,
                modifier = Modifier.size(13.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                L.contextInjection,
                color = Dsh.labelTertiary,
                style = DshType.t12x18M,
                fontWeight = FontWeight(500),
                lineHeight = 18.sp
            )
            Text(
                " · ",
                color = Dsh.labelTertiary,
                style = DshType.captionRelaxed,
                lineHeight = 18.sp
            )
            Text(
                sources.joinToString(", "),
                color = Dsh.labelTertiary,
                style = DshType.captionRelaxed,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Icon(
                if (expanded) ChevronUpOutline14 else ChevronDownOutline14,
                contentDescription = if (expanded) L.collapseInjectionContent else L.expandInjectionContent,
                tint = Dsh.labelTertiary,
                modifier = Modifier.size(14.dp)
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(animationSpec = tween(motionDuration(200), easing = FastOutSlowInEasing)) + fadeIn(animationSpec = tween(motionDuration(150))),
            exit = shrinkVertically(animationSpec = tween(motionDuration(180), easing = FastOutSlowInEasing)) + fadeOut(animationSpec = tween(motionDuration(150)))
        ) {
            Text(
                text.replace("<system-reminder>", "", ignoreCase = true)
                    .replace("</system-reminder>", "", ignoreCase = true)
                    .replace("&lt;system-reminder&gt;", "", ignoreCase = true)
                    .replace("&lt;/system-reminder&gt;", "", ignoreCase = true)
                    .trim(),
                color = Dsh.labelTertiary,
                style = DshType.captionRelaxed,
                maxLines = 16,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(top = 6.dp)
                    .clip(RoundedCornerShape(DshRadius.md))
                    .background(Dsh.bgSubtle.copy(alpha = 0.6f))
                    .padding(horizontal = 10.dp, vertical = 8.dp)
            )
        }
    }
}

// 任务清单（DSH TodoPanel：标题 + 进度 + 可展开列表）
@Composable
private fun TodoPanel(todos: List<MobileTodoItem>) {
    var expanded by remember { mutableStateOf(true) }
    val done = todos.count { it.status == "completed" }
    val active = todos.count { it.status == "active" || it.status == "progress" || it.status == "in_progress" }
    val pending = todos.count { it.status == "pending" }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(DshRadius.lg))
            .background(Dsh.bgInput)
            .border(1.dp, Dsh.borderSubtle, RoundedCornerShape(DshRadius.lg))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .semantics {
                    role = Role.Button
                    stateDescription = if (expanded) L.collapse else L.expand
                }
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = dshRipple()) { expanded = !expanded }
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                ChecklistOutline14,
                contentDescription = null,
                tint = Dsh.labelTertiary,
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(10.dp))
            Text(
                L.tasks,
                color = Dsh.labelPrimary,
                style = DshType.t13x24M,
                fontWeight = FontWeight(500),
                lineHeight = 24.sp
            )
            Spacer(Modifier.width(10.dp))
            Text(
                L.todoCompleted.format(done, todos.size),
                color = Dsh.labelTertiary,
                style = DshType.bodyDense,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Icon(
                if (expanded) ChevronUpOutline14 else ChevronDownOutline14,
                contentDescription = null,
                tint = Dsh.labelTertiary,
                modifier = Modifier.size(16.dp)
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(animationSpec = tween(motionDuration(220), easing = FastOutSlowInEasing)),
            exit = shrinkVertically(animationSpec = tween(motionDuration(180), easing = FastOutSlowInEasing))
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                todos.forEach { todo ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TodoGlyph(todo.status)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            todo.content,
                            color = Dsh.labelSecondary,
                            style = DshType.bodyDense,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TodoGlyph(status: String) {
    Box(
        modifier = Modifier.size(16.dp),
        contentAlignment = Alignment.Center
    ) {
        when (status) {
            "completed", "done" -> Icon(
                CheckOutline16,
                contentDescription = null,
                tint = Dsh.success,
                modifier = Modifier.size(14.dp)
            )
            "active", "progress", "in_progress", "running" -> {
                val angle = rememberMotionSpin(750, label = "todo-spin")
                Icon(
                    RefreshOutline16,
                    contentDescription = null,
                    tint = Dsh.brand400,
                    modifier = Modifier.size(14.dp).rotate(angle ?: 0f)
                )
            }
            else -> Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .border(1.5.dp, Dsh.labelTertiary, CircleShape)
            )
        }
    }
}

// ─── Goal 面板（紧凑 inline 卡片，DSH goal/write 事件的展示层） ─────────────

/**
 * Goal 面板：紧凑行式卡片，展示 agent 正在执行的目标文本。
 * 生产打磨参考：hermes-mobile #943 TaskProgressChip 的同源设计（小而持续可见）。
 */
@Composable
private fun GoalPanel(text: String, goalSummary: String? = null) {
    val displayText = goalSummary?.ifBlank { text } ?: text
    if (displayText.isBlank()) return
    val expanded = remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(DshRadius.md))
            .background(Dsh.brandTint.copy(alpha = 0.06f))
            .border(1.dp, Dsh.brand400.copy(alpha = 0.12f), RoundedCornerShape(DshRadius.md))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                GoalOutline16,
                contentDescription = null,
                tint = Dsh.brand400,
                modifier = Modifier.size(15.dp),
            )
            Text(
                text = L.goalRole,
                color = Dsh.brand400,
                style = DshType.t11M,
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = if (expanded.value || displayText.length <= 60) displayText
                else displayText.take(57) + "…",
                color = Dsh.labelPrimary,
                style = DshType.t13,
                maxLines = if (expanded.value) Int.MAX_VALUE else 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            // 展开/折叠按钮
            Text(
                text = if (expanded.value) "收起" else "展开",
                color = Dsh.brand400,
                style = DshType.t11M,
                modifier = Modifier.clickable { expanded.value = !expanded.value },
            )
        }
    }
}

// 加载更早（DSH chat.loadOlder：居中圆角按钮）
@Composable
internal fun LoadOlderRow(
    loading: Boolean,
    failed: Boolean = false,
    failedMessage: String? = null,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val kind = loadOlderKind(loading, failed)
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Text(
            text = when (kind) {
                LoadOlderKind.Loading -> L.loadHistory
                LoadOlderKind.Failed -> failedMessage?.takeIf { it.isNotBlank() } ?: L.loadOlderRetry
                LoadOlderKind.Idle -> L.loadOlder
            },
            color = if (kind == LoadOlderKind.Failed) Dsh.error else Dsh.labelSecondary,
            style = DshType.captionRelaxed,
            modifier = Modifier
                .heightIn(min = 48.dp)
                .clip(RoundedCornerShape(DshRadius.lg))
                .background(if (pressed) Dsh.pressed else Dsh.bgInput)
                .clickable(enabled = !loading, interactionSource = interaction, indication = dshRipple(), onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 4.dp)
        )
    }
}

// 已停止标记（按 turn/end reason 显示具体原因）
private fun stoppedReasonLabel(reason: String): String = when (reason.lowercase()) {
    "interrupted" -> L.interrupted
    "stopped" -> L.stopped
    "error" -> L.errorStopped
    "maxtokens", "max_tokens" -> L.maxTokensReached
    "aborted" -> L.cancelled
    "timeout" -> L.timeoutStopped
    else -> if (reason.isBlank()) L.stopped else reason
}

@Composable
internal fun StoppedBadge(reason: String) {
    val label = stoppedReasonLabel(reason)
    if (label.isBlank() || label.equals("null", ignoreCase = true)) return
    Row(modifier = Modifier.fillMaxWidth()) {
        DshTag(
            text = label,
            color = Dsh.pressed,
            contentColor = Dsh.labelTertiary,
            contentDescription = label,
        )
    }
}

// 思考行：四角星 + 扫光标题，正文默认收起，仅用户点击后展开

@Composable
private fun ReasoningRow(text: String, running: Boolean = false, durationMs: Long? = null) {
    var expanded by remember { mutableStateOf(false) }
    var startedAt by remember { mutableLongStateOf(0L) }
    var settledSec by remember { mutableStateOf<Long?>(null) }
    var lastRunning by remember { mutableStateOf(false) }
    LaunchedEffect(running, durationMs) {
        // 复用的行会跨 turn 存活：running 上升沿（新 turn 开始）必须重置计时，
        // 否则「思考中」从首个 turn 起累计。
        if (running && !lastRunning) {
            startedAt = System.currentTimeMillis()
            settledSec = null
        }
        lastRunning = running
        if (durationMs != null && durationMs > 0) {
            settledSec = (durationMs / 1000L).coerceAtLeast(1L)
        } else if (!running && settledSec == null && startedAt > 0L) {
            settledSec = ((System.currentTimeMillis() - startedAt) / 1000L).coerceAtLeast(1L)
        }
    }
    ThinkingTrace(
        working = running,
        activeLabel = L.thinkingActive,
        doneLabel = thoughtDoneLabel(durationMs, settledSec),
        expanded = expanded,
        onToggle = { expanded = !expanded },
    ) {
        Text(
            text,
            color = Dsh.labelTertiary,
            style = DshType.bodyDense,
            fontStyle = FontStyle.Italic,
        )
    }
}

// 用户消息：右对齐气泡（max-width min(525px,82%), 宽度随内容收缩，勿 fillMaxWidth）
@Composable
private fun UserBubble(text: String, longPress: Modifier = Modifier) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val maxBubble = minOf(maxWidth * 0.82f, 525.dp)
        Box(
            modifier = longPress
                .align(Alignment.CenterEnd)
                .widthIn(max = maxBubble)
                .clip(RoundedCornerShape(
                    topStart = DshRadius.xl,
                    topEnd = DshRadius.xl,
                    bottomStart = DshRadius.xl,
                    bottomEnd = 4.dp
                ))
                .background(Dsh.bubbleBg)
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            Text(
                text.trimEnd(),
                color = Dsh.labelPrimary,
                style = DshType.t15x23,
                lineHeight = 23.sp,
                letterSpacing = (-0.1).sp
            )
        }
    }
}

@Composable
private fun AssistantMarkdown(text: String, longPress: Modifier = Modifier, streaming: Boolean = false) {
    Column(
        modifier = longPress.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        MarkdownContent(decodeHtmlEntities(text), streaming = streaming)
    }
}

// 命令行（tool call / result）：轻量可展开行，去掉厚底卡片
@Composable
private fun CommandCard(title: String, body: String?, running: Boolean = false, runningLabel: String = L.executing) {
    var expanded by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressTint = Dsh.pressed
    val rail = Dsh.borderStrong
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .heightIn(min = 48.dp)
                .clip(RoundedCornerShape(DshRadius.sm))
                .clickable(interactionSource = interaction, indication = dshRipple()) { expanded = !expanded }
                .then(if (pressed) Modifier.drawBehind { drawRect(pressTint) } else Modifier)
                .padding(horizontal = 6.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (running) {
                CircularProgressIndicator(
                    modifier = Modifier.size(13.dp),
                    color = Dsh.brand500,
                    strokeWidth = 1.5.dp,
                )
                Spacer(Modifier.width(10.dp))
            } else {
                Box(
                    modifier = Modifier
                        .size(4.dp)
                        .clip(CircleShape)
                        .background(Dsh.labelTertiary),
                )
                Spacer(Modifier.width(8.dp))
            }
            Text(
                title,
                color = if (running) Dsh.labelSecondary else Dsh.labelTertiary,
                style = DshType.t13M,
                fontWeight = FontWeight(500),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (running) {
                Spacer(Modifier.width(8.dp))
                ShimmerLabel(text = runningLabel.trimEnd('…', '.', '。'), working = true)
                Spacer(Modifier.width(6.dp))
            }
            Icon(
                if (expanded) ChevronUpOutline14 else ChevronDownOutline14,
                contentDescription = if (expanded) L.collapse else L.expand,
                tint = Dsh.labelTertiary,
                modifier = Modifier.size(14.dp),
            )
        }
        AnimatedVisibility(
            visible = expanded && !body.isNullOrBlank(),
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
                Text(
                    body.orEmpty(),
                    color = Dsh.labelPrimary,
                    fontFamily = FontFamily.Monospace,
                    style = DshType.captionRelaxed,
                    maxLines = 16,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(DshRadius.sm))
                        .background(Dsh.bgCode)
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                )
            }
        }
    }
}
