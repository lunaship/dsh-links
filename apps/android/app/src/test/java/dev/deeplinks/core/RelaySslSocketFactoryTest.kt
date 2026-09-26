package dev.deeplinks.core

import org.junit.Assert.assertThrows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Relay socket 接管（OkHttp 裸连接阶段）单测。
 *
 * 修复前：OkHttp 5.x 在 TLS 分支判断前就会 asBufferedSocket 读取裸 socket 的
 * 输入流，占位 socket 直接抛 "no direct I/O on relay placeholder socket"，
 * Relay 路径（含 /dsh-link/pair 配对）每次请求必失败。修复后：裸连接的
 * connect() 真正建立外层隧道并连上本机网关，OkHttp 摸到的自始至终是可读写的
 * 真实 socket。
 *
 * 用本机 socket 对充当外层隧道（跳过外层 TLS/DLR——传输本身由设备集成测试
 * 覆盖，见方案文档第 5 节），只验证 socket 接管与网关 splice 本身。
 */
class RelaySslSocketFactoryTest {

    private fun factory(): RelaySslSocketFactory = RelaySslSocketFactory(
        // 不会真正外连：测试走预设外层连接
        relayHost = "127.0.0.1",
        relayPort = 1,
        routeId = ByteArray(8),
        routeSecret = ByteArray(8),
        innerPin = "a".repeat(64),
    )

    /** 一对本机互联的 socket：first 为客户端侧（充当外层），second 为假 Relay 一侧。 */
    private fun socketPair(): Pair<Socket, Socket> {
        val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
        val client = Socket()
        client.tcpNoDelay = true
        client.connect(InetSocketAddress(InetAddress.getByName("127.0.0.1"), server.localPort), 5_000)
        val accepted = server.accept()
        server.close()
        return client to accepted
    }

    @Test
    fun `raw socket streams carry IO after connect - no more no-direct-IO`() {
        val (outer, relaySide) = socketPair()
        val raw = factory().newRawSocketWithPresetGateway(outer)
        try {
            // OkHttp 会以 URL 地址调用 connect()；隧道模式下该参数被忽略
            raw.connect(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 1), 5_000)
            assertTrue(raw.isConnected)

            // 修复前这里是 "no direct I/O on relay placeholder socket"
            relaySide.getOutputStream().write("pong".toByteArray(Charsets.US_ASCII))
            relaySide.getOutputStream().flush()
            val down = ByteArray(4)
            readFully(raw.getInputStream(), down)
            assertEquals("pong", String(down, Charsets.US_ASCII))

            raw.getOutputStream().write("ping".toByteArray(Charsets.US_ASCII))
            raw.getOutputStream().flush()
            val up = ByteArray(4)
            readFully(relaySide.getInputStream(), up)
            assertEquals("ping", String(up, Charsets.US_ASCII))
        } finally {
            runCatching { raw.close() }
            runCatching { relaySide.close() }
        }
    }

    @Test
    fun `closing raw socket tears down tunnel end to end`() {
        val (outer, relaySide) = socketPair()
        val raw = factory().newRawSocketWithPresetGateway(outer)
        try {
            raw.connect(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 1), 5_000)
            raw.close()
            // 外层隧道对端（假 Relay 一侧）应最终看到 EOF：splice 已整体回收。
            // soTimeout 防回归时挂死测试而非失败。
            relaySide.soTimeout = 5_000
            assertEquals(-1, relaySide.getInputStream().read())
        } finally {
            runCatching { relaySide.close() }
        }
    }

    @Test
    fun `streams stay guarded before connect`() {
        val raw = factory().newRawSocket()
        assertThrows(IOException::class.java) { raw.getInputStream() }
        assertThrows(IOException::class.java) { raw.getOutputStream() }
    }

    @Test
    fun `close before connect reclaims preset tunnel`() {
        val (outer, relaySide) = socketPair()
        val raw = factory().newRawSocketWithPresetGateway(outer)
        // 连接建立前被取消：整条隧道（外层连接 + 网关）应立即回收
        raw.close()
        assertTrue(raw.isClosed)
        relaySide.soTimeout = 5_000
        assertEquals(-1, relaySide.getInputStream().read())
        relaySide.close()
    }

    @Test
    fun `dead outer tunnel fails fast instead of hanging`() {
        val (outer, relaySide) = socketPair()
        outer.close()
        relaySide.close()
        val raw = factory().newRawSocketWithPresetGateway(outer)
        // 外层已死：secret 写入被缓冲不报错，connect 仍返回；但流必须立即 EOF/报错，
        // 而不是把请求悬挂到网关 accept 超时
        raw.connect(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 1), 5_000)
        raw.soTimeout = 5_000
        // EOF 或 IOException 都算快速失败；只有读到数据/悬挂才是不对
        val first = try {
            raw.getInputStream().read()
        } catch (e: IOException) {
            -2
        }
        assertTrue("expected immediate EOF/error, got $first", first <= 0)
        runCatching { raw.close() }
    }

    private fun readFully(input: InputStream, out: ByteArray) {
        var read = 0
        while (read < out.size) {
            val n = input.read(out, read, out.size - read)
            if (n < 0) throw IOException("stream closed early")
            read += n
        }
    }
}
