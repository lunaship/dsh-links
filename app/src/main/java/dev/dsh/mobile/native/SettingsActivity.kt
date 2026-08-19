package dev.dsh.mobile.native
import dev.dsh.mobile.core.persist
import dev.dsh.mobile.core.Dsh
import dev.dsh.mobile.core.Host
import dev.dsh.mobile.core.ThemeManager
import dev.dsh.mobile.native.MobileSession
import dev.dsh.mobile.native.AppSettings
import dev.dsh.mobile.native.MobileApiClient
import dev.dsh.mobile.core.AppSettingsStore
import dev.dsh.mobile.core.DshTheme
import dev.dsh.mobile.core.HostStore
import dev.dsh.mobile.devices.DevicesActivity

import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 设置页 —— 1:1 复刻 DeepSeek Harness Web UI 设置面板：
 * 通用设置（语言/主题/权限/Enter 行为）、模型（供应商折叠 + 添加模型）、
 * 插件（配置与查看）、Agent 预设（选项化）、关于。
 */
class SettingsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        setContent {
            DshTheme {
                val host = remember { HostStore.load(this).firstOrNull() }
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

private enum class SettingsTab(val title: String) {
    GENERAL("通用设置"),
    MODELS("模型"),
    PLUGINS("插件"),
    ABOUT("关于"),
}

private val PERMISSION_PRESETS = listOf(
    "read-only" to "只读",
    "workspace-write" to "工作区写入",
    "danger-full-access" to "完全访问",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(
    host: Host?,
    onBack: () -> Unit,
    onOpenDevices: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf(SettingsTab.GENERAL) }
    var llmGroups by remember { mutableStateOf<List<MobileModelGroup>>(emptyList()) }
    var expandedProviders by remember { mutableStateOf(setOf<String>()) }
    var showFullAccessConfirm by remember { mutableStateOf(false) }

    // WI-004：服务端设置为唯一真实源；加载失败回退本地缓存（离线可用）
    var appSettings by remember { mutableStateOf(AppSettingsStore.cached(context)) }
    var namespaceRevisions by remember { mutableStateOf<Map<String, Long>>(emptyMap()) }
    var savingNs by remember { mutableStateOf<String?>(null) }
    var saveErrors by remember { mutableStateOf<Map<String, String>>(emptyMap()) }

    // DeepSeek 余额（经插件代查，关于页展示）
    var balance by remember { mutableStateOf<MobileBalance?>(null) }

    LaunchedEffect(tab, host) {
        if (tab == SettingsTab.ABOUT && host != null) {
            balance = null
            withContext(Dispatchers.IO) {
                try {
                    val b = MobileApiClient(host!!).getBalance()
                    withContext(Dispatchers.Main) { balance = b }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) { balance = null }
                }
            }
        }
    }

    LaunchedEffect(host) {
        if (host == null) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            try {
                val view = MobileApiClient(host).getSettings()
                withContext(Dispatchers.Main) {
                    val loaded = AppSettings.fromServer(view.namespaces)
                    loaded.persist(context)
                    appSettings = loaded
                    namespaceRevisions = view.namespaces.associate { it.ns to it.revision }
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
            saveErrors = saveErrors + (ns to "未连接设备，无法保存")
            return
        }
        if (savingNs != null) return
        savingNs = ns
        scope.launch(Dispatchers.IO) {
            try {
                val updated = AppSettingsStore.save(h, context, ns, patch, namespaceRevisions[ns])
                withContext(Dispatchers.Main) {
                    appSettings = AppSettingsStore.cached(context)
                    namespaceRevisions = namespaceRevisions + (ns to updated.revision)
                    saveErrors = saveErrors - ns
                    onSuccess()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    saveErrors = saveErrors + (ns to (e.message ?: "保存失败，请重试"))
                }
            } finally {
                withContext(Dispatchers.Main) { savingNs = null }
            }
        }
    }

    // 模型目录（llm.models）
    LaunchedEffect(tab) {
        if (tab == SettingsTab.MODELS && host != null) {
            withContext(Dispatchers.IO) {
                try {
                    val conn = java.net.URL(host.baseUrl.trimEnd('/') + "/dsh-link/mobile/llm-models").openConnection() as java.net.HttpURLConnection
                    conn.connectTimeout = 8000
                    conn.readTimeout = 10000
                    conn.setRequestProperty("x-dsh-link-token", host.token)
                    conn.setRequestProperty("Cookie", "dsh_link_token=" + host.token)
                    val root = org.json.JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                    val arr = root.optJSONArray("groups") ?: org.json.JSONArray()
                    val groups = (0 until arr.length()).map { i ->
                        val g = arr.getJSONObject(i)
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
                                )
                            },
                        )
                    }
                    withContext(Dispatchers.Main) { llmGroups = groups }
                } catch (e: Exception) {}
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Dsh.bgBase)
            .statusBarsPadding()
    ) {
        // 页头
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp, start = 20.dp, end = 28.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 32.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val backInteraction = remember { MutableInteractionSource() }
                val backPressed by backInteraction.collectIsPressedAsState()
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(if (backPressed) Dsh.hover else Color.Transparent)
                        .clickable(interactionSource = backInteraction, indication = null, onClick = onBack),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        ChevronLeftOutline14,
                        contentDescription = "返回",
                        tint = Dsh.labelSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(Modifier.width(10.dp))
                Text("设置", color = Dsh.labelPrimary, fontSize = 14.sp, fontWeight = FontWeight(500), lineHeight = 20.sp)
            }
            Spacer(Modifier.height(11.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(Dsh.borderL2)
            )
        }

