package dev.deeplinks.native

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import dev.deeplinks.core.DshStartSurface
import dev.deeplinks.core.DshTheme
import dev.deeplinks.core.EXTRA_AUTH_NOTICE
import dev.deeplinks.core.Host
import dev.deeplinks.core.HostLoadResult
import dev.deeplinks.core.HostStore
import dev.deeplinks.core.L
import dev.deeplinks.core.applyDshSecureWindow
import dev.deeplinks.core.enableDshEdgeToEdge
import dev.deeplinks.core.pickStartupHost
import dev.deeplinks.core.resolveFromIntentStrict
import dev.deeplinks.core.resolveStartSurface
import dev.deeplinks.devices.ScanActivity
import dev.deeplinks.native.util.VoiceRecognitionIssue
import dev.deeplinks.native.util.voiceRecognitionIssue
import java.util.Locale

/**
 * 单 Activity 宿主（D1/D3/D5）：Devices 与 Workspace 收敛到同一 NavHost。
 *
 * 搬迁自 WorkspaceActivity 的语音识别生命周期与 intent 透传；配对探测逻辑
 * 在 AppNavHost 内。原 Activity 仍保留，作为外部入口（分享/通知）的兼容壳。
 *
 * 缺省入口由本机上下文决定（见 [resolveLaunchRoute]）：外部 Intent 指定 >
 * 单设备：有已配对的电脑就进 Workspace；未配对或凭据不可解密才进设备（配对）页。
 */
class MainActivity : ComponentActivity() {

    companion object {
        /** 外部入口指定起始目的地；缺省按本机配对状态推导。 */
        const val EXTRA_START_ROUTE = "startRoute"

        /**
         * 冷启动路由解析（纯读本地状态，不做任何网络探测）。
         * 首页只负责恢复本地上下文，远端数据由 Workspace 的 bootstrap 异步加载。
         */
        internal fun resolveLaunchRoute(context: Context, intent: Intent): String {
            intent.getStringExtra(EXTRA_START_ROUTE)?.takeIf { it.isNotBlank() }?.let { return it }
            if (HostStore.isLocked(context)) return AppRoute.DEVICES
            val result = HostStore.loadResult(context)
            val hosts = (result as? HostLoadResult.Ok)?.hosts.orEmpty()
            val host = pickStartupHost(
                hosts = hosts,
                intentHost = hosts.resolveFromIntentStrict(intent),
                lastIdentity = HostStore.lastHostIdentity(context),
            )
            return when (resolveStartSurface(host, result is HostLoadResult.Undecryptable)) {
                DshStartSurface.Workspace -> AppRoute.WORKSPACE
                DshStartSurface.Devices -> AppRoute.DEVICES
            }
        }

        /** 启动时默认打开的 Workspace 设备；设备页/配对页不需要它。 */
        internal fun resolveLaunchHost(context: Context, intent: Intent): Host? {
            val result = HostStore.loadResult(context)
            if (result !is HostLoadResult.Ok) return null
            return pickStartupHost(
                hosts = result.hosts,
                intentHost = result.hosts.resolveFromIntentStrict(intent),
                lastIdentity = HostStore.lastHostIdentity(context),
            )
        }
    }

    private var speechRecognizer: SpeechRecognizer? = null
    private var voiceIdle: (() -> Unit)? = null
    private var voiceError: ((String) -> Unit)? = null
    private var voiceResult: ((String) -> Unit)? = null
    private val incomingIntent = mutableStateOf<Intent?>(null)
    private val hostNotice = mutableStateOf<String?>(null)
    private val pendingRoute = mutableStateOf<String?>(null)

    /** 本实例的起始路由与起始设备：只在 onCreate 解析一次，站内导航/新 Intent 不重算。 */
    private lateinit var launchRoute: String
    private var launchHost: Host? = null
    private val requestAudioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isGranted) {
            voiceError?.invoke(L.voicePermissionRequired)
            voiceIdle?.invoke()
            return@registerForActivityResult
        }
        val onResult = voiceResult
        val onIdle = voiceIdle
        val onError = voiceError
        if (onResult != null && onIdle != null && onError != null) {
            startVoiceListening(onResult, onIdle, onError)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 会话/工作台内容不进截图与最近任务；集中在此单 Activity 宿主上。
        applyDshSecureWindow()
        enableDshEdgeToEdge()
        incomingIntent.value = intent
        hostNotice.value = intent.getStringExtra(EXTRA_AUTH_NOTICE)
        launchRoute = resolveLaunchRoute(applicationContext, intent)
        launchHost = if (launchRoute == AppRoute.WORKSPACE) resolveLaunchHost(applicationContext, intent) else null
        initSpeechRecognizer()

        setContent {
            DshTheme {
                val liveIntent = incomingIntent.value ?: intent
                AppNavHost(
                    startRoute = launchRoute,
                    startHost = launchHost,
                    liveIntent = liveIntent,
                    hostNotice = hostNotice.value,
                    onHostNotice = { hostNotice.value = it },
                    onScan = { startActivity(Intent(this, ScanActivity::class.java)) },
                    onOpenSettings = { host ->
                        startActivity(host.putInto(Intent(this, SettingsActivity::class.java)))
                    },
                    onStartVoiceInput = { onResult, onIdle, onError ->
                        startVoiceListening(onResult, onIdle, onError)
                    },
                    onStopVoiceInput = { speechRecognizer?.stopListening() },
                    requestedRoute = pendingRoute.value,
                    onRouteHandled = { pendingRoute.value = null },
                )
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
        intent.getStringExtra(EXTRA_AUTH_NOTICE)?.let { hostNotice.value = it }
        intent.getStringExtra(EXTRA_START_ROUTE)?.let { pendingRoute.value = it }
    }

    private fun startVoiceListening(onResult: (String) -> Unit, onIdle: () -> Unit, onError: (String) -> Unit) {
        voiceIdle = onIdle
        voiceError = onError
        voiceResult = onResult
        if (speechRecognizer == null) {
            onError(L.voiceUnavailable)
            onIdle()
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
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
                val message = when (voiceRecognitionIssue(error)) {
                    VoiceRecognitionIssue.NO_SPEECH -> L.voiceNoSpeech
                    VoiceRecognitionIssue.NETWORK -> L.voiceNetworkError
                    VoiceRecognitionIssue.BUSY -> L.voiceBusy
                    VoiceRecognitionIssue.PERMISSION -> L.voicePermissionRequired
                    VoiceRecognitionIssue.RETRY -> L.voiceRecognitionRetry
                }
                onError(message)
            }
            override fun onResults(results: Bundle?) {
                onIdle()
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    onResult(matches[0])
                } else {
                    onError(L.voiceNoSpeech)
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
