package dev.deeplinks.native

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionStreamClientTest {

    /** 抖动中位（random=0.5 → 系数 1.0），用于断言基础档位。 */
    private fun base(ok: Boolean, failures: Int) = nextBackoffMillis(ok, failures, random = { 0.5 })

    @Test
    fun `正常断开立即 1_5s 重连`() {
        assertEquals(1_500L, base(ok = true, failures = 0))
        assertEquals(1_500L, base(ok = true, failures = 10))
    }

    @Test
    fun `首次失败也 1_5s`() {
        assertEquals(1_500L, base(ok = false, failures = 1))
    }

    @Test
    fun `连续失败逐级退避`() {
        assertEquals(3_000L, base(ok = false, failures = 2))
        assertEquals(3_000L, base(ok = false, failures = 3))
        assertEquals(6_000L, base(ok = false, failures = 4))
        assertEquals(6_000L, base(ok = false, failures = 6))
    }

    @Test
    fun `退避 15s 封顶`() {
        assertEquals(15_000L, base(ok = false, failures = 7))
        assertEquals(15_000L, base(ok = false, failures = 100))
    }

    @Test
    fun `退避带 ±30% 抖动并夹紧异常输入`() {
        // 下界 0.7x / 上界 1.3x
        assertEquals(1_050L, nextBackoffMillis(ok = true, failures = 0, random = { 0.0 }))
        assertEquals(1_950L, nextBackoffMillis(ok = true, failures = 0, random = { 1.0 }))
        assertEquals(10_500L, nextBackoffMillis(ok = false, failures = 7, random = { 0.0 }))
        assertEquals(19_500L, nextBackoffMillis(ok = false, failures = 7, random = { 1.0 }))
        // 越界 random 被 coerce 到 [0,1]
        assertEquals(1_050L, nextBackoffMillis(ok = true, failures = 0, random = { -5.0 }))
        assertEquals(1_950L, nextBackoffMillis(ok = true, failures = 0, random = { 5.0 }))
        // 抖动后仍不低于 500ms 下限
        assertTrue(nextBackoffMillis(ok = true, failures = 0, random = { 0.0 }) >= 500L)
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
    fun `401 与 403 停止自动重试`() {
        assertEquals(false, shouldRetryStream(StreamFailure.AUTH))
        assertEquals(true, shouldRetryStream(StreamFailure.SERVER))
        assertEquals(true, shouldRetryStream(StreamFailure.NETWORK))
        assertEquals(true, shouldRetryStream(null))
    }

    @Test
    fun `seed 完成前所有事件视为重复`() {
        assertTrue(isDuplicateEvent(seq = 100L, lastSeq = 0L, seeded = false))
        assertTrue(isDuplicateEvent(seq = 1L, lastSeq = 0L, seeded = false))
    }

    @Test
    fun `缺 maxSeq 时用消息 seq 否则 0`() {
        assertEquals(42L, historySeedSeq(42L, listOf(1L, 9L)))
        assertEquals(9L, historySeedSeq(null, listOf(1L, 9L)))
        assertEquals(0L, historySeedSeq(null, emptyList()))
    }

    @Test
    fun `maxSeq 远大于消息 seq 时不可信 退回消息 seq`() {
        // 实机：history maxSeq=1481，页面消息 seq≈480 → 若盲信 maxSeq 会丢光 SSE
        assertEquals(480L, historySeedSeq(1481L, listOf(100L, 480L)))
        assertEquals(1481L, historySeedSeq(1481L, listOf(1400L, 1480L)))
    }

    @Test
    fun `seed 后按 seq 去重`() {
        // seq <= 基线：重复
        assertTrue(isDuplicateEvent(seq = 5L, lastSeq = 5L, seeded = true))
        assertTrue(isDuplicateEvent(seq = 3L, lastSeq = 5L, seeded = true))
        // seq > 基线：放行
        assertFalse(isDuplicateEvent(seq = 6L, lastSeq = 5L, seeded = true))
    }

    @Test
    fun `识别重同步事件名称`() {
        assertTrue(parseResyncRequired("resync-required", """{"reason":"hit-limit"}"""))
        assertTrue(parseResyncRequired("error", """{"code":"resync-required","upgradeRequired":true}"""))
        assertFalse(parseResyncRequired("message", """{"seq":1}"""))
    }
}
