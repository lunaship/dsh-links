package dev.deeplinks.native
import dev.deeplinks.core.BoundedIo
import dev.deeplinks.core.Host
import dev.deeplinks.core.HostHttp

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToLong
import kotlin.random.Random
import okhttp3.Call
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicLong

private const val MAX_SSE_DATA_CHARS = BoundedIo.MAX_SSE_LINE_BYTES
private const val TAG = "SessionStream"

internal const val STREAM_CLIENT_CAPS = "sync2,multiQuestion,requestState"

/** DSH 会话 SSE 实时流客户端。 */
class SessionStreamClient(
    private val host: Host,
    private val sessionId: String,
    private val scope: CoroutineScope,
) {
    sealed interface Item {
        data class Ready(val resumeSeq: Long) : Item
        data class Message(val seq: Long, val type: String, val time: Long, val data: JSONObject) : Item
        data class Stats(val projections: JSONObject) : Item
        /** mux 澄清卡（无 session seq，不参与 afterSeq 去重） */
        data class Question(val rpcId: String, val data: JSONObject) : Item
        data class QuestionResolved(val rpcId: String, val outcome: String) : Item
        data class ResyncRequired(
            val reason: String,
            val afterSeq: Long,
            val oldestAvailableSeq: Long?,
            val upgradeRequired: Boolean = false,
        ) : Item
        object Disconnected : Item
    }

    enum class ConnectionState { CONNECTING, CONNECTED, RETRYING, FAILURE }

    private val _items = Channel<Item>(capacity = 256)
    val items: Channel<Item> = _items
    private val lastSeqAtomic = AtomicLong(0)
    private val committedSeq = AtomicLong(0)
    val lastSeq: Long get() = committedSeq.get()
    var connectionState by mutableStateOf(ConnectionState.RETRYING)
        private set
    /** Compatibility for polling gates and existing callers. */
    val isConnected: Boolean get() = connectionState == ConnectionState.CONNECTED
    @Volatile private var seeded = false
    val isSeeded: Boolean get() = seeded
    var lastFailure by mutableStateOf<StreamFailure?>(null)
        private set
    private var job: Job? = null
    /** start() 已请求，但需等 history seed 后再真正连，避免 afterSeq=0 整段重放历史。 */
    @Volatile private var startRequested = false
    private val attemptGeneration = AtomicLong(0)
    private var connectionBytes = 0

    /**
     * history 加载后调用。[seq] 必须是 **SSE 事件编号空间** 的游标
     * （通常取页面消息的 max(seq)，见 [historySeedSeq]），用于 afterSeq，避免重放。
     * 不可传入虚高的 history.maxSeq 字段。
     */
    fun noteSeedMaxSeq(seq: Long) {
        seeded = true
        if (seq > 0) {
            committedSeq.updateAndGet { cur -> maxOf(cur, seq) }
            lastSeqAtomic.updateAndGet { cur -> maxOf(cur, seq) }
        }
        Log.i(TAG, "seeded session=${sessionId.take(8)} seedSeq=$seq afterSeq=$lastSeq")
        if (startRequested) ensureLoop()
    }

    /** ready 时用服务端 resumeSeq 对齐去重游标。 */
    fun alignToResumeSeq(resumeSeq: Long) {
        seeded = true
        lastSeqAtomic.updateAndGet { cur -> maxOf(cur, resumeSeq) }
    }

    fun noteCommittedSeq(seq: Long) {
        if (seq > 0) committedSeq.updateAndGet { cur -> maxOf(cur, seq) }
    }

    fun applySnapshotCursor(seq: Long) {
        seeded = true
        if (seq > 0) {
            committedSeq.updateAndGet { cur -> maxOf(cur, seq) }
            lastSeqAtomic.updateAndGet { cur -> maxOf(cur, seq) }
        }
    }

    /** 当前在途的 OkHttp 调用；stop() 用 cancel() 打断阻塞中的 SSE 读。 */
    private var call: Call? = null

    fun start() {
        startRequested = true
        if (!seeded) {
            Log.i(TAG, "start deferred until history seed session=${sessionId.take(8)}")
            return
        }
        ensureLoop()
    }

    private fun ensureLoop() {
        if (job?.isActive == true) return
        Log.i(TAG, "start session=${sessionId.take(8)} afterSeq=$lastSeq seeded=$seeded")
        job = scope.launch(Dispatchers.IO) { connectLoop() }
    }

    /** Safely cancel the current loop and start a fresh connection attempt. */
    fun reconnect() {
        stop()
        lastFailure = null
        connectionState = ConnectionState.CONNECTING
        startRequested = true
        // reconnect 时若已 seed，直接连；否则仍等 seed
        if (seeded) ensureLoop()
    }

    /** 停止当前流并等待下一次 history seed 再连接，避免用旧 afterSeq 再次撞上补发缺口。 */
    fun pauseForResync() {
        stop()
        lastFailure = null
        connectionState = ConnectionState.CONNECTING
        startRequested = true
        seeded = false
        // 游标必须一并清零：noteSeedMaxSeq 是单调 max，若历史编号空间与 stream 编号空间
        // 出现过偏高（实机曾见 maxSeq≫stream seq），不清零会让重播种也压不下游标，
        // 之后所有 SSE 事件都会被 isDuplicateEvent 判成重复丢弃，会话视图永久冻结。
        lastSeqAtomic.set(0)
        committedSeq.set(0)
    }

    fun stop() {
        startRequested = false
        attemptGeneration.incrementAndGet()
        job?.cancel()
        job = null
        try { call?.cancel() } catch (_: Exception) {}
        call = null
        if (connectionState == ConnectionState.CONNECTED) connectionState = ConnectionState.RETRYING
    }

    private suspend fun CoroutineScope.connectLoop() {
        var failures = 0
        while (isActive) {
            connectionState = if (failures == 0) ConnectionState.CONNECTING else ConnectionState.RETRYING
            val result = try { connectOnce() } catch (e: Exception) { ConnectResult(false, classifyFailure(e)) }
            if (!isActive) break
            if (result.ok) {
                failures = 0
            } else {
                failures++
                lastFailure = result.failure
                connectionState = if (result.failure == StreamFailure.AUTH || result.failure == StreamFailure.SERVER) ConnectionState.FAILURE else ConnectionState.RETRYING
                Log.w(TAG, "stream fail#$failures session=${sessionId.take(8)} kind=${result.failure}")
                if (!shouldRetryStream(result.failure)) {
                    startRequested = false
                    break
                }
            }
            delay(nextBackoffMillis(result.ok, failures))
        }
    }

    private suspend fun connectOnce(): ConnectResult {
        val generation = attemptGeneration.get()
        val response = try {
            val path = "/dsh-link/mobile/sessions/" + URLEncoder.encode(sessionId, "UTF-8") +
                "/stream?afterSeq=" + lastSeq + "&caps=" + STREAM_CLIENT_CAPS
            val connectMs = if (host.hasRelay) 20_000 else 8_000
            HostHttp.execute(
                host,
                HostHttp.DshRequest(
                    method = "GET",
                    path = path,
                    headers = listOf(
                        "Accept-Encoding" to "identity",
                        "Cache-Control" to "no-cache",
                        "Accept" to "text/event-stream",
                        "x-dsh-link-token" to host.token,
                    ),
                    connectTimeoutMs = connectMs,
                    readTimeoutMs = 90_000,
                ),
                onCall = { call = it },
            )
        } catch (e: Exception) { return ConnectResult(false, classifyFailure(e)) }
        return try {
            if (!currentCoroutineContext().isActive || attemptGeneration.get() != generation) {
                return ConnectResult(false, StreamFailure.NETWORK)
            }
            val code = response.code
            if (code != 200) {
                Log.w(TAG, "HTTP $code session=${sessionId.take(8)}")
                return ConnectResult(false, classifyHttpFailure(code))
            }
            connectionBytes = 0
            response.body.byteStream().use { readSSE(it) }
            ConnectResult(true, null)
        } catch (e: Exception) { ConnectResult(false, classifyFailure(e)) }
        finally {
            runCatching { response.close() }
            if (call != null) call = null
            connectionState = ConnectionState.RETRYING
            _items.trySend(Item.Disconnected)
        }
    }

    private suspend fun readSSE(input: InputStream) {
        var eventName = ""; var data = StringBuilder()
        while (currentCoroutineContext().isActive) {
            val line = BoundedIo.readLine(input) ?: break
            connectionBytes += line.length
            if (connectionBytes > BoundedIo.MAX_SSE_STREAM_BYTES) throw IOException("sse stream too large")
            when {
                line.startsWith(":") -> {}
                line.startsWith("event:") -> eventName = line.removePrefix("event:").trim()
                line.startsWith("data:") -> {
                    val payload = line.removePrefix("data:").let { if (it.startsWith(" ")) it.drop(1) else it }
                    if (data.isNotEmpty()) data.append('\n')
                    data.append(payload)
                    if (data.length > MAX_SSE_DATA_CHARS) {
                        throw IOException("sse event too large")
                    }
                }
                line.isEmpty() -> {
                    if (data.isNotEmpty()) {
                        try {
                            dispatch(eventName, data.toString())
                        } catch (_: Exception) {
                        }
                    }
                    eventName = ""
                    data = StringBuilder()
                }
            }
        }
    }

    private suspend fun dispatch(name: String, data: String) {
        when (name) {
            "ready" -> {
                val seq = JSONObject(data).optLong("resumeSeq")
                connectionState = ConnectionState.CONNECTED
                lastFailure = null
                alignToResumeSeq(seq)
                Log.i(TAG, "ready session=${sessionId.take(8)} resumeSeq=$seq seeded=$seeded lastSeq=$lastSeq")
                _items.send(Item.Ready(seq))
            }
            "message" -> {
                if (!seeded) {
                    Log.w(TAG, "drop unseeded seq message session=${sessionId.take(8)}")
                    return
                }
                val obj = JSONObject(data)
                val seq = obj.optLong("seq")
                val type = obj.optString("type")
                if (isDuplicateEvent(seq, lastSeqAtomic.get(), seeded)) {
                    Log.d(TAG, "dup seq=$seq type=$type last=$lastSeq")
                    return
                }
                Log.i(TAG, "msg seq=$seq type=$type session=${sessionId.take(8)}")
                _items.send(Item.Message(seq, type, obj.optLong("time"), obj.optJSONObject("data") ?: JSONObject()))
                lastSeqAtomic.updateAndGet { cur -> maxOf(cur, seq) }
            }
            "stats" -> _items.send(Item.Stats(JSONObject(data)))
            "resync-required" -> {
                val obj = JSONObject(data)
                _items.send(
                    Item.ResyncRequired(
                        reason = obj.optString("reason", "incomplete"),
                        afterSeq = obj.optLong("afterSeq", lastSeq),
                        oldestAvailableSeq = if (obj.has("oldestAvailableSeq") && !obj.isNull("oldestAvailableSeq")) obj.optLong("oldestAvailableSeq") else null,
                    ),
                )
            }
            "error" -> {
                val obj = JSONObject(data)
                if (obj.optString("code") == "resync-required") {
                    _items.send(
                        Item.ResyncRequired(
                            reason = obj.optString("reason", "incomplete"),
                            afterSeq = obj.optLong("afterSeq", lastSeq),
                            oldestAvailableSeq = null,
                            upgradeRequired = obj.optBoolean("upgradeRequired", true),
                        ),
                    )
                }
            }
            "question" -> {
                val obj = JSONObject(data)
                val rpcId = obj.optString("rpcId")
                if (rpcId.isNotBlank()) {
                    Log.i(TAG, "question rpc=${rpcId.take(8)} session=${sessionId.take(8)}")
                    _items.send(Item.Question(rpcId, obj))
                }
            }
            "question-resolved" -> {
                val obj = JSONObject(data)
                val rpcId = obj.optString("rpcId")
                if (rpcId.isNotBlank()) {
                    _items.send(Item.QuestionResolved(rpcId, obj.optString("outcome", "cancelled")))
                }
            }
        }
    }
}

