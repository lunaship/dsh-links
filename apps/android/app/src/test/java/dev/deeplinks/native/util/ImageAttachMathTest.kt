package dev.deeplinks.native.util

import org.junit.Assert.assertEquals
import org.junit.Test

class ImageAttachMathTest {
    @Test
    fun scaledSize_keepsSmallImages() {
        assertEquals(800 to 600, scaledSize(800, 600, 2048))
    }

    @Test
    fun scaledSize_fitsLongEdge() {
        assertEquals(2048 to 1536, scaledSize(4000, 3000, 2048))
    }

    @Test
    fun inSampleSize_doublesUntilFit() {
        assertEquals(1, inSampleSize(1024, 768, 2048))
        assertEquals(2, inSampleSize(4000, 3000, 2048))
        assertEquals(4, inSampleSize(8192, 4096, 2048))
    }

    @Test
    fun mapViewToBitmap_identityWhenFittedSquare() {
        val (x, y) = mapViewToBitmap(0f, 0f, 100f, 100f, 100, 100, 1f, 0f, 0f)
        assertEquals(0f, x, 0.01f)
        assertEquals(0f, y, 0.01f)
        val (x1, y1) = mapViewToBitmap(100f, 100f, 100f, 100f, 100, 100, 1f, 0f, 0f)
        assertEquals(100f, x1, 0.01f)
        assertEquals(100f, y1, 0.01f)
    }

    @Test
    fun viewfinderCrop_clampsToBitmap() {
        val crop = viewfinderCropOnBitmap(100f, 100f, 100, 100, 1f, 0f, 0f)
        assertEquals(0, crop.left)
        assertEquals(0, crop.top)
        assertEquals(100, crop.width)
        assertEquals(100, crop.height)
    }

    @Test
    fun tableToTsv_joinsCells() {
        val tsv = tableToTsv(listOf(listOf("a", "b"), listOf("1", "2")))
        assertEquals("a\tb\n1\t2", tsv)
    }

    @Test
    fun tableToCsv_quotesCommaAndQuote() {
        val csv = tableToCsv(
            listOf(
                listOf("name", "note"),
                listOf("a,b", "say \"hi\""),
            ),
        )
        assertEquals("name,note\n\"a,b\",\"say \"\"hi\"\"\"", csv)
    }

    @Test
    fun tableToMarkdown_hasHeaderSep() {
        val md = tableToMarkdown(listOf(listOf("a", "b"), listOf("1", "2")))
        assertEquals("| a | b |\n| --- | --- |\n| 1 | 2 |", md)
    }
}
