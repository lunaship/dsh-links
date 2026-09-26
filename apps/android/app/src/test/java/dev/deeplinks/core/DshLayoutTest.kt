package dev.deeplinks.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 表驱动布局推导测试（对照 t3code layout.test.ts）：
 * 手机竖/横、折叠、平板都要落在正确外壳，且宽度 clamp 稳定。
 */
class DshLayoutTest {

    @Test
    fun shellsFollowAvailableSpace() {
        // (width, height, expected shell, note)
        val cases = listOf(
            Triple(360, 800, DshShell.Compact) to "手机竖屏",
            Triple(412, 915, DshShell.Compact) to "常见手机",
            Triple(599, 900, DshShell.Compact) to "刚好低于 medium 阈值",
            Triple(600, 900, DshShell.Medium) to "medium 下界",
            Triple(800, 1280, DshShell.Medium) to "折叠展开/小平板（宽够但未到 expanded）",
            Triple(839, 900, DshShell.Medium) to "刚好低于 expanded 阈值",
            Triple(840, 900, DshShell.Expanded) to "expanded 下界",
            Triple(1000, 700, DshShell.Expanded) to "宽屏窗口",
            Triple(900, 599, DshShell.Medium) to "宽但矮：高度下限把它留在 medium",
            Triple(900, 600, DshShell.Expanded) to "高度刚好达下界",
            Triple(0, 0, DshShell.Compact) to "非法尺寸退化为 compact",
        )
        for ((triple, note) in cases) {
            val (width, height, expected) = triple
            val layout = deriveDshLayout(width, height)
            assertEquals(note + " (" + width + "x" + height + ")", expected, layout.shell)
        }
    }

    @Test
    fun listPaneWidthIsClamped() {
        assertEquals(DSH_LIST_PANE_MIN_DP, deriveListPaneWidth(0))
        assertEquals(DSH_LIST_PANE_MIN_DP, deriveListPaneWidth(600))   // 192 -> 240
        assertEquals(256, deriveListPaneWidth(800))                    // 256
        assertEquals(DSH_LIST_PANE_MAX_DP, deriveListPaneWidth(1920))  // 614 -> 380
    }

    @Test
    fun contentWidthIsCappedOnlyOnWideScreens() {
        assertEquals(412, constrainDshContentWidth(412))
        assertEquals(DSH_CONTENT_MAX_DP, constrainDshContentWidth(1200))
        assertEquals(0, constrainDshContentWidth(-10))
    }

    @Test
    fun onlyExpandedKeepsTheSidebarPersistent() {
        // Compact：纯内容 + 临时面板
        assertFalse(deriveDshLayout(412, 915).persistentSidebar)
        assertFalse(deriveDshLayout(412, 915).railNavigation)
        // Medium：窄 Rail + 临时面板，不常驻完整列表
        val medium = deriveDshLayout(700, 1000)
        assertFalse(medium.persistentSidebar)
        assertTrue(medium.railNavigation)
        // 宽但矮（横屏手机）也落在 Medium：仍不常驻侧栏
        val wideShort = deriveDshLayout(900, 599)
        assertEquals(DshShell.Medium, wideShort.shell)
        assertFalse(wideShort.persistentSidebar)
        assertTrue(wideShort.railNavigation)
        // Expanded：常驻侧栏 master-detail
        val expanded = deriveDshLayout(1200, 900)
        assertTrue(expanded.persistentSidebar)
        assertFalse(expanded.railNavigation)
    }

    @Test
    fun mediumContentStaysWiderThanAPersistentListPane() {
        // 700dp 窗口：如果保留 240dp 常驻侧栏，内容只剩 460dp。Rail 只占 72dp。
        val layout = deriveDshLayout(700, 1000)
        assertTrue(700 - DSH_RAIL_WIDTH_DP > 700 - layout.listPaneWidthDp)
    }
}
