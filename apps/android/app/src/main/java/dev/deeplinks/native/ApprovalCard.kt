package dev.deeplinks.native

import dev.deeplinks.core.DshType

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.deeplinks.core.dshRipple
import dev.deeplinks.core.Dsh
import dev.deeplinks.core.L

private enum class ApprovalChoice { AllowOnce, Reject }

/**
 * 工具审批卡（对齐参考 ApprovalCard + DSH Web）：
 * 单题单选 → 手动提交；仅在服务端接受后收成绿色确认徽章。
 * 结果仍走 Web 协议：`allowed-once` / `rejected`。
 */
@Composable
internal fun ApprovalCard(
    msg: MobileMessage,
    onAnswer: (approvalId: String, outcome: String, onDone: (Boolean) -> Unit) -> Unit,
) {
    var open by remember(msg.id) { mutableStateOf(true) }
    var selected by remember(msg.id) { mutableStateOf<ApprovalChoice?>(null) }
    val haptic = rememberDshHaptic()
    var sent by remember(msg.id, msg.requestStatus, msg.outcome) {
        mutableStateOf(isTerminalRequestStatus(msg.requestStatus))
    }
    var submitting by remember(msg.id) { mutableStateOf(false) }
    var submitError by remember(msg.id) { mutableStateOf<String?>(null) }
    var sentChoice by remember(msg.id, msg.outcome) {
        mutableStateOf(
            when (msg.outcome) {
                "rejected", "cancelled" -> ApprovalChoice.Reject
                "allowed-once" -> ApprovalChoice.AllowOnce
                else -> null
            },
        )
    }

    fun submit(choice: ApprovalChoice) {
        if (sent || submitting || msg.requestStatus == REQUEST_UNKNOWN) return
        val id = msg.approvalId
        if (id.isNullOrBlank()) return
        haptic(if (choice == ApprovalChoice.AllowOnce) DshHaptic.Confirm else DshHaptic.Reject)
        submitting = true
        submitError = null
        onAnswer(id, if (choice == ApprovalChoice.AllowOnce) "allowed-once" else "rejected") { ok ->
            submitting = false
            if (ok) {
                sent = true
                sentChoice = choice
            } else {
                selected = null
                submitError = L.approvalNotAccepted
            }
        }
    }

    if (!open && !sent) {
        val interaction = remember { MutableInteractionSource() }
        val pressed by interaction.collectIsPressedAsState()
        Text(
            L.openApproval,
            color = Dsh.labelPrimary,
            style = DshType.t13M,
            fontWeight = FontWeight(500),
            modifier = Modifier
                .clip(RoundedCornerShape(DshRadius.sm))
                .background(if (pressed) Dsh.pressed else Dsh.bgSurface)
                .border(1.dp, Dsh.borderSubtle, RoundedCornerShape(DshRadius.sm))
                .clickable(interactionSource = interaction, indication = dshRipple()) { open = true }
                .semantics {
                    role = Role.Button
                    contentDescription = L.openApproval
                }
                .heightIn(min = 48.dp)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        )
        return
    }

    if (sent) {
        ApprovalSentBadge(
            choice = sentChoice ?: selected,
            status = msg.requestStatus,
        )
        return
    }
    if (msg.requestStatus == REQUEST_UNKNOWN) {
        Text(
            L.approvalStatusUnknown,
            color = Dsh.labelSecondary,
            style = DshType.t13,
            modifier = Modifier
                .clip(RoundedCornerShape(DshRadius.sm))
                .background(Dsh.bgSurface)
                .border(1.dp, Dsh.borderSubtle, RoundedCornerShape(DshRadius.sm))
                .padding(horizontal = 12.dp, vertical = 8.dp),
        )
        return
    }

    Column(
        modifier = Modifier
            .widthIn(max = 320.dp)
            .fillMaxWidth()
            .heightIn(min = 160.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .shadow(6.dp, RoundedCornerShape(DshRadius.lg), ambientColor = Dsh.shadowCard, spotColor = Dsh.shadowCard)
                .clip(RoundedCornerShape(DshRadius.lg))
                .background(Dsh.bgSurface)
                .border(1.dp, Dsh.borderSubtle, RoundedCornerShape(DshRadius.lg)),
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            L.approvalQuestion,
                            color = Dsh.labelPrimary,
                            style = DshType.titleSmall,
                            fontWeight = FontWeight(500),
                            lineHeight = 18.sp,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            msg.text.ifBlank {
                                L.approvalRequest.format(msg.toolName ?: L.toolFallbackName)
                            },
                            color = Dsh.labelSecondary,
                            style = DshType.t13x18,
                            lineHeight = 18.sp,
                        )
                        msg.toolName?.takeIf { it.isNotBlank() }?.let { name ->
                            Spacer(Modifier.height(6.dp))
                            Text(
                                name,
                                color = Dsh.labelTertiary,
                                style = DshType.t11,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clickable { open = false }
                            .semantics {
                                role = Role.Button
                                contentDescription = L.approvalDismiss
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(RoundedCornerShape(DshRadius.sm)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                CloseOutline16,
                                contentDescription = null,
                                tint = Dsh.labelTertiary,
                                modifier = Modifier.size(14.dp),
                            )
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))

                ApprovalOptionRow(
                    label = L.allowOnce,
                    selected = selected == ApprovalChoice.AllowOnce,
                    radio = true,
                    onClick = {
                        submitError = null
                        if (selected != ApprovalChoice.AllowOnce) haptic(DshHaptic.Tick)
                        selected = ApprovalChoice.AllowOnce
                    },
                )
                ApprovalOptionRow(
                    label = L.reject,
                    selected = selected == ApprovalChoice.Reject,
                    radio = true,
                    onClick = {
                        submitError = null
                        if (selected != ApprovalChoice.Reject) haptic(DshHaptic.Tick)
                        selected = ApprovalChoice.Reject
                    },
                )
            }

            val shownError = submitError
            if (shownError != null) {
                Text(
                    shownError,
                    color = Dsh.error,
                    style = DshType.caption,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                        .semantics { contentDescription = shownError },
                )
            }

            // footer：步骤点 + 上箭头提交
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Dsh.bgCard.copy(alpha = if (Dsh.isDark) 0.5f else 1f))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 单题：当前步空心粗环
                    Box(
                        modifier = Modifier
                            .size(9.dp)
                            .border(2.5.dp, Dsh.labelPrimary, CircleShape),
                    )
                }
                val canSend = selected != null && !submitting
                val sendInteraction = remember { MutableInteractionSource() }
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .dshPressScale(sendInteraction)
                        .clickable(interactionSource = sendInteraction, indication = dshRipple(), enabled = canSend) {
                            selected?.let { submit(it) }
                        }
                        .semantics {
                            role = Role.Button
                            contentDescription = L.approvalSendAnswer
                    },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (canSend) Dsh.labelPrimary else Dsh.bgTrack),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Default.KeyboardArrowUp,
                            contentDescription = null,
                            tint = if (canSend) Dsh.bgSurface else Dsh.labelTertiary,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ApprovalOptionRow(
    label: String,
    selected: Boolean,
    radio: Boolean,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(DshRadius.sm))
            .background(if (pressed) Dsh.pressed else Color.Transparent)
            .selectable(
                selected = selected,
                onClick = onClick,
                role = Role.RadioButton,
                indication = dshRipple(),
                interactionSource = interaction,
            )
            .semantics {
                contentDescription = label
            }
            .heightIn(min = 48.dp)
            .padding(horizontal = 6.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(16.dp)
                .clip(if (radio) CircleShape else RoundedCornerShape(5.dp))
                .then(
                    if (selected) Modifier.background(Dsh.labelPrimary)
                    else Modifier.border(1.5.dp, Dsh.borderStrong, if (radio) CircleShape else RoundedCornerShape(5.dp)),
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (radio) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(if (selected) Dsh.bgSurface else Color.Transparent),
                )
            } else if (selected) {
                Icon(
                    CheckOutline14,
                    contentDescription = null,
                    tint = Dsh.bgSurface,
                    modifier = Modifier.size(12.dp),
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(
            label,
            color = if (selected) Dsh.labelPrimary else Dsh.labelSecondary,
            style = DshType.titleSmall,
        )
    }
}

@Composable
private fun ApprovalSentBadge(choice: ApprovalChoice?, status: String? = null) {
    val allowed = when {
        status == REQUEST_CANCELLED || status == REQUEST_EXPIRED -> false
        choice == ApprovalChoice.Reject -> false
        else -> true
    }
    val label = when (status) {
        REQUEST_CANCELLED -> L.approvalCancelled
        REQUEST_EXPIRED -> L.approvalExpired
        else -> if (allowed) L.approvalAllowedSent else L.approvalRejectedSent
    }
    val tint = if (allowed) Dsh.success else Dsh.error
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(DshRadius.full))
            .background(tint.copy(alpha = 0.14f))
            .padding(start = 4.dp, end = 10.dp, top = 4.dp, bottom = 4.dp),
    ) {
        Box(
            modifier = Modifier
                .size(18.dp)
                .clip(CircleShape)
                .background(tint),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                CheckOutline14,
                contentDescription = null,
                tint = Dsh.onBrand,
                modifier = Modifier.size(11.dp),
            )
        }
        Spacer(Modifier.width(6.dp))
        Text(
            label,
            color = tint,
            style = DshType.t13M,
            fontWeight = FontWeight(500),
        )
    }
}