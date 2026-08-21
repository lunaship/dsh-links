package dev.dsh.mobile.devices

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingQrTest {

    @Test
    fun `valid payload parses`() {
        val text = """{"type":"dsh-link","pairingCode":"123456","urls":["https://10.0.0.2:18640"],"name":"书房","certFingerprint":"ab"}"""
        val parsed = parsePairingQr(text)
        assertTrue(parsed is PairingQrResult.Ok)
        val qr = (parsed as PairingQrResult.Ok).qr
        assertEquals("123456", qr.code)
        assertEquals(listOf("https://10.0.0.2:18640"), qr.urls)
        assertEquals("书房", qr.name)
        assertEquals("ab", qr.certFingerprint)
    }

    @Test
    fun `non dsh json is not dsh`() {
        assertTrue(parsePairingQr("""{"type":"other"}""") is PairingQrResult.NotDsh)
        assertTrue(parsePairingQr("not-json") is PairingQrResult.NotDsh)
    }

    @Test
    fun `malformed urls do not crash`() {
        assertTrue(parsePairingQr("""{"type":"dsh-link","pairingCode":"1","urls":[1,2]}""") is PairingQrResult.Invalid)
        assertTrue(parsePairingQr("""{"type":"dsh-link","pairingCode":"1","urls":[{"x":1}]}""") is PairingQrResult.Invalid)
        assertTrue(parsePairingQr("""{"type":"dsh-link","pairingCode":"1","urls":[null]}""") is PairingQrResult.Invalid)
        assertTrue(parsePairingQr("""{"type":"dsh-link","pairingCode":"1"}""") is PairingQrResult.Invalid)
        assertTrue(parsePairingQr("""{"type":"dsh-link","pairingCode":"","urls":["https://x"]}""") is PairingQrResult.Invalid)
    }

    @Test
    fun `code field alias is accepted`() {
        val parsed = parsePairingQr("""{"type":"dsh-link","code":"654321","urls":["https://example.com"]}""")
        assertEquals("654321", (parsed as PairingQrResult.Ok).qr.code)
    }
}
