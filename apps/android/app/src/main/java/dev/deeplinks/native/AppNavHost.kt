package dev.deeplinks.native

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import android.content.Intent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dev.deeplinks.core.DeviceName
import dev.deeplinks.core.Host
import dev.deeplinks.core.HostStore
import dev.deeplinks.core.L
import dev.deeplinks.core.PairClient
import dev.deeplinks.core.PinnedSsl
import dev.deeplinks.core.stableIdentity
import dev.deeplinks.devices.DevicesScreen
import dev.deeplinks.native.util.EXTRA_SHARE_IMAGE
import dev.deeplinks.native.util.EXTRA_SHARE_IMAGES
import dev.deeplinks.native.util.EXTRA_SHARE_NOTICE
import dev.deeplinks.native.util.EXTRA_SHARE_SEQ
import dev.deeplinks.native.util.EXTRA_SHARE_TEXT
import dev.deeplinks.native.util.WorkspacePrefs
import dev.deeplinks.native.util.shareImageExtras
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 应用级路由（对照 t3code 的静态路由表）。D1 迁移：Devices 与 Workspace 同一 NavHost。 */
object AppRoute {
    const val DEVICES = "devices"
    const val WORKSPACE = "workspace"
}

/**
 * 应用级 NavHost（D1/D3/D5）。
 *
 * 把原来 DevicesActivity→WorkspaceActivity 的 Activity 跳转收敛为图表内的
 * navigate；配对与保存逻辑从 DevicesActivity 搬到这里。
 * 分享/通知等外部入口仍由 MainActivity 承接并把 extras 透传给 Workspace 目的地。
 *
 * 单设备：本机只配对一台电脑。[AppRoute.DEVICES] 是配对 / 设备状态页（配对新电脑即替换），
 * 不再有设备列表与工作区内的设备切换。连接等待只在 Workspace 发生一次
 * （[AppRoute.WORKSPACE] 的 bootstrap），离线由 Workspace 顶栏 Banner 承担。
 */
