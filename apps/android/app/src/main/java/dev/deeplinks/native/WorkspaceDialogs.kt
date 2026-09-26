package dev.deeplinks.native

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.deeplinks.core.Dsh
import dev.deeplinks.core.L
import dev.deeplinks.native.ui.DshTextField
import dev.deeplinks.native.util.RenameDialogKind
import dev.deeplinks.native.util.renameDialogKind
import dev.deeplinks.core.DshType


@Composable
internal fun DshRenameDialog(
    currentName: String,
    error: String? = null,
    saving: Boolean = false,
    onDismiss: () -> Unit,
    onClearError: () -> Unit = {},
    onSave: (String) -> Unit,
) {
    var name by remember { mutableStateOf(currentName) }
    val kind = renameDialogKind(saving, error)
    val canSave = name.isNotBlank() && kind != RenameDialogKind.Saving
    val motion = dialogMotionState(onDismiss)
    // 关闭入口统一先走出场动画，播完才回调 onDismiss（saving 中不响应关闭）
    val requestDismiss = { if (kind != RenameDialogKind.Saving) motion.requestDismiss() }
    Dialog(
        onDismissRequest = requestDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
                .graphicsLayer { alpha = motion.alpha.value }
                .background(Dsh.bgOverlay)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = requestDismiss,
                ),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 280.dp)
                    .fillMaxWidth(0.8f)
                    .graphicsLayer {
                        alpha = motion.alpha.value
                        scaleX = motion.scale.value
                        scaleY = motion.scale.value
                    }
                    .shadow(12.dp, RoundedCornerShape(DshRadius.dialog), ambientColor = Dsh.shadowCard, spotColor = Dsh.shadowCard)
                    .clip(RoundedCornerShape(DshRadius.dialog))
                    .background(Dsh.bgCard)
                    .border(1.dp, Dsh.borderSubtle, RoundedCornerShape(DshRadius.dialog))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    )
                    .padding(18.dp)
            ) {
                Text(L.renameSession, color = Dsh.labelPrimary, style = DshType.t15x21M, fontWeight = FontWeight(500), lineHeight = 21.sp)
                Spacer(Modifier.height(8.dp))
                Text(L.renameSessionDesc, color = Dsh.labelTertiary, style = DshType.t12x17, lineHeight = 17.sp)
                Spacer(Modifier.height(14.dp))
                DshTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        if (error != null) onClearError()
                    },
                    singleLine = true,
                    contentDescription = L.renameSession,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (kind == RenameDialogKind.Failed && !error.isNullOrBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        error,
                        color = Dsh.error,
                        style = DshType.t12x17,
                        lineHeight = 17.sp,
                        modifier = Modifier.semantics { contentDescription = error },
                    )
                }
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(
                        onClick = requestDismiss,
                        enabled = kind != RenameDialogKind.Saving,
                        colors = ButtonDefaults.textButtonColors(contentColor = Dsh.labelSecondary),
                    ) {
                        Text(L.cancel, style = DshType.t12M, fontWeight = FontWeight(500))
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = { onSave(name) },
                        enabled = canSave,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Dsh.brand400,
                            contentColor = Color.White,
                            disabledContainerColor = Dsh.bgTrack,
                            disabledContentColor = Dsh.labelTertiary,
                        ),
                    ) {
                        Text(
                            if (kind == RenameDialogKind.Saving) L.saving else L.save,
                            style = DshType.t12M,
                            fontWeight = FontWeight(500),
                        )
                    }
                }
            }
        }
    }
}

// ---------- 确认弹窗（DSH 风格：bgCard 卡片 + 危险操作红按钮） ----------

@Composable
internal fun DshConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    danger: Boolean = false,
    error: String? = null,
    saving: Boolean = false,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val motion = dialogMotionState(onDismiss)
    val haptic = rememberDshHaptic()
    val kind = renameDialogKind(saving, error)
    val canConfirm = kind != RenameDialogKind.Saving
    // 关闭入口统一先走出场动画，播完才回调 onDismiss（saving 中不响应关闭）
    val requestDismiss = { if (kind != RenameDialogKind.Saving) motion.requestDismiss() }
    Dialog(
        onDismissRequest = requestDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = motion.alpha.value }
                .background(Dsh.bgOverlay)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = requestDismiss,
                ),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 280.dp)
                    .fillMaxWidth(0.8f)
                    .graphicsLayer {
                        alpha = motion.alpha.value
                        scaleX = motion.scale.value
                        scaleY = motion.scale.value
                    }
                    .shadow(12.dp, RoundedCornerShape(DshRadius.dialog), ambientColor = Dsh.shadowCard, spotColor = Dsh.shadowCard)
                    .clip(RoundedCornerShape(DshRadius.dialog))
                    .background(Dsh.bgCard)
                    .border(1.dp, Dsh.borderSubtle, RoundedCornerShape(DshRadius.dialog))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    )
                    .padding(18.dp)
            ) {
                Text(title, color = Dsh.labelPrimary, style = DshType.t15x21M, fontWeight = FontWeight(500), lineHeight = 21.sp)
                Spacer(Modifier.height(8.dp))
                Text(message, color = Dsh.labelTertiary, style = DshType.t12x17, lineHeight = 17.sp)
                if (kind == RenameDialogKind.Failed && !error.isNullOrBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        error,
                        color = Dsh.error,
                        style = DshType.t12x17,
                        lineHeight = 17.sp,
                        modifier = Modifier.semantics { contentDescription = error },
                    )
                }
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(
                        onClick = requestDismiss,
                        enabled = canConfirm,
                        colors = ButtonDefaults.textButtonColors(contentColor = Dsh.labelSecondary),
                    ) {
                        Text(L.cancel, style = DshType.t12M, fontWeight = FontWeight(500))
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            // 危险确认给负向触觉，普通确认给正向触觉（与 DevicesActivity ConfirmButton 同语义）
                            haptic(if (danger) DshHaptic.Reject else DshHaptic.Confirm)
                            onConfirm()
                        },
                        enabled = canConfirm,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (danger) Dsh.error else Dsh.brand400,
                            contentColor = Color.White,
                            disabledContainerColor = Dsh.bgTrack,
                            disabledContentColor = Dsh.labelTertiary,
                        ),
                    ) {
                        Text(
                            if (kind == RenameDialogKind.Saving) L.saving else confirmLabel,
                            style = DshType.t12M,
                            fontWeight = FontWeight(500),
                        )
                    }
                }
            }
        }
    }
}
