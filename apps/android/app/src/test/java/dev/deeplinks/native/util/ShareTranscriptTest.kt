package dev.deeplinks.native.util

import dev.deeplinks.native.MobileMessage
import dev.deeplinks.core.DshStringsEn
import dev.deeplinks.core.DshStringsZh
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareTranscriptTest {
    @Test
    fun flatten_replacesFencesAndTruncates() {
        val text = """
            先看图
            ```mermaid
            graph TD; A-->B
            ```
            再看代码
            ```kotlin
            fun x() = 1
            ```
        """.trimIndent()
        val flat = flattenShareText(text)
        assertTrue(flat.contains(DshStringsZh.shareFenceDiagram))
        assertTrue(flat.contains(DshStringsZh.shareFenceCode))
        assertTrue(!flat.contains("graph TD"))
        val en = flattenShareText(text, strings = DshStringsEn)
        assertTrue(en.contains(DshStringsEn.shareFenceDiagram))
        assertTrue(en.contains(DshStringsEn.shareFenceCode))
    }

    @Test
    fun stripIncompleteFence_dropsOpenTail() {
        val text = "hello\n```mermaid\ngraph TD"
        assertEquals("hello", stripIncompleteFence(text))
    }

    @Test
    fun selectShareTurns_takesLastUserAssistant() {
        val msgs = listOf(
            MobileMessage("1", "user", "第一问"),
            MobileMessage("2", "assistant", "第一答"),
            MobileMessage("3", "tool_call", "ignored"),
            MobileMessage("4", "user", "第二问"),
            MobileMessage("5", "assistant", "第二答", running = true),
        )
        val turns = selectShareTurns(msgs, maxTurns = 8)
        assertEquals(listOf("第一问", "第一答", "第二问"), turns.map { it.text })
        assertEquals(listOf("user", "assistant", "user"), turns.map { it.role })
    }

    @Test
    fun flatten_truncatesLongText() {
        val flat = flattenShareText("a".repeat(500), maxChars = 20)
        assertEquals(20, flat.length)
        assertTrue(flat.endsWith("…"))
    }
}