@Composable
internal fun AppNavHost(
    startRoute: String,
    startHost: Host?,
    liveIntent: Intent,
    hostNotice: String?,
    onHostNotice: (String?) -> Unit,
    onScan: () -> Unit,
    onOpenSettings: (Host) -> Unit,
    onStartVoiceInput: ((String) -> Unit, () -> Unit, (String) -> Unit) -> Unit,
    onStopVoiceInput: () -> Unit,
    requestedRoute: String?,
    onRouteHandled: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val navController = rememberNavController()
    val workspacePrefs = remember { WorkspacePrefs(context) }
    /** 本机配对的那台电脑（单设备）；配对 / 解除配对 / 新 Intent 到达后重读。 */
    var currentHost by remember { mutableStateOf(startHost ?: HostStore.current(context)) }
    fun reloadHost() {
        currentHost = HostStore.current(context)
    }
    LaunchedEffect(liveIntent) { reloadHost() }

    /** 配对成功或点开设备后进入工作区：工作区已在返回栈里就退回去，不叠第二份。 */
    fun openWorkspace() {
        reloadHost()
        val back = navController.previousBackStackEntry?.destination?.route
        if (back == AppRoute.WORKSPACE) {
            navController.popBackStack()
        } else {
            navController.navigate(AppRoute.WORKSPACE) {
                launchSingleTop = true
                popUpTo(AppRoute.DEVICES) { inclusive = true }
            }
        }
    }

    LaunchedEffect(requestedRoute) {
        if (requestedRoute != null) {
            navController.navigate(requestedRoute) { launchSingleTop = true }
            onRouteHandled()
        }
    }

    // 首屏入场：与 SplashActivity 的图标退出动画接力（淡入 + 上移 8dp）。
    // 只在整棵 NavHost 首次组合时播放一次，站内导航不再触发。
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }
    val enterAlpha by animateFloatAsState(
        targetValue = if (entered) 1f else 0f,
        animationSpec = tween(motionDuration(DshDuration.slow), easing = DshEasing.out),
        label = "firstScreenAlpha",
    )
    val enterRise by animateDpAsState(
        targetValue = if (entered) 0.dp else 8.dp,
        animationSpec = tween(motionDuration(DshDuration.slow), easing = DshEasing.out),
        label = "firstScreenRise",
    )

    // 站内转场时长：在可组合作用域捕获（enter/pop 各 lambda 非 @Composable，不能现调 motionDuration）
    val navMotionMs = motionDuration(DshDuration.slow)

    NavHost(
        navController = navController,
        startDestination = startRoute,
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                alpha = enterAlpha
                translationY = enterRise.toPx()
            },
        // 转场：新页从右侧整屏不透明滑入覆盖在静止旧页上，返回时当前页向右滑出露出下层；
        // 纯平移、无交叉淡化（淡化曾让下层文字透出，见 docs/ui-parity.md）。
        // predictive pop 同配方跟手滑出，避开 navigation-compose 2.10 默认的 scaleOut 0.7 整页缩小。
        enterTransition = {
            slideInHorizontally(animationSpec = tween(navMotionMs, easing = DshEasing.out)) { it }
        },
        exitTransition = { ExitTransition.None },
        popEnterTransition = { EnterTransition.None },
        popExitTransition = {
            slideOutHorizontally(animationSpec = tween(navMotionMs, easing = DshEasing.out)) { it }
        },
        predictivePopEnterTransition = { EnterTransition.None },
        predictivePopExitTransition = {
            slideOutHorizontally(animationSpec = tween(navMotionMs, easing = DshEasing.out)) { it }
        },
    ) {
        composable(AppRoute.DEVICES) {
            DevicesScreen(
                hostNotice = hostNotice,
                onHostNotice = onHostNotice,
                onOpenHost = { host, onDone ->
                    // 可达性与会话加载交给 Workspace 的 bootstrap。
                    val saved = HostStore.upsert(context, host)
                    if (!saved && HostStore.isLocked(context)) {
                        HostStore.clearLockAndReplace(context, host)
                        onHostNotice(L.credentialsResetToast)
                    } else if (!saved) {
                        onDone(false)
                        onHostNotice(L.credentialsSaveFailedToast)
                        return@DevicesScreen
                    }
                    onDone(true)
                    openWorkspace()
                },
                onHostChanged = { reloadHost() },
                onScanClick = onScan,
                onManualPair = { name, url, code, fingerprint, onSuccess, onError ->
                    scope.launch {
                        try {
                            val r = withContext(Dispatchers.IO) {
                                PairClient.pair(url, code, DeviceName.of(context), fingerprint)
                            }
                            val newHost = Host(name.ifBlank { r.name }, r.baseUrl, r.token, r.deviceId, r.certFingerprint)
                            if (!HostStore.upsert(context, newHost)) {
                                if (HostStore.isLocked(context)) {
                                    HostStore.clearLockAndReplace(context, newHost)
                                    onHostNotice(L.credentialsResetToast)
                                } else {
                                    onError(L.credentialsSaveFailedToast)
                                    return@launch
                                }
                            }
                            onSuccess(newHost)
                            if (r.pending) onHostNotice(L.pairPendingApprovalToast)
                            // 配对成功（含替换旧设备）后直接进 Workspace。
                            openWorkspace()
                        } catch (e: Exception) {
                            onError(PinnedSsl.unwrap(e).message ?: L.pairFailedCheckAddress)
                        }
                    }
                },
            )
        }

        composable(AppRoute.WORKSPACE) {
            val host = currentHost
            if (host == null) {
                LaunchedEffect(Unit) {
                    navController.navigate(AppRoute.DEVICES) { popUpTo(0) { inclusive = true } }
                }
            } else {
                // host 切换必须重建整棵状态树；intent sessionId 变化由内部 LaunchedEffect 接入
                val hostIdentity = host.stableIdentity()
                // 外部 Intent 的 sessionId 优先；没有就恢复该设备上次打开的会话（仍由
                // Workspace 在拿到会话列表后校验可见性，失效则回退最新可见会话）。
                val deepLinkSessionId = liveIntent.getStringExtra("sessionId")
                val restoreSessionId = if (deepLinkSessionId.isNullOrBlank()) {
                    workspacePrefs.lastSessionId(hostIdentity)
                } else {
                    null
                }
                key(host.slotKey) {
                    WorkspaceScreen(
                        host = host,
                        initialSessionId = deepLinkSessionId,
                        restoreSessionId = restoreSessionId,
                        initialShareText = liveIntent.getStringExtra(EXTRA_SHARE_TEXT),
                        initialShareImages = shareImageExtras(
                            liveIntent.getStringArrayListExtra(EXTRA_SHARE_IMAGES),
                            liveIntent.getStringExtra(EXTRA_SHARE_IMAGE),
                        ),
                        initialShareSeq = liveIntent.getLongExtra(EXTRA_SHARE_SEQ, 0L),
                        initialShareNotice = liveIntent.getStringExtra(EXTRA_SHARE_NOTICE),
                        onOpenDevice = { notice ->
                            if (!notice.isNullOrBlank()) onHostNotice(notice)
                            navController.navigate(AppRoute.DEVICES) {
                                launchSingleTop = true
                                popUpTo(AppRoute.DEVICES) { inclusive = true }
                            }
                        },
                        onOpenSettings = { onOpenSettings(host) },
                        onStartVoiceInput = onStartVoiceInput,
                        onStopVoiceInput = onStopVoiceInput,
                    )
                }
            }
        }
    }
}
