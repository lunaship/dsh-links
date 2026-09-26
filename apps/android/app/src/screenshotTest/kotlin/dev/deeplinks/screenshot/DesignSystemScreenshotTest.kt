package dev.deeplinks.screenshot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
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
import dev.deeplinks.core.DshS
import dev.deeplinks.core.LocalDshColors
import dev.deeplinks.core.LocalDshFontFamily
import dev.deeplinks.core.LocalDshStrings
import dev.deeplinks.core.dshTypography
import dev.deeplinks.native.AppSettings
import dev.deeplinks.native.SettingsDest
import dev.deeplinks.native.SettingsHome
import dev.deeplinks.native.ContextMeterRow
import dev.deeplinks.native.DshMenuItem
import dev.deeplinks.native.SearchOutline16
import dev.deeplinks.native.MobileSession
import dev.deeplinks.native.SessionRowItem
import dev.deeplinks.native.SidebarFooter
import dev.deeplinks.native.SidebarHostRow
import dev.deeplinks.native.SidebarNewSessionRow
import dev.deeplinks.native.SidebarSearchField
import dev.deeplinks.native.SidebarSectionHeader
import dev.deeplinks.native.SidebarWorkspaceRow
import dev.deeplinks.native.WorkspaceTopBar
import dev.deeplinks.native.chatEmptyCanvas
import dev.deeplinks.native.ComposerSeatsRow
import dev.deeplinks.native.StreamReconnectBanner
import dev.deeplinks.native.ToolSearchBar
import dev.deeplinks.native.ui.ChatLoadingSkeleton
import dev.deeplinks.native.util.ChatCanvasKind
import dev.deeplinks.native.util.StreamBannerKind
import dev.deeplinks.native.ui.DshBadge
import dev.deeplinks.native.ui.DshBanner
import dev.deeplinks.native.ui.DshBannerTone
import dev.deeplinks.native.ui.DshFilterChip
import dev.deeplinks.native.ui.DshTag
import dev.deeplinks.native.ui.DshTextTabs

/**
 * Compose Preview Screenshot Testing 基线（AGP 内置）。
 *
 * 与 app/src/debug 的 @Preview 目录不同，这里刻意不经过 DshTheme（避免
 * LaunchedEffect 里的 SharedPreferences 初始化），而是直接提供
 * LocalDshColors / LocalDshStrings，保证宿主端渲染稳定可回归。
 *
 * 覆盖：语义字阶、组件墙、颜色 token 墙、Settings 首页（亮/暗 + 中/英）。
 * 生成/更新基线：./gradlew updateDebugScreenshotTest
 * 校验：        ./gradlew validateDebugScreenshotTest
 */

@Composable
private fun ShotFrame(dark: Boolean, english: Boolean = false, content: @Composable () -> Unit) {
    val colors = if (dark) DarkDshColors else LightDshColors
    val typography = dshTypography(DshFontFamily)
    MaterialTheme(typography = typography) {
        CompositionLocalProvider(
            LocalDshColors provides colors,
            LocalDshStrings provides if (english) DshStringsEn else DshStringsZh,
            LocalDshFontFamily provides DshFontFamily,
            LocalTextStyle provides typography.bodyMedium,
        ) {
            content()
        }
    }
}

@Composable
private fun Wall(dark: Boolean, english: Boolean, content: @Composable () -> Unit) {
    ShotFrame(dark = dark, english = english) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Dsh.bgBase)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            content()
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, color = Dsh.labelTertiary, style = DshType.label)
}

@Composable
private fun TypeScale() {
    SectionTitle("Type scale")
    Text("Display 28 / displayMedium", style = DshType.display, color = Dsh.labelPrimary)
    Text("Headline 18 / headlineSmall", style = DshType.headline, color = Dsh.labelPrimary)
    Text("Title 15 / titleMedium", style = DshType.title, color = Dsh.labelPrimary)
    Text("Body 15 / bodyMedium", style = DshType.body, color = Dsh.labelPrimary)
    Text("Caption 12 / bodySmall", style = DshType.caption, color = Dsh.labelSecondary)
    Text("Label 12 / labelMedium", style = DshType.label, color = Dsh.labelTertiary)
}

