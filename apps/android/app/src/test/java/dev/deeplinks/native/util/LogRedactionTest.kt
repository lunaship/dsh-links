package dev.deeplinks.native.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogRedactionTest {
    @Test
    fun requestPathHidesFullSessionId() {
        val redacted = redactRequestPath("/dsh-link/mobile/sessions/sess-0123456789abcdef/prompt")
        assertFalse(redacted.contains("0123456789abcdef"))
        assertTrue(redacted.startsWith("/dsh-link/mobile/sessions/sess-012…"))
        assertTrue(redacted.endsWith("/prompt"))
    }

    @Test
    fun requestPathRedactsQueryValues() {
        val redacted = redactRequestPath("/dsh-link/mobile/models?sessionId=sess-0123456789abcdef")
        assertFalse(redacted.contains("0123456789abcdef"))
        assertEquals("/dsh-link/mobile/models?sessionId=sess-012…", redacted)
    }

    @Test
    fun requestPathKeepsShortSessionId() {
        assertEquals("/dsh-link/mobile/sessions/abc", redactRequestPath("/dsh-link/mobile/sessions/abc"))
    }

    @Test
    fun requestPathWithoutSessionsUntouched() {
        assertEquals("/dsh-link/mobile/devices", redactRequestPath("/dsh-link/mobile/devices"))
    }

    @Test
    fun uriKeepsSchemeAuthorityAndHashOnly() {
        val redacted = redactUriForLog("content://com.android.providers.media.documents/document/image%3A1234")
        assertTrue(redacted.startsWith("content://com.android.providers.media.documents~"))
        assertFalse(redacted.contains("image%3A1234"))
    }

    @Test
    fun fileUriDropsPath() {
        val redacted = redactUriForLog("file:///data/data/dev.deeplinks/secret.jpg")
        assertTrue(redacted.startsWith("file~"))
        assertFalse(redacted.contains("secret"))
    }

    @Test
    fun redactLogValueShortensOnlyLongValues() {
        assertEquals("", redactLogValue("   "))
        assertEquals("abc", redactLogValue("abc"))
        assertEquals("abcdefgh…", redactLogValue("abcdefghijkl"))
    }
}
