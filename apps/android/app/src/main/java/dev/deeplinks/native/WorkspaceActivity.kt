package dev.deeplinks.native
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import dev.deeplinks.core.Dsh
import dev.deeplinks.core.Host
import dev.deeplinks.core.L
import dev.deeplinks.core.deriveDshLayout
import dev.deeplinks.native.MobileSession
import dev.deeplinks.native.MobileMessage
import dev.deeplinks.core.DshNotifier
import dev.deeplinks.core.DshTheme
import dev.deeplinks.core.HostStore
import dev.deeplinks.core.stableIdentity
import dev.deeplinks.native.ui.DshSheetGrabber
import androidx.activity.ComponentActivity
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.*
import dev.deeplinks.core.DshType
import dev.deeplinks.native.ui.DshBanner
import dev.deeplinks.native.ui.ChatLoadingSkeleton
import dev.deeplinks.native.util.MessageGroup
import dev.deeplinks.native.util.groupMessages
import dev.deeplinks.native.util.userTurnJumps
import dev.deeplinks.native.util.isContextInjectionText
import dev.deeplinks.native.util.optNullableString
import dev.deeplinks.native.util.parseStoppedReason
import dev.deeplinks.native.util.selectShareTurns
import dev.deeplinks.native.util.WorkspaceAccount
import dev.deeplinks.native.util.normalizeWorkspacePath
import dev.deeplinks.native.util.reconcileDeletedWorkspaces
import dev.deeplinks.native.util.workspaceGroupKey
import dev.deeplinks.native.util.chatCanvasKind
import dev.deeplinks.native.util.ChatCanvasKind
import dev.deeplinks.native.util.copiedNeedsAppToast
import dev.deeplinks.native.util.localHideAfterRemote
import dev.deeplinks.native.util.forkAccepted
import dev.deeplinks.native.util.catalogKind
import dev.deeplinks.native.util.streamBannerKind
import dev.deeplinks.native.util.ParkedRestoreKind
import dev.deeplinks.native.util.ParkedSend
import dev.deeplinks.native.util.parkSendForPersistence
import dev.deeplinks.native.util.parkedSendRestoreKind
import dev.deeplinks.native.util.parkedRestoreComposerError
import dev.deeplinks.native.util.ComposerDraft
import dev.deeplinks.native.util.appendComposerImage
import dev.deeplinks.native.util.composerDraftKey
import dev.deeplinks.native.util.mergeComposerText
import dev.deeplinks.native.util.putComposerDraft
import dev.deeplinks.native.util.liveOrParkedComposerError
import dev.deeplinks.native.util.stashComposerDraft
import dev.deeplinks.native.util.switchComposerErrors

/**
 * DeepSeek Harness 工作台 —— 1:1 复刻 DSH Web (127.0.0.1:3080) 设计语言。
 * 设计 token 取自 dsw 暗色主题：neutral-bluish 色阶 / deepseek 品牌蓝 / 22px 气泡与输入卡。
 */

// --- DSH 设计系统常量由 DshTheme.kt 统一提供 ---

/** LazyColumn 中非消息条目的 key（锚点计算时排除，不能当作消息索引）。 */
private val NON_MESSAGE_ITEM_KEYS = setOf("load-older", "turn-status", "stopped-badge", "session-header")

/**
 * 兼容壳：本类只作为外部入口（系统分享 / 通知）的中转，把 extras 原样交给
 * MainActivity 的 workspace 目的地。语音识别、安全窗口等生命周期都在 MainActivity。
 */
class WorkspaceActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(
            Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_START_ROUTE, AppRoute.WORKSPACE)
                .putExtras(intent)
                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
        )
        finish()
    }
}

