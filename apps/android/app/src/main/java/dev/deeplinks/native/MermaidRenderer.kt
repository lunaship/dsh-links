package dev.deeplinks.native

import dev.deeplinks.core.DshType
import dev.deeplinks.native.ui.DshHeaderAction

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.annotation.Keep
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.deeplinks.core.PixelBudgetLru
import dev.deeplinks.core.dshRipple
import dev.deeplinks.core.Dsh
import dev.deeplinks.core.L
import java.util.LinkedHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

private const val MAX_MERMAID_CACHE_ENTRIES = 24

/** 与 MathRenderer 对齐的总像素预算（先对齐再按低内存设备实测决定是否下调）。 */
internal const val MERMAID_MAX_CACHE_PIXELS = 16_000_000L

/**
 * 单图预算：一张位图本身的像素数不能超过缓存总预算。
 * 超限时宁可回退源码块，也不要在低内存设备上分配一张占满配额的大图。
 *
 * 放在顶层（而不是 object 内部）以便 JVM 单测直接调用：引用 object 常量会触发
 * MermaidRenderer 的初始化（Handler/CoroutineScope），在单测环境下会失败。
 */
internal fun mermaidBitmapWithinBudget(
    physW: Int,
    physH: Int,
    budget: Long = MERMAID_MAX_CACHE_PIXELS,
): Boolean = physW > 0 && physH > 0 && physW.toLong() * physH <= budget

/**
 * Mermaid 11.17 图渲染（MIT，bundled assets，离屏 WebView 绘成位图）。
 * 失败或超时回退等宽源码，不打断消息流。
 *
 * 缓存边界：条目数 *和* 总像素双重约束（见 [PixelBudgetLru]）。只按条目数逐出时，
 * 24 张 2048×2048 ARGB_8888 位图可以占到 400MB 量级，低内存设备直接 OOM。
 */
object MermaidRenderer {
    class Rendered(val bitmap: ImageBitmap, val cssWidth: Float, val cssHeight: Float)

    private const val MAX_CACHE_ENTRIES = MAX_MERMAID_CACHE_ENTRIES
    private const val MAX_FAILURE_ENTRIES = 64
    private const val RENDER_TIMEOUT_MS = 12_000L
    private const val MAX_BITMAP_EDGE = 2048

    /** 与 MathRenderer 对齐的总像素预算；见 [MERMAID_MAX_CACHE_PIXELS]。 */
    internal const val MAX_CACHE_PIXELS = MERMAID_MAX_CACHE_PIXELS

