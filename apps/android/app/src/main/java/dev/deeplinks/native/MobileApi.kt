package dev.deeplinks.native
import dev.deeplinks.core.BoundedIo
import dev.deeplinks.core.Host
import dev.deeplinks.core.HostHttp
import dev.deeplinks.core.L
import dev.deeplinks.core.PinnedSsl
import dev.deeplinks.core.applyBootstrapRelay
import dev.deeplinks.core.isRelayRouteRevoked
import dev.deeplinks.native.MobileSession
import dev.deeplinks.native.MobileMessage
import dev.deeplinks.native.AppSettings
import dev.deeplinks.native.MobileApiClient

import android.util.Log
import org.json.JSONObject
import dev.deeplinks.native.util.optNullableString
import dev.deeplinks.native.util.parseStoppedReason
import dev.deeplinks.native.util.presentOrNull
import dev.deeplinks.native.util.redactRequestPath

data class MobileMessage(
    val id: String,
    val role: String, // "user", "assistant", "tool_call", "tool_result", "reasoning", "approval", "question", "compaction", "todo", "goal"
    val text: String,
    val toolName: String? = null,
    val toolArgs: String? = null,
    val approvalId: String? = null,
    val callId: String? = null,
    val time: Long = 0L,
    val type: String = "text",
    val durationMs: Long? = null,
    val running: Boolean? = null,
    val todos: List<MobileTodoItem> = emptyList(),
    // SSE 流式消息为 true：播放入场动画；历史/全量刷新消息为 false：跳过（避免整列重放）
    val entrance: Boolean = false,
    val seq: Long = 0L,
    /** ask_user_question 的 rpcId（role=question） */
    val questionRpcId: String? = null,
    /** 选项 label 列表（JSON 亦可塞 toolArgs；此处便于 UI） */
    val questionOptions: List<String> = emptyList(),
    val questionHeader: String? = null,
    /** 完整 questions 数组 JSON，提交答案时回传 */
    val questionPayloadJson: String? = null,
    val requestStatus: String? = null,
    val outcome: String? = null,
    /** 本轮成功写入/编辑的工作区相对路径（role=produced_files） */
    val files: List<String> = emptyList(),
    /** 顶层轮次号（role=workspace_changes 按它做同轮取代） */
    val turn: Int? = null,
    /** 本轮改动文件摘要（role=workspace_changes） */
    val changes: WorkspaceChangesSummary? = null,
    /** goal/write 事件携带的目标文本（role=goal 时使用） */
    val goalSummary: String? = null,
)

data class MobileTodoItem(val content: String, val status: String = "pending")

data class HistoryResult(
    val messages: List<MobileMessage>,
    val stats: MobileSessionStats,
    val hasMore: Boolean = false,
    val nextBeforeSeq: Long? = null,
    val maxSeq: Long? = null,
    val stoppedReason: String? = null,
)

data class MobileSession(
    val sessionId: String,
    val title: String,
    val updatedAt: Long,
    val running: Boolean,
    val blank: Boolean,
    val cwd: String?,
    val agentPreset: String?,
    val origin: String? = null, // "subagent" = 子智能体会话（侧边栏隐藏，对齐 Web UI rowVisible）
    val parentSessionId: String? = null,
    val subagentCount: Int? = null,
)

data class MobilePairedDevice(
    val deviceId: String,
    val name: String,
    val createdAt: Long = 0L,
    val lastSeenAt: Long = 0L,
    val via: String = "lan",
) {
    val isCloud: Boolean
        get() = via == "relay"
}

data class MobileSearchResult(val sessionId: String, val snippet: String)

data class MobileBootstrap(
    val hostName: String,
    val deviceName: String,
    val sessions: List<MobileSession>,
    val archivedSessionIds: Set<String> = emptySet(),
    val archiveSnapshotAvailable: Boolean = false,
    val protocol: Int = 1,
    val syncResync: Boolean = false,
    val multiQuestion: Boolean = false,
    val requestSnapshot: Boolean = false,
)

data class SessionRequestState(
    val id: String,
    val kind: String,
    val status: String,
    val outcome: String? = null,
    val questionsJson: String? = null,
    val toolName: String? = null,
    val callId: String? = null,
)

data class SessionRequestSnapshot(
    val approvals: List<SessionRequestState> = emptyList(),
    val questions: List<SessionRequestState> = emptyList(),
)

internal fun parseSessionRequestSnapshot(root: JSONObject): SessionRequestSnapshot {
    val approvals = root.optJSONArray("approvals") ?: org.json.JSONArray()
    val questions = root.optJSONArray("questions") ?: org.json.JSONArray()
    return SessionRequestSnapshot(
        approvals = (0 until approvals.length()).mapNotNull { i ->
            val obj = approvals.optJSONObject(i) ?: return@mapNotNull null
            val id = obj.optString("approvalId").ifBlank { obj.optString("id") }
            if (id.isBlank()) return@mapNotNull null
            SessionRequestState(
                id = id,
                kind = "approval",
                status = obj.optString("status", REQUEST_PENDING),
                outcome = obj.optString("outcome").takeIf { it.isNotBlank() },
                toolName = obj.optString("toolName").takeIf { it.isNotBlank() },
                callId = obj.optString("callId").takeIf { it.isNotBlank() },
            )
        },
        questions = (0 until questions.length()).mapNotNull { i ->
            val obj = questions.optJSONObject(i) ?: return@mapNotNull null
            val id = obj.optString("rpcId").ifBlank { obj.optString("id") }
            if (id.isBlank()) return@mapNotNull null
            SessionRequestState(
                id = id,
                kind = "question",
                status = obj.optString("status", REQUEST_PENDING),
                outcome = obj.optString("outcome").takeIf { it.isNotBlank() },
                questionsJson = obj.optJSONArray("questions")?.toString()
                    ?: obj.optString("questionsJson").takeIf { it.isNotBlank() },
            )
        },
    )
}

