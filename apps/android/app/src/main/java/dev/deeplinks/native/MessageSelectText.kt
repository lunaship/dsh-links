package dev.deeplinks.native

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.deeplinks.core.Dsh
import dev.deeplinks.core.L
import dev.deeplinks.core.DshType
import dev.deeplinks.native.ui.DshHeaderAction

/**
 * 精确选择复制（对照 DeepSeek 1.3.1 / OpenClaw Android）。
 * 不把 SelectionContainer 嵌进会话 LazyColumn，避免和滚动、长按菜单抢手势。
 */
@Composable
fun SelectTextDialog(text: String, onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .clip(RoundedCornerShape(DshRadius.lg))
                .background(Dsh.bgBase)
                .padding(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    L.selectText,
                    color = Dsh.labelPrimary,
                    style = DshType.t14,
                    modifier = Modifier.weight(1f),
                )
                DshHeaderAction(L.close, onClick = onDismiss)
            }
            SelectionContainer {
                Text(
                    text,
                    color = Dsh.labelPrimary,
                    style = DshType.t16x28,
                    lineHeight = 28.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 480.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(top = 8.dp, bottom = 4.dp),
                )
            }
        }
    }
}
