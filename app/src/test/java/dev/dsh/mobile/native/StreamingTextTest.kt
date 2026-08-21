package dev.dsh.mobile.native

import org.junit.Assert.assertEquals
import org.junit.Test

class StreamingTextTest {
    @Test
    fun tokenize_splitsLatinOnSpaces() {
        assertEquals(listOf("Hello ", "world"), tokenizeStreaming("Hello world"))
    }

    @Test
    fun tokenize_splitsCjkPerCharacter() {
        assertEquals(listOf("你", "好", "世", "界"), tokenizeStreaming("你好世界"))
    }

    @Test
    fun tokenize_mixesLatinAndCjk() {
        assertEquals(listOf("Hello ", "世", "界"), tokenizeStreaming("Hello 世界"))
    }

    @Test
    fun tokenize_empty() {
        assertEquals(emptyList<String>(), tokenizeStreaming(""))
    }
}
