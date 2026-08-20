package dev.dsh.mobile.devices
import dev.dsh.mobile.native.WorkspaceActivity
import dev.dsh.mobile.native.EditOutline16
import dev.dsh.mobile.native.rememberMotionSpin
import dev.dsh.mobile.native.dialogEnterState
import dev.dsh.mobile.core.Dsh
import dev.dsh.mobile.core.Host
import dev.dsh.mobile.core.DeviceName
import dev.dsh.mobile.core.DshTheme
import dev.dsh.mobile.core.HostLoadResult
import dev.dsh.mobile.core.HostStore
import dev.dsh.mobile.core.PairClient
import dev.dsh.mobile.core.PinnedSsl

import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.lifecycle.lifecycleScope
import java.net.URI

/**
 * 设备管理 Hub —— 1:1 复刻 HStudio (Hermes Studio) 设备页设计语言：
 * 22px 大标题 / 17px 圆角卡片网格 / 薄荷绿在线状态胶囊 / 虚线添加卡片 /
 * 底部滑出配对面板（扫码添加 + 手动添加表单）/ HStudio 风格确认弹窗。
 */
class DevicesActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        setContent {
            DshTheme {
                DevicesScreen(
                    onSelectHost = { host, onDone ->
                        lifecycleScope.launch {
                            val ok = withContext(Dispatchers.IO) {
                                PairClient.health(host.baseUrl, host.certFingerprint) != null
                            }
                            if (isDestroyed) return@launch
                            onDone(ok)
                            if (ok) {
                                if (!HostStore.upsert(this@DevicesActivity, host)) {
                                    Toast.makeText(this@DevicesActivity, "凭据无法保存，请重新配对", Toast.LENGTH_LONG).show()
                                    return@launch
                                }
                                startActivity(Intent(this@DevicesActivity, WorkspaceActivity::class.java)
                                    .putExtra("hostBaseUrl", host.baseUrl))
                            }
                        }
                    },
                    onScanClick = {
                        startActivity(Intent(this, ScanActivity::class.java))
                    },
                    onManualPair = { name, url, code, fingerprint, onSuccess, onError ->
                        lifecycleScope.launch {
                            try {
                                val r = withContext(Dispatchers.IO) {
                                    PairClient.pair(url, code, DeviceName.of(this@DevicesActivity), fingerprint)
                                }
                                if (isDestroyed) return@launch
                                val newHost = Host(name.ifBlank { r.name }, r.baseUrl, r.token, r.deviceId, r.certFingerprint)
                                if (!HostStore.upsert(this@DevicesActivity, newHost)) {
                                    onError("凭据无法保存，请重新配对")
                                    return@launch
                                }
                                onSuccess(newHost)
                                Toast.makeText(this@DevicesActivity, "设备连接成功", Toast.LENGTH_SHORT).show()
                            } catch (e: Exception) {
                                val unwrapped = PinnedSsl.unwrap(e)
                                if (!isDestroyed) onError(unwrapped.message ?: "连接失败，请检查地址或配对码")
                            }
                        }
                    }
                )
            }
        }
    }
}

// --- HStudio 设计系统常量（映射 Dsh 主题） ---
object HStudio {
    val bgPrimary: Color
        @Composable
        @ReadOnlyComposable
        get() = Dsh.bgBase

    val bgCard: Color
        @Composable
        @ReadOnlyComposable
        get() = Dsh.bgSidebar

    val bgSecondary: Color
        @Composable
        @ReadOnlyComposable
        get() = Dsh.bgLayer1

    val bgInput: Color
        @Composable
        @ReadOnlyComposable
        get() = Dsh.bgInput

    val pressed: Color
        @Composable
        @ReadOnlyComposable
        get() = Dsh.hover

    val textPrimary: Color
        @Composable
        @ReadOnlyComposable
        get() = Dsh.labelPrimary

    val textSecondary: Color
        @Composable
        @ReadOnlyComposable
        get() = Dsh.labelSecondary

    val textMuted: Color
        @Composable
        @ReadOnlyComposable
        get() = Dsh.labelTertiary

    val border: Color
        @Composable
        @ReadOnlyComposable
        get() = Dsh.borderL2

    val borderLight: Color
        @Composable
        @ReadOnlyComposable
        get() = Dsh.borderL1

