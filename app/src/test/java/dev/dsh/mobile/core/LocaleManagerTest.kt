package dev.dsh.mobile.core

import org.junit.Assert.assertEquals
import org.junit.Test

class LocaleManagerTest {
    @Test
    fun normalize_mapsEnglishVariantsToEn() {
        assertEquals("en", LocaleManager.normalize("en"))
        assertEquals("en", LocaleManager.normalize("EN"))
        assertEquals("en", LocaleManager.normalize("en-US"))
    }

    @Test
    fun normalize_defaultsToZh() {
        assertEquals("zh", LocaleManager.normalize("zh"))
        assertEquals("zh", LocaleManager.normalize("zh-CN"))
        assertEquals("zh", LocaleManager.normalize(null))
        assertEquals("zh", LocaleManager.normalize(""))
        assertEquals("zh", LocaleManager.normalize("fr"))
    }

    @Test
    fun strings_switchWithLanguage() {
        assertEquals("Settings", DshStringsEn.settingsTitle)
        assertEquals("设置", DshStringsZh.settingsTitle)
        assertEquals("Paired devices", DshStringsEn.pairingManage)
        assertEquals("配对管理", DshStringsZh.pairingManage)
        assertEquals("Thinking", DshStringsEn.thinkingActive)
        assertEquals("思考中", DshStringsZh.thinkingActive)
        assertEquals("Thought for %d seconds", DshStringsEn.thoughtForSeconds)
        assertEquals("已思考 %d 秒", DshStringsZh.thoughtForSeconds)
    }
}
