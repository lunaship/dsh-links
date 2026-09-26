package dev.deeplinks.native.util

import org.junit.Assert.assertEquals
import org.junit.Test

class WorkspacePathsTest {
    @Test
    fun abbreviateHomePath_macosUsers() {
        assertEquals("~/Dev/dsh-links", abbreviateHomePath("/Users/me/Dev/dsh-links"))
        assertEquals("~", abbreviateHomePath("/Users/me"))
    }

    @Test
    fun abbreviateHomePath_linuxHome() {
        assertEquals("~/proj", abbreviateHomePath("/home/foo/proj"))
    }

    @Test
    fun abbreviateHomePath_leavesVolumesAndRelative() {
        assertEquals("/Volumes/Space/Dev/dsh-links", abbreviateHomePath("/Volumes/Space/Dev/dsh-links"))
        assertEquals("relative/path", abbreviateHomePath("relative/path"))
        assertEquals("", abbreviateHomePath(null))
        assertEquals("", abbreviateHomePath("  "))
    }

    @Test
    fun producedFileName_usesLastSegment() {
        assertEquals("shot.png", producedFileName("notes/shot.png"))
        assertEquals("hi.md", producedFileName("hi.md"))
    }

    @Test
    fun producedFileKind_classifiesByExtension() {
        assertEquals(ProducedFileKind.IMAGE, producedFileKind("a/b.PNG"))
        assertEquals(ProducedFileKind.TEXT, producedFileKind("notes/hi.md"))
        assertEquals(ProducedFileKind.OTHER, producedFileKind("bin/out"))
    }

    @Test
    fun decodeProducedText_truncates() {
        assertEquals("hello", decodeProducedText("hello".toByteArray()))
        assertEquals("ab…", decodeProducedText("abcdef".toByteArray(), maxChars = 2))
    }

    @Test
    fun isProducedTextMime_acceptsJsonAndMarkdown() {
        assertEquals(true, isProducedTextMime("text/plain; charset=utf-8"))
        assertEquals(true, isProducedTextMime("application/json"))
        assertEquals(false, isProducedTextMime("application/pdf"))
    }
}
