package dev.dsh.mobile.native.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dsh.mobile.core.DarkDshColors
import dev.dsh.mobile.core.Dsh
import dev.dsh.mobile.core.DshTheme
import dev.dsh.mobile.core.LightDshColors
import dev.dsh.mobile.core.LocalDshColors

/**
 * DSH 设计系统组件目录（@Preview catalog）—— 在 Android Studio Preview 面板
 * 直接看到所有 ui.Dsh* 组件在深 / 浅主题下的真实外观。
 *
 * 命名规范：
 * - `DshCatalog*Preview`：每个组件独立的小预览；
 * - `DshFullCatalogPreview`：所有组件拼成一张目录总览，便于一次性比对。
 *
 * 不依赖任何运行时状态；只在 Preview 工具中渲染。
 */

@Preview(name = "FilterChip — All / Selected / Disabled", widthDp = 360, showBackground = true)
@Composable
private fun DshCatalogFilterChipPreview() {
    DshPreview {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DshFilterChip(label = "全部", count = 12, selected = true, onClick = {})
                DshFilterChip(label = "运行中", count = 3, selected = false, onClick = {})
                DshFilterChip(label = "已停止", count = 9, selected = false, onClick = {})
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DshFilterChip(label = "禁用态", selected = false, enabled = false, onClick = {})
                DshFilterChip(label = "纯文本", selected = false, onClick = {})
            }
        }
    }
}

@Preview(name = "Tag — Tone variants", widthDp = 360, showBackground = true)
@Composable
private fun DshCatalogTagPreview() {
    DshPreview {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DshTag(text = "任务")
                DshTag(text = "压缩")
                DshTag(text = "工具")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DshTag(text = "危险", color = Dsh.errorBg, contentColor = Dsh.error)
                DshTag(text = "警告", color = Dsh.warn.copy(alpha = 0.15f), contentColor = Dsh.warnLabel)
                DshTag(text = "成功", color = Dsh.success.copy(alpha = 0.15f), contentColor = Dsh.success)
            }
        }
    }
}

@Preview(name = "Badge — Dot / Count / 99+", widthDp = 360, showBackground = true)
@Composable
private fun DshCatalogBadgePreview() {
    DshPreview {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DshBadge(dot = true)
            DshBadge(count = 1)
            DshBadge(count = 7)
            DshBadge(count = 150) // 99+ cap
            DshBadge(count = 0, dot = true) // 0 → 隐藏
        }
    }
}

@Preview(name = "Banner — Info / Warn / Error / Success", widthDp = 360, showBackground = true)
@Composable
private fun DshCatalogBannerPreview() {
    DshPreview {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DshBanner(text = "实时流连接断开，正在重连…")
            DshBanner(
                text = "等待审批通过",
                tone = DshBannerTone.Warn,
                actionLabel = "查看",
                onAction = {},
            )
            DshBanner(
                text = "保存失败，请重试",
                tone = DshBannerTone.Error,
                actionLabel = "重试",
                onAction = {},
            )
            DshBanner(text = "会话已归档", tone = DshBannerTone.Success)
        }
    }
}

@Preview(name = "ChatLoadingSkeleton — streaming placeholder", widthDp = 360, showBackground = true)
@Composable
private fun DshCatalogChatLoadingSkeletonPreview() {
    DshPreview {
        ChatLoadingSkeleton()
    }
}

/** 全组件总览：浅色 + 深色并列，便于一次比对 token 表现。 */
@Preview(name = "Full Catalog — Light", widthDp = 420, showBackground = true)
@Composable
private fun DshFullCatalogLightPreview() {
    DshPreview(forceDark = false) { DshFullCatalogBody() }
}

@Preview(name = "Full Catalog — Dark", widthDp = 420, showBackground = true)
@Composable
private fun DshFullCatalogDarkPreview() {
    DshPreview(forceDark = true) { DshFullCatalogBody() }
}

@Composable
private fun DshFullCatalogBody() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Dsh.bgBase)
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionLabel("FilterChip")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DshFilterChip(label = "全部", count = 12, selected = true, onClick = {})
            DshFilterChip(label = "运行中", count = 3, selected = false, onClick = {})
            DshFilterChip(label = "已停止", count = 9, selected = false, onClick = {})
        }
        SectionLabel("Tag")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DshTag(text = "任务")
            DshTag(text = "压缩")
            DshTag(text = "工具")
        }
        SectionLabel("Badge")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            DshBadge(dot = true)
            DshBadge(count = 3)
            DshBadge(count = 150)
        }
        SectionLabel("Banner")
        DshBanner(text = "实时流连接断开，正在重连…")
        DshBanner(
            text = "等待审批通过",
            tone = DshBannerTone.Warn,
            actionLabel = "查看",
            onAction = {},
        )
        DshBanner(text = "会话已归档", tone = DshBannerTone.Success)
        SectionLabel("ChatLoadingSkeleton")
        ChatLoadingSkeleton()
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        color = Dsh.labelTertiary,
        fontSize = 11.sp,
    )
}

/**
 * Preview 专用主题容器：
 * - 默认使用 [DshTheme]（按系统）；
 * - [forceDark] 非 null 时锁定深色或浅色（用对应的 [LocalDshColors] 直接提供）。
 */
@Composable
private fun DshPreview(
    forceDark: Boolean? = null,
    content: @Composable () -> Unit,
) {
    when (forceDark) {
        true -> CompositionLocalProvider(LocalDshColors provides DarkDshColors, content = content)
        false -> CompositionLocalProvider(LocalDshColors provides LightDshColors, content = content)
        null -> DshTheme(content = content)
    }
}