package dev.deeplinks.core

import org.junit.Assert.assertEquals
import org.junit.Test

class FontScaleTest {
    @Test
    fun canonicalize_keepsSmallAndLarge() {
        assertEquals(FontScaleManager.SMALL, canonicalizeFontScale("small"))
        assertEquals(FontScaleManager.LARGE, canonicalizeFontScale("large"))
    }

    @Test
    fun canonicalize_defaultsUnknown() {
        assertEquals(FontScaleManager.DEFAULT, canonicalizeFontScale(null))
        assertEquals(FontScaleManager.DEFAULT, canonicalizeFontScale(""))
        assertEquals(FontScaleManager.DEFAULT, canonicalizeFontScale("xlarge"))
        assertEquals(FontScaleManager.DEFAULT, canonicalizeFontScale("default"))
    }

    @Test
    fun multiplier_matchesDeepSeekStyleSteps() {
        assertEquals(0.88f, fontScaleMultiplier("small"), 0.001f)
        assertEquals(1f, fontScaleMultiplier("default"), 0.001f)
        assertEquals(1.18f, fontScaleMultiplier("large"), 0.001f)
        assertEquals(1f, fontScaleMultiplier(null), 0.001f)
    }
}