data class MobileWorkspace(
    val workspaceId: String,
    val path: String,
    val title: String = "",
    val sessionIds: List<String> = emptyList(),
)

data class MobileWorkspaceCatalog(
    val workspaces: List<MobileWorkspace>,
    val archivedSessionIds: Set<String> = emptySet(),
    val archiveSnapshotAvailable: Boolean = false,
) {
    val paths: List<String> get() = workspaces.map { it.path }
}

data class MobileSessionSnapshot(
    val sessions: List<MobileSession>,
    val archivedSessionIds: Set<String> = emptySet(),
    val archiveSnapshotAvailable: Boolean = false,
)

data class MobileWorkspaceCreation(
    val workspace: MobileWorkspace,
    val created: Boolean,
)

internal fun parseMobileWorkspace(json: JSONObject?): MobileWorkspace? {
    if (json == null) return null
    val path = json.optString("path").ifBlank { json.optString("title") }.trimEnd('/')
    if (path.isBlank()) return null
    val ids = json.optJSONArray("sessionIds") ?: org.json.JSONArray()
    return MobileWorkspace(
        workspaceId = json.optString("workspaceId").ifBlank { json.optString("id") },
        path = path,
        title = json.optString("title").ifBlank { path.substringAfterLast('/') },
        sessionIds = (0 until ids.length()).mapNotNull { index ->
            ids.optString(index).takeIf { it.isNotBlank() }
        },
    )
}

internal fun parseHistoryFiles(json: JSONObject): List<String> {
    val arr = json.optJSONArray("files") ?: return emptyList()
    return (0 until arr.length()).mapNotNull { index ->
        arr.optString(index).trim().takeIf { it.isNotBlank() }
    }
}

internal fun parseMobileSession(json: JSONObject): MobileSession = MobileSession(
    sessionId = json.getString("sessionId"),
    title = json.optNullableString("title") ?: L.untitledSession,
    updatedAt = json.optLong("updatedAt"),
    running = json.optBoolean("running"),
    blank = json.optBoolean("blank"),
    cwd = json.optNullableString("cwd"),
    agentPreset = json.optNullableString("agentPreset"),
    origin = json.optNullableString("origin"),
    parentSessionId = json.optNullableString("parentSessionId"),
    subagentCount = json.optInt("subagentCount", -1).takeIf { it >= 0 },
)

internal fun resolveHarnessLabel(
    presets: List<MobileAgentPreset>,
    activeId: String?,
    settingsId: String,
    fallback: String,
): String {
    val id = activeId.presentOrNull() ?: settingsId.presentOrNull() ?: return fallback
    return presets.find { it.id == id }?.name?.presentOrNull() ?: id
}

data class MobileSessionStats(
    val turns: Long = 0,
    val steps: Long = 0,
    val llmMs: Long = 0,
    val toolMs: Long = 0,
    val ttftMs: Long = 0,
    val ttftSteps: Long = 0,
    val decodeMs: Long = 0,
    val decodeTokens: Long = 0,
    val uncachedInputTokens: Long = 0,
    val cacheReadTokens: Long = 0,
    val outputTokens: Long = 0,
    // ContextMeter（contextPressure/contextBreakdown 投影）
    val contextPressureTokens: Long = 0,
    val contextWindow: Long = 0,
    val systemTokens: Long = 0,
    val toolsTokens: Long = 0,
    val messageTokens: Long = 0,
)

data class MobileModelOption(
    val id: String,
    val name: String?,
    val contextWindow: Long?,
    val maxTokens: Long?,
    val reasoningEfforts: List<String> = emptyList(),
    val defaultEffort: String? = null,
)

data class MobileModelGroup(
    val provider: String,
    val displayName: String = provider,
    val models: List<MobileModelOption>,
)

data class MobileModelCatalog(
    val currentProvider: String? = null,
    val currentModel: String? = null,
    val currentReasoningEffort: String? = null,
    val groups: List<MobileModelGroup> = emptyList(),
)

/** 一个设置命名空间的脱敏视图（settings.describe/update 的移动端投影）。 */
data class MobileSettingsNamespace(
    val ns: String,
    val value: JSONObject,
    val user: JSONObject? = null,
    val applies: String = "restart",
    val revision: Long = 0,
    val secrets: List<Pair<List<String>, Boolean>> = emptyList(),
)

data class MobileSettingsView(
    val writable: Boolean,
    val namespaces: List<MobileSettingsNamespace>,
)

data class MobileAgentPreset(
    val id: String,
    val name: String,
    val description: String = "",
    val isDefault: Boolean = false,
)

/** DeepSeek 余额视图（经插件代查，密钥不下发手机）。 */
data class MobileBalance(
    val balance: Double = 0.0,
    val used: Double = 0.0,
    val remainder: Double = 0.0,
    val currency: String = "USD",
)

