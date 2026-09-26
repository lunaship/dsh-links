package dev.deeplinks.core

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketAddress
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** Relay 在 DLR CONNECT/BIND 阶段明确拒绝：该 routeId 已吊销或释放。 */
class RelayRouteRevokedException(message: String) : IOException(message)

/**
 * 已建立的外层隧道资源：到 Relay 的 TLS 连接 + 本机网关 ServerSocket。
 *
 * 正常生命周期跟随 splice：任一端断开都会由 [serveGateway]/[splice] 关闭全部
 * 资源；[close] 用于连接建立失败时的显式回收，避免网关/隧道泄漏。
 */
internal class RelayTunnel(private val outer: Socket, private val gateway: ServerSocket) {
    fun close() {
        runCatching { gateway.close() }
        runCatching { outer.close() }
    }
}

/** 起好的网关：客户端连 [port]、写入 [secret] 即接入 [tunnel] 的外层隧道。 */
internal class RelayGateway(
    val tunnel: RelayTunnel,
    val port: Int,
    val secret: ByteArray,
)

class RelaySslSocketFactory(
    private val relayHost: String,
    private val relayPort: Int,
    private val routeId: ByteArray,
    private val routeSecret: ByteArray,
    private val innerPin: String,
    private val outerPin: String? = null,
    private val connectTimeoutMs: Int = 8_000,
) : SSLSocketFactory() {
    private val innerFactory: SSLSocketFactory by lazy {
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, arrayOf(PinnedSsl.pinnedTrustManager(innerPin)), SecureRandom())
        ctx.socketFactory
    }
    private val outerFactory: SSLSocketFactory by lazy {
        if (outerPin.isNullOrBlank()) {
            SSLContext.getDefault().socketFactory
        } else {
            val ctx = SSLContext.getInstance("TLS")
            ctx.init(null, arrayOf(PinnedSsl.pinnedTrustManager(PinnedSsl.normalizeFingerprint(outerPin))), SecureRandom())
            ctx.socketFactory
        }
    }

    override fun getDefaultCipherSuites(): Array<String> = innerFactory.defaultCipherSuites
    override fun getSupportedCipherSuites(): Array<String> = innerFactory.supportedCipherSuites

    // ===== 裸连接阶段（OkHttp socketFactory）=====

    /**
     * OkHttp 裸连接入口：返回一个尚未连接的真实 socket，其 connect() 会真正
     * 建立外层隧道（DLR CONNECT + 外层 TLS + 本机网关 splice）并连上网关。
     *
     * OkHttp 5.x 在裸 TCP 连接建立后会无条件 asBufferedSocket 读取输入流，再进
     * TLS 分支——所以裸连接阶段就必须是真 socket，不允许占位实现。
     */
    internal fun newRawSocket(): Socket = RelayRawSocket()

    private inner class RelayRawSocket(private val presetGateway: RelayGateway? = null) : Socket() {
        private var tunnel: RelayTunnel? = presetGateway?.tunnel
        private var gatewayConn: Socket? = null
        // connect 进行中被 cancel（OkHttp call.cancel 从其它线程 close）时，
        // connect 尾部检测到该标志会立即回收刚建好的整条隧道，避免悬挂 15s。
        @Volatile
        private var closed = false
        // OkHttp 在 connect() 之前就会设置 socket 参数（setSoTimeout 等），先暂存
        private var pendingSoTimeout: Int = 0
        private var pendingTcpNoDelay: Boolean = true

        override fun connect(remote: SocketAddress?, timeout: Int) {
            if (closed) throw java.net.SocketException("Socket is closed")
            if (gatewayConn != null) throw java.net.SocketException("already connected")
            // 隧道模式下远端地址由 DLR 路由决定，忽略 OkHttp 传入的 URL 地址
            val gateway = presetGateway ?: openGatewayWithRetry(timeout)
            val plain = Socket()
            try {
                plain.tcpNoDelay = pendingTcpNoDelay
                plain.connect(
                    InetSocketAddress(InetAddress.getByName("127.0.0.1"), gateway.port),
                    if (timeout > 0) timeout else connectTimeoutMs,
                )
                plain.getOutputStream().write(gateway.secret)
                plain.getOutputStream().flush()
                if (pendingSoTimeout > 0) plain.soTimeout = pendingSoTimeout
                gatewayConn = plain
                tunnel = gateway.tunnel
            } catch (e: Exception) {
                runCatching { plain.close() }
                gateway.tunnel.close()
                throw e
            }
            if (closed) {
                runCatching { plain.close() }
                gateway.tunnel.close()
                throw java.net.SocketException("Socket is closed")
            }
        }

        override fun getInputStream(): InputStream =
            gatewayConn?.getInputStream() ?: throw IOException("relay tunnel not established")

        override fun getOutputStream(): OutputStream =
            gatewayConn?.getOutputStream() ?: throw IOException("relay tunnel not established")

        override fun isConnected(): Boolean = gatewayConn?.isConnected ?: false
        override fun isClosed(): Boolean = closed || (gatewayConn?.isClosed ?: false)
        override fun isBound(): Boolean = gatewayConn?.isBound ?: false
        override fun isInputShutdown(): Boolean = gatewayConn?.isInputShutdown ?: false
        override fun isOutputShutdown(): Boolean = gatewayConn?.isOutputShutdown ?: false
        override fun getInetAddress(): InetAddress? = gatewayConn?.inetAddress
        override fun getLocalAddress(): InetAddress =
            gatewayConn?.localAddress ?: InetAddress.getByName("0.0.0.0")
        override fun getPort(): Int = gatewayConn?.port ?: 0
        override fun getLocalPort(): Int = gatewayConn?.localPort ?: -1
        override fun getRemoteSocketAddress(): SocketAddress? = gatewayConn?.remoteSocketAddress
        override fun getLocalSocketAddress(): SocketAddress? = gatewayConn?.localSocketAddress

        override fun setTcpNoDelay(on: Boolean) {
            pendingTcpNoDelay = on
            gatewayConn?.tcpNoDelay = on
        }
        override fun getTcpNoDelay(): Boolean = gatewayConn?.tcpNoDelay ?: pendingTcpNoDelay
        override fun setSoTimeout(timeout: Int) {
            pendingSoTimeout = timeout
            gatewayConn?.soTimeout = timeout
        }
        override fun getSoTimeout(): Int = gatewayConn?.soTimeout ?: pendingSoTimeout
        override fun setSoLinger(on: Boolean, linger: Int) {
            gatewayConn?.setSoLinger(on, linger)
        }
        override fun getSoLinger(): Int = gatewayConn?.soLinger ?: -1
        override fun setOOBInline(on: Boolean) {
            gatewayConn?.setOOBInline(on)
        }
        override fun getOOBInline(): Boolean = gatewayConn?.getOOBInline() ?: false
        override fun setSendBufferSize(size: Int) {
            gatewayConn?.setSendBufferSize(size)
        }
        override fun getSendBufferSize(): Int = gatewayConn?.sendBufferSize ?: 0
        override fun setReceiveBufferSize(size: Int) {
            gatewayConn?.setReceiveBufferSize(size)
        }
        override fun getReceiveBufferSize(): Int = gatewayConn?.receiveBufferSize ?: 0
        override fun setKeepAlive(on: Boolean) {
            gatewayConn?.keepAlive = on
        }
        override fun getKeepAlive(): Boolean = gatewayConn?.keepAlive ?: false
        override fun setTrafficClass(tc: Int) {
            gatewayConn?.setTrafficClass(tc)
        }
        override fun getTrafficClass(): Int = gatewayConn?.trafficClass ?: 0
        override fun setReuseAddress(on: Boolean) {
            gatewayConn?.reuseAddress = on
        }
        override fun getReuseAddress(): Boolean = gatewayConn?.reuseAddress ?: false
        override fun shutdownInput() {
            gatewayConn?.shutdownInput() ?: throw IOException("relay tunnel not established")
        }
        override fun shutdownOutput() {
            gatewayConn?.shutdownOutput() ?: throw IOException("relay tunnel not established")
        }
        override fun sendUrgentData(data: Int) {
            gatewayConn?.sendUrgentData(data) ?: throw IOException("relay tunnel not established")
        }
        override fun bind(bindpoint: SocketAddress?) {
            throw IOException("relay raw socket does not support bind")
        }

        override fun close() {
            closed = true
            val conn = gatewayConn
            gatewayConn = null
            if (conn != null) {
                // splice 检测到 EOF 会连带关闭外层隧道与网关 ServerSocket
                runCatching { conn.close() }
            } else {
                tunnel?.close()
                tunnel = null
            }
        }
    }

    /** 仅供测试：跳过外层 TLS/DLR 建立，用给定外层连接构造裸连接 socket。 */
    internal fun newRawSocketWithPresetGateway(outer: Socket): Socket =
        RelayRawSocket(presetGateway = startGateway(outer))

    // ===== TLS 阶段（OkHttp sslSocketFactory）=====

    // 非 OkHttp 路径的兜底入口（OkHttp 只走 createSocket() 无参 + createSocket(s,…)）。
    // 每次调用都会 eager 建整条隧道；调用方必须 close 返回的 socket，否则隧道持续占用。
    override fun createSocket(host: String?, port: Int): Socket = openTunneled()
    override fun createSocket(host: String?, port: Int, localHost: java.net.InetAddress?, localPort: Int): Socket = openTunneled()
    override fun createSocket(host: java.net.InetAddress?, port: Int): Socket = openTunneled()
    override fun createSocket(address: java.net.InetAddress?, port: Int, localAddress: java.net.InetAddress?, localPort: Int): Socket = openTunneled()

    /**
     * OkHttp TLS 分支：[s] 已是 [RelayRawSocket] 连上网关的真实 socket，直接在
     * 其上做内层 TLS，不再另起连接。autoClose 按 SSLSocketFactory 标准语义透传。
     */
    override fun createSocket(s: Socket?, host: String?, port: Int, autoClose: Boolean): Socket {
        val plain = s ?: throw IOException("relay gateway socket missing")
        return tlsOverGateway(plain, autoClose)
    }

    /**
     * 完整流程兜底（不经 OkHttp 裸连接阶段的直接调用）：建立外层隧道 + 连网关 +
     * 内层 TLS 一次完成。
     */
    internal fun openTunneled(): Socket {
        val gateway = openGatewayWithRetry(connectTimeoutMs)
        try {
            val plain = connectGatewayLocal(gateway, connectTimeoutMs)
            return tlsOverGateway(plain, true)
        } catch (e: Exception) {
            gateway.tunnel.close()
            throw e
        }
    }

    /**
     * 外层 TLS（到 Relay）和内层 TLS（到插件）不能套在同一个 SSLSocket 上：
     * Android 叠套 SSLSocket 读大响应会提前 EOF，健康检查（十几字节）能过、
     * 会话列表（几十 KB）就会 JSON 截断。中间用本机 TCP 把两层拆开。
     */
    private fun tlsOverGateway(plain: Socket, autoClose: Boolean): Socket {
        try {
            val inner = innerFactory.createSocket(plain, "127.0.0.1", INNER_PLUGIN_PORT, autoClose) as SSLSocket
            disableHostnameVerification(inner)
            inner.startHandshake()
            inner.soTimeout = 0
            return inner
        } catch (e: Exception) {
            // 关闭底层连接，splice 检测到 EOF 即回收外层隧道与网关
            runCatching { plain.close() }
            throw e
        }
    }

    private fun connectGatewayLocal(gateway: RelayGateway, timeoutMs: Int): Socket {
        val plain = Socket()
        try {
            plain.tcpNoDelay = true
            plain.connect(InetSocketAddress(InetAddress.getByName("127.0.0.1"), gateway.port), timeoutMs)
            plain.getOutputStream().write(gateway.secret)
            plain.getOutputStream().flush()
            return plain
        } catch (e: Exception) {
            runCatching { plain.close() }
            throw e
        }
    }

    // ===== 外层隧道建立 =====

    /**
     * 针对 "rate limited" 的有限重试（与旧实现一致）。
     *
     * [timeoutMs] 为 OkHttp 传入的 connectTimeout：把它放大为整条隧道建立的总预算
     * （2 倍 + 3s 慢链路余量），预算耗尽即放弃重试——否则最坏要等
     * 4 次(8s TCP + 10s TLS/DLR) + 重试退避 ≈ 80s，OkHttp 的 connectTimeout 形同虚设。
     * 单次尝试内部仍有各自的 8s TCP 与 10s TLS/DLR 上限。
     */
    private fun openGatewayWithRetry(timeoutMs: Int): RelayGateway {
        val budgetMs = (if (timeoutMs > 0) timeoutMs else connectTimeoutMs) * 2L + 3_000L
        val deadline = System.currentTimeMillis() + budgetMs
        var last: Exception? = null
        repeat(4) { attempt ->
            try {
                return openGatewayOnce()
            } catch (e: Exception) {
                last = e
                val msg = e.message.orEmpty()
                if (!msg.contains("rate limited") || attempt == 3) throw e
                val sleep = 400L shl attempt
                if (System.currentTimeMillis() + sleep >= deadline) throw e
                Thread.sleep(sleep)
            }
        }
        throw last ?: IOException("relay connect failed")
    }

    private fun openGatewayOnce(): RelayGateway {
        val tcp = Socket()
        tcp.tcpNoDelay = true
        tcp.connect(InetSocketAddress(relayHost, relayPort), connectTimeoutMs)
        var outer: SSLSocket? = null
        try {
            outer = outerFactory.createSocket(tcp, relayHost, relayPort, true) as SSLSocket
            if (outerPin.isNullOrBlank()) {
                // The system CA path still needs endpoint authentication. The inner pin
                // authenticates the plugin, but cannot authenticate the Relay itself.
                enableHostnameVerification(outer)
            } else {
                // An explicit certificate pin is the outer identity check.
                disableHostnameVerification(outer)
            }
            sniIfDns(outer, relayHost)
            outer.soTimeout = 10_000
            outer.startHandshake()
            connectDlr(outer)
            outer.soTimeout = 0
            return startGateway(outer)
        } catch (e: Exception) {
            runCatching { outer?.close() } // autoClose=true 连带关闭 tcp
            if (outer == null) runCatching { tcp.close() }
            throw e
        }
    }

    /**
     * 用已建立的外层连接起本机网关：生成一次性凭据、监听回环端口、启动 splice。
     *
     * 本机回环 TCP 是所有 App 共享的命名空间，不能接受"第一个连上来的人"：
     * 每次隧道生成 32 字节一次性凭据，客户端在 TLS ClientHello 之前先写入，
     * 服务端校验通过才 splice。抢连者拿不到凭据，只会被关闭。
     */
    private fun startGateway(outer: Socket): RelayGateway {
        val secret = ByteArray(GATEWAY_SECRET_BYTES).also { SecureRandom().nextBytes(it) }
        val gateway = ServerSocket(0, GATEWAY_BACKLOG, InetAddress.getByName("127.0.0.1"))
        gateway.soTimeout = GATEWAY_ACCEPT_TIMEOUT_MS
        Thread({ serveGateway(gateway, outer, secret) }, "dsh-relay-splice").apply {
            isDaemon = true
            start()
        }
        return RelayGateway(RelayTunnel(outer, gateway), gateway.localPort, secret)
    }

    /**
     * 接受本进程客户端并校验首包一次性凭据，通过后才把外层 TLS 与本机 socket 对接。
     *
     * accept 是循环的：其它应用即使抢先 connect，也会因凭据不符被立即关闭，
     * 不影响随后到达的真实客户端；窗口期内始终无人合法接入时关闭外层连接，
     * 避免隧道悬挂。
     */
    private fun serveGateway(gateway: ServerSocket, outer: Socket, secret: ByteArray) {
        val deadline = System.currentTimeMillis() + GATEWAY_ACCEPT_TIMEOUT_MS
        try {
            while (System.currentTimeMillis() < deadline) {
                val peer = try {
                    gateway.accept()
                } catch (_: Exception) {
                    return
                }
                try {
                    peer.tcpNoDelay = true
                    peer.soTimeout = GATEWAY_READ_TIMEOUT_MS
                    val got = ByteArray(secret.size)
                    readFully(peer.getInputStream(), got)
                    if (!MessageDigest.isEqual(secret, got)) {
                        runCatching { peer.close() }
                        continue
                    }
                    peer.soTimeout = 0
                    // 合法客户端已接入：先关网关监听缩小暴露面，再 splice
                    runCatching { gateway.close() }
                    splice(outer, peer)
                    return
                } catch (_: Exception) {
                    runCatching { peer.close() }
                }
            }
        } finally {
            runCatching { gateway.close() }
            runCatching { outer.close() }
        }
    }

    private fun readFully(input: InputStream, out: ByteArray) {
        var read = 0
        while (read < out.size) {
            val n = input.read(out, read, out.size - read)
            if (n < 0) throw IOException("gateway closed before credential")
            read += n
        }
    }

    private fun disableHostnameVerification(socket: SSLSocket) {
        val params = socket.sslParameters
        params.endpointIdentificationAlgorithm = ""
        socket.sslParameters = params
    }

    private fun enableHostnameVerification(socket: SSLSocket) {
        val params = socket.sslParameters
        params.endpointIdentificationAlgorithm = "HTTPS"
        socket.sslParameters = params
    }

    private fun sniIfDns(socket: SSLSocket, host: String) {
        if (host.isEmpty() || host.contains(':') || host.all { it.isDigit() || it == '.' }) return
        val params = socket.sslParameters
        params.serverNames = listOf(SNIHostName(host))
        socket.sslParameters = params
    }

    private fun connectDlr(socket: SSLSocket) {
        val input = socket.inputStream
        val output = socket.outputStream
        val hello = JSONObject(readLine(input, 768))
        if (hello.optString("type") != "HELLO") throw IOException("expected HELLO")
        val challenge = DlrCrypto.decode(hello.getString("challenge"))
        val nonce = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val ts = System.currentTimeMillis() / 1000
        val mac = DlrCrypto.connectMac(routeSecret, routeId, ts, nonce, challenge)
        val frame = JSONObject()
            .put("type", "CONNECT")
            .put("route", DlrCrypto.encode(routeId))
            .put("ts", ts)
            .put("nonce", DlrCrypto.encode(nonce))
            .put("mac", DlrCrypto.encode(mac))
        output.write((frame.toString() + "\n").toByteArray(Charsets.UTF_8))
        output.flush()
        val ready = JSONObject(readLine(input, 768))
        if (ready.optString("type") == "ERROR") {
            val code = ready.optString("code")
            val message = ready.optString("message", code.ifBlank { "relay error" })
            val text = if (code.isNotBlank()) "$code $message" else message
            // 结构化区分"路由已吊销"，调用方不必再靠错误文案猜
            if (code.equals("REVOKED", ignoreCase = true)) throw RelayRouteRevokedException(text)
            throw IOException(text)
        }
        if (ready.optString("type") != "READY") throw IOException("expected READY")
    }

    private fun readLine(input: InputStream, max: Int): String {
        val out = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) throw IOException("relay closed")
            if (b == '\n'.code) break
            if (out.size() >= max) throw IOException("relay frame too large")
            if (b != '\r'.code) out.write(b)
        }
        return out.toString(Charsets.UTF_8.name())
    }

    /** 内层 TLS 的目标端口（本机插件）。 */
    private companion object {
        const val INNER_PLUGIN_PORT = 18640
    }
}

