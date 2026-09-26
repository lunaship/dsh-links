package dev.deeplinks.devices

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import dev.deeplinks.core.DshType
import dev.deeplinks.native.AppRoute
import dev.deeplinks.native.MainActivity
import dev.deeplinks.native.EditOutline16
import dev.deeplinks.native.DshHaptic
import dev.deeplinks.native.dshPressScale
import dev.deeplinks.native.rememberDshHaptic
import dev.deeplinks.native.rememberMotionSpin
import dev.deeplinks.native.ChevronRightOutline14
import dev.deeplinks.native.dialogMotionState
import dev.deeplinks.core.dshRipple
import dev.deeplinks.core.Dsh
import dev.deeplinks.core.Host
import dev.deeplinks.core.HostLoadResult
import dev.deeplinks.core.HostStore
import dev.deeplinks.core.EXTRA_AUTH_NOTICE
import dev.deeplinks.core.DshS
import dev.deeplinks.core.L
import dev.deeplinks.core.HostHealth
import dev.deeplinks.core.PairClient
import dev.deeplinks.core.PinnedSsl
import dev.deeplinks.native.MobileApiClient
import dev.deeplinks.native.shouldBlockLocalHostRemoval
import dev.deeplinks.native.shouldDemoteRelayOnAuth
import dev.deeplinks.native.DshRadius
import dev.deeplinks.native.DshSheetShape
import dev.deeplinks.native.ui.DshSheetGrabber
import dev.deeplinks.native.util.RenameDialogKind
import dev.deeplinks.native.util.renameDialogKind

import androidx.activity.ComponentActivity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URI

private sealed class HostOpenResult {
    data class Ok(val host: Host) : HostOpenResult()
    data object Offline : HostOpenResult()
    data class Auth(val error: Throwable) : HostOpenResult()
}

/**
 * 设备管理 Hub —— 对齐全 App 主设计语言（DeepSeek 风生产力工具）：
 * 单列紧凑列表 + 描边设备图标 + 成功色状态点 / 虚线添加卡片 /
 * 底部滑出配对面板（扫码添加 + 手动添加表单）+ 与 Workspace 一致的确认弹窗。
 */
class DevicesActivity : ComponentActivity() {