    val inputBorder: Color
        @Composable
        @ReadOnlyComposable
        get() = Dsh.borderL3

    val accent: Color
        @Composable
        @ReadOnlyComposable
        get() = Dsh.brand400

    val onAccent = Color(0xFFFFFFFF)        // 主按钮文字

    val green: Color
        @Composable
        @ReadOnlyComposable
        get() = Dsh.success

    val greenBg = Color(0x1F22C55E)
    val purple = Color(0xFFBEA7E3)          // 云端
    val purpleBg = Color(0x1ABEA7E3)

    val blue: Color
        @Composable
        @ReadOnlyComposable
        get() = Dsh.brand400

    val blueBg = Color(0x1A679EFE)

    val error: Color
        @Composable
        @ReadOnlyComposable
        get() = Dsh.error

    val errorBg: Color
        @Composable
        @ReadOnlyComposable
        get() = Dsh.errorBg

    val overlay: Color
        @Composable
        @ReadOnlyComposable
        get() = Dsh.bgOverlay
}

private enum class DeviceState { CHECKING, ONLINE, OFFLINE, CONNECTING }

private data class DeviceUi(
    val host: Host,
    val state: DeviceState = DeviceState.CHECKING,
    val latencyMs: Long? = null,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicesScreen(
    onSelectHost: (Host, (Boolean) -> Unit) -> Unit,
    onScanClick: () -> Unit,
    onManualPair: (name: String, url: String, code: String, fingerprint: String?, onSuccess: (Host) -> Unit, onError: (String) -> Unit) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var devices by remember { mutableStateOf(emptyList<DeviceUi>()) }
    var showPairingPanel by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<Host?>(null) }
    var deleteTarget by remember { mutableStateOf<Host?>(null) }
    var allOfflineError by remember { mutableStateOf<String?>(null) }

    val scope = rememberCoroutineScope()
    var healthJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    fun refreshHealth() {
        healthJob?.cancel()
        if (devices.isEmpty()) return
        val urls = devices.map { it.host.baseUrl }.toSet()
        devices = devices.map { it.copy(state = DeviceState.CHECKING) }
        healthJob = scope.launch {
            val snapshot = devices
            val results = withContext(Dispatchers.IO) {
                snapshot.map { d ->
                    async {
                        val ms = PairClient.health(d.host.baseUrl, d.host.certFingerprint)
                        d.host.baseUrl to d.copy(
                            state = if (ms != null) DeviceState.ONLINE else DeviceState.OFFLINE,
                            latencyMs = ms,
                        )
                    }
                }.awaitAll().toMap()
            }
            devices = devices.map { cur ->
                if (cur.host.baseUrl in urls) results[cur.host.baseUrl] ?: cur else cur
            }
            val anyOnline = devices.any { it.state == DeviceState.ONLINE }
            allOfflineError = if (!anyOnline && devices.isNotEmpty()) "所有设备均离线，请检查电脑端代理是否运行" else null
        }
    }

    fun reload() {
        when (val loaded = HostStore.loadResult(context)) {
            is HostLoadResult.Ok -> {
                devices = loaded.hosts.map { DeviceUi(it) }
                allOfflineError = null
                refreshHealth()
            }
            HostLoadResult.Empty -> {
                devices = emptyList()
                allOfflineError = null
            }
            HostLoadResult.Undecryptable -> {
                devices = emptyList()
                allOfflineError = "凭据无法读取，请重新配对"
            }
        }
    }

    // 每次回到前台重读设备列表并刷新健康状态
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) reload()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(Unit) { reload() }

    LaunchedEffect(lifecycleOwner) {
        while (true) {
            delay(30_000)
            if (lifecycleOwner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) {
                refreshHealth()
            }
        }
    }

    // ---------- 页面骨架 ----------
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(HStudio.bgPrimary)
    ) {
        // 头部（固定，含安全区）
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(HStudio.bgPrimary)
                .statusBarsPadding()
                .padding(top = 18.dp, start = 18.dp, end = 18.dp, bottom = 18.dp)
        ) {
            // section-heading：标题 + 设备计数
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "我的设备",
                        color = HStudio.textPrimary,
                        fontSize = 22.sp,
                        fontWeight = FontWeight(650),
                        lineHeight = 29.sp,
                        letterSpacing = (-0.25).sp
                    )
                    Text(
                        "管理你的 DSH Links",
                        color = HStudio.textSecondary,
                        fontSize = 12.sp,
                        lineHeight = 18.sp
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 设备计数胶囊
                    Box(
                        modifier = Modifier
                            .height(24.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(HStudio.bgSecondary)
                            .padding(horizontal = 7.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "${devices.size}",
                            color = HStudio.textSecondary,
                            fontSize = 11.sp,
                            lineHeight = 24.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }

        // 内容：设备网格（max-width 680 居中，左右 18dp）
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 18.dp)
                .padding(bottom = 28.dp)
        ) {
            val spanCount = if (context.resources.configuration.screenWidthDp >= 520) 2 else 1

            if (devices.isEmpty()) {
                EmptyDevicesState(
                    onAdd = { showPairingPanel = true }
                )
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(spanCount),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(devices, key = { it.host.baseUrl + "|" + it.host.name }) { device ->
                        DeviceCard(
                            device = device,
                            onOpen = {
                                onSelectHost(device.host) { ok ->
                                    if (!ok) {
                                        Toast.makeText(context, "连接失败，请检查设备是否在线", Toast.LENGTH_SHORT).show()
                                        refreshHealth()
                                    }
                                }
                            },
                            onRename = { renameTarget = device.host },
                            onDelete = { deleteTarget = device.host },
                        )
                    }
                    item(key = "add-device") {
                        AddDeviceCard(onClick = { showPairingPanel = true })
                    }
                }

                // 全部离线提示条（error-state 风格）
                allOfflineError?.let { msg ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(HStudio.bgSecondary)
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(msg, color = HStudio.textMuted, fontSize = 11.sp)
                        Text(
                            "重新同步",
                            color = HStudio.textPrimary,
                            fontWeight = FontWeight(600),
                            fontSize = 11.sp,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable { refreshHealth() }
                                .padding(4.dp)
                        )
                    }
                }
            }
        }
    }

    // ---------- 配对面板（底部滑出） ----------
    if (showPairingPanel) {
        HStudioPairingPanel(
            onDismiss = { showPairingPanel = false },
            onScan = {
                showPairingPanel = false
                onScanClick()
            },
            onManualPair = onManualPair,
        )
    }

    // ---------- 删除确认弹窗 ----------
    deleteTarget?.let { target ->
        HStudioConfirmDialog(
            title = "删除设备",
            content = "删除设备“${target.name}”？此操作仅移除本机记录，不影响电脑端。",
            confirmText = "删除",
            danger = true,
            onConfirm = {
                HostStore.remove(context, target.name)
                deleteTarget = null
                reload()
            },
            onDismiss = { deleteTarget = null }
        )
    }

    // ---------- 改名弹窗 ----------
    renameTarget?.let { target ->
        HStudioRenameDialog(
            currentName = target.name,
            onDismiss = { renameTarget = null },
            onSave = { newName ->
                if (newName.isNotBlank()) {
                    HostStore.rename(context, target.baseUrl, newName.trim())
                    renameTarget = null
                    reload()
                }
            }
        )
    }
}