        // 分区导航（横向滚动）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            for (t in listOf(SettingsTab.GENERAL, SettingsTab.MODELS, SettingsTab.PLUGINS, SettingsTab.ABOUT)) {
                val selected = t == tab
                val interaction = remember { MutableInteractionSource() }
                val pressed by interaction.collectIsPressedAsState()
                Box(
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .height(32.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(
                            when {
                                selected -> Dsh.bgNavActive
                                pressed -> Dsh.hover
                                else -> Color.Transparent
                            }
                        )
                        .clickable(interactionSource = interaction, indication = null) { tab = t }
                        .padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        t.title,
                        color = if (selected) Dsh.labelPrimary else Dsh.labelSecondary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight(500),
                        lineHeight = 20.sp
                    )
                }
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(Dsh.borderL1)
        )

        // 分区内容
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(top = 8.dp, bottom = 32.dp)
        ) {
            when (tab) {
                SettingsTab.GENERAL -> GeneralSettings(
                    context = context,
                    host = host,
                    appSettings = appSettings,
                    savingNs = savingNs,
                    saveErrors = saveErrors,
                    onOpenDevices = onOpenDevices,
                    onShowFullAccessConfirm = { showFullAccessConfirm = true },
                    onSave = { ns, patch, onSuccess -> saveNamespace(ns, patch, onSuccess) },
                )

                SettingsTab.MODELS -> {
                    SettingsSection("模型")
                    if (llmGroups.isEmpty()) {
                        Text("正在加载模型列表…", color = Dsh.labelTertiary, fontSize = 13.sp, modifier = Modifier.padding(vertical = 12.dp))
                    }
                    // 供应商折叠卡
                    llmGroups.forEach { group ->
                        val expanded = expandedProviders.contains(group.provider)
                        val interaction = remember { MutableInteractionSource() }
                        val pressed by interaction.collectIsPressedAsState()
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(Dsh.bgLayer1)
                                .border(1.dp, Dsh.borderL1, RoundedCornerShape(12.dp))
                                .clickable(interactionSource = interaction, indication = null) {
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
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight(500),
                                    lineHeight = 20.sp,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    "${group.models.size}",
                                    color = Dsh.labelTertiary,
                                    fontSize = 12.sp,
                                    lineHeight = 20.sp
                                )
                            }
                            if (expanded) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(1.dp)
                                        .background(Dsh.borderL1)
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
                                            fontSize = 13.sp,
                                            lineHeight = 20.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f)
                                        )
                                        model.contextWindow?.let {
                                            Text(
                                                "上下文 ${formatTokenCount(it)}",
                                                color = Dsh.labelTertiary,
                                                fontSize = 11.sp,
                                                lineHeight = 18.sp
                                            )
}
}
}