    private val hostNotice = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // D1：兼容壳，转交给 MainActivity 的 devices 目的地。
        startActivity(
            Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_START_ROUTE, AppRoute.DEVICES)
                .putExtras(intent),
        )
        finish()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra(EXTRA_AUTH_NOTICE)?.let { hostNotice.value = it }
    }
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
    onOpenHost: (Host, (Boolean) -> Unit) -> Unit,
    onScanClick: () -> Unit,
    onManualPair: (name: String, url: String, code: String, fingerprint: String?, onSuccess: (Host) -> Unit, onError: (String) -> Unit) -> Unit,
    hostNotice: String? = null,
    onHostNotice: (String?) -> Unit = {},
    /** 本机存储的设备变了（过期移除 / 解除配对 / 连接偏好），宿主据此刷新当前设备。 */
    onHostChanged: () -> Unit = {},
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val s = DshS
    // 单设备：本机至多配对一台电脑
    var device by remember { mutableStateOf<DeviceUi?>(null) }
    var showPairingPanel by remember { mutableStateOf(false) }
    var unpairTarget by remember { mutableStateOf<Host?>(null) }
    // 离线设备无法走吊销：直接给「仅本机移除」出路，不再卡在吊销失败
    var unpairOffline by remember { mutableStateOf(false) }
    var unpairError by remember { mutableStateOf<String?>(null) }
    var unpairSaving by remember { mutableStateOf(false) }
    var offlineError by remember { mutableStateOf<String?>(null) }

    val scope = rememberCoroutineScope()
    var healthJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var refreshing by remember { mutableStateOf(false) }

    fun refreshHealth() {
        healthJob?.cancel()
        val current = device ?: return
        device = current.copy(state = DeviceState.CHECKING)
        healthJob = scope.launch {
            val health = withContext(Dispatchers.IO) { PairClient.probe(current.host) }
            if (health is HostHealth.AuthFailed) {
                val demote = shouldDemoteRelayOnAuth(health.error) && current.host.hasRelay
                withContext(Dispatchers.IO) {
                    if (demote) HostStore.demoteRelay(context, current.host) else if (dev.deeplinks.native.shouldDropLocalHostOnOpenAuth(health.error)) HostStore.remove(context, current.host)
                }
                onHostNotice(if (demote) L.relayRouteExpired else L.connectionAuthExpired)
                onHostChanged()
            }
            val stored = withContext(Dispatchers.IO) { HostStore.current(context) }
            device = stored?.let { h ->
                when (health) {
                    is HostHealth.Ok -> DeviceUi(h, DeviceState.ONLINE, health.latencyMs)
                    else -> DeviceUi(h, DeviceState.OFFLINE, null)
                }
            }
            offlineError = if (device?.state == DeviceState.OFFLINE) s.allOffline else null
        }
    }

    fun reload() {
        when (val loaded = HostStore.loadResult(context)) {
            is HostLoadResult.Ok -> {
                device = loaded.hosts.firstOrNull()?.let { DeviceUi(it) }
                offlineError = null
                refreshHealth()
            }
            HostLoadResult.Empty -> {
                device = null
                offlineError = null
            }
            HostLoadResult.Undecryptable -> {
                device = null
                offlineError = s.credentialsUnreadable
            }
        }
        onHostChanged()
        // reload 本身是同步的：有在跑的健康探测就等它结束再收起刷新指示，否则立即收起
        val job = healthJob
        if (job == null || !job.isActive) {
            refreshing = false
        } else {
            job.invokeOnCompletion { refreshing = false }
        }
    }

    // 每次回到前台重读设备并刷新健康状态
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
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
            .background(Dsh.bgBase)
    ) {
        // 头部（固定，含安全区）
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Dsh.bgBase)
                .statusBarsPadding()
                .padding(top = 18.dp, start = 18.dp, end = 18.dp, bottom = 18.dp)
        ) {
            Text(
                s.myDevices,
                color = Dsh.labelPrimary,
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                s.manageYourLinks,
                color = Dsh.labelSecondary,
                style = DshType.captionRelaxed,
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 18.dp)
                .padding(bottom = 28.dp)
        ) {
            val current = device
            if (current == null) {
                Box(Modifier.weight(1f)) {
                    EmptyDevicesState(onAdd = { showPairingPanel = true })
                }
                (hostNotice ?: offlineError)?.let { msg -> DevicesNotice(msg) }
            } else {
                PullToRefreshBox(
                    isRefreshing = refreshing,
                    onRefresh = { refreshing = true; reload() },
                    modifier = Modifier.weight(1f),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState()),
                    ) {
                        DeviceCard(
                            device = current,
                            onOpen = {
                                onOpenHost(current.host) { ok ->
                                    if (!ok) reload()
                                }
                            },
                            onTogglePreferRelay = { host ->
                                val updated = host.copy(preferRelay = !host.preferRelay)
                                if (HostStore.upsert(context, updated)) {
                                    Toast.makeText(context, s.preferCloudHint, Toast.LENGTH_SHORT).show()
                                }
                                reload()
                            },
                            onUnpair = {
                                unpairTarget = current.host
                                unpairOffline = current.state != DeviceState.ONLINE
                                unpairError = null
                                unpairSaving = false
                            },
                        )
                        if (current.host.needsCloudRescan) {
                            DevicesNotice(
                                message = s.relayRouteExpired,
                                actionLabel = s.restoreCloudScan,
                                onAction = onScanClick,
                                secondaryLabel = s.restoreCloudLater,
                                onSecondary = {
                                    scope.launch(Dispatchers.IO) {
                                        HostStore.clearCloudRescan(context)
                                        withContext(Dispatchers.Main) { reload() }
                                    }
                                },
                            )
                        }
                        (hostNotice ?: offlineError)?.let { msg ->
                            DevicesNotice(message = msg, actionLabel = s.resync, onAction = { refreshHealth() })
                        }
                        Spacer(Modifier.height(20.dp))
                        // 单设备：配对新电脑 = 替换当前这台（文字按钮，不与设备卡抢主操作）
                        Box(
                            modifier = Modifier
                                .align(Alignment.CenterHorizontally)
                                .heightIn(min = 48.dp)
                                .clip(RoundedCornerShape(DshRadius.full))
                                .clickable(indication = dshRipple(), interactionSource = remember { MutableInteractionSource() }) {
                                    showPairingPanel = true
                                }
                                .semantics { role = Role.Button }
                                .padding(horizontal = 16.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                s.replaceDevice,
                                color = Dsh.brand400,
                                style = DshType.bodyDense,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }
                }
            }
        }
    }

    // ---------- 配对面板（底部滑出） ----------
    if (showPairingPanel) {
        PairingPanel(
            replacing = device != null,
            onDismiss = { showPairingPanel = false },
            onScan = {
                showPairingPanel = false
                onScanClick()
            },
            onManualPair = { name, url, code, fingerprint, onSuccess, onError ->
                onManualPair(name, url, code, fingerprint, { host ->
                    // 新设备落库（替换旧设备）后立即刷新
                    reload()
                    onSuccess(host)
                }, onError)
            },
        )
    }

    // ---------- 解除配对确认 ----------
    unpairTarget?.let { target ->
        // 离线：吊销不可能，直接讲清后果并把主操作变成「仅本机移除」
        val offlineOnly = unpairOffline
        fun finishUnpair() {
            HostStore.remove(context, target)
            unpairTarget = null
            unpairOffline = false
            unpairError = null
            reload()
        }
        ConfirmDialog(
            title = if (offlineOnly) s.removeLocalOnly else s.deleteDevice,
            content = if (offlineOnly) {
                s.deleteDeviceOfflineContent.format(target.name)
            } else {
                s.deleteDeviceContent.format(target.name)
            },
            confirmText = if (offlineOnly) s.removeLocalOnly else s.delete,
            danger = true,
            error = unpairError,
            saving = unpairSaving,
            secondaryText = if (!offlineOnly && unpairError != null) s.removeLocalOnly else null,
            onSecondary = {
                if (unpairSaving) return@ConfirmDialog
                finishUnpair()
            },
            onConfirm = {
                if (unpairSaving) return@ConfirmDialog
                if (offlineOnly) {
                    finishUnpair()
                    return@ConfirmDialog
                }
                unpairSaving = true
                unpairError = null
                scope.launch(Dispatchers.IO) {
                    var revokeFailure: Throwable? = null
                    if (target.deviceId.isNotBlank()) {
                        try {
                            MobileApiClient(target).revokePairedDevice(deviceId = target.deviceId)
                        } catch (e: Exception) {
                            revokeFailure = e
                        }
                    }
                    withContext(Dispatchers.Main) {
                        unpairSaving = false
                        if (shouldBlockLocalHostRemoval(revokeFailure)) {
                            unpairError = s.revokeFailed.format(revokeFailure?.message ?: s.unknownError)
                            return@withContext
                        }
                        finishUnpair()
                        if (revokeFailure != null) {
                            Toast.makeText(context, s.revokeLocalOnlyHint, Toast.LENGTH_LONG).show()
                        }
                    }
                }
            },
            onDismiss = {
                if (!unpairSaving) {
                    unpairTarget = null
                    unpairOffline = false
                    unpairError = null
                }
            },
        )
    }
}