/** 服务端为源的 AppSettings（WI-004）：默认 Agent 预设/权限/语言/主题/Enter 行为/默认模型。 */
data class AppSettings(
    val agentPreset: String = "standard",
    val permissionPreset: String = "workspace-write",
    val language: String = "zh",
    val theme: String = "system",
    val busyEnter: String = "queue",
    val defaultModelProvider: String? = null,
    val defaultModel: String? = null,
    val defaultReasoningEffort: String? = null,
) {
    companion object {
        fun fromServer(namespaces: List<MobileSettingsNamespace>): AppSettings {
            val byNs = namespaces.associateBy { it.ns }
            fun valueOf(ns: String, key: String): String? =
                byNs[ns]?.value?.optNullableString(key)
            return AppSettings(
                agentPreset = valueOf("agent-presets", "default") ?: "standard",
                permissionPreset = valueOf("permission", "defaultPreset") ?: "workspace-write",
                language = valueOf("locale", "preference") ?: "zh",
                theme = valueOf("ui-theme", "preference") ?: "system",
                busyEnter = valueOf("ui-conversation", "busyEnter") ?: "queue",
                defaultModelProvider = valueOf("agent-default-model", "provider"),
                defaultModel = valueOf("agent-default-model", "model"),
                defaultReasoningEffort = valueOf("agent-default-model", "reasoningEffort"),
            )
        }
    }
}

/** 解析 history/stats 响应中的会话统计（StatsLine/ContextMeter 数据源）。 */
fun parseMobileSessionStats(stats: org.json.JSONObject?): MobileSessionStats {
    val usage = stats?.optJSONObject("tokenUsage")
    val sessionStats = stats?.optJSONObject("sessionStats")
    val pressure = stats?.optJSONObject("contextPressure")
    val breakdown = stats?.optJSONObject("contextBreakdown")
    return MobileSessionStats(
        turns = sessionStats?.optLong("turns", 0L) ?: 0L,
        steps = sessionStats?.optLong("steps", 0L) ?: 0L,
        llmMs = sessionStats?.optLong("llmMs", 0L) ?: 0L,
        toolMs = sessionStats?.optLong("toolMs", 0L) ?: 0L,
        ttftMs = sessionStats?.optLong("ttftMs", 0L) ?: 0L,
        ttftSteps = sessionStats?.optLong("ttftSteps", 0L) ?: 0L,
        decodeMs = sessionStats?.optLong("decodeMs", 0L) ?: 0L,
        decodeTokens = sessionStats?.optLong("decodeTokens", 0L) ?: 0L,
        uncachedInputTokens = usage?.optLong("uncachedInputTokens", 0L) ?: 0L,
        cacheReadTokens = usage?.optLong("cacheReadTokens", 0L) ?: 0L,
        outputTokens = usage?.optLong("outputTokens", 0L) ?: 0L,
        contextPressureTokens = pressure?.optLong("projectedTokens", 0L) ?: pressure?.optLong("pressureTokens", 0L) ?: 0L,
        contextWindow = pressure?.optLong("contextWindow", 0L) ?: 0L,
        systemTokens = breakdown?.optLong("systemTokens", 0L) ?: 0L,
        toolsTokens = breakdown?.optLong("toolsTokens", 0L) ?: 0L,
        messageTokens = breakdown?.optLong("messageTokens", 0L) ?: 0L,
    )
}

/** 智能体运行中时，按 ui-conversation.busyEnter 决定 prompt mode（对标 web）。 */
fun resolvePromptMode(running: Boolean, busyEnter: String): String {
    if (!running) return "queue"
    return when (busyEnter) {
        "send", "steer" -> busyEnter
        "queue", "newline" -> "queue"
        else -> "queue"
    }
}

/** 将服务端 busyEnter 规范化为设置 UI 可选 id（兼容历史 newline 与 web steer）。 */
fun canonicalBusyEnter(value: String?): String = when (value) {
    "send", "steer", "queue" -> value
    "newline" -> "queue"
    else -> "queue"
}

fun busyEnterLabel(value: String?): String = when (canonicalBusyEnter(value)) {
    "send" -> L.busySend
    "steer" -> L.busySteer
    else -> L.busyQueue
}

class MobileApiClient(private val host: Host) {

    /** 会话模型目录（当前模型 + 供应商分组模型列表）。 */
    fun getModels(sessionId: String): MobileModelCatalog {
        val root = request("GET", "/dsh-link/mobile/models?sessionId=" + java.net.URLEncoder.encode(sessionId, "UTF-8"))
        val current = root.optJSONObject("current")
        val groupsArr = root.optJSONArray("groups") ?: org.json.JSONArray()
        val groups = (0 until groupsArr.length()).map { i ->
            val g = groupsArr.getJSONObject(i)
            val models = g.optJSONArray("models") ?: org.json.JSONArray()
            MobileModelGroup(
                provider = g.optString("provider", L.unknownProvider),
                displayName = g.optString("providerName").ifBlank { g.optString("provider", L.unknownProvider) },
                models = (0 until models.length()).map { j ->
                    val m = models.getJSONObject(j)
                    MobileModelOption(
                        id = m.optString("id", ""),
                        name = m.optString("name").takeIf { it.isNotBlank() },
                        contextWindow = if (m.has("contextWindow") && !m.isNull("contextWindow")) m.optLong("contextWindow") else null,
                        maxTokens = if (m.has("maxTokens") && !m.isNull("maxTokens")) m.optLong("maxTokens") else null,
                        reasoningEfforts = (m.optJSONArray("reasoningEfforts") ?: org.json.JSONArray()).let { arr ->
                            (0 until arr.length()).map { arr.getString(it) }
                        },
                        defaultEffort = m.optString("defaultEffort").takeIf { it.isNotBlank() },
                    )
                },
            )
        }
        return MobileModelCatalog(
            currentProvider = current?.optString("provider"),
            currentModel = current?.optString("model"),
            currentReasoningEffort = current?.optString("reasoningEffort")?.ifBlank { null }
                ?: current?.optString("effort")?.ifBlank { null },
            groups = groups,
        )
    }

