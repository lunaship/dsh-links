package dev.dsh.mobile.native
import dev.dsh.mobile.core.Dsh
import dev.dsh.mobile.core.Host
import dev.dsh.mobile.core.ThemeManager
import dev.dsh.mobile.native.MobileSession
import dev.dsh.mobile.native.MobileMessage
import dev.dsh.mobile.native.AppSettings
import dev.dsh.mobile.native.MobileApiClient
import dev.dsh.mobile.core.AppSettingsStore
import dev.dsh.mobile.core.DshNotifier
import dev.dsh.mobile.core.DshTheme
import dev.dsh.mobile.core.HostStore
import dev.dsh.mobile.devices.DevicesActivity

import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.expandVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.*
import dev.dsh.mobile.native.ui.DshBanner
import dev.dsh.mobile.native.ui.DshBannerTone
import dev.dsh.mobile.native.ui.DshFilterChip
import dev.dsh.mobile.native.ui.DshTag
import dev.dsh.mobile.native.ui.ChatLoadingSkeleton
import dev.dsh.mobile.native.util.MessageGroup
import dev.dsh.mobile.native.util.SessionFilter
import dev.dsh.mobile.native.util.WorkspacePrefs
import dev.dsh.mobile.native.util.classifySession
import dev.dsh.mobile.native.util.groupMessages
import dev.dsh.mobile.native.util.matchesTool

/**
 * DeepSeek Harness 工作台 —— 1:1 复刻 DSH Web (127.0.0.1:3080) 设计语言。
 * 设计 token 取自 dsw 暗色主题：neutral-bluish 色阶 / deepseek 品牌蓝 / 22px 气泡与输入卡。
 */

// --- DSH 设计系统常量由 DshTheme.kt 统一提供 ---

/** 消息列布局常量（--dsh-* 变量） */
private val CHAT_CONTENT_WIDTH = 748.dp
private val COMPOSER_MAX_WIDTH = 780.dp
private val COMPOSER_SIDE_CLEARANCE = 16.dp

/** LazyColumn 中非消息条目的 key（锚点计算时排除，不能当作消息索引）。 */
private val NON_MESSAGE_ITEM_KEYS = setOf("load-older", "turn-status", "stopped-badge")

class WorkspaceActivity : ComponentActivity() {

    private var speechRecognizer: SpeechRecognizer? = null
    private val requestAudioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isGranted) {
            Toast.makeText(this, "需要录音权限才能进行语音输入", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        initSpeechRecognizer()

        setContent {
            DshTheme {
                val hosts = remember { HostStore.load(this) }
                // 通知点击直达：hostBaseUrl + sessionId 定位主机与会话
                val targetUrl = intent.getStringExtra("hostBaseUrl")
                val targetSession = intent.getStringExtra("sessionId")
                var currentHost by remember { mutableStateOf(
                    hosts.firstOrNull { it.baseUrl == targetUrl } ?: hosts.firstOrNull()
                ) }

                if (currentHost == null) {
                    EmptyHostScreen(
                        onScan = {
                            startActivity(Intent(this, DevicesActivity::class.java))
                            finish()
                        }
                    )
                } else {
                    WorkspaceScreen(
                        host = currentHost!!,
                        initialSessionId = targetSession,
                        onSwitchHost = {
                            startActivity(
                                Intent(this, DevicesActivity::class.java)
                                    .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                            )
                        },
                        onOpenSettings = {
                            startActivity(Intent(this, SettingsActivity::class.java))
                        },
                        onStartVoiceInput = { onResult ->
                            startVoiceListening(onResult)
                        },
                        onStopVoiceInput = {
                            speechRecognizer?.stopListening()
                        }
                    )
                }
            }
        }
    }

    private fun initSpeechRecognizer() {
        if (SpeechRecognizer.isRecognitionAvailable(this)) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        }
    }

    private fun startVoiceListening(onResult: (String) -> Unit) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.CHINESE.toString())
            putExtra(RecognizerIntent.EXTRA_PROMPT, "正在倾听...")
        }

        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {
                Toast.makeText(this@WorkspaceActivity, "语音识别未听清，请重试", Toast.LENGTH_SHORT).show()
            }
            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    onResult(matches[0])
                }
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        speechRecognizer?.startListening(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        speechRecognizer?.destroy()
    }
}

