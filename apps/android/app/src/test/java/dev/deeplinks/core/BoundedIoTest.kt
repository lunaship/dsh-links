package dev.deeplinks.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException

class BoundedIoTest {
    @Test
    fun `readText respects byte ceiling`() {
        val body = "hello"
        assertEquals(body, BoundedIo.readText(ByteArrayInputStream(body.toByteArray()), 16))
        try {
            BoundedIo.readText(ByteArrayInputStream(ByteArray(32) { 'a'.code.toByte() }), 8)
            org.junit.Assert.fail("expected overflow")
        } catch (e: IOException) {
            assertEquals("response too large", e.message)
        }
    }

    @Test
    fun `readLine is bounded and keeps later lines unread`() {
        val stream = ByteArrayInputStream("one\ntwo\n".toByteArray())
        assertEquals("one", BoundedIo.readLine(stream, 16))
        assertEquals("two", BoundedIo.readLine(stream, 16))
        assertNull(BoundedIo.readLine(stream, 16))
        try {
            BoundedIo.readLine(ByteArrayInputStream(ByteArray(32) { 'x'.code.toByte() }), 4)
            org.junit.Assert.fail("expected overflow")
        } catch (e: IOException) {
            assertEquals("sse line too large", e.message)
        }
    }

    @Test
    fun `copy streams content within the limit`() {
        val output = ByteArrayOutputStream()
        val copied = BoundedIo.copy(ByteArrayInputStream("hello".toByteArray()), output, 5)

        assertEquals(5L, copied)
        assertEquals("hello", output.toString(Charsets.UTF_8.name()))
    }

    @Test
    fun `copy rejects content past the limit`() {
        try {
            BoundedIo.copy(ByteArrayInputStream(ByteArray(6)), ByteArrayOutputStream(), 5)
            org.junit.Assert.fail("expected overflow")
        } catch (e: IOException) {
            assertEquals("input too large", e.message)
        }
    }
}
