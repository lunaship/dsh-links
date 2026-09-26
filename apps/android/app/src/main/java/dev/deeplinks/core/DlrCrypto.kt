package dev.deeplinks.core

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object DlrCrypto {
    fun decode(value: String): ByteArray = try {
        Base64.getUrlDecoder().decode(value)
    } catch (e: IllegalArgumentException) {
        throw java.io.IOException("invalid relay credential", e)
    }

    fun encode(value: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(value)

    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    fun connectMac(routeSecret: ByteArray, routeId: ByteArray, ts: Long, nonce: ByteArray, challenge: ByteArray): ByteArray =
        hmacSha256(routeSecret, macTranscript("CONNECT", routeId, null, null, ts, nonce, challenge))

    fun bindMac(
        routeSecret: ByteArray,
        routeId: ByteArray,
        streamId: ByteArray,
        generation: Long,
        ts: Long,
        nonce: ByteArray,
        challenge: ByteArray,
    ): ByteArray = hmacSha256(routeSecret, macTranscript("BIND", routeId, streamId, generation, ts, nonce, challenge))

    fun macTranscript(
        op: String,
        routeId: ByteArray,
        streamId: ByteArray?,
        generation: Long?,
        ts: Long,
        nonce: ByteArray,
        challenge: ByteArray,
    ): ByteArray {
        val parts = ArrayList<ByteArray>(8)
        parts.add("DLR/1".toByteArray())
        parts.add(byteArrayOf(0))
        parts.add(op.toByteArray())
        parts.add(byteArrayOf(0))
        parts.add(routeId)
        if (streamId != null) parts.add(streamId)
        if (generation != null) parts.add(be64(generation))
        parts.add(be64(ts))
        parts.add(nonce)
        parts.add(challenge)
        val size = parts.sumOf { it.size }
        val out = ByteArray(size)
        var offset = 0
        for (part in parts) {
            System.arraycopy(part, 0, out, offset, part.size)
            offset += part.size
        }
        return out
    }

    fun parseHostPort(address: String, defaultPort: Int): Pair<String, Int> {
        val raw = address.trim()
        require(raw.isNotEmpty()) { "missing relay address" }
        if (raw.startsWith("[")) {
            val end = raw.indexOf(']')
            require(end > 0) { "invalid relay address" }
            val host = raw.substring(1, end)
            val rest = raw.substring(end + 1)
            val port = if (rest.startsWith(":")) rest.substring(1).toInt() else defaultPort
            require(port in 1..65535)
            return host to port
        }
        val idx = raw.lastIndexOf(':')
        if (idx > 0 && raw.substring(idx + 1).all { it.isDigit() }) {
            val port = raw.substring(idx + 1).toInt()
            require(port in 1..65535)
            return raw.substring(0, idx) to port
        }
        return raw to defaultPort
    }

    private fun be64(value: Long): ByteArray =
        ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(value).array()
}
