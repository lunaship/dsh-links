package dev.deeplinks.core

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DshContrastTest {

    @Test
    fun blackOnWhiteIsMaximumContrast() {
        assertEquals(21.0, contrastRatio(Color.Black, Color.White), 0.05)
        assertEquals(1.0, contrastRatio(Color.White, Color.White), 0.001)
    }

    @Test
    fun readableColorKeepsAccentWhenItAlreadyPasses() {
        val accent = Color(0xFF2563D8)
        assertEquals(accent, readableTextColor(accent, listOf(Color.White)))
    }

    @Test
    fun readableColorEscapesLowContrast() {
        val paleAccent = Color(0xFFDDDDDD)
        val fixed = readableTextColor(paleAccent, listOf(Color.White))
        assertTrue("got " + contrastRatio(fixed, Color.White), contrastRatio(fixed, Color.White) >= 4.5)
    }

    @Test
    fun documentedTextPairsMeetAa() {
        val pairs = listOf(
            "light labelPrimary/bgBase" to (LightDshColors.labelPrimary to LightDshColors.bgBase),
            "light labelSecondary/bgBase" to (LightDshColors.labelSecondary to LightDshColors.bgBase),
            "light brand400/bgBase" to (LightDshColors.brand400 to LightDshColors.bgBase),
            "dark labelPrimary/bgBase" to (DarkDshColors.labelPrimary to DarkDshColors.bgBase),
            "dark labelSecondary/bgBase" to (DarkDshColors.labelSecondary to DarkDshColors.bgBase),
            "dark brand400/bgBase" to (DarkDshColors.brand400 to DarkDshColors.bgBase),
        )
        for ((name, pair) in pairs) {
            val ratio = contrastRatio(pair.first, pair.second)
            assertTrue(name + " ratio=" + ratio, ratio >= 4.5)
        }
    }

    @Test
    fun tonalSeparationBetweenSurfaceLayers() {
        // 「深色卡片和页底糊在一起」的机器判据：层与层至少 1.05 分离。
        assertTrue(contrastRatio(DarkDshColors.bgSubtle, DarkDshColors.bgBase) >= 1.05)
        assertTrue(contrastRatio(LightDshColors.bgSubtle, LightDshColors.bgBase) >= 1.05)
    }

    @Test
    fun flattenCompositesTranslucentOverSurface() {
        val flat = flattenThemeColor(Color(0f, 0f, 0f, 0.5f), Color.White)
        assertEquals(1f, flat.alpha, 0.001f)
        assertEquals(0.5f, flat.red, 0.01f)
        assertEquals(1f, flattenThemeColor(Color.Red, Color.White).alpha, 0.001f)
    }
}
