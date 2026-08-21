package dev.dsh.mobile.native
import dev.dsh.mobile.core.Dsh
import dev.dsh.mobile.core.Host
import dev.dsh.mobile.core.L
import dev.dsh.mobile.core.ThemeManager
import dev.dsh.mobile.native.MobileSession
import dev.dsh.mobile.native.MobileMessage
import dev.dsh.mobile.native.AppSettings
import dev.dsh.mobile.native.MobileApiClient
import dev.dsh.mobile.core.AppSettingsStore
import dev.dsh.mobile.core.DshNotifier
import dev.dsh.mobile.core.DshTheme
import dev.dsh.mobile.core.HostStore
import dev.dsh.mobile.core.MarkdownMedia
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
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.focus.onFocusChanged
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
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
import androidx.compose.ui.text.input.ImeAction
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
import dev.dsh.mobile.native.util.contextInjectionLabels
import dev.dsh.mobile.native.util.groupMessages
import dev.dsh.mobile.native.util.isContextInjectionText
import dev.dsh.mobile.native.util.isUserWorkspace
import dev.dsh.mobile.native.util.matchesTool
import dev.dsh.mobile.native.util.visibleUserWorkspaces

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
    private var voiceIdle: (() -> Unit)? = null
    private val incomingIntent = mutableStateOf<Intent?>(null)
    private val requestAudioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isGranted) {
            Toast.makeText(this, L.voicePermissionRequired, Toast.LENGTH_SHORT).show()
            voiceIdle?.invoke()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        incomingIntent.value = intent
        initSpeechRecognizer()

        setContent {
            DshTheme {
                val liveIntent = incomingIntent.value ?: intent
                val hosts = remember(liveIntent) { HostStore.load(this) }
                val targetUrl = liveIntent.getStringExtra("hostBaseUrl")
                val targetSession = liveIntent.getStringExtra("sessionId")
                var currentHost by remember(targetUrl) { mutableStateOf(
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
                            startActivity(
                                Intent(this, SettingsActivity::class.java)
                                    .putExtra("hostBaseUrl", currentHost!!.baseUrl)
                            )
                        },
                        onStartVoiceInput = { onResult, onIdle ->
                            startVoiceListening(onResult, onIdle)
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        incomingIntent.value = intent
    }

    private fun startVoiceListening(onResult: (String) -> Unit, onIdle: () -> Unit) {
        voiceIdle = onIdle
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            onIdle()
            requestAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.CHINESE.toString())
            putExtra(RecognizerIntent.EXTRA_PROMPT, L.voiceListeningPrompt)
        }

        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { onIdle() }
            override fun onError(error: Int) {
                onIdle()
                Toast.makeText(this@WorkspaceActivity, L.voiceRecognitionRetry, Toast.LENGTH_SHORT).show()
            }
            override fun onResults(results: Bundle?) {
                onIdle()
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

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun WorkspaceScreen(
    host: Host,
    initialSessionId: String? = null,
    onSwitchHost: () -> Unit,
    onOpenSettings: () -> Unit,
    onStartVoiceInput: ((String) -> Unit, () -> Unit) -> Unit,
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
    var pendingSessionCwd by remember { mutableStateOf<String?>(null) }
    /** 用户点了「新建会话」、尚未发首条消息时为 true；此期间 refreshSessions 不得抢绑旧会话。 */
    var composeNewSession by remember { mutableStateOf(false) }
    /** 首条消息 createSession 后立即切 sid 时，保留已乐观插入的消息，避免 LaunchedEffect 清空。 */
    var preserveMessagesSessionId by remember { mutableStateOf<String?>(null) }
    var pendingAgentPreset by remember { mutableStateOf("") }
    var agentPresets by remember { mutableStateOf<List<MobileAgentPreset>>(emptyList()) }
    var showAgentPresetPicker by remember { mutableStateOf(false) }
    var showSubagentSheet by remember { mutableStateOf(false) }
    /** 新会话阶段的默认模型（create 后 selectModel）。 */
    var pendingModel by remember { mutableStateOf<Triple<String, String, String?>?>(null) }
    var expandedWorkspaces by remember { mutableStateOf(setOf<String>()) }
    var expandedGroups by remember { mutableStateOf(setOf<String>()) } // 组内"显示全部"展开态
    var deleteWorkspaceTarget by remember { mutableStateOf<String?>(null) } // 待删除的工作区路径
    var deleteSessionTarget by remember { mutableStateOf<MobileSession?>(null) } // 待删除的会话
    /** 服务端已注册工作区路径（与会话 cwd 合并后供侧栏 / 选择器共用）。 */
    var workspaceRegistry by remember { mutableStateOf<List<String>>(emptyList()) }
    // 本地已删除工作区（服务端删注册后会话 cwd 仍在；侧边栏不再单独建「未分组」文件夹）
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
        val saved = tabPrefs.getString("view_mode", "chat") ?: "chat"
        mutableStateOf(if (saved in setOf("chat", "trace", "history")) saved else "chat")
    }
    fun selectViewMode(mode: String) {
        val next = if (mode in setOf("chat", "trace", "history")) mode else "chat"
        viewMode = next
        tabPrefs.edit().putString("view_mode", next).apply()
    }
    // searchQuery：仅持久化搜索文本，不持久化结果（结果每次进入 history 视图重新拉取）
    var searchQuery by remember {
        mutableStateOf(tabPrefs.getString("history_query", "") ?: "")
    }
    var sidebarSearchOpen by remember {
        mutableStateOf(!(tabPrefs.getString("history_query", "") ?: "").isBlank())
    }
    var showSessionFilterSheet by remember { mutableStateOf(false) }
    var searchResults by remember { mutableStateOf<List<MobileSearchResult>>(emptyList()) }
    // 搜索状态机：用于 HistoryView 渲染 loading/empty/error/recovery
    var searchState by remember { mutableStateOf<SearchUiState>(SearchUiState.Idle) }
    // 异步搜索任务句柄（最近一次 in-flight 搜索）：用于取消与丢弃过期响应
    var searchJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var searchSeq by remember { mutableStateOf(0) } // 单调递增；过期响应按 seq 丢弃
    var sessionsJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
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
                    } else {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, L.imageTooLargeSkipped, Toast.LENGTH_SHORT).show()
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, L.readImageFailed.format(e.message ?: L.unknownError), Toast.LENGTH_SHORT).show()
                    }
                }
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

    LaunchedEffect(appSettings.agentPreset) {
        if (composeNewSession || currentSessionId == null) {
            pendingAgentPreset = appSettings.agentPreset
        }
    }

    LaunchedEffect(host) {
        scope.launch(Dispatchers.IO) {
            try {
                val presets = client.getAgentPresets()
                withContext(Dispatchers.Main) { agentPresets = presets }
            } catch (_: Exception) {}
        }
    }

    fun selectSession(sessionId: String) {
        composeNewSession = false
        currentSessionId = sessionId
    }

    fun startComposeSession(cwd: String? = null) {
        composeNewSession = true
        currentSessionId = null
        pendingSessionCwd = cwd
        pendingAgentPreset = appSettings.agentPreset
        pendingModel = null
        messages = emptyList()
        olderMessages = emptyList()
        sessionStats = null
        stoppedReason = null
        liveRunning = false
        seedMaxSeq = 0L
    }

    fun refreshSessions(selectLatest: Boolean = false) {
        sessionsJob?.cancel()
        sessionsJob = scope.launch(Dispatchers.IO) {
            try {
                val list = client.getSessions()
                withContext(Dispatchers.Main) {
                    sessions = list
                    if (composeNewSession && currentSessionId == null) {
                        return@withContext
                    }
                    if (currentSessionId == null || !list.any { it.sessionId == currentSessionId }) {
                        currentSessionId = if (selectLatest) {
                            list.maxByOrNull { it.updatedAt }?.sessionId ?: list.firstOrNull()?.sessionId
                        } else {
                            list.firstOrNull()?.sessionId
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, L.refreshSessionsFailed.format(e.message ?: L.unknownError), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun refreshWorkspaces() {
        scope.launch(Dispatchers.IO) {
            try {
                val catalog = client.getWorkspaces()
                withContext(Dispatchers.Main) {
                    workspaceRegistry = catalog.paths
                    if (catalog.archivedSessionIds.isNotEmpty()) {
                        archivedIds = archivedIds + catalog.archivedSessionIds
                        context.getSharedPreferences("dsh_workspace", android.content.Context.MODE_PRIVATE)
                            .edit().putStringSet("archived_sessions", archivedIds).apply()
                    }
                }
            } catch (_: Exception) {}
        }
    }

    /** 归档到服务端并本机隐藏（对标 web archive；单向，不再本地 toggle）。 */
    fun archiveSessionNow(session: MobileSession) {
        if (session.sessionId !in archivedIds) {
            archivedIds = archivedIds + session.sessionId
            context.getSharedPreferences("dsh_workspace", android.content.Context.MODE_PRIVATE)
                .edit().putStringSet("archived_sessions", archivedIds).apply()
        }
        if (currentSessionId == session.sessionId) {
            startComposeSession(pendingSessionCwd)
        }
        refreshSessions()
        scope.launch(Dispatchers.IO) {
            try {
                client.archiveSession(session.sessionId)
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, L.archiveFailed.format(e.message ?: L.fallbackRetryLater), Toast.LENGTH_SHORT).show()
                }
            }
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
                    val merged = mergeHistoryWithLive(msgs, messages)
                    val sameContent = messages.contentSignature() == merged.contentSignature()
                    if (!sameContent) messages = merged
                    sessionStats = stats
                    hasMoreMessages = result.hasMore
                    if (olderMessages.isEmpty()) {
                        nextBeforeSeq = result.nextBeforeSeq
                    }
                    // 发送中/执行中不覆盖：旧 turn 的 stoppedReason 会误显示成「已停止」
                    if (!liveRunning && !isSending) {
                        stoppedReason = result.stoppedReason
                    }
                    // 登记本页最新事件 seq，作为 SSE 增量去重基线（缺 maxSeq 也必须 seed）
                    val seed = historySeedSeq(result.maxSeq, msgs.map { it.seq })
                    seedMaxSeq = maxOf(seedMaxSeq, seed)
                    streamClient?.noteSeedMaxSeq(seed)
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
                val (results, degraded) = client.searchSessions(query)
                if (seq != searchSeq) return@launch // 过期响应丢弃
                withContext(Dispatchers.Main) {
                    searchResults = results
                    searchState = if (results.isEmpty()) SearchUiState.Empty
                    else SearchUiState.Results(query, results.size)
                    if (degraded) Toast.makeText(context, L.fullTextSearchUnavailable, Toast.LENGTH_SHORT).show()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (seq != searchSeq) return@launch
                withContext(Dispatchers.Main) {
                    searchState = SearchUiState.Error(e.message ?: L.searchFailed)
                }
            }
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
                scope.launch { drawerState.close() }
            }
            LocalKind.NEW_SESSION -> {
                startComposeSession(pendingSessionCwd)
                selectViewMode("chat")
                scope.launch { drawerState.close() }
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
                androidx.lifecycle.Lifecycle.Event.ON_START -> {
                    isForeground = true
                    streamClient?.start()
                }
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> {
                    isForeground = false
                    streamClient?.stop()
                }
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
        startComposeSession(cwd)
        scope.launch { drawerState.close() }
    }

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

    fun upsertStreamMessage(m: MobileMessage) {
        val i = messages.indexOfFirst { it.id == m.id }
        if (i >= 0) {
            updateStreamMessage(m.id) { cur -> m.copy(entrance = cur.entrance) }
            followIfNearBottom()
            return
        }
        messages = messages.filterNot { pending ->
            pending.id == "local-pending" && pending.role == "user" && pending.text == m.text
        }
        appendStreamMessage(m)
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
                // 定稿在途消息的 running，避免历史合并后长期卡住导致复制按钮不出现
                messages = messages.map { if (it.running == true) it.copy(running = false) else it }
                val kind = data.optJSONObject("reason")?.optString("kind")
                stoppedReason = if (!kind.isNullOrBlank() && kind != "completed") kind else null
                scope.launch(Dispatchers.IO) {
                    refreshSessions()
                    withContext(Dispatchers.Main) { refreshMessages(autoScroll = false) }
                }
            }
            "user/message" -> {
                val text = (data.optJSONArray("content") ?: org.json.JSONArray()).let { arr ->
                    (0 until arr.length()).joinToString("") { i ->
                        arr.optJSONObject(i)?.optString("text").orEmpty()
                    }
                }
                val role = if (isContextInjectionText(text)) "context_injection" else "user"
                upsertStreamMessage(
                    MobileMessage(
                        id = "msg-${item.seq}",
                        role = role,
                        text = text,
                        time = item.time,
                        type = "text",
                        seq = item.seq.toLong(),
                    ),
                )
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
                    text = data.optString("reason").ifBlank { L.approvalRequest.format(data.optString("toolName", L.toolFallbackName)) },
                    toolName = data.optString("toolName", "tool"),
                    approvalId = data.optString("id", ""),
                    time = item.time,
                    type = "approval",
                ))
                // 后台时系统通知提醒（前台有审批卡）
                val sid = currentSessionId
                if (!isForeground && sid != null) {
                    DshNotifier.notifyApproval(context, host, sid, data.optString("toolName", L.toolFallbackName))
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
        // 冷启动：bootstrap 一次拉主机信息 + 会话，再补工作区归档同步
        scope.launch(Dispatchers.IO) {
            try {
                val boot = client.bootstrap()
                withContext(Dispatchers.Main) {
                    if (boot.sessions.isNotEmpty()) {
                        sessions = boot.sessions
                        if (currentSessionId == null && !composeNewSession) {
                            currentSessionId = boot.sessions.maxByOrNull { it.updatedAt }?.sessionId
                                ?: boot.sessions.firstOrNull()?.sessionId
                        }
                    }
                }
            } catch (_: Exception) {
                // bootstrap 失败时回退 refreshSessions
            }
            try {
                val catalog = client.getWorkspaces()
                withContext(Dispatchers.Main) {
                    workspaceRegistry = catalog.paths
                    if (catalog.archivedSessionIds.isNotEmpty()) {
                        archivedIds = archivedIds + catalog.archivedSessionIds
                        context.getSharedPreferences("dsh_workspace", android.content.Context.MODE_PRIVATE)
                            .edit().putStringSet("archived_sessions", archivedIds).apply()
                    }
                }
            } catch (_: Exception) {}
        }
        refreshSessions(selectLatest = true)
        refreshAppSettings()
    }

    LaunchedEffect(currentSessionId) {
        currentSessionId?.let { DshNotifier.cancelForSession(context, host, it) }
        streamClient?.stop()
        if (currentSessionId == null) {
            messages = emptyList()
            olderMessages = emptyList()
            hasMoreMessages = false
            nextBeforeSeq = null
            stoppedReason = null
            liveRunning = false
            seedMaxSeq = 0L
            sessionStats = null
            toolCallTimes.clear()
            streamToolCallIds.clear()
            showScrollToBottom = false
            return@LaunchedEffect
        }
        val preserveMessages = currentSessionId == preserveMessagesSessionId
        if (preserveMessages) preserveMessagesSessionId = null
        // WI-003：会话切换必须清空上一会话的消息/分页/临时加载状态，
        // 避免旧列表短暂残留或位置继承
        if (!preserveMessages) {
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
            tailRequestId++
        }
        refreshMessages(autoScroll = preserveMessages)
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
                val streamReady = streamClient?.isConnected == true && streamClient?.isSeeded == true
                if (!streamReady) {
                    refreshSessions()
                    refreshMessages(autoScroll = false)
                }
            }
        }
    }

    val currentSession = sessions.find { it.sessionId == currentSessionId }
    val running = liveRunning || currentSession?.running == true

    val activeHarnessPresetId = if (currentSessionId == null) {
        pendingAgentPreset
    } else {
        currentSession?.agentPreset
    }
    val harnessLabel = remember(agentPresets, activeHarnessPresetId, appSettings.agentPreset) {
        val id = activeHarnessPresetId?.takeIf { it.isNotBlank() } ?: appSettings.agentPreset
        agentPresets.find { it.id == id }?.name?.ifBlank { id }
            ?: id.ifBlank { L.defaultHarnessPreset }
    }
    val composeModelLabel = remember(pendingModel, modelCatalog) {
        pendingModel?.second ?: modelChipLabel(modelCatalog)
    }
    val inputModelLabel = if (currentSessionId == null) composeModelLabel else modelChipLabel(modelCatalog)
    val activeSubagentCount = remember(sessions, currentSessionId, currentSession) {
        val sid = currentSessionId ?: return@remember 0
        currentSession?.subagentCount?.takeIf { it > 0 }
            ?: sessions.count { it.origin == "subagent" && it.parentSessionId == sid }
    }

    // 会话结束通知（仅后台；正常完成 → 任务完成，非正常 → 已停止）
    var wasRunning by remember { mutableStateOf(false) }
    LaunchedEffect(running) {
        if (!running && wasRunning && !isForeground && currentSessionId != null) {
            val title = currentSession?.title ?: L.sessionFallbackTitle
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
        if (!running) {
            elapsedSec = 0L
            return@LaunchedEffect
        }
        while (true) {
            delay(1000)
            elapsedSec = (System.currentTimeMillis() - turnStartTime) / 1000
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
                    // 品牌行：logo + 名称
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f),
                        ) {
                            Image(
                                painter = painterResource(dev.dsh.mobile.R.drawable.ic_dsh_mark_orca),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(RoundedCornerShape(8.dp)),
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                "DSH Links",
                                color = Dsh.labelPrimary,
                                fontSize = 15.sp,
                                fontWeight = FontWeight(600),
                                letterSpacing = (-0.2).sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        IconButton(
                            onClick = onSwitchHost,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                Icons.Default.Devices,
                                contentDescription = L.switchDevice,
                                tint = Dsh.labelSecondary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    // 新会话（首条消息时再 createSession，对标 web 描边胶囊）
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                            .height(40.dp)
                            .clip(RoundedCornerShape(DshRadius.full))
                            .border(1.dp, Dsh.borderL2, RoundedCornerShape(DshRadius.full))
                            .clickable {
                                scope.launch {
                                    startComposeSession(null)
                                    drawerState.close()
                                }
                            }
                            .padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        Icon(
                            PlusOutline16,
                            contentDescription = null,
                            tint = Dsh.labelPrimary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(L.newSession, color = Dsh.labelPrimary, fontSize = 14.sp, fontWeight = FontWeight(500), lineHeight = 20.sp)
                    }

                    Spacer(Modifier.height(4.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            L.workspace,
                            color = Dsh.labelTertiary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight(500),
                            lineHeight = 18.sp,
                        )
                        Spacer(Modifier.weight(1f))
                        SidebarHeaderIcon(
                            icon = SearchOutline16,
                            contentDescription = L.searchSessions,
                            active = sidebarSearchOpen || searchQuery.isNotBlank(),
                            onClick = {
                                sidebarSearchOpen = !sidebarSearchOpen
                                if (!sidebarSearchOpen && searchQuery.isBlank()) {
                                    searchResults = emptyList()
                                    searchState = SearchUiState.Idle
                                }
                            },
                        )
                        Spacer(Modifier.width(2.dp))
                        SidebarHeaderIcon(
                            icon = ChecklistOutline14,
                            contentDescription = L.filterSessions,
                            active = sessionFilter != SessionFilter.ALL,
                            onClick = { showSessionFilterSheet = true },
                        )
                        Spacer(Modifier.width(2.dp))
                        SidebarHeaderIcon(
                            icon = PlusOutline16,
                            contentDescription = L.addWorkspace,
                            onClick = { showAddWorkspace = true },
                        )
                    }

                    AnimatedVisibility(
                        visible = sidebarSearchOpen,
                        enter = fadeIn(tween(150)) + expandVertically(tween(180, easing = FastOutSlowInEasing)),
                        exit = fadeOut(tween(100)) + shrinkVertically(tween(150, easing = FastOutSlowInEasing)),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp)
                                .padding(bottom = 6.dp)
                                .height(32.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            BasicTextField(
                                value = searchQuery,
                                onValueChange = { q ->
                                    searchQuery = q
                                    tabPrefs.edit().putString("history_query", q).apply()
                                    runSearchDebounced(q)
                                },
                                singleLine = true,
                                textStyle = TextStyle(color = Dsh.labelPrimary, fontSize = 13.sp),
                                cursorBrush = SolidColor(Dsh.brand400),
                                modifier = Modifier.weight(1f),
                                decorationBox = { inner ->
                                    Box(contentAlignment = Alignment.CenterStart) {
                                        if (searchQuery.isEmpty()) {
                                            Text(L.searchSessionsPlaceholder, color = Dsh.labelTertiary, fontSize = 13.sp)
                                        }
                                        inner()
                                    }
                                },
                            )
                            if (searchState is SearchUiState.Loading) {
                                val spin = rememberMotionSpin(750, label = "sidebarSearchSpin")
                                Box(
                                    modifier = Modifier
                                        .size(12.dp)
                                        .rotate(spin ?: 0f)
                                        .border(1.dp, Dsh.labelCaption, CircleShape),
                                )
                                Spacer(Modifier.width(6.dp))
                            }
                            if (searchQuery.isNotEmpty()) {
                                Icon(
                                    CloseOutline16,
                                    contentDescription = L.clearSearch,
                                    tint = Dsh.labelCaption,
                                    modifier = Modifier
                                        .size(14.dp)
                                        .clickable {
                                            searchQuery = ""
                                            tabPrefs.edit().putString("history_query", "").apply()
                                            searchResults = emptyList()
                                            searchState = SearchUiState.Idle
                                        },
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(2.dp))

                    val staleCutoffForFilter = System.currentTimeMillis() - 24 * 3600_000
                    val filterCandidates = remember(sessions, archivedIds, deletedIds) {
                        sessions.filter {
                            it.sessionId !in archivedIds && it.sessionId !in deletedIds &&
                                it.origin != "subagent" &&
                                !(it.blank && it.updatedAt < staleCutoffForFilter)
                        }
                    }
                    val searchNeedle = searchQuery.trim()
                    val searchMatchedIds = remember(searchNeedle, filterCandidates, searchResults) {
                        if (searchNeedle.isEmpty()) null
                        else {
                            val local = filterCandidates.filter {
                                it.title.contains(searchNeedle, ignoreCase = true) ||
                                    (it.cwd?.contains(searchNeedle, ignoreCase = true) == true)
                            }.map { it.sessionId }
                            (local + searchResults.map { it.sessionId }).toSet()
                        }
                    }
                    val visibleCandidates = remember(filterCandidates, searchMatchedIds) {
                        if (searchMatchedIds == null) filterCandidates
                        else filterCandidates.filter { it.sessionId in searchMatchedIds }
                    }
                    val knownWorkspaces = remember(sessions, deletedWorkspaces, workspaceRegistry) {
                        visibleUserWorkspaces(
                            sessionCwds = sessions.map { it.cwd },
                            deletedWorkspaces = deletedWorkspaces,
                            registeredPaths = workspaceRegistry,
                        )
                    }
                    val sidebarGrouped = remember(visibleCandidates, deletedWorkspaces, sessionFilter) {
                        visibleCandidates
                            .filter { sessionFilter == SessionFilter.ALL || classifySession(it) == sessionFilter }
                            .groupBy { it.cwd?.takeUnless { c -> c in deletedWorkspaces } }
                            .filterKeys { isUserWorkspace(it) }
                    }
                    val workspacesToShow = remember(knownWorkspaces, sidebarGrouped, searchNeedle, sessionFilter) {
                        when {
                            searchNeedle.isNotEmpty() -> knownWorkspaces.filter { cwd ->
                                cwd.contains(searchNeedle, ignoreCase = true) ||
                                    cwd.substringAfterLast('/').contains(searchNeedle, ignoreCase = true) ||
                                    sidebarGrouped[cwd].orEmpty().isNotEmpty()
                            }
                            sessionFilter != SessionFilter.ALL ->
                                knownWorkspaces.filter { sidebarGrouped[it].orEmpty().isNotEmpty() }
                            else -> knownWorkspaces
                        }
                    }

                    // 会话列表
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        if (searchNeedle.isNotEmpty() && visibleCandidates.isEmpty() && workspacesToShow.isEmpty()) {
                            item(key = "sidebar-search-empty") {
                                Text(
                                    if (searchState is SearchUiState.Loading) L.searching else L.noMatchingSessions,
                                    color = Dsh.labelTertiary,
                                    fontSize = 13.sp,
                                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 20.dp)
                                )
                            }
                        } else if (flatView) {
                            // 单列表（不分组）—— 过滤已归档/已删除（空白超24h）/子智能体/sessionFilter
                            val visibleSessions = visibleCandidates.filter {
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
                                        selectSession(s.sessionId)
                                        scope.launch { drawerState.close() }
                                    },
                                    onRename = { renameTarget = s },
                                    onArchive = { archiveSessionNow(s) },
                                    onDelete = { deleteSessionTarget = s },
                                    onFork = {
                                        scope.launch(Dispatchers.IO) {
                                            try {
                                                val newId = client.forkSession(s.sessionId)
                                                withContext(Dispatchers.Main) {
                                                    if (newId != null) {
                                                        selectSession(newId)
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
                            // 按工作区（cwd）分组：组头点击 = 折叠；组内 + = 在该工作区新建会话
                            // 工作区列表 = 服务端注册 ∪ 会话 cwd（与新建会话弹层对齐，含空工作区）
                            val grouped = sidebarGrouped
                            grouped[null].orEmpty().filter { !it.blank }.forEach { s ->
                                item(key = "ungrouped-${s.sessionId}") {
                                    SessionRowItem(
                                        session = s,
                                        isSelected = s.sessionId == currentSessionId,
                                        onClick = {
                                            selectSession(s.sessionId)
                                            scope.launch { drawerState.close() }
                                        },
                                        onRename = { renameTarget = s },
                                        onArchive = { archiveSessionNow(s) },
                                        onDelete = { deleteSessionTarget = s },
                                        onFork = {
                                            scope.launch(Dispatchers.IO) {
                                                try {
                                                    val newId = client.forkSession(s.sessionId)
                                                    withContext(Dispatchers.Main) {
                                                        if (newId != null) {
                                                            selectSession(newId)
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
                            workspacesToShow.forEach { cwd ->
                                val groupSessions = grouped[cwd].orEmpty()
                                val collapsed = cwd !in expandedWorkspaces
                                item(key = "ws-$cwd") {
                                    val folderInteraction = remember { MutableInteractionSource() }
                                    val folderPressed by folderInteraction.collectIsPressedAsState()
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(32.dp)
                                            .padding(horizontal = 8.dp)
                                            .clip(RoundedCornerShape(DshRadius.sm))
                                            .background(if (folderPressed) Dsh.bgNavHover else Color.Transparent)
                                            .combinedClickable(
                                                interactionSource = folderInteraction,
                                                indication = null,
                                                onClick = {
                                                    expandedWorkspaces = if (collapsed) expandedWorkspaces + cwd else expandedWorkspaces - cwd
                                                },
                                                onLongClick = { deleteWorkspaceTarget = cwd },
                                            )
                                            .padding(horizontal = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            if (collapsed) ChevronRightOutline14 else ChevronDownOutline14,
                                            contentDescription = null,
                                            tint = Dsh.labelTertiary,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Spacer(Modifier.width(4.dp))
                                        Icon(
                                            FolderOpenOutline16,
                                            contentDescription = null,
                                            tint = Dsh.brand400,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            cwd.substringAfterLast('/'),
                                            color = Dsh.labelPrimary,
                                            fontSize = 14.sp,
                                            lineHeight = 20.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Box(
                                            modifier = Modifier
                                                .size(22.dp)
                                                .clip(RoundedCornerShape(DshRadius.sm))
                                                .clickable { createSessionIn(cwd) },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                PlusOutline16,
                                                contentDescription = L.createSession,
                                                tint = Dsh.labelTertiary,
                                                modifier = Modifier.size(12.dp)
                                            )
                                        }
                                    }
                                }
                                // 组内默认预览 5 条 + 「显示全部」占位行（对齐 Web UI SessionListPage）
                                val showMore = groupSessions.size > 5 && cwd !in expandedGroups
                                val preview = if (showMore) groupSessions.take(5) else groupSessions
                                val rows: List<MobileSession?> = if (showMore) preview + null else preview
                                item(key = "ws-body-$cwd") {
                                    AnimatedVisibility(
                                        visible = !collapsed,
                                        enter = fadeIn(animationSpec = tween(150)) + expandVertically(animationSpec = tween(220, easing = FastOutSlowInEasing), expandFrom = Alignment.Top),
                                        exit = fadeOut(animationSpec = tween(110)) + shrinkVertically(animationSpec = tween(150, easing = FastOutSlowInEasing), shrinkTowards = Alignment.Top)
                                    ) {
                                        Column {
                                            rows.forEach { s ->
                                                if (s == null) {
                                                    Row(
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .height(32.dp)
                                                            .padding(start = 12.dp, end = 6.dp)
                                                            .clip(RoundedCornerShape(DshRadius.md))
                                                            .clickable { expandedGroups = expandedGroups + cwd }
                                                            .padding(horizontal = 10.dp),
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        Text(
                                                            L.expandMoreSessions.format(groupSessions.size - 5),
                                                            color = Dsh.labelTertiary,
                                                            fontSize = 12.sp,
                                                            lineHeight = 18.sp
                                                        )
                                                    }
                                                } else {
                                                    SessionRowItem(
                                                        session = s,
                                                        isSelected = s.sessionId == currentSessionId,
                                                        indent = 12.dp,
                                                        onClick = {
                                                            selectSession(s.sessionId)
                                                            scope.launch { drawerState.close() }
                                                        },
                                                        onRename = { renameTarget = s },
                                                        onArchive = { archiveSessionNow(s) },
                                                        onDelete = { deleteSessionTarget = s },
                                                        onFork = {
                                                            scope.launch(Dispatchers.IO) {
                                                                try {
                                                                    val newId = client.forkSession(s.sessionId)
                                                                    withContext(Dispatchers.Main) {
                                                                        if (newId != null) {
                                                                            selectSession(newId)
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
                                .clip(RoundedCornerShape(DshRadius.md))
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
                            Text(L.settingsTitle, color = Dsh.labelSecondary, fontSize = 13.sp, fontWeight = FontWeight(500), lineHeight = 20.sp)
                        }

                        // 快速切换深浅色模式按钮（带太阳/月亮图标与提示文案）
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(DshRadius.md))
                                .background(if (themePressed) Dsh.hover else Color.Transparent)
                                .clickable(interactionSource = themeInteraction, indication = null) {
                                    ThemeManager.toggleTheme(context, isDarkTheme)
                                }
                                .padding(horizontal = 8.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                if (isDarkTheme) LightOutline16 else DarkOutline16,
                                contentDescription = if (isDarkTheme) L.switchToLight else L.switchToDark,
                                tint = Dsh.labelSecondary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                if (isDarkTheme) L.light else L.dark,
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
                            contentDescription = L.sessionMenu,
                            tint = Dsh.labelSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    // 标题簇（crumbs：面包屑）
                    Spacer(Modifier.width(10.dp))
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(DshRadius.sm)),
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
                            currentSession?.title ?: L.newSession,
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
                                .clip(RoundedCornerShape(DshRadius.full))
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
                            Text(L.stop, color = Dsh.labelSecondary, fontSize = 13.sp, fontWeight = FontWeight(500))
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
                                contentDescription = if (toolSearchOpen) L.closeToolSearch else L.searchToolCalls,
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
                                contentDescription = L.moreActions,
                                tint = Dsh.labelSecondary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        DshMenu(
                            expanded = headerMenuOpen,
                            onDismiss = { headerMenuOpen = false },
                            items = listOf(
                                DshMenuItem(EditOutline16, L.renameSession) {
                                    headerMenuOpen = false
                                    currentSession?.let { renameTarget = it }
                                },
                                DshMenuItem(BranchOutline16, L.forkSession) {
                                    headerMenuOpen = false
                                    val sid = currentSessionId ?: return@DshMenuItem
                                    scope.launch(Dispatchers.IO) {
                                        try {
                                            val newId = client.forkSession(sid)
                                            withContext(Dispatchers.Main) {
                                                if (newId != null) {
                                                            selectSession(newId)
                                                    refreshSessions()
                                                }
                                            }
                                        } catch (e: Exception) {}
                                    }
                                },
                                DshMenuItem(CopyOutline16, L.copySessionTitle) {
                                    headerMenuOpen = false
                                    val title = currentSession?.title ?: return@DshMenuItem
                                    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("session title", title))
                                    Toast.makeText(context, L.copied, Toast.LENGTH_SHORT).show()
                                },
                                DshMenuItem(ArchiveOutline20, L.archiveSession) {
                                    headerMenuOpen = false
                                    currentSession?.let { archiveSessionNow(it) }
                                },
                                DshMenuItem(Icons.Default.Share, L.exportConversation) {
                                    headerMenuOpen = false
                                    val sid = currentSessionId ?: return@DshMenuItem
                                    val title = currentSession?.title ?: L.sessionFallbackTitle
                                    scope.launch(Dispatchers.IO) {
                                        try {
                                            val text = exportSessionTranscript(client, sid, title)
                                            withContext(Dispatchers.Main) {
                                                val send = Intent(Intent.ACTION_SEND).apply {
                                                    type = "text/plain"
                                                    putExtra(Intent.EXTRA_SUBJECT, title)
                                                    putExtra(Intent.EXTRA_TEXT, text)
                                                }
                                                context.startActivity(Intent.createChooser(send, L.exportConversation))
                                            }
                                        } catch (e: Exception) {
                                            withContext(Dispatchers.Main) {
                                                Toast.makeText(context, L.exportFailed.format(e.message ?: L.unknownError), Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    }
                                },
                                DshMenuItem(TrashOutline16, L.deleteSession, danger = true) {
                                    headerMenuOpen = false
                                    currentSession?.let { deleteSessionTarget = it }
                                },
                            )
                        )
                    }
                }

                // Row 2: 对话 / 轨迹 / 历史 tabs；已开聊时 Harness 模式显示在「历史」旁
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    listOf("chat" to L.tabChat, "trace" to L.tabTrace, "history" to L.tabHistory).forEach { (id, label) ->
                        val selected = viewMode == id
                        val vInteraction = remember { MutableInteractionSource() }
                        val vPressed by vInteraction.collectIsPressedAsState()
                        Box(
                            modifier = Modifier
                                .height(26.dp)
                                .clip(RoundedCornerShape(DshRadius.full))
                                .background(
                                    when {
                                        selected -> Dsh.bgNavActive
                                        vPressed -> Dsh.hover
                                        else -> Color.Transparent
                                    }
                                )
                                .clickable(interactionSource = vInteraction, indication = null) {
                                    selectViewMode(id)
                                    if (id == "history") runSearchDebounced(searchQuery)
                                }
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
                    // 已有会话：模式放在历史旁（只读展示，点模式不误开新会话）
                    val showHeaderHarness = currentSessionId != null &&
                        (harnessLabel.isNotBlank() || activeSubagentCount > 0)
                    if (showHeaderHarness) {
                        Spacer(Modifier.width(6.dp))
                        if (harnessLabel.isNotBlank()) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .height(26.dp)
                                    .padding(horizontal = 8.dp),
                            ) {
                                Icon(
                                    AgentPresetOutline16,
                                    contentDescription = null,
                                    tint = Dsh.labelTertiary,
                                    modifier = Modifier.size(12.dp),
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    harnessLabel,
                                    color = Dsh.labelSecondary,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight(500),
                                    lineHeight = 18.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        if (harnessLabel.isNotBlank() && activeSubagentCount > 0) {
                            Text(
                                " · ",
                                color = Dsh.labelCaption,
                                fontSize = 12.sp,
                                lineHeight = 18.sp,
                            )
                        }
                        if (activeSubagentCount > 0) {
                            Text(
                                L.subagentCount.format(activeSubagentCount),
                                color = Dsh.brand400,
                                fontSize = 12.sp,
                                fontWeight = FontWeight(500),
                                lineHeight = 18.sp,
                                modifier = Modifier.clickable { showSubagentSheet = true },
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
                        SessionStreamClient.ConnectionState.CONNECTING -> L.connecting
                        SessionStreamClient.ConnectionState.FAILURE -> L.connectionFailedReconnecting
                        else -> L.disconnectedReconnecting
                    },
                    tone = if (streamState == SessionStreamClient.ConnectionState.FAILURE)
                        DshBannerTone.Error else DshBannerTone.Info,
                    actionLabel = L.retry,
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
                    contentDescription = L.disconnectedReconnectingContentDescription,
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
                            .clip(RoundedCornerShape(DshRadius.md))
                            .background(Dsh.bgInput)
                            .border(1.dp, Dsh.borderL2, RoundedCornerShape(DshRadius.md))
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
                                        Text(L.toolSearchPlaceholder, color = Dsh.labelTertiary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    inner()
                                }
                            }
                        )
                        if (toolQuery.isNotEmpty()) {
                            Icon(
                                CloseOutline16,
                                contentDescription = L.clearSearch,
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
                sessions = sessions.filter {
                    it.sessionId !in archivedIds &&
                        it.sessionId !in deletedIds &&
                        it.origin != "subagent"
                },
                searchQuery = searchQuery,
                searchResults = searchResults,
                searchState = searchState,
                onQueryChange = { q ->
                    searchQuery = q
                    tabPrefs.edit().putString("history_query", q).apply()
                    runSearchDebounced(q)
                },
                onOpenSession = { sid ->
                    selectSession(sid)
                    selectViewMode("chat")
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
            val allMessages = remember(olderMessages, messages) { mergeHistoryPages(olderMessages, messages) }
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
                        HeroShell()
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
                    // 正在执行的最后一条工具/思考消息带扫光（DSH command/reasoning sweep）
                    val sweepingId = if (running) {
                        messages.lastOrNull { it.role == "tool_call" || it.role == "tool_result" || it.role == "reasoning" }?.id
                    } else null
                    // 稳定列表引用（内容不变时避免 LazyColumn 滚动状态失效）
                    items(visibleGroups, key = { it.groupKey }) { group ->
                        AnimatedVisibility(
                            visible = true,
                            enter = if (group is MessageGroup.Single && group.msg.entrance)
                                fadeIn(animationSpec = tween(DshDuration.normal)) +
                                slideInVertically(animationSpec = tween(DshDuration.normal, easing = DshEasing.inOut), initialOffsetY = { 20 })
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
                                                val accepted = client.answerApproval(sid, approvalId, outcome)
                                                if (!accepted) withContext(Dispatchers.Main) {
                                                    Toast.makeText(context, L.approvalNotAccepted, Toast.LENGTH_SHORT).show()
                                                }
                                            } catch (e: Exception) {
                                                withContext(Dispatchers.Main) {
                                                    Toast.makeText(context, L.approvalFailed.format(e.message ?: L.unknownError), Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                        }
                                    },
                                    onCopy = {
                                        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("dsh message", group.msg.text))
                                        Toast.makeText(context, L.copied, Toast.LENGTH_SHORT).show()
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
                                                        selectSession(newId)
                                                        refreshSessions()
                                                    }
                                                }
                                            } catch (e: Exception) {}
                                        }
                                    },
                                    onRegenerate = if (group.msg.role == "assistant") {{
                                        val sid = currentSessionId ?: return@MessageItem
                                        val all = mergeHistoryPages(olderMessages, messages)
                                        val idx = all.indexOfFirst { it.id == group.msg.id }
                                        val prevUser = if (idx > 0) {
                                            all.subList(0, idx).lastOrNull { it.role == "user" }
                                        } else null
                                        val prompt = prevUser?.text?.takeIf { it.isNotBlank() }
                                        if (prompt.isNullOrBlank()) {
                                            Toast.makeText(context, L.cannotFindUserMessageToRegenerate, Toast.LENGTH_SHORT).show()
                                        } else {
                                            scope.launch(Dispatchers.IO) {
                                                try {
                                                    client.sendPrompt(
                                                        sid,
                                                        prompt,
                                                        mode = resolvePromptMode(running, appSettings.busyEnter),
                                                    )
                                                    withContext(Dispatchers.Main) {
                                                        Toast.makeText(context, L.regenerated, Toast.LENGTH_SHORT).show()
                                                        refreshSessions()
                                                    }
                                                } catch (e: Exception) {
                                                    withContext(Dispatchers.Main) {
                                                        Toast.makeText(context, L.regenerateFailed.format(e.message ?: L.unknownError), Toast.LENGTH_SHORT).show()
                                                    }
                                                }
                                            }
                                        }
                                    }} else null,
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
                                L.noMatchingToolCalls,
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
                    // 等待/执行中：流尾部显示品牌蓝 shimmer（对齐 web Deep diving…）
                    if (running || isSending) {
                        item(key = "turn-status") {
                            TurnStatusRow(elapsedSec)
                        }
                    }
                    // 已停止标记：仅在非执行态展示，避免等待新回复时误显示旧 turn 的停止态
                    if (stoppedReason != null && !running && !isSending) {
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
                                        client.sendPrompt(
                                            sid,
                                            picked.trigger,
                                            mode = resolvePromptMode(running, appSettings.busyEnter),
                                        )
                                        withContext(Dispatchers.Main) {
                                            refreshSessions()
                                            if (streamClient?.isConnected != true) {
                                                refreshMessages(autoScroll = true)
                                            }
                                        }
                                    } catch (e: Exception) {
                                        withContext(Dispatchers.Main) {
                                            Toast.makeText(context, L.commandSendFailed.format(e.message ?: L.unknownError), Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }
                            }
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
                // 工作区 + Harness 模式（对标 web，置于输入卡上方）
                // 新会话：模式仅在此处；已开聊：模式移到顶部「历史」旁，此处只保留工作区
                if (viewMode == "chat") {
                    ComposerTopRow(
                        sessions = sessions,
                        deletedWorkspaces = deletedWorkspaces,
                        registeredPaths = workspaceRegistry,
                        currentCwd = if (currentSessionId == null) {
                            pendingSessionCwd?.takeUnless { it in deletedWorkspaces }
                                ?: currentSession?.cwd?.takeUnless { it in deletedWorkspaces }
                        } else {
                            currentSession?.cwd?.takeUnless { it in deletedWorkspaces }
                        },
                        lastCwd = workspacePrefs.lastSelectedWorkspace
                            ?.takeUnless { it in deletedWorkspaces },
                        harnessLabel = harnessLabel,
                        showHarness = currentSessionId == null,
                        workspaceEditable = currentSessionId == null,
                        harnessEditable = currentSessionId == null,
                        onOpenHarnessPicker = if (currentSessionId == null) {
                            { showAgentPresetPicker = true }
                        } else {
                            null
                        },
                        onStartSession = { cwd ->
                            pendingSessionCwd = cwd
                            if (cwd != null) workspacePrefs.lastSelectedWorkspace = cwd
                        },
                    )
                }
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
                                .clip(RoundedCornerShape(DshRadius.full))
                                .background(Dsh.bgLayer1)
                                .border(1.dp, Dsh.borderL2, RoundedCornerShape(DshRadius.full))
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
                            Text(L.scrollToBottom, color = Dsh.labelSecondary, fontSize = 12.sp, fontWeight = FontWeight(500), lineHeight = 18.sp)
                        }
                    }
                }
                // 发送队列 dock（DSH QueueDock：发送中显示在输入卡上方）
                if (isSending) {
                    QueueDock(
                        label = if (running) L.agentStillRunning else L.sending,
                        badge = when {
                            !running -> L.sendingBadge
                            appSettings.busyEnter == "send" -> L.interruptBadge
                            appSettings.busyEnter == "steer" -> L.steerBadge
                            else -> L.queueBadge
                        },
                    )
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
                canSend = (inputText.isNotBlank() || pendingImages.isNotEmpty()) && !isSending,
                running = running,
                currentModel = inputModelLabel,
                sessionStats = sessionStats,
                heroMode = heroMode,
                onModeChange = { heroMode = it },
                permissionLabel = when (appSettings.permissionPreset) {
                    "read-only" -> L.permReadOnly
                    "danger-full-access" -> L.permFullAccess
                    else -> L.permWorkspaceWrite
                },
                onOpenModelPicker = {
                    if (currentSessionId == null) {
                        scope.launch(Dispatchers.IO) {
                            try {
                                val groups = client.getLlmModels()
                                val pending = pendingModel
                                withContext(Dispatchers.Main) {
                                    modelCatalog = MobileModelCatalog(
                                        currentProvider = pending?.first,
                                        currentModel = pending?.second,
                                        currentReasoningEffort = pending?.third,
                                        groups = groups,
                                    )
                                    showModelPicker = true
                                }
                            } catch (e: Exception) {
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(context, L.loadModelListFailed, Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    } else {
                        refreshModels()
                        showModelPicker = true
                    }
                },
                onOpenPermissionPicker = { showPermissionPicker = true },
                onToggleVoice = {
                    if (isListening) {
                        onStopVoiceInput()
                        isListening = false
                    } else {
                        isListening = true
                        onStartVoiceInput({ recognizedText ->
                            isListening = false
                            if (recognizedText.isNotBlank()) {
                                inputText = if (inputText.isBlank()) recognizedText else "$inputText $recognizedText"
                            }
                        }, { isListening = false })
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
                    val rawText = inputText.trim()
                    val images = pendingImages
                    if ((rawText.isNotBlank() || images.isNotEmpty()) && !isSending) {
                        val textToSend = when (heroMode) {
                            HeroMode.PLAN -> if (rawText.startsWith("/plan")) rawText else "/plan $rawText"
                            HeroMode.GOAL -> if (rawText.startsWith("/goal")) rawText else "/goal $rawText"
                            HeroMode.CHAT -> rawText
                        }
                        isSending = true
                        stoppedReason = null
                        liveRunning = true
                        inputText = ""
                        pendingImages = emptyList()
                        if (textToSend.isNotBlank()) {
                            messages = messages.filterNot { it.id == "local-pending" }
                            appendStreamMessage(
                                MobileMessage(
                                    id = "local-pending",
                                    role = "user",
                                    text = textToSend,
                                    time = System.currentTimeMillis(),
                                    type = "text",
                                ),
                            )
                        }
                        scope.launch(Dispatchers.IO) {
                            try {
                                var sid = currentSessionId
                                val createdNow = sid == null
                                if (sid == null) {
                                    sid = client.createSession(
                                        agentPreset = pendingAgentPreset,
                                        cwd = pendingSessionCwd,
                                    )
                                    pendingModel?.let { (provider, model, effort) ->
                                        client.selectModel(sid, provider, model, effort)
                                    } ?: run {
                                        val provider = appSettings.defaultModelProvider
                                        val model = appSettings.defaultModel
                                        if (!provider.isNullOrBlank() && !model.isNullOrBlank()) {
                                            client.selectModel(sid, provider, model, appSettings.defaultReasoningEffort)
                                        }
                                    }
                                    withContext(Dispatchers.Main) {
                                        composeNewSession = false
                                        preserveMessagesSessionId = sid
                                        currentSessionId = sid
                                        refreshSessions()
                                        refreshModels()
                                    }
                                }
                                val promptMode = resolvePromptMode(!createdNow && running, appSettings.busyEnter)
                                if (!createdNow && running) {
                                    withContext(Dispatchers.Main) {
                                        val tip = when (promptMode) {
                                            "send" -> L.interruptSent
                                            "steer" -> L.steerSent
                                            else -> L.queuedSent
                                        }
                                        Toast.makeText(context, tip, Toast.LENGTH_SHORT).show()
                                    }
                                }
                                client.sendPrompt(sid!!, textToSend, mode = promptMode, images = images)
                                withContext(Dispatchers.Main) {
                                    refreshSessions()
                                    refreshMessages(autoScroll = true)
                                }
                            } catch (e: Exception) {
                                withContext(Dispatchers.Main) {
                                    messages = messages.filterNot { it.id == "local-pending" }
                                    // 发送失败：若尚未真正进入 turn，收回乐观 running
                                    if (currentSession?.running != true) liveRunning = false
                                    Toast.makeText(context, L.sendFailed.format(e.message ?: L.unknownError), Toast.LENGTH_SHORT).show()
                                }
                            } finally {
                                withContext(Dispatchers.Main) { isSending = false }
                            }
                        }
                    }
                }
            )

            StatsLine(stats = sessionStats)

            } // bottom chrome 容器结束
        }
    }

    // 模型选择底部抽屉
    if (showModelPicker) {
        ModelPickerSheet(
            catalog = modelCatalog,
            onDismiss = { showModelPicker = false },
            onSelect = { provider, model, effort ->
                val sid = currentSessionId
                if (sid == null) {
                    pendingModel = Triple(provider, model, effort)
                    modelCatalog = modelCatalog?.copy(
                        currentProvider = provider,
                        currentModel = model,
                        currentReasoningEffort = effort,
                    )
                    showModelPicker = false
                    return@ModelPickerSheet
                }
                scope.launch(Dispatchers.IO) {
                    try {
                        client.selectModel(sid, provider, model, effort)
                        withContext(Dispatchers.Main) { refreshModels() }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, L.switchModelFailed.format(friendlySelectModelError(e.message)), Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        )
    }

    if (showAgentPresetPicker) {
        AgentPresetPickerSheet(
            presets = agentPresets,
            currentId = pendingAgentPreset,
            onDismiss = { showAgentPresetPicker = false },
            onSelect = { id ->
                pendingAgentPreset = id
                showAgentPresetPicker = false
            },
        )
    }

    if (showSessionFilterSheet) {
        val staleCutoff = System.currentTimeMillis() - 24 * 3600_000
        val filterBase = sessions.filter {
            it.sessionId !in archivedIds && it.sessionId !in deletedIds &&
                it.origin != "subagent" &&
                !(it.blank && it.updatedAt < staleCutoff)
        }
        val filterCounts = mapOf(
            SessionFilter.ALL to filterBase.size,
            SessionFilter.RUNNING to filterBase.count { classifySession(it) == SessionFilter.RUNNING },
            SessionFilter.STOPPED to filterBase.count { classifySession(it) == SessionFilter.STOPPED },
        )
        SessionFilterSheet(
            selected = sessionFilter,
            counts = filterCounts,
            onSelect = { sessionFilter = it },
            onDismiss = { showSessionFilterSheet = false },
        )
    }

    // 访问模式选择（有会话 → 改当前会话；无会话 → 写全局默认）
    if (showPermissionPicker) {
        PermissionPickerSheet(
            context = context,
            host = host,
            currentPreset = appSettings.permissionPreset,
            sessionId = currentSessionId,
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
                Text(L.addWorkspace, color = Dsh.labelPrimary, fontSize = 18.sp, fontWeight = FontWeight(500), lineHeight = 24.sp)
                Spacer(Modifier.height(4.dp))
                Text(L.addWorkspaceDesc, color = Dsh.labelTertiary, fontSize = 12.sp, lineHeight = 18.sp)
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
                        .clip(RoundedCornerShape(DshRadius.lg))
                        .background(Dsh.bgInput)
                        .border(1.dp, Dsh.borderL2, RoundedCornerShape(DshRadius.lg))
                        .padding(horizontal = 13.dp),
                    decorationBox = { inner ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (path.isEmpty()) Text(L.workspacePathExample, color = Dsh.labelTertiary, fontSize = 13.sp)
                            inner()
                        }
                    }
                )
                Spacer(Modifier.height(14.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .clip(RoundedCornerShape(DshRadius.lg))
                        .background(if (path.isBlank()) Dsh.buttonElevated.copy(alpha = 0.5f) else Dsh.brand400)
                        .clickable(enabled = path.isNotBlank()) {
                            showAddWorkspace = false
                            scope.launch(Dispatchers.IO) {
                                try {
                                    client.createWorkspace(path.trim())
                                    withContext(Dispatchers.Main) {
                                        refreshWorkspaces()
                                        refreshSessions()
                                    }
                                } catch (e: Exception) {}
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(L.add, color = if (path.isBlank()) Dsh.labelTertiary else Color.White, fontSize = 13.sp, fontWeight = FontWeight(500))
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
            title = L.deleteWorkspaceTitle,
            message = L.deleteWorkspaceMessage.format(path.substringAfterLast('/')),
            confirmLabel = L.delete,
            danger = true,
            onDismiss = { deleteWorkspaceTarget = null },
            onConfirm = {
                deleteWorkspaceTarget = null
                setDeletedWorkspace(path)
                workspaceRegistry = workspaceRegistry.filter { it != path }
                if (pendingSessionCwd == path) pendingSessionCwd = null
                if (workspacePrefs.lastSelectedWorkspace == path) {
                    workspacePrefs.lastSelectedWorkspace = null
                }
                val sessionsInWs = sessions.filter { it.cwd == path }
                sessionsInWs.forEach { setDeleted(it.sessionId) }
                refreshSessions()
                expandedWorkspaces = expandedWorkspaces - path
                expandedGroups = expandedGroups - path
                scope.launch(Dispatchers.IO) {
                    try { client.deleteWorkspace(path) } catch (_: Exception) {}
                    sessionsInWs.forEach { s ->
                        try { client.archiveSession(s.sessionId) } catch (_: Exception) {}
                    }
                    withContext(Dispatchers.Main) { refreshWorkspaces() }
                }
            }
        )
    }

    // 删除会话（服务端归档 + 本机隐藏）
    deleteSessionTarget?.let { target ->
        DshConfirmDialog(
            title = L.deleteSessionTitle,
            message = L.deleteSessionMessage.format(target.title),
            confirmLabel = L.delete,
            danger = true,
            onDismiss = { deleteSessionTarget = null },
            onConfirm = {
                deleteSessionTarget = null
                archiveSessionNow(target)
            }
        )
    }

    if (showSubagentSheet) {
        val anchorParent = currentSession?.parentSessionId ?: currentSessionId
        val children = remember(sessions, anchorParent) {
            sessions.filter { it.origin == "subagent" && it.parentSessionId == anchorParent }
                .sortedByDescending { it.updatedAt }
        }
        val parentOfCurrent = currentSession?.parentSessionId
        ModalBottomSheet(
            onDismissRequest = { showSubagentSheet = false },
            containerColor = Dsh.bgLayer1,
            contentColor = Dsh.labelPrimary,
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
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
                SheetGrabber()
                Text(L.subagents, color = Dsh.labelPrimary, fontSize = 18.sp, fontWeight = FontWeight(600))
                Spacer(Modifier.height(4.dp))
                Text(
                    if (children.isEmpty()) L.noSubagentSessions else L.subagentSheetSummary.format(children.size),
                    color = Dsh.labelTertiary,
                    fontSize = 13.sp,
                )
                Spacer(Modifier.height(12.dp))
                children.forEach { child ->
                    val selected = child.sessionId == currentSessionId
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(DshRadius.md))
                            .background(if (selected) Dsh.bgNavActive else Color.Transparent)
                            .clickable {
                                showSubagentSheet = false
                                selectSession(child.sessionId)
                            }
                            .padding(horizontal = 12.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                child.title,
                                color = Dsh.labelPrimary,
                                fontSize = 14.sp,
                                fontWeight = FontWeight(500),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (child.running) {
                                Text(L.runningStatus, color = Dsh.brand400, fontSize = 11.sp)
                            }
                        }
                    }
                }
                if (!parentOfCurrent.isNullOrBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        L.returnToParentSession,
                        color = Dsh.brand400,
                        fontSize = 13.sp,
                        fontWeight = FontWeight(500),
                        modifier = Modifier
                            .clickable {
                                showSubagentSheet = false
                                selectSession(parentOfCurrent)
                            }
                            .padding(12.dp),
                    )
                }
            }
        }
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
                    .clip(RoundedCornerShape(DshRadius.lg))
                    .background(Dsh.bgLayer1)
                    .border(1.dp, Dsh.borderL2, RoundedCornerShape(DshRadius.lg))
                    .padding(18.dp)
            ) {
                Text(L.renameSession, color = Dsh.labelPrimary, fontSize = 15.sp, fontWeight = FontWeight(500), lineHeight = 21.sp)
                Spacer(Modifier.height(8.dp))
                Text(L.renameSessionDesc, color = Dsh.labelTertiary, fontSize = 12.sp, lineHeight = 17.sp)
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
                        .clip(RoundedCornerShape(DshRadius.lg))
                        .background(Dsh.bgInput)
                        .border(1.dp, Dsh.borderL2, RoundedCornerShape(DshRadius.lg))
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
                        Text(L.cancel, color = Dsh.labelSecondary, fontSize = 12.sp, fontWeight = FontWeight(500))
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
                        Text(L.save, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight(500))
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
                    .clip(RoundedCornerShape(DshRadius.lg))
                    .background(Dsh.bgLayer1)
                    .border(1.dp, Dsh.borderL2, RoundedCornerShape(DshRadius.lg))
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
                        Text(L.cancel, color = Dsh.labelSecondary, fontSize = 12.sp, fontWeight = FontWeight(500))
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

// ---------- 新会话 Hero（仅口号；工作区/模式在输入区顶栏） ----------

private enum class HeroMode { CHAT, PLAN, GOAL }

@Composable
private fun HeroShell() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 64.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            L.heroSlogan,
            color = Dsh.labelPrimary,
            fontSize = 26.sp,
            fontWeight = FontWeight(500),
            lineHeight = 32.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}

// ---------- 工作区选择弹层 ----------

@Composable
private fun SheetGrabber() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .width(36.dp)
                .height(4.dp)
                .clip(CircleShape)
                .background(Dsh.borderL3)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WorkspacePickerSheet(
    sessions: List<MobileSession>,
    deletedWorkspaces: Set<String> = emptySet(),
    registeredPaths: List<String> = emptyList(),
    selectedPath: String? = null,
    onDismiss: () -> Unit,
    onPick: (String?) -> Unit,
) {
    val workspaces = remember(sessions, deletedWorkspaces, registeredPaths) {
        visibleUserWorkspaces(
            sessionCwds = sessions.map { it.cwd },
            deletedWorkspaces = deletedWorkspaces,
            registeredPaths = registeredPaths,
        )
    }
    var query by remember { mutableStateOf("") }
    val filtered = remember(workspaces, query) {
        if (query.isBlank()) workspaces
        else workspaces.filter {
            it.contains(query, ignoreCase = true) || it.substringAfterLast('/').contains(query, ignoreCase = true)
        }
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
                .navigationBarsPadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp)
        ) {
            SheetGrabber()
            Text(L.chooseWorkspaceTitle, color = Dsh.labelPrimary, fontSize = 18.sp, fontWeight = FontWeight(600), lineHeight = 24.sp)
            Spacer(Modifier.height(4.dp))
            Text(L.chooseWorkspaceDesc, color = Dsh.labelTertiary, fontSize = 13.sp, lineHeight = 18.sp)
            if (workspaces.size > 6) {
                Spacer(Modifier.height(12.dp))
                SheetSearchField(value = query, onValueChange = { query = it }, placeholder = L.searchWorkspace)
            }
            Spacer(Modifier.height(14.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                WorkspaceOptionRow(
                    title = L.ungrouped,
                    path = L.noWorkspaceBinding,
                    selected = selectedPath == null,
                    onClick = {
                        onDismiss()
                        onPick(null)
                    }
                )
                filtered.forEach { ws ->
                    WorkspaceOptionRow(
                        title = ws.substringAfterLast('/'),
                        path = ws,
                        selected = ws == selectedPath,
                        onClick = {
                            onDismiss()
                            onPick(ws)
                        }
                    )
                }
                if (query.isNotBlank() && filtered.isEmpty()) {
                    Text(
                        L.noMatchingWorkspace.format(query),
                        color = Dsh.labelTertiary,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 16.dp)
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
private fun SheetSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp)
            .clip(RoundedCornerShape(DshRadius.lg))
            .background(Dsh.bgSelector)
            .border(1.dp, Dsh.borderL2, RoundedCornerShape(DshRadius.lg))
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(SearchOutline16, contentDescription = null, tint = Dsh.labelCaption, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = TextStyle(color = Dsh.labelPrimary, fontSize = 14.sp, lineHeight = 20.sp),
            cursorBrush = SolidColor(Dsh.brand400),
            modifier = Modifier.weight(1f),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) Text(placeholder, color = Dsh.labelTertiary, fontSize = 14.sp)
                    inner()
                }
            }
        )
        if (value.isNotEmpty()) {
            Icon(
                CloseOutline16,
                contentDescription = L.clearSearch,
                tint = Dsh.labelCaption,
                modifier = Modifier
                    .size(16.dp)
                    .clickable { onValueChange("") }
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
            .heightIn(min = 56.dp)
            .clip(RoundedCornerShape(DshRadius.lg))
            .background(
                when {
                    selected -> Dsh.brand400.copy(alpha = 0.08f)
                    pressed -> Dsh.hover
                    else -> Dsh.bgSelector
                }
            )
            .border(
                1.dp,
                if (selected) Dsh.brand400.copy(alpha = 0.35f) else Dsh.borderL1,
                RoundedCornerShape(DshRadius.lg)
            )
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(DshRadius.md))
                .background(if (selected) Dsh.brand400.copy(alpha = 0.16f) else Dsh.bgLayer1),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                FolderOpenOutline16,
                contentDescription = null,
                tint = if (selected) Dsh.brand400 else Dsh.labelSecondary,
                modifier = Modifier.size(16.dp)
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                color = Dsh.labelPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight(500),
                lineHeight = 20.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                path,
                color = Dsh.labelTertiary,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (selected) {
            Spacer(Modifier.width(8.dp))
            Icon(CheckOutline16, contentDescription = null, tint = Dsh.brand400, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun AddWorkspaceRow(onCreate: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    var path by remember { mutableStateOf("") }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(DshRadius.lg))
            .background(Dsh.bgInput)
            .border(1.dp, Dsh.borderL2, RoundedCornerShape(DshRadius.lg))
            .padding(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(DshRadius.md))
                .clickable { expanded = !expanded }
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(Dsh.brand400.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(PlusOutline16, contentDescription = null, tint = Dsh.brand400, modifier = Modifier.size(14.dp))
            }
            Spacer(Modifier.width(10.dp))
            Text(L.addWorkspace, color = Dsh.labelPrimary, fontSize = 14.sp, fontWeight = FontWeight(500), lineHeight = 20.sp)
            Spacer(Modifier.weight(1f))
            Icon(
                if (expanded) ChevronUpOutline14 else ChevronDownOutline14,
                contentDescription = null,
                tint = Dsh.labelCaption,
                modifier = Modifier.size(14.dp)
            )
        }
        AnimatedVisibility(visible = expanded) {
            Row(
                modifier = Modifier.padding(top = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BasicTextField(
                    value = path,
                    onValueChange = { path = it },
                    singleLine = true,
                    textStyle = TextStyle(color = Dsh.labelPrimary, fontSize = 13.sp),
                    cursorBrush = SolidColor(Dsh.brand400),
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(DshRadius.md))
                        .background(Dsh.bgLayer3)
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    decorationBox = { inner ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (path.isEmpty()) Text(L.enterWorkspacePath, color = Dsh.labelTertiary, fontSize = 13.sp)
                            inner()
                        }
                    }
                )
                Spacer(Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .height(32.dp)
                        .clip(RoundedCornerShape(DshRadius.md))
                        .background(if (path.isBlank()) Dsh.buttonElevated.copy(alpha = 0.5f) else Dsh.brand400)
                        .clickable(enabled = path.isNotBlank()) { onCreate(path.trim()) }
                        .padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(L.create, color = if (path.isBlank()) Dsh.labelTertiary else Color.White, fontSize = 13.sp, fontWeight = FontWeight(500))
                }
            }
        }
    }
}

// ---------- 正在执行状态行（品牌蓝 shimmer） ----------

@Composable
private fun TurnStatusRow(elapsedSec: Long) {
    val statusLabel = "Deep diving…"
    Row(
        modifier = Modifier
            .height(26.dp)
            .clip(RoundedCornerShape(DshRadius.sm)),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isReduceMotionEnabled()) {
            // reduce-motion：静态品牌蓝文本（Web prefers-reduced-motion 语义）
            Text(
                statusLabel,
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
            val textLayout = remember(statusLabel) {
                textMeasurer.measure(
                    statusLabel,
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

// ---------- 侧栏工具 ----------

@Composable
private fun SidebarHeaderIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    active: Boolean = false,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(RoundedCornerShape(DshRadius.sm))
            .background(
                when {
                    active -> Dsh.bgNavActive
                    pressed -> Dsh.hover
                    else -> Color.Transparent
                },
            )
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = if (active) Dsh.labelPrimary else Dsh.labelTertiary,
            modifier = Modifier.size(16.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionFilterSheet(
    selected: SessionFilter,
    counts: Map<SessionFilter, Int>,
    onSelect: (SessionFilter) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Dsh.bgLayer1,
        contentColor = Dsh.labelPrimary,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
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
            SheetGrabber()
            Text(L.filterSessions, color = Dsh.labelPrimary, fontSize = 18.sp, fontWeight = FontWeight(600), lineHeight = 24.sp)
            Spacer(Modifier.height(14.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SessionFilter.entries.forEach { f ->
                    val label = when (f) {
                        SessionFilter.ALL -> L.allSessions
                        SessionFilter.RUNNING -> L.runningStatus
                        SessionFilter.STOPPED -> L.stopped
                    }
                    PermissionModeOption(
                        title = "$label · ${counts[f] ?: 0}",
                        desc = when (f) {
                            SessionFilter.ALL -> L.showAllSessions
                            SessionFilter.RUNNING -> L.onlyShowRunningSessions
                            SessionFilter.STOPPED -> L.onlyShowStoppedSessions
                        },
                        accent = Dsh.labelSecondary,
                        icon = ChecklistOutline14,
                        selected = f == selected,
                        enabled = true,
                        onClick = {
                            onSelect(f)
                            onDismiss()
                        },
                    )
                }
            }
        }
    }
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
                    SessionFilter.ALL -> L.allSessions
                    SessionFilter.RUNNING -> L.runningStatus
                    SessionFilter.STOPPED -> L.stopped
                },
                count = counts[f] ?: 0,
                selected = f == selected,
                onClick = { onSelect(f) },
                contentDescription = "${L.filterSessions}: ${when (f) {
                    SessionFilter.ALL -> L.allSessions
                    SessionFilter.RUNNING -> L.runningStatus
                    SessionFilter.STOPPED -> L.stopped
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
private fun StatsLine(stats: MobileSessionStats?) {
    if (stats == null) return
    if (stats.turns <= 0 && stats.steps <= 0 && stats.llmMs <= 0 && stats.outputTokens <= 0) return
    val parts = buildList {
        if (stats.turns > 0 || stats.steps > 0) add(L.statsTurnsSteps.format(stats.turns, stats.steps))
        if (stats.llmMs > 0) add("LLM ${formatDuration(stats.llmMs)}")
        val ttft = when {
            stats.ttftSteps > 0 -> stats.ttftMs / stats.ttftSteps
            stats.ttftMs > 0 -> stats.ttftMs
            else -> 0L
        }
        val tokPerSec = if (stats.decodeMs > 0 && stats.decodeTokens > 0)
            stats.decodeTokens * 1000.0 / stats.decodeMs
        else null
        val perf = buildList {
            if (ttft > 0) add(L.firstToken.format(formatDuration(ttft)))
            if (tokPerSec != null) add(String.format(Locale.US, "%.0f tok/s", tokPerSec))
        }
        if (perf.isNotEmpty()) add(perf.joinToString(" · "))
        val inTokens = stats.uncachedInputTokens + stats.cacheReadTokens
        if (inTokens > 0 && stats.cacheReadTokens > 0) {
            add(L.cacheHit.format((stats.cacheReadTokens * 100 / inTokens).toInt()))
        }
        if (inTokens > 0 || stats.outputTokens > 0) {
            add(L.inputOutputTokens.format(formatTokens(inTokens), formatTokens(stats.outputTokens)))
        }
    }
    if (parts.isEmpty()) return
    // 移动端两行固定展示，避免横向滑动与系统左右切应用手势冲突
    val splitAt = (parts.size + 1) / 2
    val rows = listOf(parts.take(splitAt), parts.drop(splitAt)).filter { it.isNotEmpty() }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = COMPOSER_SIDE_CLEARANCE, end = COMPOSER_SIDE_CLEARANCE, top = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        rows.forEach { rowParts ->
            Text(
                rowParts.joinToString("  |  "),
                color = Dsh.labelTertiary,
                fontSize = 10.sp,
                lineHeight = 14.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}


// @Composable
// private fun DetailRow(label: String, value: String) {
//     Row(
//         modifier = Modifier
//             .fillMaxWidth()
//             .padding(vertical = 3.dp),
//         verticalAlignment = Alignment.CenterVertically
//     ) {
//         Text(label, color = Dsh.labelTertiary, fontSize = 12.sp, lineHeight = 18.sp, modifier = Modifier.weight(1f))
//         Text(value, color = Dsh.labelSecondary, fontSize = 12.sp, lineHeight = 18.sp, fontFamily = FontFamily.Monospace)
//     }
// }

// ---------- 模型选择（对标 Web：模型 / 推理等级两级菜单） ----------

private enum class ModelPickerPage { MENU, MODELS, EFFORT }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelPickerSheet(
    catalog: MobileModelCatalog?,
    onDismiss: () -> Unit,
    onSelect: (provider: String, model: String, effort: String?) -> Unit,
) {
    var page by remember { mutableStateOf(ModelPickerPage.MENU) }
    var query by remember { mutableStateOf("") }
    val currentOption = remember(catalog) {
        catalog?.groups?.asSequence()?.flatMap { g -> g.models.asSequence().map { g to it } }
            ?.firstOrNull { (g, m) ->
                m.id == catalog.currentModel &&
                    (g.provider == catalog.currentProvider || g.displayName == catalog.currentProvider)
            }
    }
    val currentName = currentOption?.second?.name ?: catalog?.currentModel
    val currentEffort = catalog?.currentReasoningEffort
        ?: currentOption?.second?.defaultEffort
    val currentEfforts = currentOption?.second?.reasoningEfforts.orEmpty()
    val filteredGroups = remember(catalog, query) {
        val groups = catalog?.groups.orEmpty()
        if (query.isBlank()) groups
        else groups.mapNotNull { group ->
            val models = group.models.filter {
                it.id.contains(query, ignoreCase = true) ||
                    (it.name?.contains(query, ignoreCase = true) == true) ||
                    group.displayName.contains(query, ignoreCase = true)
            }
            if (models.isEmpty()) null else group.copy(models = models)
        }
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
                .navigationBarsPadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = 20.dp)
        ) {
            SheetGrabber()
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (page != ModelPickerPage.MENU) {
                    Icon(
                        ChevronLeftOutline14,
                        contentDescription = L.back,
                        tint = Dsh.labelSecondary,
                        modifier = Modifier
                            .size(22.dp)
                            .clip(CircleShape)
                            .clickable { page = ModelPickerPage.MENU }
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    when (page) {
                        ModelPickerPage.MENU -> L.selectModel
                        ModelPickerPage.MODELS -> L.model
                        ModelPickerPage.EFFORT -> L.reasoningEffort
                    },
                    color = Dsh.labelPrimary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight(600),
                    lineHeight = 24.sp
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                if (currentName != null) {
                    listOfNotNull(currentName, currentEffort?.let { formatEffortLabel(it) }).joinToString(" ")
                } else L.selectSessionModel,
                color = Dsh.labelTertiary,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(14.dp))

            when (page) {
                ModelPickerPage.MENU -> {
                    ModelMenuRow(
                        title = L.model,
                        value = currentName ?: L.noneSelected,
                        onClick = { page = ModelPickerPage.MODELS }
                    )
                    Spacer(Modifier.height(6.dp))
                    ModelMenuRow(
                        title = L.reasoningEffort,
                        value = currentEffort?.let { formatEffortLabel(it) } ?: if (currentEfforts.isEmpty()) "—" else L.defaultLabel,
                        enabled = currentEfforts.isNotEmpty(),
                        onClick = { page = ModelPickerPage.EFFORT }
                    )
                }
                ModelPickerPage.MODELS -> {
                    SheetSearchField(value = query, onValueChange = { query = it }, placeholder = L.searchModelProvider)
                    Spacer(Modifier.height(12.dp))
                    when {
                        catalog == null -> Text(L.loadingModelList, color = Dsh.labelTertiary, fontSize = 13.sp, modifier = Modifier.padding(vertical = 16.dp))
                        catalog.groups.isEmpty() -> Text(L.noAvailableModels, color = Dsh.labelTertiary, fontSize = 13.sp, modifier = Modifier.padding(vertical = 16.dp))
                        filteredGroups.isEmpty() -> Text(L.noMatchingModels.format(query), color = Dsh.labelTertiary, fontSize = 13.sp, modifier = Modifier.padding(vertical = 16.dp))
                        else -> Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 420.dp)
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            filteredGroups.forEach { group ->
                                Row(
                                    modifier = Modifier.padding(top = 10.dp, bottom = 6.dp, start = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    val accent = providerAccent(group.displayName)
                                    Box(
                                        modifier = Modifier
                                            .size(18.dp)
                                            .clip(RoundedCornerShape(5.dp))
                                            .background(accent.copy(alpha = 0.16f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            group.displayName.take(1).uppercase(),
                                            color = accent,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight(600),
                                            lineHeight = 12.sp
                                        )
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    Text(group.displayName, color = Dsh.labelTertiary, fontSize = 12.sp, fontWeight = FontWeight(500), lineHeight = 16.sp)
                                }
                                group.models.forEach { model ->
                                    val selected = model.id == catalog.currentModel &&
                                        (group.provider == catalog.currentProvider || group.displayName == catalog.currentProvider)
                                    ModelOptionRow(
                                        name = model.name ?: model.id,
                                        contextWindow = model.contextWindow,
                                        reasoningEfforts = emptyList(),
                                        selected = selected,
                                        onClick = {
                                            val effort = if (selected) currentEffort else model.defaultEffort
                                            onSelect(group.provider, model.id, effort)
                                            page = ModelPickerPage.MENU
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
                ModelPickerPage.EFFORT -> {
                    if (currentEfforts.isEmpty()) {
                        Text(L.noAdjustableReasoningEffort, color = Dsh.labelTertiary, fontSize = 13.sp, modifier = Modifier.padding(vertical = 16.dp))
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            currentEfforts.forEach { effort ->
                                val selected = effort.equals(currentEffort, ignoreCase = true)
                                ModelOptionRow(
                                    name = formatEffortLabel(effort),
                                    contextWindow = null,
                                    reasoningEfforts = emptyList(),
                                    selected = selected,
                                    onClick = {
                                        val pair = currentOption
                                        if (pair != null) {
                                            onSelect(pair.first.provider, pair.second.id, effort)
                                            page = ModelPickerPage.MENU
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelMenuRow(
    title: String,
    value: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clip(RoundedCornerShape(DshRadius.lg))
            .background(if (pressed) Dsh.hover else Dsh.bgSelector)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, color = Dsh.labelPrimary, fontSize = 15.sp, fontWeight = FontWeight(500), lineHeight = 20.sp)
        Spacer(Modifier.weight(1f))
        Text(value, color = if (enabled) Dsh.labelTertiary else Dsh.labelCaption, fontSize = 14.sp, lineHeight = 20.sp)
        Spacer(Modifier.width(4.dp))
        Icon(ChevronRightOutline14, contentDescription = null, tint = Dsh.labelCaption, modifier = Modifier.size(16.dp))
    }
}

private fun formatEffortLabel(effort: String): String =
    effort.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString() }

internal fun friendlySelectModelError(raw: String?): String {
    val text = raw?.trim().orEmpty().ifBlank { return L.unknownError }
    val lower = text.lowercase()
    return when {
        "no adapter registered" in lower -> L.noAdapterRegistered
        "unsupported_reasoning" in lower || "does not support reasoning" in lower -> L.unsupportedReasoningEffort
        "model-unavailable" in lower -> L.modelUnavailable
        "调用" in text && "api" in lower -> L.modelApiFailed
        else -> text
    }
}

private fun modelChipLabel(catalog: MobileModelCatalog?): String {
    if (catalog == null) return L.selectModel
    val option = catalog.groups.asSequence()
        .flatMap { g -> g.models.asSequence() }
        .firstOrNull { it.id == catalog.currentModel }
    val name = option?.name ?: catalog.currentModel ?: L.selectModel
    val effort = catalog.currentReasoningEffort ?: option?.defaultEffort
    return if (effort.isNullOrBlank()) name else "$name ${formatEffortLabel(effort)}"
}

private fun providerAccent(provider: String): Color {
    val palettes = listOf(
        Color(0xFF4D88FF),
        Color(0xFF8B7CF6),
        Color(0xFF06B6D4),
        Color(0xFF22C55E),
        Color(0xFFF59E0B),
        Color(0xFFEC4899),
    )
    val idx = (provider.hashCode() and 0x7FFFFFFF) % palettes.size
    return palettes[idx]
}

@Composable
private fun ModelOptionRow(
    name: String,
    contextWindow: Long?,
    reasoningEfforts: List<String>,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(DshRadius.lg))
            .background(
                when {
                    selected -> Dsh.brand400.copy(alpha = 0.08f)
                    pressed -> Dsh.hover
                    else -> Color.Transparent
                }
            )
            .border(
                1.dp,
                if (selected) Dsh.brand400.copy(alpha = 0.28f) else Color.Transparent,
                RoundedCornerShape(DshRadius.lg)
            )
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                name,
                color = Dsh.labelPrimary,
                fontSize = 15.sp,
                fontWeight = if (selected) FontWeight(500) else FontWeight(400),
                lineHeight = 20.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val meta = buildList {
                if (contextWindow != null) add(formatTokens(contextWindow))
                if (reasoningEfforts.isNotEmpty()) {
                    add(reasoningEfforts.take(4).joinToString(" · ") { formatEffortLabel(it) })
                }
            }
            if (meta.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    meta.joinToString("  ·  "),
                    color = Dsh.labelTertiary,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (selected) {
            Spacer(Modifier.width(8.dp))
            Icon(CheckOutline16, contentDescription = null, tint = Dsh.brand400, modifier = Modifier.size(16.dp))
        }
    }
}

// ---------- 会话行（Rows.module.css：32dp 高，操作菜单：重命名/分叉） ----------

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun SessionRowItem(
    session: MobileSession,
    isSelected: Boolean,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onFork: () -> Unit,
    onArchive: () -> Unit = {},
    onDelete: () -> Unit = {},
    indent: androidx.compose.ui.unit.Dp = 0.dp,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val itemInteraction = remember { MutableInteractionSource() }
    val itemPressed by itemInteraction.collectIsPressedAsState()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = indent, end = 6.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(36.dp)
                .clip(RoundedCornerShape(DshRadius.md))
                .background(
                    when {
                        isSelected -> Dsh.bgNavActive
                        itemPressed -> Dsh.bgNavHover
                        else -> Color.Transparent
                    }
                )
                .combinedClickable(
                    interactionSource = itemInteraction,
                    indication = null,
                    onClick = onClick,
                    onLongClick = { menuOpen = true },
                )
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (session.running) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(Dsh.brand400),
                )
                Spacer(Modifier.width(8.dp))
            }
            Text(
                session.title,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                fontWeight = if (isSelected) FontWeight(500) else FontWeight(400),
                color = Dsh.labelPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (!session.blank) {
                Spacer(Modifier.width(8.dp))
                Text(
                    relativeTime(session.updatedAt),
                    color = Dsh.labelTertiary,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    maxLines = 1,
                )
            }
        }
        // 锚在行尾：菜单靠右弹出，避免贴侧栏左边
        Box(modifier = Modifier.align(Alignment.CenterEnd)) {
            DshMenu(
                expanded = menuOpen,
                onDismiss = { menuOpen = false },
                offset = androidx.compose.ui.unit.DpOffset(0.dp, 4.dp),
                items = listOf(
                    DshMenuItem(EditOutline16, L.rename) {
                        menuOpen = false
                        onRename()
                    },
                    DshMenuItem(BranchOutline16, L.forkSession) {
                        menuOpen = false
                        onFork()
                    },
                    DshMenuItem(ArchiveOutline20, L.archiveSession) {
                        menuOpen = false
                        onArchive()
                    },
                    DshMenuItem(TrashOutline16, L.deleteSession, danger = true) {
                        menuOpen = false
                        onDelete()
                    },
                ),
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
    offset: androidx.compose.ui.unit.DpOffset = androidx.compose.ui.unit.DpOffset.Zero,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        offset = offset,
        containerColor = Dsh.bgLayer1,
        shape = RoundedCornerShape(DshRadius.lg),
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
                        .clip(RoundedCornerShape(DshRadius.md))
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
            .clip(RoundedCornerShape(DshRadius.lg))
            .background(Dsh.bgCode)
            .border(1.dp, Dsh.borderL2, RoundedCornerShape(DshRadius.lg))
    ) {
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
                L.waitingApproval,
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
                        .clip(RoundedCornerShape(DshRadius.full))
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
                        L.reject,
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
                        .clip(RoundedCornerShape(DshRadius.full))
                        .background(if (answered) Dsh.brand400.copy(alpha = 0.4f) else Dsh.brand400)
                        .clickable(enabled = !answered) {
                            answered = true
                            msg.approvalId?.let { onAnswer(it, "allowed-once") }
                        }
                        .padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        if (answered) L.processed else L.allowOnce,
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
private fun ContextMeterButton(stats: MobileSessionStats, running: Boolean = false) {
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
        val fillColor = if (running) Dsh.labelCaption.copy(alpha = 0.55f) else Dsh.labelTertiary
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(if (pressed) Dsh.hover else Color.Transparent)
                .clickable(interactionSource = interaction, indication = null) { expanded = true },
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
            shape = RoundedCornerShape(DshRadius.lg)
        ) {
            Column(modifier = Modifier.width(240.dp).padding(12.dp)) {
                // header：上下文已用 + 百分比 + 用量数字
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(L.contextUsed, color = Dsh.labelTertiary, fontSize = 12.sp, lineHeight = 20.sp)
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
                ContextMeterRow(L.systemPrompt, formatTokens(stats.systemTokens), DshColorSystem)
                Spacer(Modifier.height(4.dp))
                ContextMeterRow(L.tools, formatTokens(stats.toolsTokens), DshColorTools)
                Spacer(Modifier.height(4.dp))
                ContextMeterRow(L.chatMessages, formatTokens(stats.messageTokens), Dsh.brand450)
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
                .clip(RoundedCornerShape(DshRadius.lg))
                .background(Dsh.bgLayer1)
                .border(1.dp, Dsh.borderL2, RoundedCornerShape(DshRadius.lg))
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
                            .clip(RoundedCornerShape(DshRadius.sm))
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

/** 导出会话为纯文本（分享用；无独立 log 下载 API 时的替代方案）。 */
private fun exportSessionTranscript(client: MobileApiClient, sessionId: String, title: String): String {
    val pages = mutableListOf<MobileMessage>()
    var before: Long? = null
    repeat(20) {
        val page = client.getSessionHistory(sessionId, beforeSeq = before, maxMessages = 80)
        pages.addAll(0, page.messages)
        before = page.nextBeforeSeq
        if (!page.hasMore || before == null) return@repeat
    }
    val body = pages.mapNotNull { msg ->
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
                .clip(RoundedCornerShape(DshRadius.lg))
                .background(Dsh.bgInput)
                .border(1.dp, if (searchPressed) Dsh.borderL3 else Dsh.borderL2, RoundedCornerShape(DshRadius.lg))
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
                        if (searchQuery.isEmpty()) Text(L.searchSessionsPlaceholder, color = Dsh.labelTertiary, fontSize = 14.sp)
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
                    contentDescription = L.clearSearch,
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
                    .clip(RoundedCornerShape(DshRadius.md))
                    .background(Dsh.bgLayer1)
                    .border(1.dp, Dsh.borderL2, RoundedCornerShape(DshRadius.md))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    L.searchFailedWithMessage.format(searchState.message),
                    color = Dsh.labelSecondary,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    modifier = Modifier.weight(1f)
                )
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(DshRadius.full))
                        .background(Dsh.brand400)
                        .clickable { onRetry() }
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text(L.retry, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight(500))
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        if (searchQuery.isNotBlank()) {
            // 搜索结果（DSH 服务器内容搜索）
            when (searchState) {
                is SearchUiState.Loading -> {
                    Text(
                        L.searching,
                        color = Dsh.labelTertiary,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(vertical = 20.dp)
                    )
                }
                is SearchUiState.Empty, is SearchUiState.Error -> {
                    // Empty / Error 状态互斥：error 优先渲染（已在顶部错误条给重试入口）
                    if (searchState is SearchUiState.Empty) {
                        Text(
                            L.noMatchingSessions,
                            color = Dsh.labelTertiary,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(vertical = 20.dp)
                        )
                    }
                }
                else -> {
                    if (searchResults.isEmpty() && searchState is SearchUiState.Idle) {
                        Text(
                            L.noMatchingSessions,
                            color = Dsh.labelTertiary,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(vertical = 20.dp)
                        )
                    } else {
                        // 标题 + bounded LazyColumn
                        Text(
                            L.searchResults,
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
                                        L.historyOverflow.format(HISTORY_LIST_LIMIT, searchResults.size),
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
                        L.noSessions,
                        color = Dsh.labelTertiary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight(500)
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        L.noSessionsHint,
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
                                L.historyOverflow.format(HISTORY_LIST_LIMIT, all.size),
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
            .clip(RoundedCornerShape(DshRadius.md))
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
            .clip(RoundedCornerShape(DshRadius.md))
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

// ---------- 轨迹视图（TrajectoryView：对标 DSH Web 彩色时间轴） ----------

private fun formatTraceDuration(ms: Long): String = when {
    ms >= 1000 -> String.format(Locale.US, "+%.1fs", ms / 1000.0)
    else -> "+${ms}ms"
}

private data class TraceRoleVisual(
    val label: String,
    val color: Color,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
)

@Composable
private fun traceRoleVisual(role: String): TraceRoleVisual = when (role) {
    "user" -> TraceRoleVisual(L.goalRole, Dsh.brand400, GoalOutline16)
    "reasoning" -> TraceRoleVisual(L.reasoningRole, Color(0xFF8B7CF6), ThinkOutline16)
    "tool_call" -> TraceRoleVisual(L.toolCallRole, Dsh.warn, CodeOutline16)
    "tool_result" -> TraceRoleVisual(L.executionResultRole, Dsh.success, CheckOutline16)
    "approval" -> TraceRoleVisual(L.approvalRole, Color(0xFFF97316), WarningOutline16)
    "todo" -> TraceRoleVisual(L.taskRole, Color(0xFF06B6D4), ChecklistOutline14)
    "compaction" -> TraceRoleVisual(L.compactionRole, Dsh.labelTertiary, ArchiveOutline20)
    else -> TraceRoleVisual(L.answerRole, Dsh.brand400, Sparkle16)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TrajectoryView(
    messages: List<MobileMessage>,
    stats: MobileSessionStats?,
    running: Boolean,
    elapsedSec: Long,
    modifier: Modifier = Modifier,
) {
    var roleFilter by remember { mutableStateOf<String?>(null) }
    val filterOptions = remember(messages) {
        listOf(null to L.allSessions) + messages.map { it.role }.distinct()
            .map { role ->
                role to when (role) {
                    "user" -> L.goalRole
                    "assistant" -> L.answerRole
                    "reasoning" -> L.reasoningRole
                    "tool_call" -> L.tools
                    "tool_result" -> L.resultRole
                    "approval" -> L.approvalRole
                    "todo" -> L.taskRole
                    "compaction" -> L.compactionRole
                    else -> role
                }
            }
    }
    val filtered = remember(messages, roleFilter) {
        if (roleFilter == null) messages else messages.filter { it.role == roleFilter }
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        if (messages.isNotEmpty()) {
            // 自动换行，避免横向滑动与系统切应用手势冲突
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                filterOptions.forEach { (id, label) ->
                    val selected = roleFilter == id
                    Text(
                        label,
                        color = if (selected) Dsh.labelPrimary else Dsh.labelSecondary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight(500),
                        modifier = Modifier
                            .clip(RoundedCornerShape(DshRadius.full))
                            .background(if (selected) Dsh.bgNavActive else Dsh.bgLayer1)
                            .clickable { roleFilter = id }
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
        }
        if (filtered.isEmpty()) {
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
                Text(L.noTrace, color = Dsh.labelPrimary, fontSize = 15.sp, fontWeight = FontWeight(500))
                Spacer(Modifier.height(6.dp))
                Text(
                    if (messages.isEmpty()) {
                        L.noTraceEmpty
                    } else {
                        L.noTraceFilterEmpty
                    },
                    color = Dsh.labelTertiary,
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 24.dp)
                )
            }
            return@Column
        }

        stats?.takeIf { it.turns > 0 || it.steps > 0 }?.let { s ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                DshTag(
                    text = L.turnsCount.format(s.turns),
                    color = Dsh.brand400.copy(alpha = 0.12f),
                    contentColor = Dsh.brand400,
                    shape = RoundedCornerShape(DshRadius.full),
                )
                DshTag(
                    text = L.stepsCount.format(s.steps),
                    color = Color(0xFF8B7CF6).copy(alpha = 0.12f),
                    contentColor = Color(0xFF8B7CF6),
                    shape = RoundedCornerShape(DshRadius.full),
                )
                if (s.outputTokens > 0) {
                    DshTag(
                        text = "${formatTokens(s.outputTokens)} out",
                        color = Dsh.success.copy(alpha = 0.12f),
                        contentColor = Dsh.success,
                        shape = RoundedCornerShape(DshRadius.full),
                    )
                }
            }
        }

        if (running) {
            Spacer(Modifier.height(4.dp))
            TurnStatusRow(elapsedSec)
            Spacer(Modifier.height(14.dp))
        }
        filtered.forEachIndexed { index, msg ->
            val nextTime = filtered.getOrNull(index + 1)?.time ?: 0L
            val durationMs = if (msg.time > 0 && nextTime > msg.time) nextTime - msg.time else null
            TraceStepRow(
                number = index + 1,
                total = filtered.size,
                msg = msg,
                durationMs = durationMs,
                running = running && index == filtered.lastIndex,
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
    val visual = traceRoleVisual(msg.role)
    val accent = visual.color
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
    ) {
        Column(
            modifier = Modifier.width(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(if (running) accent else accent.copy(alpha = 0.16f))
                    .border(1.5.dp, accent, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "$number",
                    color = if (running) Color.White else accent,
                    fontSize = 10.sp,
                    lineHeight = 12.sp,
                    fontWeight = FontWeight(600)
                )
            }
            if (number < total) {
                Box(
                    modifier = Modifier
                        .width(2.dp)
                        .weight(1f)
                        .background(accent.copy(alpha = 0.28f))
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(bottom = 14.dp)
                .clip(RoundedCornerShape(DshRadius.lg))
                .background(accent.copy(alpha = 0.06f))
                .border(1.dp, accent.copy(alpha = 0.16f), RoundedCornerShape(DshRadius.lg))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .background(accent)
            )
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                TraceStepHeader(msg = msg, durationMs = durationMs, visual = visual)
                Spacer(Modifier.height(8.dp))
                TraceStepBody(msg = msg, running = running, accent = accent)
            }
        }
    }
}

@Composable
private fun TraceStepHeader(msg: MobileMessage, durationMs: Long?, visual: TraceRoleVisual) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(visual.icon, contentDescription = null, tint = visual.color, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(6.dp))
        DshTag(
            text = visual.label,
            color = visual.color.copy(alpha = 0.14f),
            contentColor = visual.color,
            shape = RoundedCornerShape(DshRadius.full),
            contentDescription = L.stepType.format(visual.label),
        )
        if (msg.role == "tool_call") {
            Spacer(Modifier.width(8.dp))
            Text(
                msg.toolName ?: L.toolCallRole,
                color = Dsh.labelPrimary,
                fontSize = 13.sp,
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
                color = visual.color.copy(alpha = 0.8f),
                fontSize = 11.sp,
                lineHeight = 16.sp,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

@Composable
private fun TraceStepBody(msg: MobileMessage, running: Boolean, accent: Color) {
    when (msg.role) {
        "tool_call" -> TraceCodeBlock(msg.toolArgs ?: msg.text, running, accent)
        "tool_result" -> TraceCodeBlock(msg.text, running, accent)
        "reasoning" -> TraceExpandableText(msg.text, maxLines = 4)
        "todo" -> {
            val done = msg.todos.count { it.status == "completed" }
            Text(
                if (msg.todos.isNotEmpty()) L.todoUpdate.format(done, msg.todos.size) else L.todoListUpdated,
                color = Dsh.labelSecondary,
                fontSize = 13.sp,
                lineHeight = 20.sp
            )
        }
        "compaction" -> Text(
            if (msg.running == true) L.compressing else msg.text.lineSequence().firstOrNull().orEmpty().ifBlank { L.contextCompressed },
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
private fun TraceCodeBlock(text: String, running: Boolean, accent: Color) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(DshRadius.md))
            .background(Dsh.bgCode)
            .border(
                1.dp,
                if (running) accent.copy(alpha = 0.55f) else accent.copy(alpha = 0.18f),
                RoundedCornerShape(DshRadius.md)
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
                if (expanded) L.collapse else L.expand,
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

// ---------- 输入区顶栏（工作区 + Harness 模式，对标 web composer meta row） ----------

@Composable
private fun ComposerTopRow(
    sessions: List<MobileSession>,
    deletedWorkspaces: Set<String> = emptySet(),
    registeredPaths: List<String> = emptyList(),
    currentCwd: String?,
    lastCwd: String?,
    harnessLabel: String,
    showHarness: Boolean = true,
    workspaceEditable: Boolean = true,
    harnessEditable: Boolean = true,
    onOpenHarnessPicker: (() -> Unit)? = null,
    onStartSession: (String?) -> Unit,
) {
    val workspaces = remember(sessions, deletedWorkspaces, registeredPaths) {
        visibleUserWorkspaces(
            sessionCwds = sessions.map { it.cwd },
            deletedWorkspaces = deletedWorkspaces,
            registeredPaths = registeredPaths,
        )
    }
    var showPicker by remember { mutableStateOf(false) }
    var pickedCwd by remember(deletedWorkspaces) {
        mutableStateOf<String?>(null)
    }
    // 已删工作区不再作为展示/候选
    val safePicked = pickedCwd?.takeUnless { it in deletedWorkspaces }
    val displayCwd = safePicked ?: currentCwd ?: lastCwd?.takeIf { it in workspaces }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = COMPOSER_SIDE_CLEARANCE, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = COMPOSER_MAX_WIDTH)
                .heightIn(min = 32.dp)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(DshRadius.md))
                    .then(
                        if (workspaceEditable) {
                            Modifier.clickable { showPicker = true }
                        } else {
                            Modifier
                        },
                    )
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    FolderOpenOutline16,
                    contentDescription = null,
                    tint = if (workspaceEditable) Dsh.labelPrimary else Dsh.labelSecondary,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    displayCwd?.substringAfterLast('/') ?: if (workspaceEditable) L.selectWorkspaceShort else L.unboundWorkspace,
                    color = if (workspaceEditable) Dsh.labelPrimary else Dsh.labelSecondary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight(500),
                    lineHeight = 20.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (workspaceEditable) {
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        ChevronDownOutline14,
                        contentDescription = null,
                        tint = Dsh.labelCaption,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }

            if (showHarness && harnessLabel.isNotBlank()) {
                val harnessInteraction = remember { MutableInteractionSource() }
                val harnessPressed by harnessInteraction.collectIsPressedAsState()
                val harnessBg = when {
                    harnessPressed -> Dsh.hover
                    else -> Color.Transparent
                }
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(DshRadius.md))
                        .background(harnessBg)
                        .then(
                            if (harnessEditable && onOpenHarnessPicker != null) {
                                Modifier.clickable(
                                    interactionSource = harnessInteraction,
                                    indication = null,
                                    onClick = onOpenHarnessPicker,
                                )
                            } else {
                                Modifier
                            },
                        )
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        AgentPresetOutline16,
                        contentDescription = null,
                        tint = if (harnessEditable) Dsh.labelPrimary else Dsh.labelSecondary,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        harnessLabel,
                        color = if (harnessEditable) Dsh.labelPrimary else Dsh.labelSecondary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight(500),
                        lineHeight = 20.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (harnessEditable && onOpenHarnessPicker != null) {
                        Spacer(Modifier.width(4.dp))
                        Icon(
                            ChevronDownOutline14,
                            contentDescription = null,
                            tint = Dsh.labelCaption,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
            }
        }
    }

    if (showPicker) {
        WorkspacePickerSheet(
            sessions = sessions,
            deletedWorkspaces = deletedWorkspaces,
            registeredPaths = registeredPaths,
            selectedPath = displayCwd,
            onDismiss = { showPicker = false },
            onPick = { cwd ->
                showPicker = false
                pickedCwd = cwd
                onStartSession(cwd)
            },
        )
    }
}

// ---------- Agent 预设选择（新会话 compose 阶段） ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AgentPresetPickerSheet(
    presets: List<MobileAgentPreset>,
    currentId: String,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Dsh.bgLayer1,
        contentColor = Dsh.labelPrimary,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        dragHandle = null,
        modifier = Modifier.fillMaxWidth(),
    ) {
        val selectedPreset = presets.firstOrNull { it.id == currentId }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
        ) {
            SheetGrabber()
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    selectedPreset?.name?.ifBlank { L.defaultHarnessPreset } ?: L.defaultHarnessPreset,
                    color = Dsh.labelPrimary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight(600),
                    lineHeight = 24.sp,
                    modifier = Modifier.padding(end = 8.dp),
                )
                if (selectedPreset != null) {
                    Icon(
                        CheckOutline14,
                        contentDescription = null,
                        tint = Dsh.brand400,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                L.chooseAgentPresetDesc,
                color = Dsh.labelTertiary,
                fontSize = 13.sp,
                lineHeight = 18.sp
            )
            Spacer(Modifier.height(14.dp))
            if (presets.isEmpty()) {
                Text(L.loadingPresets, color = Dsh.labelTertiary, fontSize = 13.sp)
            } else {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 440.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(presets, key = { it.id }) { preset ->
                        val selected = preset.id == currentId
                        val title = preset.name.ifBlank { preset.id }
                        val desc = preset.description.ifBlank { "" }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(DshRadius.md))
                                .background(if (selected) Dsh.bgNavActive else Color.Transparent)
                                .clickable { onSelect(preset.id) }
                                .padding(horizontal = 12.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(
                                    title,
                                    color = Dsh.labelPrimary,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight(500),
                                    lineHeight = 20.sp,
                                )
                                if (desc.isNotBlank()) {
                                    Text(
                                        desc,
                                        color = Dsh.labelTertiary,
                                        fontSize = 12.sp,
                                        lineHeight = 17.sp,
                                        maxLines = 3,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            if (selected) {
                                Spacer(Modifier.width(10.dp))
                                Icon(
                                    CheckOutline14,
                                    contentDescription = null,
                                    tint = Dsh.brand400,
                                    modifier = Modifier
                                        .padding(top = 2.dp)
                                        .size(16.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------- 权限选择弹层（WI-004：真实写入服务端 permission.defaultPreset） ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PermissionPickerSheet(
    context: android.content.Context,
    host: Host,
    currentPreset: String,
    sessionId: String? = null,
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
                if (sessionId != null) {
                    MobileApiClient(host).setSessionPermission(sessionId, preset)
                    withContext(Dispatchers.Main) {
                        saving = false
                        Toast.makeText(context, L.currentSessionPermissionUpdated, Toast.LENGTH_SHORT).show()
                        onDismiss()
                    }
                } else {
                    AppSettingsStore.save(host, context, "permission", org.json.JSONObject().put("defaultPreset", preset))
                    withContext(Dispatchers.Main) {
                        saving = false
                        onSaved(AppSettingsStore.cached(context))
                        onDismiss()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    saving = false
                    error = e.message ?: L.saveFailed
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
                .navigationBarsPadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp)
        ) {
            SheetGrabber()
            Text(L.accessMode, color = Dsh.labelPrimary, fontSize = 18.sp, fontWeight = FontWeight(600), lineHeight = 24.sp)
            Spacer(Modifier.height(4.dp))
            Text(
                if (sessionId != null) L.currentSessionPermissionDesc else L.defaultPermissionDesc,
                color = Dsh.labelTertiary,
                fontSize = 13.sp,
                lineHeight = 18.sp,
            )
            Spacer(Modifier.height(14.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PermissionModeOption(
                    title = L.permReadOnly,
                    desc = L.readOnlyPermissionDesc,
                    accent = Dsh.labelSecondary,
                    icon = BrowseOutline16,
                    selected = selected == "read-only",
                    enabled = !saving,
                    onClick = {
                        selected = "read-only"
                        apply("read-only")
                    }
                )
                PermissionModeOption(
                    title = L.permWorkspaceWrite,
                    desc = L.workspaceWritePermissionDesc,
                    accent = Dsh.brand400,
                    icon = FolderOpenOutline16,
                    selected = selected == "workspace-write",
                    enabled = !saving,
                    onClick = {
                        selected = "workspace-write"
                        apply("workspace-write")
                    }
                )
                PermissionModeOption(
                    title = L.permFullAccess,
                    desc = L.fullAccessPermissionDesc,
                    accent = Dsh.warn,
                    icon = WarningOutline16,
                    selected = selected == "danger-full-access",
                    enabled = !saving,
                    onClick = {
                        selected = "danger-full-access"
                        showFullAccessConfirm = true
                    }
                )
            }
            if (saving) {
                Spacer(Modifier.height(10.dp))
                Text(L.saving, color = Dsh.labelTertiary, fontSize = 12.sp)
            }
            if (error != null) {
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(L.saveFailedWithMessage.format(error), color = Dsh.error, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(7.dp))
                            .background(Dsh.bgSelector)
                            .clickable(enabled = !saving) { apply(selected) }
                            .padding(horizontal = 12.dp, vertical = 5.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(L.retry, color = Dsh.brand400, fontSize = 12.sp, fontWeight = FontWeight(500))
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
                        .clip(RoundedCornerShape(DshRadius.lg))
                        .background(Dsh.bgLayer1)
                        .border(1.dp, Dsh.borderL2, RoundedCornerShape(DshRadius.lg))
                        .padding(18.dp)
                ) {
                    Text(L.confirmFullAccessTitle, color = Dsh.labelPrimary, fontSize = 15.sp, fontWeight = FontWeight(500), lineHeight = 21.sp)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        L.confirmFullAccessMessage,
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
                            Text(L.cancel, color = Dsh.labelSecondary, fontSize = 12.sp, fontWeight = FontWeight(500))
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
                            Text(L.enableFullAccess, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight(500))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PermissionModeOption(
    title: String,
    desc: String,
    accent: Color,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(DshRadius.lg))
            .background(
                when {
                    selected -> accent.copy(alpha = 0.08f)
                    pressed -> Dsh.hover
                    else -> Dsh.bgSelector
                }
            )
            .border(
                1.dp,
                if (selected) accent.copy(alpha = 0.4f) else Dsh.borderL1,
                RoundedCornerShape(DshRadius.lg)
            )
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(DshRadius.md))
                .background(accent.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                color = Dsh.labelPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight(500),
                lineHeight = 20.sp
            )
            Spacer(Modifier.height(2.dp))
            Text(desc, color = Dsh.labelTertiary, fontSize = 12.sp, lineHeight = 16.sp)
        }
        Spacer(Modifier.width(10.dp))
        Box(
            modifier = Modifier
                .size(20.dp)
                .border(1.5.dp, if (selected) accent else Dsh.borderL3, CircleShape)
                .padding(4.dp),
            contentAlignment = Alignment.Center
        ) {
            if (selected) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(CircleShape)
                        .background(accent)
                )
            }
        }
    }
}

// ---------- 发送队列 dock（DSH QueueDock：输入卡上方，发送中显示） ----------

@Composable
private fun QueueDock(label: String = L.sending, badge: String = L.processing) {
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
                label,
                color = Dsh.labelSecondary,
                fontSize = 12.sp,
                lineHeight = 18.sp
            )
            Spacer(Modifier.weight(1f))
            Text(
                badge,
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
    onModeChange: (HeroMode) -> Unit = {},
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
        // 输入卡主体
        var composerFocused by remember { mutableStateOf(false) }
        val composerBorderColor by animateColorAsState(
            targetValue = if (composerFocused) Dsh.brand400 else Dsh.borderL2,
            animationSpec = tween(DshDuration.normal),
            label = "composerBorder"
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = COMPOSER_MAX_WIDTH)
                .clip(RoundedCornerShape(DshRadius.lg))
                .background(Dsh.bgInput)
                .border(1.dp, composerBorderColor, RoundedCornerShape(DshRadius.lg))
                .padding(top = 6.dp)
                .onFocusChanged { composerFocused = it.hasFocus }
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
                        val preview = remember(data) { android.util.Base64.decode(data, android.util.Base64.DEFAULT) }
                        Box {
                            coil.compose.AsyncImage(
                                model = coil.request.ImageRequest.Builder(androidx.compose.ui.platform.LocalContext.current)
                                    .data(preview)
                                    .build(),
                                contentDescription = L.pendingImage,
                                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                modifier = Modifier
                                    .size(56.dp)
                                    .clip(RoundedCornerShape(DshRadius.md))
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
                                    contentDescription = L.removeImage,
                                    tint = Dsh.labelSecondary,
                                    modifier = Modifier.size(12.dp)
                                )
                            }
                        }
                    }
                }
            }
            BasicTextField(
                value = inputText,
                onValueChange = onInputChange,
                keyboardOptions = KeyboardOptions(imeAction = if (canSend) ImeAction.Send else ImeAction.Default),
                keyboardActions = KeyboardActions(onSend = { if (canSend) onSend() }),
                textStyle = TextStyle(
                    color = Dsh.labelPrimary,
                    fontSize = 15.sp,
                    lineHeight = 23.sp,
                    letterSpacing = (-0.1).sp,
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
                                    isListening -> L.listening
                                    heroMode == HeroMode.PLAN -> L.planPlaceholder
                                    heroMode == HeroMode.GOAL -> L.goalPlaceholder
                                    else -> L.chatPlaceholder
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




            // 底部工具行：左侧控件可压缩，发送键固定在最右，永不被挤出
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 8.dp, end = 8.dp, top = 2.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 左侧：+ 按钮（DSH input add：图片/附件）
                    if (!running) {
                        RoundIconButton(
                            icon = PlusOutline16,
                            tint = Dsh.labelPrimary,
                            contentDescription = L.addAttachment,
                            onClick = onPickImage
                        )
                    }

                    // 访问模式（DSH input.accessMode）
                    if (!running) {
                        val permInteraction = remember { MutableInteractionSource() }
                        val permPressed by permInteraction.collectIsPressedAsState()
                        Row(
                            modifier = Modifier
                                .height(28.dp)
                                .clip(RoundedCornerShape(DshRadius.full))
                                .background(if (permPressed) Dsh.hover else Color.Transparent)
                                .clickable(interactionSource = permInteraction, indication = null, onClick = onOpenPermissionPicker)
                                .padding(start = 6.dp, end = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                WarningOutline16,
                                contentDescription = null,
                                tint = Dsh.labelSecondary,
                                modifier = Modifier.size(11.dp)
                            )
                            Spacer(Modifier.width(3.dp))
                            Text(
                                permissionLabel,
                                color = Dsh.labelSecondary,
                                fontSize = 10.sp,
                                fontWeight = FontWeight(500),
                                lineHeight = 14.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Icon(
                                ChevronDownOutline14,
                                contentDescription = null,
                                tint = Dsh.labelCaption,
                                modifier = Modifier.size(12.dp)
                            )
                        }
                    }

                    if (running) {
                        val stopInteraction = remember { MutableInteractionSource() }
                        val stopPressed by stopInteraction.collectIsPressedAsState()
                        val stopBg = if (stopPressed)
                            if (Dsh.isDark) Dsh.brand400.copy(alpha = 0.28f) else Dsh.brand500.copy(alpha = 0.2f)
                        else
                            if (Dsh.isDark) Dsh.brand400.copy(alpha = 0.18f) else Dsh.brand500.copy(alpha = 0.12f)
                        Box(
                            modifier = Modifier
                                .size(26.dp)
                                .clip(CircleShape)
                                .background(stopBg)
                                .clickable(interactionSource = stopInteraction, indication = null, onClick = onStop),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                StopFill16,
                                contentDescription = L.stopGenerating,
                                tint = Dsh.labelPrimary,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }

                    Spacer(Modifier.weight(1f))

                    // 模型选择：可收缩省略，不得挤掉发送键
                    if (!running) {
                        val modelInteraction = remember { MutableInteractionSource() }
                        val modelPressed by modelInteraction.collectIsPressedAsState()
                        Row(
                            modifier = Modifier
                                .height(28.dp)
                                .widthIn(max = 132.dp)
                                .clip(RoundedCornerShape(DshRadius.xl))
                                .background(if (modelPressed) Dsh.hover else Color.Transparent)
                                .clickable(interactionSource = modelInteraction, indication = null, onClick = onOpenModelPicker)
                                .padding(start = 6.dp, end = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                currentModel ?: L.selectModel,
                                color = Dsh.labelSecondary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight(500),
                                lineHeight = 16.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            Icon(
                                ChevronDownOutline14,
                                contentDescription = null,
                                tint = Dsh.labelCaption,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                        Spacer(Modifier.width(6.dp))
                    }

                    // 上下文计量
                    if (sessionStats != null && sessionStats.contextWindow > 0) {
                        ContextMeterButton(stats = sessionStats, running = running)
                        Spacer(Modifier.width(6.dp))
                    }
                }

                // 发送键：固定在行尾，始终可见
                val sendInteraction = remember { MutableInteractionSource() }
                val sendPressed by sendInteraction.collectIsPressedAsState()
                val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
                val sendBg by animateColorAsState(
                    targetValue = when {
                        !canSend && !isSending -> Dsh.brand500.copy(alpha = 0.55f)
                        sendPressed -> Dsh.brand400
                        else -> Dsh.brand500
                    },
                    animationSpec = tween(motionDuration(120)),
                    label = "sendBg"
                )
                val sendScale by animateFloatAsState(
                    targetValue = if (sendPressed) 0.88f else 1f,
                    animationSpec = tween(DshDuration.fast),
                    label = "sendScale"
                )
                Box(
                    modifier = Modifier
                        .padding(start = 4.dp)
                        .size(28.dp)
                        .graphicsLayer(scaleX = sendScale, scaleY = sendScale)
                        .clip(CircleShape)
                        .background(sendBg)
                        .clickable(
                            interactionSource = sendInteraction,
                            indication = null,
                            enabled = canSend && !isSending,
                            onClick = {
                                haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                                onSend()
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (isSending) {
                        val angle = rememberMotionSpin(750, label = "spin")
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .rotate(angle ?: 0f)
                                .border(1.5.dp, Color.White, CircleShape)
                        )
                    } else {
                        Icon(
                            SendOutline16,
                            contentDescription = L.sendMessage,
                            tint = Color.White,
                            modifier = Modifier.size(14.dp)
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
            .size(22.dp)
            .clip(CircleShape)
            .background(
                if (pressed)
                    if (Dsh.isDark) Dsh.brand400.copy(alpha = 0.18f) else Dsh.brand500.copy(alpha = 0.12f)
                else Dsh.bgSelector
            )
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = contentDescription, tint = tint, modifier = Modifier.size(14.dp))
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
                .clip(RoundedCornerShape(DshRadius.lg))
                .background(Dsh.bgCode)
                .border(1.dp, Dsh.borderL1, RoundedCornerShape(DshRadius.lg))
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
                    L.toolCallCount.format(group.items.size),
                    color = Dsh.labelSecondary,
                    fontSize = 14.sp,
                    lineHeight = 24.sp,
                    fontWeight = FontWeight(400),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (groupRunning) {
                    Text(L.executing, color = Dsh.labelTertiary, fontSize = 12.sp, lineHeight = 18.sp)
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
    onRegenerate: (() -> Unit)? = null,
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
        when {
            msg.role == "context_injection" || isContextInjectionText(msg.text) -> ContextInjectionRow(msg.text)
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
            msg.role == "reasoning" -> ReasoningRow(msg.text, running)
            msg.role == "approval" -> ApprovalCard(
                msg = msg,
                onAnswer = { approvalId, outcome ->
                    onAnswerApproval?.invoke(approvalId, outcome)
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
                    // 助手消息底部：复制 / 分叉 / 重新生成
                    if (msg.role == "assistant") {
                        Spacer(Modifier.height(8.dp))
                        MessageActionRow(
                            onCopy = onCopy,
                            onFork = onFork,
                            onRegenerate = onRegenerate,
                        )
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
                    onCopy()
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
            }
        )
    }
}

/** 消息底栏：复制 / 分叉 / 重新生成（按需显示） */
@Composable
private fun MessageActionRow(
    onCopy: () -> Unit,
    onFork: (() -> Unit)? = null,
    onRegenerate: (() -> Unit)? = null,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MessageActionIcon(
            icon = CopyOutline16,
            contentDescription = L.copy,
            onClick = onCopy,
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
            .size(32.dp)
            .clip(RoundedCornerShape(DshRadius.sm))
            .background(if (pressed) Dsh.hover else Color.Transparent)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = Dsh.labelSecondary,
            modifier = Modifier.size(16.dp),
        )
    }
}

/** 降级判定：无文本内容的消息（多为 dsh 新增的结构化类型）渲染为可折叠原始 JSON。 */
fun isRawFallback(msg: MobileMessage): Boolean = msg.text.isBlank()

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
            .background(Dsh.bgLayer1)
            .border(1.dp, Dsh.borderL1, RoundedCornerShape(DshRadius.md))
            .padding(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                if (expanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowRight,
                contentDescription = null,
                tint = Dsh.labelTertiary,
                modifier = Modifier.size(14.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                L.unsupportedMessageType.format(msg.role),
                color = Dsh.labelSecondary,
                fontSize = 13.sp,
                lineHeight = 20.sp
            )
        }
        if (expanded) {
            Spacer(Modifier.height(6.dp))
            Text(
                detail,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                lineHeight = 16.sp,
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
            .clip(RoundedCornerShape(DshRadius.sm))
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
            if (running) L.compressing else L.contextCompressed,
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

// 上下文注入行（对标 Web：上下文注入 · skill-catalog，默认折叠）
@Composable
private fun ContextInjectionRow(text: String) {
    var expanded by remember { mutableStateOf(false) }
    val sources = remember(text) { contextInjectionLabels(text) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(DshRadius.md))
            .clickable { expanded = !expanded }
            .padding(horizontal = 4.dp, vertical = 4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.Description,
                contentDescription = null,
                tint = Dsh.labelCaption,
                modifier = Modifier.size(13.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                L.contextInjection,
                color = Dsh.labelTertiary,
                fontSize = 12.sp,
                fontWeight = FontWeight(500),
                lineHeight = 18.sp
            )
            Text(
                " · ",
                color = Dsh.labelCaption,
                fontSize = 12.sp,
                lineHeight = 18.sp
            )
            Text(
                sources.joinToString(", "),
                color = Dsh.labelCaption,
                fontSize = 12.sp,
                lineHeight = 18.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Icon(
                if (expanded) ChevronUpOutline14 else ChevronDownOutline14,
                contentDescription = if (expanded) L.collapseInjectionContent else L.expandInjectionContent,
                tint = Dsh.labelCaption,
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
                fontSize = 12.sp,
                lineHeight = 18.sp,
                maxLines = 16,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(top = 6.dp)
                    .clip(RoundedCornerShape(DshRadius.md))
                    .background(Dsh.bgLayer3.copy(alpha = 0.6f))
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
            .border(1.dp, Dsh.borderL1, RoundedCornerShape(DshRadius.lg))
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
                L.tasks,
                color = Dsh.labelPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight(500),
                lineHeight = 24.sp
            )
            Spacer(Modifier.width(10.dp))
            Text(
                L.todoCompleted.format(done, todos.size),
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
            text = if (loading) L.loadHistory else L.loadOlder,
            color = Dsh.labelSecondary,
            fontSize = 12.sp,
            lineHeight = 18.sp,
            modifier = Modifier
                .clip(RoundedCornerShape(DshRadius.lg))
                .background(Dsh.bgInput)
                .clickable(enabled = !loading, interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
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
private fun StoppedBadge(reason: String) {
    val label = stoppedReasonLabel(reason)
    Row(modifier = Modifier.fillMaxWidth()) {
        DshTag(
            text = label,
            color = Dsh.hover,
            contentColor = Dsh.labelTertiary,
            contentDescription = label,
        )
    }
}

// 思考行（ReasoningRow：chevron + 标题 + 2px 圆点 + 摘要，展开显示全文）
@Composable
private fun ReasoningRow(text: String, running: Boolean = false) {
    var expanded by remember { mutableStateOf(false) }
    LaunchedEffect(running) {
        if (running) expanded = true
    }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(DshRadius.sm))
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
                L.thinking,
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

/** 相对时间（Web 侧栏：刚刚 / 3分钟 / 3小时 / 2天） */
private fun relativeTime(timestamp: Long): String {
    if (timestamp <= 0) return ""
    val diff = System.currentTimeMillis() - timestamp
    return when {
        diff < 60_000 -> L.justNowShort
        diff < 3_600_000 -> L.minutesShort.format(diff / 60_000)
        diff < 86_400_000 -> L.hoursShort.format(diff / 3_600_000)
        else -> L.daysShort.format(diff / 86_400_000)
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
                text,
                color = if (Dsh.isDark) Dsh.labelPrimary else Color(0xFF0F1115),
                fontSize = 15.sp,
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
private fun MarkdownContent(text: String, streaming: Boolean = false) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val blocks = remember(text) { splitMarkdownBlocks(text) }
    val cursorAlpha = rememberInfiniteTransition(label = "streamCursor").animateFloat(
        initialValue = 0.25f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(animation = tween(480), repeatMode = RepeatMode.Reverse),
        label = "cursorAlpha",
    ).value
    blocks.forEachIndexed { index, block ->
        val isLastBlock = index == blocks.lastIndex
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
                        .clip(RoundedCornerShape(DshRadius.md))
                        .background(Dsh.bgLayer1)
                        .border(1.dp, Dsh.borderL1, RoundedCornerShape(DshRadius.md))
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
                val imageUrl = MarkdownMedia.takeIfSafe(block.content)
                if (imageUrl != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(DshRadius.lg))
                            .background(Dsh.bgLayer1)
                            .clickable {
                                val uri = android.net.Uri.parse(imageUrl)
                                runCatching {
                                    context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, uri))
                                }
                            }
                    ) {
                        coil.compose.AsyncImage(
                            model = imageUrl,
                            contentDescription = null,
                            contentScale = androidx.compose.ui.layout.ContentScale.FillWidth,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
            MarkdownBlockType.MATH -> LatexDisplayBlock(block.content)
            MarkdownBlockType.EMPTY -> {}
            else -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top,
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        InlineMarkdownText(block.content)
                    }
                    if (streaming && isLastBlock) {
                        Spacer(Modifier.width(2.dp))
                        Box(
                            modifier = Modifier
                                .padding(top = 6.dp)
                                .size(width = 2.dp, height = 16.dp)
                                .background(Dsh.brand400.copy(alpha = cursorAlpha))
                        )
                    }
                }
            }
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
            .clip(RoundedCornerShape(DshRadius.lg))
            .background(Dsh.bgCode)
            .border(1.dp, Dsh.borderL1, RoundedCornerShape(DshRadius.lg))
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
                    .clip(RoundedCornerShape(DshRadius.sm))
                    .background(if (copyPressed) Dsh.hover else Color.Transparent)
                    .clickable(interactionSource = copyInteraction, indication = null) {
                        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("code", content.trimEnd()))
                        Toast.makeText(context, L.copied, Toast.LENGTH_SHORT).show()
                    }
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    CopyOutline16,
                    contentDescription = L.copy,
                    tint = Dsh.labelTertiary,
                    modifier = Modifier.size(12.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text(L.copy, color = Dsh.labelTertiary, fontSize = 11.sp, lineHeight = 16.sp)
            }
        }
        val dark = Dsh.isDark
        val highlighted = remember(content, lang, dark) { highlightCode(content.trimEnd(), lang, dark) }
        val codeScroll = rememberScrollState()
        Text(
            highlighted,
            color = Dsh.labelPrimary,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            lineHeight = 20.sp,
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(codeScroll)
                .padding(horizontal = 16.dp, vertical = 10.dp)
        )
    }
}

private val TABLE_SEP_CELL = Regex("^:?-{2,}:?$")

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
                    val url = trimmed.substring(urlStart + 1, urlEnd).trim()
                    if (MarkdownMedia.isSafeImageUrl(url)) {
                        blocks.add(MarkdownBlock(MarkdownBlockType.IMAGE, url))
                    } else {
                        blocks.add(MarkdownBlock(MarkdownBlockType.PARAGRAPH, line))
                    }
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
                    if (!cells.all { it.matches(TABLE_SEP_CELL) }) {
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
            .clip(RoundedCornerShape(DshRadius.lg)),
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
private fun CommandCard(title: String, body: String?, running: Boolean = false, runningLabel: String = L.executing) {
    var expanded by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(DshRadius.lg))
            .background(Dsh.bgCode)
            .border(1.dp, Dsh.borderL2, RoundedCornerShape(DshRadius.lg))
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
                "DSH Links",
                color = Dsh.labelPrimary,
                fontSize = 26.sp,
                fontWeight = FontWeight(500),
                lineHeight = 32.sp
            )
            Spacer(Modifier.height(12.dp))
            Text(L.noWorkbenchConnected, color = Dsh.labelTertiary, fontSize = 12.sp, lineHeight = 18.sp)
            Spacer(Modifier.height(28.dp))
            Box(
                modifier = Modifier
                    .height(44.dp)
                    .clip(RoundedCornerShape(DshRadius.full))
                    .background(Dsh.brand500)
                    .clickable(onClick = onScan)
                    .padding(horizontal = 24.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(L.goToDeviceList, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight(500))
            }
        }
    }
}
