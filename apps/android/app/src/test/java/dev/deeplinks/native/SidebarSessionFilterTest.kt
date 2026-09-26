package dev.deeplinks.native

import org.junit.Assert.assertEquals
import org.junit.Test

/** 侧边栏会话过滤规则测试（原先内联在 WorkspaceScreen，无法单测）。 */
class SidebarSessionFilterTest {

    private val now = 1_700_000_000_000L

    private fun session(
        id: String,
        title: String = "t",
        cwd: String? = null,
        origin: String? = null,
        blank: Boolean = false,
        updatedAt: Long = now,
    ) = MobileSession(
        sessionId = id,
        title = title,
        updatedAt = updatedAt,
        running = false,
        blank = blank,
        cwd = cwd,
        agentPreset = null,
        origin = origin,
    )

    private fun filter(
        sessions: List<MobileSession>,
        archived: Set<String> = emptySet(),
        deleted: Set<String> = emptySet(),
        needle: String = "",
        serverIds: List<String> = emptyList(),
    ) = filterSidebarSessions(sessions, archived, deleted, needle, serverIds, now).map { it.sessionId }

    @Test
    fun excludesArchivedDeletedAndSubagents() {
        val sessions = listOf(
            session("keep"),
            session("archived"),
            session("deleted"),
            session("sub", origin = "subagent"),
        )
        assertEquals(
            listOf("keep"),
            filter(sessions, archived = setOf("archived"), deleted = setOf("deleted")),
        )
    }

    @Test
    fun staleBlankSessionsHiddenButFreshBlankKept() {
        val sessions = listOf(
            session("stale", blank = true, updatedAt = now - 25 * 3600_000L),
            session("fresh", blank = true, updatedAt = now - 3600_000L),
        )
        assertEquals(listOf("fresh"), filter(sessions))
    }

    @Test
    fun noQueryReturnsEveryCandidate() {
        assertEquals(listOf("a", "b"), filter(listOf(session("a"), session("b"))))
    }

    @Test
    fun queryMatchesTitleCaseInsensitively() {
        val sessions = listOf(session("a", title = "T3Code 调研"), session("b", title = "其他"))
        assertEquals(listOf("a"), filter(sessions, needle = "t3code"))
    }

    @Test
    fun queryMatchesWorkspacePath() {
        val sessions = listOf(session("a", cwd = "/Dev/dsh-links-app"), session("b", cwd = "/tmp"))
        assertEquals(listOf("a"), filter(sessions, needle = "dsh-links"))
    }

    @Test
    fun serverSearchIdsAreIncludedEvenWithoutLocalMatch() {
        val sessions = listOf(session("a", title = "x"), session("b", title = "y"))
        assertEquals(listOf("b"), filter(sessions, needle = "needle", serverIds = listOf("b")))
    }
}
