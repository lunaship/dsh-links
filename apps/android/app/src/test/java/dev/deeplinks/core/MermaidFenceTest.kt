package dev.deeplinks.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MermaidFenceTest {
    @Test
    fun isMermaidLang_acceptsFenceAliases() {
        assertTrue(MermaidFence.isMermaidLang("mermaid"))
        assertTrue(MermaidFence.isMermaidLang("MERMAID"))
        assertTrue(MermaidFence.isMermaidLang(" mermaid "))
        assertTrue(MermaidFence.isMermaidLang("mermaid flowchart"))
        assertFalse(MermaidFence.isMermaidLang("kotlin"))
        assertFalse(MermaidFence.isMermaidLang(null))
        assertFalse(MermaidFence.isMermaidLang("notmermaid"))
    }

    @Test
    fun sourceForRender_rejectsEmptyAndOversize() {
        assertNull(MermaidFence.sourceForRender("  "))
        assertNull(MermaidFence.sourceForRender("x".repeat(MermaidFence.MAX_SOURCE_CHARS + 1)))
        assertEquals(
            "graph TD; A-->B",
            MermaidFence.sourceForRender("  graph TD; A-->B\n"),
        )
    }

    @Test
    fun looksRenderable_requiresDiagramHead() {
        assertFalse(MermaidFence.looksRenderable("gra"))
        assertTrue(MermaidFence.looksRenderable("graph TD\nA-->B"))
        assertTrue(MermaidFence.looksRenderable("sequenceDiagram\nA->>B: hi"))
        assertFalse(MermaidFence.looksRenderable("not a diagram at all here"))
    }
}
