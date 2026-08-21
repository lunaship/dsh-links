package dev.dsh.mobile.native

import org.junit.Assert.assertEquals
import org.junit.Test

class ThinkingTraceTest {
    @Test
    fun thoughtDoneLabel_prefersDurationMs() {
        assertEquals("已思考 4 秒", thoughtDoneLabel(4500, 99))
    }

    @Test
    fun thoughtDoneLabel_fallsBackToElapsed() {
        assertEquals("已思考 7 秒", thoughtDoneLabel(null, 7))
    }

    @Test
    fun thoughtDoneLabel_withoutDuration() {
        assertEquals("已思考", thoughtDoneLabel(null, null))
        assertEquals("已思考", thoughtDoneLabel(0, 0))
    }
}
