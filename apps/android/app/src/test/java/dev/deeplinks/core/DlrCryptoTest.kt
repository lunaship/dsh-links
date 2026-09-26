package dev.deeplinks.core

import org.junit.Assert.assertEquals
import org.junit.Test
import org.json.JSONObject

class DlrCryptoTest {
    @Test
    fun `CONNECT and BIND MAC match DLR1 vectors`() {
        val vectors = JSONObject(javaClass.classLoader!!.getResource("dlr1-vectors.json")!!.readText())
        val hkdf = vectors.getJSONObject("hkdf")
        val hmac = vectors.getJSONObject("hmac")
        val connectVector = hmac.getJSONObject("connect")
        val bindVector = hmac.getJSONObject("bind")
        val secret = DlrCrypto.decode(hkdf.getString("routeSecret"))
        val challenge = DlrCrypto.decode(connectVector.getString("challenge"))
        val nonce = DlrCrypto.decode(connectVector.getString("nonce"))
        val routeId = DlrCrypto.decode(connectVector.getString("routeId"))
        val ts = connectVector.getString("ts").toLong()
        val connect = DlrCrypto.connectMac(secret, routeId, ts, nonce, challenge)
        assertEquals(connectVector.getString("mac"), DlrCrypto.encode(connect))
        val bind = DlrCrypto.bindMac(
            secret,
            routeId,
            DlrCrypto.decode(bindVector.getString("streamId")),
            bindVector.getString("generation").toLong(),
            bindVector.getString("ts").toLong(),
            nonce,
            challenge,
        )
        assertEquals(bindVector.getString("mac"), DlrCrypto.encode(bind))
    }

    @Test
    fun `parseHostPort maps client default 8443`() {
        assertEquals("relay.example.com" to 8443, DlrCrypto.parseHostPort("relay.example.com", 8443))
        assertEquals("10.0.0.2" to 8443, DlrCrypto.parseHostPort("10.0.0.2:8443", 8443))
        assertEquals("2001:db8::1" to 8443, DlrCrypto.parseHostPort("[2001:db8::1]:8443", 8443))
    }
}
