package dev.deeplinks.native

import dev.deeplinks.core.DshType

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.deeplinks.core.Dsh
import dev.deeplinks.core.L
import java.util.Locale

// ---------- 轨迹视图（对齐 DSH Web 轨迹：工具栏 + 时间线 + 表格式行） ----------

internal fun formatTraceDuration(ms: Long): String = when {
    ms >= 1000 -> String.format(Locale.US, "+%.1fs", ms / 1000.0)
    else -> "+" + ms + "ms"
}

private fun traceIsError(msg: MobileMessage): Boolean {
    val outcome = msg.outcome
    if (outcome != null && outcome != "ok" && outcome != "completed" && outcome != "accepted") return true
    return msg.text.contains("\"isError\":true")
}

private data class TraceRoleVisual(
    val label: String,
    val color: Color,
    val icon: ImageVector,
)

@Composable
private fun traceRoleVisual(role: String): TraceRoleVisual = when (role) {
    "user" -> TraceRoleVisual(L.traceKindUser, Dsh.brand400, GoalOutline16)
    "reasoning" -> TraceRoleVisual(L.traceKindReasoning, Dsh.traceReasoning, ThinkOutline16)
    "tool_call" -> TraceRoleVisual(L.traceKindTool, Dsh.labelSecondary, CodeOutline16)
    "tool_result" -> TraceRoleVisual(L.traceKindResult, Dsh.labelSecondary, CheckOutline16)
    "approval" -> TraceRoleVisual(L.approvalRole, Dsh.warn, WarningOutline16)
    "todo" -> TraceRoleVisual(L.taskRole, Dsh.labelSecondary, ChecklistOutline14)
    "compaction" -> TraceRoleVisual(L.traceKindCompact, Dsh.labelTertiary, ArchiveOutline20)
    "produced_files" -> TraceRoleVisual(L.traceKindOutput, Dsh.brand400, Icons.Default.Description)
    ROLE_WORKSPACE_CHANGES -> TraceRoleVisual(ChangesL.changes, Dsh.brand400, EditOutline16)
    else -> TraceRoleVisual(L.traceKindAssistant, Dsh.brand400, Sparkle16)
}

/** 一行 = 一次工具调用（+ 紧随其后的结果）或一条独立消息。 */
private data class TraceRow(
    val key: String,
    val primary: MobileMessage,
    val result: MobileMessage?,
)

private fun buildTraceRows(steps: List<MobileMessage>): List<TraceRow> {
    val out = mutableListOf<TraceRow>()
    var i = 0
    while (i < steps.size) {
        val m = steps[i]
        if (m.role == "tool_call") {
            val next = steps.getOrNull(i + 1)
            if (next != null && next.role == "tool_result") {
                out.add(TraceRow("row-" + m.id, m, next))
                i += 2
                continue
            }
        }
        out.add(TraceRow("row-" + m.id, m, null))
        i++
    }
    return out
}

private data class TraceTurn(
    val index: Int,
    val header: MobileMessage?,
    val steps: List<MobileMessage>,
)

private fun groupTraceTurns(messages: List<MobileMessage>): List<TraceTurn> {
    if (messages.isEmpty()) return emptyList()
    val turns = mutableListOf<TraceTurn>()
    var current = mutableListOf<MobileMessage>()
    fun flush() {
        if (current.isEmpty()) return
        val header = current.firstOrNull { it.role == "user" }
        turns.add(TraceTurn(turns.size + 1, header, current.toList()))
        current = mutableListOf()
    }
    for (m in messages) {
        if (m.role == "user" && current.isNotEmpty()) flush()
        current.add(m)
    }
    flush()
    return turns
}

private fun traceDurations(messages: List<MobileMessage>): Map<String, Long?> {
    val out = HashMap<String, Long?>(messages.size)
    messages.forEachIndexed { i, m ->
        val next = messages.getOrNull(i + 1)?.time ?: 0L
        out[m.id] = if (m.time > 0 && next > m.time) next - m.time else null
    }
    return out
}

