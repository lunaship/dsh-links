package dev.dsh.mobile.util

import dev.dsh.mobile.MobileSession
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 会话分类（WI-005 / WI-006 抽出）单元测试。
 *
 * 验证：
 * - running → RUNNING；
 * - 非 running 且非 blank → STOPPED；
 * - blank → ALL（空白不算"已停止"语义）。
 */
class SessionClassificationTest {

    private fun session(
        running: Boolean,
        blank: Boolean,
        id: String = "sid",
    ): MobileSession = MobileSession(
        sessionId = id,
        title = "title-$id",
        updatedAt = 0L,
        running = running,
        blank = blank,
        cwd = "/tmp",
        agentPreset = null,
        origin = null,
    )

    @Test
    fun `running 会话归 RUNNING`() {
        assertEquals(SessionFilter.RUNNING, classifySession(session(running = true, blank = false)))
        assertEquals(SessionFilter.RUNNING, classifySession(session(running = true, blank = true)))
    }

    @Test
    fun `非 running 非 blank 归 STOPPED`() {
        assertEquals(SessionFilter.STOPPED, classifySession(session(running = false, blank = false)))
    }

    @Test
    fun `blank 会话归 ALL（不算已停止）`() {
        assertEquals(SessionFilter.ALL, classifySession(session(running = false, blank = true)))
    }

    @Test
    fun `filterSessions ALL 返回全部`() {
        val list = listOf(
            session(true, false, "a"),
            session(false, false, "b"),
            session(false, true, "c"),
        )
        val out = filterSessions(list, SessionFilter.ALL)
        assertEquals(listOf("a", "b", "c"), out.map { it.sessionId })
    }

    @Test
    fun `filterSessions RUNNING 仅保留 running`() {
        val list = listOf(
            session(true, false, "a"),
            session(false, false, "b"),
            session(false, true, "c"),
        )
        val out = filterSessions(list, SessionFilter.RUNNING)
        assertEquals(listOf("a"), out.map { it.sessionId })
    }

    @Test
    fun `filterSessions STOPPED 仅保留已完成非 blank`() {
        val list = listOf(
            session(true, false, "a"),
            session(false, false, "b"),
            session(false, false, "d"),
            session(false, true, "c"),
        )
        val out = filterSessions(list, SessionFilter.STOPPED)
        assertEquals(setOf("b", "d"), out.map { it.sessionId }.toSet())
    }

    @Test
    fun `countSessionsByFilter 按 chip 统计`() {
        val list = listOf(
            session(true, false, "a1"),
            session(true, false, "a2"),
            session(false, false, "b1"),
            session(false, true, "c1"),
            session(false, true, "c2"),
        )
        val counts = countSessionsByFilter(list)
        assertEquals(5, counts[SessionFilter.ALL])
        assertEquals(2, counts[SessionFilter.RUNNING])
        assertEquals(1, counts[SessionFilter.STOPPED])
    }
}