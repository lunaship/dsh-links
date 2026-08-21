package dev.dsh.mobile.native.util

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
    fun visibleUserWorkspaces_mergesRegistryAndSessions() {
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
    fun visibleUserWorkspaces_excludesDeleted() {
        val out = visibleUserWorkspaces(
            sessionCwds = listOf("/a/gone", "/a/keep"),
            deletedWorkspaces = setOf("/a/gone"),
            registeredPaths = listOf("/a/gone", "/a/keep", "/a/empty"),
        )
        assertEquals(listOf("/a/empty", "/a/keep"), out)
    }
}