    /** 响应工具审批（DSH approval：allowed-once / rejected）。 */
    fun answerApproval(sessionId: String, approvalId: String, outcome: String): Boolean {
        val root = request("POST", "/dsh-link/mobile/sessions/" + java.net.URLEncoder.encode(sessionId, "UTF-8") + "/approval",
            JSONObject().put("approvalId", approvalId).put("outcome", outcome))
        return root.optBoolean("accepted", false)
    }

    fun getSessionRequests(sessionId: String): SessionRequestSnapshot {
        val root = request("GET", "/dsh-link/mobile/sessions/" + java.net.URLEncoder.encode(sessionId, "UTF-8") + "/requests")
        return parseSessionRequestSnapshot(root)
    }

    /** 回答 ask_user_question（mux question/requested → /api/respond）。 */
    fun answerQuestion(sessionId: String, rpcId: String, answer: JSONObject): Boolean {
        val root = request(
            "POST",
            "/dsh-link/mobile/sessions/" + java.net.URLEncoder.encode(sessionId, "UTF-8") + "/question",
            JSONObject().put("rpcId", rpcId).put("answer", answer),
        )
        return root.optBoolean("accepted", false)
    }

    /** 读取会话内助手消息的赞/踩反馈。 */
    fun getMessageFeedback(sessionId: String): org.json.JSONObject =
        request("GET", "/dsh-link/mobile/sessions/" + java.net.URLEncoder.encode(sessionId, "UTF-8") + "/feedback")

    /** 记录一条助手消息的赞/踩。 */
    fun putMessageFeedback(sessionId: String, messageId: String, rating: String, ifVersion: String?): org.json.JSONObject {
        val body = org.json.JSONObject().put("messageId", messageId).put("rating", rating)
        if (ifVersion != null) body.put("ifVersion", ifVersion) else body.put("ifVersion", org.json.JSONObject.NULL)
        return request("POST", "/dsh-link/mobile/sessions/" + java.net.URLEncoder.encode(sessionId, "UTF-8") + "/feedback", body)
    }

    /** 取消一条助手消息的赞/踩。 */
    fun deleteMessageFeedback(sessionId: String, messageId: String, ifVersion: String): org.json.JSONObject {
        val body = org.json.JSONObject()
            .put("action", "delete")
            .put("messageId", messageId)
            .put("ifVersion", ifVersion)
        return request("POST", "/dsh-link/mobile/sessions/" + java.net.URLEncoder.encode(sessionId, "UTF-8") + "/feedback", body)
    }

    /** 会话重命名。 */
    fun renameSession(sessionId: String, title: String) {
        request("POST", "/dsh-link/mobile/sessions/" + java.net.URLEncoder.encode(sessionId, "UTF-8") + "/rename",
            JSONObject().put("title", title))
    }

    /** 分叉会话，返回子会话 id。 */
    fun forkSession(sessionId: String): String? {
        val root = request("POST", "/dsh-link/mobile/sessions/" + java.net.URLEncoder.encode(sessionId, "UTF-8") + "/fork", JSONObject())
        return root.optString("sessionId").takeIf { it.isNotBlank() }
    }

    /** 工作区列表（含服务端已归档会话 id，用于跨设备同步隐藏）。 */
    fun getWorkspaces(): MobileWorkspaceCatalog {
        val root = request("GET", "/dsh-link/mobile/workspaces")
        val arr = root.optJSONArray("workspaces") ?: org.json.JSONArray()
        val workspaces = (0 until arr.length()).mapNotNull { i ->
            parseMobileWorkspace(arr.optJSONObject(i))
        }
        val archived = root.optJSONArray("archivedSessionIds")
        val archivedArray = archived ?: org.json.JSONArray()
        val archivedIds = (0 until archivedArray.length()).mapNotNull { i ->
            archivedArray.optString(i).takeIf { it.isNotBlank() }
        }.toSet()
        return MobileWorkspaceCatalog(
            workspaces = workspaces,
            archivedSessionIds = archivedIds,
            archiveSnapshotAvailable = archived != null,
        )
    }

    /** 按会话设置权限预设（对标 web 当前会话权限）。 */
    fun setSessionPermission(sessionId: String, preset: String) {
        request(
            "POST",
            "/dsh-link/mobile/sessions/" + java.net.URLEncoder.encode(sessionId, "UTF-8") + "/permission",
            JSONObject().put("preset", preset),
        )
    }

    /** 按名称创建同级工作区，或按绝对路径注册已有目录。 */
    fun createWorkspace(input: String, parentWorkspaceId: String? = null): MobileWorkspaceCreation {
        val body = JSONObject()
            .put("input", input)
            // 兼容尚未升级、只读取 path 的插件；绝对路径模式仍可继续工作。
            .put("path", input)
        if (!parentWorkspaceId.isNullOrBlank()) body.put("parentWorkspaceId", parentWorkspaceId)
        val root = request("POST", "/dsh-link/mobile/workspaces", body)
        return MobileWorkspaceCreation(
            workspace = parseMobileWorkspace(root.optJSONObject("workspace"))
                ?: throw IllegalStateException(L.workspaceCreateInvalidResponse),
            created = root.optBoolean("created", false),
        )
    }

    /** 删除工作区（取消注册；会话日志保留，DSH workspace.delete 语义）。 */
    fun deleteWorkspace(path: String) {
        request("POST", "/dsh-link/mobile/workspaces/delete", JSONObject().put("path", path))
    }

    /** 删除会话（服务端归档，workspace.archiveSession 语义；对标 web 删除）。 */
    fun archiveSession(sessionId: String) {
        request("POST", "/dsh-link/mobile/sessions/" + java.net.URLEncoder.encode(sessionId, "UTF-8") + "/archive", JSONObject())
    }