// ---------- 工作台主界面 ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspaceScreen(
    host: Host,
    initialSessionId: String? = null,
    onSwitchHost: () -> Unit,
    onOpenSettings: () -> Unit,
    onStartVoiceInput: ((String) -> Unit) -> Unit,
    onStopVoiceInput: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val client = remember(host) { MobileApiClient(host) }

    var sessions by remember { mutableStateOf<List<MobileSession>>(emptyList()) }
    var currentSessionId by remember { mutableStateOf(initialSessionId) }
    var messages by remember { mutableStateOf<List<MobileMessage>>(emptyList()) }
    var olderMessages by remember { mutableStateOf<List<MobileMessage>>(emptyList()) }
    var sessionStats by remember { mutableStateOf<MobileSessionStats?>(null) }
    var hasMoreMessages by remember { mutableStateOf(false) }
    var nextBeforeSeq by remember { mutableStateOf<Long?>(null) }
    var stoppedReason by remember { mutableStateOf<String?>(null) }
    var isLoadingOlder by remember { mutableStateOf(false) }
    // 首次加载（当前会话有内容前的第一条 history 请求 in-flight）：驱动 ChatLoadingSkeleton
    var initialLoadInFlight by remember { mutableStateOf(false) }
    // 工具调用查找（客户端过滤当前会话工具消息；toolQuery 为瞬时视图状态，不持久化）
    var toolSearchOpen by remember { mutableStateOf(false) }
    var toolQuery by remember { mutableStateOf("") }
    var modelCatalog by remember { mutableStateOf<MobileModelCatalog?>(null) }
    var isSending by remember { mutableStateOf(false) }
    var inputText by remember { mutableStateOf("") }
    var isListening by remember { mutableStateOf(false) }
    // composer 焦点请求器：本地 Insertable（如 /plan /goal /subagent）picker 选中后重新聚焦输入框
    val composerFocusRequester = remember { androidx.compose.ui.focus.FocusRequester() }
    val composerKeyboardController = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    var showModelPicker by remember { mutableStateOf(false) }
    // showViewOptions removed — view toggle is inline in sidebar
    var showAddWorkspace by remember { mutableStateOf(false) }
    var showPermissionPicker by remember { mutableStateOf(false) }
    var archivedIds by remember { mutableStateOf(
        context.getSharedPreferences("dsh_workspace", android.content.Context.MODE_PRIVATE)
            .getStringSet("archived_sessions", emptySet()) ?: emptySet()
    ) }
    var deletedIds by remember { mutableStateOf(
        context.getSharedPreferences("dsh_workspace", android.content.Context.MODE_PRIVATE)
            .getStringSet("deleted_sessions", emptySet()) ?: emptySet()
    ) }
    fun setDeleted(id: String) {
        val next = deletedIds + id
        deletedIds = next
        context.getSharedPreferences("dsh_workspace", android.content.Context.MODE_PRIVATE)
            .edit().putStringSet("deleted_sessions", next).apply()
    }
    fun setArchived(id: String) {
        val current = archivedIds.toMutableSet()
        if (id in current) {
            current -= id
        } else {
            current += id
        }
        archivedIds = current
        context.getSharedPreferences("dsh_workspace", android.content.Context.MODE_PRIVATE)
            .edit().putStringSet("archived_sessions", current).apply()
    }
    val workspacePrefs = remember { WorkspacePrefs(context) }
    var flatView by remember { mutableStateOf(workspacePrefs.flatView) }
    var sessionFilter by remember { mutableStateOf(workspacePrefs.sessionFilter) }
    var collapsedWorkspaces by remember { mutableStateOf(setOf<String>()) }
    var expandedGroups by remember { mutableStateOf(setOf<String>()) } // 组内"显示全部"展开态
    var deleteWorkspaceTarget by remember { mutableStateOf<String?>(null) } // 待删除的工作区路径
    var deleteSessionTarget by remember { mutableStateOf<MobileSession?>(null) } // 待删除的会话
    // 本地已删除工作区（服务端删注册后会话 cwd 仍在，分组时这些会话归"未分组"）
    var deletedWorkspaces by remember { mutableStateOf(
        context.getSharedPreferences("dsh_workspace", android.content.Context.MODE_PRIVATE)
            .getStringSet("deleted_workspaces", emptySet()) ?: emptySet()
    ) }
    fun setDeletedWorkspace(path: String) {
        val next = deletedWorkspaces + path
        deletedWorkspaces = next
        context.getSharedPreferences("dsh_workspace", android.content.Context.MODE_PRIVATE)
            .edit().putStringSet("deleted_workspaces", next).apply()
    }
    var renameTarget by remember { mutableStateOf<MobileSession?>(null) }
    var heroMode by remember { mutableStateOf(HeroMode.CHAT) }
    // viewMode：chat / trace / history（全局；持久化到 SharedPreferences，仅 tab 切回记忆上次）
    val tabPrefs = remember {
        context.getSharedPreferences("dsh_workspace_tabs", android.content.Context.MODE_PRIVATE)
    }
    var viewMode by remember {
        mutableStateOf(tabPrefs.getString("view_mode", "chat") ?: "chat")
    }
    fun selectViewMode(mode: String) {
        viewMode = mode
        tabPrefs.edit().putString("view_mode", mode).apply()
    }
    // searchQuery：仅持久化搜索文本，不持久化结果（结果每次进入 history 视图重新拉取）
    var searchQuery by remember {
        mutableStateOf(tabPrefs.getString("history_query", "") ?: "")
    }
    var searchResults by remember { mutableStateOf<List<MobileSearchResult>>(emptyList()) }
    // 搜索状态机：用于 HistoryView 渲染 loading/empty/error/recovery
    var searchState by remember { mutableStateOf<SearchUiState>(SearchUiState.Idle) }
    // 异步搜索任务句柄（最近一次 in-flight 搜索）：用于取消与丢弃过期响应
    var searchJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var searchSeq by remember { mutableStateOf(0) } // 单调递增；过期响应按 seq 丢弃
    var pendingImages by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) } // (mediaType, base64)
    val imagePickerLauncher = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri: android.net.Uri? ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                try {
                    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    if (bytes != null && bytes.size < 8 * 1024 * 1024) {
                        val mediaType = context.contentResolver.getType(uri) ?: "image/jpeg"
                        val b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                        withContext(Dispatchers.Main) {
                            pendingImages = pendingImages + (mediaType to b64)
                        }
                    }
                } catch (e: Exception) {}
            }
        }
    }
    val listState = rememberLazyListState()
    var elapsedSec by remember { mutableStateOf(0L) }
    // WI-003：尾部定位请求（每次自增触发一次"等待布局后再滚底"）；0 表示无请求
    var tailRequestId by remember { mutableStateOf(0) }
    var lastTailRequestId by remember { mutableStateOf(0) }
    // 用户上翻阅读时新消息到达 → 显示"回到底部"（不强行拉回尾部）
    var showScrollToBottom by remember { mutableStateOf(false) }
    // WI-004：服务端设置为配置源（默认 Agent 预设/权限等），启动与回前台时刷新
    var appSettings by remember { mutableStateOf(AppSettingsStore.cached(context)) }
    fun refreshAppSettings() {
        scope.launch(Dispatchers.IO) {
            try {
                val loaded = AppSettingsStore.fetch(host, context)
                withContext(Dispatchers.Main) { appSettings = loaded }
            } catch (e: Exception) {}
        }
    }

    // ===== SSE 实时流（当前会话；切换会话时重建） =====
    val streamClient = remember(host, currentSessionId) {
        currentSessionId?.let { SessionStreamClient(host, it, scope) }
    }
    // SSE 事件驱动的执行状态（turn/start→true，turn/end→false；与 session.list 的 running 取或）
    var liveRunning by remember { mutableStateOf(false) }
    // 最近一次全量刷新覆盖到的最大事件 seq（ready 时用于检测服务端游标领先）
    var seedMaxSeq by remember { mutableStateOf(0L) }
    // 增量构建状态：tool/call 时间（算 tool/result 耗时）、流式 tool_call 消息 id → callId
    val toolCallTimes = remember(currentSessionId) { mutableMapOf<String, Long>() }
    val streamToolCallIds = remember(currentSessionId) { mutableMapOf<String, String>() }

    fun refreshSessions(selectLatest: Boolean = false) {
        scope.launch(Dispatchers.IO) {
            try {
                val list = client.getSessions()
                withContext(Dispatchers.Main) {
                    sessions = list
                    if (currentSessionId == null || !list.any { it.sessionId == currentSessionId }) {
                        currentSessionId = list.firstOrNull()?.sessionId
                    }
                }
            } catch (e: Exception) {}
        }
    }

    /** 用户是否停留在列表底部附近（用于决定是否跟随新消息 / 显示回到底部）。 */
    fun isNearBottom(): Boolean {
        val total = listState.layoutInfo.totalItemsCount
        if (total == 0) return true
        val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: return true
        return lastVisible >= total - 2
    }

    /** 请求一次"等待布局完成后滚动到尾部"（并发合并为一次）。 */
    fun requestTailPosition() {
        tailRequestId++
    }

    /** 立即滚到列表末尾；失败写入可检索日志。 */
    fun scrollToTail(tag: String) {
        val total = listState.layoutInfo.totalItemsCount
        if (total == 0) return
        scope.launch {
            try {
                listState.scrollToItem(total - 1)
            } catch (e: Exception) {
                Log.w("WorkspaceActivity", "scrollToTail($tag) failed: ${e.message}")
            }
        }
    }

    /**
     * 用户停在底部附近时跟随新消息（WI-003）；已上翻阅读时不强行拉回尾部，
     * 而是显示"回到底部"。
     */
    fun followIfNearBottom() {
        if (viewMode != "chat") return
        if (listState.isScrollInProgress) return
        val total = messages.size
        if (total == 0) return
        if (isNearBottom()) {
            requestTailPosition()
        } else {
            showScrollToBottom = true
        }
    }

    fun refreshMessages(autoScroll: Boolean = false) {
        val sid = currentSessionId ?: return
        if (messages.isEmpty()) initialLoadInFlight = true
        scope.launch(Dispatchers.IO) {
            try {
                val result = client.getSessionHistory(sid)
                val msgs = result.messages
                val stats = result.stats
                withContext(Dispatchers.Main) {
                    // 会话已切换：丢弃过期响应，避免旧会话内容覆盖新会话
                    if (currentSessionId != sid) return@withContext
                    // 内容未变化时保持列表引用稳定（避免轮询在滚动中替换数据源导致
                    // LazyColumn 渲染冻结——Compose 已知问题）；签名覆盖中间状态变化
                    val sameContent = messages.contentSignature() == msgs.contentSignature()
                    if (!sameContent) messages = msgs
                    sessionStats = stats
                    hasMoreMessages = result.hasMore
                    nextBeforeSeq = result.nextBeforeSeq
                    stoppedReason = result.stoppedReason
                    // 登记本页最新事件 seq，作为 SSE 增量去重基线
                    result.maxSeq?.let { maxSeq ->
                        seedMaxSeq = maxOf(seedMaxSeq, maxSeq)
                        streamClient?.noteSeedMaxSeq(maxSeq)
                    }
                    // 回前台/手动刷新：仅用户停在底部附近时跟随新尾部（WI-003）
                    if (autoScroll && !sameContent) followIfNearBottom()
                }
            } catch (e: Exception) {
                Log.w("WorkspaceActivity", "refreshMessages($sid) failed: ${e.message}")
            } finally {
                withContext(Dispatchers.Main) { initialLoadInFlight = false }
            }
        }
    }

    /** 加载更早（DSH loadOlder：按 beforeSeq 向前翻一页，prepend 到消息流，并恢复阅读锚点）。 */
    fun loadOlderMessages() {
        val sid = currentSessionId ?: return
        val before = nextBeforeSeq ?: return
        if (isLoadingOlder) return
        isLoadingOlder = true
        // 锚点：当前第一个可见"消息"（排除加载行/状态行）的 id 与视口偏移
        val anchor = listState.layoutInfo.visibleItemsInfo
            .firstOrNull { it.key !in NON_MESSAGE_ITEM_KEYS }
            ?.let { info -> (info.key as? String) to info.offset }
        scope.launch(Dispatchers.IO) {
            try {
                val result = client.getSessionHistory(sid, beforeSeq = before, maxMessages = 100)
                val older = result.messages
                val anchorOffset = anchor?.second ?: 0
                withContext(Dispatchers.Main) {
                    if (currentSessionId != sid) return@withContext
                    // 更旧页必须插到现有 olderMessages 前面（WI-002），按稳定 id 去重
                    olderMessages = mergeHistoryPages(older, olderMessages)
                    hasMoreMessages = result.hasMore
                    nextBeforeSeq = result.nextBeforeSeq
                    // 恢复锚点：新页插入后，同一消息回到原偏移
                    val anchorIndex = anchor?.first?.let { id ->
                        (olderMessages + messages).indexOfFirst { it.id == id }
                    } ?: -1
                    if (anchorIndex >= 0 && !listState.isScrollInProgress) {
                        try {
                            listState.scrollToItem(anchorIndex, anchorOffset)
                        } catch (e: Exception) {
                            Log.w("WorkspaceActivity", "loadOlder anchor restore failed: ${e.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w("WorkspaceActivity", "loadOlderMessages($sid) failed: ${e.message}")
            }
            withContext(Dispatchers.Main) {
                if (currentSessionId == sid) isLoadingOlder = false
            }
        }
    }

    fun refreshModels() {
        val sid = currentSessionId ?: return
        scope.launch(Dispatchers.IO) {
            try {
                val catalog = client.getModels(sid)
                withContext(Dispatchers.Main) { modelCatalog = catalog }
            } catch (e: Exception) {}
        }
    }

    /**
     * Debounced 搜索：250ms 节流用户输入；多次调用取消前次 in-flight 任务
     * 并丢弃过期响应。仅当 query 非空且稳定 250ms 才发请求。
     *
     * 状态机：
     *  - query 空白 → Idle / 清空结果
     *  - query 非空 + 250ms 内变更 → Loading
     *  - 返回成功 → Results（行数=0 时退化为 Empty）
     *  - 抛错 → Error(message)；再次输入自动恢复
     */
    fun runSearchDebounced(rawQuery: String) {
        // 撤销上次的等待/请求
        searchJob?.cancel()
        val query = rawQuery
        // 持久化搜索文本（不持久化结果）
        tabPrefs.edit().putString("history_query", query).apply()
        if (query.isBlank()) {
            searchResults = emptyList()
            searchState = SearchUiState.Idle
            return
        }
        searchSeq++
        val seq = searchSeq
        searchState = SearchUiState.Loading
        searchJob = scope.launch(Dispatchers.IO) {
            try {
                kotlinx.coroutines.delay(250) // debounce：250ms 内多次输入只发最后一次
                if (seq != searchSeq) return@launch
                val results = client.searchSessions(query)
                if (seq != searchSeq) return@launch // 过期响应丢弃
                withContext(Dispatchers.Main) {
                    searchResults = results
                    searchState = if (results.isEmpty()) SearchUiState.Empty
                    else SearchUiState.Results(query, results.size)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (seq != searchSeq) return@launch
                withContext(Dispatchers.Main) {
                    searchState = SearchUiState.Error(e.message ?: "搜索失败")
                }
            }
        }
    }

    /** 进入 history 视图时主动把持久化的 query 重新拉一次（global history：跨 Activity 重建持久） */
    LaunchedEffect(viewMode) {
        if (viewMode == "history") {
            // 进入即触发一次搜索：用持久化 query 让结果恢复
            runSearchDebounced(searchQuery)
        }
    }

    // 会话筛选/平铺分组持久化（dsh_workspace；重启 App 后恢复上次选择）
    LaunchedEffect(flatView) { workspacePrefs.flatView = flatView }
    LaunchedEffect(sessionFilter) { workspacePrefs.sessionFilter = sessionFilter }

    /**
     * 分发 palette 的本地动作。
     * 服务端永远不会看到这些 trigger —— 只是把 picker 当作 slash 风格的快捷入口。
     */
    fun dispatchLocalPaletteAction(kind: LocalKind) {
        when (kind) {
            LocalKind.SEARCH_SESSIONS -> {
                selectViewMode("history")
                composerKeyboardController?.hide()
            }
            LocalKind.NEW_SESSION -> {
                scope.launch(Dispatchers.IO) {
                    try {
                        val newId = client.createSession(agentPreset = appSettings.agentPreset)
                        withContext(Dispatchers.Main) {
                            currentSessionId = newId
                            refreshSessions()
                            selectViewMode("chat")
                        }
                    } catch (e: Exception) {}
                }
            }
            LocalKind.OPEN_SETTINGS -> {
                // 与顶栏抽屉设置按钮共享入口；CONSUMED 显示可以放在 picker 选中后的 toast 中
                onOpenSettings()
            }
            LocalKind.SWITCH_CHAT -> selectViewMode("chat")
            LocalKind.SWITCH_TRACE -> selectViewMode("trace")
            LocalKind.OPEN_MODEL_PICKER -> {
                refreshModels()
                showModelPicker = true
            }
            LocalKind.OPEN_PERMISSION_PICKER -> showPermissionPicker = true
        }
    }

    // ===== 前台状态 + 通知（审批/任务完成仅在后台打扰） + 回前台刷新 =====
    var isForeground by remember { mutableStateOf(true) }
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, currentSessionId, streamClient) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_START -> isForeground = true
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> isForeground = false
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> {
                    // 回前台：SSE 断线时立即补全消息，并刷新会话列表（移动网络切换场景）
                    if (streamClient?.isConnected != true) refreshMessages(autoScroll = true)
                    refreshSessions()
                    refreshAppSettings() // 设置可能在其他端修改，回前台重新读取
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val notifPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }
    LaunchedEffect(Unit) {
        DshNotifier.ensureChannel(context)
        val prefs = context.getSharedPreferences("dsh_settings", android.content.Context.MODE_PRIVATE)
        if (!prefs.getBoolean("notif_permission_asked", false) &&
            android.os.Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            prefs.edit().putBoolean("notif_permission_asked", true).apply()
            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    fun createSessionIn(cwd: String?) {
        scope.launch(Dispatchers.IO) {
            try {
                val newId = client.createSession(agentPreset = appSettings.agentPreset, cwd = cwd)
                withContext(Dispatchers.Main) {
                    currentSessionId = newId
                    refreshSessions()
                }
            } catch (e: Exception) {}
        }
    }

    // ===== SSE 事件 → 增量构建消息列表（与 history 端点的消息映射保持一致） =====

    fun appendStreamMessage(m: MobileMessage) {
        // 仅 SSE 流式新增的消息带入场动画；历史/全量刷新（messages = msgs 整体替换）不带，
        // 避免会话切换、tab 切回、首屏加载时整列重放进场动画
        messages = messages + m.copy(entrance = true)
        followIfNearBottom()
    }

    fun updateStreamMessage(id: String, transform: (MobileMessage) -> MobileMessage) {
        val i = messages.indexOfFirst { it.id == id }
        if (i >= 0) {
            messages = messages.toMutableList().apply { this[i] = transform(this[i]) }
        }
    }

    fun handleStreamChunk(item: SessionStreamClient.Item.Message) {
        val chunk = item.data.optJSONObject("chunk") ?: return
        val type = chunk.optString("type")
        val turn = item.data.optInt("turn")
        val index = chunk.optInt("index")
        when (type) {
            "reasoning-delta" -> {
                val piece = chunk.optString("text")
                val id = "reason-$turn-$index"
                val i = messages.indexOfLast { it.id == id }
                if (i >= 0) updateStreamMessage(id) { it.copy(text = it.text + piece) }
                else appendStreamMessage(MobileMessage(id = id, role = "reasoning", text = piece, time = item.time, type = "reasoning", running = true))
            }
            "text-delta" -> {
                val piece = chunk.optString("text")
                val id = "msg-stream-$turn-$index"
                val i = messages.indexOfLast { it.id == id }
                if (i >= 0) updateStreamMessage(id) { it.copy(text = it.text + piece) }
                else appendStreamMessage(MobileMessage(id = id, role = "assistant", text = piece, time = item.time, type = "text", running = true))
            }
            "tool-call-delta" -> {
                val delta = chunk.optString("argumentsDelta")
                val name = chunk.optString("name")
                val callId = chunk.optString("id")
                val id = "tool-stream-$turn-$index"
                val i = messages.indexOfLast { it.id == id }
                if (i >= 0) {
                    updateStreamMessage(id) {
                        it.copy(toolArgs = it.toolArgs + delta, toolName = name.ifBlank { it.toolName })
                    }
                } else {
                    appendStreamMessage(MobileMessage(id = id, role = "tool_call", text = "", toolName = name.takeIf { it.isNotBlank() },
                        toolArgs = delta, time = item.time, type = "tool_call", running = true))
                }
                if (callId.isNotBlank()) streamToolCallIds[id] = callId
            }
            "block-end" -> {
                val block = chunk.optJSONObject("block") ?: return
                when (block.optString("type")) {
                    "reasoning" -> {
                        val id = "reason-$turn-$index"
                        val i = messages.indexOfLast { it.id == id }
                        if (i >= 0) updateStreamMessage(id) { it.copy(text = block.optString("text").ifBlank { it.text }, running = false) }
                        else appendStreamMessage(MobileMessage(id = id, role = "reasoning", text = block.optString("text"), time = item.time, type = "reasoning"))
                    }
                    "text" -> {
                        val id = "msg-stream-$turn-$index"
                        val i = messages.indexOfLast { it.id == id }
                        if (i >= 0) updateStreamMessage(id) { it.copy(text = block.optString("text").ifBlank { it.text }, running = false) }
                        else appendStreamMessage(MobileMessage(id = id, role = "assistant", text = block.optString("text"), time = item.time, type = "text"))
                    }
                    "tool-call" -> {
                        val id = "tool-stream-$turn-$index"
                        val callId = block.optString("id")
                        val i = messages.indexOfLast { it.id == id }
                        if (i >= 0) {
                            updateStreamMessage(id) {
                                it.copy(
                                    toolName = block.optString("name").ifBlank { it.toolName },
                                    toolArgs = block.optString("arguments").ifBlank { it.toolArgs },
                                    running = false,
                                )
                            }
                        } else {
                            appendStreamMessage(MobileMessage(id = id, role = "tool_call", text = "",
                                toolName = block.optString("name"), toolArgs = block.optString("arguments"), time = item.time, type = "tool_call"))
                        }
                        if (callId.isNotBlank()) streamToolCallIds[id] = callId
                        toolCallTimes[callId] = item.time
                    }
                }
            }
        }
    }

    fun handleStreamMessage(item: SessionStreamClient.Item.Message) {
        val data = item.data
        when (item.type) {
            "turn/start" -> {
                liveRunning = true
                stoppedReason = null
            }
            "turn/end" -> {
                liveRunning = false
                val kind = data.optJSONObject("reason")?.optString("kind")
                stoppedReason = if (!kind.isNullOrBlank() && kind != "completed") kind else null
            }
            "user/message" -> {
                val text = (data.optJSONArray("content") ?: org.json.JSONArray()).let { arr ->
                    (0 until arr.length()).joinToString("") { i ->
                        arr.optJSONObject(i)?.optString("text").orEmpty()
                    }
                }
                appendStreamMessage(MobileMessage(id = "msg-${item.seq}", role = "user", text = text, time = item.time, type = "text"))
            }
            "assistant/chunk" -> handleStreamChunk(item)
            "assistant/message" -> {
                val content = data.optJSONObject("message")?.optJSONArray("content") ?: return
                for (i in 0 until content.length()) {
                    val block = content.optJSONObject(i) ?: continue
                    if (block.optString("type") != "text") continue
                    val text = block.optString("text").trim()
                    if (text.isEmpty()) continue
                    // delta 增量文本块与最终 message 的文本块内容相同，跳过重复（补一句收尾定稿）
                    val last = messages.lastOrNull()
                    if (last?.role == "assistant" && last.text == text) {
                        updateStreamMessage(last.id) { it.copy(running = false) }
                        continue
                    }
                    appendStreamMessage(MobileMessage(id = "msg-${item.seq}-${messages.size}", role = "assistant", text = text, time = item.time, type = "text"))
                }
            }
            "tool/call" -> {
                val callId = data.optString("callId")
                val streamId = streamToolCallIds.entries.firstOrNull { it.value == callId }?.key
                if (streamId != null) {
                    updateStreamMessage(streamId) {
                        it.copy(
                            toolName = data.optString("name").ifBlank { it.toolName },
                            toolArgs = data.optString("arguments").ifBlank { it.toolArgs },
                            running = false,
                        )
                    }
                } else {
                    appendStreamMessage(MobileMessage(id = "tool-${item.seq}", role = "tool_call", text = "",
                        toolName = data.optString("name"), toolArgs = data.optString("arguments"), time = item.time, type = "tool_call"))
                }
                toolCallTimes[callId] = item.time
            }
            "tool/result" -> {
                val message = data.optJSONObject("message")
                val callId = message?.optJSONObject("source")?.optString("callId")
                val start = callId?.let { toolCallTimes[it] }
                val content = (message?.optJSONArray("content") ?: org.json.JSONArray()).let { arr ->
                    (0 until arr.length()).joinToString("\n") { i ->
                        val b = arr.optJSONObject(i)
                        val t = b?.optString("text")
                        if (!t.isNullOrBlank()) t else b?.toString().orEmpty()
                    }
                }
                appendStreamMessage(MobileMessage(id = "tool-res-${item.seq}", role = "tool_result", text = content,
                    time = item.time, type = "tool_result",
                    durationMs = if (start != null && item.time > start) item.time - start else null))
            }
            "todo/write" -> {
                val todosArr = data.optJSONArray("todos") ?: return
                val todos = (0 until todosArr.length()).map { j ->
                    val t = todosArr.getJSONObject(j)
                    MobileTodoItem(t.optString("content", ""), t.optString("status", "pending"))
                }
                appendStreamMessage(MobileMessage(id = "todo-${item.seq}", role = "todo", text = "", todos = todos, time = item.time, type = "todo"))
            }
            "approval/asked" -> {
                appendStreamMessage(MobileMessage(
                    id = "approval-${item.seq}",
                    role = "approval",
                    text = data.optString("reason").ifBlank { "请求授权执行 ${data.optString("toolName", "工具")}" },
                    toolName = data.optString("toolName", "tool"),
                    approvalId = data.optString("id", ""),
                    time = item.time,
                    type = "approval",
                ))
                // 后台时系统通知提醒（前台有审批卡）
                val sid = currentSessionId
                if (!isForeground && sid != null) {
                    DshNotifier.notifyApproval(context, host, sid, data.optString("toolName", "工具"))
                }
            }
            "compaction/start" -> {
                appendStreamMessage(MobileMessage(id = "compact-${item.seq}", role = "compaction", text = "", running = true, time = item.time, type = "compaction"))
            }
            "compaction/summary" -> {
                val summaryText = (data.optJSONArray("summary") ?: org.json.JSONArray()).let { arr ->
                    (0 until arr.length()).joinToString("\n") { i -> arr.optJSONObject(i)?.optString("text").orEmpty() }
                }.trim()
                val lastIndex = messages.indexOfLast { it.role == "compaction" }
                if (lastIndex >= 0) {
                    updateStreamMessage(messages[lastIndex].id) { it.copy(text = summaryText, running = false) }
                } else {
                    appendStreamMessage(MobileMessage(id = "compact-${item.seq}", role = "compaction", text = summaryText, running = false, time = item.time, type = "compaction"))
                }
            }
            "compaction/end" -> {
                val lastIndex = messages.indexOfLast { it.role == "compaction" }
                if (lastIndex >= 0) {
                    updateStreamMessage(messages[lastIndex].id) { it.copy(running = false) }
                }
            }
        }
    }

    /** assistant/chunk 增量块：delta 追加到 (turn,index) 流式消息，block-end 定稿（权威全文）。 */
    fun applyStreamItem(item: SessionStreamClient.Item) {
        when (item) {
            is SessionStreamClient.Item.Ready -> {
                // 服务端游标领先本地 seed（多设备/服务端重启后丢状态）：补一次全量刷新对齐
                if (seedMaxSeq > 0 && item.resumeSeq > seedMaxSeq) {
                    refreshMessages(autoScroll = false)
                }
            }
            is SessionStreamClient.Item.Disconnected -> {}
            is SessionStreamClient.Item.Stats -> {
                sessionStats = parseMobileSessionStats(item.projections)
            }
            is SessionStreamClient.Item.Message -> handleStreamMessage(item)
        }
    }

    LaunchedEffect(host) {
        refreshSessions(selectLatest = true)
        refreshAppSettings()
    }

    LaunchedEffect(currentSessionId) {
        currentSessionId?.let { DshNotifier.cancelForSession(context, it) }
        if (currentSessionId != null) {
            // WI-003：会话切换必须清空上一会话的消息/分页/临时加载状态，
            // 避免旧列表短暂残留或位置继承
            messages = emptyList()
            olderMessages = emptyList()
            hasMoreMessages = false
            nextBeforeSeq = null
            stoppedReason = null
            liveRunning = false
            seedMaxSeq = 0L
            toolCallTimes.clear()
            streamToolCallIds.clear()
            showScrollToBottom = false
            tailRequestId++ // 新数据提交并完成布局后再定位到尾部
            refreshMessages()
            refreshModels()
            // SSE 实时流：订阅事件，增量更新消息列表；会话切换/离开时停止
            val client = streamClient ?: return@LaunchedEffect
            client.start()
            try {
                for (item in client.items) applyStreamItem(item)
            } finally {
                client.stop()
            }
        }
    }

    // WI-003：尾部定位必须在 Compose 提交新数据并完成布局之后执行；
    // 请求期间列表为空或正在滚动则等待，定位失败写入可检索日志
    LaunchedEffect(listState, tailRequestId, messages) {
        if (tailRequestId == 0 || tailRequestId == lastTailRequestId) return@LaunchedEffect
        if (messages.isEmpty()) return@LaunchedEffect // 数据未到，等消息变化后重入
        lastTailRequestId = tailRequestId
        try {
            withTimeoutOrNull(3000) {
                snapshotFlow { listState.layoutInfo.totalItemsCount }
                    .first { it > 0 && !listState.isScrollInProgress }
            }
            withFrameNanos { } // 等一帧，让条目完成布局
            scrollToTail("request-$tailRequestId")
        } catch (e: Exception) {
            Log.w("WorkspaceActivity", "tail position request-$tailRequestId failed: ${e.message}")
        }
    }

    // 用户滚回底部时隐藏"回到底部"提示
    LaunchedEffect(listState, currentSessionId) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .collect { last ->
                val total = listState.layoutInfo.totalItemsCount
                if (total > 0 && last >= total - 2) showScrollToBottom = false
            }
    }

    // 轮询（DSH 移动端简化：2s）：SSE 活跃时消息走实时流，仅断开时回退轮询。
    // 后台（缓存态）暂停轮询：Activity 停驻后台时 LaunchedEffect 仍在运行，
    // 持续网络 + binder 流量会被系统以 excessive binder traffic 回收进程（通知将失效）
    LaunchedEffect(currentSessionId) {
        while (true) {
            delay(2000)
            if (currentSessionId != null && isForeground) {
                refreshSessions()
                if (streamClient?.isConnected != true) {
                    refreshMessages(autoScroll = false)
                }
            }
        }
    }

    val currentSession = sessions.find { it.sessionId == currentSessionId }
    val running = liveRunning || currentSession?.running == true

    // 会话结束通知（仅后台；正常完成 → 任务完成，非正常 → 已停止）
    var wasRunning by remember { mutableStateOf(false) }
    LaunchedEffect(running) {
        if (!running && wasRunning && !isForeground && currentSessionId != null) {
            val title = currentSession?.title ?: "会话"
            if (stoppedReason.isNullOrBlank()) {
                DshNotifier.notifyTaskDone(context, host, currentSessionId!!, title)
            } else {
                DshNotifier.notifyTaskFailed(context, host, currentSessionId!!, title, stoppedReason!!)
            }
        }
        wasRunning = running
    }

    // 正在执行时的计时器（从 running 变 true 起算）
    var turnStartTime by remember { mutableStateOf(0L) }
    LaunchedEffect(running) {
        if (running) turnStartTime = System.currentTimeMillis()
        while (true) {
            delay(1000)
            elapsedSec = if (running) (System.currentTimeMillis() - turnStartTime) / 1000 else 0L
        }
    }

    // ===== 整体框架：侧边栏(抽屉) + 主区 =====
    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = Dsh.bgSidebar,
                drawerContentColor = Dsh.labelPrimary,
                modifier = Modifier.width(300.dp)
            ) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                        .padding(top = 8.dp)
                ) {
                    // 品牌行：侧栏填充（对应 sidebar brand row）
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            "DeepSeek Harness",
                            color = Dsh.labelPrimary,
                            fontSize = 16.sp,
                            fontWeight = FontWeight(500)
                        )
                        IconButton(
                            onClick = onSwitchHost,
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                Icons.Default.Devices,
                                contentDescription = "切换设备",
                                tint = Dsh.labelSecondary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }

                    // 新建会话按钮（DSH newSession：图标 + 文案）
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                scope.launch(Dispatchers.IO) {
                                    try {
                                        val newId = client.createSession(agentPreset = appSettings.agentPreset)
                                        withContext(Dispatchers.Main) {
                                            currentSessionId = newId
                                            refreshSessions()
                                            drawerState.close()
                                        }
                                    } catch (e: Exception) {}
                                }
                            }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            PlusOutline16,
                            contentDescription = null,
                            tint = Dsh.labelSecondary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("新会话", color = Dsh.labelSecondary, fontSize = 13.sp, fontWeight = FontWeight(500), lineHeight = 20.sp)
                    }

                    Spacer(Modifier.height(4.dp))

                    // 视图选项行（分组方式：点击当前弹出 / 添加工作区）
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box {
                            Row(
                                modifier = Modifier
                                    .height(28.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { flatView = !flatView }
                                    .padding(horizontal = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    SettingsOutline16,
                                    contentDescription = "视图选项",
                                    tint = Dsh.labelTertiary,
                                    modifier = Modifier.size(14.dp)
                                )
                        Spacer(Modifier.width(6.dp))
                            Text(
                                if (flatView) "单列表" else "按工作区",
                                color = Dsh.labelSecondary,
                                fontSize = 12.sp,
                                lineHeight = 18.sp
                            )
                        }
                    }
                        Spacer(Modifier.weight(1f))
                        Row(
                            modifier = Modifier
                                .height(28.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { showAddWorkspace = true }
                                .padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                PlusOutline16,
                                contentDescription = "添加工作区",
                                tint = Dsh.labelTertiary,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("添加工作区", color = Dsh.labelSecondary, fontSize = 12.sp, lineHeight = 18.sp)
                        }
                    }
                    Spacer(Modifier.height(2.dp))

                    // 会话过滤 chip：3 个状态（全部 / 运行中 / 已停止）
                    // counts 基于"已过滤 archived/deleted/subagent/staleBlank 后的活跃会话"，与下方列表数据源一致
                    val staleCutoffForFilter = System.currentTimeMillis() - 24 * 3600_000
                    val filterCandidates = remember(sessions, archivedIds, deletedIds) {
                        sessions.filter {
                            it.sessionId !in archivedIds && it.sessionId !in deletedIds &&
                                it.origin != "subagent" &&
                                !(it.blank && it.updatedAt < staleCutoffForFilter)
                        }
                    }
                    val filterCounts = remember(filterCandidates) {
                        mapOf(
                            SessionFilter.ALL to filterCandidates.size,
                            SessionFilter.RUNNING to filterCandidates.count { classifySession(it) == SessionFilter.RUNNING },
                            SessionFilter.STOPPED to filterCandidates.count { classifySession(it) == SessionFilter.STOPPED }
                        )
                    }
                    SessionFilterChips(
                        selected = sessionFilter,
                        counts = filterCounts,
                        onSelect = { sessionFilter = it }
                    )

                    // 会话列表（Rows.module.css：32dp 行 / 8dp 圆角 / 状态点槽 / 时间 / 工作区分组）
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        if (flatView) {
                            // 单列表（不分组）—— 过滤已归档/已删除（空白超24h）/子智能体/sessionFilter
                            val visibleSessions = filterCandidates.filter {
                                sessionFilter == SessionFilter.ALL || classifySession(it) == sessionFilter
                            }
                            items(visibleSessions, key = { it.sessionId }) { s ->
                                AnimatedVisibility(
                                    visible = true,
                                    enter = fadeIn(animationSpec = tween(180)) + slideInHorizontally(animationSpec = tween(220, easing = FastOutSlowInEasing), initialOffsetX = { -it }),
                                    modifier = Modifier.animateItem()
                                ) {
                                SessionRowItem(
                                    session = s,
                                    isSelected = s.sessionId == currentSessionId,
                                    onClick = {
                                        currentSessionId = s.sessionId
                                        scope.launch { drawerState.close() }
                                    },
                                    onRename = { renameTarget = s },
                                    onArchive = { setArchived(s.sessionId) },
                                    onDelete = { deleteSessionTarget = s },
                                    onFork = {
                                        scope.launch(Dispatchers.IO) {
                                            try {
                                                val newId = client.forkSession(s.sessionId)
                                                withContext(Dispatchers.Main) {
                                                    if (newId != null) {
                                                        currentSessionId = newId
                                                        refreshSessions()
                                                        drawerState.close()
                                                    }
                                                }
                                            } catch (e: Exception) {}
                                        }
                                    },
                                )
                                }
                            }
                        } else {
                            // 按工作区（cwd）分组：组头点击 = 在该工作区新建会话，chevron = 折叠
                            // 过滤系统/隐藏目录（DSH workspace 列表只显示用户工作区）
                            // 过滤已归档/已删除（空白超24h）/子智能体/sessionFilter；组内默认预览 5 条
                            // 已删除工作区的会话归"未分组"（服务端只删注册，cwd 仍在会话上）
                            val grouped = filterCandidates.filter {
                                sessionFilter == SessionFilter.ALL || classifySession(it) == sessionFilter
                            }
                                .groupBy { it.cwd?.takeUnless { c -> c in deletedWorkspaces } }
                                .filterKeys { isUserWorkspace(it) }
                            grouped.forEach { (cwd, groupSessions) ->
                                val collapsed = collapsedWorkspaces.contains(cwd)
                                item(key = "ws-$cwd") {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(34.dp)
                                            .padding(horizontal = 8.dp, vertical = 0.dp)
                                            .padding(horizontal = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        // chevron 单独可点（折叠/展开）
                                        Box(
                                            modifier = Modifier
                                                .size(22.dp)
                                                .clip(RoundedCornerShape(6.dp))
                                                .clickable {
                                                    collapsedWorkspaces = if (collapsed) collapsedWorkspaces - cwd!! else collapsedWorkspaces + cwd!!
                                                },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                if (collapsed) ChevronRightOutline14 else ChevronDownOutline14,
                                                contentDescription = null,
                                                tint = Dsh.labelTertiary,
                                                modifier = Modifier.size(14.dp)
                                            )
                                        }
                                        Spacer(Modifier.width(2.dp))
                                        Icon(
                                            FolderOpenOutline16,
                                            contentDescription = null,
                                            tint = Dsh.labelTertiary,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            cwd?.substringAfterLast('/') ?: "未分组",
                                            color = Dsh.labelPrimary,
                                            fontSize = 14.sp,
                                            lineHeight = 20.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Text(
                                            "${groupSessions.size}",
                                            color = Dsh.labelTertiary,
                                            fontSize = 12.sp,
                                            lineHeight = 20.sp
                                        )
                                        Spacer(Modifier.width(4.dp))
                                        // 新建会话按钮（在当前工作区创建）
                                        Box(
                                            modifier = Modifier
                                                .size(20.dp)
                                                .clip(RoundedCornerShape(5.dp))
                                                .clickable { createSessionIn(cwd) },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                PlusOutline16,
                                                contentDescription = "新建会话",
                                                tint = Dsh.labelTertiary,
                                                modifier = Modifier.size(12.dp)
                                            )
                                        }
                                        // 工作区操作菜单（删除注册；未分组组无对应 workspace 记录，不显示）
                                        if (cwd != null) {
                                            Box(
                                                modifier = Modifier
                                                    .size(22.dp)
                                                    .clip(RoundedCornerShape(6.dp))
                                                    .clickable { deleteWorkspaceTarget = cwd },
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(
                                                    EllipsisOutline16,
                                                    contentDescription = "工作区操作",
                                                    tint = Dsh.labelTertiary,
                                                    modifier = Modifier.size(13.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                                if (!collapsed) {
                                    // 组内默认预览 5 条 + 「显示全部」占位行（对齐 Web UI SessionListPage）
                                    val showMore = groupSessions.size > 5 && cwd !in expandedGroups
                                    val preview = if (showMore) groupSessions.take(5) else groupSessions
                                    val rows: List<MobileSession?> = if (showMore) preview + null else preview
                                    items(rows, key = { it?.sessionId ?: "ws-more-${cwd ?: ""}" }) { s ->
                                        if (s == null) {
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .height(30.dp)
                                                    .padding(horizontal = 8.dp)
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .clickable { expandedGroups = expandedGroups + cwd!! }
                                                    .padding(horizontal = 12.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    "显示全部 ${groupSessions.size} 项",
                                                    color = Dsh.labelTertiary,
                                                    fontSize = 12.sp,
                                                    lineHeight = 18.sp
                                                )
                                            }
                                        } else {
                                            AnimatedVisibility(
                                                visible = true,
                                                enter = fadeIn(animationSpec = tween(180)) + slideInHorizontally(animationSpec = tween(220, easing = FastOutSlowInEasing), initialOffsetX = { -it }),
                                                modifier = Modifier.animateItem()
                                            ) {
                                                SessionRowItem(
                                                    session = s,
                                                    isSelected = s.sessionId == currentSessionId,
                                                    onClick = {
                                                        currentSessionId = s.sessionId
                                                        scope.launch { drawerState.close() }
                                                    },
                                                    onRename = { renameTarget = s },
                                                    onArchive = { setArchived(s.sessionId) },
                                                    onDelete = { deleteSessionTarget = s },
                                                    onFork = {
                                                        scope.launch(Dispatchers.IO) {
                                                            try {
                                                                val newId = client.forkSession(s.sessionId)
                                                                withContext(Dispatchers.Main) {
                                                                    if (newId != null) {
                                                                        currentSessionId = newId
                                                                        refreshSessions()
                                                                        drawerState.close()
                                                                    }
                                                                }
                                                            } catch (e: Exception) {}
                                                        }
                                                },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    }

                    // 底部：设置与主题切换入口
                    Spacer(Modifier.height(4.dp))
                    val isDarkTheme = Dsh.isDark
                    val themeInteraction = remember { MutableInteractionSource() }
                    val themePressed by themeInteraction.collectIsPressedAsState()
                    val settingsInteraction = remember { MutableInteractionSource() }
                    val settingsPressed by settingsInteraction.collectIsPressedAsState()

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 设置按钮
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (settingsPressed) Dsh.hover else Color.Transparent)
                                .clickable(interactionSource = settingsInteraction, indication = null, onClick = onOpenSettings)
                                .padding(horizontal = 8.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                SettingsOutline16,
                                contentDescription = null,
                                tint = Dsh.labelSecondary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("设置", color = Dsh.labelSecondary, fontSize = 13.sp, fontWeight = FontWeight(500), lineHeight = 20.sp)
                        }

                        // 快速切换深浅色模式按钮（带太阳/月亮图标与提示文案）
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (themePressed) Dsh.hover else Color.Transparent)
                                .clickable(interactionSource = themeInteraction, indication = null) {
                                    ThemeManager.toggleTheme(context, isDarkTheme)
                                }
                                .padding(horizontal = 8.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                if (isDarkTheme) LightOutline16 else DarkOutline16,
                                contentDescription = if (isDarkTheme) "切换为浅色" else "切换为深色",
                                tint = Dsh.labelSecondary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                if (isDarkTheme) "浅色" else "深色",
                                color = Dsh.labelSecondary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight(500)
                            )
                        }
                    }
                }
            }
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Dsh.bgBase)
                .statusBarsPadding()
        ) {
            // ===== 顶栏（ChatView header：2 行布局 —— 标题行 + tabs 行） =====
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp, start = 20.dp, end = 28.dp)
                    .padding(bottom = 0.dp)
            ) {
                // Row 1: 菜单按钮 + 标题面包屑 + 停止按钮 + 更多菜单
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 32.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 菜单按钮（28x28 圆形 hover）
                    val menuInteraction = remember { MutableInteractionSource() }
                    val menuPressed by menuInteraction.collectIsPressedAsState()
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(if (menuPressed) Dsh.hover else Color.Transparent)
                            .clickable(interactionSource = menuInteraction, indication = null) {
                                scope.launch { drawerState.open() }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Menu,
                            contentDescription = "会话菜单",
                            tint = Dsh.labelSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    // 标题簇（crumbs：面包屑）
                    Spacer(Modifier.width(10.dp))
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(6.dp)),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (running) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(Dsh.brand400)
                            )
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(
                            currentSession?.title ?: "新会话",
                            color = Dsh.labelPrimary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight(500),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    // 右侧：运行中显示停止按钮 + 会话更多菜单
                    if (running) {
                        val stopInteraction = remember { MutableInteractionSource() }
                        val stopPressed by stopInteraction.collectIsPressedAsState()
                        val stopScale by animateFloatAsState(if (stopPressed) 0.94f else 1f)
                        val stopBg by animateColorAsState(
                            targetValue = if (stopPressed) Dsh.hover else Color.Transparent,
                            animationSpec = tween(motionDuration(120)),
                            label = "stopBg"
                        )
                        Box(
                            modifier = Modifier
                                .height(28.dp)
                                .clip(RoundedCornerShape(999.dp))
                                .background(stopBg)
                                .graphicsLayer(scaleX = stopScale, scaleY = stopScale)
                                .clickable(interactionSource = stopInteraction, indication = null) {
                                    val sid = currentSessionId ?: return@clickable
                                    scope.launch(Dispatchers.IO) {
                                        try {
                                            client.cancelSession(sid)
                                            refreshSessions()
                                        } catch (e: Exception) {}
                                    }
                                }
                                .padding(horizontal = 12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("停止", color = Dsh.labelSecondary, fontSize = 13.sp, fontWeight = FontWeight(500))
                        }
                    }
                    // 工具调用查找开关（仅对话视图；切换时重置瞬时查询）
                    if (viewMode == "chat") {
                        val tsInteraction = remember { MutableInteractionSource() }
                        val tsPressed by tsInteraction.collectIsPressedAsState()
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(
                                    when {
                                        toolSearchOpen -> Dsh.bgNavActive
                                        tsPressed -> Dsh.hover
                                        else -> Color.Transparent
                                    }
                                )
                                .clickable(interactionSource = tsInteraction, indication = null) {
                                    toolSearchOpen = !toolSearchOpen
                                    if (!toolSearchOpen) toolQuery = ""
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                SearchOutline16,
                                contentDescription = if (toolSearchOpen) "关闭工具搜索" else "搜索工具调用",
                                tint = if (toolSearchOpen) Dsh.labelPrimary else Dsh.labelSecondary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                    // 会话更多菜单（DSH 顶栏操作：重命名/分叉/复制标题）
                    var headerMenuOpen by remember { mutableStateOf(false) }
                    Box {
                        val moreInteraction = remember { MutableInteractionSource() }
                        val morePressed by moreInteraction.collectIsPressedAsState()
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(if (morePressed) Dsh.hover else Color.Transparent)
                                .clickable(interactionSource = moreInteraction, indication = null) { headerMenuOpen = true },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                EllipsisOutline16,
                                contentDescription = "更多操作",
                                tint = Dsh.labelSecondary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        DshMenu(
                            expanded = headerMenuOpen,
                            onDismiss = { headerMenuOpen = false },
                            items = listOf(
                                DshMenuItem(EditOutline16, "重命名会话") {
                                    headerMenuOpen = false
                                    currentSession?.let { renameTarget = it }
                                },
                                DshMenuItem(BranchOutline16, "分叉会话") {
                                    headerMenuOpen = false
                                    val sid = currentSessionId ?: return@DshMenuItem
                                    scope.launch(Dispatchers.IO) {
                                        try {
                                            val newId = client.forkSession(sid)
                                            withContext(Dispatchers.Main) {
                                                if (newId != null) {
                                                    currentSessionId = newId
                                                    refreshSessions()
                                                }
                                            }
                                        } catch (e: Exception) {}
                                    }
                                },
                                DshMenuItem(CopyOutline16, "复制会话标题") {
                                    headerMenuOpen = false
                                    val title = currentSession?.title ?: return@DshMenuItem
                                    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("session title", title))
                                    Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
                                },
                                DshMenuItem(ArchiveOutline20, "归档会话") {
                                    headerMenuOpen = false
                                    currentSession?.let { setArchived(it.sessionId) }
                                },
                                DshMenuItem(TrashOutline16, "删除会话", danger = true) {
                                    headerMenuOpen = false
                                    currentSession?.let { deleteSessionTarget = it }
                                },
                            )
                        )
                    }
                }

                // Row 2: 对话 / 轨迹 tabs（左对齐）
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    listOf("chat" to "对话", "trace" to "轨迹", "history" to "历史").forEach { (id, label) ->
                        val selected = viewMode == id
                        val vInteraction = remember { MutableInteractionSource() }
                        val vPressed by vInteraction.collectIsPressedAsState()
                        Box(
                            modifier = Modifier
                                .height(26.dp)
                                .clip(RoundedCornerShape(999.dp))
                                .background(
                                    when {
                                        selected -> Dsh.bgNavActive
                                        vPressed -> Dsh.hover
                                        else -> Color.Transparent
                                    }
                                )
                                .clickable(interactionSource = vInteraction, indication = null) { selectViewMode(id) }
                                .padding(horizontal = 12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                label,
                                color = if (selected) Dsh.labelPrimary else Dsh.labelSecondary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight(500),
                                lineHeight = 18.sp
                            )
                        }
                    }
                }

                // 底部 1px 分隔线
                Spacer(Modifier.height(11.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(Dsh.borderL1)
                )
            }

            // ===== 断线重连横幅（SSE 断开时提示；客户端自动退避重连；复用 DshBanner） =====
            val streamState = streamClient?.connectionState
            val streamConnected = streamState == SessionStreamClient.ConnectionState.CONNECTED
            val streamNeedsBanner = currentSessionId != null && streamState != null && !streamConnected
            AnimatedVisibility(
                visible = streamNeedsBanner,
                enter = expandVertically(animationSpec = tween(motionDuration(200))) + fadeIn(animationSpec = tween(motionDuration(200))),
                exit = shrinkVertically(animationSpec = tween(motionDuration(180))) + fadeOut(animationSpec = tween(motionDuration(180)))
            ) {
                DshBanner(
                    text = when (streamState) {
                        SessionStreamClient.ConnectionState.CONNECTING -> "正在连接…"
                        SessionStreamClient.ConnectionState.FAILURE -> "连接失败，正在重连…"
                        else -> "连接已断开，正在重连…"
                    },
                    tone = if (streamState == SessionStreamClient.ConnectionState.FAILURE)
                        DshBannerTone.Error else DshBannerTone.Info,
                    actionLabel = "重试",
                    onAction = { streamClient?.reconnect() },
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
                    contentDescription = "连接已断开，正在自动重连",
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }

            // 工具调用查找条（chat 视图；客户端过滤，瞬时状态不持久化）
            // 位于视图区（Crossfade）上方、Column 直子级：垂直排列，避免与 LazyColumn 重叠
            AnimatedVisibility(
                visible = toolSearchOpen && viewMode == "chat",
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
                            .widthIn(max = COMPOSER_MAX_WIDTH)
                            .height(36.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Dsh.bgInput)
                            .border(1.dp, Dsh.borderL2, RoundedCornerShape(10.dp))
                            .padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(SearchOutline16, contentDescription = null, tint = Dsh.labelCaption, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(6.dp))
                        BasicTextField(
                            value = toolQuery,
                            onValueChange = { toolQuery = it },
                            singleLine = true,
                            textStyle = TextStyle(color = Dsh.labelPrimary, fontSize = 13.sp),
                            cursorBrush = SolidColor(Dsh.brand400),
                            modifier = Modifier.weight(1f),
                            decorationBox = { inner ->
                                Box(contentAlignment = Alignment.CenterStart) {
                                    if (toolQuery.isEmpty()) {
                                        Text("搜索工具调用（名称 / 参数 / 结果）", color = Dsh.labelTertiary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    inner()
                                }
                            }
                        )
                        if (toolQuery.isNotEmpty()) {
                            Icon(
                                CloseOutline16,
                                contentDescription = "清除工具搜索",
                                tint = Dsh.labelCaption,
                                modifier = Modifier
                                    .size(14.dp)
                                    .clickable { toolQuery = "" }
                            )
                        }
                    }
                }
            }

            // ===== 历史视图（DSH AppHistoryView：搜索 + 会话列表） =====
            // Crossfade：对话/轨迹/历史切换带 200ms 淡入淡出（Material Motion 切换区间）
            // weight 必须加在 Crossfade 本体（Column 直接子级）上：内部子级（LazyColumn 等）
            // 的 weight 在 Crossfade 作用域内不生效，否则长会话会把 bottom chrome 挤出屏幕
            Crossfade(
                targetState = viewMode,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                animationSpec = tween(motionDuration(200)),
                label = "viewMode"
            ) { mode ->
            when (mode) {
            "history" -> HistoryView(
                    sessions = sessions,
                    searchQuery = searchQuery,
                    searchResults = searchResults,
                    searchState = searchState,
                    onQueryChange = { q ->
                        searchQuery = q
                        runSearchDebounced(q)
                    },
                    // 复用全局 history：会话列表每次进入 history 视图都已通过 refreshSessions() 拉取；
                    // 选择会话只需设置 currentSessionId（LaunchedEffect 会自动 refreshMessages，不会重复刷新）
                    onOpenSession = { sid ->
                        currentSessionId = sid
                        selectViewMode("chat")
                        refreshSessions() // global history：切换后保持服务端列表最新
                    },
                    onRetry = { runSearchDebounced(searchQuery) },
                    onClose = { selectViewMode("chat") },
                )
            "trace" -> TrajectoryView(
                messages = messages,
                stats = sessionStats,
                running = running,
                elapsedSec = elapsedSec,
                modifier = Modifier.weight(1f),
            )
            else -> {

            // ===== 消息流（max-width 748 居中） =====
            // 稳定列表引用（内容不变时避免 LazyColumn 滚动状态失效）
            val allMessages = remember(olderMessages, messages) { olderMessages + messages }
            // 按相邻 tool_call/tool_result 聚合：连续 ≥2 条折叠为聚合 header，单条仍走 MessageItem
            // 必须在 LazyColumn 外（LazyListScope 不是 @Composable context）
            val messageGroups = remember(allMessages) { groupMessages(allMessages) }
            // 工具查找：查询非空时只保留命中的工具消息（保留其聚合组上下文）
            val visibleGroups = remember(messageGroups, toolQuery) {
                if (toolQuery.isBlank()) messageGroups
                else messageGroups.filter { g ->
                    when (g) {
                        is MessageGroup.Single -> matchesTool(g.msg, toolQuery)
                        is MessageGroup.ToolGroup -> g.items.any { matchesTool(it, toolQuery) }
                        else -> false
                    }
                }
            }
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(
                    horizontal = COMPOSER_SIDE_CLEARANCE + 16.dp,
                    vertical = 16.dp
                )
            ) {
                if (messages.isEmpty()) {
                    if (initialLoadInFlight && sessionStats == null) {
                        // 首次加载骨架：第一条 history 请求 in-flight 且无任何内容时显示，
                        // 避免空白闪一下再跳内容（load/empty/error 互斥）
                        item(key = "chat-loading-skeleton") {
                            ChatLoadingSkeleton()
                        }
                    } else {
                    item {
                        HeroShell(
                            sessions = sessions,
                            mode = heroMode,
                            onModeChange = { heroMode = it },
                            onStartSession = { cwd ->
                                scope.launch(Dispatchers.IO) {
                                    try {
                                        val newId = if (cwd == null) {
                                            client.createSession(agentPreset = appSettings.agentPreset)
                                        } else {
                                            client.createSession(agentPreset = appSettings.agentPreset, cwd = cwd)
                                        }
                                        withContext(Dispatchers.Main) {
                                            currentSessionId = newId
                                            refreshSessions()
                                            // 模式生效：按所选模式发送引导命令（DSH plan/goal 模式）
                                            when (heroMode) {
                                                HeroMode.PLAN -> client.sendPrompt(newId, "/plan 描述你的任务以生成计划")
                                                HeroMode.GOAL -> client.sendPrompt(newId, "/goal 输入目标，智能体将持续执行")
                                                HeroMode.CHAT -> {}
                                            }
                                        }
                                    } catch (e: Exception) {}
                                }
                            }
                        )
                    } // 闭合 item（HeroShell）
                } // 闭合骨架 if 的 else 分支
            } else { // 闭合 if (messages.isEmpty())，打开 else 分支
                // 加载更早（DSH chat.loadOlder：hasMore 时显示在消息流顶部，点击向前翻页）
                    if (hasMoreMessages) {
                        item(key = "load-older") {
                            LoadOlderRow(
                                loading = isLoadingOlder,
                                onClick = { loadOlderMessages() }
                            )
                        }
                    }
                    // 正在执行状态行（品牌蓝 shimmer）
                    if (running) {
                        item(key = "turn-status") {
                            TurnStatusRow(elapsedSec)
                        }
                    }
                    // 正在执行的最后一条工具/思考消息带扫光（DSH command/reasoning sweep）
                    val sweepingId = if (running) {
                        messages.lastOrNull { it.role == "tool_call" || it.role == "tool_result" || it.role == "reasoning" }?.id
                    } else null
                    // 稳定列表引用（内容不变时避免 LazyColumn 滚动状态失效）
                    items(visibleGroups, key = { it.groupKey }) { group ->
                        AnimatedVisibility(
                            visible = true,
                            enter = if (group is MessageGroup.Single && group.msg.entrance)
                                fadeIn(animationSpec = tween(motionDuration(200))) +
                                slideInVertically(animationSpec = tween(motionDuration(250), easing = FastOutSlowInEasing), initialOffsetY = { it })
                            else EnterTransition.None,
                            modifier = Modifier.animateItem()
                        ) {
                            when (group) {
                                is MessageGroup.Single -> MessageItem(
                                    msg = group.msg,
                                    running = sweepingId != null && group.msg.id == sweepingId,
                                    onAnswerApproval = { approvalId, outcome ->
                                        val sid = currentSessionId ?: return@MessageItem
                                        scope.launch(Dispatchers.IO) {
                                            try {
                                                client.answerApproval(sid, approvalId, outcome)
                                            } catch (e: Exception) {}
                                        }
                                    },
                                    onCopy = {
                                        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("dsh message", group.msg.text))
                                        Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
                                    },
                                    onQuote = {
                                        // DSH 引用：把消息首行作为引用注入输入框
                                        val firstLine = group.msg.text.lineSequence().firstOrNull().orEmpty().take(120)
                                        inputText = if (inputText.isBlank()) "> $firstLine\n" else "> $firstLine\n$inputText"
                                    },
                                    onFork = {
                                        // 从当前会话分叉（DSH fork）
                                        val sid = currentSessionId ?: return@MessageItem
                                        scope.launch(Dispatchers.IO) {
                                            try {
                                                val newId = client.forkSession(sid)
                                                withContext(Dispatchers.Main) {
                                                    if (newId != null) {
                                                        currentSessionId = newId
                                                        refreshSessions()
                                                    }
                                                }
                                            } catch (e: Exception) {}
                                        }
                                    },
                                )
                                is MessageGroup.ToolGroup -> ToolGroupHeader(
                                    group = group,
                                    sweepingId = sweepingId
                                )
                            }
                        }
                    }
                    // 工具查找无命中（查询非空时提示，区别于"会话无消息"的 hero 态）
                    if (toolQuery.isNotBlank() && visibleGroups.isEmpty()) {
                        item(key = "tool-search-empty") {
                            Text(
                                "无匹配工具调用",
                                color = Dsh.labelTertiary,
                                fontSize = 13.sp,
                                lineHeight = 20.sp,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 24.dp)
                            )
                        }
                    }
                    // 已停止标记（DSH message.stopped：会话非正常结束时显示在流尾部）
                    if (stoppedReason != null) {
                        item(key = "stopped-badge") {
                            StoppedBadge(reason = stoppedReason!!)
                        }
                    }
                }
            }

            } // viewMode else 分支结束（when 结构收尾）
            } // when(viewMode) 结束
            } // Crossfade 结束

            // 命令候选（输入以 / 开头时，DSH 命令/技能/子智能体/快捷操作）—— 悬浮在输入区上方
            // typed 处理：
            //   Completable  → 直接提交到服务端并清空输入框
            //   Insertable   → 把 trigger 插入到 composer（例如 /plan /goal /subagent 待补参数）
            //   Local        → 本地动作（/search 等）；绝不到达服务端
            if (inputText.startsWith("/") && inputText.length <= 24) {
                CommandSuggestions(
                    query = inputText,
                    onPick = { picked ->
                        // 无论哪种类型，先关掉 picker：清空输入文本以触发外层 `inputText.startsWith("/") == false`
                        when (picked) {
                            is PaletteCommand.Local -> {
                                inputText = ""
                                dispatchLocalPaletteAction(picked.kind)
                            }
                            is PaletteCommand.Insertable -> {
                                // 把 trigger + 空格 放进 composer，并请求焦点 + 弹起 IME
                                inputText = picked.trigger + " "
                                composerKeyboardController?.show()
                                composerFocusRequester.requestFocus()
                            }
                            is PaletteCommand.Completable -> {
                                inputText = ""
                                val sid = currentSessionId ?: return@CommandSuggestions
                                scope.launch(Dispatchers.IO) {
                                    try {
                                        client.sendPrompt(sid, picked.trigger)
                                        withContext(Dispatchers.Main) {
                                            refreshSessions()
                                            if (streamClient?.isConnected != true) {
                                                refreshMessages(autoScroll = true)
                                            }
                                        }
                                    } catch (e: Exception) {
                                        withContext(Dispatchers.Main) {
                                            Toast.makeText(context, "命令发送失败：${e.message ?: "未知错误"}", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }
                            }
                        }
                    }
                )
            }
            // 工作区行（hero 状态下显示在输入卡上方，DSH composer workspace row）
            if (messages.isEmpty()) {
                WorkspaceComposerRow(
                    sessions = sessions,
                    onStartSession = { cwd ->
                        scope.launch(Dispatchers.IO) {
                            try {
                                val newId = if (cwd == null) {
                                    client.createSession(agentPreset = appSettings.agentPreset)
                                } else {
                                    client.createSession(agentPreset = appSettings.agentPreset, cwd = cwd)
                                }
                                withContext(Dispatchers.Main) {
                                    currentSessionId = newId
                                    refreshSessions()
                                    when (heroMode) {
                                        HeroMode.PLAN -> client.sendPrompt(newId, "/plan 描述你的任务以生成计划")
                                        HeroMode.GOAL -> client.sendPrompt(newId, "/goal 输入目标，智能体将持续执行")
                                        HeroMode.CHAT -> {}
                                    }
                                }
                            } catch (e: Exception) {}
                        }
                    }
                )
            }
            // ===== bottom chrome（WI-006：发送队列/输入卡/统计栏同一容器，统一安全区与 IME） =====
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(bottom = 8.dp)
            ) {
                // 回到底部（WI-003：用户上翻阅读时新消息到达，不强行拉回，提供显式返回）
                AnimatedVisibility(
                    visible = showScrollToBottom && currentSessionId != null && messages.isNotEmpty(),
                    enter = expandVertically(animationSpec = tween(motionDuration(180))) + fadeIn(animationSpec = tween(motionDuration(150))),
                    exit = shrinkVertically(animationSpec = tween(motionDuration(160))) + fadeOut(animationSpec = tween(motionDuration(120)))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = COMPOSER_SIDE_CLEARANCE, vertical = 4.dp),
                        contentAlignment = Alignment.CenterEnd
                    ) {
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .background(Dsh.bgLayer1)
                                .border(1.dp, Dsh.borderL2, RoundedCornerShape(999.dp))
                                .clickable {
                                    showScrollToBottom = false
                                    requestTailPosition()
                                }
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.KeyboardArrowDown,
                                contentDescription = null,
                                tint = Dsh.labelSecondary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("回到底部", color = Dsh.labelSecondary, fontSize = 12.sp, fontWeight = FontWeight(500), lineHeight = 18.sp)
                        }
                    }
                }
                // 发送队列 dock（DSH QueueDock：发送中显示在输入卡上方）
                if (isSending) {
                    QueueDock()
                }
                InputBar(
                inputText = inputText,
                onInputChange = { inputText = it },
                pendingImages = pendingImages,
                onRemoveImage = { index ->
                    pendingImages = pendingImages.filterIndexed { i, _ -> i != index }
                },
                onPickImage = { imagePickerLauncher.launch("image/*") },
                isListening = isListening,
                isSending = isSending,
                canSend = inputText.isNotBlank() && currentSessionId != null && !isSending,
                running = running,
                currentModel = modelCatalog?.currentModel,
                sessionStats = sessionStats,
                heroMode = heroMode,
                permissionLabel = when (appSettings.permissionPreset) {
                    "read-only" -> "只读"
                    "danger-full-access" -> "完全访问"
                    else -> "工作区写入"
                },
                onOpenModelPicker = {
                    refreshModels()
                    showModelPicker = true
                },
                onOpenPermissionPicker = { showPermissionPicker = true },
                onToggleVoice = {
                    if (isListening) {
                        onStopVoiceInput()
                        isListening = false
                    } else {
                        isListening = true
                        onStartVoiceInput { recognizedText ->
                            isListening = false
                            if (recognizedText.isNotBlank()) {
                                inputText = if (inputText.isBlank()) recognizedText else "$inputText $recognizedText"
                            }
                        }
                    }
                },
                onStop = {
                    val sid = currentSessionId ?: return@InputBar
                    scope.launch(Dispatchers.IO) {
                        try {
                            client.cancelSession(sid)
                            refreshSessions()
                        } catch (e: Exception) {}
                    }
                },
                composerFocusRequester = composerFocusRequester,
                onSend = {
                    val textToSend = inputText.trim()
                    val sid = currentSessionId
                    val images = pendingImages
                    if ((textToSend.isNotBlank() || images.isNotEmpty()) && sid != null && !isSending) {
                        isSending = true
                        inputText = ""
                        pendingImages = emptyList()
                        scope.launch(Dispatchers.IO) {
                            try {
                                client.sendPrompt(sid, textToSend, images = images)
                                withContext(Dispatchers.Main) {
                                    refreshSessions()
                                    // SSE 活跃时消息经实时流到达（user/message 事件自动滚底）
                                    if (streamClient?.isConnected != true) {
                                        refreshMessages(autoScroll = true)
                                    }
                                }
                            } catch (e: Exception) {
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(context, "发送失败：${e.message ?: "未知错误"}", Toast.LENGTH_SHORT).show()
                                }
                            }
                            finally {
                                withContext(Dispatchers.Main) { isSending = false }
                            }
                        }
                    }
                }
            )
            // StatsLine（DSH：轮次/步骤 | LLM/工具耗时 | 首 token/吞吐 | 缓存/tokens）—— 放在输入框下方，
            // 与输入卡同属 bottom chrome，不再落入系统手势导航区（WI-006）
            sessionStats?.let { stats ->
                if (stats.steps > 0) {
                    StatsLine(stats)
                }
            }
            } // bottom chrome 容器结束
        }
    }

    // 模型选择右侧面板
    // 注意：Dialog 内容渲染在独立窗口，外层 AnimatedVisibility 的变换对其不生效，
    // 因此滑入动画在 ModelPickerSheet 内部通过 graphicsLayer 实现（只走渲染层）
    if (showModelPicker && currentSessionId != null) {
        ModelPickerSheet(
            catalog = modelCatalog,
            onDismiss = { showModelPicker = false },
            onSelect = { provider, model ->
                showModelPicker = false
                val sid = currentSessionId!!
                scope.launch(Dispatchers.IO) {
                    try {
                        client.selectModel(sid, provider, model)
                        withContext(Dispatchers.Main) { refreshModels() }
                    } catch (e: Exception) {}
                }
            }
        )
    }

    // 访问模式选择（输入区；WI-004：写入服务端 permission.defaultPreset）
    if (showPermissionPicker) {
        PermissionPickerSheet(
            context = context,
            host = host,
            currentPreset = appSettings.permissionPreset,
            onSaved = { updated -> appSettings = updated },
            onDismiss = { showPermissionPicker = false }
        )
    }

    // 添加工作区（输入目录路径）
    if (showAddWorkspace) {
        var path by remember { mutableStateOf("") }
        ModalBottomSheet(
            onDismissRequest = { showAddWorkspace = false },
            containerColor = Dsh.bgLayer1,
            contentColor = Dsh.labelPrimary,
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
            dragHandle = null,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 32.dp)
            ) {
                Text("添加工作区", color = Dsh.labelPrimary, fontSize = 18.sp, fontWeight = FontWeight(500), lineHeight = 24.sp)
                Spacer(Modifier.height(4.dp))
                Text("输入文件夹路径，将其注册为工作区", color = Dsh.labelTertiary, fontSize = 12.sp, lineHeight = 18.sp)
                Spacer(Modifier.height(14.dp))
                BasicTextField(
                    value = path,
                    onValueChange = { path = it },
                    singleLine = true,
                    textStyle = TextStyle(color = Dsh.labelPrimary, fontSize = 13.sp),
                    cursorBrush = SolidColor(Dsh.brand400),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Dsh.bgInput)
                        .border(1.dp, Dsh.borderL2, RoundedCornerShape(12.dp))
                        .padding(horizontal = 13.dp),
                    decorationBox = { inner ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (path.isEmpty()) Text("例如 /Users/me/projects/my-app", color = Dsh.labelTertiary, fontSize = 13.sp)
                            inner()
                        }
                    }
                )
                Spacer(Modifier.height(14.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .clip(RoundedCornerShape(13.dp))
                        .background(if (path.isBlank()) Dsh.buttonElevated.copy(alpha = 0.5f) else Dsh.brand400)
                        .clickable(enabled = path.isNotBlank()) {
                            showAddWorkspace = false
                            scope.launch(Dispatchers.IO) {
                                try {
                                    client.createWorkspace(path.trim())
                                    withContext(Dispatchers.Main) {
                                        refreshSessions()
                                    }
                                } catch (e: Exception) {}
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text("添加", color = if (path.isBlank()) Dsh.labelTertiary else Color.White, fontSize = 13.sp, fontWeight = FontWeight(500))
                }
            }
        }
    }

    // 会话重命名
    renameTarget?.let { target ->
        var name by remember { mutableStateOf(target.title) }
        DshRenameDialog(
            currentName = name,
            onDismiss = { renameTarget = null },
            onSave = { newName ->
                if (newName.isNotBlank()) {
                    renameTarget = null
                    scope.launch(Dispatchers.IO) {
                        try {
                            client.renameSession(target.sessionId, newName.trim())
                            withContext(Dispatchers.Main) { refreshSessions() }
                        } catch (e: Exception) {}
                    }
                }
            }
        )
    }

    // 删除工作区（取消注册；会话日志保留，不再显示在该工作区下）
    deleteWorkspaceTarget?.let { path ->
        DshConfirmDialog(
            title = "删除工作区？",
            message = "将从设备移除「${path.substringAfterLast('/')}」的工作区注册。\n会话不会被删除，之后可在「未分组」中继续访问。",
            confirmLabel = "删除",
            danger = true,
            onDismiss = { deleteWorkspaceTarget = null },
            onConfirm = {
                deleteWorkspaceTarget = null
                scope.launch(Dispatchers.IO) {
                    try {
                        client.deleteWorkspace(path)
                        withContext(Dispatchers.Main) {
                            setDeletedWorkspace(path) // 服务端删注册 + 本地隐藏该 cwd 组（会话归"未分组"）
                            refreshSessions()
                            collapsedWorkspaces = collapsedWorkspaces - path
                            expandedGroups = expandedGroups - path
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "删除失败：${e.message ?: "未知错误"}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        )
    }

    // 删除会话（本地标记，24h 后自动清理；不影响电脑端）
    deleteSessionTarget?.let { target ->
        DshConfirmDialog(
            title = "删除会话？",
            message = "将从设备移除「${target.title}」。\n电脑端的数据不受影响，24 小时后自动清理。",
            confirmLabel = "删除",
            danger = true,
            onDismiss = { deleteSessionTarget = null },
            onConfirm = {
                deleteSessionTarget = null
                setDeleted(target.sessionId)
                refreshSessions()
            }
        )
    }
}

// ---------- 重命名弹窗（DSH 风格） ----------

@Composable
private fun DshRenameDialog(
    currentName: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var name by remember { mutableStateOf(currentName) }
    val (dlgAlpha, dlgScale) = dialogEnterState()
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = dlgAlpha.value }
                .background(Dsh.bgOverlay)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 280.dp)
                    .fillMaxWidth(0.8f)
                    .graphicsLayer {
                        alpha = dlgAlpha.value
                        scaleX = dlgScale.value
                        scaleY = dlgScale.value
                    }
                    .clip(RoundedCornerShape(14.dp))
                    .background(Dsh.bgLayer1)
                    .border(1.dp, Dsh.borderL2, RoundedCornerShape(14.dp))
                    .padding(18.dp)
            ) {
                Text("重命名会话", color = Dsh.labelPrimary, fontSize = 15.sp, fontWeight = FontWeight(500), lineHeight = 21.sp)
                Spacer(Modifier.height(8.dp))
                Text("设置一个方便识别的会话名称。", color = Dsh.labelTertiary, fontSize = 12.sp, lineHeight = 17.sp)
                Spacer(Modifier.height(14.dp))
                BasicTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    textStyle = TextStyle(color = Dsh.labelPrimary, fontSize = 13.sp),
                    cursorBrush = SolidColor(Dsh.brand400),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Dsh.bgInput)
                        .border(1.dp, Dsh.borderL2, RoundedCornerShape(12.dp))
                        .padding(horizontal = 13.dp)
                )
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    Box(
                        modifier = Modifier
                            .height(34.dp)
                            .clip(RoundedCornerShape(7.dp))
                            .clickable(onClick = onDismiss)
                            .padding(horizontal = 14.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("取消", color = Dsh.labelSecondary, fontSize = 12.sp, fontWeight = FontWeight(500))
                    }
                    Spacer(Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .height(34.dp)
                            .clip(RoundedCornerShape(7.dp))
                            .background(Dsh.brand400)
                            .clickable { onSave(name) }
                            .padding(horizontal = 14.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("保存", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight(500))
                    }
                }
            }
        }
    }
}

