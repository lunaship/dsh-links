package dev.deeplinks.native

import dev.deeplinks.native.util.SessionFilter
import org.junit.Assert.assertEquals
import org.junit.Test

class WorkspaceSessionDerivationsTest {
    @Test
    fun reconcileArchivedSessionIds_followsWebRestoreAndKeepsLocalRestoreOverride() {
        assertEquals(
            setOf("still-archived"),
            reconcileArchivedSessionIds(
                serverArchivedIds = setOf("still-archived", "restored-on-web"),
                restoredSessionIds = setOf("restored-on-web"),
            ),
        )
        assertEquals(
            emptySet<String>(),
            reconcileArchivedSessionIds(
                serverArchivedIds = emptySet(),
                restoredSessionIds = setOf("restored-on-web"),
            ),
        )
    }

    @Test
    fun upsertCreatedWorkspace_prependsNewCommittedWorkspace() {
        val existing = MobileWorkspace("old", "/a/old")
        val created = MobileWorkspace("new", "/a/new/")

        assertEquals(
            listOf(MobileWorkspace("new", "/a/new"), existing),
            upsertCreatedWorkspace(listOf(existing), created),
        )
    }

    @Test
    fun upsertCreatedWorkspace_replacesSameCanonicalPathWithoutDuplicate() {
        val stale = MobileWorkspace("", "/a/project", sessionIds = emptyList())
        val committed = MobileWorkspace("server-id", "/a/project/", sessionIds = listOf("s1"))

        assertEquals(
            listOf(MobileWorkspace("server-id", "/a/project", sessionIds = listOf("s1"))),
            upsertCreatedWorkspace(listOf(stale), committed),
        )
    }

    @Test
    fun workspaceCreationAnchor_prefersCurrentRegisteredPath() {
        val first = MobileWorkspace("one", "/a/one")
        val selected = MobileWorkspace("two", "/a/two")

        assertEquals(
            selected,
            workspaceCreationAnchor(listOf(first, selected), "/a/two/"),
        )
    }

    @Test
    fun workspaceCreationAnchor_fallsBackToFirstServerBackedWorkspace() {
        val localOnly = MobileWorkspace("", "/a/local")
        val serverBacked = MobileWorkspace("server", "/a/server")

        assertEquals(
            serverBacked,
            workspaceCreationAnchor(listOf(localOnly, serverBacked), "/a/gone"),
        )
    }

    @Test
    fun reconciledSessionId_keepsASelectionThatStillExists() {
        val result = reconciledSessionId(
            currentSessionId = "current",
            sessions = listOf(session("running", running = true), session("current")),
            preserveEmptySelection = false,
            selectLatest = true,
        )

        assertEquals("current", result)
    }

    @Test
    fun reconciledSessionId_movesToRunningSessionWhenWebRemovedCurrent() {
        val result = reconciledSessionId(
            currentSessionId = "removed",
            sessions = listOf(session("first"), session("running", running = true)),
            preserveEmptySelection = false,
            selectLatest = false,
        )

        assertEquals("running", result)
    }

    @Test
    fun reconciledSessionId_movesAwayWhenWebArchivedCurrentButSessionListStillContainsIt() {
        val result = reconciledSessionId(
            currentSessionId = "archived",
            sessions = listOf(session("archived"), session("visible")),
            hiddenSessionIds = setOf("archived"),
            preserveEmptySelection = false,
            selectLatest = false,
        )

        assertEquals("visible", result)
    }

    @Test
    fun reconciledSessionId_usesLatestWhenRequestedAndCurrentWasRemoved() {
        val result = reconciledSessionId(
            currentSessionId = "removed",
            sessions = listOf(session("older", updatedAt = 10L), session("latest", updatedAt = 20L)),
            preserveEmptySelection = false,
            selectLatest = true,
        )

        assertEquals("latest", result)
    }

    @Test
    fun reconciledSessionId_doesNotSelectWebArchivedLatestSessionOnColdStart() {
        val result = reconciledSessionId(
            currentSessionId = null,
            sessions = listOf(session("visible", updatedAt = 10L), session("archived", updatedAt = 20L)),
            hiddenSessionIds = setOf("archived"),
            preserveEmptySelection = false,
            selectLatest = true,
        )

        assertEquals("visible", result)
    }

    @Test
    fun reconciledSessionId_doesNotAutoSelectSubagentsOrStaleBlankSessions() {
        val result = reconciledSessionId(
            currentSessionId = null,
            sessions = listOf(
                session("subagent", running = true, updatedAt = System.currentTimeMillis(), origin = "subagent"),
                session("stale-blank", blank = true, updatedAt = 0L),
                session("visible", updatedAt = System.currentTimeMillis()),
            ),
            preserveEmptySelection = false,
            selectLatest = true,
        )

        assertEquals("visible", result)
    }

