package dev.deeplinks.core

import org.junit.Assert.assertThrows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException

/**
 * OkHttp 引擎的路由策略单测。传输本身（TLS/DLR 隧道）由设备集成测试覆盖，
 * 这里只验证 [attemptWithFailover] 的候选切换契约——与旧
 * FailoverHttpURLConnection 语义逐条对应。
 */
class HostHttpTest {

    @Test
    fun `connect failure switches to the next candidate`() {
        val order = mutableListOf<String>()
        val result = attemptWithFailover(listOf(FailoverRoute.LAN, FailoverRoute.RELAY), hasBody = false) { route ->
            order += route.name
            if (route == FailoverRoute.LAN) throw IOException("offline")
            "relay-response"
        }
        assertEquals(listOf("LAN", "RELAY"), order)
        assertEquals("relay-response", result)
    }

    @Test
    fun `pin change fails closed and never falls back`() {
        val order = mutableListOf<String>()
        assertThrows(PinnedSsl.CertChangedException::class.java) {
            attemptWithFailover(listOf(FailoverRoute.LAN, FailoverRoute.RELAY), hasBody = false) { route ->
                order += route.name
                throw PinnedSsl.CertChangedException()
            }
        }
        assertEquals(listOf("LAN"), order)
    }

    @Test
    fun `bodied request never replays after non-connect failure`() {
        val order = mutableListOf<String>()
        assertThrows(IOException::class.java) {
            attemptWithFailover(listOf(FailoverRoute.LAN, FailoverRoute.RELAY), hasBody = true) { route ->
                order += route.name
                // 连接建立后（体可能已写出）失败：不重放到另一条路径
                if (route == FailoverRoute.LAN) throw IOException("write failed")
                "ok"
            }
        }
        assertEquals(listOf("LAN"), order)
    }

    @Test
    fun `bodied request still switches on connect-phase failure`() {
        val order = mutableListOf<String>()
        val result = attemptWithFailover(listOf(FailoverRoute.LAN, FailoverRoute.RELAY), hasBody = true) { route ->
            order += route.name
            if (route == FailoverRoute.LAN) throw ConnectException("refused")
            "ok"
        }
        assertEquals(listOf("LAN", "RELAY"), order)
        assertEquals("ok", result)
    }

    @Test
    fun `preferRelay controls candidate order`() {
        assertEquals(listOf(FailoverRoute.RELAY, FailoverRoute.LAN), failoverRouteOrder(true))
        assertEquals(listOf(FailoverRoute.LAN, FailoverRoute.RELAY), failoverRouteOrder(false))
    }

    @Test
    fun `REVOKED still failovers to a working LAN route`() {
        val order = mutableListOf<String>()
        val result = attemptWithFailover(failoverRouteOrder(preferRelay = true), hasBody = false) { route ->
            order += route.name
            if (route == FailoverRoute.RELAY) throw IOException("REVOKED revoked")
            "lan-response"
        }
        assertEquals(listOf("RELAY", "LAN"), order)
        assertEquals("lan-response", result)
    }

    @Test
    fun `all-candidate failure keeps REVOKED instead of the later transport error`() {
        val order = mutableListOf<String>()
        val thrown = assertThrows(IOException::class.java) {
            attemptWithFailover(failoverRouteOrder(preferRelay = true), hasBody = false) { route ->
                order += route.name
                if (route == FailoverRoute.RELAY) throw IOException("REVOKED revoked")
                throw IOException("connection refused")
            }
        }
        assertTrue(isRelayRouteRevoked(thrown))
        assertEquals("REVOKED revoked", thrown.message)
        assertEquals(listOf("RELAY", "LAN"), order)
    }

    @Test
    fun `connect phase classification`() {
        // 连接期：可安全切换
        assertTrue(isConnectPhaseFailure(ConnectException("refused")))
        assertTrue(isConnectPhaseFailure(SocketTimeoutException("connect timed out")))
        assertTrue(isConnectPhaseFailure(java.net.UnknownHostException("unable to resolve host")))
        // 原因链里出现 SSL 握手失败（连接期）也应判定为连接期
        assertTrue(isConnectPhaseFailure(IOException(javax.net.ssl.SSLException("handshake failed"))))
        // 读期：体已可能写出，不可重放
        assertFalse(isConnectPhaseFailure(SocketTimeoutException("Read timed out")))
        assertFalse(isConnectPhaseFailure(IOException("write failed")))
        // 类型不明的 IOException 一律视为连接建立后（体可能已写出）→ 不可重放
        assertFalse(isConnectPhaseFailure(IOException("unable to resolve host")))
        assertTrue(isConnectPhaseFailure(IOException(SocketTimeoutException("connect timed out"))))
    }

    @Test
    fun `relay revoked detection keeps structured and message paths`() {
        assertTrue(isRelayRouteRevoked(RelayRouteRevokedException("REVOKED route gone")))
        assertTrue(isRelayRouteRevoked(IOException("REVOKED route gone")))
        assertFalse(isRelayRouteRevoked(IOException("offline")))
    }
}
