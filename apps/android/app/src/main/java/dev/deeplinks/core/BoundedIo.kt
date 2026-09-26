package dev.deeplinks.core

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

object BoundedIo {
    const val MAX_JSON_BODY_BYTES = 1_048_576
    const val MAX_SSE_LINE_BYTES = 1_048_576
    const val MAX_SSE_STREAM_BYTES = 8 * 1_048_576
    const val MAX_HEALTH_BODY_BYTES = 4_096
    const val MAX_WORKSPACE_FILE_BYTES = 8 * 1_048_576

    /** Copy untrusted content without buffering it in memory or allowing an unbounded file. */
    fun copy(stream: InputStream, sink: OutputStream, maxBytes: Long): Long {
        require(maxBytes >= 0) { "maxBytes must be non-negative" }
        val buf = ByteArray(8_192)
        var total = 0L
        while (true) {
            val n = stream.read(buf)
            if (n < 0) break
            if (total > maxBytes - n) throw IOException("input too large")
            sink.write(buf, 0, n)
            total += n
        }
        return total
    }

    fun readBytes(stream: InputStream, maxBytes: Int = MAX_WORKSPACE_FILE_BYTES): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8_192)
        var total = 0
        while (true) {
            val n = stream.read(buf)
            if (n < 0) break
            total += n
            if (total > maxBytes) throw IOException("response too large")
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    fun readText(stream: InputStream, maxBytes: Int = MAX_JSON_BODY_BYTES): String {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8_192)
        var total = 0
        while (true) {
            val n = stream.read(buf)
            if (n < 0) break
            total += n
            if (total > maxBytes) throw IOException("response too large")
            out.write(buf, 0, n)
        }
        return out.toString(Charsets.UTF_8.name())
    }

    fun readLine(stream: InputStream, maxBytes: Int = MAX_SSE_LINE_BYTES): String? {
        val out = ByteArrayOutputStream()
        while (true) {
            val b = stream.read()
            if (b < 0) {
                if (out.size() == 0) return null
                break
            }
            if (b == '\n'.code) break
            if (b != '\r'.code) out.write(b)
            if (out.size() > maxBytes) throw IOException("sse line too large")
        }
        return out.toString(Charsets.UTF_8.name())
    }
}
