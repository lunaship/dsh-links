package dev.dsh.mobile

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class MobileMessage(
    val id: String,
    val role: String, // "user", "assistant", "tool_call", "tool_result", "reasoning", "approval", "compaction", "todo"
    val text: String,
    val toolName: String? = null,
    val toolArgs: String? = null,
    val approvalId: String? = null,
    val time: Long = 0L,
    val type: String = "text",
    val durationMs: Long? = null,
    val running: Boolean? = null,
    val todos: List<MobileTodoItem> = emptyList(),
    // SSE 流式消息为 true：播放入场动画；历史/全量刷新消息为 false：跳过（避免整列重放）
    val entrance: Boolean = false,
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
)

data class MobileSearchResult(val sessionId: String, val snippet: String)

data class MobileBootstrap(
    val hostName: String,
    val deviceName: String,
    val sessions: List<MobileSession>,
)

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
)

data class MobileModelGroup(val provider: String, val models: List<MobileModelOption>)

data class MobileModelCatalog(
    val currentProvider: String? = null,
    val currentModel: String? = null,
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
                byNs[ns]?.value?.optString(key)?.takeIf { it.isNotBlank() }
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
                provider = g.optString("provider", "未知"),
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
                    )
                },
            )
        }
        return MobileModelCatalog(
            currentProvider = current?.optString("provider"),
            currentModel = current?.optString("model"),
            groups = groups,
        )
    }

    /** 响应工具审批（DSH approval：allowed-once / rejected）。 */
    fun answerApproval(sessionId: String, approvalId: String, outcome: String): Boolean {
        val root = request("POST", "/dsh-link/mobile/sessions/" + java.net.URLEncoder.encode(sessionId, "UTF-8") + "/approval",
            JSONObject().put("approvalId", approvalId).put("outcome", outcome))
        return root.optBoolean("accepted", false)
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

    /** 工作区列表。 */
    fun getWorkspaces(): List<String> {
        val root = request("GET", "/dsh-link/mobile/workspaces")
        val arr = root.optJSONArray("workspaces") ?: org.json.JSONArray()
        return (0 until arr.length()).map { i ->
            val w = arr.getJSONObject(i)
            w.optString("path").ifBlank { w.optString("title") }
        }.filter { it.isNotBlank() }
    }

    /** 创建工作区（目录路径）。 */
    fun createWorkspace(path: String) {
        request("POST", "/dsh-link/mobile/workspaces", JSONObject().put("path", path))
    }

    /** 删除工作区（取消注册；会话日志保留，DSH workspace.delete 语义）。 */
    fun deleteWorkspace(path: String) {
        request("POST", "/dsh-link/mobile/workspaces/delete", JSONObject().put("path", path))
    }

    /** 选择会话模型。 */
    fun selectModel(sessionId: String, provider: String, model: String) {
        request("POST", "/dsh-link/mobile/sessions/" + java.net.URLEncoder.encode(sessionId, "UTF-8") + "/model",
            JSONObject().put("provider", provider).put("model", model))
    }

    fun bootstrap(): MobileBootstrap {
        val root = request("GET", "/dsh-link/mobile/bootstrap")
        val sessions = root.optJSONArray("sessions") ?: org.json.JSONArray()
        return MobileBootstrap(
            hostName = root.optJSONObject("host")?.optString("name").orEmpty(),
            deviceName = root.optJSONObject("device")?.optString("name").orEmpty(),
            sessions = (0 until sessions.length()).map { index -> parseSession(sessions.getJSONObject(index)) },
        )
    }

    fun getSessions(): List<MobileSession> {
        val root = request("GET", "/dsh-link/mobile/sessions")
        val sessions = root.optJSONArray("sessions") ?: org.json.JSONArray()
        return (0 until sessions.length()).map { index -> parseSession(sessions.getJSONObject(index)) }
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
                id = obj.optString("id", "msg-$i"),
                role = obj.optString("role", "assistant"),
                text = obj.optString("text", ""),
                toolName = obj.optString("name").takeIf { it.isNotBlank() },
                toolArgs = obj.optString("args").takeIf { it.isNotBlank() },
                approvalId = obj.optString("approvalId").takeIf { it.isNotBlank() },
                time = obj.optLong("time", 0L),
                type = obj.optString("type", "text"),
                durationMs = if (obj.has("durationMs") && !obj.isNull("durationMs")) obj.optLong("durationMs") else null,
                running = if (obj.has("running") && !obj.isNull("running")) obj.optBoolean("running") else null,
                todos = (0 until todosArr.length()).map { j ->
                    val t = todosArr.getJSONObject(j)
                    MobileTodoItem(t.optString("content", ""), t.optString("status", "pending"))
                },
            )
        }
        // stats（StatsLine：轮次/步骤/LLM 耗时/工具调用/首 token/吞吐/缓存/tokens）
        val stats = root.optJSONObject("stats")
        val result = HistoryResult(
            messages = messages,
            hasMore = root.optBoolean("hasMore", false),
            nextBeforeSeq = if (root.has("nextBeforeSeq") && !root.isNull("nextBeforeSeq")) root.optLong("nextBeforeSeq") else null,
            maxSeq = if (root.has("maxSeq") && !root.isNull("maxSeq")) root.optLong("maxSeq") else null,
            stoppedReason = root.optString("stoppedReason").takeIf { it.isNotBlank() },
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

    fun createSession(agentPreset: String? = null, cwd: String? = null): String {
        val body = JSONObject()
        if (!agentPreset.isNullOrBlank()) body.put("agentPreset", agentPreset)
        if (!cwd.isNullOrBlank()) body.put("cwd", cwd)
        val res = request("POST", "/dsh-link/mobile/sessions", body)
        return res.getString("sessionId")
    }

    fun searchSessions(query: String): List<MobileSearchResult> {
        val root = request("GET", "/dsh-link/mobile/sessions/search?q=" + java.net.URLEncoder.encode(query, "UTF-8"))
        val items = root.optJSONArray("items") ?: org.json.JSONArray()
        return (0 until items.length()).map { index ->
            val item = items.getJSONObject(index)
            MobileSearchResult(item.getString("sessionId"), item.optString("snippet"))
        }
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

    private fun parseSession(json: JSONObject): MobileSession = MobileSession(
        sessionId = json.getString("sessionId"),
        title = json.optString("title").ifBlank { "未命名会话" },
        updatedAt = json.optLong("updatedAt"),
        running = json.optBoolean("running"),
        blank = json.optBoolean("blank"),
        cwd = json.optString("cwd").takeIf { it.isNotBlank() },
        agentPreset = json.optString("agentPreset").takeIf { it.isNotBlank() },
        origin = json.optString("origin").takeIf { it.isNotBlank() },
    )

    private fun request(method: String, path: String, body: JSONObject? = null): JSONObject {
        val connection = (URL(host.baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 8_000
            readTimeout = 12_000
            useCaches = false
            setRequestProperty("Accept", "application/json")
            setRequestProperty("x-dsh-link-token", host.token)
            setRequestProperty("Cookie", "dsh_link_token=" + host.token)
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }
        try {
            if (body != null) connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IllegalStateException("手机 API 返回 HTTP $code: $text")
            return JSONObject(text)
        } finally {
            connection.disconnect()
        }
    }
}