// ---------- 设备卡片 ----------

private fun statusLabel(state: DeviceState): String = when (state) {
    DeviceState.CHECKING -> "检测中"
    DeviceState.ONLINE -> "在线"
    DeviceState.OFFLINE -> "离线"
    DeviceState.CONNECTING -> "连接中"
}

@Composable
private fun DeviceCard(
    device: DeviceUi,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.96f else 1f)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer(scaleX = scale, scaleY = scale)
            .clip(RoundedCornerShape(17.dp))
            .background(HStudio.bgCard)
            .border(1.dp, HStudio.borderLight, RoundedCornerShape(17.dp))
            .clickable(interactionSource = interaction, indication = null, onClick = onOpen)
            .padding(17.dp)
    ) {
        // 顶部：显示器符号 + 状态胶囊
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            MonitorGlyph()
            val state = device.state
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        when (state) {
                            DeviceState.ONLINE -> HStudio.greenBg
                            DeviceState.CONNECTING -> HStudio.blueBg
                            else -> HStudio.bgSecondary
                        }
                    )
                    .padding(horizontal = 7.dp, vertical = 4.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(5.dp)
                            .clip(CircleShape)
                            .background(
                                when (state) {
                                    DeviceState.ONLINE -> HStudio.green
                                    DeviceState.CONNECTING -> HStudio.blue
                                    else -> HStudio.textMuted
                                }
                            )
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        statusLabel(state),
                        color = when (state) {
                            DeviceState.ONLINE -> HStudio.green
                            DeviceState.CONNECTING -> HStudio.blue
                            else -> HStudio.textMuted
                        },
                        fontSize = 10.sp,
                        lineHeight = 12.sp
                    )
                }
            }
        }

        // 名称 + 连接信息
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 18.dp)
        ) {
            Text(
                device.host.name,
                color = HStudio.textPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight(620),
                lineHeight = 20.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(4.dp))
            Text(
                hostDisplayName(device.host.baseUrl),
                color = HStudio.textSecondary,
                fontSize = 10.sp,
                lineHeight = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        // 标签行：来源（局域网）+ 端点（桌面端）+ 延迟
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 11.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            HTag("局域网", HStudio.green, HStudio.greenBg, monospace = false)
            HTag("桌面端", HStudio.green, HStudio.greenBg, monospace = false)
            if (device.latencyMs != null && device.state == DeviceState.ONLINE) {
                HTag("${device.latencyMs}ms", HStudio.blue, HStudio.blueBg, monospace = true)
            }
        }

        // 底部操作：修改名称 / 删除
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 11.dp)
        ) {
            CardAction("修改名称", false, modifier = Modifier.weight(1f), onClick = onRename)
            Spacer(Modifier.width(7.dp))
            CardAction("删除", true, modifier = Modifier.weight(1f), onClick = onDelete)
        }
    }
}

