package dev.deeplinks.native

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DrawerState
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.PermanentDrawerSheet
import androidx.compose.material3.PermanentNavigationDrawer
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.deeplinks.core.Dsh
import dev.deeplinks.core.DshLayout

/**
 * 自适应工作区外壳（对照 t3code AdaptiveWorkspaceLayout 的克制版）：
 *
 * - Compact（<600dp）：模态抽屉，行为与改造前完全一致。
 * - Medium（600–839dp）：窄 Rail 常驻 + 同一套模态抽屉展开会话列表。
 *   内容不再被 240dp 常驻列表挤压，也不用每帧重排。
 * - Expanded（>=840dp 且高 >=600dp）：侧栏常驻，不再覆盖内容、也没有每帧重排的
 *   抽屉动画——这正是 t3code 那条“面板动画期间冻结内容测量”经验的等价实现。
 *
 * 三种形态共用同一棵 [sidebar]/[content] 子树，切换 shell 不会重建业务状态。
 *
 * 容器色走 [Dsh.bgDrawer]（与 bgSidePanel 同档的 surfaceContainerLow）：与内容面只差一档，
 * 分层感由**末缘发丝线**给出（modal 遮罩 + 发丝线 / persistent 只靠发丝线），
 * 不再用「灰托盘压白内容」这种和主界面讲相反故事的对比。
 * 顶部 inset 由 DrawerSheet 自带的 systemBars 负责，侧栏内容不得再叠一层。
 */
@Composable
internal fun DshAdaptiveShell(
    layout: DshLayout,
    drawerState: DrawerState,
    compactDrawerWidth: Dp,
    sidebarCollapsed: Boolean = false,
    rail: @Composable () -> Unit = {},
    sidebar: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    if (layout.persistentSidebar) {
        PermanentNavigationDrawer(
            drawerContent = {
                PermanentDrawerSheet(
                    drawerContainerColor = Dsh.bgDrawer,
                    drawerContentColor = Dsh.labelPrimary,
                    modifier = Modifier.width(if (sidebarCollapsed) 56.dp else layout.listPaneWidthDp.dp),
                ) { SidebarEdgeHairline { sidebar() } }
            },
        ) { content() }
        return
    }

    // Compact 与 Medium 共用同一套模态抽屉：Medium 只是额外常驻一条 Rail，
    // 打开后仍然可以盖住 Rail（M3 modal drawer 语义），关闭时主内容不被压缩。
    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            // 带 drawerState 的重载：Android 14+ 预测性返回时抽屉会跟手缩放
            ModalDrawerSheet(
                drawerState = drawerState,
                drawerContainerColor = Dsh.bgDrawer,
                drawerContentColor = Dsh.labelPrimary,
                modifier = Modifier.width(compactDrawerWidth),
            ) { SidebarEdgeHairline { sidebar() } }
        },
    ) {
        if (layout.railNavigation) {
            Row(modifier = Modifier.fillMaxSize()) {
                rail()
                Box(modifier = Modifier.weight(1f)) { content() }
            }
        } else {
            content()
        }
    }
}

/**
 * 容器与内容同系配色后，抽屉右缘的 1dp 发丝线是唯一硬边界。
 * 画在内容层之上（drawWithContent）才能盖过 sheet 自身的容器底；
 * 用 [Dsh.borderSubtle]（亮 10% 黑 / 暗 12% 白），随主题自动适配。
 */
@Composable
private fun SidebarEdgeHairline(content: @Composable () -> Unit) {
    val hairline = Dsh.borderSubtle
    Box(
        modifier = Modifier
            .fillMaxSize()
            .drawWithContent {
                drawContent()
                val stroke = 1.dp.toPx()
                val x = size.width - stroke / 2
                drawLine(
                    color = hairline,
                    start = Offset(x, 0f),
                    end = Offset(x, size.height),
                    strokeWidth = stroke,
                )
            },
    ) { content() }
}
