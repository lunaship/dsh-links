package dev.deeplinks.core

/**
 * 自适应布局推导（对照 t3code lib/layout.ts）：
 * **用可用空间而不是设备/方向标签来决定外壳**，且全部是纯函数，可表驱动单测。
 *
 * 阈值对齐 Android 窗口尺寸类别，并额外加高度下限（与 t3code 的
 * SPLIT_LAYOUT_MIN_HEIGHT 同思路）：横屏手机不应该因为"够宽"就变成双栏。
 */
enum class DshShell { Compact, Medium, Expanded }

data class DshLayout(
    val shell: DshShell,
    /** 只有 Expanded 把列表常驻；Compact/Medium 都走临时面板。 */
    val persistentSidebar: Boolean,
    /** Medium 用窄 Navigation Rail 常驻入口，会话列表临时展开。 */
    val railNavigation: Boolean,
    /** 常驻侧栏宽度（dp）。 */
    val listPaneWidthDp: Int,
    /** 内容列最大宽度（dp），手机无上限时由调用方 fillMaxWidth。 */
    val contentMaxWidthDp: Int,
)

const val DSH_MEDIUM_MIN_WIDTH_DP = 600
const val DSH_EXPANDED_MIN_WIDTH_DP = 840
const val DSH_EXPANDED_MIN_HEIGHT_DP = 600
const val DSH_LIST_PANE_MIN_DP = 240
const val DSH_LIST_PANE_MAX_DP = 380
const val DSH_CONTENT_MAX_DP = 760

/** Rail 宽度：M3 NavigationRail 默认 80dp；收窄到 72dp 让 600–839dp 的内容更宽。 */
const val DSH_RAIL_WIDTH_DP = 72

/**
 * 三档外壳：
 * - Compact（<600dp）：只有内容，会话列表是临时面板；
 * - Medium（600–839dp，或够宽但不够高）：窄 Rail + 临时会话面板，内容不被 240dp
 *   固定侧栏挤压（700dp 窗口里 240dp 列表占三分之一，读起来像 Web 控制台）；
 * - Expanded（>=840dp 且 >=600dp 高）：常驻侧栏的 master-detail。
 */
fun deriveDshLayout(containerWidthDp: Int, containerHeightDp: Int): DshLayout {
    val width = containerWidthDp.coerceAtLeast(0)
    val height = containerHeightDp.coerceAtLeast(0)
    val shell = when {
        width < DSH_MEDIUM_MIN_WIDTH_DP -> DshShell.Compact
        width < DSH_EXPANDED_MIN_WIDTH_DP || height < DSH_EXPANDED_MIN_HEIGHT_DP -> DshShell.Medium
        else -> DshShell.Expanded
    }
    return DshLayout(
        shell = shell,
        persistentSidebar = shell == DshShell.Expanded,
        railNavigation = shell == DshShell.Medium,
        listPaneWidthDp = deriveListPaneWidth(width),
        contentMaxWidthDp = DSH_CONTENT_MAX_DP,
    )
}

/** 32% 宽、clamp 在 [240, 380] dp（与 t3code clamp(round(width*0.32), 280, 380) 同形）。 */
fun deriveListPaneWidth(containerWidthDp: Int): Int {
    val width = containerWidthDp.coerceAtLeast(0)
    return (width * 0.32f).toInt().coerceIn(DSH_LIST_PANE_MIN_DP, DSH_LIST_PANE_MAX_DP)
}

/** 内容列封顶，避免平板把手机布局无限拉宽。 */
fun constrainDshContentWidth(availableWidthDp: Int): Int {
    val width = availableWidthDp.coerceAtLeast(0)
    return minOf(width, DSH_CONTENT_MAX_DP)
}
