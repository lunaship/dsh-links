package dev.deeplinks.native.util

import org.junit.Assert.assertEquals
import org.junit.Test

class MarkdownEntitiesTest {
    @Test
    fun decodeHtmlEntities_passthroughWithoutAmp() {
        assertEquals("hello", decodeHtmlEntities("hello"))
    }

    @Test
    fun decodeHtmlEntities_namedAndNumeric() {
        assertEquals("<a> \"b\"", decodeHtmlEntities("&lt;a&gt; &quot;b&quot;"))
        assertEquals("A", decodeHtmlEntities("&#65;"))
        assertEquals("€", decodeHtmlEntities("&#x20AC;"))
        assertEquals("&unknown;", decodeHtmlEntities("&unknown;"))
    }

    @Test
    fun decodeHtmlEntities_keepsInvalidNumericEntitiesLiteral() {
        assertEquals("bad &#x110000;", decodeHtmlEntities("bad &#x110000;"))
        assertEquals("bad &#xD800;", decodeHtmlEntities("bad &#xD800;"))
        assertEquals("bad &#nope;", decodeHtmlEntities("bad &#nope;"))
        assertEquals("bad &amp", decodeHtmlEntities("bad &amp"))
    }
}
