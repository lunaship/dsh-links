package dev.deeplinks.native

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.deeplinks.core.dshRipple
import dev.deeplinks.core.Dsh
import dev.deeplinks.core.L
import dev.deeplinks.core.DshType

@Composable
fun CameraCropSheet(
    bitmap: Bitmap,
    onConfirm: (Bitmap) -> Unit,
    onRetake: () -> Unit,
    onDismiss: () -> Unit,
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var viewW by remember { mutableFloatStateOf(1f) }
    var viewH by remember { mutableFloatStateOf(1f) }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(20.dp)
                .clip(RoundedCornerShape(DshRadius.lg))
                .background(Dsh.bgBase)
                .padding(16.dp),
        ) {
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(360.dp)
                    .clip(RoundedCornerShape(DshRadius.md))
                    .background(Dsh.bgCard)
                    .border(1.dp, Dsh.borderSubtle, RoundedCornerShape(DshRadius.md)),
            ) {
                viewW = constraints.maxWidth.toFloat().coerceAtLeast(1f)
                viewH = constraints.maxHeight.toFloat().coerceAtLeast(1f)
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = L.pendingImage,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(bitmap) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                scale = (scale * zoom).coerceIn(1f, 4f)
                                offset += pan
                            }
                        }
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = offset.x
                            translationY = offset.y
                        },
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CropAction(L.retakePhoto, Modifier.weight(1f), onRetake)
                CropAction(L.usePhoto, Modifier.weight(1f)) {
                    onConfirm(ImageAttach.crop(bitmap, viewW, viewH, scale, offset.x, offset.y))
                }
            }
        }
    }
}

@Composable
private fun CropAction(
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        modifier = modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(DshRadius.md))
            .background(if (pressed) Dsh.pressed else Dsh.bgSubtle)
            .semantics {
                role = Role.Button
                contentDescription = label
            }
            .clickable(interactionSource = interaction, indication = dshRipple(), onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Dsh.labelPrimary, style = DshType.t14)
    }
}
