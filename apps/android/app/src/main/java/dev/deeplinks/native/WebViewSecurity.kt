package dev.deeplinks.native

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import org.json.JSONTokener
import java.nio.IntBuffer
import kotlinx.coroutines.delay

/**
 * 收紧渲染不受信内容的离屏 WebView（Mermaid / KaTeX）。
 *
 * 只保留 JS 执行能力（本地 bundle 渲染所需），其余文件、跨源、ContentProvider
 * 访问全部关闭，并开启 Safe Browsing。不得在此开启任何新能力。
 */
@SuppressLint("SetJavaScriptEnabled")
@Suppress(
    "ObsoleteSdkInt", // minSdk 26 已覆盖该 API；保留显式判断，便于将来下调 minSdk
    "DEPRECATION", // file-URL 跨源开关在 API 30 起废弃，但低版本仍需显式关闭
)
internal fun WebView.hardenUntrustedSettings() {
    settings.javaScriptEnabled = true
    settings.allowFileAccess = false
    settings.allowContentAccess = false
    settings.allowFileAccessFromFileURLs = false
    settings.allowUniversalAccessFromFileURLs = false
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        settings.safeBrowsingEnabled = true
    }
}

/**
 * 建一个离屏 WebView（KaTeX / Mermaid 共用）：透明背景、收紧设置、给定初始尺寸。
 * 离屏 WebView 不参与布局树，不给初始尺寸时页面排版宽度为 0。
 */
@SuppressLint("SetJavaScriptEnabled")
internal fun createOffscreenWebView(
    context: Context,
    cssWidth: Int,
    cssHeight: Int,
    assetBaseUrl: String,
    html: String,
    onPageFinished: () -> Unit,
): WebView {
    val density = context.resources.displayMetrics.density
    val width = (cssWidth * density).toInt()
    val height = (cssHeight * density).toInt()
    val wv = WebView(context)
    wv.hardenUntrustedSettings()
    wv.setBackgroundColor(android.graphics.Color.TRANSPARENT)
    wv.measure(
        android.view.View.MeasureSpec.makeMeasureSpec(width, android.view.View.MeasureSpec.EXACTLY),
        android.view.View.MeasureSpec.makeMeasureSpec(height, android.view.View.MeasureSpec.EXACTLY),
    )
    wv.layout(0, 0, width, height)
    wv.webViewClient = object : WebViewClient() {
        override fun onPageFinished(view: WebView?, url: String?) = onPageFinished()
    }
    wv.loadDataWithBaseURL(assetBaseUrl, html, "text/html", "utf-8", null)
    return wv
}

/**
 * 解析 JS 回传值（evaluateJavascript 回调 / @JavascriptInterface 入参）：
 * WebView 对返回值做 JSON 编码，对象直接为 {"ok":true,...}；若 JS 端返回字符串
 * 则再包一层引号（"{\"ok\":...}"），两层都兼容。
 */
internal fun parseJsResult(json: String?): JSONObject? {
    if (json == null) return null
    runCatching { JSONObject(json) }.getOrNull()?.let { return it }
    return runCatching { JSONObject(JSONTokener(json).nextValue().toString()) }.getOrNull()
}

/**
 * 把离屏 WebView 绘制成位图。离屏 WebView 无 UI 循环：draw 回调后合成器可能还没提交
 * 可见帧，因此检测非全透明像素，空帧则延时重画（最多 [attempts] 轮）。
 */
internal suspend fun drawWebViewToBitmap(
    wv: WebView,
    physWidth: Int,
    physHeight: Int,
    offsetX: Int,
    offsetY: Int,
    attempts: Int = 4,
): Bitmap? {
    repeat(attempts) { attempt ->
        val bmp = Bitmap.createBitmap(physWidth, physHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.translate(-offsetX.toFloat(), -offsetY.toFloat())
        wv.draw(canvas)
        val pixels = IntArray(physWidth * physHeight)
        bmp.copyPixelsToBuffer(IntBuffer.wrap(pixels))
        if (pixels.any { (it ushr 24) != 0 }) return bmp
        bmp.recycle()
        if (attempt < attempts - 1) delay(200L)
    }
    return null
}
