package dev.deeplinks.native
import dev.deeplinks.core.persist
import dev.deeplinks.core.dshRipple
import dev.deeplinks.core.Dsh
import dev.deeplinks.core.DshType
import dev.deeplinks.core.Host
import dev.deeplinks.core.DshS
import dev.deeplinks.core.LocaleManager
import dev.deeplinks.core.FontScaleManager
import dev.deeplinks.core.ThemeManager
import dev.deeplinks.core.UiFontManager
import dev.deeplinks.native.MobileSession
import dev.deeplinks.native.AppSettings
import dev.deeplinks.native.MobileApiClient
import dev.deeplinks.native.util.SessionSnapshot
import dev.deeplinks.native.util.WorkspacePrefs
import dev.deeplinks.native.util.SessionListKind
import dev.deeplinks.native.util.catalogKind
import dev.deeplinks.native.util.compactTokens
import dev.deeplinks.core.AppSettingsStore
import dev.deeplinks.core.DshTheme
import dev.deeplinks.core.enableDshEdgeToEdge
import dev.deeplinks.core.HostStore
import dev.deeplinks.core.resolveFromIntent
import dev.deeplinks.devices.DevicesActivity
import dev.deeplinks.BuildConfig

import android.content.Context
import android.content.Intent
import android.os.Bundle
import kotlin.text.Charsets
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.deeplinks.core.applyDshSecureWindow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 设置页 —— 1:1 复刻 DeepSeek Harness Web UI 设置面板：
 * 通用设置（语言/主题/权限/Enter 行为）、模型、插件、Agent 预设、关于。
 */
class SettingsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 设置页会展示配对设备、模型、插件等敏感信息。
        applyDshSecureWindow()
        enableDshEdgeToEdge()
        setContent {
            DshTheme {
                val host = remember(intent) {
                    HostStore.load(this).resolveFromIntent(intent)
                }
                SettingsScreen(
                    host = host,
                    onBack = { finish() },
                    onOpenDevices = {
                        startActivity(Intent(this, DevicesActivity::class.java))
                    },
                )
            }
        }
    }
}

internal enum class SettingsDest {
    HOME,
    GENERAL,
    APPEARANCE,
    CONVERSATION,
    MODELS,
    SESSIONS,
    ABOUT,
}