enum class StreamFailure { AUTH, SERVER, NETWORK, UNKNOWN }
private data class ConnectResult(val ok: Boolean, val failure: StreamFailure?)

internal fun classifyHttpFailure(code: Int): StreamFailure = when {
    code == 401 || code == 403 -> StreamFailure.AUTH
    code >= 500 -> StreamFailure.SERVER
    else -> StreamFailure.UNKNOWN
}

internal fun classifyFailure(error: Throwable): StreamFailure = when (error) {
    is java.net.UnknownHostException, is java.net.ConnectException, is java.net.SocketTimeoutException, is IOException -> StreamFailure.NETWORK
    else -> StreamFailure.UNKNOWN
}

internal fun shouldRetryStream(failure: StreamFailure?): Boolean = failure != StreamFailure.AUTH

/**
 * 重连退避：基础档位 ±30% 抖动。
 *
 * 多台设备/多个会话在主机重启或网络恢复时同时重连会形成惊群，
 * 加抖动把重连时刻打散。[random] 仅用于测试注入。
 */
internal fun nextBackoffMillis(ok: Boolean, failures: Int, random: () -> Double = { Random.nextDouble() }): Long {
    val base = when {
        ok || failures <= 1 -> 1_500L
        failures <= 3 -> 3_000L
        failures <= 6 -> 6_000L
        else -> 15_000L
    }
    val factor = 0.7 + random().coerceIn(0.0, 1.0) * 0.6
    return (base * factor).roundToLong().coerceAtLeast(500L)
}

internal fun parseResyncRequired(name: String, data: String): Boolean =
    name == "resync-required" || (name == "error" && data.contains("resync-required"))

internal fun isDuplicateEvent(seq: Long, lastSeq: Long, seeded: Boolean): Boolean = !seeded || seq <= lastSeq

/**
 * history 的 maxSeq 可能与 SSE 事件 seq 不在同一编号空间（实机曾出现 maxSeq≫stream seq）。
 * 去重游标只能跟 stream / resumeSeq；此处只用于「服务端是否领先」的弱提示。
 */
internal fun historySeedSeq(maxSeq: Long?, messageSeqs: Iterable<Long>): Long {
    val fromMessages = messageSeqs.maxOrNull() ?: 0L
    if (maxSeq == null) return fromMessages
    // maxSeq 远大于页面消息 seq 时不可信，退回消息 seq
    if (fromMessages > 0 && maxSeq > fromMessages * 2 + 100) return fromMessages
    return maxOf(maxSeq, fromMessages)
}