@Composable
private fun MonitorGlyph() {
    // 31x31 显示器符号（CSS 1:1：屏幕 31x22 圆角 5 + 屏幕点 + 底座 + 立杆）
    Box(modifier = Modifier.size(31.dp)) {
        Box(
            modifier = Modifier
                .size(width = 31.dp, height = 22.dp)
                .border(1.5.dp, HStudio.textPrimary, RoundedCornerShape(5.dp))
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 3.dp, bottom = 3.dp)
                    .size(2.dp)
                    .clip(CircleShape)
                    .background(HStudio.textPrimary)
            )
        }
        // 立杆
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(top = 17.dp)
                .width(1.5.dp)
                .height(5.dp)
                .background(HStudio.textPrimary)
        )
        // 底座
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 2.dp)
                .width(11.dp)
                .height(6.dp)
                .border(1.5.dp, HStudio.textPrimary, RoundedCornerShape(2.dp))
        )
    }
}

@Composable
private fun HTag(text: String, color: Color, bg: Color, monospace: Boolean) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .padding(horizontal = 6.dp, vertical = 3.dp)
    ) {
        Text(
            text,
            color = color,
            fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default,
            fontWeight = FontWeight(600),
            fontSize = 8.sp,
            lineHeight = 12.sp
        )
    }
}

@Composable
private fun CardAction(
    label: String,
    danger: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        modifier = modifier
            .height(28.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (pressed) HStudio.pressed else HStudio.bgSecondary)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = if (danger) HStudio.error else HStudio.textSecondary,
            fontSize = 10.sp,
            fontWeight = FontWeight(550)
        )
    }
}

// ---------- 添加设备卡片（虚线边框） ----------

@Composable
private fun AddDeviceCard(onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(17.dp))
            .background(if (pressed) HStudio.pressed else Color.Transparent)
            .dashedBorder(1.5.dp, if (pressed) HStudio.textMuted else HStudio.border, 17.dp)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(17.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(HStudio.bgSecondary),
            contentAlignment = Alignment.Center
        ) {
            Text("+", color = HStudio.textPrimary, fontSize = 24.sp, fontWeight = FontWeight(300))
        }
        Spacer(Modifier.height(9.dp))
        Text("添加设备", color = HStudio.textPrimary, fontSize = 13.sp, fontWeight = FontWeight(600), lineHeight = 18.sp)
        Spacer(Modifier.height(2.dp))
        Text("扫码或输入设备连接", color = HStudio.textMuted, fontSize = 10.sp, lineHeight = 15.sp, textAlign = TextAlign.Center)
    }
}