// ---------- 确认弹窗（DSH 风格：bgLayer1 卡片 + 危险操作红按钮） ----------

@Composable
private fun DshConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    danger: Boolean = false,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val (dlgAlpha, dlgScale) = dialogEnterState()
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = dlgAlpha.value }
                .background(Dsh.bgOverlay)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 280.dp)
                    .fillMaxWidth(0.8f)
                    .graphicsLayer {
                        alpha = dlgAlpha.value
                        scaleX = dlgScale.value
                        scaleY = dlgScale.value
                    }
                    .clip(RoundedCornerShape(14.dp))
                    .background(Dsh.bgLayer1)
                    .border(1.dp, Dsh.borderL2, RoundedCornerShape(14.dp))
                    .padding(18.dp)
            ) {
                Text(title, color = Dsh.labelPrimary, fontSize = 15.sp, fontWeight = FontWeight(500), lineHeight = 21.sp)
                Spacer(Modifier.height(8.dp))
                Text(message, color = Dsh.labelTertiary, fontSize = 12.sp, lineHeight = 17.sp)
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    Box(
                        modifier = Modifier
                            .height(34.dp)
                            .clip(RoundedCornerShape(7.dp))
                            .clickable(onClick = onDismiss)
                            .padding(horizontal = 14.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("取消", color = Dsh.labelSecondary, fontSize = 12.sp, fontWeight = FontWeight(500))
                    }
                    Spacer(Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .height(34.dp)
                            .clip(RoundedCornerShape(7.dp))
                            .background(if (danger) Dsh.error else Dsh.brand400)
                            .clickable(onClick = onConfirm)
                            .padding(horizontal = 14.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(confirmLabel, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight(500))
                    }
                }
            }
        }
    }
}