/** 设备卡下方的说明条：文案 + 至多两个文字操作（48dp 热区）。 */
@Composable
private fun DevicesNotice(
    message: String,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
    secondaryLabel: String? = null,
    onSecondary: () -> Unit = {},
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .clip(RoundedCornerShape(DshRadius.md))
            .background(Dsh.bgSubtle)
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = message,
            color = Dsh.labelSecondary,
            style = DshType.captionRelaxed,
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 8.dp),
        )
        listOfNotNull(
            actionLabel?.let { Triple(it, onAction, Dsh.labelPrimary) },
            secondaryLabel?.let { Triple(it, onSecondary, Dsh.labelTertiary) },
        ).forEach { (label, onClick, color) ->
            Box(
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .widthIn(min = 48.dp)
                    .clip(RoundedCornerShape(DshRadius.sm))
                    .clickable(onClick = onClick)
                    .semantics {
                        role = Role.Button
                        contentDescription = label
                    }
                    .padding(horizontal = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, color = color, fontWeight = FontWeight(600), style = DshType.t12)
            }
        }
    }
}

// ---------- 设备卡片 ----------

@Composable
private fun statusLabel(state: DeviceState): String {
    val s = DshS
    return when (state) {
        DeviceState.CHECKING -> s.statusChecking
        DeviceState.ONLINE -> s.statusOnline
        DeviceState.OFFLINE -> s.statusOffline
        DeviceState.CONNECTING -> s.statusConnecting
    }
}