@Composable
private fun ComponentWall() {
    SectionTitle("Chips")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        DshFilterChip(label = "全部", selected = true, onClick = {})
        DshFilterChip(label = "对话", selected = false, count = 12, onClick = {})
    }
    SectionTitle("Tabs")
    DshTextTabs(labels = listOf("对话", "轨迹"), selectedIndex = 0, onSelect = {})
    SectionTitle("Tags & badges")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        DshTag(text = "任务")
        DshTag(text = "压缩")
        DshBadge(dot = true)
        DshBadge(count = 3)
        DshBadge(count = 150)
    }
    SectionTitle("Banners")
    DshBanner(text = "实时流连接断开，正在重连…")
    DshBanner(text = "等待审批通过", tone = DshBannerTone.Warn, actionLabel = "查看", onAction = {})
    DshBanner(text = "会话已归档", tone = DshBannerTone.Success)
    DshBanner(text = "连接失败", tone = DshBannerTone.Error, actionLabel = "重试", onAction = {})
    SectionTitle("Loading")
    ChatLoadingSkeleton()
}

@Composable
private fun TokenWall() {
    SectionTitle("Color tokens")
    val swatches: List<Pair<String, Color>> = listOf(
        "bgBase" to Dsh.bgBase,
        "bgSidePanel" to Dsh.bgSidePanel,
        "bgSurface" to Dsh.bgSurface,
        "bgInput" to Dsh.bgInput,
        "bgSubtle" to Dsh.bgSubtle,
        "brand400" to Dsh.brand400,
        "success" to Dsh.success,
        "warn" to Dsh.warn,
        "error" to Dsh.error,
        "traceReasoning" to Dsh.traceReasoning,
        "traceApproval" to Dsh.traceApproval,
        "traceTodo" to Dsh.traceTodo,
        "bubbleBg" to Dsh.bubbleBg,
        "borderStrong" to Dsh.borderStrong,
        "successContent" to Dsh.successContent,
        "successContainer" to Dsh.successContainer,
        "cloudContent" to Dsh.cloudContent,
        "cloudContainer" to Dsh.cloudContainer,
        "brandTint" to Dsh.brandTint,
        "onBrand" to Dsh.onBrand,
        "systemAccent" to Dsh.systemAccent,
        "toolsAccent" to Dsh.toolsAccent,
    )
    swatches.chunked(2).forEach { pair ->
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            pair.forEach { (name, color) ->
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(20.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(color),
                    )
                    // 11sp 等宽：token 名完整可读（原 12sp + 半宽行全被截断）
                    Text(
                        name,
                        style = DshType.microRelaxed,
                        fontFamily = FontFamily.Monospace,
                        color = Dsh.labelSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsHomeWall() {
    SettingsHome(appSettings = AppSettings(), onOpen = { _: SettingsDest -> })
}

@Composable
private fun ChromeWall() {
    SectionTitle("Tool search bar")
    ToolSearchBar(visible = true, query = "", onQueryChange = {})
    ToolSearchBar(visible = true, query = "grep", onQueryChange = {})
    SectionTitle("Stream banner")
    StreamReconnectBanner(kind = StreamBannerKind.Connecting, onRetry = {})
    StreamReconnectBanner(kind = StreamBannerKind.Failed, onRetry = {})
    SectionTitle("Context meter rows")
    ContextMeterRow(label = "System prompt", value = "5.1k", swatchColor = Dsh.systemAccent)
    ContextMeterRow(label = "Tools", value = "2.4k", swatchColor = Dsh.toolsAccent)
    ContextMeterRow(label = "Messages", value = "18.7k", swatchColor = Dsh.brand400)
    SectionTitle("Composer seats")
    ComposerSeatsRow(
        modelName = "DeepSeek V4 Flash",
        modelEffort = "high",
        permissionPreset = "workspace-write",
        permissionLabel = DshS.permWorkspaceWrite,
    )
    ComposerSeatsRow(
        modelName = "GLM-5.3-Flash",
        modelEffort = null,
        permissionPreset = "read-only",
        permissionLabel = DshS.permReadOnly,
    )
    ComposerSeatsRow(
        modelName = "MiniMax-M3",
        modelEffort = "high",
        permissionPreset = "danger-full-access",
        permissionLabel = DshS.permFullAccess,
    )
    ComposerSeatsRow(
        modelName = null,
        modelEffort = null,
        permissionPreset = "workspace-write",
        permissionLabel = DshS.permWorkspaceWrite,
        compact = true,
    )
}

@PreviewTest
@Preview(name = "workspace chrome light", showBackground = true, widthDp = 412, heightDp = 700)
@Composable
internal fun WorkspaceChromeLight() {
    Wall(dark = false, english = false) { ChromeWall() }
}

@Composable
private fun TopBarWall() {
    WorkspaceTopBar(
        running = true,
        title = "调研 t3code 移动端设计并对比项目",
        onOpenDrawer = {},
        viewMode = "chat",
        onSelectViewMode = {},
        menuExpanded = false,
        onMenuExpandedChange = {},
        menuItems = listOf(
            DshMenuItem(SearchOutline16, "搜索工具调用") {},
            DshMenuItem(SearchOutline16, "重命名会话") {},
        ),
    )
}

@PreviewTest
@Preview(name = "workspace top bar light", showBackground = true, widthDp = 412, heightDp = 80)
@Composable
internal fun WorkspaceTopBarLight() {
    Wall(dark = false, english = false) { TopBarWall() }
}

@Composable
private fun ChatCanvasFrame(
    kind: ChatCanvasKind,
    dark: Boolean = false,
    english: Boolean = false,
    elapsedSec: Long = 0L,
    error: String? = null,
) {
    ShotFrame(dark = dark, english = english) {
        LazyColumn(modifier = Modifier.fillMaxSize().background(Dsh.bgBase)) {
            chatEmptyCanvas(kind = kind, elapsedSec = elapsedSec, historyLoadError = error, onRetry = {})
        }
    }
}

@PreviewTest
@Preview(name = "chat empty hero", showBackground = true, widthDp = 412, heightDp = 600)
@Composable
internal fun ChatEmptyHero() {
    ChatCanvasFrame(ChatCanvasKind.Empty)
}

@PreviewTest
@Preview(name = "chat empty error", showBackground = true, widthDp = 412, heightDp = 600)
@Composable
internal fun ChatEmptyError() {
    ChatCanvasFrame(ChatCanvasKind.Error, error = "加载会话失败")
}

@PreviewTest
@Preview(name = "workspace top bar dark en", showBackground = true, widthDp = 412, heightDp = 140)
@Composable
internal fun WorkspaceTopBarDarkEn() {
    Wall(dark = true, english = true) { TopBarWall() }
}

@PreviewTest
@Preview(name = "workspace chrome dark en", showBackground = true, widthDp = 412, heightDp = 700)
@Composable
internal fun WorkspaceChromeDarkEn() {
    Wall(dark = true, english = true) { ChromeWall() }
}

@PreviewTest
@Preview(name = "chat empty hero dark en", showBackground = true, widthDp = 412, heightDp = 600)
@Composable
internal fun ChatEmptyHeroDarkEn() {
    ChatCanvasFrame(ChatCanvasKind.Empty, dark = true, english = true)
}

@PreviewTest
@Preview(name = "chat empty error dark en", showBackground = true, widthDp = 412, heightDp = 600)
@Composable
internal fun ChatEmptyErrorDarkEn() {
    ChatCanvasFrame(ChatCanvasKind.Error, dark = true, english = true, error = "Failed to load conversation")
}

@PreviewTest
@Preview(name = "components light zh", showBackground = true, widthDp = 412, heightDp = 1400)
@Composable
internal fun ComponentsLightZh() {
    Wall(dark = false, english = false) {
        TypeScale()
        ComponentWall()
        TokenWall()
    }
}

@PreviewTest
@Preview(name = "components dark en", showBackground = true, widthDp = 412, heightDp = 1400)
@Composable
internal fun ComponentsDarkEn() {
    Wall(dark = true, english = true) {
        TypeScale()
        ComponentWall()
        TokenWall()
    }
}

@PreviewTest
@Preview(name = "settings light zh", showBackground = true, widthDp = 412, heightDp = 1100)
@Composable
internal fun SettingsLightZh() {
    Wall(dark = false, english = false) { SettingsHomeWall() }
}

@PreviewTest
@Preview(name = "settings dark en large", showBackground = true, widthDp = 412, heightDp = 1100, fontScale = 1.3f)
@Composable
internal fun SettingsDarkEnLarge() {
    Wall(dark = true, english = true) { SettingsHomeWall() }
}

/**
 * 抽屉行墙：收紧后的抽屉规格（48dp 行 / 20dp 图标 / CornerFull 选中胶囊 / bgDrawer 容器
 * 与 bgSidePanel 同档）。只用 blank 会话，避免相对时间随时钟漂移导致基线抖动。
 */
@Composable
private fun SidebarWall() {
    val hairline = Dsh.borderSubtle
    Column(
        modifier = Modifier
            .width(304.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Dsh.bgDrawer)
            // 与真实抽屉一致：末缘发丝线（DshAdaptiveShell 的 SidebarEdgeHairline）
            .drawWithContent {
                drawContent()
                val stroke = 1.dp.toPx()
                val x = size.width - stroke / 2
                drawLine(hairline, Offset(x, 0f), Offset(x, size.height), strokeWidth = stroke)
            }
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        SectionTitle("Header")
        SidebarHostRow(hostName = "MacBook Pro", onOpenDevice = {})
        SectionTitle("Primary action")
        SidebarNewSessionRow(onClick = {})
        SectionTitle("Section header")
        SidebarSectionHeader(
            title = "工作区",
            searchActive = false,
            filterActive = true,
            onToggleSearch = {},
            onOpenFilterSheet = {},
            onAddWorkspace = {},
        )
        SectionTitle("Search")
        SidebarSearchField(value = "", onValueChange = {}, onClear = {}, loading = false)
        SectionTitle("Rows")
        SessionRowItem(
            session = MobileSession(
                sessionId = "s1",
                title = "重构侧边栏行组件",
                updatedAt = 0L,
                running = true,
                blank = true,
                cwd = null,
                agentPreset = null,
            ),
            isSelected = true,
            onClick = {},
            onRename = {},
            onFork = {},
        )
        SessionRowItem(
            session = MobileSession(
                sessionId = "s2",
                title = "修复图片附件回退",
                updatedAt = 0L,
                running = false,
                blank = true,
                cwd = null,
                agentPreset = null,
            ),
            isSelected = false,
            onClick = {},
            onRename = {},
            onFork = {},
        )
        SidebarWorkspaceRow(
            name = "deeplinks",
            collapsed = false,
            sessionCount = 3,
            onToggle = {},
            onCreateSession = {},
            onDeleteWorkspace = {},
        )
        SidebarWorkspaceRow(
            name = "dsh-links",
            collapsed = true,
            sessionCount = 0,
            onToggle = {},
            onCreateSession = {},
            onDeleteWorkspace = {},
        )
        SectionTitle("Footer")
        SidebarFooter(isDarkTheme = false, onOpenSettings = {}, onToggleTheme = {})
    }
}

@PreviewTest
@Preview(name = "sidebar light zh", showBackground = true, widthDp = 340, heightDp = 900)
@Composable
internal fun SidebarLightZh() {
    Wall(dark = false, english = false) { SidebarWall() }
}

@PreviewTest
@Preview(name = "sidebar dark en", showBackground = true, widthDp = 340, heightDp = 900)
@Composable
internal fun SidebarDarkEn() {
    Wall(dark = true, english = true) { SidebarWall() }
}
