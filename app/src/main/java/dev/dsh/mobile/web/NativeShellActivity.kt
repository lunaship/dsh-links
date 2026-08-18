package dev.dsh.mobile.web
import dev.dsh.mobile.devices.WorkspaceLauncher
import dev.dsh.mobile.native.MobileBootstrap
import dev.dsh.mobile.core.Host
import dev.dsh.mobile.native.MobileSession
import dev.dsh.mobile.native.MobileApiClient
import dev.dsh.mobile.core.HostStore

import android.content.Intent
import android.os.Bundle
import android.text.format.DateUtils
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Phase 1 原生入口：高频导航由 Android 接管，复杂工作台仍按需进入 WebView。 */
@androidx.compose.material3.ExperimentalMaterial3Api
class NativeShellActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val hosts = HostStore.load(this)
        if (hosts.isEmpty()) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }
        setContent { DshNativeShell(hosts) }
    }

    private fun openSession(host: Host, session: MobileSession?) {
        startActivity(Intent(this, WorkspaceLauncher::class.java).apply {
            putExtra("baseUrl", host.baseUrl)
            session?.let {
                putExtra("sessionId", it.sessionId)
            }
        })
    }

    @androidx.compose.material3.ExperimentalMaterial3Api
    @Composable
    private fun DshNativeShell(hosts: List<Host>) {
        var selectedHostName by remember { mutableStateOf(hosts.first().name) }
        var destination by rememberSaveable { mutableStateOf(DrawerDestination.Sessions) }
        val drawerState = rememberDrawerState(DrawerValue.Closed)
        val scope = rememberCoroutineScope()
        val activeHost = hosts.firstOrNull { it.name == selectedHostName } ?: hosts.first()
        BackHandler(enabled = drawerState.isOpen) { scope.launch { drawerState.close() } }

        ShellTheme {
            ModalNavigationDrawer(
                drawerState = drawerState,
                drawerContent = {
                    ModalDrawerSheet(modifier = Modifier.width(328.dp), drawerContainerColor = MaterialTheme.colorScheme.surface) {
                        HarnessDrawer(
                            hosts = hosts,
                            selectedHostName = activeHost.name,
                            destination = destination,
                            onHost = { selectedHostName = it; destination = DrawerDestination.Sessions; scope.launch { drawerState.close() } },
                            onDestination = { destination = it; scope.launch { drawerState.close() } },
                        )
                    }
                },
            ) {
                Scaffold(
                    topBar = {
                        TopAppBar(
                            navigationIcon = { IconButton(onClick = { scope.launch { drawerState.open() } }) { Text("☰", fontSize = 22.sp) } },
                            title = {
                                Column {
                                    Text(if (destination == DrawerDestination.Sessions) "DeepHarness" else destination.title, fontWeight = FontWeight.Bold)
                                    Text(if (destination == DrawerDestination.Sessions) "工作区 · " + activeHost.name else activeHost.name, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            },
                            actions = { if (destination == DrawerDestination.Sessions) IconButton(onClick = { openSession(activeHost, null) }) { Text("＋", fontSize = 25.sp) } },
                            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
                        )
                    },
                    containerColor = MaterialTheme.colorScheme.surface,
                ) { innerPadding ->
                    Surface(Modifier.fillMaxSize().padding(innerPadding), color = MaterialTheme.colorScheme.surface) {
                        when (destination) {
                            DrawerDestination.Sessions -> SessionsScreen(activeHost, onOpen = { openSession(activeHost, it) })
                            DrawerDestination.Connections -> DevicesScreen(hosts, activeHost.name, onSelect = { selectedHostName = it }, onOpen = { openSession(it, null) })
                            DrawerDestination.Downloads -> DownloadsScreen()
                            DrawerDestination.Settings -> AccountScreen(activeHost, onManage = { startActivity(Intent(this@NativeShellActivity, MainActivity::class.java)) })
                        }
                    }
                }
            }
        }
    }
}

private enum class DrawerDestination(val title: String) { Sessions("会话"), Connections("手机连接"), Downloads("下载"), Settings("设置") }
private object ShellColors { val online = Color(0xFF22C55E) }

@Composable
private fun HarnessDrawer(hosts: List<Host>, selectedHostName: String, destination: DrawerDestination, onHost: (String) -> Unit, onDestination: (DrawerDestination) -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 18.dp)) {
        Text("DeepHarness", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 12.dp))
        Text("工作区与会话", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 3.dp))
        Spacer(Modifier.height(18.dp))
        NavigationDrawerItem(label = { Text("会话") }, selected = destination == DrawerDestination.Sessions, onClick = { onDestination(DrawerDestination.Sessions) }, icon = { Text("▤") }, colors = NavigationDrawerItemDefaults.colors(selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer))
        Spacer(Modifier.height(14.dp))
        Text("工作区", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 12.dp))
        Spacer(Modifier.height(6.dp))
        hosts.forEach { host -> NavigationDrawerItem(label = { Text(host.name, maxLines = 1, overflow = TextOverflow.Ellipsis) }, selected = host.name == selectedHostName, onClick = { onHost(host.name) }, icon = { Box(Modifier.size(8.dp).clip(CircleShape).background(ShellColors.online)) }) }
        Spacer(Modifier.height(10.dp)); HorizontalDivider(); Spacer(Modifier.height(10.dp))
        NavigationDrawerItem(label = { Text("手机连接") }, selected = destination == DrawerDestination.Connections, onClick = { onDestination(DrawerDestination.Connections) }, icon = { Text("⌂") })
        NavigationDrawerItem(label = { Text("下载") }, selected = destination == DrawerDestination.Downloads, onClick = { onDestination(DrawerDestination.Downloads) }, icon = { Text("↓") })
        NavigationDrawerItem(label = { Text("设置") }, selected = destination == DrawerDestination.Settings, onClick = { onDestination(DrawerDestination.Settings) }, icon = { Text("◎") })
        Spacer(Modifier.weight(1f))
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) { Box(Modifier.size(8.dp).clip(CircleShape).background(ShellColors.online)); Spacer(Modifier.width(8.dp)); Text("已连接", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp) }
    }
}