/** 虚线边框（HStudio add-device-card: 1.5px dashed var(--ink-border)） */
private fun Modifier.dashedBorder(width: Dp, color: Color, cornerRadius: Dp): Modifier =
    drawBehind {
        val strokeWidth = width.toPx()
        val dash = 6.dp.toPx()
        val gap = 5.dp.toPx()
        val corner = cornerRadius.toPx()
        val path = Path().apply {
            addRoundRect(
                RoundRect(
                    left = strokeWidth / 2,
                    top = strokeWidth / 2,
                    right = size.width - strokeWidth / 2,
                    bottom = size.height - strokeWidth / 2,
                    radiusX = corner,
                    radiusY = corner,
                )
            )
        }
        drawPath(
            path,
            color,
            style = Stroke(
                width = strokeWidth,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash, gap)),
            )
        )
    }

// ---------- 空态 ----------

@Composable
private fun EmptyDevicesState(onAdd: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("还没有配对任何设备", color = HStudio.textPrimary, fontSize = 14.sp, fontWeight = FontWeight(600))
        Spacer(Modifier.height(6.dp))
        Text(
            "在电脑端 dsh（DeepSeek Harness）打开「📱 手机连接」面板\n扫描二维码或输入配对码即可接入",
            color = HStudio.textMuted,
            fontSize = 11.sp,
            lineHeight = 17.sp,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(20.dp))
        // 主按钮（HStudio accent 近白底）
        Box(
            modifier = Modifier
                .width(160.dp)
                .height(44.dp)
                .clip(RoundedCornerShape(13.dp))
                .background(HStudio.accent)
                .clickable(onClick = onAdd),
            contentAlignment = Alignment.Center
        ) {
            Text("添加设备", color = HStudio.onAccent, fontSize = 13.sp, fontWeight = FontWeight(600))
        }
    }
}

// ---------- 配对面板（底部滑出） ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HStudioPairingPanel(
    onDismiss: () -> Unit,
    onScan: () -> Unit,
    onManualPair: (name: String, url: String, code: String, fingerprint: String?, onSuccess: (Host) -> Unit, onError: (String) -> Unit) -> Unit,
) {
    var mode by remember { mutableStateOf<PairingMode>(PairingMode.CHOOSE) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = HStudio.bgCard,
        contentColor = HStudio.textPrimary,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        scrimColor = HStudio.overlay,
        dragHandle = null,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
        ) {
            // 头部：标题 + 描述 + 关闭
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        if (mode == PairingMode.CHOOSE) "添加设备" else "手动添加",
                        color = HStudio.textPrimary,
                        fontSize = 18.sp,
                        fontWeight = FontWeight(650),
                        lineHeight = 24.sp
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        if (mode == PairingMode.CHOOSE) "选择一种设备连接方式。" else "输入局域网地址和配对码",
                        color = HStudio.textSecondary,
                        fontSize = 11.sp,
                        lineHeight = 17.sp
                    )
                }
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(HStudio.bgSecondary)
                        .clickable(onClick = onDismiss),
                    contentAlignment = Alignment.Center
                ) {
                    Text("×", color = HStudio.textSecondary, fontSize = 19.sp, fontWeight = FontWeight(300))
                }
            }

            when (mode) {
                PairingMode.CHOOSE -> {
                    // 方法列表
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 18.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        MethodOption(
                            title = "扫码添加",
                            description = "扫描电脑端 DSH Links 二维码",
                            icon = Icons.Default.QrCodeScanner,
                            onClick = onScan
                        )
                        MethodOption(
                            title = "手动添加",
                            description = "输入局域网地址和配对码",
                            icon = EditOutline16,
                            onClick = { mode = PairingMode.MANUAL }
                        )
                    }
                }

                PairingMode.MANUAL -> {
                    ManualPairForm(
                        onPair = { name, url, code, fingerprint, onSuccess, onError ->
                            onManualPair(name, url, code, fingerprint, { host ->
                                onSuccess(host)
                                onDismiss()
                            }, onError)
                        },
                        onBack = { mode = PairingMode.CHOOSE }
                    )
                }
            }
        }
    }
}

private enum class PairingMode { CHOOSE, MANUAL }

