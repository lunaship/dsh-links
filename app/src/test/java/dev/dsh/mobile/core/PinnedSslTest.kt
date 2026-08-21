package dev.dsh.mobile.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.net.URL

class PinnedSslTest {

    @Test
    fun `http is upgraded to https`() {
        assertEquals("https://10.0.0.2:18640", PinnedSsl.normalizeUrl("http://10.0.0.2:18640"))
        assertEquals("https://dsh.example.com", PinnedSsl.normalizeUrl("dsh.example.com"))
        assertEquals("https://dsh.example.com", PinnedSsl.normalizeUrl("https://dsh.example.com"))
    }

    @Test
    fun `apply rejects cleartext connections`() {
        val conn = URL("http://127.0.0.1:9/").openConnection() as java.net.HttpURLConnection
        try {
            assertThrows(IllegalArgumentException::class.java) {
                PinnedSsl.apply(conn, null)
            }
        } finally {
            conn.disconnect()
        }
    }
}