private fun splice(a: Socket, b: Socket) {
    val closed = AtomicBoolean(false)
    val closeBoth = {
        if (closed.compareAndSet(false, true)) {
            runCatching { a.close() }
            runCatching { b.close() }
        }
    }
    val up = Thread({
        try {
            copyFlush(a.getInputStream(), b.getOutputStream())
        } catch (_: Exception) {
        } finally {
            closeBoth()
        }
    }, "dsh-relay-up")
    up.isDaemon = true
    up.start()
    try {
        copyFlush(b.getInputStream(), a.getOutputStream())
    } catch (_: Exception) {
    } finally {
        closeBoth()
    }
    runCatching { up.join(1_000) }
}

private fun copyFlush(from: InputStream, to: OutputStream) {
    val buf = ByteArray(16 * 1024)
    while (true) {
        val n = from.read(buf)
        if (n < 0) break
        to.write(buf, 0, n)
        to.flush()
    }
}

/** 本地网关一次性凭据长度（字节）。 */
private const val GATEWAY_SECRET_BYTES = 32

/** 等待合法客户端接入的最长时间；超时关闭隧道，避免悬挂。 */
private const val GATEWAY_ACCEPT_TIMEOUT_MS = 15_000

/** 单个 accept 到的连接读凭据的超时；收紧以避免抢连者挤占 15s accept 窗口。 */
private const val GATEWAY_READ_TIMEOUT_MS = 1_000

/** backlog 略大于 1，避免合法客户端排在恶意连接之后被拒。 */
private const val GATEWAY_BACKLOG = 4