/**
 * 已配对电脑卡片（96–112dp）：图标 + 名称 + 点状状态 + 次要信息，
 * 连接偏好 / 解除配对收进「更多」菜单，主操作是整行点按进入工作区。
 */
@Composable
private fun DeviceCard(
    device: DeviceUi,
    onOpen: () -> Unit,
    onUnpair: () -> Unit,
    onTogglePreferRelay: (Host) -> Unit = {},
) {
    val s = DshS
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    var menuOpen by remember { mutableStateOf(false) }
    val state = device.state
    val statusColor = when (state) {
        DeviceState.ONLINE -> Dsh.successContent
        DeviceState.CONNECTING -> Dsh.brand400
        else -> Dsh.labelTertiary
    }
    val connection = when {
        device.host.hasRelay && device.host.preferRelay -> s.preferCloud
        device.host.hasRelay -> s.viaCloud
        else -> s.viaLan
    }
    val stateLabel = statusLabel(state)
    val subtitle = buildList {
        add(hostDisplayName(device.host.baseUrl))
        add(connection)
        if (device.latencyMs != null && state == DeviceState.ONLINE) add("${device.latencyMs}ms")
    }.joinToString(" · ")

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 96.dp)
            .clip(RoundedCornerShape(DshRadius.lg))
            .background(if (pressed) Dsh.pressed else Dsh.bgSidePanel)
            .border(1.dp, Dsh.borderSubtle, RoundedCornerShape(DshRadius.lg))
            .clickable(interactionSource = interaction, indication = dshRipple(), onClick = onOpen)
            .semantics {
                role = Role.Button
                contentDescription = "${device.host.name}, $stateLabel"
            }
            .padding(start = 14.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MonitorGlyph()
        Spacer(Modifier.width(12.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    device.host.name,
                    color = Dsh.labelPrimary,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(statusColor)
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    statusLabel(state),
                    color = statusColor,
                    style = DshType.microRelaxed,
                    maxLines = 1,
                )
            }
            Spacer(Modifier.height(3.dp))
            Text(
                subtitle,
                color = Dsh.labelSecondary,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (device.host.needsCloudRescan) {
                Spacer(Modifier.height(4.dp))
                DeviceTag(s.restoreCloudTag, Dsh.error, Dsh.errorBg, monospace = false)
            }
        }
        Spacer(Modifier.width(4.dp))
        Box {
            val menuInteraction = remember { MutableInteractionSource() }
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .clickable(interactionSource = menuInteraction, indication = dshRipple()) {
                        menuOpen = true
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.MoreVert,
                    contentDescription = s.moreActions,
                    tint = Dsh.labelTertiary,
                    modifier = Modifier.size(20.dp),
                )
            }
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
                containerColor = Dsh.bgCard,
                shape = RoundedCornerShape(DshRadius.lg),
                tonalElevation = 0.dp,
            ) {
                if (device.host.hasRelay) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                if (device.host.preferRelay) s.viaLan else s.preferCloud,
                                color = Dsh.labelPrimary,
                                style = DshType.t14,
                            )
                        },
                        onClick = {
                            menuOpen = false
                            onTogglePreferRelay(device.host)
                        },
                    )
                }
                DropdownMenuItem(
                    text = { Text(s.deleteDevice, color = Dsh.error, style = DshType.t14) },
                    onClick = {
                        menuOpen = false
                        onUnpair()
                    },
                )
            }
        }
    }
}

@Composable
private fun MonitorGlyph() {
    // 统一描边图标体系（ic_device_glyph vector），不再手绘像素风 Box 堆叠
    Icon(
        painter = painterResource(dev.deeplinks.R.drawable.ic_device_glyph),
        contentDescription = null,
        tint = Dsh.labelPrimary,
        modifier = Modifier.size(26.dp),
    )
}

@Composable
private fun DeviceTag(text: String, color: Color, bg: Color, monospace: Boolean) {
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
            style = DshType.caption,
            lineHeight = 16.sp
        )
    }
}

// ---------- 空态 ----------