// ---------- 新会话 Hero（HeroShell：logo + 口号 + 模式/权限/工作区选择） ----------

private enum class HeroMode(val label: String, val placeholder: String) {
    CHAT("对话", "描述你想要构建的内容"),
    PLAN("计划", "描述你的任务以生成计划"),
    GOAL("目标", "输入目标，智能体将持续执行"),
}

private val HERO_PERMISSIONS = listOf(
    "read-only" to "只读",
    "workspace-write" to "工作区写入",
    "danger-full-access" to "完全访问",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HeroShell(
    sessions: List<MobileSession>,
    mode: HeroMode,
    onModeChange: (HeroMode) -> Unit,
    onStartSession: (String?) -> Unit,
) {
    var showWorkspacePicker by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 64.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // logo（品牌圆标）
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(
                    Brush.linearGradient(listOf(Dsh.brand400, Dsh.brand500))
                ),
            contentAlignment = Alignment.Center
        ) {
            Text("DSH", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight(600), letterSpacing = 0.5.sp)
        }
        Spacer(Modifier.height(14.dp))

        // 口号（DSH hero.headline：探索未至之境 26px/500 + 预览版 badge）
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Text(
                "探索未至之境",
                color = Dsh.labelPrimary,
                fontSize = 26.sp,
                fontWeight = FontWeight(500),
                lineHeight = 32.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "预览版",
                color = Dsh.labelPrimary.copy(alpha = 0.9f),
                fontSize = 12.sp,
                lineHeight = 18.sp,
                fontWeight = FontWeight(500),
                modifier = Modifier
                    .clip(RoundedCornerShape(24.dp))
                    .border(1.dp, Dsh.hover, RoundedCornerShape(24.dp))
                    .background(Dsh.brand400.copy(alpha = 0.12f))
                    .padding(horizontal = 7.dp, vertical = 1.dp)
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "DeepSeek Harness · 移动端",
            color = Dsh.labelTertiary,
            fontSize = 12.sp,
            lineHeight = 18.sp
        )
        Spacer(Modifier.height(20.dp))

        // 模式选择（对话 / 计划 / 目标：DSH 分段胶囊组）
        Row(
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier
                .clip(RoundedCornerShape(999.dp))
                .background(Dsh.bgInput)
                .border(1.dp, Dsh.borderL1, RoundedCornerShape(999.dp))
                .padding(3.dp)
        ) {
            ModeChip(label = "对话", selected = mode == HeroMode.CHAT, onClick = { onModeChange(HeroMode.CHAT) })
            ModeChip(label = "计划", selected = mode == HeroMode.PLAN, onClick = { onModeChange(HeroMode.PLAN) })
            ModeChip(label = "目标", selected = mode == HeroMode.GOAL, onClick = { onModeChange(HeroMode.GOAL) })
        }
    }

    // 工作区选择弹层（从输入区工作区行触发）
    if (showWorkspacePicker) {
        WorkspacePickerSheet(
            sessions = sessions,
            onDismiss = { showWorkspacePicker = false },
            onPick = { cwd ->
                showWorkspacePicker = false
                onStartSession(cwd)
            }
        )
    }
}

// ---------- 工作区选择弹层 ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WorkspacePickerSheet(
    sessions: List<MobileSession>,
    onDismiss: () -> Unit,
    onPick: (String?) -> Unit,
) {
    val workspaces = remember(sessions) {
        sessions.mapNotNull { it.cwd }.distinct().filter { isUserWorkspace(it) }.sorted()
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Dsh.bgLayer1,
        contentColor = Dsh.labelPrimary,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        dragHandle = null,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
        ) {
            Text("选择一个工作区开始", color = Dsh.labelPrimary, fontSize = 18.sp, fontWeight = FontWeight(500), lineHeight = 24.sp)
            Spacer(Modifier.height(4.dp))
            Text("会话将保存在所选工作区", color = Dsh.labelTertiary, fontSize = 12.sp, lineHeight = 18.sp)
            Spacer(Modifier.height(16.dp))

            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                WorkspaceOptionRow(
                    title = "未分组",
                    path = "不绑定工作区",
                    selected = false,
                    onClick = {
                        onDismiss()
                        onPick(null)
                    }
                )
                workspaces.forEach { ws ->
                    WorkspaceOptionRow(
                        title = ws.substringAfterLast('/'),
                        path = ws,
                        selected = false,
                        onClick = {
                            onDismiss()
                            onPick(ws)
                        }
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            AddWorkspaceRow(
                onCreate = { path ->
                    onDismiss()
                    onPick(path)
                }
            )
        }
    }
}

@Composable
private fun WorkspaceOptionRow(
    title: String,
    path: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (pressed || selected) Dsh.hover else Color.Transparent)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            FolderOpenOutline16,
            contentDescription = null,
            tint = if (selected) Dsh.brand400 else Dsh.labelTertiary,
            modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = Dsh.labelPrimary, fontSize = 14.sp, lineHeight = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(path, color = Dsh.labelTertiary, fontSize = 12.sp, lineHeight = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun AddWorkspaceRow(onCreate: (String) -> Unit) {
    var path by remember { mutableStateOf("") }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Dsh.bgInput)
            .border(1.dp, Dsh.borderL2, RoundedCornerShape(12.dp))
            .padding(10.dp)
    ) {
        Text("添加工作区", color = Dsh.labelSecondary, fontSize = 13.sp, fontWeight = FontWeight(500), lineHeight = 20.sp)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(
                value = path,
                onValueChange = { path = it },
                singleLine = true,
                textStyle = TextStyle(color = Dsh.labelPrimary, fontSize = 13.sp),
                cursorBrush = SolidColor(Dsh.brand400),
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Dsh.bgLayer3)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (path.isEmpty()) Text("输入工作区目录路径", color = Dsh.labelTertiary, fontSize = 13.sp)
                        inner()
                    }
                }
            )
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .height(32.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (path.isBlank()) Dsh.buttonElevated.copy(alpha = 0.5f) else Dsh.brand400)
                    .clickable(enabled = path.isNotBlank()) { onCreate(path.trim()) }
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("创建", color = if (path.isBlank()) Dsh.labelTertiary else Color.White, fontSize = 13.sp, fontWeight = FontWeight(500))
            }
        }
    }
}