// ---------- 微调卡片（Fine-tune Card：模型参数、推理设置、自动审配） ----------
@Composable
fun FineTuneCard(
    appSettings: AppSettings,
    onModelChange: () -> Unit = {},
) {
    var expanded by remember { mutableStateOf(false) }
    val providerById = appSettings.modelProviders?.associate { it.id to it } ?: mapOf()
    val currentProviderId = appSettings.defaultModelProvider?.id
    val currentModelId = appSettings.defaultModel?.id

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // 模型供应商选择
        if (appSettings.modelProviders?.isNotEmpty() == true) {
            SettingsSection("模型供应商") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("当前供应商", color = Dsh.labelTertiary, fontSize = 12.sp)
                    Spacer(Modifier.width(4.dp))
                    Text(
                        currentProviderId?.let { it.capitalize() } ?: "未选择",
                        color = Dsh.labelPrimary,
                        fontSize = 12.sp,
                    )
                    Icon(
                        ChevronRightOutline14,
                        tint = Dsh.labelCaption,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }

        // 推理等级选择
        SettingsSection("推理设置") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("推理等级", color = Dsh.labelTertiary, fontSize = 12.sp)
                Spacer(Modifier.width(4.dp))
                Text(
                    appSettings.defaultReasoningEffort?.toString() ?: "默认",
                    color = Dsh.labelPrimary,
                    fontSize = 12.sp,
                )
                Icon(
                    ChevronRightOutline14,
                    tint = Dsh.labelCaption,
                    modifier = Modifier.size(16.dp),
                )
            }
        }

        // 自动审批开关
        SettingsSection("自动审批") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("允许一次", color = Dsh.labelTertiary, fontSize = 12.sp)
                Spacer(Modifier.width(4.dp))
                Switch(
                    checked = appSettings.autoApprove ?: false,
                    onCheckedChange = { isChecked -> {
                        // 保存 autoApprove 设置到 SharedPreferences
                        val editor = context.getSharedPreferences("dsh_settings", Context.MODE_PRIVATE).edit()
                        editor.putBoolean("auto_approve", isChecked)
                        editor.apply()
                        // 更新 appSettings 以保持一致
                        appSettings = appSettings.copy(autoApprove = isChecked)
                    } },
                    colors = SwitchDefaults.colors(
                        defaultColor = Dsh.brand400,
                        thumbColor = { Dsh.labelPrimary },
                        trackColor = { Dsh.bgLayer1 },
                    ),
                )
            }
        }
    }
}
// ---------- End FineTuneCard
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                    // 添加模型：当前 Harness 协议无模型供应商写入接口，禁用并说明（WI-004 禁止假保存）
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Dsh.bgLayer1)
                            .border(1.dp, Dsh.borderL2, RoundedCornerShape(12.dp))
                            .padding(horizontal = 14.dp, vertical = 12.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(PlusOutline16, contentDescription = null, tint = Dsh.labelTertiary, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("添加模型", color = Dsh.labelTertiary, fontSize = 13.sp, fontWeight = FontWeight(500))
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "当前 Harness 版本暂不支持在手机端添加模型或保存模型密钥；请在电脑端「设置 → 模型」中添加。",
                            color = Dsh.labelTertiary,
                            fontSize = 12.sp,
                            lineHeight = 17.sp
                        )
                    }
                }

                SettingsTab.PLUGINS -> PluginsSettings(context)

                SettingsTab.ABOUT -> {
                    SettingsSection("账户余额")
                    val b = balance
                    if (b == null) {
                        SettingsItem(title = "DeepSeek 余额", description = "查询中…", onClick = {})
                    } else {
                        SettingsItem(
                            title = "DeepSeek 余额",
                            description = "${b.balance} ${b.currency}  ·  已用 ${b.used}  ·  剩余 ${b.remainder}",
                            onClick = {}
                        )
                    }
                    SettingsSection("关于")
                    SettingsItem(title = "DeepSeek Harness 移动端", description = "版本 0.4.1 · 1:1 复刻 DeepSeek Harness", onClick = {})
                    SettingsItem(title = "开源许可", description = "MIT License", onClick = {})
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
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { showFullAccessConfirm = false },
                contentAlignment = Alignment.Center
            ) {
                Column(
                    modifier = Modifier
                        .widthIn(max = 360.dp)
                        .fillMaxWidth(0.9f)
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
                                .clickable {
                                    showFullAccessConfirm = false
                                    // WI-004：真实写入服务端 permission.defaultPreset（新会话由 DSH 服务端应用）
                                    saveNamespace("permission", org.json.JSONObject().put("defaultPreset", "danger-full-access"))
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

// ---------- 通用设置（WI-004：服务端设置为唯一真实源，保存需读回校验） ----------

@Composable
private fun GeneralSettings(
    context: Context,
    host: Host?,
    appSettings: AppSettings,
    savingNs: String?,
    saveErrors: Map<String, String>,
    onOpenDevices: () -> Unit,
    onShowFullAccessConfirm: () -> Unit,
    onSave: (ns: String, patch: org.json.JSONObject, onSuccess: () -> Unit) -> Unit,
) {
    // --- Agent 预设（agent-presets.default：新会话由创建参数真实使用） ---
    SettingsSection("通用设置")
    SettingsSelectItem(
        title = "Agent 预设",
        description = "对此后新建的会话生效。运行中的会话保持它开始时的预设。",
        value = when (appSettings.agentPreset) {
            "standard" -> "标准模式"
            "code" -> "PTC 模式"
            "minimal" -> "极简模式"
            "creator", "cordis" -> "创造模式"
            else -> appSettings.agentPreset
        },
        options = listOf(
            "标准模式" to "standard",
            "PTC 模式" to "code",
            "极简模式" to "minimal",
            "创造模式" to "cordis",
        ),
        selectedId = appSettings.agentPreset,
        saving = savingNs == "agent-presets",
        error = saveErrors["agent-presets"],
        onRetry = { onSave("agent-presets", org.json.JSONObject().put("default", appSettings.agentPreset), {}) },
        onSelect = { _, id ->
            onSave("agent-presets", org.json.JSONObject().put("default", id), {})
        }
    )
    HorizontalDivider(color = Dsh.borderL1, modifier = Modifier.padding(vertical = 4.dp))

    // --- 权限（permission.defaultPreset：DSH 服务端在新会话创建时应用） ---
    SettingsSelectItem(
        title = "权限",
        description = "选择新会话的默认权限模式",
        value = when (appSettings.permissionPreset) {
            "read-only" -> "只读"
            "danger-full-access" -> "Full access"
            else -> "工作区写入"
        },
        options = listOf(
            "只读" to "read-only",
            "工作区写入" to "workspace-write",
            "Full access" to "danger-full-access",
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
    HorizontalDivider(color = Dsh.borderL1, modifier = Modifier.padding(vertical = 4.dp))

    // --- 语言（locale.preference） ---
    SettingsSelectItem(
        title = "语言",
        description = "作用于 Harness 界面语言",
        value = if (appSettings.language == "zh") "中文" else "English",
        options = listOf("中文" to "zh", "English" to "en"),
        selectedId = appSettings.language,
        saving = savingNs == "locale",
        error = saveErrors["locale"],
        onRetry = { onSave("locale", org.json.JSONObject().put("preference", appSettings.language), {}) },
        onSelect = { _, id ->
            onSave("locale", org.json.JSONObject().put("preference", id), {})
        }
    )
    HorizontalDivider(color = Dsh.borderL1, modifier = Modifier.padding(vertical = 4.dp))

    // --- 外观（ui-theme.preference：写入服务端并同步应用本地主题） ---
    SettingsSection("外观")
    val currentTheme = appSettings.theme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        ThemeCard(
            label = "浅色",
            icon = LightOutline16,
            selected = currentTheme == "light",
            modifier = Modifier.weight(1f),
            onClick = {
                ThemeManager.setThemeMode(context, "light")
                onSave("ui-theme", org.json.JSONObject().put("preference", "light"), {})
            }
        )
        ThemeCard(
            label = "深色",
            icon = DarkOutline16,
            selected = currentTheme == "dark",
            modifier = Modifier.weight(1f),
            onClick = {
                ThemeManager.setThemeMode(context, "dark")
                onSave("ui-theme", org.json.JSONObject().put("preference", "dark"), {})
            }
        )
        ThemeCard(
            label = "跟随系统",
            icon = SettingsOutline16,
            selected = currentTheme == "system",
            modifier = Modifier.weight(1f),
            onClick = {
                ThemeManager.setThemeMode(context, "system")
                onSave("ui-theme", org.json.JSONObject().put("preference", "system"), {})
            }
        )
    }
    if (savingNs == "ui-theme") {
        Text("保存中…", color = Dsh.labelTertiary, fontSize = 12.sp, modifier = Modifier.padding(start = 4.dp, top = 4.dp))
    }
    saveErrors["ui-theme"]?.let { err ->
        Row(
            modifier = Modifier.padding(start = 4.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("保存失败：$err", color = Dsh.error, fontSize = 12.sp, modifier = Modifier.weight(1f))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(7.dp))
                    .background(Dsh.bgLayer1)
                    .clickable {
                        onSave("ui-theme", org.json.JSONObject().put("preference", currentTheme), {})
                    }
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("重试", color = Dsh.brand400, fontSize = 12.sp, fontWeight = FontWeight(500))
            }
        }
    }
    HorizontalDivider(color = Dsh.borderL1, modifier = Modifier.padding(vertical = 8.dp))

    // --- 繁忙时 Enter 键行为（ui-conversation.busyEnter） ---
    SettingsSelectItem(
        title = "繁忙时 Enter 键行为",
        description = "仅在智能体运行时生效；Cmd/Ctrl+Enter 使用另一行为",
        value = if (appSettings.busyEnter == "send") "插话发送" else "换行",
        options = listOf("插话发送" to "send", "换行" to "newline"),
        selectedId = appSettings.busyEnter,
        saving = savingNs == "ui-conversation",
        error = saveErrors["ui-conversation"],
        onRetry = { onSave("ui-conversation", org.json.JSONObject().put("busyEnter", appSettings.busyEnter), {}) },
        onSelect = { _, id ->
            onSave("ui-conversation", org.json.JSONObject().put("busyEnter", id), {})
        }
    )

    // --- 默认模型（agent-default-model 只读展示：创建会话由 DSH 服务端应用） ---
    if (appSettings.defaultModelProvider != null || appSettings.defaultModel != null) {
        HorizontalDivider(color = Dsh.borderL1, modifier = Modifier.padding(vertical = 8.dp))
        SettingsSection("模型与推理")
        SettingsItem(
            title = "默认模型",
            description = listOfNotNull(
                appSettings.defaultModelProvider?.let { "供应商 $it" },
                appSettings.defaultModel?.let { "模型 $it" },
                appSettings.defaultReasoningEffort?.let { "推理等级 $it" },
            ).joinToString(" · ").ifBlank { "未配置" } + "。新会话由 Harness 服务端应用，手机端只读。",
            onClick = {}
        )
    }

    // --- 微调卡片（Fine-tune Card：模型参数、推理设置、自动审配） ----------
    FineTuneCard(appSettings = appSettings, onModelChange = { /* TODO: model refresh */ })

    // --- 会话管理（归档 / 删除） ---
    HorizontalDivider(color = Dsh.borderL1, modifier = Modifier.padding(vertical = 8.dp))
    var sessionMgmtExpanded by remember { mutableStateOf(false) }
    val mgmtInteraction = remember { MutableInteractionSource() }
    val mgmtPressed by mgmtInteraction.collectIsPressedAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (mgmtPressed) Dsh.hover else Color.Transparent)
            .clickable(interactionSource = mgmtInteraction, indication = null) {
                sessionMgmtExpanded = !sessionMgmtExpanded
            }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "会话管理",
            color = Dsh.labelSecondary,
            fontSize = 13.sp,
            fontWeight = FontWeight(500),
            modifier = Modifier.weight(1f)
        )
        Icon(
            if (sessionMgmtExpanded) ChevronUpOutline14 else ChevronDownOutline14,
            contentDescription = null,
            tint = Dsh.labelTertiary,
            modifier = Modifier.size(16.dp)
        )
    }

    AnimatedVisibility(
        visible = sessionMgmtExpanded,
        enter = expandVertically(animationSpec = tween(motionDuration(220), easing = FastOutSlowInEasing)) + fadeIn(animationSpec = tween(motionDuration(150))),
        exit = shrinkVertically(animationSpec = tween(motionDuration(180), easing = FastOutSlowInEasing)) + fadeOut(animationSpec = tween(motionDuration(150)))
    ) {
        val workspacePrefs = remember { dev.dsh.mobile.native.util.WorkspacePrefs(context) }
        var archivedIds by remember { mutableStateOf(workspacePrefs.archivedSessionIds) }
        var deletedIds by remember { mutableStateOf(workspacePrefs.deletedSessionIds) }
        var sessionList by remember { mutableStateOf<List<MobileSession>>(emptyList()) }

        // 加载会话列表
        LaunchedEffect(Unit) {
            if (host != null) {
                withContext(Dispatchers.IO) {
                    try {
                        val client = MobileApiClient(host)
                        val list = client.getSessions()
                        withContext(Dispatchers.Main) { sessionList = list }
                    } catch (_: Exception) {}
                }
            }
        }

        val archivedSessions = sessionList.filter { it.sessionId in archivedIds }
        val deletedSessions = sessionList.filter { it.sessionId in deletedIds }

        if (archivedSessions.isEmpty() && deletedSessions.isEmpty()) {
            Text(
                "没有已归档或已删除的会话",
                color = Dsh.labelTertiary,
                fontSize = 13.sp,
                modifier = Modifier.padding(vertical = 8.dp)
            )
        }

        // 已归档会话列表
        if (archivedSessions.isNotEmpty()) {
            Text(
                "已归档 (${archivedSessions.size})",
                color = Dsh.labelSecondary,
                fontSize = 13.sp,
                fontWeight = FontWeight(500),
                modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)
            )
            archivedSessions.forEach { s ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        s.title,
                        color = Dsh.labelPrimary,
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    // 取消归档
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .clickable {
                                val next = archivedIds - s.sessionId
                                archivedIds = next
                                workspacePrefs.archivedSessionIds = next
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            ChevronRightOutline14,
                            contentDescription = "取消归档",
                            tint = Dsh.labelSecondary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                    // 删除
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .clickable {
                                val nextA = archivedIds - s.sessionId
                                archivedIds = nextA
                                workspacePrefs.archivedSessionIds = nextA
                                val nextD = deletedIds + s.sessionId
                                deletedIds = nextD
                                workspacePrefs.deletedSessionIds = nextD
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            TrashOutline16,
                            contentDescription = "删除",
                            tint = Dsh.error,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }

        // 已删除会话列表
        if (deletedSessions.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(
                "已删除 (${deletedSessions.size})",
                color = Dsh.labelSecondary,
                fontSize = 13.sp,
                fontWeight = FontWeight(500),
                modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)
            )
            deletedSessions.forEach { s ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        s.title,
                        color = Dsh.labelTertiary,
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    // 恢复
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .clickable {
                                val next = deletedIds - s.sessionId
                                deletedIds = next
                                workspacePrefs.deletedSessionIds = next
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            RefreshOutline16,
                            contentDescription = "恢复",
                            tint = Dsh.brand400,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
    }
}

/** 外观三卡片（浅色/深色/跟随系统） */
@Composable
private fun ThemeCard(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (pressed) Dsh.hover else Color.Transparent)
            .border(
                1.dp,
                if (selected) Dsh.brand400.copy(alpha = 0.5f) else Dsh.borderL2,
                RoundedCornerShape(12.dp)
            )
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (selected) Dsh.brand400 else Dsh.labelSecondary,
            modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.height(6.dp))
        Text(
            label,
            color = if (selected) Dsh.brand400 else Dsh.labelPrimary,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight(500) else FontWeight(400)
        )
    }
}

/** 下拉选择设置项（DSH Select：点击触发 inline DropdownMenu，选中项带品牌蓝勾选） */
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
    var expanded by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(if (pressed) Dsh.hover else Color.Transparent)
                .clickable(interactionSource = interaction, indication = null, enabled = !saving) { expanded = true }
                .padding(horizontal = 10.dp, vertical = 8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(title, color = Dsh.labelPrimary, fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.weight(1f))
                if (saving) {
                    Text("保存中…", color = Dsh.labelTertiary, fontSize = 12.sp, lineHeight = 20.sp)
                } else {
                    Text(value, color = Dsh.labelTertiary, fontSize = 13.sp, lineHeight = 20.sp)
                    Icon(
                        ChevronDownOutline14,
                        contentDescription = null,
                        tint = Dsh.labelCaption,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            if (description != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    description,
                    color = Dsh.labelTertiary,
                    fontSize = 12.sp,
                    lineHeight = 17.sp
                )
            }
            // 保存失败：行内错误 + 重试（不关闭页面、不清空选择）
            if (error != null) {
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("保存失败：$error", color = Dsh.error, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.weight(1f))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(7.dp))
                            .background(Dsh.bgLayer1)
                            .clickable(enabled = onRetry != null) { onRetry?.invoke() }
                            .padding(horizontal = 10.dp, vertical = 3.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("重试", color = Dsh.brand400, fontSize = 12.sp, fontWeight = FontWeight(500))
                    }
                }
            }
        }

        // Inline 下拉选择器（对标 web UI DropdownMenu 风格）
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = Dsh.bgLayer1,
            shape = RoundedCornerShape(12.dp),
            tonalElevation = 0.dp,
            shadowElevation = 12.dp,
            border = BorderStroke(1.dp, Dsh.borderL2)
        ) {
            Column(
                modifier = Modifier
                    .width(200.dp)
                    .padding(vertical = 4.dp)
            ) {
                options.forEach { (label, id) ->
                    val isSelected = id == (selectedId ?: value)
                    val optInteraction = remember { MutableInteractionSource() }
                    val optPressed by optInteraction.collectIsPressedAsState()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(40.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (optPressed) Dsh.hover else Color.Transparent)
                            .clickable(interactionSource = optInteraction, indication = null) {
                                onSelect(label, id)
                                expanded = false
                            }
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            label,
                            color = if (isSelected) Dsh.brand400 else Dsh.labelPrimary,
                            fontSize = 13.sp,
                            lineHeight = 20.sp,
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

// ---------- 插件设置（WI-004：无插件配置写接口，只读展示，禁止假保存） ----------

@Composable
private fun PluginsSettings(context: Context) {
    SettingsSection("插件")
    Text(
        "当前 Harness 版本暂不支持在手机端读取或修改插件配置；请在电脑端「设置 → 插件」中管理。",
        color = Dsh.labelTertiary,
        fontSize = 12.sp,
        lineHeight = 18.sp,
        modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
    )

    // 已安装插件只读列表（无配置读写入口）
    listOf(
        "dsh-deepharness" to "手机远程连接",
        "dsh-client-ui-theme" to "主题",
        "dsh-client-locale" to "多语言",
        "dshmarket" to "插件市场",
        "@zseven-w/dsh-noema" to "记忆系统",
    ).forEach { (name, label) ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(Dsh.bgLayer1)
                .padding(horizontal = 10.dp, vertical = 8.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 36.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(name, color = Dsh.labelPrimary, fontSize = 14.sp, lineHeight = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(2.dp))
                    Text(label, color = Dsh.labelTertiary, fontSize = 12.sp, lineHeight = 17.sp)
                }
            }
        }
        Spacer(Modifier.height(2.dp))
    }
}

// ---------- Agent 预设（DSH agent-preset：选项化列表） ----------

@Composable
private fun PresetsSettings(context: Context) {
    SettingsSection("Agent 预设")
    Text(
        "预设即一个会话的 Agent 所运行的插件组装：它的工具、提示词与能力。对此后新建的会话生效。",
        color = Dsh.labelTertiary,
        fontSize = 12.sp,
        lineHeight = 18.sp,
        modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
    )

    val prefs = context.getSharedPreferences("dsh_settings", Context.MODE_PRIVATE)
    var defaultPreset by remember { mutableStateOf(prefs.getString("default_preset", "standard") ?: "standard") }

    listOf(
        Triple("standard", "标准模式", "功能完整的编码 Agent，支持文件编辑、Shell、文件与网页检索、Skills、计划、目标、子代理和工作流。"),
        Triple("code", "模式", "具备标准模式的全部能力，并通过 Code Mode 呈现工具，让模型用一个 TypeScript 程序组合多步操作。"),
        Triple("minimal", "极简模式", "仅提供持久 bash 与 str_replace_edit 的双工具编码 Agent。"),
        Triple("creator", "创造模式", "用于创建自定义 Agent preset：具备标准模式的全部能力，并提供运行时检查、插件实验和 preset 创作指导。"),
    ).forEach { (id, title, desc) ->
        val isDefault = id == defaultPreset
        val interaction = remember { MutableInteractionSource() }
        val pressed by interaction.collectIsPressedAsState()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(if (pressed || isDefault) Dsh.hover else Dsh.bgLayer1)
                .border(1.dp, if (isDefault) Dsh.brand400.copy(alpha = 0.5f) else Dsh.borderL1, RoundedCornerShape(12.dp))
                .clickable(interactionSource = interaction, indication = null) {
                    defaultPreset = id
                    prefs.edit().putString("default_preset", id).apply()
                    Toast.makeText(context, "已设为默认：$title", Toast.LENGTH_SHORT).show()
                }
                .padding(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = Dsh.labelPrimary, fontSize = 14.sp, fontWeight = FontWeight(500), lineHeight = 20.sp, modifier = Modifier.weight(1f))
                if (isDefault) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(Dsh.brand400.copy(alpha = 0.15f))
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text("设为默认", color = Dsh.brand400, fontSize = 10.sp, lineHeight = 14.sp)
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(desc, color = Dsh.labelTertiary, fontSize = 12.sp, lineHeight = 17.sp)
        }
        Spacer(Modifier.height(8.dp))
    }
}

private fun formatTokenCount(n: Long): String = when {
    n >= 1_000_000 -> String.format(java.util.Locale.US, "%.1fM", n / 1_000_000.0)
    n >= 1000 -> String.format(java.util.Locale.US, "%.1fK", n / 1000.0)
    else -> "$n"
}

@Composable
private fun SettingsSection(title: String) {
    Text(
        title,
        color = Dsh.labelTertiary,
        fontSize = 12.sp,
        lineHeight = 18.sp,
        modifier = Modifier.padding(top = 16.dp, bottom = 6.dp, start = 4.dp)
    )
}

@Composable
private fun SettingsItem(
    title: String,
    description: String,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (pressed) Dsh.hover else Color.Transparent)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = Dsh.labelPrimary, fontSize = 14.sp, lineHeight = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (description.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(description, color = Dsh.labelTertiary, fontSize = 12.sp, lineHeight = 17.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        Icon(
            ChevronRightOutline14,
            contentDescription = null,
            tint = Dsh.labelCaption,
            modifier = Modifier.size(16.dp)
        )
    }
}
