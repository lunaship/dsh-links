package dev.dsh.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionStreamClientTest {

    @Test
    fun `正常断开立即 1_5s 重连`() {
        assertEquals(1_500L, nextBackoffMillis(ok = true, failures = 0))
        assertEquals(1_500L, nextBackoffMillis(ok = true, failures = 10))
    }

    @Test
    fun `首次失败也 1_5s`() {
        assertEquals(1_500L, nextBackoffMillis(ok = false, failures = 1))
    }

    @Test
    fun `连续失败逐级退避`() {
        assertEquals(3_000L, nextBackoffMillis(ok = false, failures = 2))
        assertEquals(3_000L, nextBackoffMillis(ok = false, failures = 3))
        assertEquals(6_000L, nextBackoffMillis(ok = false, failures = 4))
        assertEquals(6_000L, nextBackoffMillis(ok = false, failures = 6))
    }

    @Test
    fun `退避 15s 封顶`() {
        assertEquals(15_000L, nextBackoffMillis(ok = false, failures = 7))
        assertEquals(15_000L, nextBackoffMillis(ok = false, failures = 100))
    }

    @Test
    fun `HTTP 与网络失败分类`() {
        assertEquals(StreamFailure.AUTH, classifyHttpFailure(401))
        assertEquals(StreamFailure.AUTH, classifyHttpFailure(403))
        assertEquals(StreamFailure.SERVER, classifyHttpFailure(503))
        assertEquals(StreamFailure.UNKNOWN, classifyHttpFailure(404))
        assertEquals(StreamFailure.NETWORK, classifyFailure(java.net.SocketTimeoutException()))
    }

    @Test
    fun `seed 完成前所有事件视为重复`() {
        assertTrue(isDuplicateEvent(seq = 100L, lastSeq = 0L, seeded = false))
        assertTrue(isDuplicateEvent(seq = 1L, lastSeq = 0L, seeded = false))
    }

    @Test
    fun `seed 后按 seq 去重`() {
        // seq <= 基线：重复
        assertTrue(isDuplicateEvent(seq = 5L, lastSeq = 5L, seeded = true))
        assertTrue(isDuplicateEvent(seq = 3L, lastSeq = 5L, seeded = true))
        // seq > 基线：放行
        assertFalse(isDuplicateEvent(seq = 6L, lastSeq = 5L, seeded = true))
    }
}
