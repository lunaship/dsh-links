package dev.dsh.mobile.native
import dev.dsh.mobile.core.Host
import dev.dsh.mobile.core.PinnedSsl

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicLong

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
        object Disconnected : Item
    }

    enum class ConnectionState { CONNECTING, CONNECTED, RETRYING, FAILURE }

    private val _items = Channel<Item>(capacity = Channel.UNLIMITED)
    val items: Channel<Item> = _items
    private val lastSeqAtomic = AtomicLong(0)
    val lastSeq: Long get() = lastSeqAtomic.get()
    var connectionState by mutableStateOf(ConnectionState.RETRYING)
        private set
    /** Compatibility for polling gates and existing callers. */
    val isConnected: Boolean get() = connectionState == ConnectionState.CONNECTED
    @Volatile private var seeded = false
    val isSeeded: Boolean get() = seeded
    var lastFailure by mutableStateOf<StreamFailure?>(null)
        private set
    private var job: Job? = null

    fun noteSeedMaxSeq(seq: Long) {
        seeded = true
        lastSeqAtomic.updateAndGet { cur -> maxOf(cur, seq) }
    }

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) { connectLoop() }
    }

    /** Safely cancel the current loop and start a fresh connection attempt. */
    fun reconnect() {
        stop()
        lastFailure = null
        connectionState = ConnectionState.CONNECTING
        start()
    }

    fun stop() {
        job?.cancel()
        job = null
        if (connectionState == ConnectionState.CONNECTED) connectionState = ConnectionState.RETRYING
    }

    private suspend fun CoroutineScope.connectLoop() {
        var failures = 0
        while (isActive) {
            connectionState = if (failures == 0) ConnectionState.CONNECTING else ConnectionState.RETRYING
            val result = try { connectOnce() } catch (e: Exception) { ConnectResult(false, classifyFailure(e)) }
            if (!isActive) break
            if (result.ok) { failures = 0 } else {
                failures++
                lastFailure = result.failure
                connectionState = if (result.failure == StreamFailure.AUTH || result.failure == StreamFailure.SERVER) ConnectionState.FAILURE else ConnectionState.RETRYING
            }
            delay(nextBackoffMillis(result.ok, failures))
        }
    }

    private suspend fun connectOnce(): ConnectResult {
        val connection = try {
            val path = "/dsh-link/mobile/sessions/" + URLEncoder.encode(sessionId, "UTF-8") + "/stream?afterSeq=" + lastSeq
            (URL(host.baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"; connectTimeout = 8_000; readTimeout = 60_000; useCaches = false
                setRequestProperty("Accept", "text/event-stream")
                setRequestProperty("x-dsh-link-token", host.token)
            }.also { PinnedSsl.apply(it, host.certFingerprint) }
        } catch (e: Exception) { return ConnectResult(false, classifyFailure(e)) }
        return try {
            val code = connection.responseCode
            if (code != 200) return ConnectResult(false, classifyHttpFailure(code))
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { readSSE(it) }
            ConnectResult(true, null)
        } catch (e: Exception) { ConnectResult(false, classifyFailure(e)) }
        finally {
            connection.disconnect()
            connectionState = ConnectionState.RETRYING
            _items.trySend(Item.Disconnected)
        }
    }

    private suspend fun readSSE(reader: BufferedReader) {
        var eventName = ""; var data = ""
        while (true) {
            val line = reader.readLine() ?: break
            when {
                line.startsWith(":") -> {}
                line.startsWith("event:") -> eventName = line.removePrefix("event:").trim()
                line.startsWith("data:") -> data += line.removePrefix("data:").trim()
                line.isEmpty() -> { if (data.isNotBlank()) try { dispatch(eventName, data) } catch (_: Exception) {}; eventName = ""; data = "" }
            }
        }
    }

    private suspend fun dispatch(name: String, data: String) {
        when (name) {
            "ready" -> {
                val seq = JSONObject(data).optLong("resumeSeq")
                connectionState = ConnectionState.CONNECTED
                lastFailure = null
                _items.send(Item.Ready(seq))
            }
            "message" -> {
                if (!seeded) return
                val obj = JSONObject(data)
                val seq = obj.optLong("seq")
                if (isDuplicateEvent(seq, lastSeqAtomic.get(), seeded)) return
                _items.send(Item.Message(seq, obj.optString("type"), obj.optLong("time"), obj.optJSONObject("data") ?: JSONObject()))
                lastSeqAtomic.updateAndGet { cur -> maxOf(cur, seq) }
            }
            "stats" -> _items.send(Item.Stats(JSONObject(data)))
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

internal fun nextBackoffMillis(ok: Boolean, failures: Int): Long = when {
    ok || failures <= 1 -> 1_500L
    failures <= 3 -> 3_000L
    failures <= 6 -> 6_000L
    else -> 15_000L
}

internal fun isDuplicateEvent(seq: Long, lastSeq: Long, seeded: Boolean): Boolean = !seeded || seq <= lastSeq

/** 历史接口缺 maxSeq 时仍要 seed，否则 SSE 会一直丢消息。 */
internal fun historySeedSeq(maxSeq: Long?, messageSeqs: Iterable<Long>): Long =
    maxSeq ?: messageSeqs.maxOrNull() ?: 0L