    private const val TAG = "MermaidRenderer"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val cacheLock = Any()
    private val cache = PixelBudgetLru<String, Rendered>(
        maxEntries = MAX_CACHE_ENTRIES,
        maxPixels = MAX_CACHE_PIXELS,
        pixelsOf = { it.bitmap.width.toLong() * it.bitmap.height },
    )
    private val failures = object : LinkedHashMap<String, Unit>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Unit>?): Boolean =
            size > MAX_FAILURE_ENTRIES
    }

    @Volatile private var appContext: Context? = null
    private var webView: WebView? = null
    private var pageReady: CompletableDeferred<Unit>? = null
    private val renderMutex = Mutex()
    private var resultDeferred: CompletableDeferred<JSONObject?>? = null

    var revision: Int = 0
        private set

    private fun bumpRevision() {
        revision++
    }

    fun attach(context: Context) {
        if (appContext == null) appContext = context.applicationContext
    }

    private fun cacheKey(source: String, dark: Boolean) = "$dark\u0000$source"

    fun peek(source: String, dark: Boolean, persistFailure: Boolean = true): Rendered? {
        val key = cacheKey(source, dark)
        synchronized(cacheLock) {
            cache.get(key)?.let { return it }
            if (persistFailure && failures.containsKey(key)) return null
        }
        scope.launch { render(source, dark, persistFailure) }
        return null
    }

    suspend fun render(source: String, dark: Boolean, persistFailure: Boolean = true): Rendered? {
        val key = cacheKey(source, dark)
        synchronized(cacheLock) { cache.get(key)?.let { return it } }
        val rendered = renderMutex.withLock { renderUncached(source, dark) }
        synchronized(cacheLock) {
            if (rendered != null) {
                cache.put(key, rendered)
                bumpRevision()
            } else if (persistFailure) {
                failures[key] = Unit
                bumpRevision()
            }
        }
        return rendered
    }

    private suspend fun renderUncached(source: String, dark: Boolean): Rendered? =
        withContext(Dispatchers.Main) {
            val wv = ensureWebView() ?: run {
                android.util.Log.w(TAG, "render: ensureWebView returned null")
                return@withContext null
            }
            try {
                withTimeoutOrNull(RENDER_TIMEOUT_MS) {
                    pageReady?.await()
                    val pending = CompletableDeferred<JSONObject?>()
                    resultDeferred = pending
                    evaluate(wv, source, dark)
                    val result = pending.await()
                    if (result == null || !result.optBoolean("ok", false)) {
                        android.util.Log.w(TAG, "render: JS ok=false: $result")
                        null
                    } else {
                        val cssW = result.optDouble("w", 0.0).toFloat()
                        val cssH = result.optDouble("h", 0.0).toFloat()
                        if (cssW < 1f || cssH < 1f) {
                            android.util.Log.w(TAG, "render: zero size w=$cssW h=$cssH")
                            null
                        } else {
                            capture(wv, cssW, cssH, result)
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w(TAG, "render: exception", e)
                null
            } finally {
                resultDeferred = null
            }
        }

    private suspend fun capture(
        wv: WebView,
        cssW: Float,
        cssH: Float,
        result: JSONObject,
    ): Rendered? {
        val density = wv.resources.displayMetrics.density
        val physW = (cssW * density).toInt().coerceAtLeast(1)
        val physH = (cssH * density).toInt().coerceAtLeast(1)
        if (physW > MAX_BITMAP_EDGE || physH > MAX_BITMAP_EDGE) {
            android.util.Log.w(TAG, "render: bitmap too large ${physW}x${physH}")
            return null
        }
        // 单图预算：超限不进缓存也不分配位图，直接降级为源码块。
        if (!mermaidBitmapWithinBudget(physW, physH)) {
            android.util.Log.w(TAG, "render: bitmap exceeds pixel budget ${physW}x${physH}")
            return null
        }
        val offsetX = (result.optDouble("l", 0.0).toFloat() * density).toInt()
        val offsetY = (result.optDouble("t", 0.0).toFloat() * density).toInt()
        val bmp = drawWebViewToBitmap(wv, physW, physH, offsetX, offsetY) ?: return null
        return Rendered(bmp.asImageBitmap(), cssW, cssH)
    }

    private fun evaluate(wv: WebView, source: String, dark: Boolean) {
        val payload = JSONObject().put("src", source).put("dark", dark)
        val js = """
            (function(){
              var p=$payload;
              var el=document.getElementById('m');
              el.innerHTML='';
              try {
                mermaid.initialize({
                  startOnLoad:false,
                  securityLevel:'strict',
                  theme: p.dark ? 'dark' : 'neutral',
                  flowchart:{htmlLabels:false},
                  fontFamily:'sans-serif'
                });
                mermaid.render('dsh'+Date.now(), p.src).then(function(out){
                  el.innerHTML=out.svg;
                  var svg=el.querySelector('svg')||el;
                  var r=svg.getBoundingClientRect();
                  MermaidBridge.onResult(JSON.stringify({
                    ok:true,
                    w:Math.ceil(r.width),
                    h:Math.ceil(r.height),
                    l:r.left,
                    t:r.top
                  }));
                }).catch(function(e){
                  MermaidBridge.onResult(JSON.stringify({ok:false}));
                });
              } catch(e) {
                MermaidBridge.onResult(JSON.stringify({ok:false}));
              }
              return true;
            })()
        """.trimIndent()
        wv.evaluateJavascript(js, null)
    }

    private fun ensureWebView(): WebView? {
        webView?.let { return it }
        val context = appContext ?: return null
        val ready = CompletableDeferred<Unit>()
        pageReady = ready
        val wv = createOffscreenWebView(context, 1400, 2400, "file:///android_asset/mermaid/", PAGE_HTML) {
            ready.complete(Unit)
        }
        wv.addJavascriptInterface(
            MermaidJsBridge { json -> resultDeferred?.complete(parseJsResult(json)) },
            "MermaidBridge",
        )
        webView = wv
        return wv
    }

    private const val PAGE_HTML = """
        <!DOCTYPE html><html><head><meta charset="utf-8">
        <style>html,body{margin:0;padding:0;background:transparent;overflow:hidden}#m{display:inline-block}</style>
        </head><body><div id="m"></div>
        <script src="mermaid.min.js"></script>
        </body></html>
    """
}

@Keep
internal class MermaidJsBridge(
    private val accept: (String) -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun onResult(json: String) {
        handler.post { accept(json) }
    }
}

@Composable
fun MermaidDiagramBlock(
    source: String,
    modifier: Modifier = Modifier,
    ephemeral: Boolean = false,
) {
    val dark = Dsh.isDark
    val context = LocalContext.current
    val density = LocalDensity.current
    SideEffect { MermaidRenderer.attach(context.applicationContext) }
    val revision = MermaidRenderer.revision
    var rendered by remember(source, dark, revision) {
        mutableStateOf(
            if (ephemeral) null else MermaidRenderer.peek(source, dark, persistFailure = true),
        )
    }
    LaunchedEffect(source, dark, revision, ephemeral) {
        if (ephemeral) kotlinx.coroutines.delay(450)
        if (rendered == null) {
            rendered = MermaidRenderer.render(source, dark, persistFailure = !ephemeral)
        }
    }
    val drawn = rendered
    var zoomOpen by remember { mutableStateOf(false) }
    if (drawn == null) {
        Text(
            source,
            color = Dsh.labelTertiary,
            style = DshType.bodyDense,
            fontFamily = FontFamily.Monospace,
            modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
        )
        return
    }
    val widthDp = with(density) { drawn.cssWidth.toDp() }
    val heightDp = with(density) { drawn.cssHeight.toDp() }.coerceAtMost(480.dp)
    var saveError by remember { mutableStateOf<String?>(null) }
    fun shareDiagram() {
        runCatching {
            ShareCardRenderer.sharePng(
                context,
                drawn.bitmap.asAndroidBitmap(),
                L.mermaidDiagram,
                L.save,
            )
        }.onSuccess {
            saveError = null
        }.onFailure { e ->
            saveError = L.exportFailed.format(e.message ?: L.unknownError)
        }
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .heightIn(max = 480.dp)
            .horizontalScroll(rememberScrollState()),
        contentAlignment = Alignment.CenterStart,
    ) {
        Image(
            bitmap = drawn.bitmap,
            contentDescription = L.mermaidDiagram,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .width(widthDp)
                .height(heightDp)
                .semantics { contentDescription = L.mermaidDiagram }
                .combinedClickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = dshRipple(),
                    onClick = { zoomOpen = true },
                    onLongClick = { shareDiagram() },
                ),
        )
    }
    val shownSaveError = saveError
    if (!shownSaveError.isNullOrBlank()) {
        val error = shownSaveError
        Text(
            error,
            color = Dsh.error,
            style = DshType.t12x17,
            lineHeight = 17.sp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp)
                .semantics { contentDescription = error },
        )
    }
    if (zoomOpen) {
        MermaidZoomDialog(
            bitmap = drawn.bitmap,
            error = saveError,
            onDismiss = { zoomOpen = false },
            onSave = { shareDiagram() },
        )
    }
}

@Composable
private fun MermaidZoomDialog(
    bitmap: ImageBitmap,
    error: String? = null,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f)
                .navigationBarsPadding()
                .padding(12.dp)
                .clip(RoundedCornerShape(DshRadius.lg))
                .background(Dsh.bgBase)
                .padding(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    L.mermaidDiagram,
                    color = Dsh.labelPrimary,
                    style = DshType.t14,
                    modifier = Modifier.weight(1f),
                )
                DshHeaderAction(L.save, onClick = onSave)
                DshHeaderAction(L.close, onClick = onDismiss)
            }
            if (!error.isNullOrBlank()) {
                Text(
                    error,
                    color = Dsh.error,
                    style = DshType.t12x17,
                    lineHeight = 17.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp)
                        .semantics { contentDescription = error },
                )
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(DshRadius.md))
                    .background(Dsh.bgCard)
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            val next = (scale * zoom).coerceIn(1f, 6f)
                            scale = next
                            offset = if (next <= 1.01f) Offset.Zero else offset + pan
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    bitmap = bitmap,
                    contentDescription = L.mermaidDiagram,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = offset.x
                            translationY = offset.y
                        },
                )
            }
        }
    }
}