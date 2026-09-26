package dev.deeplinks.native

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.deeplinks.core.dshRipple
import dev.deeplinks.core.Dsh
import dev.deeplinks.core.DshType

// ---------- DSH 风格菜单浮层（12dp 圆角 + L2 细边框 + 图标菜单项） ----------

internal data class DshMenuItem(
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val label: String,
    val danger: Boolean = false,
    val onClick: () -> Unit,
)

@Composable
internal fun DshMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    items: List<DshMenuItem>,
    offset: androidx.compose.ui.unit.DpOffset = androidx.compose.ui.unit.DpOffset.Zero,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        offset = offset,
        containerColor = Dsh.bgCard,
        shape = RoundedCornerShape(DshRadius.lg),
        tonalElevation = 0.dp,
        shadowElevation = 12.dp,
        border = BorderStroke(1.dp, Dsh.borderSubtle)
    ) {
        Column(
            modifier = Modifier
                .width(220.dp)
                .padding(vertical = 4.dp)
        ) {
            items.forEach { item ->
                val interaction = remember { MutableInteractionSource() }
                val pressed by interaction.collectIsPressedAsState()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clip(RoundedCornerShape(DshRadius.md))
                        .background(if (pressed) Dsh.pressed else Color.Transparent)
                        .semantics {
                            role = Role.Button
                            contentDescription = item.label
                        }
                        .clickable(interactionSource = interaction, indication = dshRipple(), onClick = item.onClick)
                        .padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        item.icon,
                        contentDescription = null,
                        tint = if (item.danger) Dsh.error else Dsh.labelSecondary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        item.label,
                        color = if (item.danger) Dsh.error else Dsh.labelPrimary,
                        style = DshType.bodyDense,
                        lineHeight = 20.sp
                    )
                }
            }
        }
    }
}