// ---------- 正在执行状态行（品牌蓝 shimmer） ----------

@Composable
private fun TurnStatusRow(elapsedSec: Long) {
    Row(
        modifier = Modifier
            .height(26.dp)
            .clip(RoundedCornerShape(6.dp)),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isReduceMotionEnabled()) {
            // reduce-motion：静态品牌蓝文本（Web prefers-reduced-motion 语义）
            Text(
                "正在执行…",
                fontSize = 14.sp,
                fontWeight = FontWeight(500),
                color = Dsh.brand400
            )
        } else {
            // 渐变扫光文本：动画值只在 Canvas 绘制阶段读取，避免每帧重组/重建 Brush
            val transition = rememberInfiniteTransition(label = "shimmer")
            val offset = transition.animateFloat(
                initialValue = 1f,
                targetValue = 0f,
                animationSpec = infiniteRepeatable(
                    animation = tween(1800, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart
                ),
                label = "shimmerOffset"
            )
            val textMeasurer = rememberTextMeasurer()
            val brand400 = Dsh.brand400
            val brand200 = Dsh.brand200
            val textLayout = remember {
                textMeasurer.measure(
                    "正在执行…",
                    TextStyle(fontSize = 14.sp, fontWeight = FontWeight(500))
                )
            }
            Canvas(
                modifier = Modifier
                    .width(with(LocalDensity.current) { textLayout.size.width.toDp() })
                    .height(26.dp)
            ) {
                drawText(
                    textLayout,
                    brush = Brush.linearGradient(
                        colors = listOf(brand400, brand200, brand400),
                        start = Offset(offset.value * size.width, 0f),
                        end = Offset(offset.value * size.width + size.width, 0f)
                    ),
                    topLeft = Offset(0f, (size.height - textLayout.size.height) / 2f)
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(
            "${elapsedSec}s",
            fontSize = 12.sp,
            color = Dsh.labelTertiary,
            fontFamily = FontFamily.Monospace
        )
    }
}

// ---------- ModeChip：对话/计划/目标分段胶囊（复用 DshFilterChip，无计数） ----------

@Composable
private fun ModeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    DshFilterChip(
        label = label,
        selected = selected,
        onClick = onClick,
        contentDescription = label,
    )
}

// ---------- 会话过滤：3 chip（全部 / 运行中 / 已停止）----------
// 状态枚举与分类逻辑抽到 util/SessionClassification.kt（纯函数，JVM 单测覆盖）

/** 3 chip 横向 Row：全部 / 运行中 / 已停止，每个带计数。 */
@Composable
private fun SessionFilterChips(
    selected: SessionFilter,
    counts: Map<SessionFilter, Int>,
    onSelect: (SessionFilter) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SessionFilter.entries.forEach { f ->
            DshFilterChip(
                label = when (f) {
                    SessionFilter.ALL -> "全部"
                    SessionFilter.RUNNING -> "运行中"
                    SessionFilter.STOPPED -> "已停止"
                },
                count = counts[f] ?: 0,
                selected = f == selected,
                onClick = { onSelect(f) },
                contentDescription = "筛选会话：${when (f) {
                    SessionFilter.ALL -> "全部"
                    SessionFilter.RUNNING -> "运行中"
                    SessionFilter.STOPPED -> "已停止"
                }}"
            )
        }
    }
}

// ---------- StatsLine（DSH：12px/20px tertiary 居中，| 分隔） ----------

private fun formatDuration(ms: Long): String = when {
    ms >= 3_600_000 -> String.format(Locale.US, "%.1fh", ms / 3_600_000.0)
    ms >= 60_000 -> String.format(Locale.US, "%.0fm", ms / 60_000.0)
    ms >= 1000 -> String.format(Locale.US, "%.1fs", ms / 1000.0)
    else -> "${ms}ms"
}

private fun formatTokens(n: Long): String = when {
    n >= 1_000_000 -> String.format(Locale.US, "%.1fM", n / 1_000_000.0)
    n >= 1000 -> String.format(Locale.US, "%.1fK", n / 1000.0)
    else -> "$n"
}

@Composable
private fun StatsLine(stats: MobileSessionStats) {
    var showDetails by remember { mutableStateOf(false) }
    val groups = mutableListOf<String>()
    if (stats.steps > 0) {
        groups.add("${stats.turns} 轮 · ${stats.steps} 步")
        val durations = mutableListOf<String>()
        if (stats.llmMs > 0) durations.add("LLM ${formatDuration(stats.llmMs)}")
        if (stats.toolMs > 0) durations.add("工具调用 ${formatDuration(stats.toolMs)}")
        if (durations.isNotEmpty()) groups.add(durations.joinToString(" · "))
        val speeds = mutableListOf<String>()
        if (stats.ttftSteps > 0) speeds.add("首 token 平均 ${formatDuration(stats.ttftMs / stats.ttftSteps)}")
        if (stats.decodeMs > 0 && stats.decodeTokens > 0) {
            speeds.add(String.format(Locale.US, "%.0f tok/s", stats.decodeTokens / (stats.decodeMs / 1000.0)))
        }
        if (speeds.isNotEmpty()) groups.add(speeds.joinToString(" · "))
    }
    val input = stats.uncachedInputTokens + stats.cacheReadTokens
    if (input > 0 || stats.outputTokens > 0) {
        val total = input + stats.cacheReadTokens
        if (stats.cacheReadTokens > 0 && total > 0) {
            groups.add("缓存命中 ${(stats.cacheReadTokens * 100 / total)}%")
        }
        groups.add("输入 ${formatTokens(input)} tok · 输出 ${formatTokens(stats.outputTokens)} tok")
    }
    if (groups.isEmpty()) return
    Box {
        Text(
            groups.joinToString(" | "),
            color = Dsh.labelTertiary,
            fontSize = 12.sp,
            lineHeight = 20.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .clickable { showDetails = true }
                .padding(vertical = 2.dp)
        )
        // 详情面板（DSH DetailsPanel：token/耗时明细）
        DropdownMenu(
            expanded = showDetails,
            onDismissRequest = { showDetails = false },
            containerColor = Dsh.bgLayer1,
            shape = RoundedCornerShape(12.dp),
            tonalElevation = 0.dp,
            shadowElevation = 12.dp,
            border = BorderStroke(1.dp, Dsh.borderL2)
        ) {
            Column(
                modifier = Modifier
                    .width(250.dp)
                    .padding(10.dp)
            ) {
                Text(
                    "会话统计",
                    color = Dsh.labelPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight(500),
                    lineHeight = 20.sp,
                    modifier = Modifier.padding(bottom = 6.dp)
                )
                DetailRow("轮次", "${stats.turns}")
                DetailRow("步骤", "${stats.steps}")
                DetailRow("LLM 耗时", formatDuration(stats.llmMs))
                DetailRow("工具调用耗时", formatDuration(stats.toolMs))
                DetailRow("首 token 平均", if (stats.ttftSteps > 0) formatDuration(stats.ttftMs / stats.ttftSteps) else "-")
                DetailRow("吞吐", if (stats.decodeMs > 0 && stats.decodeTokens > 0)
                    String.format(Locale.US, "%.0f tok/s", stats.decodeTokens / (stats.decodeMs / 1000.0)) else "-")
                DetailRow("输入 tokens", formatTokens(stats.uncachedInputTokens + stats.cacheReadTokens))
                DetailRow("缓存读取", formatTokens(stats.cacheReadTokens))
                DetailRow("输出 tokens", formatTokens(stats.outputTokens))
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = Dsh.labelTertiary, fontSize = 12.sp, lineHeight = 18.sp, modifier = Modifier.weight(1f))
        Text(value, color = Dsh.labelSecondary, fontSize = 12.sp, lineHeight = 18.sp, fontFamily = FontFamily.Monospace)
    }
}

// ---------- 模型选择右侧面板（DSH ModelPicker 移动版：右侧滑出，供应商在左模型在右） ----------

@Composable
private fun ModelPickerSheet(
    catalog: MobileModelCatalog?,
    onDismiss: () -> Unit,
    onSelect: (provider: String, model: String) -> Unit,
) {
    val (dlgAlpha, dlgScale) = dialogEnterState()
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = dlgAlpha.value }
                .background(Dsh.bgOverlay)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss)
        ) {
            // 右侧面板（宽 320dp）—— 滑入动画在 Dialog 内部（独立窗口），走 graphicsLayer 渲染层
            Column(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .width(320.dp)
                    .graphicsLayer {
                        alpha = dlgAlpha.value
                        translationX = (1f - dlgAlpha.value) * 320.dp.toPx()
                        scaleX = dlgScale.value
                        scaleY = dlgScale.value
                    }
                    .background(Dsh.bgLayer1)
                    .border(1.dp, Dsh.borderL2)
                    .padding(horizontal = 16.dp)
                    .statusBarsPadding()
                    .padding(top = 12.dp)
            ) {
                // 头部
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("选择模型", color = Dsh.labelPrimary, fontSize = 16.sp, fontWeight = FontWeight(500), lineHeight = 22.sp)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            if (catalog?.currentModel != null) "当前 ${catalog.currentModel}" else "选择本会话使用的模型",
                            color = Dsh.labelTertiary,
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(26.dp)
                            .clip(CircleShape)
                            .background(Dsh.bgLayer3)
                            .clickable(onClick = onDismiss),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("×", color = Dsh.labelSecondary, fontSize = 19.sp, fontWeight = FontWeight(300))
                    }
                }
                Spacer(Modifier.height(12.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(Dsh.borderL1)
                )
                Spacer(Modifier.height(8.dp))

                if (catalog == null) {
                    Text("正在加载模型列表…", color = Dsh.labelTertiary, fontSize = 13.sp, modifier = Modifier.padding(vertical = 16.dp))
                } else if (catalog.groups.isEmpty()) {
                    Text("没有可用的模型。", color = Dsh.labelTertiary, fontSize = 13.sp, modifier = Modifier.padding(vertical = 16.dp))
                } else {
                    // 供应商分组 + 模型列表（纵向滚动）
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                    ) {
                        catalog.groups.forEach { group ->
                            Text(
                                group.provider,
                                color = Dsh.labelTertiary,
                                fontSize = 12.sp,
                                lineHeight = 18.sp,
                                modifier = Modifier.padding(top = 12.dp, bottom = 4.dp, start = 4.dp)
                            )
                            group.models.forEach { model ->
                                val selected = model.id == catalog.currentModel && group.provider == catalog.currentProvider
                                val interaction = remember { MutableInteractionSource() }
                                val pressed by interaction.collectIsPressedAsState()
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (pressed || selected) Dsh.hover else Color.Transparent)
                                        .clickable(interactionSource = interaction, indication = null) {
                                            onSelect(group.provider, model.id)
                                        }
                                        .padding(horizontal = 10.dp, vertical = 6.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        if (selected) {
                                            Box(
                                                modifier = Modifier
                                                    .size(6.dp)
                                                    .clip(CircleShape)
                                                    .background(Dsh.brand400)
                                            )
                                            Spacer(Modifier.width(10.dp))
                                        }
                                        Text(
                                            model.name ?: model.id,
                                            color = Dsh.labelPrimary,
                                            fontSize = 14.sp,
                                            lineHeight = 20.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f)
                                        )
                                        if (model.contextWindow != null) {
                                            Text(
                                                formatTokens(model.contextWindow),
                                                color = Dsh.labelTertiary,
                                                fontSize = 12.sp,
                                                lineHeight = 20.sp
                                            )
                                        }
                                    }
                                    // 推理等级（DSH reasoning efforts）
                                    if (model.reasoningEfforts.isNotEmpty()) {
                                        Spacer(Modifier.height(2.dp))
                                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                            model.reasoningEfforts.take(4).forEach { effort ->
                                                Box(
                                                    modifier = Modifier
                                                        .clip(RoundedCornerShape(4.dp))
                                                        .background(Dsh.bgLayer3)
                                                        .padding(horizontal = 5.dp, vertical = 1.dp)
                                                ) {
                                                    Text(
                                                        effort.replaceFirstChar { it.uppercase() },
                                                        color = Dsh.labelTertiary,
                                                        fontSize = 10.sp,
                                                        lineHeight = 14.sp
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
            }
        }
    }
}

// ---------- 会话行（Rows.module.css：32dp 高，操作菜单：重命名/分叉） ----------

@Composable
private fun SessionRowItem(
    session: MobileSession,
    isSelected: Boolean,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onFork: () -> Unit,
    onArchive: () -> Unit = {},
    onDelete: () -> Unit = {},
) {
    var menuOpen by remember { mutableStateOf(false) }
    val itemInteraction = remember { MutableInteractionSource() }
    val itemPressed by itemInteraction.collectIsPressedAsState()
    // 外层 Row：将 Row 点击和菜单按钮分开，避免点击菜单时触发 Row 导航
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(32.dp)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 可点击的行内容（状态点 + 标题 + 时间）
        Row(
            modifier = Modifier
                .weight(1f)
                .height(32.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(
                    if (isSelected || itemPressed) Dsh.hover else Color.Transparent
                )
                .clickable(interactionSource = itemInteraction, indication = null, onClick = onClick)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 状态点槽（16x20）
            Box(
                modifier = Modifier.size(width = 16.dp, height = 20.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                if (session.running) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(Dsh.brand400)
                    )
                } else if (isSelected) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(Dsh.labelCaption)
                    )
                }
            }
            Text(
                session.title,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                fontWeight = if (isSelected) FontWeight(500) else FontWeight(400),
                color = Dsh.labelPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 4.dp, end = 6.dp)
            )
            // 时间（12sp tertiary）
            if (!session.blank) {
                Text(
                    relativeTime(session.updatedAt),
                    color = Dsh.labelTertiary,
                    fontSize = 12.sp,
                    lineHeight = 20.sp,
                    maxLines = 1
                )
            }
        }

        // 操作菜单按钮（独立于 Row 点击区域）
        Box {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { menuOpen = true },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    EllipsisOutline16,
                    contentDescription = "会话操作",
                    tint = Dsh.labelTertiary,
                    modifier = Modifier.size(14.dp)
                )
            }
            // 操作菜单（重命名 / 分叉 / 归档 / 删除）
            DshMenu(
                expanded = menuOpen,
                onDismiss = { menuOpen = false },
                items = listOf(
                    DshMenuItem(EditOutline16, "重命名") {
                        menuOpen = false
                        onRename()
                    },
                    DshMenuItem(BranchOutline16, "分叉会话") {
                        menuOpen = false
                        onFork()
                    },
                    DshMenuItem(ArchiveOutline20, "归档会话") {
                        menuOpen = false
                        onArchive()
                    },
                    DshMenuItem(TrashOutline16, "删除会话", danger = true) {
                        menuOpen = false
                        onDelete()
                    },
                )
            )
        }
    }
}

// ---------- DSH 风格菜单浮层（12dp 圆角 + L2 细边框 + 图标菜单项） ----------

private data class DshMenuItem(
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val label: String,
    val danger: Boolean = false,
    val onClick: () -> Unit,
)

@Composable
private fun DshMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    items: List<DshMenuItem>,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        containerColor = Dsh.bgLayer1,
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 0.dp,
        shadowElevation = 12.dp,
        border = BorderStroke(1.dp, Dsh.borderL2)
    ) {
        Column(
            modifier = Modifier
                .width(196.dp)
                .padding(vertical = 4.dp)
        ) {
            items.forEach { item ->
                val interaction = remember { MutableInteractionSource() }
                val pressed by interaction.collectIsPressedAsState()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(38.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (pressed) Dsh.hover else Color.Transparent)
                        .clickable(interactionSource = interaction, indication = null, onClick = item.onClick)
                        .padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        item.icon,
                        contentDescription = null,
                        tint = if (item.danger) Dsh.error else Dsh.labelSecondary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        item.label,
                        color = if (item.danger) Dsh.error else Dsh.labelPrimary,
                        fontSize = 13.sp,
                        lineHeight = 20.sp
                    )
                }
            }
        }
    }
}

// ---------- 工具审批卡（DSH ApprovalPanel：等待审批 + 允许一次/拒绝） ----------

@Composable
private fun ApprovalCard(
    msg: MobileMessage,
    onAnswer: (approvalId: String, outcome: String) -> Unit,
) {
    var answered by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Dsh.bgInput)
            .border(1.dp, Dsh.warn.copy(alpha = 0.5f), RoundedCornerShape(20.dp))
    ) {
        // 警告条（DSH strip：warn 色 + 圆点 + 等待审批）——背景用 warn 色系而非 brand800（原用错 token）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Dsh.warn.copy(alpha = 0.15f))
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(Dsh.warn)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "等待审批",
                color = Dsh.warn,
                fontSize = 13.sp,
                lineHeight = 18.sp
            )
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Text(
                msg.text,
                color = Dsh.labelPrimary,
                fontSize = 13.sp,
                lineHeight = 20.sp
            )
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                val rejectInteraction = remember { MutableInteractionSource() }
                val rejectPressed by rejectInteraction.collectIsPressedAsState()
                val rejectScale by animateFloatAsState(if (rejectPressed) 0.94f else 1f)
                val rejectBg by animateColorAsState(
                    targetValue = if (rejectPressed) Dsh.hover else Color.Transparent,
                    animationSpec = tween(motionDuration(120)),
                    label = "rejectBg"
                )
                Box(
                    modifier = Modifier
                        .height(30.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(rejectBg)
                        .graphicsLayer(scaleX = rejectScale, scaleY = rejectScale)
                        .clickable(
                            interactionSource = rejectInteraction,
                            indication = null,
                            enabled = !answered
                        ) {
                            answered = true
                            msg.approvalId?.let { onAnswer(it, "rejected") }
                        }
                        .padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "拒绝",
                        color = if (answered) Dsh.labelTertiary else Dsh.labelSecondary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight(500),
                        lineHeight = 20.sp
                    )
                }
                Spacer(Modifier.width(10.dp))
                Box(
                    modifier = Modifier
                        .height(30.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(if (answered) Dsh.brand400.copy(alpha = 0.4f) else Dsh.brand400)
                        .clickable(enabled = !answered) {
                            answered = true
                            msg.approvalId?.let { onAnswer(it, "allowed-once") }
                        }
                        .padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        if (answered) "已处理" else "允许一次",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight(500),
                        lineHeight = 20.sp
                    )
                }
            }
        }
    }
}

// ---------- 上下文计量（DSH ContextMeter：28px 环形 + 用量面板） ----------

@Composable
private fun ContextMeterButton(stats: MobileSessionStats) {
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
        // 环形按钮（DSH：28px trigger，14px viewBox 圆环，2px stroke）
        val interaction = remember { MutableInteractionSource() }
        val pressed by interaction.collectIsPressedAsState()
        val borderL3Color = Dsh.borderL3
        val fillColor = Dsh.labelTertiary
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(if (pressed) Dsh.hover else Color.Transparent)
                .clickable(interactionSource = interaction, indication = null) { expanded = true }
                .minimumInteractiveComponentSize(),
            contentAlignment = Alignment.Center
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
        }

        // 用量面板（DSH：264px 宽、radius 12、上方弹出）
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = Dsh.bgLayer3,
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.width(240.dp).padding(12.dp)) {
                // header：上下文已用 + 百分比 + 用量数字
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("上下文已用", color = Dsh.labelTertiary, fontSize = 12.sp, lineHeight = 20.sp)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "${percent.toInt()}%",
                        color = Dsh.labelPrimary,
                        fontSize = 12.sp,
                        lineHeight = 20.sp,
                        fontWeight = FontWeight(500)
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        "~${formatTokens(used)} / ${formatTokens(window)}",
                        color = Dsh.labelPrimary,
                        fontSize = 12.sp,
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
                        .background(Dsh.hover)
                ) {
                    if (hasBreakdown) {
                        val segments = listOf(
                            Triple(systemRatio, DshColorSystem, Dsh.success.copy(alpha = 0.9f)),
                            Triple(toolsRatio, DshColorTools, DshColorTools),
                            Triple(messagesRatio, Dsh.brand450, Dsh.brand450),
                        )
                        segments.forEach { (ratio, _, color) ->
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
                ContextMeterRow("系统提示词", formatTokens(stats.systemTokens), DshColorSystem)
                Spacer(Modifier.height(4.dp))
                ContextMeterRow("工具", formatTokens(stats.toolsTokens), DshColorTools)
                Spacer(Modifier.height(4.dp))
                ContextMeterRow("对话消息", formatTokens(stats.messageTokens), Dsh.brand450)
            }
        }
    }
}

// dsw 分段色：系统=neutral bluish-400、工具=#a78bfa、消息=blue-450
private val DshColorSystem = Color(0xFF7D8590)
private val DshColorTools = Color(0xFFA78BFA)