@Composable
internal fun TrajectoryView(
    messages: List<MobileMessage>,
    running: Boolean,
    elapsedSec: Long,
    modifier: Modifier = Modifier,
) {
    var actualDuration by remember { mutableStateOf(true) }
    var searchOpen by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    // 全局开关 + 单项覆盖：会话流式增长时 turns/rows 会变化，
    // 用布尔开关承载「全部收起/展开」，避免集合比较随列表变化而失效。
    var allTurnsCollapsed by remember { mutableStateOf(false) }
    var turnOverrides by remember { mutableStateOf(emptyMap<String, Boolean>()) }
    var allCallsExpanded by remember { mutableStateOf(false) }
    var rowOverrides by remember { mutableStateOf(emptyMap<String, Boolean>()) }
    var focusKey by remember { mutableStateOf<String?>(null) }

    val visible = remember(messages, query) {
        if (query.isBlank()) messages
        else messages.filter { m ->
            m.text.contains(query, ignoreCase = true) ||
                (m.toolName?.contains(query, ignoreCase = true) == true) ||
                (m.toolArgs?.contains(query, ignoreCase = true) == true)
        }
    }
    val turns = remember(visible) { groupTraceTurns(visible) }
    val turnRows = remember(turns) { turns.map { it to buildTraceRows(it.steps) } }
    val durations = remember(messages) { traceDurations(messages) }
    // 收起/展开按回合首条消息 id 存，不用位置序号：搜索会重排回合编号，
    // 位置序号会让「第 2 回合」的收起状态落到另一个回合上。
    val isTurnCollapsed: (String) -> Boolean = { turnOverrides[it] ?: allTurnsCollapsed }
    val isRowExpanded: (String) -> Boolean = { rowOverrides[it] ?: allCallsExpanded }

    LazyColumn(
        modifier = modifier
            .fillMaxWidth()
    ) {
        item(key = "trace-toolbar") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 6.dp, top = 8.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                TraceToggle(
                    icon = Icons.Default.Schedule,
                    label = L.traceToolbarDuration,
                    active = !actualDuration,
                    onClick = { actualDuration = !actualDuration },
                )
                TraceToggle(
                    icon = Icons.AutoMirrored.Filled.FormatListBulleted,
                    label = L.traceToolbarTurns,
                    active = allTurnsCollapsed,
                    onClick = {
                        allTurnsCollapsed = !allTurnsCollapsed
                        turnOverrides = emptyMap()
                    },
                )
                TraceToggle(
                    icon = Icons.Default.Code,
                    label = L.traceToolbarCalls,
                    active = allCallsExpanded,
                    onClick = {
                        allCallsExpanded = !allCallsExpanded
                        rowOverrides = emptyMap()
                    },
                )
                Spacer(Modifier.weight(1f))
                Box(
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .widthIn(min = 48.dp)
                        .clip(CircleShape)
                        .clickable {
                            searchOpen = !searchOpen
                            // 收起搜索即清空关键词：不留「看不见的筛选」，列表回到全部步骤
                            if (!searchOpen && query.isNotEmpty()) query = ""
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier.size(40.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Default.Search, contentDescription = L.traceSearchPlaceholder, tint = Dsh.labelSecondary, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }

        item(key = "trace-search") {
            AnimatedVisibility(
                visible = searchOpen,
                enter = expandVertically(animationSpec = tween(motionDuration(180))) + fadeIn(),
                exit = shrinkVertically(animationSpec = tween(motionDuration(140))) + fadeOut(),
            ) {
                TraceSearchField(value = query, onValueChange = { query = it })
            }
        }

        if (visible.isNotEmpty()) {
            item(key = "trace-timeline") {
                TraceTimeline(
                    messages = visible,
                    durations = durations,
                    actualDuration = actualDuration,
                    focusKey = focusKey,
                    onFocus = { focusKey = if (focusKey == it) null else it },
                )
            }
        }

        if (messages.isEmpty()) {
            item(key = "trace-empty") { TraceEmptyState() }
        } else if (visible.isEmpty()) {
            // 有轨迹但搜索没命中：说清楚是「没匹配」而不是「没轨迹」
            item(key = "trace-filter-empty") {
                Text(
                    L.noTraceFilterEmpty,
                    color = Dsh.labelTertiary,
                    style = DshType.bodyDense,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 32.dp),
                )
            }
        } else {
            if (running) {
                item(key = "trace-running") {
                    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                        ThinkingStatusRow(elapsedSec)
                    }
                }
            }

            turnRows.forEach { (turn, rows) ->
                val turnKey = turn.steps.firstOrNull()?.id ?: "turn-" + turn.index
                val collapsed = isTurnCollapsed(turnKey)
                item(key = "trace-turn-" + turn.index) {
                    TraceTurnHeader(
                        turn = turn,
                        collapsed = collapsed,
                        onToggle = { turnOverrides = turnOverrides + (turnKey to !collapsed) },
                    )
                }
                if (!collapsed) {
                    items(
                        items = rows,
                        key = { it.key },
                        contentType = { "trace-row" },
                    ) { row ->
                        TraceTableRow(
                            row = row,
                            durationMs = durations[row.primary.id],
                            running = running,
                            expanded = isRowExpanded(row.key),
                            focused = focusKey == row.key,
                            onToggle = { rowOverrides = rowOverrides + (row.key to !isRowExpanded(row.key)) },
                        )
                    }
                }
            }
            item(key = "trace-bottom-space") { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun TraceEmptyState() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(Dsh.brand400.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(ThinkOutline16, contentDescription = null, tint = Dsh.brand400, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.height(14.dp))
        Text(L.noTrace, color = Dsh.labelPrimary, style = DshType.title, fontWeight = FontWeight(500))
        Spacer(Modifier.height(6.dp))
        Text(
            L.noTraceEmpty,
            color = Dsh.labelTertiary,
            style = DshType.bodyDense,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.padding(horizontal = 24.dp)
        )
    }
}

@Composable
private fun TraceToggle(icon: ImageVector, label: String, active: Boolean, onClick: () -> Unit) {
    val bg by animateColorAsState(
        targetValue = if (active) Dsh.brand400.copy(alpha = 0.14f) else Dsh.bgCard,
        animationSpec = tween(motionDuration(180)),
        label = "traceToggleBg",
    )
    val fg by animateColorAsState(
        targetValue = if (active) Dsh.brand400 else Dsh.labelSecondary,
        animationSpec = tween(motionDuration(180)),
        label = "traceToggleFg",
    )
    Row(
        modifier = Modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(DshRadius.full))
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .height(40.dp)
                .clip(RoundedCornerShape(DshRadius.full))
                .background(bg)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(5.dp))
            Text(label, color = fg, style = DshType.t12M, fontWeight = FontWeight(500))
        }
    }
}