@Composable
private fun SettingsDest.label(): String {
    val s = DshS
    return when (this) {
        SettingsDest.HOME -> s.settingsTitle
        SettingsDest.GENERAL -> s.tabGeneral
        SettingsDest.APPEARANCE -> s.sectionAppearance
        SettingsDest.CONVERSATION -> s.settingsConversation
        SettingsDest.MODELS -> s.tabModels
        SettingsDest.SESSIONS -> s.tabSessions
        SettingsDest.ABOUT -> s.tabAbout
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(
    host: Host?,
    onBack: () -> Unit,
    onOpenDevices: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val s = DshS
    val scope = rememberCoroutineScope()
    val initialSettings = remember(host) { AppSettingsStore.cached(context, host) }
    val settingsViewModel: SettingsViewModel = viewModel(
        key = "settings:${host?.slotKey ?: "offline"}",
        factory = SettingsViewModel.factory(host, initialSettings),
    )
    val settingsClient = settingsViewModel.client
    val navController = rememberNavController()
    val settingsEntry by navController.currentBackStackEntryAsState()
    val dest = settingsEntry?.destination?.route
        ?.let { route -> SettingsDest.entries.firstOrNull { it.name == route } }
        ?: SettingsDest.HOME
    // NavHost 自带返回栈：二级页 pop 回首页；首页时系统返回交回 Activity（onBack）
    var llmGroups by settingsViewModel.llmGroups
    var llmLoading by settingsViewModel.llmLoading
    var llmError by settingsViewModel.llmError
    var llmReloadEpoch by remember { mutableStateOf(0) }
    var expandedProviders by settingsViewModel.expandedProviders
    var showFullAccessConfirm by remember { mutableStateOf(false) }
    var legalDoc by remember { mutableStateOf<Pair<String, String>?>(null) }

    // WI-004：服务端设置为唯一真实源；加载失败回退本地缓存（离线可用）
    var appSettings by settingsViewModel.appSettings
    var namespaceRevisions by settingsViewModel.namespaceRevisions
    var savingNs by settingsViewModel.savingNamespace
    var saveErrors by settingsViewModel.saveErrors

    // DeepSeek 余额（经插件代查，模型页展示）
    var balance by settingsViewModel.balance
    var balanceLoading by settingsViewModel.balanceLoading
    var balanceError by settingsViewModel.balanceError
    var balanceReloadEpoch by remember { mutableStateOf(0) }

    LaunchedEffect(dest, host, balanceReloadEpoch) {
        if (dest != SettingsDest.MODELS) return@LaunchedEffect
        if (host == null) {
            balanceLoading = false
            if (balance == null) balanceError = s.notConnectedCannotSave
            return@LaunchedEffect
        }
        balanceLoading = true
        withContext(Dispatchers.IO) {
            try {
                val b = settingsClient?.getBalance()
                withContext(Dispatchers.Main) {
                    if (b != null) {
                        balance = b
                        balanceError = null
                    } else if (balance == null) {
                        balanceError = s.loadFailed
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    if (balance == null) {
                        balanceError = e.message?.takeIf { it.isNotBlank() } ?: s.loadFailed
                    }
                }
            } finally {
                withContext(Dispatchers.Main) { balanceLoading = false }
            }
        }
    }

    LaunchedEffect(host) {
        if (host == null) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            try {
                val view = settingsClient?.getSettings() ?: return@withContext
                withContext(Dispatchers.Main) {
                    val loaded = AppSettings.fromServer(view.namespaces)
                    loaded.persist(context, host, updateLocalExperience = true)
                    appSettings = loaded
                    LocaleManager.setLanguage(context, loaded.language)
                    namespaceRevisions = view.namespaces.associate { it.ns to it.revision }
                    if (loaded.theme in setOf("light", "dark", "system")) {
                        ThemeManager.setThemeMode(context, loaded.theme)
                    }
                }
            } catch (e: Exception) {
                // 离线：回退本地缓存，设置项仍可展示（保存时会提示错误）
            }
        }
    }

    /** 写服务端并校验读回；失败留在当前页面并显示行内错误与重试入口。 */
    fun saveNamespace(ns: String, patch: org.json.JSONObject, onSuccess: () -> Unit = {}) {
        val h = host
        if (h == null) {
            saveErrors = saveErrors + (ns to s.notConnectedCannotSave)
            return
        }
        if (savingNs != null) return
        savingNs = ns
        scope.launch(Dispatchers.IO) {
            try {
                val updated = AppSettingsStore.save(h, context, ns, patch, namespaceRevisions[ns])
                withContext(Dispatchers.Main) {
                    appSettings = AppSettingsStore.cached(context, h)
                    namespaceRevisions = namespaceRevisions + (ns to updated.revision)
                    saveErrors = saveErrors - ns
                    onSuccess()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    saveErrors = saveErrors + (ns to (e.message ?: s.saveFailed))
                }
            } finally {
                withContext(Dispatchers.Main) { savingNs = null }
            }
        }
    }

    // 模型目录（llm.models）—— 模型 Tab 浏览用；失败不得伪装成加载中
    LaunchedEffect(dest, host, llmReloadEpoch) {
        if (dest != SettingsDest.MODELS) return@LaunchedEffect
        if (host == null) {
            llmLoading = false
            llmError = s.notConnectedCannotSave
            return@LaunchedEffect
        }
        llmLoading = true
        llmError = null
        withContext(Dispatchers.IO) {
            try {
                val groups = settingsClient?.getLlmModels().orEmpty()
                withContext(Dispatchers.Main) {
                    llmGroups = groups
                    llmError = null
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    llmError = e.message?.takeIf { it.isNotBlank() } ?: s.loadModelListFailed
                }
            } finally {
                withContext(Dispatchers.Main) { llmLoading = false }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Dsh.bgBase)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        // 唯一 Top App Bar：首页只负责分类，二级页负责具体配置
        TopAppBar(
            title = {
                Text(
                    dest.label(),
                    color = Dsh.labelPrimary,
                    style = DshType.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            navigationIcon = {
                IconButton(
                    onClick = { if (dest == SettingsDest.HOME) onBack() else navController.popBackStack() },
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = s.back, tint = Dsh.labelSecondary, modifier = Modifier.size(24.dp))
                }
            },
            windowInsets = WindowInsets(0, 0, 0, 0),
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Dsh.bgBase),
        )

        // 分区内容：手机 16dp 边距；大屏最大宽度 720dp 居中
        // 站内转场：transition lambda 非 @Composable，时长在作用域预先捕获
        val navMotionMs = motionDuration(DshDuration.slow)
        NavHost(
            navController = navController,
            startDestination = SettingsDest.HOME.name,
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .widthIn(max = 720.dp)
                .weight(1f)
                .fillMaxWidth(),
            enterTransition = { slideInHorizontally(animationSpec = tween(navMotionMs, easing = DshEasing.out)) { it } },
            exitTransition = { ExitTransition.None },
            popEnterTransition = { EnterTransition.None },
            popExitTransition = { slideOutHorizontally(animationSpec = tween(navMotionMs, easing = DshEasing.out)) { it } },
            predictivePopEnterTransition = { EnterTransition.None },
            predictivePopExitTransition = { slideOutHorizontally(animationSpec = tween(navMotionMs, easing = DshEasing.out)) { it } },
        ) {
            composable(SettingsDest.HOME.name) {
                SettingsPage {
                        SettingsHome(
                        appSettings = appSettings,
                        onOpen = { navController.navigate(it.name) },
                    )
                }
            }

            composable(SettingsDest.GENERAL.name) {
                SettingsPage {
                        LanguageSettings(
                        context = context,
                        appSettings = appSettings,
                        savingNs = savingNs,
                        saveErrors = saveErrors,
                        onOpenDevices = onOpenDevices,
                        onSave = { ns, patch, onSuccess -> saveNamespace(ns, patch, onSuccess) },
                    )
                }
            }

            composable(SettingsDest.APPEARANCE.name) {
                SettingsPage {
                        AppearanceSettings(
                        context = context,
                        savingNs = savingNs,
                        saveErrors = saveErrors,
                        onSave = { ns, patch, onSuccess -> saveNamespace(ns, patch, onSuccess) },
                    )
                }
            }

            composable(SettingsDest.CONVERSATION.name) {
                SettingsPage {
                        ConversationSettings(
                        appSettings = appSettings,
                        savingNs = savingNs,
                        saveErrors = saveErrors,
                        onShowFullAccessConfirm = { showFullAccessConfirm = true },
                        onSave = { ns, patch, onSuccess -> saveNamespace(ns, patch, onSuccess) },
                    )
                }
            }

            composable(SettingsDest.MODELS.name) {
                SettingsPage {
                        // 默认模型（桌面端「设置 → 模型」的默认项；新会话未手动选择时使用）
                        val defaultModelValue = listOfNotNull(
                            appSettings.defaultModelProvider,
                            appSettings.defaultModel,
                        ).joinToString(" / ").ifBlank { s.noneSelected }
                        DshSettingsGroup {
                            SettingsItem(
                                title = s.defaultModelSetting,
                                description = "$defaultModelValue · ${s.defaultModelSettingDesc}",
                                onClick = {},
                            )
                        }
                        // 账户余额（经插件代查；桌面端在模型页展示）
                        SettingsSection(s.sectionBalance)
                        DshSettingsGroup {
                            val b = balance
                            when {
                                b != null -> SettingsItem(
                                    title = s.deepseekBalance,
                                    description = s.balanceSummary.format(b.balance, b.currency, b.used, b.remainder),
                                    onClick = { balanceReloadEpoch += 1 },
                                )
                                balanceLoading -> SettingsItem(
                                    title = s.deepseekBalance,
                                    description = s.querying,
                                    onClick = {},
                                )
                                host == null -> SettingsItem(
                                    title = s.deepseekBalance,
                                    description = s.notConnectedCannotSave,
                                    onClick = onOpenDevices,
                                )
                                else -> SettingsItem(
                                    title = s.deepseekBalance,
                                    description = (balanceError ?: s.loadFailed) + " · " + s.retry,
                                    onClick = { balanceReloadEpoch += 1 },
                                )
                            }
                        }
                        SettingsSection(s.modelListSetting)
                        val modelKind = catalogKind(
                            hasItems = llmGroups.isNotEmpty(),
                            initialLoad = llmLoading,
                            hasError = llmError != null,
                        )
                        when (modelKind) {
                            SessionListKind.Loading -> Text(
                                s.loadingModelList,
                                color = Dsh.labelTertiary,
                                style = DshType.t13,
                                modifier = Modifier.padding(vertical = 12.dp),
                            )
                            SessionListKind.Error -> SettingsLoadRetry(
                                message = llmError ?: s.loadModelListFailed,
                                onRetry = { llmReloadEpoch += 1 },
                            )
                            SessionListKind.Empty -> Text(
                                s.noAvailableModels,
                                color = Dsh.labelTertiary,
                                style = DshType.t13,
                                modifier = Modifier.padding(vertical = 12.dp),
                            )
                            SessionListKind.Content -> Unit
                        }
                        if (modelKind == SessionListKind.Content) {
                        // 供应商折叠卡
                        llmGroups.forEach { group ->
                            val expanded = expandedProviders.contains(group.provider)
                            val interaction = remember { MutableInteractionSource() }
                            val pressed by interaction.collectIsPressedAsState()
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(DshRadius.lg))
                                    .background(Dsh.bgCard)
                                    .border(1.dp, Dsh.borderSubtle, RoundedCornerShape(DshRadius.lg))
                                    .clickable(interactionSource = interaction, indication = dshRipple()) {
                                        expandedProviders = if (expanded) expandedProviders - group.provider else expandedProviders + group.provider
                                    }
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 14.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        if (expanded) ChevronDownOutline14 else ChevronRightOutline14,
                                        contentDescription = null,
                                        tint = Dsh.labelTertiary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        group.provider,
                                        color = Dsh.labelPrimary,
                                        style = DshType.labelLarge,
                                        fontWeight = FontWeight(500),
                                        lineHeight = 20.sp,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text(
                                        "${group.models.size}",
                                        color = Dsh.labelTertiary,
                                        style = DshType.t12x20,
                                        lineHeight = 20.sp
                                    )
                                }
                                if (expanded) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(1.dp)
                                            .background(Dsh.borderSubtle)
                                    )
                                    group.models.forEach { model ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 14.dp, vertical = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                model.name ?: model.id,
                                                color = Dsh.labelSecondary,
                                                style = DshType.bodyDense,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f)
                                            )
                                            model.contextWindow?.let {
                                                Text(
                                                    s.contextSize.format(compactTokens(it)),
                                                    color = Dsh.labelTertiary,
                                                    style = DshType.t11x18,
                                                    lineHeight = 18.sp
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                        }
                        // 添加模型：当前 Harness 协议无模型供应商写入接口（WI-004 禁止假保存），
                        // 不做假按钮，只留一行说明指向电脑端
                        Text(
                            s.modelAddOnDesktopHint,
                            color = Dsh.labelTertiary,
                            style = DshType.t12x17,
                            lineHeight = 17.sp,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                        )
                        }
                }
            }

            composable(SettingsDest.SESSIONS.name) {
                SettingsPage {
                        SessionsSettings(host = host)
                }
            }

            composable(SettingsDest.ABOUT.name) {
                SettingsPage {
                        Text(
                            text = s.unofficialNotice,
                            color = Dsh.labelTertiary,
                            style = DshType.captionRelaxed,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                        )
                        DshSettingsGroup {
                        SettingsItem(
                            title = "DeepLinks",
                            description = s.aboutVersion.replace("%s", BuildConfig.VERSION_NAME),
                            onClick = {},
                        )
                        SettingsItem(
                            title = s.openSourceLicense,
                            description = "MIT License",
                            onClick = { legalDoc = "LICENSE" to s.openSourceLicense },
                        )
                        SettingsItem(
                            title = s.thirdPartyNotices,
                            description = "THIRD_PARTY_NOTICES",
                            onClick = { legalDoc = "THIRD_PARTY_NOTICES.md" to s.thirdPartyNotices },
                        )
                        }
                }
            }

        }
    }

    // Full access 确认弹窗（DSH confirm 文案）
    if (showFullAccessConfirm) {
        Dialog(
            onDismissRequest = { showFullAccessConfirm = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Dsh.bgOverlay)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = dshRipple()) { showFullAccessConfirm = false },
                contentAlignment = Alignment.Center
            ) {
                Column(
                    modifier = Modifier
                        .widthIn(max = 360.dp)
                        .fillMaxWidth(0.9f)
                        .clip(RoundedCornerShape(DshRadius.lg))
                        .background(Dsh.bgCard)
                        .border(1.dp, Dsh.borderSubtle, RoundedCornerShape(DshRadius.lg))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = dshRipple(),
                            onClick = {},
                        )
                        .padding(18.dp)
                ) {
                    Text(s.confirmFullAccessTitle, color = Dsh.labelPrimary, style = DshType.t15x21M, fontWeight = FontWeight(500), lineHeight = 21.sp)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        s.confirmFullAccessMessage,
                        color = Dsh.labelTertiary,
                        style = DshType.captionRelaxed,
                        lineHeight = 18.sp
                    )
                    Spacer(Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        Box(
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .clip(RoundedCornerShape(DshRadius.sm))
                                .clickable { showFullAccessConfirm = false }
                                .padding(horizontal = 14.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(s.cancel, color = Dsh.labelSecondary, style = DshType.t12M, fontWeight = FontWeight(500))
                        }
                        Spacer(Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .clip(RoundedCornerShape(DshRadius.sm))
                                .background(Dsh.error)
                                .clickable {
                                    showFullAccessConfirm = false
                                    // WI-004：真实写入服务端 permission.defaultPreset（新会话由 DSH 服务端应用）
                                    saveNamespace("permission", org.json.JSONObject().put("defaultPreset", "danger-full-access"))
                                }
                                .padding(horizontal = 14.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(s.enableFullAccess, color = Dsh.onBrand, style = DshType.t12M, fontWeight = FontWeight(500))
                        }
                    }
                }
            }
        }
    }

    legalDoc?.let { (fileName, title) ->
        val body = remember(fileName) {
            runCatching {
                context.assets.open("legal/$fileName").bufferedReader(Charsets.UTF_8).use { it.readText() }
            }.getOrElse { s.legalLoadFailed.replace("%s", fileName) }
        }
        Dialog(
            onDismissRequest = { legalDoc = null },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Dsh.bgOverlay)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = dshRipple(),
                    ) { legalDoc = null },
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    modifier = Modifier
                        .widthIn(max = 420.dp)
                        .fillMaxWidth(0.92f)
                        .fillMaxHeight(0.8f)
                        .clip(RoundedCornerShape(DshRadius.lg))
                        .background(Dsh.bgCard)
                        .border(1.dp, Dsh.borderSubtle, RoundedCornerShape(DshRadius.lg))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = dshRipple(),
                        ) {}
                        .padding(18.dp)
                ) {
                    Text(title, color = Dsh.labelPrimary, style = DshType.title, fontWeight = FontWeight(500))
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = body,
                        color = Dsh.labelSecondary,
                        style = DshType.microRelaxed,
                        modifier = Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState()),
                    )
                    Spacer(Modifier.height(12.dp))
                    Box(
                        modifier = Modifier
                            .align(Alignment.End)
                            .heightIn(min = 48.dp)
                            .clip(RoundedCornerShape(DshRadius.sm))
                            .clickable { legalDoc = null }
                            .padding(horizontal = 14.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(s.close, color = Dsh.labelSecondary, style = DshType.t12M, fontWeight = FontWeight(500))
                    }
                }
            }
        }
    }
}