@Composable
private fun ContextMeterRow(label: String, value: String, swatchColor: Color) {
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
        Text(label, color = Dsh.labelSecondary, fontSize = 12.sp, lineHeight = 18.sp, modifier = Modifier.weight(1f))
        Text(value, color = Dsh.labelPrimary, fontSize = 12.sp, lineHeight = 18.sp, fontFamily = FontFamily.Monospace)
    }
}

// ---------- 命令候选（DSH CommandPalette：typed entries + 本地动作分发） ----------
//
// 旧实现使用静态 `List<Pair<String, List<Pair<String, String>>>>`（命令/技能/子智能体
// 三组），所有条目在选中时都直接发送到服务端。新实现改为 typed model：
//  - Completable：picker 选中后直接提交到服务端并清空输入框
//  - Insertable（/plan /goal /subagent）：picker 选中后插入到输入框并取得焦点，
//    让用户补完参数再发送（避免误触发某些需要参数的命令）
//  - Local（/search /new-session /settings /chat /trace /model /permission）：本地动作，
//    仅作为 slash 入口的"发现"路径，picker 选中时由调用方分发到 UI 层
//
// 过滤 / 分组逻辑见 CommandPalette.kt 的 filterPalette()，可在 JVM 单测中独立验证。

@Composable
private fun CommandSuggestions(
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
                .widthIn(max = COMPOSER_MAX_WIDTH)
                .clip(RoundedCornerShape(12.dp))
                .background(Dsh.bgLayer1)
                .border(1.dp, Dsh.borderL2, RoundedCornerShape(12.dp))
                .padding(vertical = 6.dp)
        ) {
            grouped.forEach { (group, entries) ->
                Text(
                    group.displayName,
                    color = Dsh.labelTertiary,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp)
                )
                entries.forEach { entry ->
                    val interaction = remember { MutableInteractionSource() }
                    val pressed by interaction.collectIsPressedAsState()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(36.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (pressed) Dsh.hover else Color.Transparent)
                            .clickable(interactionSource = interaction, indication = null) { onPick(entry.command) }
                            .padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            entry.command.trigger,
                            color = Dsh.labelPrimary,
                            fontSize = 13.sp,
                            lineHeight = 20.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.width(220.dp)
                        )
                        Text(
                            entry.command.description,
                            color = Dsh.labelTertiary,
                            fontSize = 12.sp,
                            lineHeight = 18.sp,
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

// ---------- 历史视图（DSH AppHistoryView：搜索会话 + 会话列表） ----------
//
// 设计要点（搜索）：
//  - 250ms debounce + 序列号丢弃过期响应，防止输入抖动
//  - 状态机: Idle / Loading / Empty / Error(message) / Results(count)
//  - error 时显示重试按钮；再次输入即恢复（无需手动刷新）
//
// 设计要点（列表）：
//  - bounded LazyColumn：超过 HISTORY_LIST_LIMIT 行时不渲染尾部，避免大列表让 ChatView 历史首次渲染卡顿
//  - 空状态："暂无会话"（无任何会话时）/ "无匹配会话"（搜索无果）
//
// 焦点 / IME 行为：
//  - 搜索框无 FocusRequester：history 视图是从顶栏 tabs 触发的全局视图，
//    进入时不主动抢焦点（避免与 drawer 打开后的 IME 互相打架）
//  - 进入时由外层 LaunchedEffect(viewMode) 触发 onQueryChange("") 或
//    debounced re-search，不会重复执行

/** 历史列表上限：超过此值不渲染尾部，给 LazyColumn 一个稳定渲染区间 */
private const val HISTORY_LIST_LIMIT = 200

@Composable
private fun HistoryView(
    sessions: List<MobileSession>,
    searchQuery: String,
    searchResults: List<MobileSearchResult>,
    searchState: SearchUiState,
    onQueryChange: (String) -> Unit,
    onOpenSession: (String) -> Unit,
    onRetry: () -> Unit,
    onClose: () -> Unit,
) {
    // 系统返回 / 手势返回：从 history 回到 chat（全局 history 仍然持久化 query）
    androidx.activity.compose.BackHandler(enabled = true) { onClose() }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
    ) {
        Spacer(Modifier.height(12.dp))
        // 搜索框（DSH search.placeholder：搜索会话…）
        val searchInteraction = remember { MutableInteractionSource() }
        val searchPressed by searchInteraction.collectIsPressedAsState()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Dsh.bgInput)
                .border(1.dp, if (searchPressed) Dsh.borderL3 else Dsh.borderL2, RoundedCornerShape(12.dp))
                .clickable(interactionSource = searchInteraction, indication = null) {}
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                SearchOutline16,
                contentDescription = null,
                tint = Dsh.labelCaption,
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(8.dp))
            BasicTextField(
                value = searchQuery,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = TextStyle(color = Dsh.labelPrimary, fontSize = 14.sp),
                cursorBrush = SolidColor(Dsh.brand400),
                modifier = Modifier.weight(1f),
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (searchQuery.isEmpty()) Text("搜索会话…", color = Dsh.labelTertiary, fontSize = 14.sp)
                        inner()
                    }
                }
            )
            // 加载指示器：仅 Loading 时显示（与搜索文本右对齐）
            if (searchState is SearchUiState.Loading) {
                val spin = rememberMotionSpin(750, label = "searchSpin")
                Box(
                    modifier = Modifier
                        .size(14.dp)
                        .rotate(spin ?: 0f)
                        .border(1.dp, Dsh.labelCaption, CircleShape)
                )
                Spacer(Modifier.width(6.dp))
            }
            if (searchQuery.isNotEmpty()) {
                Icon(
                    CloseOutline16,
                    contentDescription = "清除搜索",
                    tint = Dsh.labelCaption,
                    modifier = Modifier
                        .size(16.dp)
                        .clickable { onQueryChange("") }
                )
            }
        }
        Spacer(Modifier.height(12.dp))

        // 错误条：error 时显示 message + 重试按钮
        if (searchState is SearchUiState.Error) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Dsh.bgLayer1)
                    .border(1.dp, Dsh.borderL2, RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "搜索失败：${searchState.message}",
                    color = Dsh.labelSecondary,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    modifier = Modifier.weight(1f)
                )
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(Dsh.brand400)
                        .clickable { onRetry() }
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text("重试", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight(500))
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        if (searchQuery.isNotBlank()) {
            // 搜索结果（DSH 服务器内容搜索）
            when (searchState) {
                is SearchUiState.Loading -> {
                    Text(
                        "搜索中…",
                        color = Dsh.labelTertiary,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(vertical = 20.dp)
                    )
                }
                is SearchUiState.Empty, is SearchUiState.Error -> {
                    // Empty / Error 状态互斥：error 优先渲染（已在顶部错误条给重试入口）
                    if (searchState is SearchUiState.Empty) {
                        Text(
                            "无匹配会话",
                            color = Dsh.labelTertiary,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(vertical = 20.dp)
                        )
                    }
                }
                else -> {
                    if (searchResults.isEmpty() && searchState is SearchUiState.Idle) {
                        Text(
                            "无匹配会话",
                            color = Dsh.labelTertiary,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(vertical = 20.dp)
                        )
                    } else {
                        // 标题 + bounded LazyColumn
                        Text(
                            "搜索结果",
                            color = Dsh.labelTertiary,
                            fontSize = 12.sp,
                            lineHeight = 18.sp,
                            modifier = Modifier.padding(start = 4.dp, bottom = 6.dp)
                        )
                        val bounded = remember(searchResults) {
                            searchResults.take(HISTORY_LIST_LIMIT)
                        }
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            items(bounded, key = { "${it.sessionId}:${it.snippet.hashCode()}" }) { r ->
                                val s = sessions.find { it.sessionId == r.sessionId }
                                if (s != null) {
                                    HistoryRow(
                                        session = s,
                                        onClick = { onOpenSession(s.sessionId) }
                                    )
                                } else {
                                    HistorySnippetRow(
                                        snippet = r.snippet,
                                        onClick = { onOpenSession(r.sessionId) }
                                    )
                                }
                            }
                            if (searchResults.size > HISTORY_LIST_LIMIT) {
                                item(key = "history-overflow") {
                                    Text(
                                        "已显示前 $HISTORY_LIST_LIMIT 条，共 ${searchResults.size} 条",
                                        color = Dsh.labelCaption,
                                        fontSize = 11.sp,
                                        lineHeight = 16.sp,
                                        modifier = Modifier.padding(8.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        } else {
            // 全部会话列表（DSH HistoryView：按更新时间）
            val all = remember(sessions) { sessions.sortedByDescending { it.updatedAt } }
            if (all.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "暂无会话",
                        color = Dsh.labelTertiary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight(500)
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "新建一段对话后会出现在这里",
                        color = Dsh.labelCaption,
                        fontSize = 12.sp,
                        lineHeight = 18.sp
                    )
                }
            } else {
                val bounded = remember(all) { all.take(HISTORY_LIST_LIMIT) }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    items(bounded.size) { i ->
                        HistoryRow(
                            session = bounded[i],
                            onClick = { onOpenSession(bounded[i].sessionId) }
                        )
                    }
                    if (all.size > HISTORY_LIST_LIMIT) {
                        item(key = "history-overflow") {
                            Text(
                                "已显示前 $HISTORY_LIST_LIMIT 条，共 ${all.size} 条",
                                color = Dsh.labelCaption,
                                fontSize = 11.sp,
                                lineHeight = 16.sp,
                                modifier = Modifier.padding(8.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 搜索结果仅返回 sessionId + snippet 而服务端没列出 session 时的兜底行 */
@Composable
private fun HistorySnippetRow(snippet: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (pressed) Dsh.hover else Color.Transparent)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            snippet,
            color = Dsh.labelPrimary,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun HistoryRow(
    session: MobileSession,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (pressed) Dsh.hover else Color.Transparent)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(width = 12.dp, height = 20.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            if (session.running) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(Dsh.brand400)
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                session.title,
                color = Dsh.labelPrimary,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (session.cwd != null) {
                Text(
                    session.cwd,
                    color = Dsh.labelTertiary,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(
            relativeTime(session.updatedAt),
            color = Dsh.labelTertiary,
            fontSize = 12.sp,
            lineHeight = 20.sp
        )
    }
}

// ---------- 轨迹视图（TrajectoryView：时间轴渲染会话每一步执行） ----------

private fun formatTraceDuration(ms: Long): String = when {
    ms >= 1000 -> String.format(Locale.US, "+%.1fs", ms / 1000.0)
    else -> "+${ms}ms"
}

@Composable
private fun TrajectoryView(
    messages: List<MobileMessage>,
    stats: MobileSessionStats?,
    running: Boolean,
    elapsedSec: Long,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        if (messages.isEmpty()) {
            Text(
                "暂无轨迹数据。\n开始一段对话后，每一步执行（思考、工具调用、结果与回答）都会记录在这里。",
                color = Dsh.labelTertiary,
                fontSize = 13.sp,
                lineHeight = 20.sp,
                modifier = Modifier.padding(vertical = 24.dp)
            )
            return@Column
        }
        stats?.let { StatsLine(it) }
        if (running) {
            Spacer(Modifier.height(8.dp))
            TurnStatusRow(elapsedSec)
        }
        Spacer(Modifier.height(18.dp))
        messages.forEachIndexed { index, msg ->
            val nextTime = messages.getOrNull(index + 1)?.time ?: 0L
            val durationMs = if (msg.time > 0 && nextTime > msg.time) nextTime - msg.time else null
            TraceStepRow(
                number = index + 1,
                total = messages.size,
                msg = msg,
                durationMs = durationMs,
                running = running && index == messages.lastIndex,
            )
        }
    }
}

@Composable
private fun TraceStepRow(
    number: Int,
    total: Int,
    msg: MobileMessage,
    durationMs: Long?,
    running: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
    ) {
        // 左侧：序号节点 + 竖向连接线
        Column(
            modifier = Modifier.width(26.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(if (running) Dsh.brand400 else Dsh.bgLayer1)
                    .border(
                        1.dp,
                        if (running) Dsh.brand400 else Dsh.borderL2,
                        CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "$number",
                    color = if (running) Color.White else Dsh.labelTertiary,
                    fontSize = 10.sp,
                    lineHeight = 14.sp,
                    fontWeight = FontWeight(500)
                )
            }
            if (number < total) {
                Box(
                    modifier = Modifier
                        .width(2.dp)
                        .weight(1f)
                        .background(Dsh.borderL1)
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        // 右侧：步骤内容
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(bottom = 18.dp)
        ) {
            TraceStepHeader(msg = msg, durationMs = durationMs)
            Spacer(Modifier.height(6.dp))
            TraceStepBody(msg = msg, running = running)
        }
    }
}

@Composable
private fun TraceStepHeader(msg: MobileMessage, durationMs: Long?) {
    val chipLabel: String
    val chipColor: Color
    when (msg.role) {
        "user" -> { chipLabel = "目标"; chipColor = Dsh.labelPrimary }
        "reasoning" -> { chipLabel = "思考"; chipColor = Dsh.labelTertiary }
        "tool_call" -> { chipLabel = "工具调用"; chipColor = Dsh.brand400 }
        "tool_result" -> { chipLabel = "执行结果"; chipColor = Dsh.success }
        "approval" -> { chipLabel = "授权"; chipColor = Dsh.warn }
        "todo" -> { chipLabel = "任务"; chipColor = Dsh.brand400 }
        "compaction" -> { chipLabel = "压缩"; chipColor = Dsh.warn }
        else -> { chipLabel = "回答"; chipColor = Dsh.labelPrimary }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        DshTag(
            text = chipLabel,
            color = chipColor.copy(alpha = 0.12f),
            contentColor = chipColor,
            shape = RoundedCornerShape(999.dp),
            contentDescription = "步骤类型：$chipLabel",
        )
        if (msg.role == "tool_call") {
            Spacer(Modifier.width(6.dp))
            Text(
                msg.toolName ?: "工具调用",
                color = Dsh.labelSecondary,
                fontSize = 12.sp,
                lineHeight = 18.sp,
                fontWeight = FontWeight(500),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        } else {
            Spacer(Modifier.weight(1f))
        }
        if (durationMs != null && msg.role != "user") {
            Text(
                formatTraceDuration(durationMs),
                color = Dsh.labelCaption,
                fontSize = 11.sp,
                lineHeight = 16.sp,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

@Composable
private fun TraceStepBody(msg: MobileMessage, running: Boolean) {
    when (msg.role) {
        "tool_call" -> TraceCodeBlock(msg.toolArgs ?: msg.text, running)
        "tool_result" -> TraceCodeBlock(msg.text, running)
        "reasoning" -> TraceExpandableText(msg.text, maxLines = 4)
        "todo" -> {
            val done = msg.todos.count { it.status == "completed" }
            Text(
                if (msg.todos.isNotEmpty()) "任务清单更新 · $done/${msg.todos.size} 已完成" else "任务清单已更新",
                color = Dsh.labelSecondary,
                fontSize = 13.sp,
                lineHeight = 20.sp
            )
        }
        "compaction" -> Text(
            if (msg.running == true) "正在压缩…" else msg.text.lineSequence().firstOrNull().orEmpty().ifBlank { "上下文已压缩" },
            color = Dsh.labelSecondary,
            fontSize = 13.sp,
            lineHeight = 20.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        else -> Text(
            msg.text,
            color = Dsh.labelSecondary,
            fontSize = 13.sp,
            lineHeight = 20.sp
        )
    }
}

@Composable
private fun TraceCodeBlock(text: String, running: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Dsh.bgCode)
            .border(
                1.dp,
                if (running) Dsh.brand400.copy(alpha = 0.5f) else Dsh.borderL1,
                RoundedCornerShape(10.dp)
            )
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        TraceExpandableText(text, maxLines = 5, mono = true)
    }
}

@Composable
private fun TraceExpandableText(text: String, maxLines: Int = 4, mono: Boolean = false) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text(
            text,
            color = Dsh.labelSecondary,
            fontSize = if (mono) 12.sp else 13.sp,
            lineHeight = if (mono) 17.sp else 20.sp,
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
            maxLines = if (expanded) Int.MAX_VALUE else maxLines,
            overflow = TextOverflow.Ellipsis
        )
        if (text.length > (if (mono) 140 else 100)) {
            Text(
                if (expanded) "收起" else "展开",
                color = Dsh.brand400,
                fontSize = 11.sp,
                lineHeight = 16.sp,
                modifier = Modifier
                    .padding(top = 2.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 4.dp, vertical = 2.dp)
            )
        }
    }
}

// ---------- 工作区行（hero 状态下显示在输入卡上方，DSH composer workspace row） ----------

@Composable
private fun WorkspaceComposerRow(
    sessions: List<MobileSession>,
    onStartSession: (String?) -> Unit,
) {
    val workspaces = remember(sessions) {
        sessions.mapNotNull { it.cwd }.distinct().filter { isUserWorkspace(it) }.sorted()
    }
    var showPicker by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = COMPOSER_SIDE_CLEARANCE, vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = COMPOSER_MAX_WIDTH)
                .heightIn(min = 28.dp)
                .padding(start = 8.dp)
                .clip(RoundedCornerShape(8.dp))
                .clickable { showPicker = true }
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                FolderOpenOutline16,
                contentDescription = null,
                tint = Dsh.labelPrimary,
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(4.dp))
            Text(
                "选择一个工作区开始",
                color = Dsh.labelPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight(500),
                lineHeight = 20.sp
            )
            Spacer(Modifier.width(4.dp))
            Icon(
                ChevronDownOutline14,
                contentDescription = null,
                tint = Dsh.labelCaption,
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.weight(1f))
            // 已选工作区（第一个用户工作区）
            if (workspaces.isNotEmpty()) {
                Text(
                    workspaces.first(),
                    color = Dsh.labelTertiary,
                    fontSize = 11.sp,
                    lineHeight = 18.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }

    if (showPicker) {
        WorkspacePickerSheet(
            sessions = sessions,
            onDismiss = { showPicker = false },
            onPick = { cwd ->
                showPicker = false
                onStartSession(cwd)
            }
        )
    }
}

// ---------- 权限选择弹层（WI-004：真实写入服务端 permission.defaultPreset） ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PermissionPickerSheet(
    context: android.content.Context,
    host: Host,
    currentPreset: String,
    onSaved: (AppSettings) -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var selected by remember { mutableStateOf(currentPreset) }
    var showFullAccessConfirm by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun apply(preset: String) {
        if (saving) return
        saving = true
        error = null
        scope.launch(Dispatchers.IO) {
            try {
                AppSettingsStore.save(host, context, "permission", org.json.JSONObject().put("defaultPreset", preset))
                withContext(Dispatchers.Main) {
                    saving = false
                    onSaved(AppSettingsStore.cached(context))
                    onDismiss()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    saving = false
                    error = e.message ?: "保存失败，请重试"
                }
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = { if (!saving) onDismiss() },
        containerColor = Dsh.bgLayer1,
        contentColor = Dsh.labelPrimary,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        dragHandle = null,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
        ) {
            Text("访问模式", color = Dsh.labelPrimary, fontSize = 18.sp, fontWeight = FontWeight(500), lineHeight = 24.sp)
            Spacer(Modifier.height(4.dp))
            Text("选择新会话的默认权限模式", color = Dsh.labelTertiary, fontSize = 12.sp, lineHeight = 18.sp)
            Spacer(Modifier.height(16.dp))
            listOf(
                Triple("read-only", "只读", "agent 只读，所有写入操作需确认"),
                Triple("workspace-write", "工作区写入", "允许 agent 在工作区内修改文件"),
                Triple("danger-full-access", "完全访问", "减少确认步骤，可执行敏感操作与外部命令"),
            ).forEach { (id, title, desc) ->
                val isSelected = id == selected
                val interaction = remember { MutableInteractionSource() }
                val pressed by interaction.collectIsPressedAsState()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (pressed || isSelected) Dsh.hover else Color.Transparent)
                        .clickable(interactionSource = interaction, indication = null, enabled = !saving) {
                            selected = id
                            if (id == "danger-full-access") {
                                showFullAccessConfirm = true
                            } else {
                                apply(id)
                            }
                        }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(title, color = Dsh.labelPrimary, fontSize = 14.sp, lineHeight = 20.sp)
                        Text(desc, color = Dsh.labelTertiary, fontSize = 12.sp, lineHeight = 17.sp)
                    }
                    if (isSelected) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(Dsh.brand400)
                        )
                    }
                }
            }
            // 保存失败：行内错误 + 重试（不关闭弹层）
            if (saving) {
                Spacer(Modifier.height(8.dp))
                Text("保存中…", color = Dsh.labelTertiary, fontSize = 12.sp)
            }
            if (error != null) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("保存失败：$error", color = Dsh.error, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(7.dp))
                            .background(Dsh.bgLayer1)
                            .clickable(enabled = !saving) { apply(selected) }
                            .padding(horizontal = 12.dp, vertical = 5.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("重试", color = Dsh.brand400, fontSize = 12.sp, fontWeight = FontWeight(500))
                    }
                }
            }
        }
    }

    // Full access 确认
    if (showFullAccessConfirm) {
        val (dlgAlpha, dlgScale) = dialogEnterState()
        Dialog(
            onDismissRequest = { showFullAccessConfirm = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = dlgAlpha.value }
                    .background(Dsh.bgOverlay)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { showFullAccessConfirm = false },
                contentAlignment = Alignment.Center
            ) {
                Column(
                    modifier = Modifier
                        .widthIn(max = 360.dp)
                        .fillMaxWidth(0.9f)
                        .graphicsLayer {
                            alpha = dlgAlpha.value
                            scaleX = dlgScale.value
                            scaleY = dlgScale.value
                        }
                        .clip(RoundedCornerShape(14.dp))
                        .background(Dsh.bgLayer1)
                        .border(1.dp, Dsh.borderL2, RoundedCornerShape(14.dp))
                        .padding(18.dp)
                ) {
                    Text("确认启用 Full access？", color = Dsh.labelPrimary, fontSize = 15.sp, fontWeight = FontWeight(500), lineHeight = 21.sp)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "启用 Full access 后，agent 将减少确认步骤，并且可以直接执行更多操作，包括敏感操作、文件修改或外部命令。仅建议在你信任当前任务时使用。",
                        color = Dsh.labelTertiary,
                        fontSize = 12.sp,
                        lineHeight = 18.sp
                    )
                    Spacer(Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        Box(
                            modifier = Modifier
                                .height(34.dp)
                                .clip(RoundedCornerShape(7.dp))
                                .clickable { showFullAccessConfirm = false }
                                .padding(horizontal = 14.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("取消", color = Dsh.labelSecondary, fontSize = 12.sp, fontWeight = FontWeight(500))
                        }
                        Spacer(Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .height(34.dp)
                                .clip(RoundedCornerShape(7.dp))
                                .background(Dsh.error)
                                .clickable(enabled = !saving) {
                                    showFullAccessConfirm = false
                                    apply("danger-full-access")
                                }
                                .padding(horizontal = 14.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("启用 Full access", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight(500))
                        }
                    }
                }
            }
        }
    }
}

// ---------- 发送队列 dock（DSH QueueDock：输入卡上方，发送中显示） ----------

@Composable
private fun QueueDock() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = COMPOSER_SIDE_CLEARANCE, vertical = 0.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = COMPOSER_MAX_WIDTH)
                .height(36.dp)
                .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                .background(Dsh.bgLayer1)
                .border(1.dp, Dsh.borderL1, RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 旋转小圈
            val angle = rememberMotionSpin(750, label = "dock")
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .rotate(angle ?: 0f)
                    .border(1.5.dp, Dsh.brand400, CircleShape)
            )
            Spacer(Modifier.width(10.dp))
            Text(
                "正在发送…",
                color = Dsh.labelSecondary,
                fontSize = 12.sp,
                lineHeight = 18.sp
            )
            Spacer(Modifier.weight(1f))
            Text(
                "排队中",
                color = Dsh.labelTertiary,
                fontSize = 11.sp,
                lineHeight = 16.sp
            )
        }
    }
}

// ---------- 输入卡（InputBar 1:1） ----------

@Composable
private fun InputBar(
    inputText: String,
    onInputChange: (String) -> Unit,
    pendingImages: List<Pair<String, String>> = emptyList(),
    onRemoveImage: (Int) -> Unit = {},
    onPickImage: () -> Unit = {},
    isListening: Boolean,
    isSending: Boolean,
    canSend: Boolean,
    running: Boolean,
    currentModel: String?,
    sessionStats: MobileSessionStats?,
    heroMode: HeroMode,
    permissionLabel: String,
    onOpenModelPicker: () -> Unit,
    onOpenPermissionPicker: () -> Unit,
    onToggleVoice: () -> Unit,
    onStop: () -> Unit,
    onSend: () -> Unit,
    composerFocusRequester: androidx.compose.ui.focus.FocusRequester? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = COMPOSER_SIDE_CLEARANCE, end = COMPOSER_SIDE_CLEARANCE),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 输入卡主体（radius 22, bg #2C2C2E, border rgba(255,255,255,.06)）
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = COMPOSER_MAX_WIDTH)
                .clip(RoundedCornerShape(22.dp))
                .background(Dsh.bgInput)
                .border(1.dp, Dsh.borderL2, RoundedCornerShape(22.dp))
                .padding(top = 10.dp)
        ) {
            // 待发送图片缩略图（DSH 待发送图片行）
            if (pendingImages.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 12.dp, top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    pendingImages.forEachIndexed { index, (_, data) ->
                        Box {
                            coil.compose.AsyncImage(
                                model = coil.request.ImageRequest.Builder(androidx.compose.ui.platform.LocalContext.current)
                                    .data(android.util.Base64.decode(data, android.util.Base64.DEFAULT))
                                    .build(),
                                contentDescription = "待发送图片",
                                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                modifier = Modifier
                                    .size(56.dp)
                                    .clip(RoundedCornerShape(10.dp))
                            )
                            // 移除按钮
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .size(20.dp)
                                    .clip(CircleShape)
                                    .background(Dsh.bgLayer3)
                                    .clickable { onRemoveImage(index) },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    CloseOutline16,
                                    contentDescription = "移除图片",
                                    tint = Dsh.labelSecondary,
                                    modifier = Modifier.size(12.dp)
                                )
                            }
                        }
                    }
                }
            }
            // 文本输入（padding 4px 12px 0 16px, 16px/24px, caret 品牌蓝）
            BasicTextField(
                value = inputText,
                onValueChange = onInputChange,
                textStyle = TextStyle(
                    color = Dsh.labelPrimary,
                    fontSize = 16.sp,
                    lineHeight = 24.sp,
                ),
                cursorBrush = SolidColor(Dsh.brand400),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp, max = 200.dp)
                    .padding(start = 16.dp, end = 12.dp, top = 4.dp)
                    .let { base ->
                        if (composerFocusRequester != null) base.focusRequester(composerFocusRequester) else base
                    },
                decorationBox = { innerTextField ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (inputText.isEmpty()) {
                            Text(
                                when {
                                    isListening -> "正在倾听…"
                                    heroMode == HeroMode.PLAN -> "描述你的任务以生成计划"
                                    heroMode == HeroMode.GOAL -> "当前目标进行中。可输入 edit 修改 / pause 暂停 / resume 继续 / clear 清除"
                                    else -> "给智能体发消息"
                                },
                                color = Dsh.labelTertiary,
                                fontSize = 16.sp,
                                lineHeight = 24.sp
                            )
                        }
                        innerTextField()
                    }
                }
            )

            // 底部工具行（padding 2px 8px 6px）—— 对标 web UI toolbar 布局
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp, end = 8.dp, top = 2.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 左侧：+ 按钮（DSH input add：图片/附件）
                if (!running) {
                    RoundIconButton(
                        icon = PlusOutline16,
                        tint = Dsh.labelPrimary,
                        contentDescription = "添加附件",
                        onClick = onPickImage
                    )
                    Spacer(Modifier.width(6.dp))
                }

                // 访问模式（DSH input.accessMode）
                if (!running) {
                    val permInteraction = remember { MutableInteractionSource() }
                    val permPressed by permInteraction.collectIsPressedAsState()
                    Row(
                        modifier = Modifier
                            .height(28.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .background(if (permPressed) Dsh.hover else Color.Transparent)
                            .clickable(interactionSource = permInteraction, indication = null, onClick = onOpenPermissionPicker)
                            .minimumInteractiveComponentSize()
                            .padding(start = 8.dp, end = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            WarningOutline16,
                            contentDescription = null,
                            tint = Dsh.labelSecondary,
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            permissionLabel,
                            color = Dsh.labelSecondary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight(500),
                            lineHeight = 20.sp
                        )
                        Icon(
                            ChevronDownOutline14,
                            contentDescription = null,
                            tint = Dsh.labelCaption,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }

                if (running) {
                    val stopInteraction = remember { MutableInteractionSource() }
                    val stopPressed by stopInteraction.collectIsPressedAsState()
                    Box(
                        modifier = Modifier
                            .height(28.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .background(if (stopPressed) Dsh.hover else Color.Transparent)
                            .clickable(interactionSource = stopInteraction, indication = null, onClick = onStop)
                            .padding(horizontal = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("停止生成", color = Dsh.labelSecondary, fontSize = 12.sp, fontWeight = FontWeight(500))
                    }
                }

                Spacer(Modifier.weight(1f))

                // 右侧：模型选择（DSH select trigger）+ 发送按钮
                if (!running) {
                    val modelInteraction = remember { MutableInteractionSource() }
                    val modelPressed by modelInteraction.collectIsPressedAsState()
                    Row(
                        modifier = Modifier
                            .height(28.dp)
                            .clip(RoundedCornerShape(24.dp))
                            .background(if (modelPressed) Dsh.hover else Color.Transparent)
                            .clickable(interactionSource = modelInteraction, indication = null, onClick = onOpenModelPicker)
                            .minimumInteractiveComponentSize()
                            .padding(start = 8.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            currentModel ?: "选择模型",
                            color = Dsh.labelSecondary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight(500),
                            lineHeight = 20.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 140.dp)
                        )
                        Icon(
                            ChevronDownOutline14,
                            contentDescription = null,
                            tint = Dsh.labelCaption,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                }

                // 上下文计量（DSH ContextMeter：环形按钮 + 用量弹层；contextPressure 投影可用时显示）
                if (!running && sessionStats != null && sessionStats.contextWindow > 0) {
                    ContextMeterButton(stats = sessionStats)
                    Spacer(Modifier.width(6.dp))
                }

                // 右侧：发送按钮（34x34 圆形品牌蓝，上浮 2px）
                val sendInteraction = remember { MutableInteractionSource() }
                val sendPressed by sendInteraction.collectIsPressedAsState()
                val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
                // 背景色按压反馈：120ms 过渡（Material Motion 按压区间 100-150ms）
                val sendBg by animateColorAsState(
                    targetValue = when {
                        !canSend && !isSending -> Dsh.brand500.copy(alpha = 0.4f)
                        sendPressed -> Dsh.brand400
                        else -> Dsh.brand500
                    },
                    animationSpec = tween(motionDuration(120)),
                    label = "sendBg"
                )
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .offset(y = (-2).dp)
                        .clip(CircleShape)
                        .minimumInteractiveComponentSize()
                        .background(sendBg)
                        .clickable(
                            interactionSource = sendInteraction,
                            indication = null,
                            enabled = canSend || isSending,
                            onClick = {
                                haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                                onSend()
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (isSending) {
                        // 简单旋转圈
                        val angle = rememberMotionSpin(750, label = "spin")
                        Box(
                            modifier = Modifier
                                .size(14.dp)
                                .rotate(angle ?: 0f)
                                .border(1.5.dp, Color.White, CircleShape)
                        )
                    } else {
                        Icon(
                            SendOutline16,
                            contentDescription = "发送消息",
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RoundIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color,
    contentDescription: String? = null,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(if (pressed) Dsh.hover else Dsh.bgSelector)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .minimumInteractiveComponentSize(),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = contentDescription, tint = tint, modifier = Modifier.size(16.dp))
    }
}

// ---------- Tool Chips 聚合：相邻 tool_call/tool_result 合并为聚合组 ----------
// MessageGroup / groupMessages 抽到 util/MessageGrouping.kt（纯函数，JVM 单测覆盖），
// 此处仅保留渲染层 ToolGroupHeader。

/** 聚合 header：N 工具调用 · +X.Ys · 展开/收起（默认收起）。 */
@Composable
private fun ToolGroupHeader(
    group: MessageGroup.ToolGroup,
    sweepingId: String?,
) {
    var expanded by remember(group.groupKey) { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    // 总耗时 = 最后一条 time - 第一条 time
    val first = group.items.first()
    val last = group.items.last()
    val totalDuration = if (first.time > 0 && last.time >= first.time) last.time - first.time else null
    // 聚合组 running 视觉：任一条 tool_call 的 id 命中 sweepingId
    val groupRunning = sweepingId != null && group.items.any { it.id == sweepingId }
    Box {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Dsh.bgCode)
                .border(1.dp, Dsh.borderL1, RoundedCornerShape(12.dp))
                .clickable(interactionSource = interaction, indication = null) { expanded = !expanded }
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(4.dp)
                        .clip(CircleShape)
                        .background(if (pressed || groupRunning) Dsh.brand400 else Dsh.labelTertiary)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "${group.items.size} 个工具调用",
                    color = Dsh.labelSecondary,
                    fontSize = 14.sp,
                    lineHeight = 24.sp,
                    fontWeight = FontWeight(400),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (groupRunning) {
                    Text("执行中…", color = Dsh.labelTertiary, fontSize = 12.sp, lineHeight = 18.sp)
                    Spacer(Modifier.width(6.dp))
                    RunningDots(tint = Dsh.brand400)
                    Spacer(Modifier.width(8.dp))
                } else if (totalDuration != null) {
                    Text(
                        "+${formatTraceDuration(totalDuration)}",
                        color = Dsh.labelCaption,
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Icon(
                    if (expanded) ChevronUpOutline14 else ChevronDownOutline14,
                    contentDescription = null,
                    tint = Dsh.labelTertiary,
                    modifier = Modifier.size(18.dp)
                )
            }
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(animationSpec = tween(motionDuration(200), easing = FastOutSlowInEasing)) + fadeIn(animationSpec = tween(motionDuration(150))),
                exit = shrinkVertically(animationSpec = tween(motionDuration(180), easing = FastOutSlowInEasing)) + fadeOut(animationSpec = tween(motionDuration(150)))
            ) {
                Column(modifier = Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    group.items.forEach { item ->
                        MessageItem(
                            msg = item,
                            running = sweepingId != null && item.id == sweepingId,
                            onCopy = {},
                            onQuote = {},
                            onFork = {}
                        )
                    }
                }
            }
        }
    }
}

// ---------- 消息渲染 ----------

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun MessageItem(
    msg: MobileMessage,
    running: Boolean = false,
    onAnswerApproval: ((String, String) -> Unit)? = null,
    onCopy: () -> Unit = {},
    onQuote: () -> Unit = {},
    onFork: () -> Unit = {},
) {
    var menuOpen by remember { mutableStateOf(false) }
    // 文本消息支持长按操作菜单（工具卡/思考行保持自身交互）
    val longPressModifier = if (msg.role == "user" || msg.role == "assistant") {
        Modifier.combinedClickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = {},
            onLongClick = { menuOpen = true }
        )
    } else {
        Modifier
    }

    Box {
        when (msg.role) {
            "user" -> UserBubble(msg.text, longPressModifier)
            "reasoning" -> ReasoningRow(msg.text, running)
            "approval" -> ApprovalCard(
                msg = msg,
                onAnswer = { approvalId, outcome ->
                    onAnswerApproval?.invoke(approvalId, outcome)
                }
            )
            "tool_call" -> CommandCard(msg.toolName ?: "工具调用", msg.toolArgs, running)
            "tool_result" -> CommandCard(
                title = "执行结果" + (msg.durationMs?.let { " · ${formatTraceDuration(it)}" } ?: ""),
                body = msg.text.take(4000),
                running = running,
                runningLabel = "执行中…"
            )
            "compaction" -> CompactionRow(msg.text, msg.running ?: false)
            "todo" -> TodoPanel(msg.todos)
            else -> {
                // 检测 system-reminder 消息，折叠为「上下文注入」行
                if (msg.text.contains("<system-reminder>") && msg.text.contains("</system-reminder>")) {
                    ContextInjectionRow(msg.text)
                } else {
                    AssistantMarkdown(msg.text, longPressModifier)
                }
            }
        }

        DshMenu(
            expanded = menuOpen,
            onDismiss = { menuOpen = false },
            items = listOf(
                DshMenuItem(CopyOutline16, "复制") {
                    menuOpen = false
                    onCopy()
                },
                DshMenuItem(Icons.Default.FormatQuote, "引用") {
                    menuOpen = false
                    onQuote()
                },
                DshMenuItem(BranchOutline16, "分叉会话") {
                    menuOpen = false
                    onFork()
                },
            )
        )
    }
}

// 上下文压缩行（DSH CompactionRow：折叠标题 + 可展开摘要）
@Composable
private fun CompactionRow(summary: String, running: Boolean) {
    var expanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { expanded = !expanded }
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
            if (running) "正在压缩…" else "上下文已压缩",
            color = Dsh.labelPrimary.copy(alpha = 0.85f),
            fontSize = 14.sp,
            lineHeight = 24.sp
        )
        if (!running) {
            Box(
                modifier = Modifier
                    .size(2.dp)
                    .clip(CircleShape)
                    .background(Dsh.labelCaption)
                    .padding(horizontal = 8.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                summary.lineSequence().firstOrNull().orEmpty(),
                color = Dsh.labelTertiary,
                fontSize = 14.sp,
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
            fontSize = 14.sp,
            lineHeight = 24.sp,
            modifier = Modifier.padding(start = 22.dp, top = 4.dp, bottom = 4.dp)
        )
    }
}

// 上下文注入行（DSH ContextInjectionRow：折叠 system-reminder 为紧凑行）
@Composable
private fun ContextInjectionRow(text: String) {
    var expanded by remember { mutableStateOf(false) }
    // 提取上下文来源名称
    val sources = remember(text) {
        val sourcePattern = Regex("""Instructions from:\s*(.+?)(?:\n|$)""")
        val filePattern = Regex("""(?:AGENTS\.md|CLAUDE\.md|\.zcode/[^,\s]+|@[\w-]+/[\w-]+)""")
        val fromMatches = sourcePattern.findAll(text).map { it.groupValues[1].trim() }.toList()
        val fileMatches = filePattern.findAll(text).map { it.value }.toList()
        val all = (fromMatches + fileMatches).distinct()
        if (all.isEmpty()) listOf("workspace instructions") else all
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Dsh.bgLayer3.copy(alpha = 0.5f))
            .clickable { expanded = !expanded }
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.Description,
                contentDescription = null,
                tint = Dsh.labelSecondary,
                modifier = Modifier.size(14.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                "上下文注入",
                color = Dsh.labelSecondary,
                fontSize = 12.sp,
                fontWeight = FontWeight(500),
                lineHeight = 18.sp
            )
            Spacer(Modifier.width(6.dp))
            Text(
                sources.joinToString(", "),
                color = Dsh.labelTertiary,
                fontSize = 12.sp,
                lineHeight = 18.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Icon(
                if (expanded) ChevronUpOutline14 else ChevronDownOutline14,
                contentDescription = null,
                tint = Dsh.labelCaption,
                modifier = Modifier.size(14.dp)
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(animationSpec = tween(motionDuration(200), easing = FastOutSlowInEasing)) + fadeIn(animationSpec = tween(motionDuration(150))),
            exit = shrinkVertically(animationSpec = tween(motionDuration(180), easing = FastOutSlowInEasing)) + fadeOut(animationSpec = tween(motionDuration(150)))
        ) {
            Column {
                Spacer(Modifier.height(6.dp))
                Text(
                    text.replace("<system-reminder>", "").replace("</system-reminder>", "").trim(),
                    color = Dsh.labelTertiary,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    maxLines = 20,
                    overflow = TextOverflow.Ellipsis
                )
            }
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
            .clip(RoundedCornerShape(12.dp))
            .background(Dsh.bgInput)
            .border(1.dp, Dsh.borderL1, RoundedCornerShape(12.dp))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { expanded = !expanded }
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
                "任务",
                color = Dsh.labelPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight(500),
                lineHeight = 24.sp
            )
            Spacer(Modifier.width(10.dp))
            Text(
                "$done/${todos.size} 已完成",
                color = Dsh.labelTertiary,
                fontSize = 13.sp,
                lineHeight = 20.sp,
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
                            fontSize = 13.sp,
                            lineHeight = 20.sp,
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
                    .border(1.5.dp, Dsh.labelCaption, CircleShape)
            )
        }
    }
}

// 加载更早（DSH chat.loadOlder：居中圆角按钮）
@Composable
private fun LoadOlderRow(loading: Boolean, onClick: () -> Unit) {
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Text(
            text = if (loading) "载入历史…" else "加载更早",
            color = Dsh.labelSecondary,
            fontSize = 12.sp,
            lineHeight = 18.sp,
            modifier = Modifier
                .clip(RoundedCornerShape(14.dp))
                .background(Dsh.bgInput)
                .clickable(enabled = !loading, interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 4.dp)
        )
    }
}

// 已停止标记（DSH message.stopped：会话非正常结束的角标；复用 DshTag 语义标签）
@Composable
private fun StoppedBadge(reason: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        DshTag(
            text = "已停止",
            color = Dsh.hover,
            contentColor = Dsh.labelTertiary,
            contentDescription = reason.ifBlank { "已停止" },
        )
    }
}

// 思考行（ReasoningRow：chevron + 标题 + 2px 圆点 + 摘要，展开显示全文）
@Composable
private fun ReasoningRow(text: String, running: Boolean = false) {
    var expanded by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .clickable(interactionSource = interaction, indication = null) { expanded = !expanded }
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                if (expanded) ChevronDownOutline14 else ChevronRightOutline14,
                contentDescription = null,
                tint = if (pressed) Dsh.labelPrimary else Dsh.labelSecondary,
                modifier = Modifier.size(16.dp)
            )
            Text(
                "思考",
                color = Dsh.labelPrimary,
                fontSize = 14.sp,
                lineHeight = 24.sp,
                fontWeight = FontWeight(400)
            )
            Box(
                modifier = Modifier
                    .size(2.dp)
                    .clip(CircleShape)
                    .background(Dsh.labelCaption)
                    .padding(horizontal = 8.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text.trim().lineSequence().firstOrNull().orEmpty(),
                color = Dsh.labelTertiary,
                fontSize = 14.sp,
                lineHeight = 24.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(animationSpec = tween(motionDuration(200), easing = FastOutSlowInEasing)) + fadeIn(animationSpec = tween(motionDuration(150))),
            exit = shrinkVertically(animationSpec = tween(motionDuration(180), easing = FastOutSlowInEasing)) + fadeOut(animationSpec = tween(motionDuration(150)))
        ) {
            Text(
                text,
                color = Dsh.labelTertiary,
                fontSize = 14.sp,
                lineHeight = 24.sp,
                modifier = Modifier.padding(start = 22.dp, top = 4.dp, bottom = 4.dp)
            )
        }
    }
    if (running) {
        RunningSweep()
    }
    }
}

