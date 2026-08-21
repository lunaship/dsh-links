package dev.dsh.mobile.native

import org.junit.Assert.assertEquals
import org.junit.Test

class PixelLoadingStateTest {
    @Test
    fun formatPixelElapsed_underOneMinute() {
        assertEquals("0s", formatPixelElapsed(0))
        assertEquals("7s", formatPixelElapsed(7))
        assertEquals("59s", formatPixelElapsed(59))
    }

    @Test
    fun formatPixelElapsed_minutes() {
        assertEquals("1m 0s", formatPixelElapsed(60))
        assertEquals("2m 5s", formatPixelElapsed(125))
    }
}
