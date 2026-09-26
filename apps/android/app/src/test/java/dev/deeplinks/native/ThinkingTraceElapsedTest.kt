package dev.deeplinks.native

import org.junit.Assert.assertEquals
import org.junit.Test

class ThinkingTraceElapsedTest {
    @Test
    fun formatThinkingElapsed_underOneMinute() {
        assertEquals("0s", formatThinkingElapsed(0))
        assertEquals("7s", formatThinkingElapsed(7))
        assertEquals("59s", formatThinkingElapsed(59))
    }

    @Test
    fun formatThinkingElapsed_minutes() {
        assertEquals("1m 0s", formatThinkingElapsed(60))
        assertEquals("2m 5s", formatThinkingElapsed(125))
    }
}
