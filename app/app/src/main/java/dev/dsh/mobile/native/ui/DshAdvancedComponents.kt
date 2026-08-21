package dev.dsh.mobile.native.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import dev.dsh.mobile.core.Dsh

// ============================================================
// DshTextField —— 文本输入（借鉴 HStudio field-shell：48dp 高、focus 描边 + 3dp 焦点环、error 态红边）
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
        !enabled -> Dsh.borderL2
        isError -> Dsh.error
        focused -> Dsh.brand400
        else -> Dsh.borderL2
    }
    val ringColor = when {
        isError -> Dsh.errorBg
        focused -> Dsh.brand400.copy(alpha = 0.1f)
        else -> Color.Transparent
    }

    Column(modifier) {
        if (label != null) {
            Text(
                text = label,
                color = Dsh.labelSecondary,
                fontSize = 12.sp,
                fontWeight = FontWeight(500),
                modifier = Modifier.padding(bottom = 4.dp, start = 2.dp),
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .border(3.dp, ringColor, shape),
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                enabled = enabled,
                singleLine = singleLine,
                visualTransformation = visualTransformation,
                interactionSource = interaction,
                textStyle = TextStyle(
                    color = if (enabled) Dsh.labelPrimary else Dsh.labelDimmed,
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                ),
                modifier = Modifier.fillMaxWidth(),
                decorationBox = { innerTextField ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .background(Dsh.bgInput, shape)
                            .border(1.dp, borderColor, shape)
                            .padding(horizontal = 13.dp, vertical = 13.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        if (value.isEmpty() && placeholder != null) {
                            Text(
                                text = placeholder,
                                color = Dsh.labelTertiary,
                                fontSize = 13.sp,
                                lineHeight = 20.sp,
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
                fontSize = 11.sp,
                lineHeight = 16.sp,
                modifier = Modifier.padding(top = 4.dp, start = 2.dp),
            )
        }
    }
}

// ============================================================
// DshSelect —— 下拉选择（借鉴 HStudio AppSelect：宽度对齐触发器 + 上下空间自动翻转 + 高度自适应 72-240dp）
// 用 BoxWithConstraints 取得触发器宽度对齐下拉层
// ============================================================
@Composable
fun DshSelect(
    value: String,
    options: List<String>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    enabled: Boolean = true,
    contentDescription: String? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)

    Column(modifier) {
        if (label != null) {
            Text(
                text = label,
                color = Dsh.labelSecondary,
                fontSize = 12.sp,
                fontWeight = FontWeight(500),
                modifier = Modifier.padding(bottom = 4.dp, start = 2.dp),
            )
        }
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(Dsh.bgInput, shape)
                .border(1.dp, Dsh.borderL2, shape)
                .clickable(enabled = enabled) { expanded = !expanded }
                .padding(horizontal = 13.dp, vertical = 13.dp)
                .semantics {
                    role = Role.DropdownList
                    if (contentDescription != null) this.contentDescription = contentDescription
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = value,
                    color = Dsh.labelPrimary,
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "⌄",
                    color = Dsh.labelTertiary,
                    fontSize = 16.sp,
                    lineHeight = 16.sp,
                )
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier
                    .width(maxWidth)
                    .heightIn(max = 240.dp),
            ) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = option,
                                color = Dsh.labelPrimary,
                                fontSize = 13.sp,
                                lineHeight = 20.sp,
                            )
                        },
                        onClick = {
                            onSelect(option)
                            expanded = false
                        },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                    )
                }
            }
        }
    }
}

// ============================================================
// DshConfirmDialog —— 确认弹窗（借鉴 HStudio AppConfirmDialog：danger 红按钮）
// 受控组件 + 轻量全局单例 DshDialogs / DshDialogHost
// ============================================================
data class DshDialogRequest(
    val title: String,
    val message: String,
    val confirmLabel: String = "确定",
    val dismissLabel: String? = "取消",
    val danger: Boolean = false,
    val onConfirm: () -> Unit,
    val onDismiss: () -> Unit = {},
)

object DshDialogs {
    private val queue = mutableStateListOf<DshDialogRequest>()

    fun confirm(request: DshDialogRequest) {
        queue.add(request)
    }

    fun clear() {
        queue.clear()
    }

    @Composable
    internal fun Render() {
        val current = queue.firstOrNull()
        DshConfirmDialog(
            show = current != null,
            title = current?.title ?: "",
            message = current?.message ?: "",
            confirmLabel = current?.confirmLabel ?: "确定",
            dismissLabel = current?.dismissLabel,
            danger = current?.danger ?: false,
            onConfirm = {
                val req = queue.firstOrNull() ?: return@DshConfirmDialog
                req.onConfirm()
                queue.removeFirstOrNull()
            },
            onDismiss = {
                val req = queue.firstOrNull() ?: return@DshConfirmDialog
                req.onDismiss()
                queue.removeFirstOrNull()
            },
        )
    }
}

@Composable
fun DshDialogHost(content: @Composable () -> Unit) {
    content()
    DshDialogs.Render()
}