/** NavHost 每个目的地共用的页面容器：手机 16dp 边距、大屏 720dp 居中、独立滚动。 */
@Composable
private fun SettingsPage(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            // 不透明底是「二级页文字透出上一级」的兜底：NavHost 在 pop 时目标页 zIndex 在下层，
            // 转场帧里只要有一层透明，下层页面的文字就会叠上来。每页自己铺满 bgBase 后，
            // 无论转场怎么算，看到的永远只有最上层那一页。
            .background(Dsh.bgBase)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(top = 4.dp, bottom = 32.dp),
        content = content,
    )
}

// ---------- 设置首页（分类导航） ----------

@Composable
internal fun SettingsHome(
    appSettings: AppSettings,
    onOpen: (SettingsDest) -> Unit,
) {
    val s = DshS
    val agentPresetLabel = when (appSettings.agentPreset) {
        "standard" -> s.presetStandard
        "code" -> s.presetCode
        "minimal" -> s.presetMinimal
        "creator", "cordis" -> s.presetCreator
        else -> appSettings.agentPreset
    }
    val themeLabel = when (ThemeManager.currentThemeMode) {
        "light" -> s.themeLight
        "dark" -> s.themeDark
        else -> s.themeSystem
    }

    SettingsSection(s.sectionGeneral)
    DshSettingsGroup {
        SettingsNavRow(
            title = s.language,
            summary = if (appSettings.language == "zh") s.langZh else s.langEn,
            onClick = { onOpen(SettingsDest.GENERAL) },
        )
        DshSettingsDivider()
        SettingsNavRow(
            title = s.sectionAppearance,
            summary = themeLabel,
            onClick = { onOpen(SettingsDest.APPEARANCE) },
        )
        DshSettingsDivider()
        SettingsNavRow(
            title = s.settingsConversation,
            summary = agentPresetLabel,
            onClick = { onOpen(SettingsDest.CONVERSATION) },
        )
    }
    SettingsSection(s.sectionWorkspace)
    DshSettingsGroup {
        val modelSummary = appSettings.defaultModel ?: s.noneSelected
        SettingsNavRow(
            title = s.tabModels,
            summary = modelSummary,
            onClick = { onOpen(SettingsDest.MODELS) },
        )
        DshSettingsDivider()
        SettingsNavRow(title = s.tabSessions, onClick = { onOpen(SettingsDest.SESSIONS) })
    }
    SettingsSection(s.sectionMore)
    DshSettingsGroup {
        SettingsNavRow(
            title = s.tabAbout,
            summary = BuildConfig.VERSION_NAME,
            onClick = { onOpen(SettingsDest.ABOUT) },
        )
    }
}