/** 相对时间（DSH timeLabel：刚刚 / X 分钟前 / X 小时前 / X 天前） */
private fun relativeTime(timestamp: Long): String {
    if (timestamp <= 0) return ""
    val diff = System.currentTimeMillis() - timestamp
    return when {
        diff < 60_000 -> "刚刚"
        diff < 3_600_000 -> "${diff / 60_000} 分钟前"
        diff < 86_400_000 -> "${diff / 3_600_000} 小时前"
        else -> "${diff / 86_400_000} 天前"
    }
}

/** 用户工作区过滤：排除隐藏目录与系统/依赖目录（DSH 只显示用户项目工作区）。 */
private fun isUserWorkspace(cwd: String?): Boolean {
    if (cwd.isNullOrBlank()) return true
    val parts = cwd.split('/').filter { it.isNotBlank() }
    if (parts.isEmpty()) return false
    return parts.none { part ->
        part.startsWith(".") ||
            part == "node_modules" ||
            part == ".npm" ||
            part == ".bin" ||
            part == "Library" ||
            part == "Applications" ||
            part == "System" ||
            part == "tmp" ||
            part == "private"
    }
}

// 用户消息：右对齐气泡（max-width min(525px,82%), radius 22, bg #2C2C2E）
@Composable
private fun UserBubble(text: String, longPress: Modifier = Modifier) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End
    ) {
        Box(
            modifier = longPress
                .fillMaxWidth(0.82f)
                .widthIn(max = 525.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(Dsh.bgInput)
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            Text(
                text,
                color = Dsh.labelPrimary,
                fontSize = 16.sp,
                lineHeight = 24.sp
            )
        }
    }
}