// ---------- 工作台主界面 ----------

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun WorkspaceScreen(
    host: Host,
    initialSessionId: String? = null,
    restoreSessionId: String? = null,
    initialShareText: String? = null,
    initialShareImages: List<String> = emptyList(),
    initialShareSeq: Long = 0L,
    initialShareNotice: String? = null,
    /** 打开设备（配对）页；authNotice 非空时在设备页顶部说明原因。 */
    onOpenDevice: (authNotice: String?) -> Unit,
    onOpenSettings: () -> Unit,
    onStartVoiceInput: ((String) -> Unit, () -> Unit, (String) -> Unit) -> Unit,
    onStopVoiceInput: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val hostIdentity = remember(host) { host.stableIdentity() }
    // 进入工作区即记录「最近使用设备」；设备重命名不影响该身份。
    LaunchedEffect(hostIdentity) { HostStore.rememberLastHost(context, host) }
    val workspaceViewModel: WorkspaceViewModel = viewModel(
        key = "workspace:${host.slotKey}",
        factory = WorkspaceViewModel.factory(host),
    )
    val client = workspaceViewModel.client

    var sessions by workspaceViewModel.sessions
    var currentSessionId by workspaceViewModel.currentSessionId
    var messages by workspaceViewModel.messages
    var olderMessages by workspaceViewModel.olderMessages
    var sessionStats by workspaceViewModel.sessionStats
    var hasMoreMessages by workspaceViewModel.hasMoreMessages
    var nextBeforeSeq by workspaceViewModel.nextBeforeSeq
    var stoppedReason by workspaceViewModel.stoppedReason
    var isLoadingOlder by workspaceViewModel.isLoadingOlder

    // 助手消息的赞/踩反馈：messageId -> (rating, version)。
    // feedbackSupported=false 时（插件未提供 /feedback 路由）隐藏赞踩，避免死按钮。
    var messageFeedback by remember { mutableStateOf(emptyMap<String, Pair<String, String>>()) }
    var feedbackSupported by remember { mutableStateOf(false) }
    LaunchedEffect(currentSessionId) {
        val sid = currentSessionId
        if (sid == null) {
            messageFeedback = emptyMap()
            feedbackSupported = false
            return@LaunchedEffect
        }
        // 打开会话时订阅可能还没注册（插件对活跃订阅有校验），短暂重试；
        // 路由不存在（404）则立即放弃并隐藏赞踩。
        var result: Map<String, Pair<String, String>>? = null
        var attempt = 0
        while (result == null && attempt < 6) {
            attempt++
            val outcome: Pair<Map<String, Pair<String, String>>?, Boolean> = withContext(Dispatchers.IO) {
                try {
                    val root = client.getMessageFeedback(sid)
                    val items = root.optJSONObject("value")?.optJSONArray("items") ?: org.json.JSONArray()
                    val map = buildMap<String, Pair<String, String>> {
                        for (i in 0 until items.length()) {
                            val o = items.optJSONObject(i) ?: continue
                            val id = o.optString("messageId")
                            val rating = o.optString("rating")
                            val ver = o.optString("version")
                            if (id.isNotBlank() && rating.isNotBlank()) put(id, rating to ver)
                        }
                    }
                    map to false
                } catch (e: Exception) {
                    val msg = e.message.orEmpty()
                    null to (msg.contains("404") || msg.contains("not found"))
                }
            }
            val map = outcome.first
            if (map != null) {
                result = map
                break
            }
            if (outcome.second) break
            kotlinx.coroutines.delay(1200)
        }
        messageFeedback = result ?: emptyMap()
        feedbackSupported = result != null
    }
    var loadOlderFailed by workspaceViewModel.loadOlderFailed
    // 首次加载（当前会话有内容前的第一条 history 请求 in-flight）：驱动 ChatLoadingSkeleton
    var initialLoadInFlight by workspaceViewModel.initialLoadInFlight
    var historyLoadError by workspaceViewModel.historyLoadError
    var sessionsLoadError by workspaceViewModel.sessionsLoadError
    var sessionsInitialLoad by workspaceViewModel.sessionsInitialLoad
    var workspacesLoadError by workspaceViewModel.workspacesLoadError
    var workspacesInitialLoad by workspaceViewModel.workspacesInitialLoad
    // 工具调用查找（客户端过滤当前会话工具消息；toolQuery 为瞬时视图状态，不持久化）
    var toolSearchOpen by remember { mutableStateOf(false) }
    var toolQuery by remember { mutableStateOf("") }
    var modelCatalog by workspaceViewModel.modelCatalog
    var modelCatalogLoading by workspaceViewModel.modelCatalogLoading
    var modelCatalogError by workspaceViewModel.modelCatalogError
    var isSending by workspaceViewModel.isSending
    var composerDrafts by workspaceViewModel.composerDrafts
    var composerDraftErrors by workspaceViewModel.composerDraftErrors
    var composerActionError by workspaceViewModel.composerActionError
    var sessionPermissionOverrides by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var inputText by workspaceViewModel.inputText
    var pendingImages by workspaceViewModel.pendingImages
    var isListening by remember { mutableStateOf(false) }
    // composer 焦点请求器：本地 Insertable（如 /plan /goal /subagent）picker 选中后重新聚焦输入框
    val composerFocusRequester = remember { androidx.compose.ui.focus.FocusRequester() }
    val composerKeyboardController = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    var showModelPicker by remember { mutableStateOf(false) }
    // showViewOptions removed — view toggle is inline in sidebar
    var showAddWorkspace by remember { mutableStateOf(false) }
    var showPermissionPicker by remember { mutableStateOf(false) }
    // WI-R3：归档/删除集合统一由 WorkspaceLocalStore 所有（Compose 状态 + prefs 双写）
    val localStore = workspaceViewModel.local
    var archivedIds by localStore.archivedSessionIds
    var deletedIds by localStore.deletedSessionIds
    fun setDeleted(id: String) = localStore.addDeletedSession(id)
    fun setArchived(id: String) = localStore.setArchivedSessionIds(
        if (id in archivedIds) archivedIds - id else archivedIds + id,
    )
    val workspacePrefs = localStore.prefs
    // 记录该设备最近打开的会话（下次冷启动恢复；失效时由 boot 校验回退）。
    LaunchedEffect(hostIdentity, currentSessionId) {
        val sid = currentSessionId ?: return@LaunchedEffect
        workspacePrefs.rememberLastSession(hostIdentity, sid)
    }
    // 当前会话被本地删除/归档时清掉最近会话指针，避免下次启动去拉一个不存在的会话。
    LaunchedEffect(hostIdentity, currentSessionId, deletedIds, archivedIds) {
        val sid = currentSessionId ?: return@LaunchedEffect
        if (sid in deletedIds || sid in archivedIds) {
            workspacePrefs.forgetLastSession(hostIdentity, sid)
        }
    }
    var sessionFilter by remember { mutableStateOf(workspacePrefs.sessionFilter) }
    var pendingSessionCwd by remember { mutableStateOf<String?>(null) }
    /** 用户点了「新建会话」、尚未发首条消息时为 true；此期间 refreshSessions 不得抢绑旧会话。 */
    var composeNewSession by remember { mutableStateOf(false) }
    var pendingAttachOwnerKey by rememberSaveable { mutableStateOf<String?>(null) }
    fun composerOwnerKey(): String =
        composerDraftKey(if (composeNewSession) null else currentSessionId)
    fun liveComposerDraft() = ComposerDraft(inputText, pendingImages)
    fun applyLiveComposer(draft: ComposerDraft) {
        inputText = draft.text
        pendingImages = draft.images
    }
    fun restoreComposerToOwner(ownerKey: String, draft: ComposerDraft) {
        if (composerOwnerKey() == ownerKey) applyLiveComposer(draft)
        else composerDrafts = putComposerDraft(composerDrafts, ownerKey, draft)
    }
    fun switchComposer(toSessionId: String?, composingNew: Boolean) {
        val fromKey = composerOwnerKey()
        val toKey = composerDraftKey(if (composingNew) null else toSessionId)
        if (fromKey != toKey && isListening) {
            onStopVoiceInput()
            isListening = false
        }
        val (next, loaded) = stashComposerDraft(composerDrafts, fromKey, toKey, liveComposerDraft())
        val (nextErrors, parkedError) = switchComposerErrors(
            composerDraftErrors,
            fromKey,
            toKey,
            composerActionError,
        )
        composerDrafts = next
        composerDraftErrors = nextErrors
        applyLiveComposer(loaded)
        composerActionError = parkedError
        composeNewSession = composingNew
        currentSessionId = toSessionId
    }
    fun reportComposerOwnerError(ownerKey: String, message: String) {
        val (next, live) = liveOrParkedComposerError(
            composerDraftErrors,
            ownerKey,
            composerOwnerKey(),
            message,
        )
        composerDraftErrors = next
        if (live != null) composerActionError = live
    }
    /** 首条消息 createSession 后立即切 sid 时，保留已乐观插入的消息，避免 LaunchedEffect 清空。 */
    var preserveMessagesSessionId by remember { mutableStateOf<String?>(null) }
    var pendingAgentPreset by remember { mutableStateOf("") }
    var agentPresets by workspaceViewModel.agentPresets
    var agentPresetsLoading by workspaceViewModel.agentPresetsLoading
    var agentPresetsError by workspaceViewModel.agentPresetsError
    var showAgentPresetPicker by remember { mutableStateOf(false) }
    var showSubagentSheet by remember { mutableStateOf(false) }
    /** 新会话阶段的默认模型（create 后 selectModel）。 */
    var pendingModel by remember { mutableStateOf<Triple<String, String, String?>?>(null) }
    var expandedWorkspaces by remember { mutableStateOf(setOf<String>()) }
    var expandedGroups by remember { mutableStateOf(setOf<String>()) } // 组内"显示全部"展开态
    var deleteWorkspaceTarget by remember { mutableStateOf<String?>(null) } // 待删除的工作区路径
    var deleteWorkspaceError by remember { mutableStateOf<String?>(null) }
    var deleteWorkspaceSaving by remember { mutableStateOf(false) }
    var deleteSessionTarget by remember { mutableStateOf<MobileSession?>(null) } // 待删除的会话
    var deleteSessionError by remember { mutableStateOf<String?>(null) }
    var deleteSessionSaving by remember { mutableStateOf(false) }
    fun openDeleteSession(session: MobileSession) {
        deleteSessionError = null
        deleteSessionSaving = false
        deleteSessionTarget = session
    }
    fun openDeleteWorkspace(path: String) {
        deleteWorkspaceError = null
        deleteWorkspaceSaving = false
        deleteWorkspaceTarget = path
    }
    /** 服务端已注册工作区（含 sessionIds；侧栏 / 选择器以此为准）。 */
    var workspaceCatalogItems by remember { mutableStateOf<List<MobileWorkspace>>(emptyList()) }
    val workspaceRegistry = workspaceCatalogItems.map { normalizeWorkspacePath(it.path) }.filter { it.isNotBlank() }
    val workspaceAccounts = remember(workspaceCatalogItems) {
        workspaceCatalogItems.map { WorkspaceAccount(it.path, it.sessionIds) }
    }
    /** 是否已成功拉取过工作区注册表；未就绪前侧栏可临时回退到会话 cwd。 */
    var workspaceRegistryReady by remember { mutableStateOf(false) }
    // 本地已删除工作区（本机乐观隐藏；服务端重新注册后会自动解除）
    var deletedWorkspaces by localStore.deletedWorkspacePaths
    fun persistDeletedWorkspaces(next: Set<String>) = localStore.setDeletedWorkspaces(next)
    fun setDeletedWorkspace(path: String) {
        persistDeletedWorkspaces(deletedWorkspaces + normalizeWorkspacePath(path))
    }
    var renameTarget by remember { mutableStateOf<MobileSession?>(null) }
    var renameError by remember { mutableStateOf<String?>(null) }
    var renameSaving by remember { mutableStateOf(false) }
    fun openRename(session: MobileSession) {
        renameError = null
        renameSaving = false
        renameTarget = session
    }
    // viewMode：chat / trace（全局；持久化，仅 tab 切回记忆上次）
    var viewMode by remember {
        val saved = localStore.viewMode
        mutableStateOf(if (saved in setOf("chat", "trace")) saved else "chat")
    }
    fun selectViewMode(mode: String) {
        val next = if (mode in setOf("chat", "trace")) mode else "chat"
        viewMode = next
        localStore.viewMode = next
    }
    // searchQuery：侧边栏搜索文本（持久化文本，不持久化结果）
    var searchQuery by remember { mutableStateOf(localStore.historyQuery) }
    var sidebarSearchOpen by remember { mutableStateOf(localStore.historyQuery.isNotBlank()) }
    var showSessionFilterSheet by remember { mutableStateOf(false) }
    // WI-R2/R3：搜索结果与状态机、网络任务句柄（sessions/workspaces/history）
    // 一律由 WorkspaceViewModel 持有，组合重建不再中断数据流
    var searchResults by workspaceViewModel.searchResults
    var searchState by workspaceViewModel.searchState
    var cropBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var captureUri by remember { mutableStateOf<android.net.Uri?>(null) }
    /** 固定的拍照输出文件。Activity 被系统重建时 ${captureUri} 会丢，回调里用同一路径兜底重建。 */
    fun captureFileUri(): android.net.Uri {
        val dir = java.io.File(context.cacheDir, "capture").apply { mkdirs() }
        val file = java.io.File(dir, "photo.jpg")
        return androidx.core.content.FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
    }
    fun addPendingBitmap(bmp: android.graphics.Bitmap, ownerKey: String = pendingAttachOwnerKey ?: composerOwnerKey()) {
        scope.launch(Dispatchers.IO) {
            try {
                val pair = ImageAttach.toPending(bmp)
                withContext(Dispatchers.Main) {
                    if (composerOwnerKey() == ownerKey) {
                        applyLiveComposer(appendComposerImage(liveComposerDraft(), pair))
                        // 新图落到当前输入槽即是对上一次失败的修正，清掉槽里停着的旧错误。
                        composerActionError = null
                    } else {
                        val stored = composerDrafts[ownerKey] ?: ComposerDraft()
                        composerDrafts = putComposerDraft(composerDrafts, ownerKey, appendComposerImage(stored, pair))
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    val msg = if (e.message?.contains("8 MiB") == true) L.imageTooLargeSkipped
                    else L.readImageFailed.format(e.message ?: L.unknownError)
                    reportComposerOwnerError(ownerKey, msg)
                }
            } finally {
                if (!bmp.isRecycled) bmp.recycle()
            }
        }
    }
    val imagePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: android.net.Uri? ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                try {
                    val bmp = ImageAttach.decode(context, uri)
                    addPendingBitmap(bmp)
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        reportComposerOwnerError(
                            pendingAttachOwnerKey ?: composerOwnerKey(),
                            L.readImageFailed.format(e.message ?: L.unknownError),
                        )
                    }
                }
            }
        }
    }
    val takePictureLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { ok ->
        // Activity 在相机返回时可能被系统重建（HyperOS 常见），remember 的 captureUri 会丢；
        // 用固定的 cache/capture/photo.jpg 兜底重建，否则照片被静默丢弃、缩略图不出现。
        val uri = captureUri ?: captureFileUri()
        if (ok) {
            scope.launch(Dispatchers.IO) {
                try {
                    val bmp = ImageAttach.decode(context, uri)
                    withContext(Dispatchers.Main) { cropBitmap = bmp }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        reportComposerOwnerError(
                            pendingAttachOwnerKey ?: composerOwnerKey(),
                            L.readImageFailed.format(e.message ?: L.unknownError),
                        )
                    }
                }
            }
        }
    }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            val uri = captureFileUri()
            captureUri = uri
            takePictureLauncher.launch(uri)
        } else {
            Toast.makeText(context, L.cameraPermissionRequired, Toast.LENGTH_SHORT).show()
        }
    }
    fun launchCamera() {
        pendingAttachOwnerKey = composerOwnerKey()
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            return
        }
        val uri = captureFileUri()
        captureUri = uri
        takePictureLauncher.launch(uri)
    }
    val listState = rememberLazyListState()
    var elapsedSec by remember { mutableStateOf(0L) }
    // WI-003：尾部定位请求（每次自增触发一次"等待布局后再滚底"）；0 表示无请求
    var tailRequestId by remember { mutableStateOf(0) }
    var lastTailRequestId by remember { mutableStateOf(0) }
    // 用户上翻阅读时新消息到达 → 显示"回到底部"（不强行拉回尾部）
    var showScrollToBottom by remember { mutableStateOf(false) }
    // 悬浮「回到底部」上的未读数：以离开底部那一刻的消息条数为基线
    var scrollAnchorCount by remember { mutableStateOf(0) }
    var unreadWhileScrolled by remember { mutableStateOf(0) }
    LaunchedEffect(showScrollToBottom, messages.size) {
        if (!showScrollToBottom) {
            scrollAnchorCount = messages.size
            unreadWhileScrolled = 0
        } else {
            unreadWhileScrolled = (messages.size - scrollAnchorCount).coerceAtLeast(0)
        }
    }
    var showTurnJumpSheet by remember { mutableStateOf(false) }
    val changesPanel = remember { ChangesPanelState() }
    LaunchedEffect(currentSessionId) { changesPanel.reset() }
    val changeSummaries = remember(olderMessages, messages) { sessionChangeSummaries(mergeHistoryPages(olderMessages, messages)) }
    /** 停稳时是否贴在底部：web/SSE 新消息据此决定是否自动跟尾（比瞬时 isNearBottom 更稳）。 */
    var stickToBottom by remember { mutableStateOf(true) }
    // WI-004：服务端设置为配置源（默认 Agent 预设/权限等），启动与回前台时刷新
    var appSettings by workspaceViewModel.appSettings
    fun refreshAppSettings() = workspaceViewModel.refreshAppSettings()

    // ===== SSE 实时流（当前会话；VM 持有，Activity 重建不断流） =====
    val streamClient = currentSessionId?.let { workspaceViewModel.acquireStream(it) }
    var streamEverConnected by remember(currentSessionId) { mutableStateOf(false) }
    var streamQuietElapsed by remember(currentSessionId) { mutableStateOf(false) }
    // 最近一次 SSE 活动（任意事件帧）；看门狗用它识别「连接看似健康但事件停流」的半开状态。
    var lastStreamEventAt by remember(currentSessionId) { mutableLongStateOf(0L) }
    LaunchedEffect(currentSessionId) {
        streamQuietElapsed = false
        delay(1_500)
        streamQuietElapsed = true
    }
    LaunchedEffect(streamClient?.connectionState) {
        if (streamClient?.connectionState == SessionStreamClient.ConnectionState.CONNECTED) {
            streamEverConnected = true
        }
    }
    // SSE 事件驱动的执行状态（turn/start→true，turn/end→false；与 session.list 的 running 取或）
    var liveRunning by workspaceViewModel.liveRunning
    // 乐观 liveRunning：若长时间收不到 turn/end 且会话也不再 running，收回以免永久卡死轮询
    LaunchedEffect(liveRunning, currentSessionId) {
        if (!liveRunning) return@LaunchedEffect
        delay(90_000)
        if (liveRunning && sessions.find { it.sessionId == currentSessionId }?.running != true && !isSending) {
            liveRunning = false
        }
    }
    // 最近一次全量刷新覆盖到的最大事件 seq（ready 时用于检测服务端游标领先）
    var seedMaxSeq by workspaceViewModel.seedMaxSeq
    var lastResyncAt by remember { mutableStateOf(0L) }
    // 增量构建状态：tool/call 时间（算 tool/result 耗时）、流式 tool_call 消息 id → callId
    val toolCallTimes = remember(currentSessionId) { mutableMapOf<String, Long>() }
    val streamToolCallIds = remember(currentSessionId) { mutableMapOf<String, String>() }

    LaunchedEffect(appSettings.agentPreset) {
        if (composeNewSession || currentSessionId == null) {
            pendingAgentPreset = appSettings.agentPreset
        }
    }

    // Agent 预设（WI-R2：网络迁入 VM）
    fun loadAgentPresets() = workspaceViewModel.loadAgentPresets()

    LaunchedEffect(host) {
        sessionPermissionOverrides = emptyMap()
        loadAgentPresets()
    }

    fun selectSession(sessionId: String) {
        switchComposer(sessionId, composingNew = false)
    }

    // singleTask + 通知 deep-link：intent.sessionId 变化时切入目标会话
    LaunchedEffect(initialSessionId) {
        val target = initialSessionId?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        if (target != currentSessionId) selectSession(target)
    }

    var appliedShareSeq by rememberSaveable { mutableStateOf(0L) }
    var appliedShareOwner by rememberSaveable { mutableStateOf("") }
    var shareConsumed by rememberSaveable { mutableStateOf(false) }
    /**
     * 外部分享带入的、以 `/` 开头的文本（原样存 composer 文本）。
     * 系统分享内容不可信，不能让它直接变成可一键触发的命令入口；
     * 只有用户手动编辑（inputText 与该快照不再相等）后才恢复命令候选。
     */
    var shareCommandGuard by remember { mutableStateOf<String?>(null) }
    val shareOwnerKey = composerOwnerKey()
    LaunchedEffect(initialShareSeq, initialShareText, initialShareImages, initialShareNotice, shareOwnerKey) {
        if (initialShareText.isNullOrBlank() && initialShareImages.isEmpty() && initialShareNotice.isNullOrBlank()) return@LaunchedEffect
        val token = if (initialShareSeq != 0L) {
            initialShareSeq
        } else {
            (initialShareText.orEmpty() + "\u0000" + initialShareImages.joinToString("\u0000") + "\u0000" + initialShareNotice.orEmpty()).hashCode().toLong()
        }
        // 已消费的分享不再重复应用；但冷启动时分享先落在「新会话」owner，会话列表随后加载并
        // switchComposer 会清空输入框——因此 owner 从空变为真实会话时需要再应用一次。
        if (shareConsumed && appliedShareSeq == token) return@LaunchedEffect
        if (appliedShareSeq == token && appliedShareOwner == shareOwnerKey) return@LaunchedEffect
        appliedShareSeq = token
        appliedShareOwner = shareOwnerKey
        if (shareOwnerKey.isNotEmpty()) shareConsumed = true
        val text = initialShareText?.trim().orEmpty()
        if (text.isNotEmpty()) {
            inputText = if (inputText.isBlank()) text else "$inputText\n$text"
            if (text.startsWith("/")) shareCommandGuard = inputText
        }
        var failed = 0
        for (uriString in initialShareImages) {
            try {
                val bmp = withContext(Dispatchers.IO) { ImageAttach.decode(context, android.net.Uri.parse(uriString)) }
                addPendingBitmap(bmp)
            } catch (e: Exception) {
                failed += 1
                if (initialShareImages.size == 1) {
                    composerActionError = L.readImageFailed.format(e.message ?: L.unknownError)
                }
            }
        }
        if (failed > 0 && initialShareImages.size > 1) {
            composerActionError = L.readImageFailed.format(L.unknownError)
        } else if (!initialShareNotice.isNullOrBlank()) {
            composerActionError = initialShareNotice
        }
    }

    LaunchedEffect(host.slotKey, currentSessionId, isSending) {
        if (isSending) return@LaunchedEffect
        val parked = workspacePrefs.parkedSend ?: return@LaunchedEffect
        when (parkedSendRestoreKind(parked, host.slotKey, currentSessionId)) {
            ParkedRestoreKind.None -> Unit
            ParkedRestoreKind.SelectSession -> {
                val target = parked.sessionId ?: return@LaunchedEffect
                selectSession(target)
            }
            ParkedRestoreKind.IntoCurrent -> {
                val filledText = inputText.isBlank() && parked.text.isNotBlank()
                val filledImages = pendingImages.isEmpty() && parked.images.isNotEmpty()
                if (filledText) inputText = parked.text
                if (filledImages) pendingImages = parked.images
                workspacePrefs.parkedSend = null
                if (parkedRestoreComposerError(parked.droppedImages)) {
                    composerActionError = L.sendParkedImagesDropped
                }
            }
        }
    }

    fun startComposeSession(cwd: String? = null) {
        switchComposer(null, composingNew = true)
        pendingSessionCwd = cwd
        pendingAgentPreset = appSettings.agentPreset
        pendingModel = null
        messages = emptyList()
        olderMessages = emptyList()
        sessionStats = null
        historyLoadError = null
        stoppedReason = null
        liveRunning = false
        seedMaxSeq = 0L
        isLoadingOlder = false
        loadOlderFailed = false
        initialLoadInFlight = false
    }

    var authExpired by remember { mutableStateOf(false) }
    fun onAuthExpired(error: Throwable? = null) {
        if (authExpired) return
        authExpired = true
        val message = error?.let(::mobileAuthUserMessage) ?: L.connectionAuthExpired
        // 设备 token / 证书失效时丢掉本机配对。云端路由 REVOKED 只降级为局域网，不断整台。
        when {
            error != null && shouldDemoteRelayOnAuth(error) && host.hasRelay -> {
                runCatching { HostStore.demoteRelay(context, host) }
                    .onFailure { Log.w("WorkspaceActivity", "onAuthExpired: demote relay failed: ${it.message}") }
            }
            error != null && shouldDropLocalHostOnOpenAuth(error) -> {
                runCatching { HostStore.remove(context, host) }
                    .onFailure { Log.w("WorkspaceActivity", "onAuthExpired: remove revoked host failed: ${it.message}") }
            }
        }
        onOpenDevice(message)
        // WorkspaceScreen 现宿主于 MainActivity；旧独立 Activity 时代的 finish() 会关掉整个 app
    }
    // VM 内的周期性请求遇到认证失效时走同一条退出路径（WI-R2）
    workspaceViewModel.authListener = { error -> onAuthExpired(error) }

    fun applySessionList(list: List<MobileSession>, selectLatest: Boolean = false): String? {
        val previousSessionId = currentSessionId
        sessions = list
        val nextSessionId = reconciledSessionId(
            currentSessionId = previousSessionId,
            sessions = list,
            hiddenSessionIds = archivedIds + deletedIds,
            preserveEmptySelection = composeNewSession,
            selectLatest = selectLatest,
        )
        if (nextSessionId != previousSessionId) {
            // A Web-side archive/delete can remove the currently open session while its
            // SSE connection is still alive. Move away from that stale stream immediately.
            liveRunning = list.any { it.sessionId == nextSessionId && it.running }
            switchComposer(nextSessionId, composingNew = nextSessionId == null && composeNewSession)
        } else if (list.any { it.sessionId == nextSessionId && it.running }) {
            liveRunning = true
        }
        return nextSessionId
    }

    fun applySessionSnapshot(snapshot: MobileSessionSnapshot, selectLatest: Boolean = false): String? {
        if (snapshot.archiveSnapshotAvailable) {
            val restored = workspacePrefs.restoredSessionIds
            val nextRestored = restored intersect snapshot.archivedSessionIds
            if (nextRestored != restored) {
                workspacePrefs.restoredSessionIds = nextRestored
            }
            val nextArchivedIds = reconcileArchivedSessionIds(snapshot.archivedSessionIds, nextRestored)
            if (nextArchivedIds != archivedIds) {
                localStore.setArchivedSessionIds(nextArchivedIds)
            }
        }
        return applySessionList(snapshot.sessions, selectLatest)
    }

    fun refreshSessions(selectLatest: Boolean = false, reportFailure: Boolean = true) {
        // WI-R2：单飞/代际去重/取消都在 VM（viewModelScope），屏幕只做调和
        workspaceViewModel.refreshSessions(selectLatest, reportFailure) { snapshot ->
            applySessionSnapshot(snapshot, selectLatest)
        }
    }

    fun applyWorkspaceCatalog(catalog: MobileWorkspaceCatalog) {
        val nextItems = catalog.workspaces.map { ws ->
            ws.copy(path = normalizeWorkspacePath(ws.path))
        }.filter { it.path.isNotBlank() }
        val nextPaths = nextItems.map { it.path }
        val nextSet = nextPaths.toSet()
        workspaceCatalogItems = nextItems
        workspaceRegistryReady = true
        val reconciled = reconcileDeletedWorkspaces(nextPaths, deletedWorkspaces)
        if (reconciled != deletedWorkspaces) {
            persistDeletedWorkspaces(reconciled)
        }
        if (pendingSessionCwd?.let(::normalizeWorkspacePath)?.let { it !in nextSet || it in reconciled } == true) {
            pendingSessionCwd = null
        }
        val last = workspacePrefs.lastSelectedWorkspace?.let(::normalizeWorkspacePath)
        if (last != null && (last !in nextSet || last in reconciled)) {
            workspacePrefs.lastSelectedWorkspace = null
        }
        if (catalog.archiveSnapshotAvailable) {
            val restored = workspacePrefs.restoredSessionIds
            val nextRestored = restored intersect catalog.archivedSessionIds
            if (nextRestored != restored) {
                workspacePrefs.restoredSessionIds = nextRestored
            }
            val nextArchivedIds = reconcileArchivedSessionIds(catalog.archivedSessionIds, nextRestored)
            if (nextArchivedIds != archivedIds) {
                localStore.setArchivedSessionIds(nextArchivedIds)
                // workspace.archiveSession is the Web "delete" contract. session.list still
                // contains archived rows, so the archive set must invalidate the selection.
                applySessionList(sessions)
            }
        }
    }

    fun refreshWorkspaces() {
        // WI-R2：网络在 VM；目录为空时才把失败升级为可见错误（与旧实现一致）
        workspaceViewModel.refreshWorkspaces(
            onApplied = { catalog -> applyWorkspaceCatalog(catalog) },
            onError = { message ->
                if (workspaceCatalogItems.isEmpty()) workspacesLoadError = message
            },
        )
    }

    /** 归档到服务端成功后再本机隐藏（对标 web archive；失败时列表保持原样）。 */
    fun applyLocalArchive(session: MobileSession, asDeleted: Boolean) {
        workspacePrefs.rememberSessionSnapshot(
            sessionId = session.sessionId,
            title = session.title,
            cwd = session.cwd,
            updatedAt = session.updatedAt,
        )
        if (session.sessionId in workspacePrefs.restoredSessionIds) {
            workspacePrefs.restoredSessionIds = workspacePrefs.restoredSessionIds - session.sessionId
        }
        if (session.sessionId in workspacePrefs.settingsHiddenSessionIds) {
            workspacePrefs.settingsHiddenSessionIds = workspacePrefs.settingsHiddenSessionIds - session.sessionId
        }
        if (asDeleted) {
            setDeleted(session.sessionId)
        }
        if (session.sessionId !in archivedIds) {
            localStore.setArchivedSessionIds(archivedIds + session.sessionId)
        }
        if (currentSessionId == session.sessionId) {
            startComposeSession(pendingSessionCwd)
        }
        refreshSessions()
    }

    // 归档防抖：服务端确认前行不消失，重复右滑/长按只提交一次，避免叠一摞撤销条
    val archiveInFlight = remember { java.util.Collections.synchronizedSet(mutableSetOf<String>()) }

    fun archiveSessionNow(
        session: MobileSession,
        asDeleted: Boolean = false,
        onDone: ((String?) -> Unit)? = null,
    ) {
        if (!archiveInFlight.add(session.sessionId)) return
        scope.launch(Dispatchers.IO) {
            try {
                client.archiveSession(session.sessionId)
                withContext(Dispatchers.Main) {
                    if (localHideAfterRemote(accepted = true)) {
                        applyLocalArchive(session, asDeleted)
                    }
                    onDone?.invoke(null)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    val message = L.archiveFailed.format(e.message ?: L.fallbackRetryLater)
                    if (onDone != null) onDone(message)
                    else sessionsLoadError = message
                }
            } finally {
                // 失败后放行重试；成功则行已本机隐藏，不会再来
                archiveInFlight.remove(session.sessionId)
            }
        }
    }

    fun forkNow(sessionId: String, closeDrawer: Boolean = false) {
        scope.launch(Dispatchers.IO) {
            try {
                val newId = client.forkSession(sessionId)
                withContext(Dispatchers.Main) {
                    if (!forkAccepted(newId) || newId == null) {
                        val message = L.forkFailed.format(L.unknownError)
                        if (closeDrawer) sessionsLoadError = message
                        else composerActionError = message
                        return@withContext
                    }
                    selectSession(newId)
                    refreshSessions()
                    if (closeDrawer) scope.launch { drawerState.close() }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    val message = L.forkFailed.format(e.message ?: L.unknownError)
                    if (closeDrawer) sessionsLoadError = message
                    else composerActionError = message
                }
            }
        }
    }

    /** 用户是否停留在列表底部附近（用于决定是否跟随新消息 / 显示回到底部）。 */
    fun isNearBottom(): Boolean {
        val info = listState.layoutInfo
        val total = info.totalItemsCount
        if (total == 0) return true
        val lastVisible = info.visibleItemsInfo.lastOrNull() ?: return true
        if (lastVisible.index < total - 2) return false
        // 最后一项很长时：只要其底部离视口底 ≤ 120px，仍算贴底（避免只露出开头就判定已离开）
        if (lastVisible.index >= total - 1) {
            val overflow = (lastVisible.offset + lastVisible.size) - info.viewportEndOffset
            return overflow <= 120
        }
        return true
    }

    /** 请求一次"等待布局完成后滚动到尾部"（并发合并为一次）。 */
    fun requestTailPosition() {
        tailRequestId++
    }

    /**
     * 把列表真正贴到底：scrollToItem 只会把条目顶到视口顶部，
     * 长回复需要再 scrollBy 把溢出部分推上去，否则最新内容仍在视口外。
     */
    suspend fun alignListToBottom() {
        val total = listState.layoutInfo.totalItemsCount
        if (total == 0) return
        val lastIndex = total - 1
        listState.scrollToItem(lastIndex)
        withFrameNanos { }
        repeat(2) {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull() ?: return
            val overflow = (last.offset + last.size) - info.viewportEndOffset
            if (overflow > 0) {
                listState.scrollBy(overflow.toFloat())
                withFrameNanos { }
            } else {
                return
            }
        }
    }

    /** 立即滚到列表末尾；失败写入可检索日志。 */
    fun scrollToTail(tag: String) {
        if (listState.layoutInfo.totalItemsCount == 0) return
        scope.launch {
            try {
                alignListToBottom()
            } catch (e: Exception) {
                Log.w("WorkspaceActivity", "scrollToTail($tag) failed: ${e.message}")
            }
        }
    }

    /**
     * 用户停在底部附近时跟随新消息（WI-003）；已上翻阅读时不强行拉回尾部，
     * 而是显示"回到底部"。发送等用户主动操作传 [force]=true 强制贴底。
     *
     * [stickToBottom]：滚动中一旦离开底部即清掉；跟滚时优先看它，避免追加瞬间
     * isNearBottom 误判。轮询跟滚不传 force，避免读历史时被拽回。
     */
    fun followIfNearBottom(force: Boolean = false) {
        if (viewMode != "chat") return
        if (messages.isEmpty() && listState.layoutInfo.totalItemsCount == 0) return
        if (!force && listState.isScrollInProgress) return
        if (force || stickToBottom || isNearBottom()) {
            stickToBottom = true
            showScrollToBottom = false
            requestTailPosition()
        } else {
            showScrollToBottom = true
        }
    }

    fun refreshMessages(autoScroll: Boolean = false, replaceInFlight: Boolean = false, onDone: (() -> Unit)? = null) {
        // WI-R2：拉取/合并/SSE 种子游标/代际去重都在 VM（viewModelScope），
        // 屏幕只负责滚动回调（贴底跟随是纯 UI 行为）
        workspaceViewModel.refreshMessages(
            autoScroll = autoScroll,
            replaceInFlight = replaceInFlight,
            onDone = onDone,
            onHistoryApplied = { appliedAutoScroll, changed ->
                if (appliedAutoScroll && changed) followIfNearBottom()
            },
        )
    }

    /** 加载更早（DSH loadOlder：按 beforeSeq 向前翻一页，prepend 到消息流，并恢复阅读锚点）。 */
    fun loadOlderMessages() {
        val sid = currentSessionId ?: return
        val before = nextBeforeSeq ?: return
        if (isLoadingOlder) return
        isLoadingOlder = true
        loadOlderFailed = false
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
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("WorkspaceActivity", "loadOlderMessages($sid) failed: ${e.message}")
                withContext(Dispatchers.Main) {
                    if (currentSessionId == sid) loadOlderFailed = true
                }
            }
            withContext(Dispatchers.Main) {
                isLoadingOlder = false
            }
        }
    }

    fun loadModelCatalog(openPicker: Boolean = false) {
        if (openPicker) showModelPicker = true
        // WI-R2：拉取与状态在 VM
        workspaceViewModel.loadModelCatalog(currentSessionId, pendingModel)
    }

    fun refreshModels() {
        if (currentSessionId == null) return
        loadModelCatalog(openPicker = false)
    }

    /**
     * Debounced 搜索（WI-R2：状态机与网络整体迁入 VM，持久化经 WorkspaceLocalStore）。
     */
    fun runSearchDebounced(rawQuery: String) {
        searchQuery = rawQuery
        workspaceViewModel.runSearchDebounced(rawQuery)
    }

    // 会话筛选持久化（dsh_workspace；重启 App 后恢复上次选择）
    LaunchedEffect(sessionFilter) { workspacePrefs.sessionFilter = sessionFilter }

    /**
     * 分发 palette 的本地动作。
     * 服务端永远不会看到这些 trigger —— 只是把 picker 当作 slash 风格的快捷入口。
     */
    fun dispatchLocalPaletteAction(kind: LocalKind) {
        when (kind) {
            LocalKind.SEARCH_SESSIONS -> {
                sidebarSearchOpen = true
                composerKeyboardController?.hide()
                scope.launch { drawerState.open() }
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
            LocalKind.OPEN_MODEL_PICKER -> loadModelCatalog(openPicker = true)
            LocalKind.OPEN_PERMISSION_PICKER -> showPermissionPicker = true
        }
    }

    // ===== 前台状态 + 通知（审批/任务完成仅在后台打扰） + 回前台刷新 =====
    var isForeground by remember { mutableStateOf(true) }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
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
                    // 设置页可能改动了归档/删除集合，回前台重新水合（唯一所有者）
                    localStore.reload()
                    applySessionList(sessions)
                    refreshSessions()
                    refreshWorkspaces()
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
            // 流式变长：贴底时只轻推溢出，避免每个 delta 都重跑整段尾部定位
            if (viewMode == "chat" && (stickToBottom || isNearBottom())) {
                stickToBottom = true
                showScrollToBottom = false
                scope.launch {
                    try {
                        withFrameNanos { }
                        val info = listState.layoutInfo
                        val last = info.visibleItemsInfo.lastOrNull() ?: return@launch
                        val overflow = (last.offset + last.size) - info.viewportEndOffset
                        if (overflow > 0) listState.scrollBy(overflow.toFloat())
                    } catch (_: Exception) { }
                }
            }
        }
    }

    fun applyApprovalDecision(approvalId: String, outcome: String, seq: Long, time: Long) {
        if (approvalId.isBlank()) return
        val status = approvalUiStatus(outcome)
        val existing = (olderMessages + messages).firstOrNull { it.approvalId == approvalId || it.id == "approval-$approvalId" }
        if (existing != null) {
            updateStreamMessage(existing.id) { applyRequestState(it, status, outcome) }
            olderMessages = olderMessages.map { msg ->
                if (msg.approvalId == approvalId || msg.id == existing.id) applyRequestState(msg, status, outcome) else msg
            }
        } else {
            appendStreamMessage(
                MobileMessage(
                    id = "approval-$approvalId",
                    role = "approval",
                    text = L.approvalRequest.format(L.toolFallbackName),
                    approvalId = approvalId,
                    time = time,
                    type = "approval",
                    seq = seq,
                    requestStatus = status,
                    outcome = outcome,
                ),
            )
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
                when (val blockType = block.optString("type")) {
                    "reasoning" -> {
                        val id = "reason-$turn-$index"
                        val text = block.optString("text")
                        val i = messages.indexOfLast { it.id == id }
                        if (i >= 0) updateStreamMessage(id) { it.copy(text = text.ifBlank { it.text }, running = false) }
                        else if (text.isNotBlank()) {
                            appendStreamMessage(MobileMessage(id = id, role = "reasoning", text = text, time = item.time, type = "reasoning"))
                        }
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
                    else -> Log.i("SessionStream", "unhandled block-end type=$blockType turn=$turn index=$index keys=${block.keys().asSequence().toList()}")
                }
            }
            else -> Log.i("SessionStream", "unhandled chunk type=$type turn=$turn index=$index keys=${chunk.keys().asSequence().toList()}")
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
                val kind = data.optJSONObject("reason")?.optNullableString("kind")
                stoppedReason = parseStoppedReason(kind)
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
                        seq = item.seq,
                    ),
                )
            }
            "assistant/chunk" -> handleStreamChunk(item)
            "assistant/message" -> {
                val content = data.optJSONObject("message")?.optJSONArray("content") ?: return
                for (i in 0 until content.length()) {
                    val block = content.optJSONObject(i) ?: continue
                    val blockType = block.optString("type")
                    if (blockType != "text") {
                        // 澄清卡/结构化块等：先打日志，便于补协议；勿静默丢弃无痕迹
                        Log.i("WorkspaceActivity", "assistant/message unhandled block type=$blockType keys=${block.keys().asSequence().toList()}")
                        continue
                    }
                    val text = block.optString("text").trim()
                    if (text.isEmpty()) continue
                    // 优先定稿已有的流式气泡，避免再 append 出第二句
                    val streamId = messages.indexOfLast {
                        it.role == "assistant" && it.id.startsWith("msg-stream-")
                    }.takeIf { it >= 0 }?.let { messages[it].id }
                    if (streamId != null) {
                        updateStreamMessage(streamId) {
                            it.copy(text = text.ifBlank { it.text }, running = false, entrance = it.entrance)
                        }
                        continue
                    }
                    val lastAssist = messages.lastOrNull { it.role == "assistant" }
                    if (lastAssist != null && (lastAssist.text == text || text.startsWith(lastAssist.text) || lastAssist.text.startsWith(text.take(32)))) {
                        updateStreamMessage(lastAssist.id) { it.copy(text = text.ifBlank { it.text }, running = false) }
                        continue
                    }
                    appendStreamMessage(
                        MobileMessage(
                            id = "msg-${item.seq}-$i",
                            role = "assistant",
                            text = text,
                            time = item.time,
                            type = "text",
                        ),
                    )
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
            "workspace/changes" -> scope.fetchLiveWorkspaceChanges(client, currentSessionId, item.seq, item.time, { it == currentSessionId }) {
                upsertStreamMessage(it)
            }
            "todo/write" -> {
                val todosArr = data.optJSONArray("todos") ?: return
                val todos = (0 until todosArr.length()).map { j ->
                    val t = todosArr.getJSONObject(j)
                    MobileTodoItem(t.optString("content", ""), t.optString("status", "pending"))
                }
                appendStreamMessage(MobileMessage(id = "todo-${item.seq}", role = "todo", text = "", todos = todos, time = item.time, type = "todo"))
            }
            "goal/write" -> {
                val goalText = data.optString("text").ifBlank { data.optString("goal") }.trim()
                if (goalText.isBlank()) return
                val sid = currentSessionId ?: return
                val lastGoalIdx = messages.indexOfLast { it.role == "goal" && it.id.startsWith("goal-$sid-") }
                if (lastGoalIdx >= 0) {
                    updateStreamMessage(messages[lastGoalIdx].id) {
                        it.copy(text = goalText, goalSummary = goalText, time = item.time)
                    }
                } else {
                    appendStreamMessage(MobileMessage(
                        id = "goal-$sid-${item.seq}",
                        role = "goal",
                        text = goalText,
                        goalSummary = goalText,
                        time = item.time,
                        type = "goal",
                    ))
                }
                // 同步刷新侧栏 / 顶栏的 goal 缓存
                workspaceViewModel.recomputeGoalSummaries()
            }
            "approval/asked" -> {
                val approvalId = data.optString("id", "")
                appendStreamMessage(MobileMessage(
                    id = "approval-${approvalId.ifBlank { item.seq.toString() }}",
                    role = "approval",
                    text = data.optString("reason").ifBlank { L.approvalRequest.format(data.optString("toolName", L.toolFallbackName)) },
                    toolName = data.optString("toolName", "tool"),
                    approvalId = approvalId,
                    callId = data.optString("callId").takeIf { it.isNotBlank() },
                    time = item.time,
                    type = "approval",
                    seq = item.seq,
                    requestStatus = REQUEST_PENDING,
                ))
                val sid = currentSessionId
                if (!isForeground && sid != null) {
                    DshNotifier.notifyApproval(context, host, sid, data.optString("toolName", L.toolFallbackName))
                }
            }
            "approval/decided" -> {
                val approvalId = data.optString("id", "")
                val outcome = data.optString("outcome").ifBlank { data.optString("decision") }.ifBlank { data.optString("result") }
                applyApprovalDecision(approvalId, outcome, item.seq, item.time)
                currentSessionId?.let { DshNotifier.cancelApproval(context, host, it) }
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
            else -> {
                // 澄清卡 / inbox 等未适配事件：打类型与顶层 key，便于对照 Web
                val keys = data.keys().asSequence().toList()
                Log.i("SessionStream", "unhandled type=${item.type} seq=${item.seq} keys=$keys")
            }
        }
    }

    /** assistant/chunk 增量块：delta 追加到 (turn,index) 流式消息，block-end 定稿（权威全文）。 */
    fun applyStreamItem(item: SessionStreamClient.Item) {
        when (item) {
            is SessionStreamClient.Item.Ready -> {
                lastStreamEventAt = System.currentTimeMillis()
                // 服务端 stream 游标领先本地 history 标记时补一次全量（多设备/重启）
                if (seedMaxSeq > 0 && item.resumeSeq > seedMaxSeq) {
                    refreshMessages(autoScroll = true)
                }
            }
            // 断线不在此狂刷 history：ON_STOP/重连 finally 都会发 Disconnected，
            // 交给下方「SSE 未就绪时」的 2s 轮询兜底，避免风暴 + 后台 binder 流量。
            is SessionStreamClient.Item.Disconnected -> {}
            is SessionStreamClient.Item.Stats -> {
                lastStreamEventAt = System.currentTimeMillis()
                sessionStats = parseMobileSessionStats(item.projections)
            }
            is SessionStreamClient.Item.Question -> {
                val questionsArr = item.data.optJSONArray("questions") ?: org.json.JSONArray()
                val parsed = parseClarifyingQuestions(questionsArr)
                val options = parsed.firstOrNull()?.options?.map { it.label }.orEmpty()
                val text = questionSummaryText(parsed, L.approvalQuestion)
                val header = parsed.firstOrNull()?.header?.takeIf { it.isNotBlank() }
                messages = messages.filterNot { it.role == "question" && it.questionRpcId == item.rpcId }
                appendStreamMessage(
                    MobileMessage(
                        id = "question-${item.rpcId}",
                        role = "question",
                        text = text,
                        time = System.currentTimeMillis(),
                        type = "question",
                        questionRpcId = item.rpcId,
                        questionOptions = options,
                        questionHeader = header,
                        questionPayloadJson = questionsArr.toString(),
                        entrance = true,
                        requestStatus = REQUEST_PENDING,
                    ),
                )
                followIfNearBottom()
            }
            is SessionStreamClient.Item.QuestionResolved -> {
                messages = messages.map { msg ->
                    if (msg.role == "question" && msg.questionRpcId == item.rpcId) {
                        applyRequestState(msg, REQUEST_RESOLVED, item.outcome)
                    } else msg
                }
            }
            is SessionStreamClient.Item.ResyncRequired -> {
                val now = System.currentTimeMillis()
                if (now - lastResyncAt < 2_000L) return
                lastResyncAt = now
                olderMessages = emptyList()
                hasMoreMessages = false
                nextBeforeSeq = null
                workspaceViewModel.bumpHistoryGeneration()
                streamClient?.pauseForResync()
                refreshMessages(autoScroll = false, replaceInFlight = true)
            }
            is SessionStreamClient.Item.Message -> {
                lastStreamEventAt = System.currentTimeMillis()
                handleStreamMessage(item)
                streamClient?.noteCommittedSeq(item.seq)
            }
        }
    }

    LaunchedEffect(host) {
        // 冷启动：bootstrap 一次拉主机信息 + 会话，再补工作区归档同步
        // 必须在 effect 协程内执行，host 切换时自动取消，避免旧主机结果写回
        var bootstrapOk = false
        try {
            val (boot, refreshed) = workspaceViewModel.repo.bootstrap()
            bootstrapOk = true
            if (refreshed != host) {
                runCatching {
                    if (host.hasRelay && !refreshed.hasRelay) HostStore.demoteRelay(context, host)
                    else HostStore.upsert(context, refreshed)
                }
            }
            // Bootstrap carries the same durable archive set as Web. Apply it
            // before selecting a session, so an archived Web session cannot
            // flash back into the App during cold start.
            val nextArchivedIds = if (boot.archiveSnapshotAvailable) {
                val restored = workspacePrefs.restoredSessionIds
                val nextRestored = restored intersect boot.archivedSessionIds
                if (nextRestored != restored) {
                    workspacePrefs.restoredSessionIds = nextRestored
                }
                val synced = reconcileArchivedSessionIds(boot.archivedSessionIds, nextRestored)
                if (synced != archivedIds) {
                    localStore.setArchivedSessionIds(synced)
                }
                synced
            } else {
                archivedIds
            }
            sessions = boot.sessions
            sessionsLoadError = null
            sessionsInitialLoad = false
            if (currentSessionId == null && !composeNewSession && boot.sessions.isNotEmpty()) {
                currentSessionId = reconciledSessionId(
                    currentSessionId = null,
                    preferredSessionId = restoreSessionId,
                    sessions = boot.sessions,
                    hiddenSessionIds = nextArchivedIds + deletedIds,
                    preserveEmptySelection = false,
                    selectLatest = true,
                )
                if (boot.sessions.any { it.sessionId == currentSessionId && it.running }) {
                    liveRunning = true
                }
            }
        } catch (e: Exception) {
            if (isMobileAuthFailure(e)) {
                onAuthExpired(e)
                return@LaunchedEffect
            }
            // bootstrap 失败时回退 refreshSessions
        }
        try {
            val catalog = withContext(Dispatchers.IO) { client.getWorkspaces() }
            applyWorkspaceCatalog(catalog)
            workspacesLoadError = null
            workspacesInitialLoad = false
        } catch (e: Exception) {
            if (isMobileAuthFailure(e)) {
                onAuthExpired(e)
                return@LaunchedEffect
            }
            workspacesInitialLoad = false
            if (workspaceCatalogItems.isEmpty()) {
                workspacesLoadError = e.message?.takeIf { it.isNotBlank() } ?: L.loadWorkspaceListFailed
            }
        }
        if (!bootstrapOk) refreshSessions(selectLatest = true)
        refreshAppSettings()
    }

    LaunchedEffect(currentSessionId) {
        // WI-R2：代际去重与 history 任务归 VM；旧流由 acquireStream 切换时停止，
        // 这里不再对新流做 stop（避免把新会话的连接打断再重连）
        workspaceViewModel.bumpHistoryGeneration()
        currentSessionId?.let { DshNotifier.cancelForSession(context, host, it) }
        if (currentSessionId == null) {
            // 切到「无会话」：显式释放 VM 持有的旧流，避免为过期会话维持连接
            workspaceViewModel.releaseStream()
            messages = emptyList()
            olderMessages = emptyList()
            hasMoreMessages = false
            nextBeforeSeq = null
            stoppedReason = null
            liveRunning = false
            seedMaxSeq = 0L
            sessionStats = null
            historyLoadError = null
            toolCallTimes.clear()
            streamToolCallIds.clear()
            showScrollToBottom = false
            stickToBottom = true
            modelCatalog = null
            modelCatalogError = null
            // 切换会话时清空旧目标摘要
            workspaceViewModel.currentGoalSummary.value = null
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
            stickToBottom = true
            isLoadingOlder = false
            loadOlderFailed = false
            initialLoadInFlight = false
            historyLoadError = null
            modelCatalog = null
            modelCatalogError = null
            tailRequestId++
        }
        refreshMessages(autoScroll = preserveMessages)
        refreshModels()
        // SSE 实时流：订阅事件，增量更新消息列表。
        // 流由 VM 持有（viewModelScope）：Activity 重建时组合虽销毁、流与游标仍在，
        // 新组合重新挂上收集器即可；生命周期的 ON_STOP/ON_START 负责前后台启停。
        val client = streamClient ?: return@LaunchedEffect
        client.start()
        for (item in client.items) applyStreamItem(item)
    }

    // WI-003：尾部定位必须在 Compose 提交新数据并完成布局之后执行。
    // 注意：不要把 messages 放进 key——流式追加会反复取消本 effect，导致
    // 「coroutine scope left the composition」且发送后看不到贴底。
    LaunchedEffect(listState, tailRequestId) {
        if (tailRequestId == 0 || tailRequestId == lastTailRequestId) return@LaunchedEffect
        lastTailRequestId = tailRequestId
        try {
            withTimeoutOrNull(3000) {
                snapshotFlow {
                    messages.isNotEmpty() &&
                        listState.layoutInfo.totalItemsCount > 0 &&
                        !listState.isScrollInProgress
                }.first { it }
            }
            withFrameNanos { }
            alignListToBottom()
        } catch (e: Exception) {
            Log.w("WorkspaceActivity", "tail position request-$tailRequestId failed: ${e.message}")
        }
    }

    // 键盘顶起：只走 LazyColumn onSizeChanged 按变矮像素 scrollBy（一条路径，避免与
    // IME inset 双补偿导致不跟手）。stickToBottom 时才上推。
    // （IME inset 本身仍通过 bottom chrome 的 imePadding 抬起输入区。）

    // 滚动中一旦离开底部立即清 stick，避免「还在上滑、stick 仍为 true」被轮询跟滚抢走
    LaunchedEffect(listState, currentSessionId) {
        snapshotFlow { listState.isScrollInProgress to isNearBottom() }
            .collect { (scrolling, near) ->
                if (scrolling) {
                    if (!near) {
                        stickToBottom = false
                        if (messages.isNotEmpty()) showScrollToBottom = true
                    }
                } else {
                    stickToBottom = near
                    if (near) showScrollToBottom = false
                }
            }
    }

    // 前台轮询（WI-R2/R5 重构）：
    // - SSE 未就绪：2s 拉会话 + 工作区 + 消息（回退路径）
    // - SSE 已连接：会话探针单飞；工作区目录降频到 ~10s（会话快照已含
    //   archivedSessionIds，归档同步不依赖 getWorkspaces 的 2s 高频轮询），
    //   updatedAt 前进且非流式生成中时才补 history，执行中完全交给 SSE
    // - 后台暂停，防止 excessive binder traffic；单飞/代际去重全部在 VM
    // - 新会话编辑态也继续拉列表，保证 Web 端增删能同步到侧栏
    LaunchedEffect(currentSessionId) {
        var pollTick = 0
        while (true) {
            delay(2000)
            pollTick++
            val sid = currentSessionId
            if (!isForeground) continue
            val streamClientRef = streamClient
            val streamReady = streamClientRef != null && streamClientRef.isConnected && streamClientRef.isSeeded
            // Web delete/archive is represented only by workspace.archivedSessionIds;
            // session.list intentionally keeps the underlying session log.
            // R5：流健康时工作区目录每 5 拍（≈10s）一次，其余拍交给会话快照
            if (!streamReady || pollTick % 5 == 0) refreshWorkspaces()
            if (sid == null) {
                refreshSessions(reportFailure = false)
                continue
            }
            if (!streamReady) {
                refreshSessions(reportFailure = false)
                refreshMessages(autoScroll = true)
                continue
            }
            // 看门狗：执行中 SSE 看似健康却静默超过 12s——可能是半开连接，或游标
            // 停在错误编号空间导致事件全被去重丢弃（实机曾见 maxSeq≫stream seq）。
            // 强制 resync（清游标 + 从历史页重播种）并刷新消息，避免视图冻结到重进会话。
            val busyNow = liveRunning || isSending || (sessions.find { it.sessionId == sid }?.running == true)
            if (busyNow && lastStreamEventAt > 0 && System.currentTimeMillis() - lastStreamEventAt > 12_000) {
                Log.w("WorkspaceActivity", "stream watchdog: running but silent ${System.currentTimeMillis() - lastStreamEventAt}ms sid=${sid.take(8)}, forcing resync")
                lastStreamEventAt = System.currentTimeMillis()
                streamClientRef.pauseForResync()
                refreshMessages(autoScroll = true)
                continue
            }
            // 执行中仍刷新会话列表（running 标记）；history 交给 SSE，避免盖掉在途块
            if (liveRunning || isSending) {
                refreshSessions(reportFailure = false)
                continue
            }
            val prevUpdated = sessions.find { it.sessionId == sid }?.updatedAt ?: 0L
            // 探针在 VM 内单飞：在途时跳过本拍，慢链路不会排队
            workspaceViewModel.probeSessions(sid) { snapshot ->
                if (currentSessionId != sid) return@probeSessions
                val selectedSessionId = applySessionSnapshot(snapshot)
                if (selectedSessionId != sid) return@probeSessions
                val nextUpdated = snapshot.sessions.find { it.sessionId == sid }?.updatedAt ?: 0L
                if (nextUpdated > prevUpdated && !liveRunning && !isSending) {
                    refreshMessages(autoScroll = true)
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
        resolveHarnessLabel(agentPresets, activeHarnessPresetId, appSettings.agentPreset, L.defaultHarnessPreset)
    }
    // 输入条模型座内容（名称 + 推理等级拆开，DSH ModelSelect 同构）；
    // 新会话看 pendingModel，已开聊只看该会话的目录（pending 不得跨会话泄到已有会话上）。
    val inputModelSeat = remember(pendingModel, modelCatalog, currentSessionId) {
        composerModelSeat(modelCatalog, pendingModel.takeIf { currentSessionId == null })
    }
    val activeSubagentCount = remember(sessions, currentSessionId, currentSession) {
        resolveActiveSubagentCount(sessions, currentSessionId, currentSession?.subagentCount)
    }

    // 会话结束通知（仅后台；正常完成 → 任务完成，非正常 → 已停止）
    var wasRunning by remember { mutableStateOf(false) }
    LaunchedEffect(running) {
        val sid = currentSessionId
        if (!running && wasRunning && !isForeground && sid != null) {
            val title = currentSession?.title ?: L.sessionFallbackTitle
            val failReason = parseStoppedReason(stoppedReason)
            if (failReason == null) {
                DshNotifier.notifyTaskDone(context, host, sid, title)
            } else {
                DshNotifier.notifyTaskFailed(context, host, sid, title, failReason)
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
    // 抽屉宽度：封顶 264dp 且不超过容器宽 - 96dp（留出足够的看到主区的边缘），
    // 300dp 在窄屏手机上占比过大，会明显挤压主区视线。
    // 用实际窗口容器宽度（LocalWindowInfo）而不是设备屏幕宽度：
    // 分屏、自由窗口和折叠屏下 screenWidthDp 会失真。
    val windowInfo = androidx.compose.ui.platform.LocalWindowInfo.current
    val windowDensity = androidx.compose.ui.platform.LocalDensity.current
    val containerWidthDp = with(windowDensity) { windowInfo.containerSize.width.toDp() }
    val containerHeightDp = with(windowDensity) { windowInfo.containerSize.height.toDp() }
    // 抽屉宽度对齐 M3：上限 340dp；手机取 85% 宽（264dp 偏窄，读起来像网页侧栏）
    val sidebarWidth = minOf(340.dp, containerWidthDp * 0.85f).coerceAtLeast(240.dp)
    // 自适应外壳：手机模态抽屉；Medium/Expanded 侧栏常驻（纯函数 DshLayout 推导）
    val dshLayout = remember(containerWidthDp, containerHeightDp) {
        deriveDshLayout(containerWidthDp.value.toInt(), containerHeightDp.value.toInt())
    }
    var sidebarCollapsed by remember { mutableStateOf(false) }
    // 归档撤销 Snackbar + 下拉刷新/输入区测量状态。
    // Snackbar 宿主在根 Box（DshAdaptiveShell 之外）——抽屉打开时仍可见可点（遮罩之上）；
    // 底部让位用输入区实际 top 计算，键盘弹起时自动跟随。
    val snackbarHostState = remember { SnackbarHostState() }
    var rootHeightPx by remember { mutableStateOf(0) }
    var composerTopPx by remember { mutableStateOf(-1f) }
    var historyRefreshing by remember { mutableStateOf(false) }

    fun showArchiveUndo(sessionId: String) {
        scope.launch {
            val result = snackbarHostState.showSnackbar(
                message = L.sessionArchivedToast,
                actionLabel = L.undoAction,
            )
            if (result == SnackbarResult.ActionPerformed) {
                // 服务端无 unarchive：走设置页同一套本地恢复通路
                //（restoredSessionIds 会豁免 archivedSessionIds 同步过滤，见 applySessionSnapshot）
                localStore.restoreSession(sessionId)
                refreshSessions()
            }
        }
    }

    val sidebarActions = WorkspaceSidebarActions(
        onOpenDevice = { onOpenDevice(null) },
        onNewSession = {
            scope.launch {
                startComposeSession(null)
                drawerState.close()
            }
        },
        onSelectSession = { sid ->
            selectSession(sid)
            scope.launch { drawerState.close() }
        },
        onRenameSession = { openRename(it) },
        onArchiveSession = { session ->
            archiveSessionNow(session) { err ->
                if (err == null) {
                    showArchiveUndo(session.sessionId)
                } else {
                    sessionsLoadError = err
                }
            }
        },
        onDeleteSession = { openDeleteSession(it) },
        onForkSession = { forkNow(it, closeDrawer = true) },
        onCreateSessionIn = { createSessionIn(it) },
        onDeleteWorkspace = { openDeleteWorkspace(it) },
        onToggleWorkspaceExpanded = { cwd ->
            expandedWorkspaces = if (cwd in expandedWorkspaces) expandedWorkspaces - cwd else expandedWorkspaces + cwd
        },
        onExpandGroup = { expandedGroups = expandedGroups + it },
        onToggleSearch = {
            sidebarSearchOpen = !sidebarSearchOpen
            if (!sidebarSearchOpen && searchQuery.isBlank()) {
                searchResults = emptyList()
                searchState = SearchUiState.Idle
            }
        },
        onSearchQueryChange = { q ->
            searchQuery = q
            localStore.historyQuery = q
            runSearchDebounced(q)
        },
        onClearSearch = {
            searchQuery = ""
            localStore.historyQuery = ""
            searchResults = emptyList()
            searchState = SearchUiState.Idle
        },
        onRetrySearch = { runSearchDebounced(searchQuery) },
        onRetrySessions = { refreshSessions() },
        onOpenFilterSheet = { showSessionFilterSheet = true },
        onAddWorkspace = { showAddWorkspace = true },
        onOpenSettings = onOpenSettings,
    )

    // 根容器：承载抽屉框架与置顶 Snackbar
    Box(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { rootHeightPx = it.size.height },
    ) {
    DshAdaptiveShell(
        layout = dshLayout,
        drawerState = drawerState,
        compactDrawerWidth = sidebarWidth,
        sidebarCollapsed = sidebarCollapsed,
        rail = {
            WorkspaceNavigationRail(
                hostName = host.name,
                onOpenSessionList = { scope.launch { drawerState.open() } },
                onNewSession = {
                    scope.launch {
                        startComposeSession(null)
                        drawerState.close()
                    }
                },
                onOpenDevice = { onOpenDevice(null) },
                onOpenSettings = onOpenSettings,
            )
        },
        sidebar = {
            WorkspaceSidebar(
                sessions = sessions,
                archivedIds = archivedIds,
                deletedIds = deletedIds,
                currentSessionId = currentSessionId,
                searchQuery = searchQuery,
                searchState = searchState,
                searchResults = searchResults,
                sidebarSearchOpen = sidebarSearchOpen,
                sessionFilter = sessionFilter,
                workspaceAccounts = workspaceAccounts,
                deletedWorkspaces = deletedWorkspaces,
                workspaceRegistry = workspaceRegistry,
                workspaceRegistryReady = workspaceRegistryReady,
                sessionsInitialLoad = sessionsInitialLoad,
                sessionsLoadError = sessionsLoadError,
                expandedWorkspaces = expandedWorkspaces,
                expandedGroups = expandedGroups,
                hostName = host.name,
                collapsed = sidebarCollapsed,
                goalSummaries = workspaceViewModel.goalSummaries.value,
                actions = sidebarActions,
            )
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Dsh.bgBase)
                .statusBarsPadding()
        ) {
            // ===== 顶栏（单行：≡ 侧栏 + 标题 + 模式分段 + 更多） =====
            var headerMenuOpen by remember { mutableStateOf(false) }
            val shareDark = Dsh.isDark
            // 跳转轮次从悬浮按钮移到这里：≥3 轮才出现，不占输入区视觉重量
            val turnJumpsForMenu = remember(olderMessages, messages) {
                userTurnJumps(mergeHistoryPages(olderMessages, messages))
            }
            val topBarMenuItems = workspaceHeaderMenuItems(
                            viewMode = viewMode,
                            toolSearchOpen = toolSearchOpen,
                            activeSubagentCount = activeSubagentCount,
                            turnJumpCount = turnJumpsForMenu.size,
                            onCloseMenu = { headerMenuOpen = false },
                            onOpenToolSearch = {
                                toolSearchOpen = !toolSearchOpen
                                if (!toolSearchOpen) toolQuery = ""
                            },
                            onShowSubagents = { showSubagentSheet = true },
                            onShowTurnJump = { showTurnJumpSheet = true },
                            onRename = { currentSession?.let { openRename(it) } },
                            onFork = { currentSessionId?.let { forkNow(it) } },
                            onCopyTitle = {
                                val title = currentSession?.title
                                if (title != null) {
                                    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("session title", title))
                                    if (copiedNeedsAppToast(android.os.Build.VERSION.SDK_INT)) {
                                        Toast.makeText(context, L.copied, Toast.LENGTH_SHORT).show()
                                    }
                                }
                            },
                            onArchive = {
                                currentSession?.let { session ->
                                    archiveSessionNow(session) { err ->
                                        if (err == null) {
                                            showArchiveUndo(session.sessionId)
                                        } else {
                                            sessionsLoadError = err
                                        }
                                    }
                                }
                            },
                            onShareImage = {
                                val sid = currentSessionId
                                if (sid != null) {
                                    val title = currentSession?.title ?: L.sessionFallbackTitle
                                    val dark = shareDark
                                    scope.launch(Dispatchers.IO) {
                                        try {
                                            val turns = selectShareTurns(loadSessionMessagesForExport(client, sid))
                                            withContext(Dispatchers.Main) {
                                                if (turns.isEmpty()) {
                                                    composerActionError = L.shareConversationEmpty
                                                } else {
                                                    val bmp = ShareCardRenderer.render(title, turns, dark, "DeepLinks")
                                                    try {
                                                        ShareCardRenderer.sharePng(context, bmp, title, L.shareConversationImage)
                                                    } finally {
                                                        bmp.recycle()
                                                    }
                                                }
                                            }
                                        } catch (e: Exception) {
                                            withContext(Dispatchers.Main) {
                                                composerActionError = L.exportFailed.format(e.message ?: L.unknownError)
                                            }
                                        }
                                    }
                                }
                            },
                            onExport = {
                                val sid = currentSessionId
                                if (sid != null) {
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
                                                composerActionError = L.exportFailed.format(e.message ?: L.unknownError)
                                            }
                                        }
                                    }
                                }
                            },
                            onDelete = { currentSession?.let { openDeleteSession(it) } },
            )
            WorkspaceTopBar(
                running = running,
                title = currentSession?.title ?: L.newSession,
                onOpenDrawer = {
                    if (dshLayout.persistentSidebar) {
                        sidebarCollapsed = !sidebarCollapsed
                    } else {
                        scope.launch { drawerState.open() }
                    }
                },
                viewMode = viewMode,
                onSelectViewMode = { selectViewMode(it) },
                menuExpanded = headerMenuOpen,
                onMenuExpandedChange = { headerMenuOpen = it },
                menuItems = topBarMenuItems,
                latestChanges = changeSummaries.firstOrNull(),
                onOpenChanges = { scope.launch { changesPanel.open() } },
                goalSummary = workspaceViewModel.currentGoalSummary.value,
            )

            // ===== 设备不可达横幅：离线时不强退到设备页，给「重试 / 设备」 =====
            DeviceUnreachableBanner(
                hostName = host.name,
                visible = !sessionsInitialLoad && sessionsLoadError != null && sessions.isEmpty(),
                onRetry = { refreshSessions(reportFailure = true) },
                onOpenDevice = { onOpenDevice(null) },
            )

            // ===== 断线重连横幅（SSE 断开时提示；客户端自动退避重连；复用 DshBanner） =====
            val streamState = streamClient?.connectionState
            val streamBanner = streamBannerKind(
                hasSession = currentSessionId != null,
                connected = streamState == SessionStreamClient.ConnectionState.CONNECTED,
                failed = streamState == SessionStreamClient.ConnectionState.FAILURE,
                connecting = streamState == SessionStreamClient.ConnectionState.CONNECTING,
                retrying = streamState == SessionStreamClient.ConnectionState.RETRYING,
                everConnected = streamEverConnected,
                quietElapsed = streamQuietElapsed,
            )
            StreamReconnectBanner(
                kind = streamBanner,
                onRetry = { streamClient?.reconnect() },
            )

            // 工具调用查找条（chat 视图；客户端过滤，瞬时状态不持久化）
            // 已抽为独立 composable（COM-001 拆解）：位于视图区上方、Column 直子级
            ToolSearchBar(
                visible = toolSearchOpen && viewMode == "chat",
                query = toolQuery,
                onQueryChange = { toolQuery = it },
            )

            // 消息流 + 悬浮「回到底部」：weight 加在容器（Column 直接子级）上，
            // 悬浮按钮盖在列表之上；框内 LazyColumn 用 fillMaxSize 填满 Box。
            // 下拉刷新（M3 PullToRefreshBox）：聊天/轨迹共用同一消息流数据。
            PullToRefreshBox(
                isRefreshing = historyRefreshing,
                onRefresh = {
                    historyRefreshing = true
                    refreshMessages(replaceInFlight = true, onDone = { historyRefreshing = false })
                },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
            // 对话与轨迹的内容直接切换；视觉反馈只留在顶栏短下划线，
            // 避免两张完整长列表在一次点按中同时测量、绘制和滑动。
            ChangesSwipeArea(
                state = changesPanel,
                enabled = changeSummaries.isNotEmpty(),
                // 大屏（Medium / Expanded）把消息流封顶 760dp 居中，
                // 而不是把手机布局无限拉宽；手机仍是全宽 16dp 边距。
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .widthIn(max = dshLayout.contentMaxWidthDp.dp)
                    .fillMaxHeight()
                    .fillMaxWidth(),
            ) {
            when (viewMode) {
            "trace" -> {
                val traceKind = chatCanvasKind(
                    hasMessages = messages.isNotEmpty(),
                    initialLoadInFlight = initialLoadInFlight && sessionStats == null,
                    hasHistoryError = historyLoadError != null,
                    working = running || isSending,
                )
                when (traceKind) {
                    ChatCanvasKind.Loading -> ChatLoadingSkeleton(modifier = Modifier.fillMaxSize())
                    ChatCanvasKind.Error -> Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        ChatHistoryError(
                            message = historyLoadError ?: L.loadConversationFailed,
                            onRetry = { refreshMessages() },
                        )
                    }
                    ChatCanvasKind.Working -> Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        ThinkingStatusRow(elapsedSec)
                    }
                    ChatCanvasKind.Empty, ChatCanvasKind.Content -> TrajectoryView(
                        messages = messages,
                        running = running,
                        elapsedSec = elapsedSec,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            else -> {

            // ===== 消息流（max-width 748 居中） =====
            // 消息流数据推导（纯函数，见 ChatFeedDerivation.kt）：
            // 合并历史页 → 丢弃空思考行 → 按相邻工具调用聚合 → 工具查找过滤
            val chatFeed = remember(olderMessages, messages, toolQuery) {
                deriveChatFeed(olderMessages, messages, toolQuery)
            }
            val visibleGroups = chatFeed.visibleGroups
            // 消息渲染的副作用集合（COM-001 拆解）：状态用 getter/setter 注入，调用时读最新值
            val chatActions = remember(client, host, context) {
                ChatFeedActions(
                    client = client,
                    scope = scope,
                    context = context,
                    host = host,
                    currentSessionId = { currentSessionId },
                    messages = { messages },
                    setMessages = { messages = it },
                    olderMessages = { olderMessages },
                    composerText = { inputText },
                    setComposerText = { inputText = it },
                    setComposerError = { composerActionError = it },
                    isRunning = { running },
                    busyEnter = { appSettings.busyEnter },
                    isFeedbackSupported = { feedbackSupported },
                    feedbackFor = { id -> messageFeedback[id] },
                    updateFeedback = { transform -> messageFeedback = transform(messageFeedback) },
                    refreshSessions = { refreshSessions() },
                    fork = { sid -> forkNow(sid) },
                    openChanges = { seq, fileIndex -> scope.launch { changesPanel.open(seq, fileIndex) } },
                )
            }
            // 列表高度随 IME/底栏变化时：贴底用户按变矮像素上推，跟手不跳
            var chatListHeightPx by remember { mutableIntStateOf(0) }
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .onSizeChanged { size ->
                        val h = size.height
                        val prev = chatListHeightPx
                        if (prev > 0 && h < prev && stickToBottom && messages.isNotEmpty()) {
                            val delta = (prev - h).toFloat()
                            scope.launch {
                                try {
                                    listState.scrollBy(delta)
                                } catch (_: Exception) {
                                }
                            }
                        }
                        chatListHeightPx = h
                    },
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(
                    horizontal = COMPOSER_SIDE_CLEARANCE + 8.dp,
                    vertical = 10.dp
                )
            ) {
                if (messages.isEmpty()) {
                    chatEmptyCanvas(
                        kind = chatCanvasKind(
                            hasMessages = false,
                            initialLoadInFlight = initialLoadInFlight && sessionStats == null,
                            hasHistoryError = historyLoadError != null,
                            working = running || isSending,
                        ),
                        elapsedSec = elapsedSec,
                        historyLoadError = historyLoadError,
                        onRetry = { refreshMessages() },
                    )
            } else { // 闭合 if (messages.isEmpty())，打开 else 分支
                    // 会话标题只保留在顶栏；消息流不再重复大标题
                    // 加载更早（DSH chat.loadOlder：hasMore 时显示在消息流顶部，点击向前翻页）
                    if (hasMoreMessages) {
                        item(key = "load-older") {
                            LoadOlderRow(
                                loading = isLoadingOlder,
                                failed = loadOlderFailed,
                                onClick = { loadOlderMessages() }
                            )
                        }
                    }
                    // 只扫正在生成的工具/思考行；已定稿的「已思考」不能抢状态条
                    val sweepingId = resolveSweepingId(messages, running)
                    // 稳定列表引用（内容不变时避免 LazyColumn 滚动状态失效）
                    val messagesForSummary = messages
                    chatMessageItems(
                        visibleGroups = visibleGroups,
                        sweepingId = sweepingId,
                        toolQuery = toolQuery,
                        actions = chatActions,
                        goalSummary = latestGoalSummary(messagesForSummary),
                        todoProgress = latestTodoProgress(messagesForSummary),
                        isRunning = running,
                    )
                    // 对齐网页 TurnStatus（Deep diving...）：整轮生成期间都在流尾显示思考中扫光
                    if (running || isSending) {
                        item(key = "turn-status") {
                            ThinkingStatusRow(elapsedSec)
                        }
                    }
                    // 已停止标记：仅在非执行态展示，避免等待新回复时误显示旧 turn 的停止态
                    val badgeReason = parseStoppedReason(stoppedReason)
                    if (badgeReason != null && !running && !isSending) {
                        item(key = "stopped-badge") {
                            StoppedBadge(reason = badgeReason)
                        }
                    }
                }
            }

            } // else 分支结束
            } // when(viewMode) 结束
            } // Box 结束（viewMode 容器）

            // 回到底部：悬浮在消息流右下角（原生聊天 App 的位置），不挤压输入区
            androidx.compose.animation.AnimatedVisibility(
                visible = showScrollToBottom && currentSessionId != null && messages.isNotEmpty(),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = COMPOSER_SIDE_CLEARANCE, bottom = 12.dp),
                enter = fadeIn(animationSpec = tween(motionDuration(150))) +
                    scaleIn(animationSpec = tween(motionDuration(180))),
                exit = fadeOut(animationSpec = tween(motionDuration(120))) +
                    scaleOut(animationSpec = tween(motionDuration(160))),
            ) {
                ScrollToBottomButton(
                    unread = unreadWhileScrolled,
                    onClick = {
                        showScrollToBottom = false
                        stickToBottom = true
                        requestTailPosition()
                    },
                )
            }
            } // Box 结束（消息流 + 悬浮层）

            // 命令候选（输入以 / 开头时，DSH 命令/技能/子智能体/快捷操作）—— 悬浮在输入区上方
            // typed 处理：
            //   Completable  → 直接提交到服务端并清空输入框
            //   Insertable   → 把 trigger 插入到 composer（例如 /plan /goal /subagent 待补参数）
            //   Local        → 本地动作（/search 等）；绝不到达服务端
            // 外部分享带入的 `/…` 文本在用户手动编辑前不参与候选（见 shareCommandGuard）。
            val commandModeActive = inputText.startsWith("/") &&
                inputText.length <= 24 &&
                (shareCommandGuard == null || inputText != shareCommandGuard)
            if (viewMode == "chat" && commandModeActive) {
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
                                val sid = currentSessionId
                                if (!completableCanSubmit(sid != null) || sid == null) return@CommandSuggestions
                                val trigger = picked.trigger
                                inputText = ""
                                composerActionError = null
                                scope.launch(Dispatchers.IO) {
                                    try {
                                        client.sendPrompt(
                                            sid,
                                            trigger,
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
                                            inputText = trigger
                                            composerActionError = L.commandSendFailed.format(e.message ?: L.unknownError)
                                        }
                                    }
                                }
                            }
                        }
                    }
                )
            }
            // ===== bottom chrome（WI-006：发送队列/输入卡/统计栏同一容器，统一安全区与 IME） =====
            // 输入卡带 8dp 阴影悬浮，底部留 10dp 让影子完整落在手势条上方。
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(bottom = 10.dp)
                    // 键盘弹起时输入区上移，Snackbar 底部让位随之跟随
                    .onGloballyPositioned { composerTopPx = it.positionInRoot().y }
            ) {
                // 工作区 + Harness 模式（对标 web，置于输入卡上方）
                // 新会话：模式仅在此处；已开聊：模式移到顶部「历史」旁，此处只保留工作区
                if (viewMode == "chat") {
                    ComposerTopRow(
                        sessions = sessions,
                        deletedWorkspaces = deletedWorkspaces,
                        registeredPaths = workspaceRegistry,
                        registryReady = workspaceRegistryReady,
                        currentCwd = if (currentSessionId == null) {
                            pendingSessionCwd?.takeUnless { it in deletedWorkspaces }
                        } else {
                            currentSessionId?.let {
                                workspaceGroupKey(it, workspaceAccounts, deletedWorkspaces)
                            }
                        },
                        lastCwd = if (currentSessionId == null) {
                            workspacePrefs.lastSelectedWorkspace
                                ?.takeUnless { it in deletedWorkspaces }
                        } else {
                            null
                        },
                        harnessLabel = harnessLabel,
                        showHarness = currentSessionId == null,
                        workspaceEditable = currentSessionId == null,
                        harnessEditable = currentSessionId == null,
                        workspaceCatalogKind = catalogKind(
                            hasItems = workspaceCatalogItems.isNotEmpty(),
                            initialLoad = workspacesInitialLoad,
                            hasError = workspacesLoadError != null,
                        ),
                        workspaceCatalogError = workspacesLoadError,
                        onRetryWorkspaces = { refreshWorkspaces() },
                        onOpenHarnessPicker = if (currentSessionId == null) {
                            {
                                showAgentPresetPicker = true
                                if (agentPresets.isEmpty()) loadAgentPresets()
                            }
                        } else {
                            null
                        },
                        onStartSession = { cwd ->
                            pendingSessionCwd = cwd
                            if (cwd != null) workspacePrefs.lastSelectedWorkspace = cwd
                        },
                        // 「回到底部」已改为消息流内的悬浮按钮（不再占用输入区上方整行），
                        // 所以这里不再有 trailing 内容。
                        trailingContent = {},
                    )
                }
                // 发送中不堆 QueueDock；插话/引导/排队由发送槽转圈表示（Grok：状态写进动作）
                if (viewMode == "chat") {
                // 发送主体与高权限确认的共享状态：submitComposer 在 InputBar 之后赋值，
                // onSend 与确认弹窗都通过同一个可变引用复用同一条发送路径。
                var submitComposer: () -> Unit = {}
                var showFullAccessSendConfirm by remember { mutableStateOf(false) }
                // 访问模式座（DSH conversation.input.permission）：本会话改过的预设优先，否则用全局默认。
                val inputPermissionPreset = composerPermissionPreset(currentSessionId, sessionPermissionOverrides, appSettings.permissionPreset).let(::canonicalComposerPermission)
                val inputPermissionLabel = when (inputPermissionPreset) {
                    "read-only" -> L.permReadOnly
                    "danger-full-access" -> L.permFullAccess
                    else -> L.permWorkspaceWrite
                }
                InputBar(
                inputText = inputText,
                onInputChange = {
                    inputText = it
                    if (composerActionError != null) composerActionError = null
                },
                pendingImages = pendingImages,
                onRemoveImage = { index ->
                    pendingImages = pendingImages.filterIndexed { i, _ -> i != index }
                    if (composerActionError != null) composerActionError = null
                },
                onPickImage = {
                    pendingAttachOwnerKey = composerOwnerKey()
                    imagePickerLauncher.launch(
                        androidx.activity.result.PickVisualMediaRequest(
                            ActivityResultContracts.PickVisualMedia.ImageOnly,
                        )
                    )
                },
                onTakePhoto = { launchCamera() },
                isListening = isListening,
                isSending = isSending,
                canSend = (inputText.isNotBlank() || pendingImages.isNotEmpty()) && !isSending,
                running = running,
                modelName = inputModelSeat.name,
                modelEffort = inputModelSeat.effort,
                sessionStats = sessionStats,
                permissionPreset = inputPermissionPreset,
                permissionLabel = inputPermissionLabel,
                compact = composerSeatsCompact(containerWidthDp.value),
                onOpenModelPicker = { loadModelCatalog(openPicker = true) },
                onOpenPermissionPicker = { showPermissionPicker = true },
                onToggleVoice = {
                    if (isListening) {
                        onStopVoiceInput()
                        isListening = false
                    } else {
                        composerActionError = null
                        isListening = true
                        val voiceOwnerKey = composerOwnerKey()
                        onStartVoiceInput({ recognizedText ->
                            isListening = false
                            if (recognizedText.isNotBlank()) {
                                if (composerOwnerKey() == voiceOwnerKey) {
                                    applyLiveComposer(mergeComposerText(liveComposerDraft(), recognizedText))
                                } else {
                                    val stored = composerDrafts[voiceOwnerKey] ?: ComposerDraft()
                                    composerDrafts = putComposerDraft(
                                        composerDrafts,
                                        voiceOwnerKey,
                                        mergeComposerText(stored, recognizedText),
                                    )
                                }
                            }
                        }, { isListening = false }, { message ->
                            isListening = false
                            reportComposerOwnerError(voiceOwnerKey, message)
                        })
                    }
                },
                onStop = {
                    val sid = currentSessionId ?: return@InputBar
                    composerActionError = null
                    scope.launch(Dispatchers.IO) {
                        try {
                            client.cancelSession(sid)
                            refreshSessions()
                        } catch (e: Exception) {
                            withContext(Dispatchers.Main) {
                                composerActionError = L.stopFailed.format(e.message ?: L.unknownError)
                            }
                        }
                    }
                },
                actionError = composerActionError,
                composerFocusRequester = composerFocusRequester,
                onSend = {
                    // 不可逆权限升级：无论从哪个入口触发，都不直发，先走二次确认。
                    if (isDangerPermissionCommand(inputText)) {
                        showFullAccessSendConfirm = true
                    } else {
                        submitComposer()
                    }
                },
            )

            // 发送主体（原 onSend 内联体）：抽成局部函数，让高权限确认弹窗能复用同一条路径。
            submitComposer = {
                val rawText = inputText.trim()
                    val images = pendingImages
                    if ((rawText.isNotBlank() || images.isNotEmpty()) && !isSending) {
                        // DSH 里 plan / goal 是 `/plan` `/goal` 命令（命令面板里可选），
                        // 不是输入条上的模式开关，所以这里不再自动加前缀。
                        val textToSend = rawText
                        val startedOnSession = currentSessionId
                        var sendOwnerId = startedOnSession
                        val createCwd = pendingSessionCwd
                        val createWsId = workspaceCatalogItems.firstOrNull {
                            normalizeWorkspacePath(it.path) == createCwd?.let(::normalizeWorkspacePath) &&
                                it.workspaceId.isNotBlank()
                        }?.workspaceId
                        val draftText = inputText
                        val draftImages = pendingImages
                        workspacePrefs.parkedSend = parkSendForPersistence(
                            ParkedSend(
                                slotKey = host.slotKey,
                                sessionId = startedOnSession,
                                text = draftText,
                                images = draftImages,
                            ),
                        )
                        composerActionError = null
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
                            // 用户主动发送：强制贴底，不看是否已上翻
                            followIfNearBottom(force = true)
                        }
                        scope.launch(Dispatchers.IO) {
                            try {
                                var sid = startedOnSession
                                val createdNow = sid == null
                                if (sid == null) {
                                    sid = client.createSession(
                                        agentPreset = pendingAgentPreset,
                                        cwd = createCwd,
                                        workspaceId = createWsId,
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
                                }
                                if (sid != startedOnSession) {
                                    sendOwnerId = sid
                                    workspacePrefs.parkedSend = parkSendForPersistence(
                                        ParkedSend(
                                            slotKey = host.slotKey,
                                            sessionId = sid,
                                            text = draftText,
                                            images = draftImages,
                                        ),
                                    )
                                }
                                val stillFocused = withContext(Dispatchers.Main) {
                                    if (createdNow) {
                                        // 创建期间用户已切到其它会话：不抢焦点、不发 prompt
                                        if (currentSessionId != null && currentSessionId != sid) {
                                            false
                                        } else {
                                            val createdId = sid
                                            preserveMessagesSessionId = createdId
                                            switchComposer(createdId, composingNew = false)
                                            if (!createWsId.isNullOrBlank()) {
                                                workspaceCatalogItems = workspaceCatalogItems.map { ws ->
                                                    if (ws.workspaceId == createWsId && createdId !in ws.sessionIds) {
                                                        ws.copy(sessionIds = ws.sessionIds + createdId)
                                                    } else ws
                                                }
                                            }
                                            refreshSessions()
                                            refreshWorkspaces()
                                            refreshModels()
                                            true
                                        }
                                    } else {
                                        currentSessionId == sid
                                    }
                                }
                                if (!stillFocused) {
                                    withContext(Dispatchers.Main) {
                                        messages = messages.filterNot { it.id == "local-pending" }
                                        liveRunning = sessions.any { it.sessionId == currentSessionId && it.running }
                                        restoreComposerToOwner(composerDraftKey(sid), ComposerDraft(draftText, draftImages))
                                        composerActionError = L.sendFailed.format(L.switchedSessionNotSent)
                                    }
                                    return@launch
                                }
                                val promptMode = resolvePromptMode(!createdNow && running, appSettings.busyEnter)
                                client.sendPrompt(sid, textToSend, mode = promptMode, images = images)
                                withContext(Dispatchers.Main) {
                                    refreshSessions()
                                    // SSE 已连接时由流增量更新；立刻全量 refresh 容易在服务端
                                    // 尚未写入 history 时冲掉 local-pending，造成「已发送但本机空白」
                                    if (streamClient?.isConnected != true) {
                                        refreshMessages(autoScroll = true)
                                    } else {
                                        followIfNearBottom(force = true)
                                    }
                                }
                            } catch (e: Exception) {
                                withContext(Dispatchers.Main) {
                                    messages = messages.filterNot { it.id == "local-pending" }
                                    restoreComposerToOwner(
                                        composerDraftKey(sendOwnerId),
                                        ComposerDraft(draftText, draftImages),
                                    )
                                    // 发送失败：若尚未真正进入 turn，收回乐观 running
                                    if (currentSession?.running != true) liveRunning = false
                                    composerActionError = L.sendFailed.format(e.message ?: L.unknownError)
                                }
                            } finally {
                                withContext(Dispatchers.Main) {
                                    workspacePrefs.parkedSend = null
                                    isSending = false
                                }
                            }
                        }
                    }
                }

            // 高权限命令的二次确认（文案与 PermissionPickerSheet 的确认弹窗一致）。
            // 取消后 composer 保留原文；确认后只执行一次发送。
            if (showFullAccessSendConfirm) {
                DshConfirmDialog(
                    title = L.confirmFullAccessTitle,
                    message = L.confirmFullAccessMessage,
                    confirmLabel = L.confirm,
                    danger = true,
                    onDismiss = { showFullAccessSendConfirm = false },
                    onConfirm = {
                        showFullAccessSendConfirm = false
                        submitComposer()
                    },
                )
            }

            // 会话级统计：写在输入卡下方（会话下方）的单行 12sp 居中文本。
            // 无论浏览对话还是轨迹，累计用量都只在这一处出现；单条回答只保留自身耗时。
            if (currentSessionId != null) {
                SessionStatsLine(stats = sessionStats)
            }
                }

            } // bottom chrome 容器结束
        }
    }

    WorkspaceChangesPanel(
        state = changesPanel,
        summaries = changeSummaries,
        loadSummary = { seq -> currentSessionId?.let { sid -> withContext(Dispatchers.IO) { client.getWorkspaceChanges(sid, seq) } } },
        loadDiff = { seq, index -> client.getWorkspaceChangeDiff(currentSessionId ?: error(L.unknownError), seq, index) },
    )

    // Snackbar 叠在抽屉/遮罩之上（抽屉打开时仍可见可点）；底部让开输入区：
    // 间距 = 根高 - 输入区 top，键盘弹起时 composerTopPx 上移自动收窄。
    val clearancePx = if (composerTopPx >= 0f) (rootHeightPx - composerTopPx).coerceAtLeast(0f) else 0f
    val density = androidx.compose.ui.platform.LocalDensity.current
    SnackbarHost(
        hostState = snackbarHostState,
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(horizontal = 16.dp)
            .padding(bottom = with(density) { clearancePx.toDp() } + 16.dp),
    )
    }

    cropBitmap?.let { pendingCrop ->
        CameraCropSheet(
            bitmap = pendingCrop,
            onConfirm = { cropped ->
                cropBitmap = null
                if (pendingCrop !== cropped && !pendingCrop.isRecycled) pendingCrop.recycle()
                addPendingBitmap(cropped)
            },
            onRetake = {
                cropBitmap = null
                if (!pendingCrop.isRecycled) pendingCrop.recycle()
                launchCamera()
            },
            onDismiss = {
                cropBitmap = null
                if (!pendingCrop.isRecycled) pendingCrop.recycle()
            },
        )
    }

    // 模型选择底部抽屉
    if (showModelPicker) {
        ModelPickerSheet(
            catalog = modelCatalog,
            loading = modelCatalogLoading,
            error = modelCatalogError,
            onRetry = { loadModelCatalog(openPicker = true) },
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
                        withContext(Dispatchers.Main) {
                            modelCatalogError = null
                            refreshModels()
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            modelCatalogError = L.switchModelFailed.format(friendlySelectModelError(e.message))
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
            loading = agentPresetsLoading,
            error = agentPresetsError,
            onRetry = { loadAgentPresets() },
            onDismiss = { showAgentPresetPicker = false },
            onSelect = { id ->
                pendingAgentPreset = id
                showAgentPresetPicker = false
            },
        )
    }

    if (showSessionFilterSheet) {
        val filterData = remember(sessions, archivedIds, deletedIds, showSessionFilterSheet) {
            buildSessionFilterData(
                sessions = sessions,
                archivedIds = archivedIds,
                deletedIds = deletedIds,
                nowMillis = System.currentTimeMillis(),
            )
        }
        SessionFilterSheet(
            selected = sessionFilter,
            counts = filterData.counts,
            onSelect = { sessionFilter = it },
            onDismiss = { showSessionFilterSheet = false },
        )
    }

    // 访问模式选择（有会话 → 改当前会话；无会话 → 写全局默认）
    if (showPermissionPicker) {
        PermissionPickerSheet(
            context = context,
            host = host,
            currentPreset = composerPermissionPreset(
                currentSessionId,
                sessionPermissionOverrides,
                appSettings.permissionPreset,
            ),
            sessionId = currentSessionId,
            onSaved = { updated -> appSettings = updated },
            onSessionPreset = { preset ->
                val sid = currentSessionId ?: return@PermissionPickerSheet
                sessionPermissionOverrides = sessionPermissionOverrides + (sid to preset)
            },
            onDismiss = { showPermissionPicker = false }
        )
    }

    // 添加工作区：名称创建同级目录；绝对路径注册已有目录
    if (showAddWorkspace) {
        val sid = currentSessionId
        val preferredAnchorPath = if (sid == null) {
            pendingSessionCwd ?: workspacePrefs.lastSelectedWorkspace
        } else {
            workspaceGroupKey(sid, workspaceAccounts, deletedWorkspaces)
        }
        val creationAnchor = workspaceCreationAnchor(workspaceCatalogItems, preferredAnchorPath)
        AddWorkspaceSheet(
            creationAnchor = creationAnchor,
            onDismiss = { showAddWorkspace = false },
            createWorkspace = client::createWorkspace,
            onAuthExpired = { error -> onAuthExpired(error) },
            onCreated = { workspace ->
                // A workspace.list started before this commit may carry a stale snapshot.
                workspaceViewModel.invalidateWorkspaceRequests()
                workspaceCatalogItems = upsertCreatedWorkspace(workspaceCatalogItems, workspace)
                workspaceRegistryReady = true
                val committedPath = normalizeWorkspacePath(workspace.path)
                persistDeletedWorkspaces(deletedWorkspaces - committedPath)
                expandedWorkspaces = expandedWorkspaces + committedPath
                workspacePrefs.lastSelectedWorkspace = committedPath
                startComposeSession(committedPath)
                selectViewMode("chat")
                showAddWorkspace = false
                refreshWorkspaces()
                refreshSessions()
                scope.launch { drawerState.close() }
            },
        )
    }

    // 会话重命名
    renameTarget?.let { target ->
        DshRenameDialog(
            currentName = target.title,
            error = renameError,
            saving = renameSaving,
            onDismiss = {
                if (!renameSaving) {
                    renameTarget = null
                    renameError = null
                }
            },
            onClearError = { renameError = null },
            onSave = { newName ->
                if (newName.isBlank() || renameSaving) return@DshRenameDialog
                renameSaving = true
                renameError = null
                scope.launch(Dispatchers.IO) {
                    try {
                        client.renameSession(target.sessionId, newName.trim())
                        withContext(Dispatchers.Main) {
                            renameTarget = null
                            renameError = null
                            refreshSessions()
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            renameError = L.renameFailed.format(e.message ?: L.unknownError)
                        }
                    } finally {
                        withContext(Dispatchers.Main) { renameSaving = false }
                    }
                }
            }
        )
    }

    // 删除工作区（取消注册；会话日志保留，不再显示在该工作区下）
    deleteWorkspaceTarget?.let { rawPath ->
        val path = normalizeWorkspacePath(rawPath)
        DshConfirmDialog(
            title = L.deleteWorkspaceTitle,
            message = L.deleteWorkspaceMessage.format(path.substringAfterLast('/')),
            confirmLabel = L.delete,
            danger = true,
            error = deleteWorkspaceError,
            saving = deleteWorkspaceSaving,
            onDismiss = {
                if (!deleteWorkspaceSaving) {
                    deleteWorkspaceTarget = null
                    deleteWorkspaceError = null
                }
            },
            onConfirm = {
                if (deleteWorkspaceSaving) return@DshConfirmDialog
                deleteWorkspaceSaving = true
                deleteWorkspaceError = null
                scope.launch(Dispatchers.IO) {
                    try {
                        client.deleteWorkspace(path)
                        withContext(Dispatchers.Main) {
                            if (localHideAfterRemote(accepted = true)) {
                                setDeletedWorkspace(path)
                                workspaceCatalogItems = workspaceCatalogItems.filter {
                                    normalizeWorkspacePath(it.path) != path
                                }
                                if (pendingSessionCwd?.let(::normalizeWorkspacePath) == path) pendingSessionCwd = null
                                if (workspacePrefs.lastSelectedWorkspace?.let(::normalizeWorkspacePath) == path) {
                                    workspacePrefs.lastSelectedWorkspace = null
                                }
                                expandedWorkspaces = expandedWorkspaces - path
                                expandedGroups = expandedGroups - path
                                refreshSessions()
                            }
                            deleteWorkspaceTarget = null
                            deleteWorkspaceError = null
                            refreshWorkspaces()
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            deleteWorkspaceError = L.deleteWorkspaceFailed.format(e.message ?: L.unknownError)
                        }
                    } finally {
                        withContext(Dispatchers.Main) { deleteWorkspaceSaving = false }
                    }
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
            error = deleteSessionError,
            saving = deleteSessionSaving,
            onDismiss = {
                if (!deleteSessionSaving) {
                    deleteSessionTarget = null
                    deleteSessionError = null
                }
            },
            onConfirm = {
                if (deleteSessionSaving) return@DshConfirmDialog
                deleteSessionSaving = true
                deleteSessionError = null
                archiveSessionNow(target, asDeleted = true) { err ->
                    deleteSessionSaving = false
                    if (err == null) {
                        deleteSessionTarget = null
                        deleteSessionError = null
                    } else {
                        deleteSessionError = err
                    }
                }
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
                Text(L.subagents, color = Dsh.labelPrimary, style = DshType.t18SB, fontWeight = FontWeight(600))
                Spacer(Modifier.height(4.dp))
                Text(
                    if (children.isEmpty()) L.noSubagentSessions else L.subagentSheetSummary.format(children.size),
                    color = Dsh.labelTertiary,
                    style = DshType.t13,
                )
                Spacer(Modifier.height(12.dp))
                children.forEach { child ->
                    val selected = child.sessionId == currentSessionId
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(DshRadius.md))
                            .background(if (selected) Dsh.bgSelected else Color.Transparent)
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
                                style = DshType.t14M,
                                fontWeight = FontWeight(500),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (child.running) {
                                Text(L.runningStatus, color = Dsh.brand400, style = DshType.t11)
                            }
                        }
                    }
                }
                if (!parentOfCurrent.isNullOrBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        L.returnToParentSession,
                        color = Dsh.brand400,
                        style = DshType.t13M,
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

    if (showTurnJumpSheet) {
        val merged = remember(olderMessages, messages) { mergeHistoryPages(olderMessages, messages) }
        val jumps = remember(merged) { userTurnJumps(merged) }
        ModalBottomSheet(
            onDismissRequest = { showTurnJumpSheet = false },
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
                Text(L.jumpToTurn, color = Dsh.labelPrimary, style = DshType.t18SB, fontWeight = FontWeight(600))
                Spacer(Modifier.height(12.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    jumps.forEachIndexed { index, jump ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .clip(RoundedCornerShape(DshRadius.md))
                                .clickable {
                                    val display = merged.filterNot {
                                        it.role == "reasoning" && it.text.isBlank() && it.running != true
                                    }
                                    val groups = groupMessages(display)
                                    val groupIndex = groups.indexOfFirst {
                                        it is MessageGroup.Single && it.msg.id == jump.messageId
                                    }
                                    val offset = if (hasMoreMessages) 1 else 0
                                    showTurnJumpSheet = false
                                    if (groupIndex >= 0) {
                                        stickToBottom = false
                                        showScrollToBottom = true
                                        scope.launch {
                                            listState.scrollToItem(offset + groupIndex)
                                        }
                                    }
                                }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "${index + 1}",
                                color = Dsh.labelTertiary,
                                style = DshType.t12,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.width(28.dp),
                            )
                            Text(
                                jump.preview,
                                color = Dsh.labelPrimary,
                                style = DshType.t14,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}
