package dev.deeplinks.native.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AnswerMetaTest {
    @Test
    fun compactDuration_and_tokens() {
        assertEquals("1.5s", compactDuration(1_500))
        assertEquals("2.1K", compactTokens(2_100))
        assertEquals("900ms", compactDuration(900))
    }

    @Test
    fun buildAnswerMeta_skipsEmpty() {
        assertNull(buildAnswerMeta(null))
        assertNull(buildAnswerMeta(0L))
    }

    @Test
    fun buildAnswerMeta_durationOnly_withoutSession() {
        val meta = buildAnswerMeta(2_400)
        assertEquals(2_400L, meta?.elapsedMs)
        assertEquals("2.4s", answerMetaSummary(meta!!))
    }

    @Test
    fun buildAnswerMeta_neverIncludesSessionUsage() {
        val meta = buildAnswerMeta(durationMs = 3_200)!!
        assertEquals(3_200L, meta.elapsedMs)
        assertEquals("3.2s", answerMetaSummary(meta))
    }
}
