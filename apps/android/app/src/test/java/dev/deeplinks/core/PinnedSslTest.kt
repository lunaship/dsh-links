package dev.deeplinks.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

class PinnedSslTest {

    @Test
    fun `http is upgraded to https`() {
        assertEquals("https://10.0.0.2:18640", PinnedSsl.normalizeUrl("http://10.0.0.2:18640"))
        assertEquals("https://dsh.example.com", PinnedSsl.normalizeUrl("dsh.example.com"))
        assertEquals("https://dsh.example.com", PinnedSsl.normalizeUrl("https://dsh.example.com"))
    }

    @Test
    fun `empty fingerprint fail-closed on private lan`() {
        assertThrows(PinnedSsl.CertChangedException::class.java) {
            validateLanIdentity("https://192.168.1.8:18640", "")
        }
        assertThrows(PinnedSsl.CertChangedException::class.java) {
            validateLanIdentity("https://192.168.1.8:18640", PinnedSsl.normalizeFingerprint(null))
        }
        // 公网主机空指纹：允许走系统 PKI
        validateLanIdentity("https://example.com", "")
        validateLanIdentity("https://8.8.8.8", "")
    }

    @Test
    fun `shouldPin covers private and loopback hosts`() {
        assertTrue(PinnedSsl.shouldPin("https://192.168.1.8:18640"))
        assertTrue(PinnedSsl.shouldPin("https://10.0.0.2:18640"))
        assertTrue(PinnedSsl.shouldPin("https://127.0.0.1:18640"))
        assertTrue(PinnedSsl.shouldPin("https://172.16.0.1:18640"))
        assertTrue(PinnedSsl.shouldPin("https://100.64.0.1:18640"))
        assertTrue(PinnedSsl.shouldPin("https://100.127.255.254:18640"))
        assertTrue(PinnedSsl.shouldPin("https://localhost:18640"))
        assertFalse(PinnedSsl.shouldPin("https://example.com"))
        assertFalse(PinnedSsl.shouldPin("https://8.8.8.8"))
        assertFalse(PinnedSsl.shouldPin("https://172.15.0.1"))
        assertFalse(PinnedSsl.shouldPin("https://100.128.0.1"))
    }

    @Test
    fun `shouldPin defaults to pinning on reserved and non-public addresses`() {
        // 白名单式判定：以下地址此前会 fail-open 到系统 PKI
        assertTrue(PinnedSsl.shouldPin("https://169.254.10.20:18640"))
        assertTrue(PinnedSsl.shouldPin("https://198.18.0.1:18640"))
        assertTrue(PinnedSsl.shouldPin("https://198.19.255.254:18640"))
        assertTrue(PinnedSsl.shouldPin("https://0.0.0.0:18640"))
        assertTrue(PinnedSsl.shouldPin("https://224.0.0.1:18640"))
        assertTrue(PinnedSsl.shouldPin("https://240.0.0.1:18640"))
        assertTrue(PinnedSsl.shouldPin("https://192.0.2.10:18640"))
        assertTrue(PinnedSsl.shouldPin("https://203.0.113.9:18640"))
        assertTrue(PinnedSsl.shouldPin("https://dsh-host.local:18640"))
        // IPv6：ULA / 链路本地 / 回环 / 映射私网地址一律钉扎
        assertTrue(PinnedSsl.shouldPin("https://[fd00::1]:18640"))
        assertTrue(PinnedSsl.shouldPin("https://[fe80::1]:18640"))
        assertTrue(PinnedSsl.shouldPin("https://[::1]:18640"))
        assertTrue(PinnedSsl.shouldPin("https://[::ffff:192.168.1.5]:18640"))
        assertTrue(PinnedSsl.shouldPin("https://[2001:db8::1]:18640"))
        // 公网单播 IPv6 走系统 PKI
        assertFalse(PinnedSsl.shouldPin("https://[2606:4700:4700::1111]:18640"))
    }

    @Test
    fun `invalid fingerprint is rejected`() {
        assertThrows(PinnedSsl.CertChangedException::class.java) {
            PinnedSsl.requireValidPin("not-a-fingerprint")
        }
        assertThrows(PinnedSsl.CertChangedException::class.java) {
            PinnedSsl.requireValidPin("gg".repeat(32))
        }
        assertThrows(PinnedSsl.CertChangedException::class.java) {
            PinnedSsl.requireValidPin("ab".repeat(31))
        }
        PinnedSsl.requireValidPin("ab".repeat(32))
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
