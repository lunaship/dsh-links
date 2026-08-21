package dev.dsh.mobile.native

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

class CodeHighlightTest {

    @Test
    fun tokenColor_knownTypes_resolveToDarkSyntaxColors() {
        assertEquals(Color(0xFFFF7B72), tokenColor("keyword", true))
        assertEquals(Color(0xFFA5D6FF), tokenColor("string", true))
        assertEquals(Color(0xFF8B949E), tokenColor("comment", true))
        assertEquals(Color(0xFFD2A8FF), tokenColor("function", true))
        assertEquals(Color(0xFF79C0FF), tokenColor("number", true))
        assertEquals(Color(0xFF7EE787), tokenColor("tag", true))
    }

    @Test
    fun tokenColor_lightTheme_resolvesToLightSyntaxColors() {
        assertEquals(Color(0xFFD73A49), tokenColor("keyword", false))
        assertEquals(Color(0xFF0A3069), tokenColor("string", false))
    }

    @Test
    fun tokenColor_unknownType_fallsBackToDefaultText() {
        assertEquals(Color(0xFFC9D1D9), tokenColor("definitely-unknown-type", true))
        assertEquals(Color(0xFF24292E), tokenColor("definitely-unknown-type", false))
    }

    @Test
    fun tokenColor_aliasTypes_resolveCorrectly() {
        assertEquals(Color(0xFFFF7B72), tokenColor("atrule", true))
        assertEquals(Color(0xFFA5D6FF), tokenColor("char", true))
        assertEquals(Color(0xFFD2A8FF), tokenColor("method", true))
    }

    @Test
    fun highlightCode_preservesTextAndHandlesLanguages() {
        val samples = mapOf(
            "kt" to "fun main() { val x = 1 // hi\n println(x) }",
            "py" to "def f():\n    return 1  # c\n",
            "js" to "const a = `t\${1}`; /* b */",
            "sql" to "SELECT * FROM t -- c\nWHERE x=1",
        )
        for ((lang, src) in samples) {
            val result = highlightCode(src, lang, true)
            assertEquals(src, result.text)
        }
    }
}
