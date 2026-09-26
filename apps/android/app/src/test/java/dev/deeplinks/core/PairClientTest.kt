package dev.deeplinks.core

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PairClientTest {

    @Test
    fun `pair request carries idempotency key`() {
        val lan = PairClient.pairRequestBody("123456", "手机", "lan", "request-1")
        val relay = PairClient.pairRequestBody("123456", "手机", "relay", "request-1")
        assertEquals("request-1", lan.getString("requestId"))
        assertEquals(lan.getString("requestId"), relay.getString("requestId"))
        assertEquals("relay", relay.getString("via"))
    }

    @Test
    fun `pair success without pending is active`() {
        val r = PairClient.parsePairSuccess(
            "https://10.0.0.2:18640",
            """{"ok":true,"token":"tok","deviceId":"dev-1","name":"书房"}""",
            "手机",
            "ab",
        )
        assertEquals("tok", r.token)
        assertEquals("dev-1", r.deviceId)
        assertEquals("书房", r.name)
        assertEquals("ab", r.certFingerprint)
        assertFalse(r.pending)
    }

    @Test
    fun `pair success pending waits for host approval`() {
        val r = PairClient.parsePairSuccess(
            "https://10.0.0.2:18640",
            """{"ok":true,"token":"tok","deviceId":"dev-1","pending":true}""",
            "手机",
            null,
        )
        assertTrue(r.pending)
        assertEquals("手机", r.name)
        assertEquals("", r.certFingerprint)
    }

    @Test
    fun `health error classification keeps REVOKED as auth failure`() {
        val revoked = classifyHostHealthError(IOException("REVOKED revoked"))
        assertTrue(revoked is HostHealth.AuthFailed)
        assertTrue(isRelayRouteRevoked((revoked as HostHealth.AuthFailed).error))

        assertTrue(classifyHostHealthError(IOException("AGENT_OFFLINE agent offline")) is HostHealth.Unreachable)
        assertTrue(classifyHostHealthError(IOException("RATE_LIMITED daily budget")) is HostHealth.Unreachable)
        assertTrue(classifyHostHealthError(IOException("connection refused")) is HostHealth.Unreachable)
        assertTrue(classifyHostHealthError(PinnedSsl.CertChangedException()) is HostHealth.AuthFailed)
    }

    @Test
    fun `pair http errors follow app language`() {
        assertEquals(DshStringsZh.pairCodeInvalid, PairClient.friendlyPairError(401, ""))
        assertEquals(DshStringsZh.pairNameTaken, PairClient.friendlyPairError(409, "{}"))
        assertEquals(DshStringsZh.pairBadRequest, PairClient.friendlyPairError(415, """{"error":"ignored"}"""))
        assertEquals(DshStringsZh.pairTooManyAttempts, PairClient.friendlyPairError(429, ""))
        assertEquals(DshStringsZh.pairHostUnavailable.format(503), PairClient.friendlyPairError(503, ""))
        assertEquals(DshStringsZh.pairFailedHttp.format(418), PairClient.friendlyPairError(418, ""))
        assertEquals("bad pin", PairClient.friendlyPairError(401, """{"error":"bad pin"}"""))
    }
}
