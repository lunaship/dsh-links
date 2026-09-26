package dev.deeplinks.native.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceListsTest {
    @Test
    fun isUserWorkspace_rejectsSystemPaths() {
        assertFalse(isUserWorkspace("/Users/me/Library/Caches"))
        assertFalse(isUserWorkspace("/tmp/foo"))
        assertFalse(isUserWorkspace("/Users/me/proj/node_modules/pkg"))
        assertTrue(isUserWorkspace("/Volumes/Space/Dev/dsh-links"))
        assertTrue(isUserWorkspace(null))
    }

    @Test
    fun visibleUserWorkspaces_mergesRegistryAndSessions_whenNotRequireRegistered() {
        val out = visibleUserWorkspaces(
            sessionCwds = listOf("/Volumes/Space/Dev/DeepHarness", "/Volumes/Space/Dev/DeepHarness"),
            deletedWorkspaces = emptySet(),
            registeredPaths = listOf(
                "/Volumes/Space/Dev/dsh-links",
                "/Volumes/Space/Dev/dsh-chat",
                "/Volumes/Space/Dev/DeepHarness",
            ),
        )
        assertEquals(
            listOf(
                "/Volumes/Space/Dev/DeepHarness",
                "/Volumes/Space/Dev/dsh-chat",
                "/Volumes/Space/Dev/dsh-links",
            ),
            out,
        )
    }

    @Test
    fun visibleUserWorkspaces_requireRegistered_ignoresOrphanSessionCwds() {
        val out = visibleUserWorkspaces(
            sessionCwds = listOf(
                "/Volumes/Space/Dev/DeepHarness",
                "/Volumes/Space/Dev/Kept",
                "/Users/me",
            ),
            deletedWorkspaces = emptySet(),
            registeredPaths = listOf(
                "/Volumes/Space/Dev/dsh-links",
                "/Volumes/Space/Dev/dsh-links/relay",
            ),
            requireRegistered = true,
        )
        assertEquals(
            listOf(
                "/Volumes/Space/Dev/dsh-links",
                "/Volumes/Space/Dev/dsh-links/relay",
            ),
            out,
        )
    }

    @Test
    fun visibleUserWorkspaces_excludesDeleted() {
        val out = visibleUserWorkspaces(
            sessionCwds = listOf("/a/gone", "/a/keep"),
            deletedWorkspaces = setOf("/a/gone"),
            registeredPaths = listOf("/a/gone", "/a/keep", "/a/empty"),
            requireRegistered = true,
        )
        assertEquals(listOf("/a/empty", "/a/keep"), out)
    }

    @Test
    fun visibleSidebarWorkspaces_keepsNewRegisteredWorkspaceWithoutSessions() {
        val out = visibleSidebarWorkspaces(
            knownWorkspaces = listOf("/a/existing", "/a/new"),
            workspacesWithVisibleSessions = setOf("/a/existing"),
            searchQuery = "",
            sessionFilterActive = false,
        )

        assertEquals(listOf("/a/existing", "/a/new"), out)
    }

    @Test
    fun visibleSidebarWorkspaces_filtersEmptyWorkspaceOnlyForActiveSessionFilter() {
        val out = visibleSidebarWorkspaces(
            knownWorkspaces = listOf("/a/existing", "/a/new"),
            workspacesWithVisibleSessions = setOf("/a/existing"),
            searchQuery = "",
            sessionFilterActive = true,
        )

        assertEquals(listOf("/a/existing"), out)
    }

    @Test
    fun isSessionWorkspaceVisible_hidesUnregisteredWhenReady() {
        assertFalse(
            isSessionWorkspaceVisible(
                cwd = "/Volumes/Space/Dev/Kept",
                deletedWorkspaces = emptySet(),
                registeredPaths = listOf("/Volumes/Space/Dev/dsh-links"),
                registryReady = true,
            ),
        )
        assertTrue(
            isSessionWorkspaceVisible(
                cwd = "/Volumes/Space/Dev/dsh-links",
                deletedWorkspaces = emptySet(),
                registeredPaths = listOf("/Volumes/Space/Dev/dsh-links"),
                registryReady = true,
            ),
        )
        // 注册表未就绪时，仍允许会话 cwd 临时显示
        assertTrue(
            isSessionWorkspaceVisible(
                cwd = "/Volumes/Space/Dev/Kept",
                deletedWorkspaces = emptySet(),
                registeredPaths = emptyList(),
                registryReady = false,
            ),
        )
    }

    @Test
    fun workspaceGroupKey_usesMembershipNotCwd() {
        val accounts = listOf(
            WorkspaceAccount("/Volumes/Space/Dev/dsh-links/relay", listOf("in-folder")),
        )
        assertEquals(
            "/Volumes/Space/Dev/dsh-links/relay",
            workspaceGroupKey("in-folder", accounts),
        )
        assertEquals(null, workspaceGroupKey("cwd-only", accounts))
    }

    @Test
    fun workspaceGroupKey_deletedPathBecomesUngrouped() {
        val accounts = listOf(WorkspaceAccount("/a/relay", listOf("s1")))
        assertEquals(
            null,
            workspaceGroupKey("s1", accounts, deletedWorkspaces = setOf("/a/relay")),
        )
    }

    @Test
    fun reconcileDeletedWorkspaces_unhidesWhenReregistered() {
        val out = reconcileDeletedWorkspaces(
            nextRegistered = listOf("/a/keep", "/a/back"),
            deletedWorkspaces = setOf("/a/gone", "/a/back"),
        )
        assertEquals(setOf("/a/gone"), out)
    }
}