@Composable
private fun ShellTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val base = MaterialTheme.colorScheme
    val colors = if (dark) {
        base.copy(primary = Color(0xFFC0C7FF), onPrimary = Color(0xFF17205A), primaryContainer = Color(0xFF252D62), onPrimaryContainer = Color(0xFFE1E5FF), secondaryContainer = Color(0xFF2E3044), surface = Color(0xFF121318), surfaceContainer = Color(0xFF1C1D24), surfaceContainerLow = Color(0xFF202127))
    } else {
        base.copy(primary = Color(0xFF3F51B5), onPrimary = Color.White, primaryContainer = Color(0xFFE3E7FF), onPrimaryContainer = Color(0xFF111A4B), secondaryContainer = Color(0xFFE5E7F8), surface = Color(0xFFF9F9FC), surfaceContainer = Color(0xFFF0F1F6), surfaceContainerLow = Color.White)
    }
    MaterialTheme(colorScheme = colors, content = content)
}

private sealed interface SessionsState {
    data object Loading : SessionsState
    data class Ready(val data: MobileBootstrap) : SessionsState
    data class Error(val message: String) : SessionsState
}

@Composable
private fun SessionsScreen(host: Host, onOpen: (MobileSession?) -> Unit) {
    var state by remember(host.name) { mutableStateOf<SessionsState>(SessionsState.Loading) }
    var query by remember(host.name) { mutableStateOf("") }
    var notice by remember(host.name) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    fun refresh() {
        state = SessionsState.Loading
        scope.launch {
            state = try { SessionsState.Ready(withContext(Dispatchers.IO) { MobileApiClient(host).bootstrap() }) }
            catch (error: Exception) { SessionsState.Error(error.message ?: "无法连接到此 dsh 实例") }
        }
    }
    LaunchedEffect(host.name) { refresh() }

    when (val current = state) {
        SessionsState.Loading -> LoadingState("正在同步会话", host.name)
        is SessionsState.Error -> EmptyState("连接暂时不可用", current.message, "重新连接", { refresh() }, true)
        is SessionsState.Ready -> {
            val visibleSessions = current.data.sessions.filter { session ->
                query.isBlank() || session.title.contains(query, ignoreCase = true) || (session.cwd?.contains(query, ignoreCase = true) == true)
            }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { HeroHeader("DSH WORKSPACE", "继续工作", current.data.sessions.size.toString() + " 个会话 · 自动同步", "新会话", { onOpen(null) }) }
                item { OutlinedTextField(value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("搜索会话") }, placeholder = { Text("标题或工作目录") }) }
                if (visibleSessions.isEmpty()) item { EmptyState("没有匹配会话", "尝试更短的关键词，或刷新会话列表。", "清除搜索", { query = "" }) }
                else {
                    item { Text(if (query.isBlank()) "最近会话" else "搜索结果 · " + visibleSessions.size, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 3.dp, start = 2.dp)) }
                    items(visibleSessions, key = { it.sessionId }) { session ->
                        SessionCard(session, onClick = { onOpen(session) }, onCancel = if (session.running) {
                            {
                                scope.launch {
                                    notice = "正在停止生成…"
                                    try {
                                        withContext(Dispatchers.IO) { MobileApiClient(host).cancelSession(session.sessionId) }
                                        notice = "已发送停止请求"
                                        refresh()
                                    } catch (_: Exception) {
                                        notice = "当前服务尚未启用原生停止接口"
                                    }
                                }
                            }
                        } else null)
                    }
                }
                if (notice != null) item { StatusNotice(notice!!) }
                item { FilledTonalButton(onClick = { refresh() }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) { Text("刷新会话") }; Spacer(Modifier.height(18.dp)) }
            }
        }
    }
}

@Composable
private fun HeroHeader(eyebrow: String, title: String, detail: String, actionLabel: String? = null, action: () -> Unit = {}) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(eyebrow, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = .65f))
                Spacer(Modifier.height(6.dp)); Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                Spacer(Modifier.height(4.dp)); Text(detail, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = .75f))
            }
            if (actionLabel != null) FilledTonalButton(onClick = action, shape = RoundedCornerShape(14.dp)) { Text(actionLabel) }
        }
    }
}