@Composable
private fun TraceSearchField(value: String, onValueChange: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(DshRadius.md))
            .background(Dsh.bgInput)
            .border(1.dp, Dsh.borderSubtle, RoundedCornerShape(DshRadius.md))
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Search, contentDescription = null, tint = Dsh.labelTertiary, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(6.dp))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = DshType.bodyDense.copy(color = Dsh.labelPrimary),
            cursorBrush = SolidColor(Dsh.brand400),
            modifier = Modifier.weight(1f),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) {
                        Text(L.traceSearchPlaceholder, color = Dsh.labelTertiary, style = DshType.t13, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    inner()
                }
            }
        )
        if (value.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .widthIn(min = 48.dp)
                    .clip(CircleShape)
                    .clickable { onValueChange("") },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier.size(32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Default.Close, contentDescription = null, tint = Dsh.labelTertiary, modifier = Modifier.size(14.dp))
                }
            }
        }
    }
}

@Composable
private fun TraceTimeline(
    messages: List<MobileMessage>,
    durations: Map<String, Long?>,
    actualDuration: Boolean,
    focusKey: String?,
    onFocus: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(30.dp)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        messages.forEach { m ->
            val visual = traceRoleVisual(m.role)
            val key = "row-" + m.id
            val weight = if (actualDuration) {
                (durations[m.id] ?: 120L).coerceIn(60L, 6000L).toFloat()
            } else {
                1f
            }
            val alpha = if (focusKey == null || focusKey == key) 0.8f else 0.25f
            Box(
                modifier = Modifier
                    .weight(weight)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(2.dp))
                    .background(visual.color.copy(alpha = alpha))
                    .clickable { onFocus(key) },
            )
        }
    }
}

@Composable
private fun TraceTurnHeader(turn: TraceTurn, collapsed: Boolean, onToggle: () -> Unit) {
    val preview = turn.header?.text?.lineSequence()?.firstOrNull()?.trim().orEmpty()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(start = 12.dp, end = 12.dp, top = 14.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            L.traceTurnLabel.format(turn.index),
            color = Dsh.labelTertiary,
            style = DshType.t11SB,
            fontWeight = FontWeight(600),
            fontFamily = FontFamily.Monospace,
        )
        if (preview.isNotBlank()) {
            Spacer(Modifier.width(8.dp))
            Text(
                preview,
                color = Dsh.labelSecondary,
                style = DshType.caption,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        } else {
            Spacer(Modifier.weight(1f))
        }
        Text(
            if (collapsed) L.stepsCount.format(turn.steps.size) else L.collapse,
            color = Dsh.labelTertiary,
            style = DshType.t11,
        )
    }
    HorizontalDivider(color = Dsh.borderSubtle, thickness = 0.5.dp)
}