@Composable
fun DshConfirmDialog(
    show: Boolean,
    title: String,
    message: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    confirmLabel: String = "确定",
    dismissLabel: String? = "取消",
    danger: Boolean = false,
    contentDescription: String? = null,
) {
    if (!show) return
    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.86f)
                .clip(RoundedCornerShape(12.dp))
                .background(Dsh.bgLayer1)
                .border(1.dp, Dsh.borderL2, RoundedCornerShape(12.dp))
                .padding(20.dp)
                .semantics {
                    if (contentDescription != null) this.contentDescription = contentDescription
                },
        ) {
            Column {
                Text(
                    text = title,
                    color = Dsh.labelPrimary,
                    fontSize = 17.sp,
                    fontWeight = FontWeight(600),
                    lineHeight = 24.sp,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = message,
                    color = Dsh.labelSecondary,
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                )
                Spacer(Modifier.height(20.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (dismissLabel != null) {
                        TextButton(onClick = onDismiss) {
                            Text(
                                text = dismissLabel,
                                color = Dsh.labelSecondary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight(500),
                            )
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    val confirmColor = if (danger) Dsh.error else Dsh.brand400
                    TextButton(onClick = onConfirm) {
                        Text(
                            text = confirmLabel,
                            color = confirmColor,
                            fontSize = 13.sp,
                            fontWeight = FontWeight(600),
                        )
                    }
                }
            }
        }
    }
}

// ============================================================
// DshMenuButton —— 汉堡菜单（借鉴 HStudio menu-line ×3 纯 CSS 三横线）+ 活动状态点
// ============================================================
enum class DshActivityState { Idle, Active, Busy, Error }

@Composable
fun DshMenuButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    activityState: DshActivityState = DshActivityState.Idle,
    contentDescription: String = "菜单",
) {
    val dotColor = when (activityState) {
        DshActivityState.Idle -> Color.Transparent
        DshActivityState.Active -> Dsh.success
        DshActivityState.Busy -> Dsh.warn
        DshActivityState.Error -> Dsh.error
    }
    Box(
        modifier = modifier
            .size(40.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .semantics {
                role = Role.Button
                this.contentDescription = contentDescription
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            repeat(3) {
                Box(
                    modifier = Modifier
                        .width(18.dp)
                        .height(2.dp)
                        .clip(RoundedCornerShape(1.dp))
                        .background(Dsh.labelPrimary),
                )
            }
        }
        if (activityState != DshActivityState.Idle) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(2.dp, (-2).dp)
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(dotColor)
                    .border(1.5.dp, Dsh.bgBase, CircleShape),
            )
        }
    }
}

// ============================================================
// DshEnter —— 入场动效（借鉴 HStudio 卡片入场：淡入 + 上移 5dp / 0.22s）
// ============================================================
@Composable
fun DshEnter(
    modifier: Modifier = Modifier,
    visible: Boolean = true,
    content: @Composable () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(animationSpec = tween(220)) +
            slideInVertically(animationSpec = tween(220)) { 5 },
        exit = fadeOut(animationSpec = tween(150)),
    ) {
        content()
    }
}

// ============================================================
// DshDrawerItem —— 抽屉条目（借鉴 HStudio ConversationDrawer：运行中 spinner / 未读 / 失败 状态标记）
// ============================================================
enum class DshDrawerItemStatus { None, Running, Unread, Failed }

@Composable
fun DshDrawerItem(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    selected: Boolean = false,
    status: DshDrawerItemStatus = DshDrawerItemStatus.None,
    unreadCount: Int = 0,
    onClick: () -> Unit = {},
    contentDescription: String? = null,
) {
    val bg = if (selected) Dsh.bgNavActive else Color.Transparent
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .semantics {
                role = Role.Tab
                if (contentDescription != null) this.contentDescription = contentDescription
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = Dsh.labelPrimary,
                fontSize = 13.sp,
                lineHeight = 20.sp,
                fontWeight = FontWeight(500),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    color = Dsh.labelTertiary,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        when (status) {
            DshDrawerItemStatus.Running -> CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                color = Dsh.brand400,
                strokeWidth = 2.dp,
            )
            DshDrawerItemStatus.Failed -> DshBadge(
                color = Dsh.error,
                dot = true,
                contentDescription = "失败",
            )
            DshDrawerItemStatus.Unread -> if (unreadCount > 0) {
                DshBadge(color = Dsh.brand400, count = unreadCount)
            } else {
                DshBadge(color = Dsh.brand400, dot = true)
            }
            DshDrawerItemStatus.None -> {}
        }
    }
}

// ============================================================
// DshQueueOrbit —— 队列旋转轨道 + 动态 count 徽标（借鉴 HStudio queue-orbit 0.8s 旋转 + queue-count）
// ============================================================
@Composable
fun DshQueueOrbit(
    count: Int,
    modifier: Modifier = Modifier,
    spinning: Boolean = true,
    contentDescription: String? = null,
) {
    val transition = rememberInfiniteTransition(label = "queue-orbit")
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(800, easing = LinearEasing)),
        label = "rotation",
    )
    Box(
        modifier = modifier.size(36.dp),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(
            progress = 0.75f,
            modifier = Modifier
                .size(36.dp)
                .rotate(if (spinning) rotation else 0f),
            color = Dsh.brand400,
            strokeWidth = 2.dp,
            trackColor = Color.Transparent,
        )
        if (count > 0) {
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(Dsh.bgLayer3)
                    .border(1.dp, Dsh.borderL2, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (count > 99) "99+" else count.toString(),
                    color = Dsh.labelPrimary,
                    fontSize = 10.sp,
                    lineHeight = 14.sp,
                    fontWeight = FontWeight(600),
                    modifier = Modifier.semantics {
                        this.contentDescription = contentDescription ?: "队列 $count"
                    },
                )
            }
        }
    }
}
