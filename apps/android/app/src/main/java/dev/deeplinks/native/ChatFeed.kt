package dev.deeplinks.native

import dev.deeplinks.core.DshType

import android.content.Context
import android.widget.Toast
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

import dev.deeplinks.core.Dsh
import dev.deeplinks.core.DshNotifier
import dev.deeplinks.core.Host
import dev.deeplinks.core.L
import dev.deeplinks.native.MobileMessage
import dev.deeplinks.native.MobileTodoItem
import dev.deeplinks.native.util.MessageGroup
import dev.deeplinks.native.util.copiedNeedsAppToast
import dev.deeplinks.native.util.goalRoundObjective

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject


/** 从消息列表提取最上方（最新）的 goal 摘要文本。 */
internal fun latestGoalSummary(messages: List<MobileMessage>): String? {
    // 优先取显式 role=goal 消息
    for (msg in messages.asReversed()) {
        if (msg.role == "goal" && msg.goalSummary != null) return msg.goalSummary
    }
    // 降级：从最新一条 goal_round 注入文本中提取 objective
    for (msg in messages.asReversed()) {
        if ((msg.role == "assistant" || msg.role == "user")) {
            val obj = goalRoundObjective(msg.text)
            if (obj != null) return obj
        }
    }
    return null
}

/** todo 进度统计。 */
internal data class TodoProgress(
    val pending: Int = 0,
    val inProgress: Int = 0,
    val done: Int = 0,
    val total: Int = 0,
) {
    val hasActive: Boolean get() = pending > 0 || inProgress > 0
}

internal fun latestTodoProgress(messages: List<MobileMessage>): TodoProgress {
    var pending = 0
    var inProgress = 0
    var done = 0
    var latestTodoMsg: MobileMessage? = null
    for (msg in messages.asReversed()) {
        if (msg.role == "todo") { latestTodoMsg = msg; break }
    }
    if (latestTodoMsg != null) {
        for (t in latestTodoMsg.todos) {
            when (t.status) {
                "pending", "todo" -> pending++
                "in_progress", "inprogress", "running" -> inProgress++
                "done", "completed", "complete" -> done++
            }
        }
    }
    return TodoProgress(pending, inProgress, done, pending + inProgress + done)
}

/**
 * 粘性任务摘要卡片：在消息列表顶端粘住，始终可见（不随消息滚出视野）。
 * 生产打磨参考：hermes-mobile #943（TaskProgressChip 模式）、AgenticX StickyTaskBar。
 *
 * 显示内容：
 * - 活动目标（goal_round objective 或显式 goal 消息）
 * - todo 进度（pending / in_progress / done 计数条）
 *
 * 条件：仅在 isRunning == true 且存在可显示内容时出现。
 */
