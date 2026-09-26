package dev.deeplinks.native

import android.content.Context
import dev.deeplinks.core.AppSettingsStore
import dev.deeplinks.core.Host
import dev.deeplinks.native.util.WorkspacePrefs
import dev.deeplinks.native.util.normalizeWorkspacePath
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 工作台数据仓库（WorkspaceScreen 深度重构 · WI-R1）：
 *
 * - **屏幕不再自己发「所有权型」请求**：会话/工作区/历史/模型/设置等周期性数据
 *   一律由 [WorkspaceViewModel] 通过本仓库在 viewModelScope 拉取；
 * - 屏幕保留的一次性用户动作（发送、归档、反馈……）也经由 `api` 走统一 OkHttp
 *   网络栈，不再各自直连；
 * - 会话级本地状态（归档/删除/工作区偏好）统一归 [WorkspaceLocalStore] 所有，
 *   屏幕不再散落 getSharedPreferences 调用。
 */
internal class WorkspaceRepository(
    private val host: Host,
    private val context: Context,
) {
    /** 一次性动作仍需同步 API（调用方自带 IO 协程）。 */
    val api = MobileApiClient(host)

    /** 会话级本地状态唯一所有者。 */
    val local = WorkspaceLocalStore(context)

    fun stream(sessionId: String, scope: CoroutineScope): SessionStreamClient =
        SessionStreamClient(host, sessionId, scope)

    // ===== 周期性数据（VM 在 viewModelScope 调用；IO 已切） =====

    suspend fun bootstrap(): Pair<MobileBootstrap, Host> = io { api.bootstrap() }

    suspend fun sessions(): MobileSessionSnapshot = io { api.getSessions() }

    suspend fun workspaces(): MobileWorkspaceCatalog = io { api.getWorkspaces() }

    suspend fun history(
        sessionId: String,
        beforeSeq: Long? = null,
        maxMessages: Int? = null,
    ): HistoryResult = io { api.getSessionHistory(sessionId, beforeSeq, maxMessages) }

    suspend fun sessionRequests(sessionId: String): SessionRequestSnapshot =
        io { api.getSessionRequests(sessionId) }

    suspend fun models(sessionId: String): MobileModelCatalog = io { api.getModels(sessionId) }

    suspend fun llmModels(): List<MobileModelGroup> = io { api.getLlmModels() }

    suspend fun agentPresets(): List<MobileAgentPreset> = io { api.getAgentPresets() }

    suspend fun search(query: String): Pair<List<MobileSearchResult>, Boolean> =
        io { api.searchSessions(query) }

    /** 服务端为源的 App 设置（WI-004），失败回退本地缓存。内部为阻塞请求，必须切 IO。 */
    suspend fun appSettings(): AppSettings = io { AppSettingsStore.fetch(host, context) }

    // ===== 一次性动作 =====

    suspend fun sessionFile(sessionId: String, path: String): Pair<String, ByteArray> =
        io { api.getSessionFile(sessionId, path) }

    suspend fun messageFeedback(sessionId: String): JSONObject = io { api.getMessageFeedback(sessionId) }

    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }
}

/**
 * 会话级本地状态唯一所有者（WI-R3）。
 *
 * 所有权分层（此前散落在 WorkspaceScreen 的三处存储收敛至此）：
 * - [WorkspacePrefs]（"dsh_workspace" / "dsh_workspace_tabs"）：跨启动持久化；
 * - SavedStateHandle（WorkspaceViewModel）：进程内可恢复的 UI 位置（当前会话、输入框）；
 * - ViewModel 内存态：会话数据（sessions/messages/stream）。
 *
 * 屏幕只读这里的 Compose 状态、调用这里的写方法；不再直接碰 SharedPreferences。
 */
internal class WorkspaceLocalStore(context: Context) {

    val prefs = WorkspacePrefs(context)

    private val tabsPrefs = context.applicationContext
        .getSharedPreferences(TABS_PREFS, Context.MODE_PRIVATE)

    // ===== Compose 状态（hydrate 自 prefs；写入走方法，双写保持一致） =====

    val archivedSessionIds = androidx.compose.runtime.mutableStateOf(prefs.archivedSessionIds)
    val deletedSessionIds = androidx.compose.runtime.mutableStateOf(prefs.deletedSessionIds)
    val deletedWorkspacePaths = androidx.compose.runtime.mutableStateOf(
        prefs.deletedWorkspacePaths
            .map(::normalizeWorkspacePath)
            .filter { it.isNotBlank() }
            .toSet(),
    )

    /** 当前会话在本地隐藏集合中的可见性快照（applySessionList 用）。 */
    val hiddenSessionIds: Set<String>
        get() = archivedSessionIds.value + deletedSessionIds.value

    fun setArchivedSessionIds(next: Set<String>) {
        archivedSessionIds.value = next
        prefs.archivedSessionIds = next
    }

    fun addDeletedSession(id: String) {
        val next = deletedSessionIds.value + id
        deletedSessionIds.value = next
        prefs.deletedSessionIds = next
    }

    fun setDeletedWorkspaces(next: Set<String>) {
        val normalized = next.map(::normalizeWorkspacePath).filter { it.isNotBlank() }.toSet()
        deletedWorkspacePaths.value = normalized
        prefs.deletedWorkspacePaths = normalized
    }

    /** 归档撤销（设置页同一套本地恢复通路）。 */
    fun restoreSession(sessionId: String) {
        prefs.archivedSessionIds = prefs.archivedSessionIds - sessionId
        prefs.settingsHiddenSessionIds = prefs.settingsHiddenSessionIds - sessionId
        prefs.restoredSessionIds = prefs.restoredSessionIds + sessionId
        reload()
    }

    /** 设置页可能直接改过 prefs：回前台时重新水合（保持既有 ON_RESUME 同步语义）。 */
    fun reload() {
        archivedSessionIds.value = prefs.archivedSessionIds
        deletedSessionIds.value = prefs.deletedSessionIds
        deletedWorkspacePaths.value = prefs.deletedWorkspacePaths
            .map(::normalizeWorkspacePath)
            .filter { it.isNotBlank() }
            .toSet()
    }

    // ===== tab/侧栏偏好（"dsh_workspace_tabs"，与既有键保持一致） =====

    var viewMode: String
        get() = tabsPrefs.getString(KEY_VIEW_MODE, "chat") ?: "chat"
        set(value) = tabsPrefs.edit().putString(KEY_VIEW_MODE, value).apply()

    var historyQuery: String
        get() = tabsPrefs.getString(KEY_HISTORY_QUERY, "") ?: ""
        set(value) = tabsPrefs.edit().putString(KEY_HISTORY_QUERY, value).apply()

    companion object {
        private const val TABS_PREFS = "dsh_workspace_tabs"
        private const val KEY_VIEW_MODE = "view_mode"
        private const val KEY_HISTORY_QUERY = "history_query"
    }
}
