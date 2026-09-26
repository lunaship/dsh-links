package dev.deeplinks.core

import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp

/**
 * 全局统一触摸反馈：低 alpha 的品牌 ripple。
 *
 * 原生手感的第一信号——指尖挡住按钮时，ripple 是唯一告诉用户「按到了」的反馈。
 * 之前全 App 用 `indication = dshRipple()` 关掉了它（网页 `:active` 思维），
 * 恢复时统一走低 alpha，避免 Material 默认 ripple 过重。
 *
 * 用法：`.clickable(interactionSource = interaction, indication = dshRipple(), onClick = ...)`
 */
@Composable
fun dshRipple(
    bounded: Boolean = true,
    radius: Dp = Dp.Unspecified,
    color: Color = Color.Unspecified,
) = ripple(
    color = if (color == Color.Unspecified) {
        LocalDshColors.current.labelPrimary.copy(alpha = 0.12f)
    } else {
        color
    },
    bounded = bounded,
    radius = radius,
)
