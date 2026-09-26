package dev.deeplinks.native.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ParkedSendTest {
    @Test
    fun roundTripTextAndImage() {
        val send = ParkedSend(
            slotKey = "lan|box|http://h",
            sessionId = "s1",
            text = "hello",
            images = listOf("image/jpeg" to "abc"),
        )
        val decoded = decodeParkedSend(encodeParkedSend(send))
        assertEquals(send, decoded)
    }

    @Test
    fun decodeRejectsEmptyAndGarbage() {
        assertNull(decodeParkedSend(null))
        assertNull(decodeParkedSend(" "))
        assertNull(decodeParkedSend("{not json"))
        assertNull(decodeParkedSend(encodeParkedSend(ParkedSend("slot", null, ""))))
    }

    @Test
    fun persistDropsImagesWhenOverCap() {
        val huge = "x".repeat(50_000)
        val send = ParkedSend(
            slotKey = "slot",
            sessionId = "s",
            text = "keep me",
            images = listOf("image/jpeg" to huge, "image/jpeg" to huge, "image/jpeg" to huge, "image/jpeg" to huge),
        )
        val parked = parkSendForPersistence(send, maxChars = 20_000)
        assertEquals("keep me", parked?.text)
        assertTrue(parked?.images.isNullOrEmpty())
        assertTrue(parked?.droppedImages == true)
    }

    @Test
    fun persistKeepsSmallPayload() {
        val send = ParkedSend("slot", "s", "hi", listOf("image/jpeg" to "qq"))
        assertEquals(send, parkSendForPersistence(send, maxChars = 20_000))
    }

    @Test
    fun restoreOnlySameHostAndSession() {
        val parked = ParkedSend("slot-a", "s1", "hello")
        assertEquals(ParkedRestoreKind.IntoCurrent, parkedSendRestoreKind(parked, "slot-a", "s1"))
        assertEquals(ParkedRestoreKind.None, parkedSendRestoreKind(parked, "slot-b", "s1"))
        assertEquals(ParkedRestoreKind.None, parkedSendRestoreKind(parked, "slot-a", "s2"))
        assertEquals(ParkedRestoreKind.SelectSession, parkedSendRestoreKind(parked, "slot-a", null))
        val compose = ParkedSend("slot-a", null, "hello")
        assertEquals(ParkedRestoreKind.IntoCurrent, parkedSendRestoreKind(compose, "slot-a", null))
        assertEquals(ParkedRestoreKind.None, parkedSendRestoreKind(compose, "slot-a", "s1"))
        assertEquals(false, parkedRestoreComposerError(droppedImages = false))
        assertEquals(true, parkedRestoreComposerError(droppedImages = true))
    }
}
