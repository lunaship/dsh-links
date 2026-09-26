package dev.deeplinks.native

import android.content.Context
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.deeplinks.core.AppSettingsStore
import dev.deeplinks.core.Host
import dev.deeplinks.core.L
import dev.deeplinks.native.util.ComposerDraft
import dev.deeplinks.native.util.parseStoppedReason
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Retained owner for the workspace's remote data and user-restorable location.
 *
 * Transient presentation state (open sheets, pressed/focus state, animations) stays in Compose;
 * session data and the network client survive Activity recreation and have one owner.
 *
 * WI-R2（本轮重构）：
 * - SSE 客户端由 VM 持有（[acquireStream]）：Activity 重建不再断流、丢游标；
 * - 周期性网络编排（会话/工作区/历史/模型/设置/搜索）迁入 VM，Job 挂在
 *   viewModelScope；单飞（single-flight）与代际去重随迁，轮询不再重复排队；
 * - 所有请求经 [WorkspaceRepository]（统一 OkHttp 网络栈）。
 */
internal class WorkspaceViewModel(
    private val host: Host,
    savedStateHandle: SavedStateHandle,
    appContext: Context,
) : ViewModel() {

    val repo = WorkspaceRepository(host, appContext)

    /** 一次性 UI 动作（发送/归档/反馈…）仍走同步 API，包在调用方 IO 协程里。 */
    val client get() = repo.api

    val local = repo.local

    // ===== 会话数据 =====

    val sessions = mutableStateOf<List<MobileSession>>(emptyList())
    val currentSessionId: MutableState<String?> =
        SavedStateMutableState(savedStateHandle, "currentSessionId", null)
    val messages = mutableStateOf<List<MobileMessage>>(emptyList())
    val olderMessages = mutableStateOf<List<MobileMessage>>(emptyList())
    val sessionStats = mutableStateOf<MobileSessionStats?>(null)
    val hasMoreMessages = mutableStateOf(false)
    val nextBeforeSeq = mutableStateOf<Long?>(null)
    val stoppedReason = mutableStateOf<String?>(null)
    val isLoadingOlder = mutableStateOf(false)
    val loadOlderFailed = mutableStateOf(false)
    val initialLoadInFlight = mutableStateOf(false)
    val historyLoadError = mutableStateOf<String?>(null)
    val sessionsLoadError = mutableStateOf<String?>(null)
    val sessionsInitialLoad = mutableStateOf(true)
    val workspacesLoadError = mutableStateOf<String?>(null)
    val workspacesInitialLoad = mutableStateOf(true)
    val modelCatalog = mutableStateOf<MobileModelCatalog?>(null)
    val modelCatalogLoading = mutableStateOf(false)
    val modelCatalogError = mutableStateOf<String?>(null)
    val isSending = mutableStateOf(false)
    val inputText: MutableState<String> =
        SavedStateMutableState(savedStateHandle, "inputText", "")
    val pendingImages = mutableStateOf<List<Pair<String, String>>>(emptyList())
    val composerDrafts = mutableStateOf<Map<String, ComposerDraft>>(emptyMap())
    val composerDraftErrors = mutableStateOf<Map<String, String>>(emptyMap())
    val composerActionError = mutableStateOf<String?>(null)

    // ===== 自 composable remember 上收的数据态（WI-R3） =====

    val appSettings = mutableStateOf(AppSettingsStore.cached(appContext, host))
    val agentPresets = mutableStateOf<List<MobileAgentPreset>>(emptyList())
    val agentPresetsLoading = mutableStateOf(false)
    val agentPresetsError = mutableStateOf<String?>(null)
    /** SSE 事件驱动的执行状态（turn/start→true，turn/end→false；与 session.list 的 running 取或） */
    val liveRunning = mutableStateOf(false)
    /** 最近一次全量刷新覆盖到的最大事件 seq（ready 时用于检测服务端游标领先） */
    val seedMaxSeq = mutableStateOf(0L)
    val searchResults = mutableStateOf<List<MobileSearchResult>>(emptyList())
    val searchState = mutableStateOf<SearchUiState>(SearchUiState.Idle)
    /** 各会话的最新 goal 文本（从消息流推导，key = sessionId）。 */
    val goalSummaries = mutableStateOf<Map<String, String>>(emptyMap())

    /** 当前会话的最新 goal 摘要（Compose 可观察状态，供顶栏与粘性摘要卡使用）。 */
    val currentGoalSummary = mutableStateOf<String?>(null)

    init {
        // 监听会话切换：切换时清空旧 goal，等 refreshMessages 再填入
        viewModelScope.launch {
            snapshotFlow { currentSessionId.value }.collect { newSid ->
                val goal = if (newSid == null) null else goalSummaries.value[newSid]
                currentGoalSummary.value = goal
            }
        }
    }

    /** 从消息流推导所有会话的 goal 摘要（反向遍历取最新）。 */
    fun recomputeGoalSummaries(allMessages: List<MobileMessage> = messages.value) {
        val result = mutableMapOf<String, String>()
        for (msg in allMessages.asReversed()) {
            if (msg.role == "goal" && msg.goalSummary != null) {
                val sid = msg.id.removePrefix("goal-").substringBefore('-').takeIf { it.length >= 8 }
                if (sid != null) result.putIfAbsent(sid, msg.goalSummary)
            }
        }
        goalSummaries.value = result
        currentSessionId.value?.let { sid ->
            currentGoalSummary.value = result[sid]
                ?: latestGoalSummary(allMessages)
        }
    }

    /** 认证失效回调（由屏幕注册：清凭据 + 跳转设备页）。 */
    var authListener: ((Throwable) -> Unit)? = null

    // ===== SSE：VM 持有，Activity 重建不断流（WI-R2） =====

    private var streamClient: SessionStreamClient? = null
    private var streamSessionId: String? = null

    /**
     * 取当前会话的流客户端；会话切换时停止旧流并新建。
     * 幂等：同一会话重复获取返回同一实例（组合重建不产生新流）。
     */
    fun acquireStream(sessionId: String): SessionStreamClient {
        val current = streamClient
        if (current != null && streamSessionId == sessionId) return current
        current?.stop()
        val created = repo.stream(sessionId, viewModelScope)
        streamClient = created
        streamSessionId = sessionId
        return created
    }

    /** 仅当 [sessionId] 是当前流会话时返回客户端（防过期会话误写游标）。 */
    fun currentStream(sessionId: String?): SessionStreamClient? =
        streamClient?.takeIf { sessionId != null && streamSessionId == sessionId }

    /** 切到「无会话」时显式释放当前流（acquireStream 只覆盖会话间切换）。 */
    fun releaseStream() {
        streamClient?.stop()
        streamClient = null
        streamSessionId = null
    }

    /** 工作区目录乐观提交后作废在途请求（对齐旧 requestSeq 自增语义）。 */
    fun invalidateWorkspaceRequests() {
        workspacesRequestSeq++
    }

    // ===== 会话列表编排（单飞 + 代际去重） =====

    private var sessionsJob: Job? = null
    private var sessionsRequestSeq = 0L
    private var workspacesJob: Job? = null
    private var workspacesRequestSeq = 0L

    val sessionsProbeInFlight: Boolean
        get() = sessionsJob?.isActive == true

    /**
     * 拉取会话列表并应用到状态；[onApplied] 在 Main 上收到快照后由屏幕做
     * 选择/归档调和（applySessionSnapshot）。单飞：已有在途请求时直接忽略。
     */
    fun refreshSessions(
        selectLatest: Boolean = false,
        reportFailure: Boolean = true,
        onApplied: (MobileSessionSnapshot) -> Unit = {},
    ) {
        if (sessionsJob?.isActive == true) return
        val requestSeq = ++sessionsRequestSeq
        sessionsJob = viewModelScope.launch {
            try {
                val snapshot = repo.sessions()
                if (requestSeq != sessionsRequestSeq) return@launch
                onApplied(snapshot)
                sessionsLoadError.value = null
                sessionsInitialLoad.value = false
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (isMobileAuthFailure(e)) {
                    authListener?.invoke(e)
                } else {
                    sessionsInitialLoad.value = false
                    if (sessions.value.isEmpty() || reportFailure) {
                        sessionsLoadError.value = e.message?.takeIf { it.isNotBlank() } ?: L.loadSessionListFailed
                    } else {
                        android.util.Log.w("WorkspaceViewModel", "background session sync failed: ${e.message}")
                    }
                }
            } finally {
                if (requestSeq == sessionsRequestSeq) sessionsJob = null
            }
        }
    }

    /**
     * 轮询探针（SSE 就绪路径）：单飞拉取列表快照，[onSnapshot] 在 Main 上回调。
     * 返回 false 表示有在途请求被跳过（调用方跳过本拍）。
     */
    fun probeSessions(sid: String, onSnapshot: (MobileSessionSnapshot) -> Unit) {
        if (sessionsJob?.isActive == true) return
        val requestSeq = ++sessionsRequestSeq
        sessionsJob = viewModelScope.launch {
            try {
                val snapshot = repo.sessions()
                if (requestSeq != sessionsRequestSeq) return@launch
                onSnapshot(snapshot)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (isMobileAuthFailure(e)) {
                    authListener?.invoke(e)
                } else {
                    android.util.Log.w("WorkspaceViewModel", "session probe while SSE ready failed: ${e.message}")
                }
            } finally {
                if (requestSeq == sessionsRequestSeq) sessionsJob = null
            }
        }
    }

    /**
     * 拉取工作区目录；[onApplied] 由屏幕做调和，[onError] 收到用户可读文案时
     * 由屏幕决定是否展示（目录非空时静默，与旧实现一致）。
     */
    fun refreshWorkspaces(
        onApplied: (MobileWorkspaceCatalog) -> Unit = {},
        onError: (String) -> Unit = {},
    ) {
        if (workspacesJob?.isActive == true) return
        val requestSeq = ++workspacesRequestSeq
        workspacesJob = viewModelScope.launch {
            try {
                val catalog = repo.workspaces()
                if (requestSeq != workspacesRequestSeq) return@launch
                onApplied(catalog)
                workspacesLoadError.value = null
                workspacesInitialLoad.value = false
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (isMobileAuthFailure(e)) {
                    authListener?.invoke(e)
                } else {
                    android.util.Log.w("WorkspaceViewModel", "background workspace sync failed: ${e.message}")
                    workspacesInitialLoad.value = false
                    onError(e.message?.takeIf { it.isNotBlank() } ?: L.loadWorkspaceListFailed)
                }
            } finally {
                if (requestSeq == workspacesRequestSeq) workspacesJob = null
            }
        }
    }

    // ===== 会话历史编排 =====

    private var historyJob: Job? = null
    private var historyJobSessionId: String? = null

    /** 会话切换/重同步时递增：过期响应按代丢弃。 */
    var historyGeneration = 0L
        private set

    fun bumpHistoryGeneration() {
        historyGeneration++
        historyJob?.cancel()
        historyJob = null
        historyJobSessionId = null
    }

    /**
     * 拉取当前会话历史并合并（保留 SSE 在途块），登记 SSE 种子游标。
     * 单飞：同会话在途时默认跳过；[replaceInFlight] 用于 resync 强制重拉。
     * [onHistoryApplied](autoScroll, changed) 与 [onDone] 在 Main 上回调。
     */
    fun refreshMessages(
        autoScroll: Boolean = false,
        replaceInFlight: Boolean = false,
        onDone: (() -> Unit)? = null,
        onHistoryApplied: (autoScroll: Boolean, changed: Boolean) -> Unit = { _, _ -> },
    ) {
        val sid = currentSessionId.value
        if (sid == null) {
            onDone?.invoke()
            return
        }
        // 同一会话至多一个 history 请求；替换语义由调用方显式要求。
        if (historyJob?.isActive == true && historyJobSessionId == sid) {
            if (!replaceInFlight) {
                onDone?.invoke()
                return
            }
            historyJob?.cancel()
        }
        val generation = historyGeneration
        historyJobSessionId = sid
        if (messages.value.isEmpty()) {
            initialLoadInFlight.value = true
            historyLoadError.value = null
        }
        historyJob = viewModelScope.launch {
            try {
                val result = repo.history(sid)
                val requests = runCatching { repo.sessionRequests(sid) }.getOrNull()
                // 会话已切换：丢弃过期响应，避免旧会话内容覆盖新会话
                if (currentSessionId.value != sid || generation != historyGeneration) return@launch
                // 内容未变化时保持列表引用稳定（避免轮询在滚动中替换数据源导致
                // LazyColumn 渲染冻结）；快路径用实例同一性 O(n) 指针比较，
                // 有差异时才回退内容签名比较
                val live = messages.value
                val merged = mergeHistoryWithLive(result.messages, live)
                val sameContent = live === merged ||
                    (live.size == merged.size && live.withIndex().all { (i, m) -> m === merged[i] }) ||
                    live.contentSignature() == merged.contentSignature()
                if (!sameContent) messages.value = merged
                // 同步推导各会话的 goal 摘要（供侧栏 / 顶栏 / 粘性摘要卡消费）
                recomputeGoalSummaries()
                sessionStats.value = result.stats
                hasMoreMessages.value = result.hasMore
                if (olderMessages.value.isEmpty()) {
                    nextBeforeSeq.value = result.nextBeforeSeq
                }
                // 发送中/执行中不覆盖：旧 turn 的 stoppedReason 会误显示成「已停止」
                if (!liveRunning.value && !isSending.value) {
                    stoppedReason.value = parseStoppedReason(result.stoppedReason)
                }
                // 登记本页最新事件 seq，作为 SSE afterSeq（只用消息 seq，不用虚高 maxSeq）
                val seed = historySeedSeq(result.maxSeq, result.messages.map { it.seq })
                if (seed > seedMaxSeq.value) seedMaxSeq.value = seed
                currentStream(sid)?.let { stream ->
                    stream.noteSeedMaxSeq(seed)
                    stream.applySnapshotCursor(seed)
                }
                if (requests != null) {
                    messages.value = mergeMessagesWithRequestSnapshot(messages.value, requests)
                    olderMessages.value = applyRequestSnapshotToMessages(olderMessages.value, requests)
                }
                // SSE 在途块 / request snapshot 也可能引入 goal 消息
                recomputeGoalSummaries()
                onHistoryApplied(autoScroll, !sameContent)
                historyLoadError.value = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("WorkspaceViewModel", "refreshMessages($sid) failed: ${e.message}")
                // history 失败也要放行 SSE，否则永远 deferred —— 仅作用于仍停留在该 sid 的客户端
                if (currentSessionId.value == sid) {
                    currentStream(sid)?.noteSeedMaxSeq(seedMaxSeq.value)
                    if (messages.value.isEmpty() && olderMessages.value.isEmpty()) {
                        historyLoadError.value = e.message?.takeIf { it.isNotBlank() } ?: L.loadConversationFailed
                    }
                }
            } finally {
                if (currentSessionId.value == sid && generation == historyGeneration) {
                    initialLoadInFlight.value = false
                }
                // 下拉刷新指示器复位：成功/失败/取消/被替换路径都会走到这里
                onDone?.invoke()
                if (generation == historyGeneration && historyJobSessionId == sid) {
                    historyJob = null
                    historyJobSessionId = null
                }
            }
        }
    }

    // ===== 模型目录 / Agent 预设 / 设置 =====

    fun loadModelCatalog(sessionId: String?, pending: Triple<String, String, String?>?) {
        if (modelCatalogLoading.value) return
        modelCatalogLoading.value = true
        modelCatalogError.value = null
        viewModelScope.launch {
            try {
                val catalog = if (sessionId == null) {
                    MobileModelCatalog(
                        currentProvider = pending?.first,
                        currentModel = pending?.second,
                        currentReasoningEffort = pending?.third,
                        groups = repo.llmModels(),
                    )
                } else {
                    repo.models(sessionId)
                }
                modelCatalog.value = catalog
                modelCatalogError.value = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (modelCatalog.value == null) {
                    modelCatalogError.value = e.message?.takeIf { it.isNotBlank() } ?: L.loadModelListFailed
                }
            } finally {
                modelCatalogLoading.value = false
            }
        }
    }

    fun loadAgentPresets() {
        if (agentPresetsLoading.value) return
        agentPresetsLoading.value = true
        agentPresetsError.value = null
        viewModelScope.launch {
            try {
                val loaded = repo.agentPresets()
                agentPresets.value = loaded
                agentPresetsError.value = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("WorkspaceViewModel", "getAgentPresets failed: ${e.message}")
                agentPresetsError.value = e.message?.takeIf { it.isNotBlank() } ?: L.loadFailed
            } finally {
                agentPresetsLoading.value = false
            }
        }
    }

    /** 设置可能在其他端修改：重新读取（失败回退缓存，不提示）。 */
    fun refreshAppSettings() {
        viewModelScope.launch {
            try {
                appSettings.value = repo.appSettings()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("WorkspaceViewModel", "refresh app settings failed: ${e.message}")
            }
        }
    }

    // ===== 侧栏搜索（状态机整体迁入 VM；持久化经 WorkspaceLocalStore） =====

    private var searchJob: Job? = null
    private var searchSeq = 0

    /**
     * Debounced 搜索：250ms 节流用户输入；多次调用取消前次 in-flight 任务
     * 并丢弃过期响应。仅当 query 非空且稳定 250ms 才发请求。
     */
    fun runSearchDebounced(rawQuery: String) {
        searchJob?.cancel()
        val query = rawQuery
        // 持久化搜索文本（不持久化结果）
        local.historyQuery = query
        if (query.isBlank()) {
            searchResults.value = emptyList()
            searchState.value = SearchUiState.Idle
            return
        }
        searchSeq++
        val seq = searchSeq
        searchState.value = SearchUiState.Loading
        searchJob = viewModelScope.launch {
            try {
                delay(250) // debounce：250ms 内多次输入只发最后一次
                if (seq != searchSeq) return@launch
                val (results, degraded) = repo.search(query)
                if (seq != searchSeq) return@launch // 过期响应丢弃
                searchResults.value = results
                searchState.value = if (results.isEmpty()) SearchUiState.Empty(degraded = degraded)
                else SearchUiState.Results(query, results.size, degraded = degraded)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (seq != searchSeq) return@launch
                searchState.value = SearchUiState.Error(e.message ?: L.searchFailed)
            }
        }
    }

    companion object {
        fun factory(host: Host) = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as android.app.Application
                WorkspaceViewModel(host, createSavedStateHandle(), app)
            }
        }
    }
}

private class SavedStateMutableState<T>(
    private val savedStateHandle: SavedStateHandle,
    private val key: String,
    initialValue: T,
) : MutableState<T> {
    private val delegate = mutableStateOf(savedStateHandle[key] ?: initialValue)

    override var value: T
        get() = delegate.value
        set(value) {
            delegate.value = value
            savedStateHandle[key] = value
        }

    override fun component1(): T = value

    override fun component2(): (T) -> Unit = { value = it }
}
