package dev.deeplinks.core

import okhttp3.Call
import okhttp3.ConnectionPool
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.PortUnreachableException
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.X509TrustManager

internal enum class FailoverRoute { LAN, RELAY }

/** Relay CONNECT/BIND after a verified MAC when the route is gone or revoked. */
internal fun isRelayRouteRevoked(error: Throwable): Boolean {
    var cur: Throwable? = error
    while (cur != null) {
        // 结构化类型是首选判据；文案匹配仅用于兼容尚未升级的旧 Relay/调用方
        if (cur is RelayRouteRevokedException) return true
        if (cur.message.orEmpty().contains("REVOKED")) return true
        cur = cur.cause
    }
    return false
}

internal fun failoverRouteOrder(preferRelay: Boolean): List<FailoverRoute> =
    if (preferRelay) listOf(FailoverRoute.RELAY, FailoverRoute.LAN)
    else listOf(FailoverRoute.LAN, FailoverRoute.RELAY)

/**
 * 该异常是否发生在「连接尚未建立」阶段。
 *
 * 与旧 FailoverHttpURLConnection 的不变量一致：只有连接尚未建立时切换候选路由
 * 才是安全的——请求体一旦写出，重放到另一条路径可能造成非幂等请求重复执行。
 * OkHttp 把连接与请求写合并在 execute() 里，这里按异常类型区分：
 * - Connect/UnknownHost/NoRoute/PortUnreachable/SSL 握手类：连接期，可切换；
 * - SocketTimeoutException：只有「connect timed out」视为连接期（读超时不可重放）。
 */
internal fun isConnectPhaseFailure(error: Throwable): Boolean {
    var cur: Throwable? = error
    while (cur != null) {
        when (cur) {
            is ConnectException,
            is UnknownHostException,
            is NoRouteToHostException,
            is PortUnreachableException,
            is SSLException,
            -> return true
            is SocketTimeoutException -> {
                if (cur.message.orEmpty().contains("connect", ignoreCase = true)) return true
            }
        }
        cur = cur.cause
    }
    return false
}

/**
 * LAN/公网身份校验：有指纹则要求合法格式（64 位十六进制）；
 * 无指纹仅允许公网主机走系统 PKI（私网/回环 fail-closed）。
 */
internal fun validateLanIdentity(baseUrl: String, normalizedPin: String) {
    if (normalizedPin.isEmpty()) {
        // 私网/回环主机必须钉死证书；空指纹不得静默回退到系统 PKI（fail-closed）
        if (PinnedSsl.shouldPin(baseUrl)) throw PinnedSsl.CertChangedException()
        return
    }
    PinnedSsl.requireValidPin(normalizedPin)
}

/**
 * AUTO 路由候选切换策略（原 FailoverHttpURLConnection 语义）：
 *
 * - 按候选顺序尝试，任一候选返回结果即终止，绝不因响应期错误切换路径；
 * - 证书变更（CertChangedException）是认证失败而非传输故障，立即抛出；
 * - REVOKED 表示该云端路由已死：记住它并继续尝试 LAN；全部失败时优先抛
 *   REVOKED，让设备列表能识别吊销而不是当作离线；
 * - 带请求体的调用：一旦异常发生在连接建立之后（体已可能被写出），直接抛出，
 *   不重放到另一条路径。
 */
internal fun <R> attemptWithFailover(
    routes: List<FailoverRoute>,
    hasBody: Boolean,
    attempt: (FailoverRoute) -> R,
): R {
    var last: IOException? = null
    var revoked: IOException? = null
    for (route in routes) {
        try {
            return attempt(route)
        } catch (e: IOException) {
            // A certificate change is an authentication failure, not an AUTO
            // transport failure. Never hide it behind the other route.
            if (PinnedSsl.unwrap(e) is PinnedSsl.CertChangedException) throw e
            if (isRelayRouteRevoked(e)) revoked = e
            last = e
            if (hasBody && !isConnectPhaseFailure(e)) throw e
        }
    }
    throw revoked ?: last ?: IOException("relay and LAN both failed")
}

