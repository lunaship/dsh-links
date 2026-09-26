package dev.deeplinks.native.util

import org.junit.Assert.assertEquals
import org.junit.Test

class ClipboardFeedbackTest {
    @Test
    fun android13UsesSystemClipboardOverlay() {
        assertEquals(true, copiedNeedsAppToast(32))
        assertEquals(false, copiedNeedsAppToast(33))
        assertEquals(false, copiedNeedsAppToast(36))
    }
}