// 助手消息：全宽 markdown（16px/28px）
@Composable
private fun AssistantMarkdown(text: String, longPress: Modifier = Modifier) {
    Column(
        modifier = longPress.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        MarkdownContent(decodeHtmlEntities(text))
    }
}

/** 解码服务端文本中的 HTML 实体（Web 端渲染会转义，原生端需手动解码）。 */
private fun decodeHtmlEntities(text: String): String {
    if (!text.contains('&')) return text
    val sb = StringBuilder(text.length)
    var i = 0
    while (i < text.length) {
        val amp = text.indexOf('&', i)
        if (amp == -1 || amp > text.length - 2) {
            sb.append(text.substring(i))
            break
        }
        sb.append(text, i, amp)
        val semi = text.indexOf(';', amp)
        if (semi == -1 || semi - amp > 12) {
            sb.append('&')
            i = amp + 1
            continue
        }
        val entity = text.substring(amp + 1, semi)
        val decoded = when {
            entity == "amp" -> "&"
            entity == "lt" -> "<"
            entity == "gt" -> ">"
            entity == "quot" -> "\""
            entity == "apos" -> "'"
            entity.startsWith("#x") || entity.startsWith("#X") ->
                entity.substring(2).toIntOrNull(16)?.let { String(Character.toChars(it)) }
            entity.startsWith("#") ->
                entity.substring(1).toIntOrNull(10)?.let { String(Character.toChars(it)) }
            else -> null
        }
        if (decoded != null) {
            sb.append(decoded)
            i = semi + 1
        } else {
            sb.append('&')
            i = amp + 1
        }
    }
    return sb.toString()
}

// ---------- Markdown 完整渲染（表格/链接/图片/引用/代码块/行内样式，对齐 DSH AssistantMarkdown） ----------

private enum class MarkdownBlockType { PARAGRAPH, HEADING, LIST, CODE, QUOTE, TABLE, HR, IMAGE, MATH, EMPTY }

private data class MarkdownBlock(
    val type: MarkdownBlockType,
    val content: String,
    val lang: String? = null,
    val level: Int = 0,
    val rows: List<List<String>> = emptyList(),
)

@Composable
private fun MarkdownContent(text: String) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val blocks = splitMarkdownBlocks(text)
    blocks.forEach { block ->
        when (block.type) {
            MarkdownBlockType.CODE -> MarkdownCodeBlock(block.lang, block.content)
            MarkdownBlockType.HEADING -> {
                Text(
                    block.content,
                    color = Dsh.labelPrimary,
                    fontSize = when (block.level) {
                        1 -> 22.sp
                        2 -> 18.sp
                        else -> 16.sp
                    },
                    fontWeight = FontWeight(500),
                    lineHeight = 28.sp
                )
            }
            MarkdownBlockType.LIST -> {
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text("•  ", color = Dsh.labelTertiary, fontSize = 16.sp, lineHeight = 28.sp)
                    InlineMarkdownText(block.content)
                }
            }
            MarkdownBlockType.QUOTE -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Dsh.bgLayer1)
                        .border(2.dp, Dsh.borderL2)
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    InlineMarkdownText(block.content, color = Dsh.labelSecondary)
                }
            }
            MarkdownBlockType.TABLE -> {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Dsh.bgLayer1)
                        .border(1.dp, Dsh.borderL1, RoundedCornerShape(8.dp))
                ) {
                    block.rows.forEachIndexed { rowIdx, cells ->
                        Row(modifier = Modifier.fillMaxWidth()) {
                            cells.forEachIndexed { colIdx, cell ->
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .background(if (rowIdx == 0) Dsh.bgLayer3 else Color.Transparent)
                                        .border(0.5.dp, Dsh.borderL1)
                                        .padding(horizontal = 10.dp, vertical = 6.dp)
                                ) {
                                    Text(
                                        cell,
                                        color = Dsh.labelPrimary,
                                        fontSize = 13.sp,
                                        lineHeight = 20.sp,
                                        fontWeight = if (rowIdx == 0) FontWeight(500) else FontWeight(400)
                                    )
                                }
                            }
                        }
                        if (rowIdx < block.rows.size - 1) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(1.dp)
                                    .background(Dsh.borderL1)
                            )
                        }
                    }
                }
            }
            MarkdownBlockType.HR -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(Dsh.borderL2)
                )
            }
            MarkdownBlockType.IMAGE -> {
                // 网络图片（DSH markdown 图片）
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Dsh.bgLayer1)
                        .clickable {
                            // 点击用系统浏览器打开原图
                            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(block.content))
                            context.startActivity(intent)
                        }
                ) {
                    coil.compose.AsyncImage(
                        model = block.content,
                        contentDescription = null,
                        contentScale = androidx.compose.ui.layout.ContentScale.FillWidth,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            MarkdownBlockType.MATH -> LatexDisplayBlock(block.content)
            MarkdownBlockType.EMPTY -> {}
            else -> InlineMarkdownText(block.content)
        }
    }
}

// 代码块（DSH：语言标签行 + 复制按钮 + 等宽内容，radius 12）
@Composable
private fun MarkdownCodeBlock(lang: String?, content: String) {
    val context = androidx.compose.ui.platform.LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Dsh.bgCode)
            .border(1.dp, Dsh.borderL1, RoundedCornerShape(12.dp))
    ) {
        // 语言标签 + 复制（DSH code-block-banner）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Dsh.bgCodeBanner)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                lang ?: "code",
                color = Dsh.labelTertiary,
                fontSize = 11.sp,
                lineHeight = 16.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f)
            )
            val copyInteraction = remember { MutableInteractionSource() }
            val copyPressed by copyInteraction.collectIsPressedAsState()
            Row(
                modifier = Modifier
                    .height(22.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (copyPressed) Dsh.hover else Color.Transparent)
                    .clickable(interactionSource = copyInteraction, indication = null) {
                        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("code", content.trimEnd()))
                        Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
                    }
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    CopyOutline16,
                    contentDescription = "复制",
                    tint = Dsh.labelTertiary,
                    modifier = Modifier.size(12.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text("复制", color = Dsh.labelTertiary, fontSize = 11.sp, lineHeight = 16.sp)
            }
        }
        Text(
            content.trimEnd(),
            color = Dsh.labelPrimary,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            lineHeight = 20.sp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp)
        )
    }
}

private fun splitMarkdownBlocks(text: String): List<MarkdownBlock> {
    val blocks = mutableListOf<MarkdownBlock>()
    val lines = text.split("\n")
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        when {
            line.trimStart().startsWith("```") -> {
                val lang = line.trim().removePrefix("```").trim().ifBlank { null }
                val buf = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                    buf.appendLine(lines[i])
                    i++
                }
                i++
                blocks.add(MarkdownBlock(MarkdownBlockType.CODE, buf.toString(), lang))
            }
            line.trimStart().startsWith("> ") || line.trimStart() == ">" -> {
                val buf = StringBuilder(line.trimStart().removePrefix(">").trim())
                i++
                while (i < lines.size && (lines[i].trimStart().startsWith("> ") || lines[i].trimStart() == ">")) {
                    buf.append("\n").append(lines[i].trimStart().removePrefix(">").trim())
                    i++
                }
                blocks.add(MarkdownBlock(MarkdownBlockType.QUOTE, buf.toString()))
            }
            // 块级公式：$$...$$（同行闭合或跨行，DSH micromark math 扩展）
            line.trimStart().startsWith("$$") -> {
                val buf = StringBuilder()
                var i0 = i
                val open = line.trim()
                if (open.length > 2 && open.endsWith("$$")) {
                    // 同行闭合：$$x$$
                    buf.append(open.removePrefix("$$").removeSuffix("$$").trim())
                    i++
                } else {
                    buf.append(line.trimStart().removePrefix("$$"))
                    i++
                    var closed = false
                    while (i < lines.size) {
                        val l = lines[i]
                        val t = l.trim()
                        if (t == "$$") {
                            closed = true
                            i++
                            break
                        }
                        if (t.length > 2 && t.endsWith("$$")) {
                            buf.append("\n").append(t.removeSuffix("$$").trim())
                            closed = true
                            i++
                            break
                        }
                        buf.append("\n").append(l)
                        i++
                    }
                    if (!closed && i == i0 + 1) {
                        // 孤立 $$ 标记（非公式）：按原文段落回退
                        blocks.add(MarkdownBlock(MarkdownBlockType.PARAGRAPH, line))
                        continue
                    }
                }
                blocks.add(MarkdownBlock(MarkdownBlockType.MATH, buf.toString().trim()))
            }
            // 图片行：![alt](url)
            line.trimStart().startsWith("![") && line.contains("](") -> {
                val trimmed = line.trim()
                val close = trimmed.indexOf(']')
                val urlStart = trimmed.indexOf('(', close)
                val urlEnd = trimmed.indexOf(')', urlStart)
                if (urlStart > 0 && urlEnd > urlStart) {
                    val url = trimmed.substring(urlStart + 1, urlEnd)
                    blocks.add(MarkdownBlock(MarkdownBlockType.IMAGE, url))
                } else {
                    blocks.add(MarkdownBlock(MarkdownBlockType.PARAGRAPH, line))
                }
                i++
            }
            line.trimStart() == "---" || line.trimStart() == "***" || line.trimStart() == "___" -> {
                blocks.add(MarkdownBlock(MarkdownBlockType.HR, ""))
                i++
            }
            line.isBlank() -> {
                i++
            }
            // 表格：| a | b | 行 + 分隔行
            line.trimStart().startsWith("|") && line.contains("|") -> {
                val rows = mutableListOf<List<String>>()
                while (i < lines.size && lines[i].trimStart().startsWith("|") && lines[i].contains("|")) {
                    val cells = lines[i].trim().trim('|').split("|").map { it.trim() }
                    // 跳过分隔行（|---|）
                    if (!cells.all { it.matches(Regex("^:?-{2,}:?$")) }) {
                        rows.add(cells)
                    }
                    i++
                }
                if (rows.size >= 1) {
                    blocks.add(MarkdownBlock(MarkdownBlockType.TABLE, "", rows = rows))
                }
            }
            line.trimStart().startsWith("###") -> {
                blocks.add(MarkdownBlock(MarkdownBlockType.HEADING, line.trimStart().removePrefix("###").trim(), level = 3))
                i++
            }
            line.trimStart().startsWith("##") -> {
                blocks.add(MarkdownBlock(MarkdownBlockType.HEADING, line.trimStart().removePrefix("##").trim(), level = 2))
                i++
            }
            line.trimStart().startsWith("#") -> {
                blocks.add(MarkdownBlock(MarkdownBlockType.HEADING, line.trimStart().removePrefix("#").trim(), level = 1))
                i++
            }
            line.trimStart().startsWith("- ") || line.trimStart().startsWith("* ") -> {
                blocks.add(MarkdownBlock(MarkdownBlockType.LIST, line.trimStart().removePrefix("- ").removePrefix("* ")))
                i++
            }
            else -> {
                blocks.add(MarkdownBlock(MarkdownBlockType.PARAGRAPH, line))
                i++
            }
        }
    }
    return blocks
}

/** 行内渲染：`code` / **bold** / *italic* / ~~删除线~~ / [链接](url) / $公式$（KaTeX 级排版） */
@Composable
private fun InlineMarkdownText(text: String, color: Color = Dsh.labelPrimary) {
    val hoverColor = Dsh.hover
    val labelPrimaryColor = Dsh.labelPrimary
    val brand400Color = Dsh.brand400
    val density = LocalDensity.current
    val built = remember(text, hoverColor, labelPrimaryColor, brand400Color, color, density) {
        buildInlineMarkdown(text, hoverColor, labelPrimaryColor, brand400Color, color.toArgb(), density)
    }
    Text(
        built.first,
        inlineContent = built.second,
        color = color,
        fontSize = 16.sp,
        lineHeight = 28.sp
    )
}

private fun buildInlineMarkdown(
    text: String,
    hoverColor: Color,
    labelPrimaryColor: Color,
    brand400Color: Color,
    mathColorArgb: Int,
    density: Density,
): Pair<AnnotatedString, Map<String, InlineTextContent>> {
    val inlineContent = mutableMapOf<String, InlineTextContent>()
    val mathTextSizePx = with(density) { 16.sp.toPx() } // 与正文 16sp 一致
    val annotated = buildAnnotatedString {
        var i = 0
        var mathIndex = 0
        while (i < text.length) {
            // 找下一个标记位置
            val tokens = listOf(
                text.indexOf('`', i) to '`',
                text.indexOf("**", i) to '*',
                text.indexOf("~~", i) to '~',
                text.indexOf("$", i) to '$',
                text.indexOf("[", i) to '[',
            ).filter { it.first != -1 }
            if (tokens.isEmpty()) {
                append(text.substring(i))
                break
            }
            val (nextIdx, kind) = tokens.minBy { it.first }
            append(text.substring(i, nextIdx))
            when (kind) {
                '`' -> {
                    val end = text.indexOf('`', nextIdx + 1)
                    if (end == -1) { append(text.substring(nextIdx)); break }
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = hoverColor, color = labelPrimaryColor)) {
                        append(text.substring(nextIdx + 1, end))
                    }
                    i = end + 1
                }
                '$' -> {
                    // 行内公式 $...$：内容首尾不能是空白（KaTeX 约定），不跨行、不嵌套
                    val end = text.indexOf('$', nextIdx + 1)
                    if (end == -1) { append(text.substring(nextIdx)); break }
                    val inner = text.substring(nextIdx + 1, end)
                    val valid = inner.isNotBlank() &&
                        !inner.startsWith(" ") && !inner.endsWith(" ") &&
                        !inner.contains('\n') && !inner.contains('$')
                    if (!valid) {
                        append("$")
                        i = nextIdx + 1
                        continue
                    }
                    val built = buildInlineMath(inner, mathTextSizePx, mathColorArgb, density)
                    if (built == null) {
                        // 非法 LaTeX：原文回退
                        append("$inner$")
                        i = end + 1
                        continue
                    }
                    val (drawable, size) = built
                    val key = "math-${mathIndex++}"
                    val start = length
                    append(" ")
                    addStringAnnotation("inlineContent", key, start, start + 1)
                    inlineContent[key] = InlineTextContent(
                        placeholder = Placeholder(
                            width = with(density) { size.width.toSp() },
                            height = with(density) { size.height.toSp() },
                            placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter,
                        )
                    ) {
                        LatexMathCanvas(drawable, Modifier.fillMaxSize())
                    }
                    i = end + 1
                }
                '*' -> {
                    // **bold** 或 *italic*
                    val after = if (nextIdx + 2 < text.length) text[nextIdx + 2] else '\u0000'
                    if (after == '*') {
                        val end = text.indexOf("**", nextIdx + 2)
                        if (end == -1) { append(text.substring(nextIdx)); break }
                        withStyle(SpanStyle(fontWeight = FontWeight(600))) {
                            append(text.substring(nextIdx + 2, end))
                        }
                        i = end + 2
                    } else {
                        val end = text.indexOf('*', nextIdx + 1)
                        if (end == -1) { append(text.substring(nextIdx)); break }
                        withStyle(SpanStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)) {
                            append(text.substring(nextIdx + 1, end))
                        }
                        i = end + 1
                    }
                }
                '~' -> {
                    val end = text.indexOf("~~", nextIdx + 2)
                    if (end == -1) { append(text.substring(nextIdx)); break }
                    withStyle(SpanStyle(textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough)) {
                        append(text.substring(nextIdx + 2, end))
                    }
                    i = end + 2
                }
                '[' -> {
                    // [text](url)
                    val close = text.indexOf(']', nextIdx + 1)
                    if (close == -1 || close + 1 >= text.length || text[close + 1] != '(') {
                        append("[")
                        i = nextIdx + 1
                        continue
                    }
                    val urlEnd = text.indexOf(')', close + 2)
                    if (urlEnd == -1) { append(text.substring(nextIdx)); break }
                    val label = text.substring(nextIdx + 1, close)
                    val url = text.substring(close + 2, urlEnd)
                    withStyle(SpanStyle(color = brand400Color, textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline)) {
                        append(label)
                    }
                    i = urlEnd + 1
                }
            }
        }
    }
    return annotated to inlineContent
}
// 执行中扫光（DSH command/reasoning-row-sweep：300px 渐变条 2.6s 左→右无限）
@Composable
private fun RunningSweep() {
    // reduce-motion：不播放扫光
    if (isReduceMotionEnabled()) return
    val transition = rememberInfiniteTransition(label = "sweep")
    val progress = transition.animateFloat(
        initialValue = -1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "sweepProgress"
    )
    val sweepBrush = Brush.horizontalGradient(
        colors = listOf(
            Color.Transparent,
            Dsh.bgBase.copy(alpha = 0.6f),
            Color.Transparent
        )
    )
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.CenterStart
    ) {
        Box(
            modifier = Modifier
                .width(300.dp)
                .fillMaxHeight()
                .graphicsLayer {
                    // 渲染阶段读取动画值：只更新变换，不触发布局/重组
                    translationX = progress.value * 1200.dp.toPx()
                }
                .background(sweepBrush)
        )
    }
}

// 命令卡片（tool call / result：radius 12, bg code-block, max-height 260）
@Composable
private fun CommandCard(title: String, body: String?, running: Boolean = false, runningLabel: String = "执行中…") {
    var expanded by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Dsh.bgCode)
            .border(1.dp, Dsh.borderL1, RoundedCornerShape(12.dp))
            .clickable(interactionSource = interaction, indication = null) { expanded = !expanded }
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 2px 圆点分隔符风格
            Box(
                modifier = Modifier
                    .size(4.dp)
                    .clip(CircleShape)
                    .background(if (pressed) Dsh.brand400 else Dsh.labelTertiary)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                title,
                color = Dsh.labelSecondary,
                fontSize = 14.sp,
                lineHeight = 24.sp,
                fontWeight = FontWeight(400),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (running) {
                Text(
                    runningLabel,
                    color = Dsh.labelTertiary,
                    fontSize = 12.sp,
                    lineHeight = 18.sp
                )
                Spacer(Modifier.width(6.dp))
                RunningDots(tint = Dsh.brand400)
                Spacer(Modifier.width(8.dp))
            }
            Icon(
                if (expanded) ChevronUpOutline14 else ChevronDownOutline14,
                contentDescription = null,
                tint = Dsh.labelTertiary,
                modifier = Modifier.size(18.dp)
            )
        }
        // 展开内容（收起时隐藏；visible 条件内置，enter/exit 动画均有效）
        AnimatedVisibility(
            visible = expanded && !body.isNullOrBlank(),
            enter = expandVertically(animationSpec = tween(motionDuration(200), easing = FastOutSlowInEasing)) + fadeIn(animationSpec = tween(motionDuration(150))),
            exit = shrinkVertically(animationSpec = tween(motionDuration(180), easing = FastOutSlowInEasing)) + fadeOut(animationSpec = tween(motionDuration(150)))
        ) {
            Column {
                Spacer(Modifier.height(8.dp))
                Text(
                    body.orEmpty(),
                    color = Dsh.labelPrimary,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                    maxLines = 16,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
    // 旧版 RunningSweep() 已被 Header 内的 RunningDots（脉动 3 点）替代
    // reduce-motion 时 RunningDots 静态全亮，无需兜底
    }
}

// ---------- 空主机引导 ----------

@Composable
fun EmptyHostScreen(onScan: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Dsh.bgBase)
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "DeepSeek Harness",
                color = Dsh.labelPrimary,
                fontSize = 26.sp,
                fontWeight = FontWeight(500),
                lineHeight = 32.sp
            )
            Spacer(Modifier.height(12.dp))
            Text("未连接工作台，请先添加设备", color = Dsh.labelTertiary, fontSize = 12.sp, lineHeight = 18.sp)
            Spacer(Modifier.height(28.dp))
            Box(
                modifier = Modifier
                    .height(44.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(Dsh.brand500)
                    .clickable(onClick = onScan)
                    .padding(horizontal = 24.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("去设备列表", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight(500))
            }
        }
    }
}