    @Test
    fun reconciledSessionId_preservesNewSessionComposerDuringSync() {
        val result = reconciledSessionId(
            currentSessionId = null,
            sessions = listOf(session("existing", running = true)),
            preserveEmptySelection = true,
            selectLatest = true,
        )

        assertEquals(null, result)
    }

    @Test
    fun reconciledSessionId_returnsNullWhenAuthoritativeListIsEmpty() {
        val result = reconciledSessionId(
            currentSessionId = "removed",
            sessions = emptyList(),
            preserveEmptySelection = false,
            selectLatest = false,
        )

        assertEquals(null, result)
    }

    // ===== 冷启动恢复「最近打开的会话」（方案 P0） =====

    @Test
    fun reconciledSessionId_restoresPreferredSessionOnColdStart() {
        val sessions = listOf(
            session("newest", updatedAt = 900L),
            session("remembered", updatedAt = 100L),
        )
        val result = reconciledSessionId(
            currentSessionId = null,
            sessions = sessions,
            preserveEmptySelection = false,
            selectLatest = true,
            preferredSessionId = "remembered",
        )
        assertEquals("remembered", result)
    }

    @Test
    fun reconciledSessionId_fallsBackWhenPreferredSessionIsGone() {
        val sessions = listOf(session("newest", updatedAt = 900L))
        val result = reconciledSessionId(
            currentSessionId = null,
            sessions = sessions,
            preserveEmptySelection = false,
            selectLatest = true,
            preferredSessionId = "deleted-elsewhere",
        )
        assertEquals("newest", result)
    }

    @Test
    fun reconciledSessionId_ignoresPreferredSessionThatIsArchived() {
        val sessions = listOf(
            session("newest", updatedAt = 900L),
            session("remembered", updatedAt = 100L),
        )
        val result = reconciledSessionId(
            currentSessionId = null,
            sessions = sessions,
            hiddenSessionIds = setOf("remembered"),
            preserveEmptySelection = false,
            selectLatest = true,
            preferredSessionId = "remembered",
        )
        assertEquals("newest", result)
    }

    @Test
    fun reconciledSessionId_existingSelectionBeatsPreferredSession() {
        val sessions = listOf(
            session("current", updatedAt = 900L),
            session("remembered", updatedAt = 100L),
        )
        val result = reconciledSessionId(
            currentSessionId = "current",
            sessions = sessions,
            preserveEmptySelection = false,
            selectLatest = true,
            preferredSessionId = "remembered",
        )
        assertEquals("current", result)
    }

    @Test
    fun reconciledSessionId_newSessionComposerIsNotOverriddenByRestore() {
        val sessions = listOf(session("remembered", updatedAt = 100L))
        val result = reconciledSessionId(
            currentSessionId = null,
            sessions = sessions,
            preserveEmptySelection = true,
            selectLatest = true,
            preferredSessionId = "remembered",
        )
        assertEquals(null, result)
    }

    @Test
    fun visibleHistorySessions_excludesArchivedDeletedAndSubagents() {
        val sessions = listOf(
            session("visible"),
            session("archived"),
            session("deleted"),
            session("subagent", origin = "subagent"),
        )

        val result = visibleHistorySessions(
            sessions = sessions,
            archivedIds = setOf("archived"),
            deletedIds = setOf("deleted"),
        )

        assertEquals(listOf("visible"), result.map { it.sessionId })
    }

    @Test
    fun buildSessionFilterData_filtersStaleBlanksAndCountsInOnePass() {
        val now = 2 * 24 * 3600_000L
        val sessions = listOf(
            session("running", running = true, updatedAt = now),
            session("stopped", running = false, updatedAt = now),
            session("stale-blank", blank = true, updatedAt = 0L),
            session("archived", updatedAt = now),
        )

        val result = buildSessionFilterData(
            sessions = sessions,
            archivedIds = setOf("archived"),
            deletedIds = emptySet(),
            nowMillis = now,
        )

        assertEquals(listOf("running", "stopped"), result.sessions.map { it.sessionId })
        assertEquals(2, result.counts[SessionFilter.ALL])
        assertEquals(1, result.counts[SessionFilter.RUNNING])
        assertEquals(1, result.counts[SessionFilter.STOPPED])
    }

    private fun session(
        id: String,
        running: Boolean = false,
        blank: Boolean = false,
        updatedAt: Long = 0L,
        origin: String? = null,
    ) = MobileSession(
        sessionId = id,
        title = id,
        updatedAt = updatedAt,
        running = running,
        blank = blank,
        cwd = null,
        agentPreset = null,
        origin = origin,
    )
}