@Composable
private fun TraceTableRow(
    row: TraceRow,
    durationMs: Long?,
    running: Boolean,
    expanded: Boolean,
    focused: Boolean,
    onToggle: () -> Unit,
) {
    val primary = row.primary
    val error = traceIsError(primary) || (row.result?.let { traceIsError(it) } == true)
    val visual = traceRoleVisual(primary.role)
    val accent = if (error) Dsh.error else visual.color
    val bg = when {
        focused -> accent.copy(alpha = 0.10f)
        else -> Color.Transparent
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(bg)
            .clickable(onClick = onToggle)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        val name = primary.toolName ?: if (primary.role == "tool_result") L.executionResultRole else null
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(visual.icon, contentDescription = null, tint = accent, modifier = Modifier.size(13.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                visual.label,
                color = accent,
                style = DshType.t11x15M,
                fontWeight = FontWeight(600),
            )
            if (name != null) {
                Text(
                    " · ",
                    color = Dsh.labelTertiary,
                    style = DshType.t11x15M,
                )
                Text(
                    name,
                    color = Dsh.labelPrimary,
                    style = DshType.titleSmall,
                    fontWeight = FontWeight(500),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
            if (durationMs != null && primary.role != "user") {
                Spacer(Modifier.width(8.dp))
                Text(
                    formatTraceDuration(durationMs),
                    color = accent.copy(alpha = 0.85f),
                    style = DshType.microRelaxed,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        TraceRowBody(primary = primary, result = row.result, running = running, expanded = expanded)
        Spacer(Modifier.height(6.dp))
        HorizontalDivider(color = Dsh.borderSubtle, thickness = 0.5.dp)
    }
}

@Composable
private fun TraceRowBody(primary: MobileMessage, result: MobileMessage?, running: Boolean, expanded: Boolean) {
    when {
        primary.role == "tool_call" && result != null -> {
            TraceCodeBlock(primary.toolArgs ?: primary.text, running, expanded)
            Spacer(Modifier.height(4.dp))
            TraceCodeBlock(result.text, running = false, expanded = expanded)
        }
        primary.role == "tool_call" -> TraceCodeBlock(primary.toolArgs ?: primary.text, running, expanded)
        primary.role == "tool_result" -> TraceCodeBlock(primary.text, false, expanded)
        primary.role == "reasoning" -> TraceExpandableText(primary.text, maxLines = 4, forceExpanded = expanded)
        primary.role == "todo" -> {
            val done = primary.todos.count { it.status == "completed" }
            Text(
                if (primary.todos.isNotEmpty()) L.todoUpdate.format(done, primary.todos.size) else L.todoListUpdated,
                color = Dsh.labelSecondary,
                style = DshType.bodyDense,
                lineHeight = 20.sp
            )
        }
        primary.role == "compaction" -> Text(
            if (primary.running == true) L.compressing else primary.text.lineSequence().firstOrNull().orEmpty().ifBlank { L.contextCompressed },
            color = Dsh.labelSecondary,
            style = DshType.bodyDense,
            maxLines = if (expanded) Int.MAX_VALUE else 2,
            overflow = TextOverflow.Ellipsis
        )
        primary.role == "produced_files" -> Text(
            primary.files.joinToString("\n").ifBlank { primary.text },
            color = Dsh.labelSecondary,
            style = DshType.bodyDense,
            lineHeight = 20.sp
        )
        primary.role == ROLE_WORKSPACE_CHANGES -> TraceExpandableText(
            primary.changes?.files.orEmpty().joinToString("\n") { f ->
                f.display + if (f.added > 0 || f.deleted > 0) "  +${f.added} −${f.deleted}" else ""
            },
            maxLines = 6,
            forceExpanded = expanded,
        )
        else -> TraceExpandableText(primary.text, maxLines = 6, forceExpanded = expanded)
    }
}

@Composable
private fun TraceCodeBlock(text: String, running: Boolean, expanded: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(DshRadius.md))
            .background(Dsh.bgCode)
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        TraceExpandableText(text, maxLines = 5, mono = true, forceExpanded = expanded)
    }
}

@Composable
private fun TraceExpandableText(
    text: String,
    maxLines: Int = 4,
    mono: Boolean = false,
    forceExpanded: Boolean = false,
) {
    var localExpanded by rememberSaveable { mutableStateOf(false) }
    val expanded = forceExpanded || localExpanded
    // 走 DshType 字阶（12/17 mono、13/20 密集正文），不在业务代码里写裸字号
    val style = if (mono) DshType.t12x17.copy(fontFamily = FontFamily.Monospace) else DshType.bodyDense
    Column {
        Text(
            text,
            color = Dsh.labelSecondary,
            style = style,
            maxLines = if (expanded) Int.MAX_VALUE else maxLines,
            overflow = TextOverflow.Ellipsis
        )
        if (!forceExpanded && text.length > (if (mono) 140 else 100)) {
            Text(
                if (expanded) L.collapse else L.expand,
                color = Dsh.brand400,
                style = DshType.microRelaxed,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .padding(top = 2.dp)
                    .clip(RoundedCornerShape(DshRadius.sm))
                    .clickable { localExpanded = !localExpanded }
                    .padding(horizontal = 4.dp)
                    .wrapContentHeight(Alignment.CenterVertically)
            )
        }
    }
}
