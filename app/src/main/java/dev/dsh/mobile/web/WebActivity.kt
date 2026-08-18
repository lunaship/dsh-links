package dev.dsh.mobile.web
import dev.dsh.mobile.R
import dev.dsh.mobile.BuildConfig

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import java.net.HttpURLConnection
import java.net.URL
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.io.File

/**
 * DSH Web UI 全屏壳（HStudio 风格）：
 * 沉浸式状态栏 / 返回键路由协调（JS 注入检测页面栈）/ 文件上传+相机 / 系统下载管理器 /
 * Cookie 完整管理 + 自动续期 / 摄像头权限桥接。
 */
class WebActivity : AppCompatActivity() {

    private var fileUploadCallback: ValueCallback<Array<Uri>>? = null
    private var cameraCaptureUri: Uri? = null
    private lateinit var webView: WebView
    private lateinit var loading: ProgressBar
    private lateinit var errorState: View
    private lateinit var errorMessage: TextView
    private var token: String = ""
    private var baseUrl: String = ""
    private var sessionId: String = ""
    private var pendingPermissionRequest: PermissionRequest? = null
    private val bootHandler = Handler(Looper.getMainLooper())
    private var bootChecks = 0

    companion object {
        /**
         * 返回键桥接：只处理 DeepSeek Harness 自己的 SPA 路由栈；
         * 当没有可回退的 Web 路由时，Android 返回到设备中心。
         */
        private const val BACK_BRIDGE_JS = """
            (function () {
              if (!window.__dshBack) {
                var depth = 0;
                var ps = history.pushState.bind(history);
                var rs = history.replaceState.bind(history);
                history.pushState = function () { depth++; return ps.apply(history, arguments); };
                history.replaceState = function () { return rs.apply(history, arguments); };
                window.addEventListener('popstate', function () { depth = Math.max(0, depth - 1); });
                window.__dshBack = {
                  depth: function () { return depth; },
                  back: function () { if (depth > 0) { history.back(); return true; } return false; }
                };
              }
              return String(window.__dshBack.depth());
            })()
        """
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 沉浸式：内容延伸到状态栏下方
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.show(WindowInsetsCompat.Type.systemBars())
        val night = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        controller.isAppearanceLightStatusBars = !night
        controller.isAppearanceLightNavigationBars = !night

        setContentView(R.layout.activity_web)
        webView = findViewById(R.id.webview)
        loading = findViewById(R.id.web_loading)
        errorState = findViewById(R.id.web_error)
        errorMessage = findViewById(R.id.web_error_message)
        findViewById<TextView>(R.id.web_retry).setOnClickListener { reloadPage() }

        baseUrl = intent.getStringExtra("baseUrl").orEmpty()
        token = intent.getStringExtra("token") ?: ""
        sessionId = intent.getStringExtra("sessionId") ?: ""
        if (baseUrl.isEmpty()) {
            finish()
            return
        }
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            setSupportZoom(false)
            allowFileAccess = true
            allowContentAccess = true
            // 移动壳激活标记：mobile-client.js 依此判断是否启用移动适配层
            userAgentString = "${webView.settings.userAgentString} DshMobile/1.0"
            // 缓存策略：有缓存时离线可用
            cacheMode = WebSettings.LOAD_CACHE_ELSE_NETWORK
            // 媒体自动播放
            mediaPlaybackRequiresUserGesture = false
        }

        if (BuildConfig.DEBUG) {
            WebView.setWebContentsDebuggingEnabled(true)
        }

        // 保留 WebView 缓存：与 LOAD_CACHE_ELSE_NETWORK 配合，断网时仍可打开已访问页面。