/** 首页 / 分组入口行：标题 + 尾部当前值 + chevron，48dp 以上触控目标。 */
@Composable
private fun SettingsNavRow(
    title: String,
    onClick: () -> Unit,
    summary: String? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(RoundedCornerShape(DshRadius.sm))
            .background(if (pressed) Dsh.pressed else Color.Transparent)
            .clickable(interactionSource = interaction, indication = dshRipple(), onClick = onClick)
            .semantics { role = Role.Button }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            color = Dsh.labelPrimary,
            style = DshType.title,
            modifier = Modifier.weight(1f),
        )
        if (!summary.isNullOrBlank()) {
            Text(
                summary,
                color = Dsh.labelTertiary,
                style = DshType.body,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(6.dp))
        }
        Icon(
            ChevronRightOutline14,
            contentDescription = null,
            tint = Dsh.labelTertiary,
            modifier = Modifier.size(16.dp),
        )
    }
}

// ---------- 通用：语言与配对（WI-004：服务端设置为唯一真实源，保存需读回校验） ----------

@Composable
private fun LanguageSettings(
    context: Context,
    appSettings: AppSettings,
    savingNs: String?,
    saveErrors: Map<String, String>,
    onOpenDevices: () -> Unit,
    onSave: (ns: String, patch: org.json.JSONObject, onSuccess: () -> Unit) -> Unit,
) {
    val s = DshS
    DshSettingsGroup {
        SettingsSelectItem(
            title = s.language,
            description = s.languageDesc,
            value = if (appSettings.language == "zh") s.langZh else s.langEn,
            options = listOf(s.langZh to "zh", s.langEn to "en"),
            selectedId = appSettings.language,
            saving = savingNs == "locale",
            error = saveErrors["locale"],
            onRetry = { onSave("locale", org.json.JSONObject().put("preference", appSettings.language), {}) },
            onSelect = { _, id ->
                LocaleManager.setLanguage(context, id)
                onSave("locale", org.json.JSONObject().put("preference", id), {})
            }
        )
        DshSettingsDivider()
        SettingsItem(
            title = s.pairingManage,
            description = s.manageYourLinks,
            onClick = onOpenDevices,
        )
    }
}

// ---------- 外观：主题 / 字号 / 系统字体 ----------

