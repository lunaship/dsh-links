package dev.deeplinks.native

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposerMetricsTest {
    @Test
    fun compactBelow360dp() {
        assertTrue(composerIsCompact(320f))
        assertTrue(composerIsCompact(359.9f))
        assertFalse(composerIsCompact(360f))
        assertFalse(composerIsCompact(780f))
    }

    @Test
    fun seatsCompactUsesTheInputCardWidthNotTheWindowWidth() {
        // 412dp 手机：卡片可用宽 388dp，两个座位都带文字
        assertFalse(composerSeatsCompact(412f))
        // 小屏 / 分屏：卡片可用宽 336dp，座位只留图标
        assertTrue(composerSeatsCompact(320f))
        assertTrue(composerSeatsCompact(383f))
        assertFalse(composerSeatsCompact(384f))
    }

    @Test
    fun modelSeatSplitsNameAndEffort() {
        val catalog = MobileModelCatalog(
            currentProvider = "deepseek-official",
            currentModel = "deepseek-v4-flash",
            currentReasoningEffort = "high",
            groups = listOf(
                MobileModelGroup(
                    provider = "deepseek-official",
                    displayName = "DeepSeek 官方",
                    models = listOf(
                        MobileModelOption(
                            id = "deepseek-v4-flash",
                            name = "DeepSeek V4 Flash",
                            contextWindow = 1_000_000,
                            maxTokens = 384_000,
                            reasoningEfforts = listOf("low", "high"),
                            defaultEffort = "low",
                        ),
                    ),
                ),
            ),
        )
        val seat = composerModelSeat(catalog)
        assertEquals("DeepSeek V4 Flash", seat.name)
        assertEquals("high", seat.effort)
        // 无会话时 pending 优先（目录里只有 id 时有展示名就用展示名）
        val pendingSeat = composerModelSeat(catalog, Triple("deepseek-official", "deepseek-v4-flash", "low"))
        assertEquals("DeepSeek V4 Flash", pendingSeat.name)
        assertEquals("low", pendingSeat.effort)
        // 完全没选中：座位显示「选择模型」
        assertEquals(ComposerModelSeat(null, null), composerModelSeat(null))
        assertEquals("deepseek-v4-flash", composerModelSeat(null, Triple("p", "deepseek-v4-flash", null)).name)
    }

    @Test
    fun modelSeatFallsBackToTheAdvertisedDefaultEffort() {
        val catalog = MobileModelCatalog(
            currentModel = "m1",
            currentReasoningEffort = null,
            groups = listOf(
                MobileModelGroup(
                    provider = "p",
                    models = listOf(
                        MobileModelOption(id = "m1", name = "M1", contextWindow = null, maxTokens = null, defaultEffort = "medium"),
                    ),
                ),
            ),
        )
        assertEquals("medium", composerModelSeat(catalog).effort)
    }

    @Test
    fun accessSeatOnlyKnowsTheThreeSessionPresets() {
        assertEquals("read-only", canonicalComposerPermission("read-only"))
        assertEquals("danger-full-access", canonicalComposerPermission("danger-full-access"))
        assertEquals("workspace-write", canonicalComposerPermission("workspace-write"))
        // 表外值（旧值 / 自定义 / null）不得渲染成空白座位
        assertEquals("workspace-write", canonicalComposerPermission(null))
        assertEquals("workspace-write", canonicalComposerPermission("custom"))
        assertFalse(composerPermissionIsDanger("read-only"))
        assertFalse(composerPermissionIsDanger(null))
        assertTrue(composerPermissionIsDanger("danger-full-access"))
    }

    @Test
    fun setupRowOnlyWhileComposingNew() {
        assertTrue(composerShowsSetupRow(workspaceEditable = true, showHarness = true, harnessLabel = "标准模式"))
        assertTrue(composerShowsSetupRow(workspaceEditable = true, showHarness = false, harnessLabel = ""))
        assertFalse(composerShowsSetupRow(workspaceEditable = false, showHarness = false, harnessLabel = "标准模式"))
        assertFalse(composerShowsSetupRow(workspaceEditable = false, showHarness = true, harnessLabel = ""))
        assertTrue(composerShowsSetupRow(workspaceEditable = false, showHarness = true, harnessLabel = "标准模式"))
    }

    @Test
    fun actionErrorStaysOnComposerUntilNextSend() {
        assertTrue(composerShowsActionError("发送失败：timeout", sending = false))
        assertFalse(composerShowsActionError("发送失败：timeout", sending = true))
        assertFalse(composerShowsActionError(null, sending = false))
        assertFalse(composerShowsActionError("", sending = false))
    }

    @Test
    fun permissionChipFollowsSessionOverride() {
        assertEquals(
            "read-only",
            composerPermissionPreset("s1", mapOf("s1" to "read-only"), "workspace-write"),
        )
        assertEquals(
            "workspace-write",
            composerPermissionPreset("s2", mapOf("s1" to "read-only"), "workspace-write"),
        )
        assertEquals(
            "workspace-write",
            composerPermissionPreset(null, mapOf("s1" to "read-only"), "workspace-write"),
        )
    }

    @Test
    fun slashCompletableNeedsASession() {
        assertFalse(completableCanSubmit(hasSession = false))
        assertTrue(completableCanSubmit(hasSession = true))
    }
}