/**
 * OkHttp 传输引擎（原 HttpURLConnection 网络栈的统一替换）。
 *
 * - LAN/公网：按证书指纹钉死的 SSLSocketFactory；无指纹仅允许公网主机走系统 CA。
 * - Relay：[RelaySslSocketFactory] 完成 DLR CONNECT + 内层 TLS，OkHttp 在隧道上
 *   跑 HTTP/1.1；连接不复用（对齐旧 Connection: close 语义）。
 * - 路由 failover 语义见 [attemptWithFailover]。
 */
object HostHttp {

    class DshRequest(
        val method: String,
        val path: String,
        val body: ByteArray? = null,
        val headers: List<Pair<String, String>> = emptyList(),
        val connectTimeoutMs: Int = 8_000,
        val readTimeoutMs: Int = 12_000,
    )

    /**
     * 执行请求并返回 [Response]。调用方负责 close。
     * 非响应码 2xx 不是异常（不触发候选切换）；只有连接/写体期 IOException 才会。
     */
    internal fun execute(
        host: Host,
        request: DshRequest,
        forceRoute: FailoverRoute? = null,
        onCall: ((Call) -> Unit)? = null,
    ): Response {
        val url = host.baseUrl.trimEnd('/') + request.path
        // 明文 HTTP 不允许：局域网自签证书场景必须走钉死指纹的 HTTPS
        require(url.startsWith("https://")) { "拒绝明文 HTTP，仅支持 HTTPS" }
        val routes = when {
            forceRoute != null -> listOf(forceRoute)
            host.hasRelay -> failoverRouteOrder(host.preferRelay)
            else -> listOf(FailoverRoute.LAN)
        }
        return attemptWithFailover(routes, request.body != null) { route ->
            val client = clientFor(host, route, request.connectTimeoutMs, request.readTimeoutMs)
            val builder = Request.Builder().url(url)
            request.headers.forEach { (k, v) -> builder.header(k, v) }
            when {
                request.body != null -> builder.method(
                    request.method,
                    request.body.toRequestBody("application/json; charset=utf-8".toMediaType()),
                )
                request.method == "GET" -> builder.get()
                else -> builder.method(request.method, null)
            }
            val call = client.newCall(builder.build())
            onCall?.invoke(call)
            call.execute()
        }
    }

    // ===== 客户端构建 =====

    /** LAN/公网客户端缓存：同主机（URL+指纹）复用连接池。 */
    private val lanClients = ConcurrentHashMap<String, OkHttpClient>()

