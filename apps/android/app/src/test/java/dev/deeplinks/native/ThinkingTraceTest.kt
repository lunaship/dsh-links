package dev.deeplinks.native

import org.junit.Assert.assertEquals
import org.junit.Test

class ThinkingTraceTest {
    @Test
    fun thoughtDoneLabel_prefersDurationMs() {
        assertEquals("思考 · 4秒", thoughtDoneLabel(4500, 99))
    }

    @Test
    fun thoughtDoneLabel_fallsBackToElapsed() {
        assertEquals("思考 · 7秒", thoughtDoneLabel(null, 7))
    }

    @Test
    fun thoughtDoneLabel_withoutDuration() {
        assertEquals("思考", thoughtDoneLabel(null, null))
        assertEquals("思考", thoughtDoneLabel(0, 0))
    }
}
