package dev.deeplinks.native

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingTextTest {
    @Test
    fun closeMarkdown_closesDanglingBold() {
        assertEquals("**bol**", closeStreamingMarkdown("**bol"))
    }

    @Test
    fun closeMarkdown_leavesBalancedBoldAlone() {
        assertEquals("**bold** tail", closeStreamingMarkdown("**bold** tail"))
    }

    @Test
    fun closeMarkdown_closesDanglingCodeFirst() {
        // 代码内的 ** 不参与配对：先闭合反引号即可
        assertEquals("see `a**b`", closeStreamingMarkdown("see `a**b"))
    }

    @Test
    fun closeMarkdown_ignoresMarkersInsideCodeSpans() {
        assertEquals("`**` and **x**", closeStreamingMarkdown("`**` and **x"))
    }

    @Test
    fun closeMarkdown_closesStrike() {
        assertEquals("~~gone~~", closeStreamingMarkdown("~~gone"))
    }

    @Test
    fun closeMarkdown_doesNotTouchSingleAsterisk() {
        assertEquals("2*3 = 6", closeStreamingMarkdown("2*3 = 6"))
    }

    @Test
    fun fadeAlpha_startsHiddenAndSettles() {
        assertEquals(0f, streamFadeAlpha(0), 0f)
        assertEquals(1f, streamFadeAlpha(220), 0f)
        assertEquals(1f, streamFadeAlpha(10_000), 0f)
    }

    @Test
    fun fadeAlpha_isMonotonicAndQuantized() {
        var last = 0f
        for (age in 0L..220L step 10) {
            val a = streamFadeAlpha(age)
            assertTrue("age=$age", a >= last)
            // 5 档量化：只可能是 0, .2, .4, .6, .8, 1
            assertEquals(0f, (a * 5) - Math.round(a * 5), 1e-4f)
            last = a
        }
    }

    @Test
    fun streamTail_noSpansNoCaretReturnsSameInstance() {
        val base = AnnotatedString("hello")
        assertSame(base, base.withStreamTail(emptyList(), Color.Black, caret = false))
    }

    @Test
    fun streamTail_appendsCaretAndClampsSpans() {
        val base = AnnotatedString("hello")
        val out = base.withStreamTail(listOf(StreamFadeSpan(3, 99, 0.4f)), Color.Black, caret = true)
        // 光标以 inline content 追加一个占位字符
        assertEquals("hello▍", out.text)
        val span = out.spanStyles.single()
        assertEquals(3, span.start)
        assertEquals(5, span.end)
        assertEquals(0.4f, span.item.color.alpha, 1e-3f)
    }
}
