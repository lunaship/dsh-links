package dev.deeplinks.native
import dev.deeplinks.core.Dsh

import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.LinkedHashMap
import java.util.Locale
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import dev.deeplinks.core.DshType

private const val MAX_MATH_RENDER_CSS_WIDTH = 1_024f
private const val MAX_MATH_RENDER_CSS_HEIGHT = 768f
private const val MAX_MATH_RENDER_BITMAP_PIXELS = 4_000_000L

/**
 * LaTeX 数学公式渲染（KaTeX 0.18，MIT，经共享离屏 WebView 绘制为位图，与 Web UI 同源排版）。
 * - 行内公式 `$...$`：与 Markdown 文本流内联（InlineTextContent 占位，尺寸由公式决定）
 * - 块级公式 `$$...$$`：独立居中块，displayMode 渲染、20px 字号
 * WebView 渲染天然异步：未命中缓存时先回退原文，渲染完成后 revision 变化触发重组换图。
 * 渲染失败（含非法 LaTeX）负缓存并回退原文，保证消息流不崩。
 */

object MathRenderer {
    /** 一条已渲染公式：位图 + CSS 像素尺寸（位图物理像素 = CSS × 屏幕密度）。 */
    class Rendered(val bitmap: ImageBitmap, val cssWidth: Float, val cssHeight: Float)

    private const val MAX_CACHE_ENTRIES = 96
    private const val MAX_CACHE_PIXELS = 16_000_000L
    private const val MAX_FAILURE_ENTRIES = 256
    private const val MAX_LATEX_CHARS = 32_000
    private const val RENDER_TIMEOUT_MS = 6_000L
    private const val TAG = "MathRenderer"

