package dev.dsh.mobile.native

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RawFallbackTest {

    @Test
    fun emptyText_unknownRole_isRawFallback() {
        val msg = MobileMessage(id = "1", role = "image_generation", text = "")
        assertTrue(isRawFallback(msg))
    }

    @Test
    fun blankText_isRawFallback() {
        assertTrue(isRawFallback(MobileMessage(id = "1", role = "x", text = "   ")))
    }

    @Test
    fun nonEmptyText_isNotRawFallback() {
        assertFalse(isRawFallback(MobileMessage(id = "1", role = "unknown", text = "hello")))
    }
}