@Composable
private fun EmptyDevicesState(onAdd: () -> Unit) {
    val s = DshS
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(88.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(Dsh.brand400.copy(alpha = 0.12f))
                .border(1.dp, Dsh.brand400.copy(alpha = 0.28f), RoundedCornerShape(24.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(dev.deeplinks.R.drawable.ic_dsh_mark),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Spacer(Modifier.height(22.dp))
        Text(
            s.noDevicesYet,
            color = Dsh.labelPrimary,
            style = DshType.t17SB,
            fontWeight = FontWeight(600),
            letterSpacing = (-0.2).sp,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            s.noDevicesHint,
            color = Dsh.labelTertiary,
            style = DshType.bodyDense,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 280.dp),
        )
        Spacer(Modifier.height(28.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 280.dp)
                .height(48.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Dsh.brand500)
                .clickable(onClick = onAdd)
                .semantics {
                    role = Role.Button
                    contentDescription = s.addDevice
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                s.addDevice,
                color = Dsh.onBrand,
                style = DshType.t15SB,
                fontWeight = FontWeight(600),
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            s.addDeviceScanOrCode,
            color = Dsh.labelTertiary,
            style = DshType.t11,
            textAlign = TextAlign.Center,
        )
    }
}

// ---------- 配对面板（底部滑出） ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PairingPanel(
    /** 已有配对设备：配对新电脑会替换它，标题与说明据此改写。 */
    replacing: Boolean,
    onDismiss: () -> Unit,
    onScan: () -> Unit,
    onManualPair: (name: String, url: String, code: String, fingerprint: String?, onSuccess: (Host) -> Unit, onError: (String) -> Unit) -> Unit,
) {
    val s = DshS
    var mode by remember { mutableStateOf<PairingMode>(PairingMode.CHOOSE) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Dsh.bgSidePanel,
        contentColor = Dsh.labelPrimary,
        shape = DshSheetShape,
        scrimColor = Dsh.bgOverlay,
        dragHandle = null,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
        ) {
            DshSheetGrabber()
            // 头部：标题 + 描述 + 关闭
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        when {
                            mode != PairingMode.CHOOSE -> s.methodManual
                            replacing -> s.replaceDevice
                            else -> s.addDevice
                        },
                        color = Dsh.labelPrimary,
                        style = DshType.headline,
                        fontWeight = FontWeight(600),
                        lineHeight = 24.sp
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        when {
                            mode != PairingMode.CHOOSE -> s.manualPairSheetHint
                            replacing -> s.replaceDeviceHint
                            else -> s.pairChooseHint
                        },
                        color = Dsh.labelSecondary,
                        style = DshType.t11x17,
                        lineHeight = 17.sp
                    )
                }
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clickable(onClick = onDismiss)
                        .semantics {
                            role = Role.Button
                            contentDescription = s.close
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(26.dp)
                            .clip(CircleShape)
                            .background(Dsh.bgCard),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = null,
                            tint = Dsh.labelSecondary,
                            modifier = Modifier.size(14.dp),
                        )
                    }
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
                            title = s.methodScan,
                            description = s.methodScanDesc,
                            icon = Icons.Default.QrCodeScanner,
                            onClick = onScan
                        )
                        MethodOption(
                            title = s.methodManual,
                            description = s.methodManualDesc,
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
            .background(if (pressed) Dsh.pressed else Dsh.bgInput)
            .border(1.dp, Dsh.borderSubtle, RoundedCornerShape(14.dp))
            .clickable(interactionSource = interaction, indication = dshRipple(), onClick = onClick)
            .semantics {
                role = Role.Button
                contentDescription = title
            }
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(Dsh.bgCard),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = Dsh.labelPrimary, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(11.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = Dsh.labelPrimary, style = DshType.t13x18SB, fontWeight = FontWeight(600), lineHeight = 18.sp)
            Spacer(Modifier.height(2.dp))
            Text(description, color = Dsh.labelTertiary, style = DshType.captionRelaxed, lineHeight = 18.sp)
        }
        Icon(ChevronRightOutline14, contentDescription = null, tint = Dsh.labelTertiary, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun ManualPairForm(
    onPair: (name: String, url: String, code: String, fingerprint: String?, onSuccess: (Host) -> Unit, onError: (String) -> Unit) -> Unit,
    onBack: () -> Unit,
) {
    val s = DshS
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

    val fingerprint = tofuFingerprint
    if (fingerprint != null) {
        Dialog(
            onDismissRequest = { if (!loading) tofuFingerprint = null },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Dsh.bgOverlay)
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
                        .background(Dsh.bgSidePanel)
                        .border(1.dp, Dsh.borderSubtle, RoundedCornerShape(14.dp))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = dshRipple(),
                            onClick = {},
                        )
                        .padding(18.dp)
                ) {
                    Text(s.verifyCertificateTitle, color = Dsh.labelPrimary, style = DshType.t15SB, fontWeight = FontWeight(600))
                    Spacer(Modifier.height(6.dp))
                    Text(
                        url.trim(),
                        color = Dsh.labelSecondary,
                        style = DshType.captionRelaxed,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 2,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        s.verifyCertificateDesc,
                        color = Dsh.labelTertiary,
                        style = DshType.t11x17,
                        lineHeight = 17.sp,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        PinnedSsl.formatFingerprint(fingerprint),
                        color = Dsh.labelPrimary,
                        style = DshType.captionRelaxed,
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 18.sp,
                    )
                    Spacer(Modifier.height(16.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        ConfirmButton(s.cancel, false, onClick = { tofuFingerprint = null })
                        Spacer(Modifier.width(8.dp))
                        ConfirmButton(s.fingerprintMatches, true, onClick = {
                            tofuFingerprint.also { tofuFingerprint = null }?.let { submit(it) }
                        })
                    }
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(top = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        PairingField(
            label = s.pairFieldName,
            placeholder = s.pairFieldNamePlaceholder,
            value = name,
            onValueChange = { name = it },
        )
        PairingField(
            label = s.pairFieldAddress,
            placeholder = s.pairFieldAddressPlaceholder,
            value = url,
            onValueChange = { url = it },
            monospace = true,
        )
        PairingField(
            label = s.pairCodeLabel,
            placeholder = s.pairFieldCodePlaceholder,
            value = code,
            onValueChange = { if (it.length <= 8) code = it.trim() },
            monospace = true,
        )

        error?.let { msg ->
            Text(msg, color = Dsh.error, style = DshType.captionRelaxed, lineHeight = 18.sp)
        }

        val angle = rememberMotionSpin(750, label = "spinAngle")
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clip(RoundedCornerShape(13.dp))
                .background(Dsh.brand500)
                .clickable(
                    enabled = !loading,
                    onClick = {
                        val cleanUrl = url.trim()
                        val cleanCode = code.trim()
                        if (cleanUrl.isEmpty() || cleanCode.isEmpty()) {
                            error = s.pairAddressIncomplete
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
                                error = PinnedSsl.unwrap(e).message ?: s.cannotReadCertificate
                            }
                        }
                    }
                )
                .semantics {
                    role = Role.Button
                    contentDescription = s.addDevice
                },
            contentAlignment = Alignment.Center
        ) {
            if (loading) {
                Box(
                    modifier = Modifier
                        .size(14.dp)
                        .rotate(angle ?: 0f)
                        .border(1.5.dp, Color.White, CircleShape)
                )
            } else {
                Text(s.connectDevice, color = Dsh.onBrand, style = DshType.t13SB, fontWeight = FontWeight(600))
            }
        }
        Box(
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .heightIn(min = 48.dp)
                .clip(RoundedCornerShape(6.dp))
                .clickable(onClick = onBack)
                .semantics {
                    role = Role.Button
                    contentDescription = s.back
                }
                .padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Text(s.back, color = Dsh.labelSecondary, style = DshType.t11)
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
        Text(label, color = Dsh.labelSecondary, style = DshType.t11x16M, fontWeight = FontWeight(500), lineHeight = 16.sp)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = TextStyle(
                color = Dsh.labelPrimary,
                fontSize = if (monospace) 12.sp else 13.sp,
                fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default,
            ),
            cursorBrush = SolidColor(Dsh.labelPrimary),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Dsh.bgInput)
                .border(1.dp, Dsh.borderStrong, RoundedCornerShape(12.dp))
                .semantics {
                    contentDescription = "$label，$placeholder"
                }
                .padding(horizontal = 13.dp),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) {
                        Text(placeholder, color = Dsh.labelTertiary, fontSize = if (monospace) 12.sp else 13.sp)
                    }
                    inner()
                }
            }
        )
    }
}