    /** 行内/块级公式字号（CSS px，与正文 16sp、块级 20sp 对齐）。 */
    const val INLINE_FONT_CSS_PX = 16f
    const val DISPLAY_FONT_CSS_PX = 20f

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val cacheLock = Any()
    private val cache = LinkedHashMap<String, Rendered>(64, 0.75f, true)
    private val failures = object : LinkedHashMap<String, Unit>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Unit>?): Boolean =
            size > MAX_FAILURE_ENTRIES
    }

    private var cachePixels = 0L
    private val inFlight = HashSet<String>()

    @Volatile private var appContext: Context? = null
    private var webView: WebView? = null
    private var pageReady: CompletableDeferred<Unit>? = null
    private val renderMutex = Mutex()

    var revision by mutableIntStateOf(0)
        private set

    private fun bumpRevision() {
        revision++
    }

    /** 供组合层注入 ApplicationContext（幂等，须在主线程）。 */
    fun attach(context: Context) {
        if (appContext == null) appContext = context.applicationContext
    }

    /**
     * 仅供 androidTest 收尾调用（须在主线程）：销毁共享离屏 WebView 并清空缓存。
     *
     * 该 WebView 常驻进程生命周期，App 内从不需要释放；但同一 Instrumentation 进程内
     * 跑完一个测试类不会重启进程，WebView 及其 Chromium 沙箱进程会跨测试类残留，
     * 阻塞后续 Compose UI 测试的 Espresso 主线程空闲检测（表现为下一个测试类挂起、
     * 真机白屏无响应）。
     */
    fun resetForTests() {
        webView?.destroy()
        webView = null
        pageReady = null
        synchronized(cacheLock) {
            cache.clear()
            cachePixels = 0L
            failures.clear()
            inFlight.clear()
        }
    }

    private fun cacheKey(latex: String, cssFontPx: Float, colorArgb: Int, displayMode: Boolean) =
        "$latex\u0000$cssFontPx\u0000$colorArgb\u0000$displayMode"

    /** 缓存优先；未命中且未在渲染中则后台排队，完成或失败后 bumpRevision()。 */
    fun peek(latex: String, cssFontPx: Float, colorArgb: Int, displayMode: Boolean = false): Rendered? {
        val key = cacheKey(latex, cssFontPx, colorArgb, displayMode)
        synchronized(cacheLock) {
            cache[key]?.let { return it }
            if (failures.containsKey(key)) return null
            if (!inFlight.add(key)) return null
        }
        scope.launch {
            try {
                render(latex, cssFontPx, colorArgb, displayMode)
            } finally {
                synchronized(cacheLock) { inFlight.remove(key) }
            }
        }
        return null
    }

    /** 挂起渲染（块级公式用）；成功入缓存，失败入负缓存并返回 null。 */
    suspend fun render(
        latex: String,
        cssFontPx: Float,
        colorArgb: Int,
        displayMode: Boolean = false,
    ): Rendered? {
        val key = cacheKey(latex, cssFontPx, colorArgb, displayMode)
        synchronized(cacheLock) { cache[key]?.let { return it } }
        val rendered = renderMutex.withLock {
            synchronized(cacheLock) {
                cache[key]?.let { return@withLock it }
                if (failures.containsKey(key)) return@withLock null
            }
            renderUncached(latex, cssFontPx, colorArgb, displayMode)
        }
        synchronized(cacheLock) {
            if (rendered != null) putCache(key, rendered) else failures[key] = Unit
        }
        bumpRevision()
        return rendered
    }

    private fun putCache(key: String, rendered: Rendered) {
        cache.remove(key)?.let { cachePixels -= it.bitmap.width.toLong() * it.bitmap.height }
        cache[key] = rendered
        cachePixels += rendered.bitmap.width.toLong() * rendered.bitmap.height
        while (cache.size > MAX_CACHE_ENTRIES || cachePixels > MAX_CACHE_PIXELS) {
            val eldest = cache.entries.firstOrNull() ?: break
            cache.remove(eldest.key)
            cachePixels -= eldest.value.bitmap.width.toLong() * eldest.value.bitmap.height
        }
    }

    private suspend fun renderUncached(
        latex: String,
        cssFontPx: Float,
        colorArgb: Int,
        displayMode: Boolean,
    ): Rendered? = withContext(Dispatchers.Main) {
        if (latex.length > MAX_LATEX_CHARS) {
            android.util.Log.w(TAG, "render: latex too large chars=${latex.length}")
            return@withContext null
        }
        val wv = ensureWebView() ?: run {
            android.util.Log.w(TAG, "render: ensureWebView returned null")
            return@withContext null
        }
        try {
            withTimeoutOrNull(RENDER_TIMEOUT_MS) {
                pageReady?.await()
                val result = evaluate(wv, latex, cssFontPx, colorArgb, displayMode)
                if (result == null) {
                    android.util.Log.w(TAG, "render: evaluate returned null")
                    null
                } else if (!result.optBoolean("ok", false)) {
                    android.util.Log.w(TAG, "render: JS ok=false: ${result}")
                    null
                } else {
                    val cssW = result.optDouble("w", 0.0).toFloat()
                    val cssH = result.optDouble("h", 0.0).toFloat()
                    val bitmapSize = safeMathBitmapSize(cssW, cssH, wv.resources.displayMetrics.density)
                    if (bitmapSize == null) {
                        android.util.Log.w(TAG, "render: invalid size w=$cssW h=$cssH")
                        null
                    } else {
                        val scale = wv.resources.displayMetrics.density
                        val offsetX = (result.optDouble("l", 0.0).toFloat() * scale).toInt()
                        val offsetY = (result.optDouble("t", 0.0).toFloat() * scale).toInt()
                        drawWebViewToBitmap(wv, bitmapSize.width, bitmapSize.height, offsetX, offsetY)
                            ?.let { Rendered(it.asImageBitmap(), cssW, cssH) }
                    }
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w(TAG, "render: exception", e)
            null
        }
    }

    private suspend fun evaluate(
        wv: WebView,
        latex: String,
        cssFontPx: Float,
        colorArgb: Int,
        displayMode: Boolean,
    ): JSONObject? = suspendCancellableCoroutine { cont ->
        val safeLatex = latex
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\n", "\\n")
            .replace("\r", "")
        val a = Color.alpha(colorArgb)
        val color = String.format(
            Locale.US,
            "rgba(%d,%d,%d,%.3f)",
            Color.red(colorArgb),
            Color.green(colorArgb),
            Color.blue(colorArgb),
            a / 255.0,
        )
        val js = buildString {
            append("(function(){var el=document.getElementById('m');")
            append("el.style.fontSize='${cssFontPx}px';el.style.color='$color';")
            append("try{katex.render('$safeLatex',el,{throwOnError:true,displayMode:$displayMode,output:'html'});")
            // 量内层 .katex（displayMode 下外层 .katex-display 是占满容器的块级元素）
            append("var k=el.querySelector('.katex')||el;var r=k.getBoundingClientRect();")
            append("return {ok:true,w:Math.ceil(r.width),h:Math.ceil(r.height),l:r.left,t:r.top};}")
            append("catch(e){return {ok:false};}})()")
        }
        mainHandler.post {
            try {
                wv.evaluateJavascript(js) { json ->
                    if (cont.isActive) {
                        val parsed = parseJsResult(json)
                        cont.resumeWith(Result.success(parsed))
                    }
                }
            } catch (_: Exception) {
                if (cont.isActive) cont.resumeWith(Result.success(null))
            }
        }
    }

    private fun ensureWebView(): WebView? {
        webView?.let { return it }
        val context = appContext ?: return null
        val ready = CompletableDeferred<Unit>()
        pageReady = ready
        val wv = createOffscreenWebView(context, 1024, 768, "file:///android_asset/katex/", PAGE_HTML) {
            ready.complete(Unit)
        }
        webView = wv
        return wv
    }

    private const val PAGE_HTML = """
        <!DOCTYPE html><html><head><meta charset="utf-8">
        <link rel="stylesheet" href="katex.min.css">
        <style>html,body{margin:0;padding:0;background:transparent;overflow:hidden}</style>
        </head><body><span id="m"></span>
        <script src="katex.min.js"></script>
        </body></html>
    """
}

internal data class MathBitmapSize(val width: Int, val height: Int)

internal fun safeMathBitmapSize(cssWidth: Float, cssHeight: Float, density: Float): MathBitmapSize? {
    if (!cssWidth.isFinite() || !cssHeight.isFinite() || !density.isFinite() || density <= 0f) return null
    if (cssWidth < 1f || cssHeight < 1f || cssWidth > MAX_MATH_RENDER_CSS_WIDTH || cssHeight > MAX_MATH_RENDER_CSS_HEIGHT) return null
    val width = kotlin.math.ceil(cssWidth.toDouble() * density.toDouble()).toLong()
    val height = kotlin.math.ceil(cssHeight.toDouble() * density.toDouble()).toLong()
    if (width !in 1..2_147_483_647L || height !in 1..2_147_483_647L) return null
    if (width * height > MAX_MATH_RENDER_BITMAP_PIXELS) return null
    return MathBitmapSize(width.toInt(), height.toInt())
}

/** 公式位图绘制（InlineTextContent 占位内容 / 块级公式共用）。 */
@Composable
fun LatexMathCanvas(rendered: MathRenderer.Rendered, modifier: Modifier = Modifier) {
    Image(
        bitmap = rendered.bitmap,
        contentDescription = null,
        contentScale = ContentScale.FillBounds,
        modifier = modifier,
    )
}

/** 块级公式（$$...$$）：居中，20px。渲染未就绪或失败回退等宽原文。 */
@Composable
fun LatexDisplayBlock(latex: String, modifier: Modifier = Modifier) {
    val color = Dsh.labelPrimary
    val context = LocalContext.current
    val density = LocalDensity.current
    SideEffect { MathRenderer.attach(context.applicationContext) }
    val revision = MathRenderer.revision
    var rendered by remember(latex, color, revision) {
        mutableStateOf(MathRenderer.peek(latex, MathRenderer.DISPLAY_FONT_CSS_PX, color.toArgb(), displayMode = true))
    }
    LaunchedEffect(latex, color, revision) {
        if (rendered == null) {
            rendered = MathRenderer.render(latex, MathRenderer.DISPLAY_FONT_CSS_PX, color.toArgb(), displayMode = true)
        }
    }
    val drawn = rendered
    if (drawn == null) {
        Text(
            latex,
            color = Dsh.labelTertiary,
            style = DshType.t14,
            lineHeight = 22.sp,
            fontFamily = FontFamily.Monospace,
            modifier = modifier.fillMaxWidth()
        )
        return
    }
    val size = with(density) {
        DpSize(width = drawn.cssWidth.toDp(), height = drawn.cssHeight.toDp())
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        contentAlignment = Alignment.Center
    ) {
        LatexMathCanvas(drawn, Modifier.size(size))
    }
}
