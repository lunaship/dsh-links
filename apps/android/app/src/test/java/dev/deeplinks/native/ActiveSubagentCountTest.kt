package dev.deeplinks.native

import org.junit.Assert.assertEquals
import org.junit.Test

class ActiveSubagentCountTest {

    private fun session(id: String, origin: String? = null, parent: String? = null) = MobileSession(
        sessionId = id,
        title = id,
        updatedAt = 0L,
        running = false,
        blank = false,
        cwd = null,
        agentPreset = null,
        origin = origin,
        parentSessionId = parent,
    )

    @Test
    fun noCurrentSessionIsZero() {
        assertEquals(0, resolveActiveSubagentCount(listOf(session("s1")), null, 5))
    }

    @Test
    fun explicitPositiveCountWins() {
        assertEquals(3, resolveActiveSubagentCount(emptyList(), "s1", 3))
    }

    @Test
    fun zeroCountFallsBackToCountingChildSessions() {
        val sessions = listOf(
            session("child1", origin = "subagent", parent = "s1"),
            session("child2", origin = "subagent", parent = "s1"),
            session("other", origin = "subagent", parent = "s2"),
            session("main"),
        )
        assertEquals(2, resolveActiveSubagentCount(sessions, "s1", 0))
        assertEquals(2, resolveActiveSubagentCount(sessions, "s1", null))
    }
}
