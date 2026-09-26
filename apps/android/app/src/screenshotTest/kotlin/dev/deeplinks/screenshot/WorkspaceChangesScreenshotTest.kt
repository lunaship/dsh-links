package dev.deeplinks.screenshot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import dev.deeplinks.core.DarkDshColors
import dev.deeplinks.core.Dsh
import dev.deeplinks.core.DshFontFamily
import dev.deeplinks.core.DshStringsEn
import dev.deeplinks.core.DshStringsZh
import dev.deeplinks.core.DshType
import dev.deeplinks.core.LightDshColors
import dev.deeplinks.core.LocalDshColors
import dev.deeplinks.core.LocalDshFontFamily
import dev.deeplinks.core.LocalDshStrings
import dev.deeplinks.core.dshTypography
import dev.deeplinks.native.ChangedFile
import dev.deeplinks.native.ChangesPanelState
import dev.deeplinks.native.DiffHunk
import dev.deeplinks.native.WorkspaceChangesCard
import dev.deeplinks.native.WorkspaceChangesPanel
import dev.deeplinks.native.WorkspaceChangesSummary
import dev.deeplinks.native.WorkspaceFileDiff
import dev.deeplinks.native.WorkspaceTopBar

/** 本轮改动：轮末卡片、顶栏入口、审查面（列表 / 对比，手机全屏与宽屏贴右）。 */

@Composable
private fun ChangesFrame(dark: Boolean, english: Boolean = false, content: @Composable () -> Unit) {
    val typography = dshTypography(DshFontFamily)
    MaterialTheme(typography = typography) {
        CompositionLocalProvider(
            LocalDshColors provides if (dark) DarkDshColors else LightDshColors,
            LocalDshStrings provides if (english) DshStringsEn else DshStringsZh,
            LocalDshFontFamily provides DshFontFamily,
            LocalTextStyle provides typography.bodyMedium,
        ) {
            Box(modifier = Modifier.fillMaxSize().background(Dsh.bgBase)) { content() }
        }
    }
}

private val multi = WorkspaceChangesSummary(
    seq = 120,
    turn = 7,
    total = 6,
    added = 148,
    deleted = 37,
    files = listOf(
        ChangedFile("../dsh-links/src/workspace-changes.js", "../dsh-links/src/workspace-changes.js", added = 118),
        ChangedFile("app/src/main/java/dev/dsh/mobile/native/WorkspaceChangesPanel.kt", "app/src/main/java/dev/dsh/mobile/native/WorkspaceChangesPanel.kt", added = 22, deleted = 9),
        ChangedFile("docs/ui-parity.md", "docs/ui-parity.md", added = 8, deleted = 28),
        ChangedFile("branding/icon.png", "branding/icon.png", binary = true),
        ChangedFile("build/outputs/bundle.map", "build/outputs/bundle.map", oversized = true),
        ChangedFile("README.md", "README.md"),
    ),
)

private val single = WorkspaceChangesSummary(
    seq = 88,
    turn = 5,
    total = 1,
    added = 3,
    deleted = 1,
    files = listOf(ChangedFile("src/history.js", "src/history.js", added = 3, deleted = 1)),
)

private val sampleDiff = WorkspaceFileDiff.Text(
    path = multi.files[1].path,
    display = multi.files[1].display,
    before = true,
    after = true,
    coarse = false,
    hunks = listOf(
        DiffHunk(
            oldStart = 118, oldLines = 6, newStart = 118, newLines = 8,
            lines = listOf(
                " internal class ChangesPanelState(initialProgress: Float = 0f) {",
                "-    val progress = Animatable(0f)",
                "+    /** 0 = 收起，1 = 完全展开；拖动时跟手，松手后动画到端点。 */",
                "+    val progress = Animatable(initialProgress)",
                "+    var seq by mutableStateOf<Long?>(null)",
                "     var fileIndex by mutableStateOf<Int?>(null)",
                "     var wrap by mutableStateOf(true)",
                "-    val visible: Boolean get() = progress.value > 0f",
                "+    val visible: Boolean get() = progress.value > 0f || progress.targetValue > 0f",
            ),
        ),
    ),
)

@Composable
private fun CardWall() {
    Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Top bar", color = Dsh.labelTertiary, style = DshType.label)
        WorkspaceTopBar(
            running = false,
            title = "对照 Paseo 设计左滑改动面板",
            onOpenDrawer = {},
            viewMode = "chat",
            onSelectViewMode = {},
            menuExpanded = false,
            onMenuExpandedChange = {},
            menuItems = emptyList(),
            latestChanges = multi,
        )
        Text("Multi-file card", color = Dsh.labelTertiary, style = DshType.label)
        WorkspaceChangesCard(summary = multi, onOpen = {})
        Text("Single-file card", color = Dsh.labelTertiary, style = DshType.label)
        WorkspaceChangesCard(summary = single, onOpen = {})
    }
}

@Composable
private fun PanelShot(fileIndex: Int?, wrap: Boolean = true) {
    val state = remember {
        ChangesPanelState(initialProgress = 1f).apply {
            seq = multi.seq
            this.fileIndex = fileIndex
            this.wrap = wrap
            if (fileIndex != null) cacheDiff(multi.seq, fileIndex, sampleDiff)
        }
    }
    WorkspaceChangesPanel(
        state = state,
        summaries = listOf(multi, single),
        loadSummary = { null },
        loadDiff = { _, _ -> sampleDiff },
    )
}

@PreviewTest
@Preview(name = "changes card light zh", showBackground = true, widthDp = 412, heightDp = 560)
@Composable
internal fun ChangesCardLightZh() {
    ChangesFrame(dark = false) { CardWall() }
}

@PreviewTest
@Preview(name = "changes card dark en", showBackground = true, widthDp = 412, heightDp = 560)
@Composable
internal fun ChangesCardDarkEn() {
    ChangesFrame(dark = true, english = true) { CardWall() }
}

@PreviewTest
@Preview(name = "changes panel list dark", showBackground = true, widthDp = 412, heightDp = 640)
@Composable
internal fun ChangesPanelListDark() {
    ChangesFrame(dark = true) { PanelShot(fileIndex = null) }
}

@PreviewTest
@Preview(name = "changes panel diff light", showBackground = true, widthDp = 412, heightDp = 640)
@Composable
internal fun ChangesPanelDiffLight() {
    ChangesFrame(dark = false) { PanelShot(fileIndex = 1) }
}

@PreviewTest
@Preview(name = "changes panel diff wide dark", showBackground = true, widthDp = 900, heightDp = 600)
@Composable
internal fun ChangesPanelDiffWideDark() {
    ChangesFrame(dark = true) { PanelShot(fileIndex = 1, wrap = false) }
}
