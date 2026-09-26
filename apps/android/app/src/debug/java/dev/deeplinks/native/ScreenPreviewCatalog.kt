package dev.deeplinks.native

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.deeplinks.core.Dsh
import dev.deeplinks.core.DshStringsEn
import dev.deeplinks.core.DshTheme
import dev.deeplinks.core.LocalDshStrings
import dev.deeplinks.core.LocalDshColors
import dev.deeplinks.core.DarkDshColors

/**
 * 页面级 Preview 目录（仅 debug 源集，不进入 Release）。
 * 覆盖 P0 重构后的关键状态：设置首页、会话设置入口行，
 * 并按亮/暗、中/英、常规/大字体重放，作为视觉回归的人工基线。
 */
@Preview(name = "Settings home — zh / light", showBackground = true, widthDp = 412, heightDp = 860)
@Composable
private fun SettingsHomeZhLightPreview() {
    DshTheme {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Dsh.bgBase)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            SettingsHome(appSettings = AppSettings(), onOpen = {})
        }
    }
}

@Preview(
    name = "Settings home — en / dark / 1.3x",
    showBackground = true,
    widthDp = 412,
    heightDp = 860,
    fontScale = 1.3f,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun SettingsHomeEnDarkPreview() {
    DshTheme {
        CompositionLocalProvider(LocalDshStrings provides DshStringsEn) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Dsh.bgBase)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            ) {
                SettingsHome(appSettings = AppSettings(language = "en"), onOpen = {})
            }
        }
    }
}

@Preview(name = "Composer seats — light zh", showBackground = true, widthDp = 412)
@Composable
private fun ComposerSeatsLightPreview() {
    DshTheme {
        Column(modifier = Modifier.fillMaxSize().background(Dsh.bgInput).padding(16.dp)) {
            ComposerSeatsRow(
                modelName = "DeepSeek V4 Flash",
                modelEffort = "high",
                permissionPreset = "workspace-write",
                permissionLabel = "工作区内修改",
            )
            ComposerSeatsRow(
                modelName = "DeepSeek V4 Flash",
                modelEffort = "high",
                permissionPreset = "danger-full-access",
                permissionLabel = "完全权限",
            )
            ComposerSeatsRow(
                modelName = null,
                modelEffort = null,
                permissionPreset = "read-only",
                permissionLabel = "仅可查看",
                compact = true,
            )
        }
    }
}

@Preview(name = "Composer seats — dark en", showBackground = true, widthDp = 412)
@Composable
private fun ComposerSeatsDarkPreview() {
    CompositionLocalProvider(LocalDshColors provides DarkDshColors, LocalDshStrings provides DshStringsEn) {
        Column(modifier = Modifier.fillMaxSize().background(Dsh.bgInput).padding(16.dp)) {
            ComposerSeatsRow(
                modelName = "DeepSeek V4 Flash",
                modelEffort = "high",
                permissionPreset = "workspace-write",
                permissionLabel = "Workspace Write",
            )
            ComposerSeatsRow(
                modelName = "DeepSeek V4 Flash",
                modelEffort = "high",
                permissionPreset = "danger-full-access",
                permissionLabel = "Full access",
            )
        }
    }
}