@Composable
private fun AppearanceSettings(
    context: Context,
    savingNs: String?,
    saveErrors: Map<String, String>,
    onSave: (ns: String, patch: org.json.JSONObject, onSuccess: () -> Unit) -> Unit,
) {
    val s = DshS
    val haptic = rememberDshHaptic()
    DshSettingsGroup {
        SettingsSelectItem(
            title = s.settingsTheme,
            value = when (ThemeManager.currentThemeMode) {
                "light" -> s.themeLight
                "dark" -> s.themeDark
                else -> s.themeSystem
            },
            options = listOf(
                s.themeLight to "light",
                s.themeDark to "dark",
                s.themeSystem to "system",
            ),
            selectedId = ThemeManager.currentThemeMode,
            saving = savingNs == "ui-theme",
            error = saveErrors["ui-theme"],
            onRetry = { onSave("ui-theme", org.json.JSONObject().put("preference", ThemeManager.currentThemeMode), {}) },
            onSelect = { _, id ->
                ThemeManager.setThemeMode(context, id)
                onSave("ui-theme", org.json.JSONObject().put("preference", id), {})
            }
        )
        DshSettingsDivider()
        SettingsSelectItem(
            title = s.settingsFontSize,
            value = when (FontScaleManager.currentScale) {
                FontScaleManager.SMALL -> s.fontSizeSmall
                FontScaleManager.LARGE -> s.fontSizeLarge
                else -> s.fontSizeDefault
            },
            options = listOf(
                s.fontSizeSmall to FontScaleManager.SMALL,
                s.fontSizeDefault to FontScaleManager.DEFAULT,
                s.fontSizeLarge to FontScaleManager.LARGE,
            ),
            selectedId = FontScaleManager.currentScale,
            onSelect = { _, id -> FontScaleManager.setScale(context, id) },
        )
        DshSettingsDivider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(s.systemFont, color = Dsh.labelPrimary, style = DshType.labelLarge, lineHeight = 20.sp, fontWeight = FontWeight(500))
                Spacer(Modifier.height(2.dp))
                Text(s.systemFontDesc, color = Dsh.labelTertiary, style = DshType.t12x17, lineHeight = 17.sp)
            }
            Spacer(Modifier.width(12.dp))
            Switch(
                checked = UiFontManager.useSystemFont,
                onCheckedChange = { haptic(DshHaptic.Tick); UiFontManager.setUseSystemFont(context, it) },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Dsh.onBrand,
                    checkedTrackColor = Dsh.brand400,
                    checkedBorderColor = Dsh.brand400,
                    uncheckedThumbColor = Dsh.labelSecondary,
                    uncheckedTrackColor = Dsh.bgSubtle,
                    uncheckedBorderColor = Dsh.borderStrong,
                ),
            )
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            DshSettingsDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(s.dynamicColor, color = Dsh.labelPrimary, style = DshType.body, fontWeight = FontWeight(500))
                    Spacer(Modifier.height(2.dp))
                    Text(s.dynamicColorDesc, color = Dsh.labelTertiary, style = DshType.caption)
                }
                Spacer(Modifier.width(12.dp))
                Switch(
                    checked = ThemeManager.dynamicColor,
                    onCheckedChange = { haptic(DshHaptic.Tick); ThemeManager.setDynamicColor(context, it) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Dsh.onBrand,
                        checkedTrackColor = Dsh.brand400,
                        checkedBorderColor = Dsh.brand400,
                        uncheckedThumbColor = Dsh.labelSecondary,
                        uncheckedTrackColor = Dsh.bgSubtle,
                        uncheckedBorderColor = Dsh.borderStrong,
                    ),
                )
            }
        }
    }
}

// ---------- 会话：预设 / 权限 / 繁忙行为 ----------

@Composable
private fun ConversationSettings(
    appSettings: AppSettings,
    savingNs: String?,
    saveErrors: Map<String, String>,
    onShowFullAccessConfirm: () -> Unit,
    onSave: (ns: String, patch: org.json.JSONObject, onSuccess: () -> Unit) -> Unit,
) {
    val s = DshS
    DshSettingsGroup {
        SettingsSelectItem(
            title = s.agentPreset,
            description = s.agentPresetDesc,
            value = when (appSettings.agentPreset) {
                "standard" -> s.presetStandard
                "code" -> s.presetCode
                "minimal" -> s.presetMinimal
                "creator", "cordis" -> s.presetCreator
                else -> appSettings.agentPreset
            },
            options = listOf(
                s.presetStandard to "standard",
                s.presetCode to "code",
                s.presetMinimal to "minimal",
                s.presetCreator to "cordis",
            ),
            selectedId = appSettings.agentPreset,
            saving = savingNs == "agent-presets",
            error = saveErrors["agent-presets"],
            onRetry = { onSave("agent-presets", org.json.JSONObject().put("default", appSettings.agentPreset), {}) },
            onSelect = { _, id ->
                onSave("agent-presets", org.json.JSONObject().put("default", id), {})
            }
        )
        DshSettingsDivider()
        SettingsSelectItem(
            title = s.permission,
            description = s.permissionDesc,
            value = when (appSettings.permissionPreset) {
                "read-only" -> s.permReadOnly
                "danger-full-access" -> s.permFullAccess
                else -> s.permWorkspaceWrite
            },
            options = listOf(
                s.permReadOnly to "read-only",
                s.permWorkspaceWrite to "workspace-write",
                s.permFullAccess to "danger-full-access",
            ),
            selectedId = appSettings.permissionPreset,
            saving = savingNs == "permission",
            error = saveErrors["permission"],
            onRetry = { onSave("permission", org.json.JSONObject().put("defaultPreset", appSettings.permissionPreset), {}) },
            onSelect = { _, id ->
                if (id == "danger-full-access") {
                    onShowFullAccessConfirm()
                } else {
                    onSave("permission", org.json.JSONObject().put("defaultPreset", id), {})
                }
            }
        )
        DshSettingsDivider()
        val busyEnterId = canonicalBusyEnter(appSettings.busyEnter)
        SettingsSelectItem(
            title = s.busyEnter,
            description = s.busyEnterDesc,
            value = when (busyEnterId) {
                "send" -> s.busySend
                "steer" -> s.busySteer
                else -> s.busyQueue
            },
            options = listOf(
                s.busySend to "send",
                s.busySteer to "steer",
                s.busyQueue to "queue",
            ),
            selectedId = busyEnterId,
            saving = savingNs == "ui-conversation",
            error = saveErrors["ui-conversation"],
            onRetry = { onSave("ui-conversation", org.json.JSONObject().put("busyEnter", busyEnterId), {}) },
            onSelect = { _, id ->
                onSave("ui-conversation", org.json.JSONObject().put("busyEnter", id), {})
            }
        )
    }
}