    private fun clientFor(
        host: Host,
        route: FailoverRoute,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
    ): OkHttpClient {
        if (route == FailoverRoute.RELAY) return relayClient(host, connectTimeoutMs, readTimeoutMs)
        val base = lanClients.computeIfAbsent(lanKey(host)) { lanClient(host) }
        return base.newBuilder()
            .connectTimeout(connectTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(readTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .build()
    }

    private fun lanKey(host: Host): String =
        PinnedSsl.normalizeUrl(host.baseUrl).trimEnd('/').lowercase() +
            "\u001f" + PinnedSsl.normalizeFingerprint(host.certFingerprint)

    private fun lanClient(host: Host): OkHttpClient {
        val pin = PinnedSsl.normalizeFingerprint(host.certFingerprint)
        validateLanIdentity(host.baseUrl, pin)
        val builder = OkHttpClient.Builder()
            .connectionPool(ConnectionPool(5, 30_000, TimeUnit.MILLISECONDS))
            .retryOnConnectionFailure(true)
        if (pin.isEmpty()) return builder.build()
        val trustManager: X509TrustManager = PinnedSsl.pinnedTrustManager(pin)
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, arrayOf(trustManager), SecureRandom())
        return builder
            .sslSocketFactory(ctx.socketFactory, trustManager)
            // 身份 = 叶证书指纹钉死，跳过主机名校验（与旧 PinnedSsl.apply 一致）
            .hostnameVerifier(HostnameVerifier { _, _ -> true })
            .build()
    }

    /** Relay 客户端缓存：同主机（URL+指纹+Relay 路由参数）复用底层引擎，超时按请求覆盖。 */
    private val relayClients = ConcurrentHashMap<String, OkHttpClient>()

    private fun relayKey(host: Host): String =
        lanKey(host) +
            "\u001f" + host.relayClient +
            "\u001f" + host.relayRouteId +
            "\u001f" + host.relayRouteSecret +
            "\u001f" + host.relayTlsFingerprint

    private fun relayClient(host: Host, connectTimeoutMs: Int, readTimeoutMs: Int): OkHttpClient {
        val base = relayClients.computeIfAbsent(relayKey(host)) { buildRelayClient(host) }
        return base.newBuilder()
            .connectTimeout(connectTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(readTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .build()
    }

    private fun buildRelayClient(host: Host): OkHttpClient {
        try {
            val (relayHost, relayPort) = DlrCrypto.parseHostPort(host.relayClient, 8443)
            val innerPin = PinnedSsl.normalizeFingerprint(host.certFingerprint)
            if (innerPin.isBlank()) throw IOException("missing inner TLS pin")
            PinnedSsl.requireValidPin(innerPin)
            validateLanIdentity(host.baseUrl, innerPin)
            val factory = RelaySslSocketFactory(
                relayHost = relayHost,
                relayPort = relayPort,
                routeId = DlrCrypto.decode(host.relayRouteId),
                routeSecret = DlrCrypto.decode(host.relayRouteSecret),
                innerPin = innerPin,
                outerPin = host.relayTlsFingerprint.takeIf { it.isNotBlank() },
            )
            val trustManager = PinnedSsl.pinnedTrustManager(innerPin)
            // OkHttp 5.x 在裸 TCP 连接建立后、TLS 分支判断前就会把裸 socket 包成
            // Okio 的 BufferedSocket（读 getInputStream），再调
            // sslSocketFactory.createSocket(raw, …) 建 TLS——裸连接阶段必须是真实
            // 可读写的 socket。所以裸连接的 connect() 由 RelayRawSocket 接管：真正
            // 建立隧道（DLR CONNECT + 外层 TLS + 本机网关 splice）并连上网关，
            // 之后内层 TLS 直接在这条网关连接上进行。
            val plainFactory = object : javax.net.SocketFactory() {
                override fun createSocket(): Socket = factory.newRawSocket()
                override fun createSocket(host: String?, port: Int): Socket = factory.newRawSocket()
                override fun createSocket(
                    host: String?,
                    port: Int,
                    localHost: java.net.InetAddress?,
                    localPort: Int,
                ): Socket = factory.newRawSocket()
                override fun createSocket(address: java.net.InetAddress?, port: Int): Socket = factory.newRawSocket()
                override fun createSocket(
                    address: java.net.InetAddress?,
                    port: Int,
                    localAddress: java.net.InetAddress?,
                    localPort: Int,
                ): Socket = factory.newRawSocket()
            }
            return OkHttpClient.Builder()
                .socketFactory(plainFactory)
                .sslSocketFactory(factory, trustManager)
                // 内层身份 = 插件叶证书指纹；外层身份由 factory 的 outerPin/系统 CA 校验
                .hostnameVerifier(HostnameVerifier { _, _ -> true })
                // DLR 隧道不复用：每次调用独占连接，用完即弃（对齐旧 Connection: close）。
                // maxIdleConnections=0 已经保证不留存空闲连接；keepAliveDuration 必须
                // 是正数（OkHttp ConnectionPool 构造函数校验 > 0），取值本身不影响行为。
                .connectionPool(ConnectionPool(0, 1, TimeUnit.MILLISECONDS))
                .retryOnConnectionFailure(false)
                .build()
        } catch (e: IllegalArgumentException) {
            throw IOException(e.message ?: "invalid relay host", e)
        }
    }
}