// ---------- 确认弹窗 ----------

@Composable
private fun ConfirmDialog(
    title: String,
    content: String,
    confirmText: String,
    danger: Boolean = false,
    error: String? = null,
    saving: Boolean = false,
    secondaryText: String? = null,
    onSecondary: (() -> Unit)? = null,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val s = DshS
    val kind = renameDialogKind(saving, error)
    val canConfirm = kind != RenameDialogKind.Saving
    val motion = dialogMotionState(onDismiss)
    // \u5173\u95ed\u5165\u53e3\u7edf\u4e00\u5148\u8d70\u51fa\u573a\u52a8\u753b\uff0c\u64ad\u5b8c\u624d\u56de\u8c03 onDismiss\uff08saving \u4e2d\u4e0d\u54cd\u5e94\u5173\u95ed\uff09
    val requestDismiss = { if (canConfirm) motion.requestDismiss() }
    Dialog(
        onDismissRequest = requestDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = motion.alpha.value }
                .background(Dsh.bgOverlay)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = dshRipple(),
                    onClick = requestDismiss,
                ),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 360.dp)
                    .fillMaxWidth(0.9f)
                    .graphicsLayer {
                        alpha = motion.alpha.value
                        scaleX = motion.scale.value
                        scaleY = motion.scale.value
                    }
                    .shadow(12.dp, RoundedCornerShape(DshRadius.dialog), ambientColor = Dsh.shadowCard, spotColor = Dsh.shadowCard)
                    .clip(RoundedCornerShape(DshRadius.dialog))
                    .background(Dsh.bgSidePanel)
                    .border(1.dp, Dsh.borderSubtle, RoundedCornerShape(DshRadius.dialog))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    )
                    .padding(18.dp)
            ) {
                Text(title, color = Dsh.labelPrimary, style = DshType.t15x21SB, fontWeight = FontWeight(600), lineHeight = 21.sp)
                Spacer(Modifier.height(8.dp))
                Text(content, color = Dsh.labelTertiary, style = DshType.t11x17, lineHeight = 17.sp)
                if (kind == RenameDialogKind.Failed && !error.isNullOrBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        error,
                        color = Dsh.error,
                        style = DshType.t12x17,
                        lineHeight = 17.sp,
                        modifier = Modifier.semantics { contentDescription = error },
                    )
                }
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (secondaryText != null && onSecondary != null) {
                        ConfirmButton(secondaryText, false, enabled = canConfirm, onClick = onSecondary)
                        Spacer(Modifier.width(4.dp))
                    }
                    ConfirmButton(s.cancel, false, enabled = canConfirm, onClick = onDismiss)
                    Spacer(Modifier.width(8.dp))
                    ConfirmButton(
                        if (kind == RenameDialogKind.Saving) s.saving else confirmText,
                        danger,
                        enabled = canConfirm,
                        onClick = onConfirm,
                    )
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
    primary: Boolean = false,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val haptic = rememberDshHaptic()
    Box(
        modifier = Modifier
            .widthIn(min = 64.dp)
            .heightIn(min = 48.dp)
            .dshPressScale(interaction)
            .clickable(
                enabled = enabled,
                interactionSource = interaction,
                indication = dshRipple(),
                onClick = {
                    // 危险确认给负向触觉，普通确认给正向触觉
                    haptic(if (danger) DshHaptic.Reject else DshHaptic.Confirm)
                    onClick()
                },
            )
            .semantics {
                role = Role.Button
                contentDescription = label
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .height(34.dp)
                .clip(RoundedCornerShape(DshRadius.sm))
                .background(
                    when {
                        !enabled -> Dsh.pressed
                        danger -> Dsh.error
                        primary -> Dsh.brand500
                        pressed -> Dsh.pressed
                        else -> Color.Transparent
                    }
                )
                .padding(horizontal = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                label,
                color = when {
                    !enabled -> Dsh.labelTertiary
                    danger || primary -> Color.White
                    else -> Dsh.labelSecondary
                },
                style = DshType.t12M,
                fontWeight = FontWeight(500)
            )
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