/** 下拉选择设置项（DSH Select：菜单从右侧下拉角标弹出，选中项带品牌蓝勾选） */
@Composable
private fun SettingsSelectItem(
    title: String,
    value: String,
    options: List<Pair<String, String>>,
    description: String? = null,
    selectedId: String? = null,
    saving: Boolean = false,
    error: String? = null,
    onRetry: (() -> Unit)? = null,
    onSelect: (label: String, id: String) -> Unit,
) {
    val s = DshS
    var expanded by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(DshRadius.md))
            .background(if (pressed) Dsh.pressed else Color.Transparent)
            .clickable(interactionSource = interaction, indication = dshRipple(), enabled = !saving) { expanded = true }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = Dsh.labelPrimary, style = DshType.t14x20, lineHeight = 20.sp)
            if (description != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    description,
                    color = Dsh.labelTertiary,
                    style = DshType.t12x17,
                    lineHeight = 17.sp
                )
            }
            if (error != null) {
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(s.saveFailedWithMessage.format(error), color = Dsh.error, style = DshType.t12x17, lineHeight = 17.sp, modifier = Modifier.weight(1f))
                    Box(
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .clip(RoundedCornerShape(DshRadius.sm))
                            .background(Dsh.bgCard)
                            .clickable(enabled = onRetry != null) { onRetry?.invoke() }
                            .padding(horizontal = 10.dp, vertical = 3.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(s.retry, color = Dsh.brand400, style = DshType.t12M, fontWeight = FontWeight(500))
                    }
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        Box(modifier = Modifier.wrapContentSize(Alignment.TopEnd)) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (saving) {
                    Text(s.saving, color = Dsh.labelTertiary, style = DshType.t12x20, lineHeight = 20.sp)
                } else {
                    Text(value, color = Dsh.labelTertiary, style = DshType.bodyDense, lineHeight = 20.sp)
                    Icon(
                        ChevronDownOutline14,
                        contentDescription = null,
                        tint = Dsh.labelTertiary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                containerColor = Dsh.bgCard,
                shape = RoundedCornerShape(DshRadius.lg),
                tonalElevation = 0.dp,
                shadowElevation = 12.dp,
                border = BorderStroke(1.dp, Dsh.borderSubtle),
                offset = DpOffset(0.dp, 4.dp)
            ) {
                Column(
                    modifier = Modifier
                        .widthIn(min = 160.dp, max = 240.dp)
                        .padding(vertical = 4.dp)
                ) {
                    options.forEach { (label, id) ->
                        val isSelected = id == (selectedId ?: value)
                        val optInteraction = remember { MutableInteractionSource() }
                        val optPressed by optInteraction.collectIsPressedAsState()
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .clip(RoundedCornerShape(DshRadius.md))
                                .background(if (optPressed || isSelected) Dsh.pressed else Color.Transparent)
                                .clickable(interactionSource = optInteraction, indication = dshRipple()) {
                                    onSelect(label, id)
                                    expanded = false
                                }
                                .padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                label,
                                color = if (isSelected) Dsh.brand400 else Dsh.labelPrimary,
                                style = DshType.bodyDense,
                                modifier = Modifier.weight(1f)
                            )
                            if (isSelected) {
                                Icon(
                                    CheckOutline16,
                                    contentDescription = null,
                                    tint = Dsh.brand400,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------- 会话管理：已归档 / 已删除 ----------

@Composable
private fun SessionsSettings(host: Host?) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val s = DshS
    val prefs = remember { WorkspacePrefs(context) }
    var archivedIds by remember { mutableStateOf(prefs.archivedSessionIds) }
    var deletedIds by remember { mutableStateOf(prefs.deletedSessionIds) }
    var snapshots by remember { mutableStateOf(prefs.sessionSnapshots) }
    var hiddenIds by remember { mutableStateOf(prefs.settingsHiddenSessionIds) }
    var liveById by remember { mutableStateOf<Map<String, MobileSession>>(emptyMap()) }
    var loading by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var reloadEpoch by remember { mutableStateOf(0) }
    var pendingClear by remember { mutableStateOf<ClearSessionsScope?>(null) }

    fun reloadLocal() {
        archivedIds = prefs.archivedSessionIds
        deletedIds = prefs.deletedSessionIds
        snapshots = prefs.sessionSnapshots
        hiddenIds = prefs.settingsHiddenSessionIds
    }

    LaunchedEffect(host, reloadEpoch) {
        loading = true
        loadError = null
        if (host != null) {
            withContext(Dispatchers.IO) {
                try {
                    val client = MobileApiClient(host)
                    val sessionSnapshot = client.getSessions()
                    val sessions = sessionSnapshot.sessions
                    val catalog = try { client.getWorkspaces() } catch (_: Exception) { null }
                    withContext(Dispatchers.Main) {
                        liveById = sessions.associateBy { it.sessionId }
                        val serverArchivedIds = when {
                            sessionSnapshot.archiveSnapshotAvailable -> sessionSnapshot.archivedSessionIds
                            catalog?.archiveSnapshotAvailable == true -> catalog.archivedSessionIds
                            else -> null
                        }
                        if (serverArchivedIds != null) {
                            val restored = prefs.restoredSessionIds
                            val nextRestored = restored intersect serverArchivedIds
                            if (nextRestored != restored) {
                                prefs.restoredSessionIds = nextRestored
                            }
                            val syncedArchivedIds = reconcileArchivedSessionIds(serverArchivedIds, nextRestored)
                            if (syncedArchivedIds != prefs.archivedSessionIds) {
                                prefs.archivedSessionIds = syncedArchivedIds
                            }
                            // 为仅有 id、尚无快照的归档项补一条占位
                            (syncedArchivedIds - nextRestored).forEach { id ->
                                if (id !in prefs.sessionSnapshots) {
                                    val live = sessions.firstOrNull { it.sessionId == id }
                                    prefs.rememberSessionSnapshot(
                                        sessionId = id,
                                        title = live?.title ?: id.take(8),
                                        cwd = live?.cwd,
                                        updatedAt = live?.updatedAt ?: 0L,
                                    )
                                }
                            }
                        }
                        // 活跃列表里仍能拿到的归档/删除项，刷新快照标题
                        sessions.forEach { session ->
                            if (session.sessionId in prefs.archivedSessionIds || session.sessionId in prefs.deletedSessionIds) {
                                prefs.rememberSessionSnapshot(
                                    sessionId = session.sessionId,
                                    title = session.title,
                                    cwd = session.cwd,
                                    updatedAt = session.updatedAt,
                                )
                            }
                        }
                        reloadLocal()
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        reloadLocal()
                        loadError = e.message?.takeIf { it.isNotBlank() } ?: s.loadFailed
                    }
                }
            }
        } else {
            reloadLocal()
        }
        loading = false
    }

    fun resolveRow(id: String): SessionSnapshot {
        liveById[id]?.let {
            return SessionSnapshot(it.sessionId, it.title, it.cwd, it.updatedAt)
        }
        return snapshots[id] ?: SessionSnapshot(id, id.take(8), null, 0L)
    }

    fun restoreToSidebar(id: String) {
        prefs.archivedSessionIds = prefs.archivedSessionIds - id
        prefs.deletedSessionIds = prefs.deletedSessionIds - id
        prefs.settingsHiddenSessionIds = prefs.settingsHiddenSessionIds - id
        prefs.restoredSessionIds = prefs.restoredSessionIds + id
        // 保留快照无妨；侧边栏以 id 集合为准
        reloadLocal()
    }

    fun clearLocalRecords(ids: Collection<String>) {
        val set = ids.toSet()
        if (set.isEmpty()) return
        prefs.hideFromSettings(set)
        reloadLocal()
    }

    val deletedRows = (deletedIds - hiddenIds).map { resolveRow(it) }.sortedByDescending { it.updatedAt }
    val archivedRows = (archivedIds - deletedIds - hiddenIds).map { resolveRow(it) }.sortedByDescending { it.updatedAt }
    val totalManaged = archivedRows.size + deletedRows.size
    val listKind = catalogKind(
        hasItems = totalManaged > 0,
        initialLoad = loading,
        hasError = loadError != null,
    )

    Text(
        s.sessionsSettingsHint,
        color = Dsh.labelTertiary,
        style = DshType.captionRelaxed,
        modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 4.dp),
    )

    if (listKind == SessionListKind.Loading) {
        Text(
            s.loading,
            color = Dsh.labelTertiary,
            style = DshType.t13,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 12.dp),
        )
    } else if (listKind == SessionListKind.Error) {
        SettingsLoadRetry(
            message = loadError ?: s.loadFailed,
            onRetry = { reloadEpoch += 1 },
        )
    } else {

    if (totalManaged > 0) {
        SettingsItem(
            title = s.clearAllLocalRecords,
            description = s.clearAllLocalRecordsDesc,
            onClick = { pendingClear = ClearSessionsScope.ALL },
            danger = true,
        )
    }

    SettingsSection(
        title = s.sectionArchivedSessions,
        actionLabel = if (archivedRows.isNotEmpty()) s.clearSectionRecords else null,
        onAction = { pendingClear = ClearSessionsScope.ARCHIVED },
    )
    if (archivedRows.isEmpty()) {
        Text(
            s.noArchivedSessions,
            color = Dsh.labelTertiary,
            style = DshType.t13,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
        )
    } else {
        archivedRows.forEach { row ->
            ManagedSessionRow(
                snapshot = row,
                onRestore = { restoreToSidebar(row.sessionId) },
                onClear = { clearLocalRecords(listOf(row.sessionId)) },
            )
            Spacer(Modifier.height(2.dp))
        }
    }

    SettingsSection(
        title = s.sectionDeletedSessions,
        actionLabel = if (deletedRows.isNotEmpty()) s.clearSectionRecords else null,
        onAction = { pendingClear = ClearSessionsScope.DELETED },
    )
    if (deletedRows.isEmpty()) {
        Text(
            s.noDeletedSessions,
            color = Dsh.labelTertiary,
            style = DshType.t13,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
        )
    } else {
        deletedRows.forEach { row ->
            ManagedSessionRow(
                snapshot = row,
                onRestore = { restoreToSidebar(row.sessionId) },
                onClear = { clearLocalRecords(listOf(row.sessionId)) },
            )
            Spacer(Modifier.height(2.dp))
        }
    }
    }

    pendingClear?.let { scope ->
        val (title, message, ids) = when (scope) {
            ClearSessionsScope.ARCHIVED -> Triple(
                s.clearArchivedTitle,
                s.clearArchivedMessage.format(archivedRows.size),
                archivedRows.map { it.sessionId },
            )
            ClearSessionsScope.DELETED -> Triple(
                s.clearDeletedTitle,
                s.clearDeletedMessage.format(deletedRows.size),
                deletedRows.map { it.sessionId },
            )
            ClearSessionsScope.ALL -> Triple(
                s.clearAllSessionsTitle,
                s.clearAllSessionsMessage.format(totalManaged),
                (archivedRows + deletedRows).map { it.sessionId },
            )
        }
        SettingsConfirmDialog(
            title = title,
            message = message,
            confirmLabel = s.clearAllLocalRecords,
            danger = true,
            onDismiss = { pendingClear = null },
            onConfirm = {
                pendingClear = null
                clearLocalRecords(ids)
            },
        )
    }
}

private enum class ClearSessionsScope { ARCHIVED, DELETED, ALL }

@Composable
private fun ManagedSessionRow(
    snapshot: SessionSnapshot,
    onRestore: () -> Unit,
    onClear: () -> Unit,
) {
    val s = DshS
    var menuOpen by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(DshRadius.md))
            .background(if (pressed) Dsh.pressed else Color.Transparent),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .clickable(interactionSource = interaction, indication = dshRipple()) { menuOpen = true }
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    snapshot.title,
                    color = Dsh.labelPrimary,
                    style = DshType.t14x20,
                    lineHeight = 20.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val desc = buildList {
                    snapshot.cwd?.substringAfterLast('/')?.takeIf { it.isNotBlank() }?.let { add(it) }
                    if (snapshot.updatedAt > 0L) add(formatSessionTime(snapshot.updatedAt))
                }.joinToString(" · ")
                if (desc.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(desc, color = Dsh.labelTertiary, style = DshType.t12x17, lineHeight = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Icon(
                ChevronRightOutline14,
                contentDescription = null,
                tint = Dsh.labelTertiary,
                modifier = Modifier.size(16.dp),
            )
        }
        Box(modifier = Modifier.align(Alignment.CenterEnd)) {
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
                containerColor = Dsh.bgCard,
                shape = RoundedCornerShape(DshRadius.lg),
                tonalElevation = 0.dp,
                shadowElevation = 12.dp,
                border = BorderStroke(1.dp, Dsh.borderSubtle),
                offset = DpOffset(0.dp, 4.dp),
            ) {
                DropdownMenuItem(
                    text = { Text(s.restoreToSidebar, color = Dsh.labelPrimary, style = DshType.t13) },
                    onClick = {
                        menuOpen = false
                        onRestore()
                    },
                )
                DropdownMenuItem(
                    text = { Text(s.removeFromLocalList, color = Dsh.error, style = DshType.t13) },
                    onClick = {
                        menuOpen = false
                        onClear()
                    },
                )
            }
        }
    }
}

private fun formatSessionTime(timestamp: Long): String {
    if (timestamp <= 0L) return ""
    val millis = if (timestamp < 1_000_000_000_000L) timestamp * 1000 else timestamp
    val sdf = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
    return sdf.format(java.util.Date(millis))
}

@Composable
private fun SettingsLoadRetry(
    message: String,
    onRetry: () -> Unit,
) {
    val s = DshS
    Column(
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            message,
            color = Dsh.error,
            style = DshType.t13x18,
            lineHeight = 18.sp,
        )
        Text(
            s.retry,
            color = Dsh.brand400,
            style = DshType.t14M,
            fontWeight = FontWeight(500),
            modifier = Modifier
                .heightIn(min = 48.dp)
                .clip(RoundedCornerShape(DshRadius.md))
                .clickable(onClick = onRetry)
                .padding(horizontal = 4.dp, vertical = 8.dp)
                .semantics {
                    role = Role.Button
                    contentDescription = s.retry
                },
        )
    }
}

/** 设置分组：不再逐行套白卡，仅靠分组标题与间距建立层级（Material 3 结构）。 */
@Composable
private fun DshSettingsGroup(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        content = content,
    )
}