@Composable
private fun MethodOption(
    title: String,
    description: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 68.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (pressed) HStudio.pressed else HStudio.bgInput)
            .border(1.dp, HStudio.borderLight, RoundedCornerShape(14.dp))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(HStudio.bgSecondary),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = HStudio.textPrimary, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(11.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = HStudio.textPrimary, fontSize = 13.sp, fontWeight = FontWeight(600), lineHeight = 18.sp)
            Spacer(Modifier.height(2.dp))
            Text(description, color = HStudio.textMuted, fontSize = 10.sp, lineHeight = 15.sp)
        }
        Text("›", color = HStudio.textMuted, fontSize = 22.sp, fontWeight = FontWeight(300))
    }
}

@Composable
private fun ManualPairForm(
    onPair: (name: String, url: String, code: String, fingerprint: String?, onSuccess: (Host) -> Unit, onError: (String) -> Unit) -> Unit,
    onBack: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var tofuFingerprint by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun submit(fingerprint: String?) {
        loading = true
        error = null
        onPair(name.trim(), url.trim(), code.trim(), fingerprint, { _ ->
            loading = false
        }) { msg ->
            loading = false
            error = msg
        }
    }

    if (tofuFingerprint != null) {
        Dialog(
            onDismissRequest = { if (!loading) tofuFingerprint = null },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(HStudio.overlay)
                    .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                        if (!loading) tofuFingerprint = null
                    },
                contentAlignment = Alignment.Center
            ) {
                Column(
                    modifier = Modifier
                        .widthIn(max = 360.dp)
                        .fillMaxWidth(0.9f)
                        .clip(RoundedCornerShape(14.dp))
                        .background(HStudio.bgCard)
                        .border(1.dp, HStudio.border, RoundedCornerShape(14.dp))
                        .padding(18.dp)
                ) {
                    Text("核对主机证书", color = HStudio.textPrimary, fontSize = 15.sp, fontWeight = FontWeight(650))
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "请对照电脑端「手机连接」面板上的 TLS 指纹。不一致则可能正在被拦截，不要继续。",
                        color = HStudio.textMuted,
                        fontSize = 11.sp,
                        lineHeight = 17.sp,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        PinnedSsl.formatFingerprint(tofuFingerprint!!),
                        color = HStudio.textPrimary,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 18.sp,
                    )
                    Spacer(Modifier.height(16.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        ConfirmButton("取消", false, onClick = { tofuFingerprint = null })
                        Spacer(Modifier.width(8.dp))
                        ConfirmButton("指纹一致，连接", true, onClick = {
                            val fp = tofuFingerprint
                            tofuFingerprint = null
                            submit(fp)
                        })
                    }
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        PairingField(
            label = "设备名称",
            placeholder = "例如 工作室电脑",
            value = name,
            onValueChange = { name = it },
        )
        PairingField(
            label = "设备连接",
            placeholder = "192.168.1.10:18640",
            value = url,
            onValueChange = { url = it },
            monospace = true,
        )
        PairingField(
            label = "6 位配对码",
            placeholder = "电脑端「手机连接」面板实时显示",
            value = code,
            onValueChange = { if (it.length <= 8) code = it.trim() },
            monospace = true,
        )

        if (error != null) {
            Text(error!!, color = HStudio.error, fontSize = 10.sp, lineHeight = 14.sp)
        }

        val angle = rememberMotionSpin(750, label = "spinAngle")
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .clip(RoundedCornerShape(13.dp))
                .background(HStudio.accent)
                .clickable(
                    enabled = !loading,
                    onClick = {
                        val cleanUrl = url.trim()
                        val cleanCode = code.trim()
                        if (cleanUrl.isEmpty() || cleanCode.isEmpty()) {
                            error = "请完整输入设备连接地址与配对码"
                            return@clickable
                        }
                        loading = true
                        error = null
                        if (!PinnedSsl.shouldPin(cleanUrl)) {
                            submit(null)
                            return@clickable
                        }
                        scope.launch {
                            try {
                                val fp = withContext(Dispatchers.IO) { PinnedSsl.peekFingerprint(cleanUrl) }
                                loading = false
                                tofuFingerprint = fp
                            } catch (e: Exception) {
                                loading = false
                                error = PinnedSsl.unwrap(e).message ?: "无法读取主机证书"
                            }
                        }
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            if (loading) {
                Box(
                    modifier = Modifier
                        .size(14.dp)
                        .rotate(angle ?: 0f)
                        .border(1.5.dp, HStudio.onAccent, CircleShape)
                )
            } else {
                Text("连接设备", color = HStudio.onAccent, fontSize = 13.sp, fontWeight = FontWeight(600))
            }
        }
        Box(
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .padding(top = 2.dp)
                .clip(RoundedCornerShape(6.dp))
                .clickable(onClick = onBack)
                .padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Text("返回选择方式", color = HStudio.textSecondary, fontSize = 11.sp)
        }
    }
}

@Composable
private fun PairingField(
    label: String,
    placeholder: String,
    value: String,
    onValueChange: (String) -> Unit,
    monospace: Boolean = false,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, color = HStudio.textSecondary, fontSize = 11.sp, fontWeight = FontWeight(550), lineHeight = 16.sp)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = TextStyle(
                color = HStudio.textPrimary,
                fontSize = if (monospace) 12.sp else 13.sp,
                fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default,
            ),
            cursorBrush = SolidColor(HStudio.textPrimary),
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(HStudio.bgInput)
                .border(1.dp, HStudio.inputBorder, RoundedCornerShape(12.dp))
                .padding(horizontal = 13.dp),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) {
                        Text(placeholder, color = HStudio.textMuted, fontSize = if (monospace) 12.sp else 13.sp)
                    }
                    inner()
                }
            }
        )
    }
}