    /** 选择会话模型（可选推理等级，对齐 Web session.selectModel）。 */
    fun selectModel(sessionId: String, provider: String, model: String, reasoningEffort: String? = null) {
        val body = JSONObject().put("provider", provider).put("model", model)
        if (!reasoningEffort.isNullOrBlank()) body.put("reasoningEffort", reasoningEffort)
        request("POST", "/dsh-link/mobile/sessions/" + java.net.URLEncoder.encode(sessionId, "UTF-8") + "/model", body)
    }

    fun bootstrap(): Pair<MobileBootstrap, Host> {
        val root = request("GET", "/dsh-link/mobile/bootstrap")
        val sessions = root.optJSONArray("sessions") ?: org.json.JSONArray()
        val archived = root.optJSONArray("archivedSessionIds")
        val archivedArray = archived ?: org.json.JSONArray()
        val info = MobileBootstrap(
            hostName = root.optJSONObject("host")?.optString("name").orEmpty(),
            deviceName = root.optJSONObject("device")?.optString("name").orEmpty(),
            sessions = (0 until sessions.length()).mapNotNull { index ->
                runCatching { parseMobileSession(sessions.getJSONObject(index)) }.getOrNull()
            },
            archivedSessionIds = (0 until archivedArray.length()).mapNotNull { index ->
                archivedArray.optString(index).takeIf { it.isNotBlank() }
            }.toSet(),
            archiveSnapshotAvailable = archived != null,
            protocol = root.optInt("protocol", 1),
            syncResync = root.optJSONObject("capabilities")?.optJSONObject("sync")?.optBoolean("resync") == true,
            multiQuestion = root.optJSONObject("capabilities")?.optJSONObject("questions")?.optBoolean("multi") == true,
            requestSnapshot = root.optJSONObject("capabilities")?.optJSONObject("requests")?.optBoolean("snapshot") == true,
        )
        return info to applyBootstrapRelay(host, root)
    }

    fun getSessions(): MobileSessionSnapshot {
        val root = request("GET", "/dsh-link/mobile/sessions")
        val sessions = root.optJSONArray("sessions") ?: org.json.JSONArray()
        val archived = root.optJSONArray("archivedSessionIds")
        val archivedArray = archived ?: org.json.JSONArray()
        return MobileSessionSnapshot(
            sessions = (0 until sessions.length()).mapNotNull { index ->
                runCatching { parseMobileSession(sessions.getJSONObject(index)) }.getOrNull()
            },
            archivedSessionIds = (0 until archivedArray.length()).mapNotNull { index ->
                archivedArray.optString(index).takeIf { it.isNotBlank() }
            }.toSet(),
            archiveSnapshotAvailable = archived != null,
        )
    }

    fun getSessionHistory(sessionId: String, beforeSeq: Long? = null, maxMessages: Int? = null): HistoryResult {
        var path = "/dsh-link/mobile/sessions/" + java.net.URLEncoder.encode(sessionId, "UTF-8") + "/history"
        val query = buildList {
            beforeSeq?.let { add("beforeSeq=$it") }
            maxMessages?.let { add("maxMessages=$it") }
        }
        if (query.isNotEmpty()) path += "?" + query.joinToString("&")
        val root = request("GET", path)
        val rawList = root.optJSONArray("messages") ?: org.json.JSONArray()
        val messages = (0 until rawList.length()).map { i ->
            val obj = rawList.getJSONObject(i)
            val todosArr = obj.optJSONArray("todos") ?: org.json.JSONArray()
            MobileMessage(
                id = obj.optString("id").ifBlank { "msg-${beforeSeq ?: "tail"}-$i" },
                role = obj.optString("role", "assistant"),
                text = obj.optString("text", ""),
                toolName = obj.optString("name").takeIf { it.isNotBlank() } ?: obj.optString("toolName").takeIf { it.isNotBlank() },
                toolArgs = obj.optString("args").takeIf { it.isNotBlank() },
                approvalId = obj.optString("approvalId").takeIf { it.isNotBlank() },
                callId = obj.optString("callId").takeIf { it.isNotBlank() },
                time = obj.optLong("time", 0L),
                type = obj.optString("type", "text"),
                durationMs = if (obj.has("durationMs") && !obj.isNull("durationMs")) obj.optLong("durationMs") else null,
                running = if (obj.has("running") && !obj.isNull("running")) obj.optBoolean("running") else null,
                todos = (0 until todosArr.length()).map { j ->
                    val t = todosArr.getJSONObject(j)
                    MobileTodoItem(t.optString("content", ""), t.optString("status", "pending"))
                },
                seq = obj.optLong("seq", 0L),
                questionRpcId = obj.optString("questionRpcId").takeIf { it.isNotBlank() },
                questionOptions = obj.optJSONArray("questionOptions")?.let { arr ->
                    (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() }
                } ?: emptyList(),
                questionHeader = obj.optString("questionHeader").takeIf { it.isNotBlank() },
                questionPayloadJson = obj.optString("questionPayloadJson").takeIf { it.isNotBlank() }
                    ?: obj.optJSONArray("questions")?.toString(),
                requestStatus = obj.optString("requestStatus").takeIf { it.isNotBlank() },
                outcome = obj.optString("outcome").takeIf { it.isNotBlank() },
                files = parseHistoryFiles(obj),
                turn = if (obj.has("turn") && !obj.isNull("turn")) obj.optInt("turn") else null,
                changes = obj.optJSONObject("changes")?.let { parseWorkspaceChanges(it, obj.optLong("seq", 0L)) },
            )
        }
        // stats（StatsLine：轮次/步骤/LLM 耗时/工具调用/首 token/吞吐/缓存/tokens）
        val stats = root.optJSONObject("stats")
        val result = HistoryResult(
            messages = messages,
            hasMore = root.optBoolean("hasMore", false),
            nextBeforeSeq = if (root.has("nextBeforeSeq") && !root.isNull("nextBeforeSeq")) root.optLong("nextBeforeSeq") else null,
            maxSeq = if (root.has("maxSeq") && !root.isNull("maxSeq")) root.optLong("maxSeq") else null,
            stoppedReason = parseStoppedReason(root.optNullableString("stoppedReason")),
            stats = parseMobileSessionStats(stats),
        )
        return result
    }