@Composable
private fun SessionCard(session: MobileSession, onClick: () -> Unit, onCancel: (() -> Unit)? = null) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick), shape = RoundedCornerShape(18.dp), border = if (session.running) BorderStroke(1.dp, ShellColors.online.copy(alpha = .35f)) else null, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(horizontal = 17.dp, vertical = 15.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Box(Modifier.size(9.dp).clip(CircleShape).background(if (session.running) ShellColors.online else MaterialTheme.colorScheme.outlineVariant))
                Spacer(Modifier.width(10.dp)); Text(session.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (session.running) {
                    StatusPill("进行中", ShellColors.online)
                    if (onCancel != null) { Spacer(Modifier.width(6.dp)); FilledTonalButton(onClick = onCancel, shape = RoundedCornerShape(10.dp)) { Text("停止", fontSize = 12.sp) } }
                }
            }
            Spacer(Modifier.height(10.dp)); Text(if (session.blank) "等待第一条消息" else session.cwd ?: "DeepSeek Harness", color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp)
            if (!session.agentPreset.isNullOrBlank()) {
                Spacer(Modifier.height(7.dp)); StatusPill("Agent · " + session.agentPreset, MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.height(9.dp)); Text(DateUtils.getRelativeTimeSpanString(session.updatedAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString(), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun StatusNotice(message: String) { Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) { Text(message, color = MaterialTheme.colorScheme.onSecondaryContainer, fontSize = 13.sp, modifier = Modifier.padding(12.dp)) } }

@Composable
private fun StatusPill(text: String, color: Color) { Surface(shape = RoundedCornerShape(50), color = color.copy(alpha = .12f)) { Text(text, color = color, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp)) } }

@Composable
private fun DevicesScreen(hosts: List<Host>, selectedHostName: String, onSelect: (String) -> Unit, onOpen: (Host) -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { HeroHeader("CONNECTIONS", "我的设备", hosts.size.toString() + " 个 dsh 实例") }
        items(hosts, key = { it.name }) { host ->
            Card(Modifier.fillMaxWidth().clickable { onSelect(host.name) }, shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                Row(Modifier.padding(17.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(42.dp).clip(RoundedCornerShape(13.dp)), contentAlignment = Alignment.Center) { Text("DH", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary) }
                    Spacer(Modifier.width(13.dp)); Column(Modifier.weight(1f)) { Text(host.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis); Spacer(Modifier.height(3.dp)); Text(host.baseUrl, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    if (host.name == selectedHostName) StatusPill("当前", MaterialTheme.colorScheme.primary) else FilledTonalButton(onClick = { onOpen(host) }, shape = RoundedCornerShape(12.dp)) { Text("打开") }
                }
            }
        }
    }
}

@Composable
private fun DownloadsScreen() = EmptyState("下载中心", "从 DSH 下载的文件会显示在系统下载中。", "查看系统下载", {})

@Composable
private fun AccountScreen(host: Host, onManage: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        HeroHeader("ACCOUNT", "连接与设置", "管理设备、配对和连接状态", "管理", onManage)
        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
            Column(Modifier.padding(17.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) { Box(Modifier.size(10.dp).clip(CircleShape).background(ShellColors.online)); Spacer(Modifier.width(8.dp)); Text("已连接", color = ShellColors.online, fontWeight = FontWeight.SemiBold) }
                Spacer(Modifier.height(14.dp)); Text(host.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold); Spacer(Modifier.height(4.dp)); Text(host.baseUrl, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                Spacer(Modifier.height(14.dp)); HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f)); Spacer(Modifier.height(12.dp)); Text("连接令牌已安全保存在本机。", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            }
        }
        Button(onClick = onManage, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) { Text("管理设备与配对") }
    }
}

@Composable
private fun LoadingState(title: String, detail: String) { Box(Modifier.fillMaxSize().padding(28.dp), contentAlignment = Alignment.Center) { Column(horizontalAlignment = Alignment.CenterHorizontally) { CircularProgressIndicator(strokeWidth = 3.dp, modifier = Modifier.size(34.dp)); Spacer(Modifier.height(18.dp)); Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold); Spacer(Modifier.height(5.dp)); Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp) } } }

@Composable
private fun EmptyState(title: String, detail: String, action: String, onAction: () -> Unit, isError: Boolean = false) { Box(Modifier.fillMaxSize().padding(28.dp), contentAlignment = Alignment.Center) { Column(horizontalAlignment = Alignment.CenterHorizontally) { Box(Modifier.size(58.dp).clip(CircleShape).background(if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) { Text(if (isError) "!" else "·", fontSize = 25.sp, fontWeight = FontWeight.Bold, color = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer) }; Spacer(Modifier.height(18.dp)); Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold); Spacer(Modifier.height(8.dp)); Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp); Spacer(Modifier.height(18.dp)); FilledTonalButton(onClick = onAction, shape = RoundedCornerShape(14.dp)) { Text(action) } } } }