// ---------- 确认弹窗（HStudio app-confirm 风格） ----------

@Composable
private fun HStudioConfirmDialog(
    title: String,
    content: String,
    confirmText: String,
    danger: Boolean = false,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
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
                .background(HStudio.overlay)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
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
                    .background(HStudio.bgCard)
                    .border(1.dp, HStudio.border, RoundedCornerShape(14.dp))
                    .padding(18.dp)
            ) {
                Text(title, color = HStudio.textPrimary, fontSize = 15.sp, fontWeight = FontWeight(650), lineHeight = 21.sp)
                Spacer(Modifier.height(8.dp))
                Text(content, color = HStudio.textMuted, fontSize = 11.sp, lineHeight = 17.sp)
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ConfirmButton("取消", false, onClick = onDismiss)
                    Spacer(Modifier.width(8.dp))
                    ConfirmButton(confirmText, danger, onClick = onConfirm)
                }
            }
        }
    }
}

@Composable
private fun ConfirmButton(
    label: String,
    danger: Boolean,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        modifier = Modifier
            .widthIn(min = 64.dp)
            .height(34.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(
                when {
                    danger -> HStudio.error
                    pressed -> HStudio.pressed
                    else -> Color.Transparent
                }
            )
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = if (danger) Color.White else HStudio.textSecondary,
            fontSize = 12.sp,
            fontWeight = FontWeight(550)
        )
    }
}

// ---------- 改名弹窗 ----------

@Composable
private fun HStudioRenameDialog(
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
                .background(HStudio.overlay)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
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
                    .background(HStudio.bgCard)
                    .border(1.dp, HStudio.border, RoundedCornerShape(14.dp))
                    .padding(18.dp)
            ) {
                Text("修改设备名称", color = HStudio.textPrimary, fontSize = 15.sp, fontWeight = FontWeight(650), lineHeight = 21.sp)
                Spacer(Modifier.height(8.dp))
                Text("设置一个方便识别的设备名称。", color = HStudio.textMuted, fontSize = 11.sp, lineHeight = 17.sp)
                Spacer(Modifier.height(14.dp))
                BasicTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    textStyle = TextStyle(color = HStudio.textPrimary, fontSize = 13.sp),
                    cursorBrush = SolidColor(HStudio.textPrimary),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(HStudio.bgInput)
                        .border(1.dp, HStudio.inputBorder, RoundedCornerShape(12.dp))
                        .padding(horizontal = 13.dp)
                )
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    ConfirmButton("取消", false, onClick = onDismiss)
                    Spacer(Modifier.width(8.dp))
                    ConfirmButton("保存", true, onClick = { onSave(name) })
                }
            }
        }
    }
}

/** baseUrl → 展示名：去协议、去末尾斜杠。 */
private fun hostDisplayName(baseUrl: String): String {
    return try {
        val uri = URI(baseUrl.trimEnd('/'))
        (uri.host ?: baseUrl) + (if (uri.port > 0) ":${uri.port}" else "")
    } catch (e: Exception) {
        baseUrl
    }
}