        // Cookie 认证：完整管理 + 自动续期
        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        cm.setAcceptThirdPartyCookies(webView, true)
        applyAuthCookie(baseUrl)

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = false

            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                if (request == null || request.method != "GET") return super.shouldInterceptRequest(view, request)
                val uri = request.url
                val sameHost = uri.host == Uri.parse(baseUrl).host && uri.port == Uri.parse(baseUrl).port
                return if (sameHost && (uri.path ?: "").startsWith("/plugins/")) {
                    openAuthenticatedPluginResource(uri, request.requestHeaders) ?: super.shouldInterceptRequest(view, request)
                } else {
                    super.shouldInterceptRequest(view, request)
                }
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                bootChecks = 0
                showLoading()
                // 每次导航前重新种认证 cookie，防止被页面或系统清掉。
                applyAuthCookie(baseUrl)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                webView.evaluateJavascript(BACK_BRIDGE_JS, null)
                injectMobileLayer()
                monitorPageBoot()
                // 深链：通知点击 → 打开目标会话
                val pending = sessionId
                if (pending.isNotEmpty()) {
                    sessionId = ""
                    openSessionByDeepLink(pending)
                }
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: android.webkit.WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) {
                    showError("无法连接到 DSH：" + (error?.description ?: "网络错误"))
                }
            }

            override fun onReceivedHttpError(
                view: WebView?,
                request: WebResourceRequest?,
                errorResponse: WebResourceResponse?
            ) {
                super.onReceivedHttpError(view, request, errorResponse)
                if (request?.isForMainFrame != true) return
                when (errorResponse?.statusCode) {
                    401 -> {
                        Toast.makeText(this@WebActivity, "连接已失效，请回到设备列表重新配对", Toast.LENGTH_LONG).show()
                        finish()
                    }
                    else -> showError("DSH 返回了 HTTP " + (errorResponse?.statusCode ?: 0))
                }
            }
        }

        // Chrome 客户端：文件上传 + 相机拍摄 + 摄像头权限
        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                fileUploadCallback?.onReceiveValue(null)
                fileUploadCallback = filePathCallback
                cameraCaptureUri = null

                val baseIntent = fileChooserParams?.createIntent()
                if (baseIntent == null) {
                    fileUploadCallback?.onReceiveValue(null)
                    fileUploadCallback = null
                    return false
                }

                val chooser = Intent.createChooser(baseIntent, "选择文件")
                // 相机拍摄走 FileProvider（Android 7+ 必须 content:// 才能跨应用写）
                if (fileChooserParams.isCaptureEnabled) {
                    val capture = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                        val uri = createCameraCaptureUri()
                        cameraCaptureUri = uri
                        putExtra(MediaStore.EXTRA_OUTPUT, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                    }
                    if (capture.resolveActivity(packageManager) != null) {
                        chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(capture))
                    }
                }
                fileChooserLauncher.launch(chooser)
                return true
            }

            override fun onPermissionRequest(request: PermissionRequest?) {
                handleWebPermissionRequest(request)
            }
        }

        // 文件下载：系统下载管理器（HStudio 同款）
        webView.setDownloadListener(
            DownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
                download(url, userAgent, contentDisposition, mimeType)
            },
        )

        webView.loadUrl(contentUrl())

        // 返回键：WebView 内部路由优先（JS 页面栈检测），否则退出
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = handleBack()
        })
    }

    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val callback = fileUploadCallback
        if (callback == null) return@registerForActivityResult
        var uris: Array<Uri>? = null
        if (result.resultCode == RESULT_OK) {
            if (result.data != null) {
                uris = WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
            }
            // 相机拍摄走 EXTRA_OUTPUT，result.data 为 null，用预创建的 content URI
            if ((uris == null || uris.isEmpty()) && cameraCaptureUri != null) {
                uris = arrayOf(cameraCaptureUri!!)
            }
        }
        callback.onReceiveValue(uris)
        fileUploadCallback = null
        cameraCaptureUri = null
    }

    private val webPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val request = pendingPermissionRequest
        pendingPermissionRequest = null
        if (request == null) return@registerForActivityResult

        val needsCamera = request.resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)
        val needsAudio = request.resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
        val granted = (!needsCamera || grants[Manifest.permission.CAMERA] == true ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) &&
            (!needsAudio || grants[Manifest.permission.RECORD_AUDIO] == true ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
        if (granted) {
            request.grant(request.resources)
        } else {
            request.deny()
            Toast.makeText(this, "需要相机或麦克风权限才能继续", Toast.LENGTH_LONG).show()
        }
    }

    private fun handleWebPermissionRequest(request: PermissionRequest?) {
        if (request == null) return
        val permissions = mutableListOf<String>()
        if (request.resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)) {
            permissions.add(Manifest.permission.CAMERA)
        }
        if (request.resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) {
            permissions.add(Manifest.permission.RECORD_AUDIO)
        }
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            request.grant(request.resources)
            return
        }
        pendingPermissionRequest?.deny()
        pendingPermissionRequest = request
        webPermissionLauncher.launch(missing.toTypedArray())
    }

    /**
     * WebView 动态 script 有时不会携带认证 cookie；插件 bundle 走这里，
     * 原生请求强制附带 token，避免手机端停在“Failed to load plugins”。
     */
    private fun openAuthenticatedPluginResource(uri: Uri, headers: Map<String, String>): WebResourceResponse? {
        return try {
            val connection = URL(uri.toString()).openConnection() as HttpURLConnection
            connection.connectTimeout = 8_000
            connection.readTimeout = 12_000
            connection.requestMethod = "GET"
            connection.setRequestProperty("x-dsh-link-token", token)
            CookieManager.getInstance().getCookie(uri.toString())?.let {
                connection.setRequestProperty("Cookie", it)
            }
            headers["User-Agent"]?.let { connection.setRequestProperty("User-Agent", it) }
            headers["Accept"]?.let { connection.setRequestProperty("Accept", it) }
            val code = connection.responseCode
            if (code !in 200..299) {
                connection.disconnect()
                null
            } else {
                val contentType = connection.contentType ?: "application/javascript"
                val mime = contentType.substringBefore(';').ifBlank { "application/javascript" }
                val charset = Regex("charset=([^;]+)", RegexOption.IGNORE_CASE)
                    .find(contentType)?.groupValues?.getOrNull(1)?.trim() ?: "utf-8"
                WebResourceResponse(mime, charset, connection.inputStream)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun showLoading() {
        loading.visibility = View.VISIBLE
        errorState.visibility = View.GONE
    }

    private fun hideStates() {
        loading.visibility = View.GONE
        errorState.visibility = View.GONE
    }

    private fun showError(message: String) {
        loading.visibility = View.GONE
        errorMessage.text = message
        errorState.visibility = View.VISIBLE
    }

    private fun injectMobileLayer() {
        try {
            // 1) 移动布局 CSS（Base64 注入，避免转义问题）
            val css = assets.open("mobile_override.css").bufferedReader().use { it.readText() }
            val encodedCss = android.util.Base64.encodeToString(css.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
            val cssJs = """
                (function() {
                    var styleId = 'dsh-native-mobile-css';
                    var style = document.getElementById(styleId);
                    if (!style) {
                        style = document.createElement('style');
                        style.id = styleId;
                        document.head.appendChild(style);
                    }
                    style.textContent = atob('$encodedCss');
                })()
            """.trimIndent()
            webView.evaluateJavascript(cssJs, null)

            // 2) 移动壳交互 JS（FAB / 抽屉 / 深链桥）+ 结果监听
            val shellJs = assets.open("mobile-client.js").bufferedReader().use { it.readText() }
            val resultBridge = """
                ;(function() {
                    if (window.__dshMobileResultBridge) return;
                    window.__dshMobileResultBridge = true;
                    window.__dshMobileLastResult = null;
                    window.addEventListener('dsh-mobile-open-session-result', function(e) {
                        window.__dshMobileLastResult = (e && e.detail) ? e.detail : null;
                    });
                })()
            """.trimIndent()
            webView.evaluateJavascript(shellJs + resultBridge, null)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 深链：通知点击后定位到目标会话。
     * 通过 dsh-mobile-open-session 事件交给注入的移动壳处理，
     * 然后轮询读取结果（V1 简化实现；失败不打断用户）。
     */
    private fun openSessionByDeepLink(targetSessionId: String) {
        val requestId = System.currentTimeMillis().toString()
        val dispatchJs = """
            window.dispatchEvent(new CustomEvent('dsh-mobile-open-session', {
                detail: { sessionId: ${jsonString(targetSessionId)}, requestId: ${jsonString(requestId)} }
            }));
        """.trimIndent()
        webView.evaluateJavascript(dispatchJs, null)
        // 轮询结果（最多 8 次，每次 600ms）
        val poll = object : Runnable {
            var attempts = 0
            override fun run() {
                if (attempts++ >= 8) return
                webView.evaluateJavascript(
                    "window.__dshMobileLastResult ? (window.__dshMobileLastResult.ok ? '1' : '0') : 'null'"
                ) { value ->
                    val v = value?.trim() ?: "null"
                    if (v != "null") {
                        if (v != "\"1\"") {
                            Toast.makeText(this@WebActivity, "无法打开目标会话", Toast.LENGTH_SHORT).show()
                        }
                        webView.evaluateJavascript("window.__dshMobileLastResult = null", null)
                    } else {
                        bootHandler.postDelayed(this, 600)
                    }
                }
            }
        }
        bootHandler.postDelayed(poll, 600)
    }

    private fun jsonString(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                else -> sb.append(c)
            }
        }
        sb.append("\"")
        return sb.toString()
    }

    private fun contentUrl(): String = baseUrl.trimEnd('/') + "/"

    private fun reloadPage() {
        bootChecks = 0
        applyAuthCookie(baseUrl)
        showLoading()
        webView.reload()
    }

    private fun monitorPageBoot() {
        val probe = """
            (function () {
              var text = document.body ? document.body.innerText : '';
              var html = document.documentElement;
              // 正常 DSH UI 或移动壳已经挂载，聊天内容中出现错误文案也不能误判为故障。
              if (html.classList.contains('dsh-mobile-shell') || document.querySelector('textarea, [data-shell-overlay]')) {
                return 'ready';
              }
              if (text.indexOf('Failed to load plugins') >= 0) return 'failed';
              if (text.indexOf('Loading plugins') >= 0) return 'loading';
              return 'ready';
            })()
        """.trimIndent()
        webView.evaluateJavascript(probe) { raw ->
            when (raw?.trim('"')) {
                "failed" -> showError("DSH 插件加载失败，请重试")
                "loading" -> {
                    if (bootChecks++ < 20) {
                        bootHandler.postDelayed({ monitorPageBoot() }, 700)
                    } else {
                        showError("DSH 加载超时，请检查网络后重试")
                    }
                }
                else -> hideStates()
            }
        }
    }

    /** 认证 cookie 自动续期：每次加载前重新种，双 host 覆盖。 */
    private fun applyAuthCookie(baseUrl: String) {
        try {
            if (token.isEmpty()) return
            val cm = CookieManager.getInstance()
            val cookie = "dsh_link_token=" + token + "; Path=/"
            cm.setCookie(baseUrl, cookie)
            val host = Uri.parse(baseUrl).host
            if (host != null && !host.startsWith("127.") && host != "localhost") {
                cm.setCookie("http://" + host, cookie)
            }
            cm.flush()
        } catch (_: Exception) {
        }
    }

    private fun createCameraCaptureUri(): Uri {
        val dir = File(cacheDir, "uploads").apply { mkdirs() }
        val file = File(dir, "camera_" + System.currentTimeMillis() + ".jpg")
        return FileProvider.getUriForFile(this, packageName + ".fileprovider", file)
    }

    private fun download(url: String, userAgent: String?, contentDisposition: String?, mimeType: String?) {
        try {
            val fileName = guessFileName(url, contentDisposition, mimeType)
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                setMimeType(mimeType ?: "application/octet-stream")
                setTitle(fileName)
                setDescription(getString(R.string.app_name))
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                if (!userAgent.isNullOrEmpty()) addRequestHeader("User-Agent", userAgent)
                // 代理按 cookie 鉴权：显式带上保证下载成功
                CookieManager.getInstance().getCookie(url)?.let { addRequestHeader("Cookie", it) }
            }
            val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            dm.enqueue(request)
            Toast.makeText(this, "开始下载：" + fileName, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "下载失败：" + e.message, Toast.LENGTH_LONG).show()
        }
    }

    private fun guessFileName(url: String, contentDisposition: String?, mimeType: String?): String {
        contentDisposition?.let { cd ->
            val m = Regex("filename\\*?=(?:UTF-8''|\")([^\";]+)", RegexOption.IGNORE_CASE).find(cd)
            if (m != null) {
                val name = m.groupValues[1].trim().trim('"').replace("%20", " ")
                if (name.isNotEmpty() && !name.contains('/') && name != "." && name != "..") return name
            }
        }
        Uri.parse(url).lastPathSegment?.let { seg ->
            if (seg.isNotEmpty() && !seg.contains('/')) return seg
        }
        val ext = when (mimeType?.lowercase()) {
            "application/pdf" -> ".pdf"
            "text/plain" -> ".txt"
            "text/html" -> ".html"
            "application/zip" -> ".zip"
            "application/json" -> ".json"
            "image/png" -> ".png"
            "image/jpeg" -> ".jpg"
            "image/gif" -> ".gif"
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> ".xlsx"
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> ".docx"
            else -> ""
        }
        return "dsh_" + System.currentTimeMillis() + ext
    }

    private fun handleBack() {
        webView.evaluateJavascript(BACK_BRIDGE_JS) { raw ->
            val value = raw?.trim('"')?.trim() ?: "-1"
            when {
                value == "ui" -> Unit // 已关闭移动壳抽屉，返回键已消费
                (value.toIntOrNull() ?: -1) > 0 ->
                    webView.evaluateJavascript("window.__dshBack.back();", null)
                webView.canGoBack() -> webView.goBack()
                else -> {
                    // 从 Splash 直达 WebActivity（任务根）：返回先回设备列表，避免无法管理设备
                    if (isTaskRoot()) {
                        startActivity(Intent(this, MainActivity::class.java))
                    }
                    finish()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::webView.isInitialized) webView.onResume()
    }

    override fun onPause() {
        if (::webView.isInitialized) webView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        bootHandler.removeCallbacksAndMessages(null)
        pendingPermissionRequest?.deny()
        pendingPermissionRequest = null
        if (::webView.isInitialized) webView.destroy()
        super.onDestroy()
    }
}