    fun sendPrompt(sessionId: String, text: String, mode: String = "queue", images: List<Pair<String, String>> = emptyList()) {
        val body = JSONObject().apply {
            put("text", text)
            put("mode", mode)
            if (images.isNotEmpty()) {
                put("images", org.json.JSONArray().apply {
                    images.forEach { (mediaType, data) ->
                        put(JSONObject().put("mediaType", mediaType).put("data", data))
                    }
                })
            }
        }
        request("POST", "/dsh-link/mobile/sessions/" + java.net.URLEncoder.encode(sessionId, "UTF-8") + "/prompt", body)
    }

    fun getSessionFile(sessionId: String, path: String): Pair<String, ByteArray> {
        if (host.token.isBlank()) throw MobileAuthException("缺少或无效的连接 token", 401)
        val encodedSid = java.net.URLEncoder.encode(sessionId, "UTF-8")
        val encodedPath = java.net.URLEncoder.encode(path, "UTF-8").replace("+", "%20")
        val apiPath = "/dsh-link/mobile/sessions/$encodedSid/file?path=$encodedPath"
        val connectMs = if (host.hasRelay) 20_000 else 8_000
        val readMs = if (host.hasRelay) 45_000 else 12_000
        try {
            HostHttp.execute(
                host,
                HostHttp.DshRequest(
                    method = "GET",
                    path = apiPath,
                    headers = dshAuthHeaders() + listOf("Accept" to "*/*", "Accept-Encoding" to "identity"),
                    connectTimeoutMs = connectMs,
                    readTimeoutMs = readMs,
                ),
            ).use { response ->
                val code = response.code
                if (code !in 200..299) {
                    val text = response.body.byteStream().use { BoundedIo.readText(it) }
                    val msg = parseMobileApiError(code, text)
                    if (code == 401 || code == 403) throw MobileAuthException(msg, code)
                    throw IllegalStateException(msg)
                }
                val bytes = response.body.byteStream()
                    .use { BoundedIo.readBytes(it, BoundedIo.MAX_WORKSPACE_FILE_BYTES) }
                val mime = response.header("Content-Type")?.substringBefore(';')?.trim().orEmpty()
                    .ifBlank { "application/octet-stream" }
                return mime to bytes
            }
        } catch (e: MobileAuthException) {
            throw e
        } catch (e: Exception) {
            throw IllegalStateException(friendlyNetworkError(e), e)
        }
    }

    /**
     * 本轮改动摘要（完整文件列表）。Host 已取不到、旧插件无此路由或网络失败都返回 null：
     * 调用方只是少一张卡，下一次历史刷新会按内嵌摘要补回；鉴权失败照常抛出。
     */
    fun getWorkspaceChanges(sessionId: String, seq: Long): WorkspaceChangesSummary? {
        val sid = java.net.URLEncoder.encode(sessionId, "UTF-8")
        val root = try {
            request("GET", "/dsh-link/mobile/sessions/$sid/changes?seq=$seq")
        } catch (e: MobileAuthException) {
            throw e
        } catch (_: IllegalStateException) {
            return null
        }
        return parseWorkspaceChanges(root, seq)
    }

    /** 单个改动文件的对比（hunk 可达数千行，放宽 JSON 读取上限）。 */
    fun getWorkspaceChangeDiff(sessionId: String, seq: Long, index: Int): WorkspaceFileDiff {
        val sid = java.net.URLEncoder.encode(sessionId, "UTF-8")
        val root = request("GET", "/dsh-link/mobile/sessions/$sid/changes/diff?seq=$seq&index=$index", maxBytes = DIFF_BODY_MAX_BYTES)
        return parseWorkspaceFileDiff(root) ?: throw IllegalStateException(L.unknownError)
    }

    fun createSession(agentPreset: String? = null, cwd: String? = null, workspaceId: String? = null): String {
        val body = JSONObject()
        if (!agentPreset.isNullOrBlank()) body.put("agentPreset", agentPreset)
        // session.create 至多一个：workspaceId 才会记入 Web 分组；只传 cwd 会变成未分组。
        if (!workspaceId.isNullOrBlank()) body.put("workspaceId", workspaceId)
        else if (!cwd.isNullOrBlank()) body.put("cwd", cwd)
        val res = request("POST", "/dsh-link/mobile/sessions", body)
        return res.getString("sessionId")
    }

    fun searchSessions(query: String): Pair<List<MobileSearchResult>, Boolean> {
        val root = request("GET", "/dsh-link/mobile/sessions/search?q=" + java.net.URLEncoder.encode(query, "UTF-8"))
        val items = root.optJSONArray("items") ?: org.json.JSONArray()
        val list = (0 until items.length()).map { index ->
            val item = items.getJSONObject(index)
            MobileSearchResult(item.getString("sessionId"), item.optString("snippet"))
        }
        return list to root.optBoolean("degraded", false)
    }

