package dev.deeplinks.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DshCodeSurfaceTest {

    @Test
    fun defaultsMatchExistingCodeBlock() {
        val metrics = DshCodeSurface.default
        assertEquals(13f, metrics.fontSize.value, 0.001f)
        assertEquals(20f, metrics.lineHeight.value, 0.001f)
        assertEquals(22, metrics.rowHeightDp)
        assertEquals(34, metrics.gutterWidthDp)
    }

    @Test
    fun clampsAndScales() {
        val big = DshCodeSurface.resolve(100f)
        assertEquals(22f, big.fontSize.value, 0.001f)
        assertTrue(big.rowHeightDp > 22)

        val small = DshCodeSurface.resolve(0f)
        assertEquals(9f, small.fontSize.value, 0.001f)
        assertTrue(small.rowHeightDp >= 14)
    }
}
