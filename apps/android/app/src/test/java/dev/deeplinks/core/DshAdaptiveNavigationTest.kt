package dev.deeplinks.core

import org.junit.Assert.assertEquals
import org.junit.Test

class DshAdaptiveNavigationTest {

    @Test
    fun compactAlwaysPushesSoBackStackStaysValid() {
        assertEquals(DshNavAction.Push, resolveThreadSelectionAction(usesPersistentSidebar = false, atHome = true))
        assertEquals(DshNavAction.Push, resolveThreadSelectionAction(usesPersistentSidebar = false, atHome = false))
    }

    @Test
    fun persistentSidebarSelectsInPlaceExceptFromHome() {
        assertEquals(DshNavAction.UpdateArgs, resolveThreadSelectionAction(usesPersistentSidebar = true, atHome = false))
        // Home stays beneath the thread when collapsing back to compact.
        assertEquals(DshNavAction.Push, resolveThreadSelectionAction(usesPersistentSidebar = true, atHome = true))
    }

    @Test
    fun fileSelectionReplacesOnlyWithPersistentInspector() {
        assertEquals(DshNavAction.Replace, resolveFileSelectionAction(hasPersistentInspector = true))
        assertEquals(DshNavAction.Push, resolveFileSelectionAction(hasPersistentInspector = false))
    }

    @Test
    fun actionsMatchDerivedLayout() {
        // Expanded -> persistent sidebar -> in-place switching
        assertEquals(DshNavAction.UpdateArgs, resolveThreadSelectionAction(deriveDshLayout(1200, 900).persistentSidebar, atHome = false))
        // Compact phone -> push
        assertEquals(DshNavAction.Push, resolveThreadSelectionAction(deriveDshLayout(412, 915).persistentSidebar, atHome = false))
    }
}