    fun cancelSession(sessionId: String) {
        request("POST", "/dsh-link/mobile/sessions/" + java.net.URLEncoder.encode(sessionId, "UTF-8") + "/cancel", JSONObject())
    }

    /** 设置命名空间视图（settings.describe 透传；值已由服务端脱敏）。 */
    fun getSettings(): MobileSettingsView {
        val root = request("GET", "/dsh-link/mobile/settings")
        val arr = root.optJSONArray("namespaces") ?: org.json.JSONArray()
        return MobileSettingsView(
            writable = root.optBoolean("writable", false),
            namespaces = (0 until arr.length()).map { i ->
                val ns = arr.getJSONObject(i)
                MobileSettingsNamespace(
                    ns = ns.optString("ns"),
                    value = ns.optJSONObject("value") ?: JSONObject(),
                    user = ns.optJSONObject("user"),
                    applies = ns.optString("applies", "restart"),
                    revision = ns.optLong("revision", 0L),
                    secrets = (ns.optJSONArray("secrets") ?: org.json.JSONArray()).let { sa ->
                        (0 until sa.length()).map { j ->
                            val s = sa.getJSONObject(j)
                            val path = (s.optJSONArray("path") ?: org.json.JSONArray())
                            val pathList = (0 until path.length()).map { path.getString(it) }
                            pathList to s.optBoolean("set", false)
                        }
                    },
                )
            },
        )
    }

    /**
     * 写一个设置命名空间（settings.update 透传）。返回服务端读回的新视图；
     * 失败抛异常（消息含服务端错误，供行内展示）。
     */
    fun updateSettings(ns: String, patch: JSONObject, expectedRevision: Long? = null): MobileSettingsNamespace {
        val body = JSONObject().put("ns", ns).put("patch", patch)
        expectedRevision?.let { body.put("expectedRevision", it) }
        val root = request("POST", "/dsh-link/mobile/settings/update", body)
        val secrets = (root.optJSONArray("secrets") ?: org.json.JSONArray())
        return MobileSettingsNamespace(
            ns = root.optString("ns", ns),
            value = root.optJSONObject("value") ?: JSONObject(),
            user = root.optJSONObject("user"),
            applies = root.optString("applies", "restart"),
            revision = root.optLong("revision", 0L),
            secrets = (0 until secrets.length()).map { j ->
                val s = secrets.getJSONObject(j)
                val path = (s.optJSONArray("path") ?: org.json.JSONArray())
                val pathList = (0 until path.length()).map { path.getString(it) }
                pathList to s.optBoolean("set", false)
            },
        )
    }

    /** 真实 Agent 预设列表（agentPreset.list 透传）。 */
    fun getAgentPresets(): List<MobileAgentPreset> {
        val root = request("GET", "/dsh-link/mobile/agent-presets")
        val arr = root.optJSONArray("presets") ?: org.json.JSONArray()
        return (0 until arr.length()).map { i ->
            val p = arr.getJSONObject(i)
            MobileAgentPreset(
                id = p.getString("id"),
                name = p.optString("name", p.optString("id")),
                description = p.optString("description", ""),
                isDefault = p.optBoolean("isDefault", false),
            )
        }
    }

    fun getLlmModels(): List<MobileModelGroup> {
        val root = request("GET", "/dsh-link/mobile/llm-models")
        val arr = root.optJSONArray("groups") ?: org.json.JSONArray()
        return (0 until arr.length()).map { i ->
            val g = arr.getJSONObject(i)
            val models = g.optJSONArray("models") ?: org.json.JSONArray()
            MobileModelGroup(
                provider = g.optString("provider", L.unknownProvider),
                models = (0 until models.length()).map { j ->
                    val m = models.getJSONObject(j)
                    MobileModelOption(
                        id = m.optString("id", ""),
                        name = m.optString("name").takeIf { it.isNotBlank() },
                        contextWindow = if (m.has("contextWindow") && !m.isNull("contextWindow")) m.optLong("contextWindow") else null,
                        maxTokens = if (m.has("maxTokens") && !m.isNull("maxTokens")) m.optLong("maxTokens") else null,
                    )
                },
            )
        }
    }

    /** DeepSeek 余额（经插件 /dsh-link/mobile/balance 代查，密钥不下发）。 */
    fun getBalance(): MobileBalance {
        val root = request("GET", "/dsh-link/mobile/balance")
        return MobileBalance(
            balance = root.optDouble("balance", 0.0),
            used = root.optDouble("used", 0.0),
            remainder = root.optDouble("remainder", 0.0),
            currency = root.optString("currency", "USD"),
        )
    }

    /** 已配对到此电脑端的设备列表（对标 web /dsh-link/devices）。 */
    fun getPairedDevices(): List<MobilePairedDevice> {
        val root = request("GET", "/dsh-link/mobile/devices")
        val arr = root.optJSONArray("devices") ?: org.json.JSONArray()
        return (0 until arr.length()).map { i ->
            val d = arr.getJSONObject(i)
            val name = d.optString("name", "")
            val rawVia = d.optString("via", "")
            val via = when {
                rawVia == "relay" || rawVia == "lan" -> rawVia
                name.endsWith("·云") || name.contains(" · 云端") -> "relay"
                else -> "lan"
            }
            MobilePairedDevice(
                deviceId = d.optString("deviceId", ""),
                name = name,
                createdAt = d.optLong("createdAt", 0L),
                lastSeenAt = d.optLong("lastSeenAt", 0L),
                via = via,
            )
        }.filter { it.deviceId.isNotBlank() }
    }

