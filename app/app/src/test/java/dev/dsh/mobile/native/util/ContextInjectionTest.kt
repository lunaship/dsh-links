package dev.dsh.mobile.native.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextInjectionTest {
    @Test
    fun `skill catalog reminder is context injection`() {
        val text = """
            <system-reminder>
            The available skill catalog changed. This complete catalog replaces every earlier available-skills list in this session:
            <available_skills>
            agent-reach: 全网调研
            </available_skills>
            </system-reminder>
        """.trimIndent()
        assertTrue(isContextInjectionText(text))
        assertEquals(listOf("skill-catalog"), contextInjectionLabels(text))
    }

    @Test
    fun `plain user hello is not injection`() {
        assertFalse(isContextInjectionText("你好"))
        assertFalse(isContextInjectionText(""))
    }

    @Test
    fun `html-escaped reminder still counts`() {
        assertTrue(isContextInjectionText("&lt;system-reminder&gt;hi&lt;/system-reminder&gt;"))
    }

    @Test
    fun `runtime context block is context injection`() {
        val text = """
            Current runtime context:
            - Host OS: macOS
            - Current DSH file policy: danger-full-access
            - Approval prompts are disabled
        """.trimIndent()
        assertTrue(isContextInjectionText(text))
        assertTrue(contextInjectionLabels(text).contains("runtime"))
        assertTrue(contextInjectionLabels(text).contains("file-policy"))
    }
}
