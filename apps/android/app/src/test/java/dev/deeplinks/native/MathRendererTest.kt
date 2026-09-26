package dev.deeplinks.native

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MathRendererTest {
    @Test
    fun `valid formula dimensions are converted to bounded physical pixels`() {
        assertEquals(MathBitmapSize(200, 100), safeMathBitmapSize(100f, 50f, 2f))
    }

    @Test
    fun `invalid or oversized formula dimensions fall back`() {
        assertNull(safeMathBitmapSize(0f, 20f, 2f))
        assertNull(safeMathBitmapSize(Float.NaN, 20f, 2f))
        assertNull(safeMathBitmapSize(1_025f, 20f, 2f))
        assertNull(safeMathBitmapSize(1_000f, 1_000f, 4f))
    }
}