@Composable
internal fun StickyTaskSummaryCard(
    goalSummary: String?,
    todoProgress: TodoProgress,
    isRunning: Boolean,
    modifier: Modifier = Modifier,
) {
    if (!isRunning) return
    val hasGoal = !goalSummary.isNullOrBlank()
    val hasTodos = todoProgress.hasActive
    if (!hasGoal && !hasTodos) return

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(DshRadius.lg))
            .background(Dsh.bgInput.copy(alpha = 0.92f))
            .border(1.dp, Dsh.borderSubtle.copy(alpha = 0.5f), RoundedCornerShape(DshRadius.lg))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            // 目标行
            if (hasGoal) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        GoalOutline16,
                        contentDescription = null,
                        tint = Dsh.brand400,
                        modifier = Modifier.size(14.dp),
                    )
                    Text(
                        text = L.goalRole,
                        color = Dsh.brand400,
                        style = DshType.t11M,
                    )
                    Text(
                        text = goalSummary.take(60) + if (goalSummary.length > 60) "…" else "",
                        color = Dsh.labelPrimary,
                        style = DshType.t12,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            // todo 进度条
            if (hasTodos) {
                val total = todoProgress.total
                if (total > 0) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = "${todoProgress.done}/${total}",
                            color = Dsh.labelTertiary,
                            style = DshType.t11,
                            maxLines = 1,
                        )
                        val segments = buildList {
                            repeat(todoProgress.done) { add(Dsh.success) }
                            repeat(todoProgress.inProgress) { add(Dsh.brand500) }
                            repeat(todoProgress.pending) { add(Dsh.labelTertiary) }
                        }
                        if (segments.isNotEmpty()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(4.dp)
                                    .clip(RoundedCornerShape(2.dp)),
                                horizontalArrangement = Arrangement.spacedBy(1.dp),
                            ) {
                                segments.forEach { color ->
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth(1f / segments.size)
                                            .height(4.dp)
                                            .background(color),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 消息渲染所需的状态与副作用（从 WorkspaceScreen 抽出，COM-001 拆解）。
 *
 * 状态用 getter/setter 注入而不是持有 Compose State，因此实例可稳定复用，
 * 回调在调用时读取最新值。真正的 IO（审批/提问/反馈/重生成）留在类里，
 * 与原先内联在 composable 中的行为一一对应。
 */
internal class ChatFeedActions(
    private val client: MobileApiClient,
    private val scope: CoroutineScope,
    private val context: Context,
    private val host: Host,
    private val currentSessionId: () -> String?,
    private val messages: () -> List<MobileMessage>,
    private val setMessages: (List<MobileMessage>) -> Unit,
    private val olderMessages: () -> List<MobileMessage>,
    private val composerText: () -> String,
    private val setComposerText: (String) -> Unit,
    private val setComposerError: (String?) -> Unit,
    private val isRunning: () -> Boolean,
    private val busyEnter: () -> String,
    private val isFeedbackSupported: () -> Boolean,
    private val feedbackFor: (String) -> Pair<String, String>?,
    private val updateFeedback: (transform: (Map<String, Pair<String, String>>) -> Map<String, Pair<String, String>>) -> Unit,
    private val refreshSessions: () -> Unit,
    private val fork: (String) -> Unit,
    /** 打开改动审查面：轮次 seq + 文件下标（null = 文件列表）。 */
    val openChanges: (Long, Int?) -> Unit = { _, _ -> },
) {
    fun onAnswerApproval(): (String, String, (Boolean) -> Unit) -> Unit = { approvalId, outcome, onDone ->
        val sid = currentSessionId()
        if (sid == null) {
            onDone(false)
        } else {
            scope.launch(Dispatchers.IO) {
                val accepted = try {
                    client.answerApproval(sid, approvalId, outcome)
                } catch (_: Exception) {
                    false
                }
                withContext(Dispatchers.Main) {
                    if (accepted) {
                        setMessages(
                            messages().map { msg ->
                                if (msg.approvalId == approvalId) {
                                    applyRequestState(msg, approvalUiStatus(outcome), outcome)
                                } else msg
                            },
                        )
                        DshNotifier.cancelApproval(context, host, sid)
                    }
                    onDone(accepted)
                }
            }
        }
    }

    fun onAnswerQuestion(): (String, JSONObject, (Boolean) -> Unit) -> Unit = { rpcId, answer, onDone ->
        val sid = currentSessionId()
        if (sid == null) {
            onDone(false)
        } else {
            scope.launch(Dispatchers.IO) {
                val accepted = try {
                    client.answerQuestion(sid, rpcId, answer)
                } catch (_: Exception) {
                    false
                }
                withContext(Dispatchers.Main) { onDone(accepted) }
            }
        }
    }

    fun onCopy(msg: MobileMessage): () -> Unit = {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("dsh message", msg.text))
        if (copiedNeedsAppToast(android.os.Build.VERSION.SDK_INT)) {
            Toast.makeText(context, L.copied, Toast.LENGTH_SHORT).show()
        }
    }

    fun onQuote(msg: MobileMessage): () -> Unit = {
        // DSH 引用：把消息首行作为引用注入输入框
        val firstLine = msg.text.lineSequence().firstOrNull().orEmpty().take(120)
        val current = composerText()
        setComposerText(if (current.isBlank()) "> $firstLine\n" else "> $firstLine\n$current")
    }

    fun onFork(): () -> Unit = {
        val sid = currentSessionId()
        if (sid != null) fork(sid)
    }

    fun onRegenerate(msg: MobileMessage): (() -> Unit)? {
        if (msg.role != "assistant") return null
        return {
            val sid = currentSessionId()
            if (sid != null) {
                val all = mergeHistoryPages(olderMessages(), messages())
                val idx = all.indexOfFirst { it.id == msg.id }
                val prevUser = if (idx > 0) {
                    all.subList(0, idx).lastOrNull { it.role == "user" }
                } else {
                    null
                }
                val prompt = prevUser?.text?.takeIf { it.isNotBlank() }
                if (prompt.isNullOrBlank()) {
                    setComposerError(L.cannotFindUserMessageToRegenerate)
                } else {
                    setComposerError(null)
                    scope.launch(Dispatchers.IO) {
                        try {
                            client.sendPrompt(sid, prompt, mode = resolvePromptMode(isRunning(), busyEnter()))
                            withContext(Dispatchers.Main) { refreshSessions() }
                        } catch (e: Exception) {
                            withContext(Dispatchers.Main) {
                                setComposerError(L.regenerateFailed.format(e.message ?: L.unknownError))
                            }
                        }
                    }
                }
            }
        }
    }

    fun onFeedback(msg: MobileMessage): (() -> Unit)? {
        if (msg.role != "assistant") return null
        return { setComposerText(insertFeedbackCommand(composerText())) }
    }

    fun feedbackRating(msg: MobileMessage): String? =
        if (isFeedbackSupported()) feedbackFor(msg.id)?.first else null

    fun onRate(msg: MobileMessage): ((String) -> Unit)? {
        if (msg.role != "assistant" || !isFeedbackSupported()) return null
        return { rating ->
            val sid = currentSessionId()
            if (sid != null) {
                val current = feedbackFor(msg.id)
                scope.launch(Dispatchers.IO) {
                    try {
                        val root = client.putMessageFeedback(sid, msg.id, rating, current?.second)
                        val ver = root.optJSONObject("value")?.optString("version").orEmpty()
                        withContext(Dispatchers.Main) {
                            updateFeedback { it + (msg.id to (rating to ver)) }
                        }
                    } catch (_: Exception) {
                    }
                }
            }
        }
    }

    fun onRetract(msg: MobileMessage): (() -> Unit)? {
        if (msg.role != "assistant" || !isFeedbackSupported()) return null
        return {
            val sid = currentSessionId()
            val current = feedbackFor(msg.id)
            if (sid != null && current != null) {
                scope.launch(Dispatchers.IO) {
                    try {
                        client.deleteMessageFeedback(sid, msg.id, current.second)
                        withContext(Dispatchers.Main) {
                            updateFeedback { it - msg.id }
                        }
                    } catch (_: Exception) {
                    }
                }
            }
        }
    }

    fun onFetchProducedFile(msg: MobileMessage): ((String) -> Pair<String, ByteArray>)? {
        if (msg.role != "produced_files") return null
        return { path ->
            val sid = currentSessionId() ?: throw IllegalStateException(L.unknownError)
            client.getSessionFile(sid, path)
        }
    }
}

/**
 * 消息列表渲染（从 WorkspaceScreen 的 LazyColumn 抽出，COM-001 拆解）。
 * 纯展示 + 通过 [actions] 触发副作用。
 */
internal fun LazyListScope.chatMessageItems(
    visibleGroups: List<MessageGroup>,
    sweepingId: String?,
    toolQuery: String,
    actions: ChatFeedActions,
    goalSummary: String? = null,
    todoProgress: TodoProgress = TodoProgress(),
    isRunning: Boolean = false,
) {
    // 任务摘要卡片：运行中且有内容时作为列表首项显示
    if (isRunning && (!goalSummary.isNullOrBlank() || todoProgress.hasActive)) {
        item(key = "sticky-task-summary") {
            StickyTaskSummaryCard(
                goalSummary = goalSummary,
                todoProgress = todoProgress,
                isRunning = isRunning,
                modifier = Modifier.animateItem(),
            )
        }
    }
    items(
        items = visibleGroups,
        key = { it.groupKey },
        contentType = { group -> if (group is MessageGroup.ToolGroup) "toolgroup" else "single" },
    ) { group ->
        // 入场只交给 animateItem：AnimatedVisibility(visible = true) 首帧即可见，enter 永远不会播。
        // 仅本机刚收到的消息（entrance）淡入；历史分页、切会话载入的消息直接出现。
        val live = group is MessageGroup.Single && group.msg.entrance
        Box(
            modifier = Modifier.animateItem(
                fadeInSpec = if (live) tween(motionDuration(DshDuration.normal), easing = DshEasing.out) else null,
                fadeOutSpec = null,
            ),
        ) {
            when (group) {
                is MessageGroup.Single -> MessageItem(
                    msg = group.msg,
                    running = sweepingId != null && group.msg.id == sweepingId,
                    onAnswerApproval = actions.onAnswerApproval(),
                    onAnswerQuestion = actions.onAnswerQuestion(),
                    onCopy = actions.onCopy(group.msg),
                    onQuote = actions.onQuote(group.msg),
                    onFork = actions.onFork(),
                    onRegenerate = actions.onRegenerate(group.msg),
                    onFeedback = actions.onFeedback(group.msg),
                    feedbackRating = actions.feedbackRating(group.msg),
                    onRate = actions.onRate(group.msg),
                    onRetract = actions.onRetract(group.msg),
                    onFetchProducedFile = actions.onFetchProducedFile(group.msg),
                    onOpenChanges = actions.openChanges,
                )
                is MessageGroup.ToolGroup -> ToolGroupHeader(
                    group = group,
                    sweepingId = sweepingId,
                )
            }
        }
    }
    // 工具查找无命中（查询非空时提示，区别于"会话无消息"的 hero 态）
    if (toolQuery.isNotBlank() && visibleGroups.isEmpty()) {
        item(key = "tool-search-empty") {
            Text(
                L.noMatchingToolCalls,
                color = Dsh.labelTertiary,
                style = DshType.bodyDense,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 24.dp),
            )
        }
    }
}
