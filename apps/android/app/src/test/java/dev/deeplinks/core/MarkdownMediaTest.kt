package dev.deeplinks.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownMediaTest {

    @Test
    fun `https public host is allowed`() {
        assertTrue(MarkdownMedia.isSafeImageUrl("https://example.com/a.png"))
        assertTrue(MarkdownMedia.isSafeImageUrl("https://cdn.example.org/img.jpg?x=1"))
    }

    @Test
    fun `non https and credentials are blocked`() {
        assertFalse(MarkdownMedia.isSafeImageUrl("http://example.com/a.png"))
        assertFalse(MarkdownMedia.isSafeImageUrl("file:///sdcard/a.png"))
        assertFalse(MarkdownMedia.isSafeImageUrl("content://media/1"))
        assertFalse(MarkdownMedia.isSafeImageUrl("https://user:pass@example.com/a.png"))
        assertNull(MarkdownMedia.takeIfSafe("http://10.0.0.1/x.png"))
    }

    @Test
    fun `private and metadata destinations are blocked`() {
        assertFalse(MarkdownMedia.isSafeImageUrl("https://127.0.0.1/a.png"))
        assertFalse(MarkdownMedia.isSafeImageUrl("https://10.0.0.8/a.png"))
        assertFalse(MarkdownMedia.isSafeImageUrl("https://192.168.1.1/a.png"))
        assertFalse(MarkdownMedia.isSafeImageUrl("https://172.16.0.1/a.png"))
        assertFalse(MarkdownMedia.isSafeImageUrl("https://169.254.169.254/latest/meta-data"))
        assertFalse(MarkdownMedia.isSafeImageUrl("https://100.64.0.1/a.png"))
        assertFalse(MarkdownMedia.isSafeImageUrl("https://localhost/a.png"))
        assertFalse(MarkdownMedia.isSafeImageUrl("https://metadata.google.internal/"))
        assertFalse(MarkdownMedia.isSafeImageUrl("https://foo.local/a.png"))
        assertFalse(MarkdownMedia.isSafeImageUrl("https://[::1]/a.png"))
        assertFalse(MarkdownMedia.isSafeImageUrl("https://2130706433/a.png"))
        assertFalse(MarkdownMedia.isSafeImageUrl("https://0x7f000001/a.png"))
        assertFalse(MarkdownMedia.isSafeImageUrl("https://127.1/a.png"))
        assertFalse(MarkdownMedia.isSafeImageUrl("https://0177.0.0.1/a.png"))
        assertFalse(MarkdownMedia.isSafeImageUrl("https://2852039166/latest/meta-data"))
    }

    @Test
    fun `publicOnlyDns 拒绝回环与私网解析结果`() {
        try {
            MarkdownMedia.publicOnlyDns.lookup("localhost")
            org.junit.Assert.fail("localhost should be blocked")
        } catch (e: java.net.UnknownHostException) {
            assertTrue(e.message?.contains("blocked") == true || e.message != null)
        }
        try {
            MarkdownMedia.publicOnlyDns.lookup("127.0.0.1")
            org.junit.Assert.fail("loopback IP should be blocked")
        } catch (e: java.net.UnknownHostException) {
            assertTrue(e.message != null)
        }
    }
}
