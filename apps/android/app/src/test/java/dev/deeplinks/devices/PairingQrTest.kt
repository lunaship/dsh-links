package dev.deeplinks.devices

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
    fun `legacy payload ignores unknown relay fields`() {
        val text = """{"type":"dsh-link","pairingCode":"123456","urls":["https://10.0.0.2:18640"],"name":"书房","certFingerprint":"ab","relay":{"v":2,"client":"relay.example:8443","routeId":"rid","routeSecret":"sec","tlsFingerprint":"ff"}}"""
        val parsed = parsePairingQr(text)
        assertTrue(parsed is PairingQrResult.Ok)
        val qr = (parsed as PairingQrResult.Ok).qr
        assertEquals("123456", qr.code)
        assertEquals("relay.example:8443", qr.relay?.client)
        assertEquals("rid", qr.relay?.routeId)
        assertEquals("sec", qr.relay?.routeSecret)
        assertEquals("ff", qr.relay?.tlsFingerprint)
    }

    @Test
    fun `relay-only qr without inner pin is invalid`() {
        val text = """{"type":"dsh-link","pairingCode":"123456","relay":{"client":"10.0.0.9:8443","routeId":"rid","routeSecret":"sec"}}"""
        val parsed = parsePairingQr(text)
        assertTrue(parsed is PairingQrResult.Invalid)
    }

    @Test
    fun `relay-only qr requires inner pin`() {
        val text = """{"type":"dsh-link","pairingCode":"123456","certFingerprint":"ab","relay":{"client":"10.0.0.9:8443","routeId":"rid","routeSecret":"sec"}}"""
        val parsed = parsePairingQr(text)
        assertTrue(parsed is PairingQrResult.Ok)
        val qr = (parsed as PairingQrResult.Ok).qr
        assertTrue(qr.urls.isEmpty())
        assertEquals("10.0.0.9:8443", qr.relay?.client)
    }

    @Test
    fun `anonymous relay qr is rejected because it has no target route`() {
        val text = """{"type":"dsh-link","pairingCode":"123456","certFingerprint":"ab","relay":{"client":"10.0.0.9:8443","mode":"anonymous","tlsFingerprint":"ff"}}"""
        assertTrue(parsePairingQr(text) is PairingQrResult.Invalid)
    }

    @Test
    fun `incomplete relay is ignored and urls remain required`() {
        assertTrue(parsePairingQr("""{"type":"dsh-link","pairingCode":"1","relay":{"client":"x"}}""") is PairingQrResult.Invalid)
        val parsed = parsePairingQr("""{"type":"dsh-link","pairingCode":"1","urls":["https://10.0.0.2:18640"],"relay":{"client":"x"}}""")
        assertTrue(parsed is PairingQrResult.Ok)
        assertEquals(null, (parsed as PairingQrResult.Ok).qr.relay)
    }
}
