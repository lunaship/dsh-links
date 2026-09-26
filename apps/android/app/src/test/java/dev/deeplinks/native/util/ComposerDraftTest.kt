package dev.deeplinks.native.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposerDraftTest {
    @Test
    fun keyTreatsNullAsNewSession() {
        assertEquals("", composerDraftKey(null))
        assertEquals("s1", composerDraftKey("s1"))
    }

    @Test
    fun stashKeepsDraftsOnTheirSessions() {
        val a = ComposerDraft("hello A", listOf("image/jpeg" to "aaa"))
        val (stored, loadedB) = stashComposerDraft(emptyMap(), "s1", "s2", a)
        assertEquals(a, stored["s1"])
        assertTrue(loadedB.isEmpty)
        val (back, loadedA) = stashComposerDraft(stored, "s2", "s1", ComposerDraft("hello B"))
        assertEquals("hello B", back["s2"]?.text)
        assertEquals(a, loadedA)
    }

    @Test
    fun stashSameKeyLeavesState() {
        val current = ComposerDraft("keep")
        val (stored, loaded) = stashComposerDraft(emptyMap(), "s1", "s1", current)
        assertTrue(stored.isEmpty())
        assertEquals(current, loaded)
    }

    @Test
    fun emptyDraftIsDroppedFromMap() {
        val start = mapOf("s1" to ComposerDraft("old"))
        val (stored, loaded) = stashComposerDraft(start, "s1", "s2", ComposerDraft())
        assertTrue("s1" !in stored)
        assertTrue(loaded.isEmpty)
    }

    @Test
    fun mergeTextAndCapImages() {
        assertEquals("hi", mergeComposerText(ComposerDraft(), "hi").text)
        assertEquals("hi there", mergeComposerText(ComposerDraft("hi"), "there").text)
        var draft = ComposerDraft()
        repeat(COMPOSER_MAX_IMAGES + 2) { i ->
            draft = appendComposerImage(draft, "image/jpeg" to "$i")
        }
        assertEquals(COMPOSER_MAX_IMAGES, draft.images.size)
    }

    @Test
    fun putRemovesEmpty() {
        val stored = putComposerDraft(mapOf("s1" to ComposerDraft("x")), "s1", ComposerDraft())
        assertTrue(stored.isEmpty())
    }

    @Test
    fun switchParksAndRestoresComposerError() {
        val (parked, liveB) = switchComposerErrors(
            emptyMap(),
            fromKey = "s1",
            toKey = "s2",
            currentError = "read failed",
        )
        assertEquals("read failed", parked["s1"])
        assertEquals(null, liveB)
        val (restoredMap, liveA) = switchComposerErrors(parked, "s2", "s1", null)
        assertEquals("read failed", liveA)
        assertTrue("s1" !in restoredMap)
    }

    @Test
    fun switchSameKeyLeavesComposerError() {
        val (errors, live) = switchComposerErrors(
            mapOf("s2" to "other"),
            fromKey = "s1",
            toKey = "s1",
            currentError = "keep",
        )
        assertEquals("keep", live)
        assertEquals("other", errors["s2"])
    }

    @Test
    fun liveOrParkedComposerErrorFollowsOwner() {
        val (sameErrors, live) = liveOrParkedComposerError(emptyMap(), "s1", "s1", " boom ")
        assertEquals("boom", live)
        assertTrue(sameErrors.isEmpty())
        val (parked, noLive) = liveOrParkedComposerError(emptyMap(), "s1", "s2", "voice failed")
        assertEquals(null, noLive)
        assertEquals("voice failed", parked["s1"])
    }
}
