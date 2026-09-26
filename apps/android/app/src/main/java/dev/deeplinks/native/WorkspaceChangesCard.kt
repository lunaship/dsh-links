package dev.deeplinks.native

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.deeplinks.core.Dsh
import dev.deeplinks.core.DshType
import dev.deeplinks.core.dshRipple

/**
 * 轮末改动文件卡片（对齐 DSH Web `dsh-client-ui-deliverables` 的 changed-files card）：
 * 单文件标题写「已编辑 文件名」，多文件写总数；右侧增删合计；默认列前 4 个文件，
 * 其余收成「还有 N 个文件」。点文件直达该文件对比，点标题 / 更多打开审查面。
 */
@Composable
internal fun WorkspaceChangesCard(
    summary: WorkspaceChangesSummary,
    onOpen: (fileIndex: Int?) -> Unit,
) {
    val shape = RoundedCornerShape(DshRadius.lg)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Dsh.bgInput)
            .border(1.dp, Dsh.borderSubtle, shape),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable(indication = dshRipple(), interactionSource = null) { onOpen(null) }
                .semantics {
                    role = Role.Button
                    contentDescription = "${ChangesL.viewChanges}: ${ChangesL.cardTitle(summary)}"
                }
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(EditOutline16, contentDescription = null, tint = Dsh.labelTertiary, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(10.dp))
            Text(
                ChangesL.cardTitle(summary),
                color = Dsh.labelPrimary,
                style = DshType.t13M,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            DiffStat(summary.added, summary.deleted)
        }
        val visible = summary.files.take(CHANGES_CARD_VISIBLE_FILES)
        // 单文件卡片标题已写明文件名，不再重复一行
        if (summary.total > 1 || summary.files.size > 1) {
            visible.forEachIndexed { index, file ->
                ChangedFileRow(file = file, onClick = { onOpen(index) })
            }
            val hidden = summary.total - visible.size
            if (hidden > 0) {
                Text(
                    ChangesL.moreFiles.format(hidden),
                    color = Dsh.brand400,
                    style = DshType.t12M,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 44.dp)
                        .clickable(indication = dshRipple(), interactionSource = null) { onOpen(null) }
                        .padding(horizontal = 38.dp, vertical = 13.dp),
                )
            }
        }
    }
}

/** 一行改动文件：文件名 + 目录（弱）+ 行数 / 降级说明。卡片与审查面共用。 */
@Composable
internal fun ChangedFileRow(
    file: ChangedFile,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    startPadding: Dp = 38.dp,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clickable(indication = dshRipple(), interactionSource = null, onClick = onClick)
            .semantics {
                role = Role.Button
                contentDescription = file.display
            }
            .padding(start = startPadding, end = 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Text(
                file.name,
                color = Dsh.labelPrimary,
                style = DshType.t13,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (file.directory.isNotEmpty()) {
                Spacer(Modifier.width(6.dp))
                Text(
                    file.directory,
                    color = Dsh.labelTertiary,
                    style = DshType.t12,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        when {
            file.binary -> Text("BIN", color = Dsh.labelTertiary, style = DshType.t11M)
            file.oversized -> Text("—", color = Dsh.labelTertiary, style = DshType.t11M)
            else -> DiffStat(file.added, file.deleted)
        }
    }
}

/** `+12 −3`：增绿删红，等宽数字；为 0 的一侧省略。 */
@Composable
internal fun DiffStat(added: Int, deleted: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (added > 0) Text("+$added", color = Dsh.success, style = DshType.t12M, fontFamily = FontFamily.Monospace)
        if (added > 0 && deleted > 0) Spacer(Modifier.width(6.dp))
        if (deleted > 0) Text("−$deleted", color = Dsh.error, style = DshType.t12M, fontFamily = FontFamily.Monospace)
    }
}