/** iOS 式组内分隔线：发丝级、左侧内缩（与行首文字对齐）。 */
@Composable
private fun DshSettingsDivider() {
    HorizontalDivider(
        color = Dsh.borderSubtle,
        thickness = 0.5.dp,
        modifier = Modifier.padding(start = 16.dp),
    )
}

/** iOS 式分区标题：小号灰字，与卡片左缘对齐。 */
@Composable
private fun SettingsSection(
    title: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 18.dp, bottom = 4.dp, start = 4.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            color = Dsh.labelTertiary,
            style = DshType.label,
            modifier = Modifier.weight(1f),
        )
        if (actionLabel != null && onAction != null) {
            val interaction = remember { MutableInteractionSource() }
            val pressed by interaction.collectIsPressedAsState()
            Box(
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(DshRadius.sm))
                    .background(if (pressed) Dsh.pressed else Color.Transparent)
                    .clickable(interactionSource = interaction, indication = dshRipple(), onClick = onAction)
                    .padding(horizontal = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    actionLabel,
                    color = Dsh.error,
                    style = DshType.t12x18M,
                    fontWeight = FontWeight(500),
                    lineHeight = 18.sp,
                )
            }
        }
    }
}

@Composable
private fun SettingsItem(
    title: String,
    description: String,
    onClick: () -> Unit,
    danger: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clip(RoundedCornerShape(DshRadius.md))
            .background(if (pressed) Dsh.pressed else Color.Transparent)
            .clickable(interactionSource = interaction, indication = dshRipple(), onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                color = if (danger) Dsh.error else Dsh.labelPrimary,
                style = DshType.body,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (description.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(description, color = Dsh.labelTertiary, style = DshType.t12x17, lineHeight = 17.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        Icon(
            ChevronRightOutline14,
            contentDescription = null,
            tint = if (danger) Dsh.error else Dsh.labelTertiary,
            modifier = Modifier.size(16.dp)
        )
    }
}

@Composable
private fun SettingsConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    danger: Boolean = false,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val s = DshS
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Dsh.bgOverlay)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = dshRipple(),
                    onClick = onDismiss,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 360.dp)
                    .fillMaxWidth(0.9f)
                    .clip(RoundedCornerShape(DshRadius.lg))
                    .background(Dsh.bgCard)
                    .border(1.dp, Dsh.borderSubtle, RoundedCornerShape(DshRadius.lg))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = dshRipple(),
                    ) {}
                    .padding(18.dp)
            ) {
                Text(title, color = Dsh.labelPrimary, style = DshType.t15x21M, fontWeight = FontWeight(500), lineHeight = 21.sp)
                Spacer(Modifier.height(8.dp))
                Text(message, color = Dsh.labelTertiary, style = DshType.captionRelaxed, lineHeight = 18.sp)
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    Box(
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .clip(RoundedCornerShape(DshRadius.sm))
                            .semantics {
                                role = Role.Button
                                contentDescription = s.cancel
                            }
                            .clickable(onClick = onDismiss)
                            .padding(horizontal = 14.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(s.cancel, color = Dsh.labelSecondary, style = DshType.t12M, fontWeight = FontWeight(500))
                    }
                    Spacer(Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .clip(RoundedCornerShape(DshRadius.sm))
                            .background(if (danger) Dsh.error else Dsh.brand400)
                            .semantics {
                                role = Role.Button
                                contentDescription = confirmLabel
                            }
                            .clickable(onClick = onConfirm)
                            .padding(horizontal = 14.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(confirmLabel, color = Dsh.onBrand, style = DshType.t12M, fontWeight = FontWeight(500))
                    }
                }
            }
        }
    }
}