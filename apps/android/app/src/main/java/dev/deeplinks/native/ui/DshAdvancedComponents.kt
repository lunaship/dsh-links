package dev.deeplinks.native.ui

import dev.deeplinks.core.DshType

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import dev.deeplinks.core.Dsh

// ============================================================
// DshTextField —— 文本输入：48dp 高、聚焦仅加深描边（无 focus ring）、error 态红边
// ============================================================
@Composable
fun DshTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    enabled: Boolean = true,
    isError: Boolean = false,
    errorText: String? = null,
    singleLine: Boolean = true,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailingIcon: (@Composable () -> Unit)? = null,
    contentDescription: String? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(8.dp)

    val borderColor = when {
        !enabled -> Dsh.borderSubtle
        isError -> Dsh.error
        focused -> Dsh.brand400
        else -> Dsh.borderSubtle
    }

    Column(modifier) {
        if (label != null) {
            Text(
                text = label,
                color = Dsh.labelSecondary,
                style = DshType.t12M,
                fontWeight = FontWeight(500),
                modifier = Modifier.padding(bottom = 4.dp, start = 2.dp),
            )
        }
        Box(modifier = Modifier.fillMaxWidth()) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                enabled = enabled,
                singleLine = singleLine,
                visualTransformation = visualTransformation,
                interactionSource = interaction,
                textStyle = DshType.bodyDense.copy(
                    color = if (enabled) Dsh.labelPrimary else Dsh.labelDimmed,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics {
                        if (contentDescription != null) {
                            this.contentDescription = contentDescription
                        }
                    },
                decorationBox = { innerTextField ->
                    val heightModifier = if (singleLine) {
                        Modifier.height(48.dp)
                    } else {
                        Modifier.heightIn(min = 48.dp)
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(heightModifier)
                            .background(Dsh.bgInput, shape)
                            .border(1.dp, borderColor, shape)
                            .padding(horizontal = 13.dp, vertical = 13.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        if (value.isEmpty() && placeholder != null) {
                            Text(
                                text = placeholder,
                                color = Dsh.labelTertiary,
                                style = DshType.bodyDense,
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(modifier = Modifier.weight(1f)) { innerTextField() }
                            if (trailingIcon != null) {
                                Spacer(Modifier.width(8.dp))
                                trailingIcon()
                            }
                        }
                    }
                },
            )
        }
        if (isError && errorText != null) {
            Text(
                text = errorText,
                color = Dsh.error,
                style = DshType.microRelaxed,
                modifier = Modifier.padding(top = 4.dp, start = 2.dp),
            )
        }
    }
}