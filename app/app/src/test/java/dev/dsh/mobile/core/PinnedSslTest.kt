package dev.dsh.mobile.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URL
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection

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

    @Test
    fun `shouldPin covers private and loopback hosts`() {
        assertTrue(PinnedSsl.shouldPin("https://192.168.1.8:18640"))
        assertTrue(PinnedSsl.shouldPin("https://10.0.0.2:18640"))
        assertTrue(PinnedSsl.shouldPin("https://127.0.0.1:18640"))
        assertTrue(PinnedSsl.shouldPin("https://172.16.0.1:18640"))
        assertTrue(PinnedSsl.shouldPin("https://localhost:18640"))
        assertFalse(PinnedSsl.shouldPin("https://example.com"))
        assertFalse(PinnedSsl.shouldPin("https://8.8.8.8"))
        assertFalse(PinnedSsl.shouldPin("https://172.15.0.1"))
    }

    @Test
    fun `empty fingerprint fail-closed on private lan`() {
        val conn = URL("https://192.168.1.8:18640/").openConnection() as HttpsURLConnection
        try {
            assertThrows(PinnedSsl.CertChangedException::class.java) {
                PinnedSsl.apply(conn, null)
            }
            assertThrows(PinnedSsl.CertChangedException::class.java) {
                PinnedSsl.apply(conn, "")
            }
        } finally {
            conn.disconnect()
        }
    }

    @Test
    fun `invalid fingerprint is rejected`() {
        val conn = URL("https://10.0.0.2:18640/").openConnection() as HttpsURLConnection
        try {
            assertThrows(PinnedSsl.CertChangedException::class.java) {
                PinnedSsl.apply(conn, "not-a-fingerprint")
            }
            assertThrows(PinnedSsl.CertChangedException::class.java) {
                PinnedSsl.apply(conn, "gg".repeat(32))
            }
            assertThrows(PinnedSsl.CertChangedException::class.java) {
                PinnedSsl.apply(conn, "ab".repeat(31))
            }
        } finally {
            conn.disconnect()
        }
    }

    @Test
    fun `empty fingerprint on public host uses system ca path`() {
        val conn = URL("https://example.com/").openConnection() as HttpsURLConnection
        try {
            PinnedSsl.apply(conn, null)
            PinnedSsl.apply(conn, "")
        } finally {
            conn.disconnect()
        }
    }

    @Test
    fun `pinned trust manager rejects empty chain and wrong fingerprint`() {
        val cert = testCert()
        val tmWrong = PinnedSsl.pinnedTrustManager("b".repeat(64))
        assertThrows(PinnedSsl.CertChangedException::class.java) {
            tmWrong.checkServerTrusted(emptyArray(), "RSA")
        }
        assertThrows(PinnedSsl.CertChangedException::class.java) {
            tmWrong.checkServerTrusted(arrayOf(cert), "RSA")
        }
        PinnedSsl.pinnedTrustManager(PinnedSsl.fingerprintOf(cert))
            .checkServerTrusted(arrayOf(cert), "RSA")
    }

    private fun testCert(): X509Certificate {
        val pem = """
            -----BEGIN CERTIFICATE-----
            MIICrjCCAZYCCQCaPfVOdjN5vDANBgkqhkiG9w0BAQsFADAZMRcwFQYDVQQDDA5k
            c2gtbGlua3MtdGVzdDAeFw0yNjA4MjEwMzA2NDlaFw0zNjA4MTgwMzA2NDlaMBkx
            FzAVBgNVBAMMDmRzaC1saW5rcy10ZXN0MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8A
            MIIBCgKCAQEA0rwTaErhIDfxCzAnamYBNY6PMwIiMFcFtNpZr3/pLi71jxOhSy4C
            0Aubtdl7Nd8LcI7j7JHBeEx3DYJpYhmUopWecHIDYVQLxA160X50604x6Kt1J6T5
            Ec3hm4xHHlbYZTU2uJAKoel2IWELAOijDmayN4GVtxJPqrPbrL+FYNGfjiwuW2T4
            BzWNsPhYWYre6udlcBUXIfgVKXcYM//JQLyoct1o4yCZL9ce4VJPxzezGpCghMOz
            JbwRQ8N/1lWn5r/QqNoi8AyTUbn/Bk/GAGwh6m3ccafHzX74K/qv6bswaI7DFjmj
            390jjIwv5APqO54b3eBHU37HdFkfcX5xbQIDAQABMA0GCSqGSIb3DQEBCwUAA4IB
            AQC99oFh2IR/dOB21PoPXGTTlLPuah8fsEVeaniQ6xglxxokNhpoAMluuR/qtaam
            6KGzJLfBDFLs6VxIh58cc+mzZR5jtpfpOCV5N+37Md3NEnqberhxEYtAIDhQ5eWU
            4ksfiFfoWChsx9D9TR/ZaaVEKgdvM5c3vaDz3LuhvCs77rCYjJCKoOy0dIt8rb/6
            4r7cTjd273Mc1MUfehLQ0F0SZzO1e68uE3CVTOVEeCF3IqM9VMhe2O/6QYDc9PZV
            ezjXKazBwLtzufa6YLfG+rzTfTSwCxqaVvNTT2jco50uqajjxAYBxRbP24YWfA10
            tew0+9NymAtRx2fhIYeWALQK
            -----END CERTIFICATE-----
        """.trimIndent()
        return pem.byteInputStream().use {
            CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
        }
    }
}