    /** 吊销电脑端已配对设备（对标 web POST /dsh-link/revoke）。 */
    fun revokePairedDevice(deviceId: String? = null, name: String? = null) {
        val body = JSONObject()
        if (!deviceId.isNullOrBlank()) body.put("deviceId", deviceId)
        if (!name.isNullOrBlank()) body.put("name", name)
        request("POST", "/dsh-link/mobile/revoke", body)
    }

    private fun request(
        method: String,
        path: String,
        body: JSONObject? = null,
        maxBytes: Int = BoundedIo.MAX_JSON_BODY_BYTES,
    ): JSONObject {
        if (host.token.isBlank()) throw MobileAuthException("缺少或无效的连接 token", 401)
        val connectMs = if (host.hasRelay) 20_000 else 8_000
        val readMs = if (host.hasRelay) 45_000 else 12_000
        try {
            HostHttp.execute(
                host,
                HostHttp.DshRequest(
                    method = method,
                    path = path,
                    body = body?.toString()?.toByteArray(Charsets.UTF_8),
                    headers = dshAuthHeaders() + listOf(
                        "Accept" to "application/json",
                        "Accept-Encoding" to "identity",
                    ),
                    connectTimeoutMs = connectMs,
                    readTimeoutMs = readMs,
                ),
            ).use { response ->
                val code = response.code
                val text = response.body.byteStream().use { BoundedIo.readText(it, maxBytes) }
                if (code !in 200..299) {
                    val msg = parseMobileApiError(code, text)
                    if (code == 401 || code == 403) throw MobileAuthException(msg, code)
                    throw IllegalStateException(msg)
                }
                return JSONObject(text)
            }
        } catch (e: MobileAuthException) {
            Log.w("MobileApi", "$method ${redactRequestPath(path)} relay=${host.hasRelay} auth ${e.code}: ${e.message}")
            throw e
        } catch (e: Exception) {
            val friendly = friendlyNetworkError(e)
            Log.w("MobileApi", "$method ${redactRequestPath(path)} relay=${host.hasRelay} ${e.javaClass.simpleName}: ${e.message}")
            throw IllegalStateException(friendly, e)
        }
    }

    /** DSH 鉴权头（token 头 + 可选 Bearer）。 */
    private fun dshAuthHeaders(): List<Pair<String, String>> = buildList {
        add("x-dsh-link-token" to host.token)
        if (host.token.isNotBlank()) add("Authorization" to "Bearer ${host.token}")
    }
}

internal class MobileAuthException(message: String, val code: Int) : IllegalStateException(message)

/** 改动对比 JSON 的读取上限：插件侧已按 5000 行 / 150 万字符截断，转义后留足余量。 */
private const val DIFF_BODY_MAX_BYTES = 4 * 1_048_576

internal fun isPendingHostApproval(error: Throwable): Boolean =
    generateSequence(error) { it.cause }.any { err ->
        val msg = err.message.orEmpty()
        msg.contains("设备待主机确认") ||
            msg.contains("waiting for approval", ignoreCase = true) ||
            msg.contains("pending approval", ignoreCase = true)
    }

internal fun isMobileAuthFailure(error: Throwable): Boolean {
    var cur: Throwable? = error
    while (cur != null) {
        if (cur is MobileAuthException) return true
        val msg = cur.message.orEmpty()
        if (
            msg.contains("缺少或无效的连接 token") ||
            isPendingHostApproval(cur) ||
            msg.contains("设备已被吊销") ||
            msg.contains("REVOKED")
        ) return true
        cur = cur.cause
    }
    return false
}

internal fun mobileAuthUserMessage(error: Throwable): String {
    if (isPendingHostApproval(error)) return L.devicePendingApproval
    val unwrapped = if (error is Exception) PinnedSsl.unwrap(error) else error
    if (unwrapped is PinnedSsl.CertChangedException) {
        return L.certificateChanged
    }
    if (isRelayRouteRevoked(error)) return L.relayRouteExpired
    return L.connectionAuthExpired
}

/** 打开设备时凭据已死则丢掉本机配对；待主机确认与失效云端路由仍保留局域网记录。 */
internal fun shouldDropLocalHostOnOpenAuth(error: Throwable): Boolean {
    if (isRelayRouteRevoked(error)) return false
    if (isPendingHostApproval(error)) return false
    val unwrapped = if (error is Exception) PinnedSsl.unwrap(error) else error
    return isMobileAuthFailure(error) || unwrapped is PinnedSsl.CertChangedException
}

/** 云端路由已更换或吊销：清掉 Relay 字段，保留局域网配对。 */
internal fun shouldDemoteRelayOnAuth(error: Throwable): Boolean = isRelayRouteRevoked(error)

/** 凭据已失效时本机删除仍应放行；其它错误则保留本地记录以免服务端孤儿配对。 */
internal fun shouldBlockLocalHostRemoval(revokeError: Throwable?): Boolean =
    revokeError != null && !isMobileAuthFailure(revokeError)

internal fun friendlyNetworkError(error: Throwable): String {
    val msg = error.message.orEmpty()
    return when {
        msg.contains("REVOKED") -> L.relayRouteExpired
        msg.contains("agent offline") -> L.relayAgentOffline
        msg.contains("rate limited") -> L.requestTooFrequent
        msg.contains("route busy") -> L.relayRouteBusy
        msg.contains("bind timeout") -> L.relayBindTimeout
        msg.contains("truncated HTTP body") -> L.relayTruncatedBody
        else -> msg.ifBlank { error.javaClass.simpleName }
    }
}

internal fun parseMobileApiError(code: Int, text: String): String {
    val fromJson = runCatching { JSONObject(text).optString("error") }.getOrNull()?.takeIf { it.isNotBlank() }
    return fromJson ?: text.ifBlank { "HTTP $code" }
}
