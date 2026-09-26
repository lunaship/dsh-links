package dev.deeplinks.core

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import dev.deeplinks.R

/**
 * DeepLinks type system — Plus Jakarta Sans.
 * Latin/UI copy uses Jakarta; CJK glyphs fall back to the system sans.
 */
val DshFontFamily = FontFamily(
    Font(R.font.plus_jakarta_sans_regular, FontWeight.Normal),
    Font(R.font.plus_jakarta_sans_medium, FontWeight.Medium),
    Font(R.font.plus_jakarta_sans_semibold, FontWeight.SemiBold),
    Font(R.font.plus_jakarta_sans_bold, FontWeight.Bold),
)

fun dshTypography(family: FontFamily): Typography = Typography(
    displayLarge = TextStyle(
        fontFamily = family,
        fontWeight = FontWeight.Bold,
        fontSize = 34.sp,
        lineHeight = 40.sp,
        letterSpacing = (-0.4).sp,
    ),
    displayMedium = TextStyle(
        fontFamily = family,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 34.sp,
        letterSpacing = (-0.3).sp,
    ),
    headlineLarge = TextStyle(
        fontFamily = family,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 30.sp,
        letterSpacing = (-0.2).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = family,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
        letterSpacing = (-0.15).sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = family,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 24.sp,
        letterSpacing = (-0.1).sp,
    ),
    titleLarge = TextStyle(
        fontFamily = family,
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = family,
        fontWeight = FontWeight.Medium,
        fontSize = 15.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.01.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = family,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.02.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = family,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 26.sp,
        letterSpacing = 0.01.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = family,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.01.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = family,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.02.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = family,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.02.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = family,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.03.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = family,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.04.sp,
    ),
)

/**
 * 语义排版入口：业务代码只引用角色名，禁止再写裸 fontSize/lineHeight。
 *
 * 每个角色映射到 [dshTypography] 定义的字阶，因此应用内字号（FontScaleManager）
 * 与系统 fontScale 会自动生效，且全 App 排版收敛到同一套语义。
 * 尺寸→角色：12→bodySmall、13→titleSmall、15→bodyMedium、15→titleMedium、
 * 16→bodyLarge、17→titleLarge、18→headlineSmall、20→headlineMedium、24→headlineLarge、
 * 28→displayMedium、34→displayLarge。
 */
object DshType {
    val caption: TextStyle
        @Composable @ReadOnlyComposable get() = MaterialTheme.typography.bodySmall

    val label: TextStyle
        @Composable @ReadOnlyComposable get() = MaterialTheme.typography.labelMedium

    val titleSmall: TextStyle
        @Composable @ReadOnlyComposable get() = MaterialTheme.typography.titleSmall

    val body: TextStyle
        @Composable @ReadOnlyComposable get() = MaterialTheme.typography.bodyMedium

    val title: TextStyle
        @Composable @ReadOnlyComposable get() = MaterialTheme.typography.titleMedium

    val bodyLarge: TextStyle
        @Composable @ReadOnlyComposable get() = MaterialTheme.typography.bodyLarge

    val titleLarge: TextStyle
        @Composable @ReadOnlyComposable get() = MaterialTheme.typography.titleLarge

    val headline: TextStyle
        @Composable @ReadOnlyComposable get() = MaterialTheme.typography.headlineSmall

    val headlineMedium: TextStyle
        @Composable @ReadOnlyComposable get() = MaterialTheme.typography.headlineMedium

    val display: TextStyle
        @Composable @ReadOnlyComposable get() = MaterialTheme.typography.displayMedium

    val displayLarge: TextStyle
        @Composable @ReadOnlyComposable get() = MaterialTheme.typography.displayLarge

    // ===== 产品实际在用的密集字号（补齐后即可零视觉变化地替换裸 .sp）=====

    /** 12/18：密集次要文本（比 caption 松一行）。 */
    val captionRelaxed: TextStyle
        @Composable @ReadOnlyComposable
        get() = TextStyle(
            fontFamily = LocalDshFontFamily.current,
            fontWeight = FontWeight.Normal,
            fontSize = 12.sp,
            lineHeight = 18.sp,
            letterSpacing = 0.01.sp,
        )

    /** 13/20：密集正文。 */
    val bodyDense: TextStyle
        @Composable @ReadOnlyComposable
        get() = TextStyle(
            fontFamily = LocalDshFontFamily.current,
            fontWeight = FontWeight.Normal,
            fontSize = 13.sp,
            lineHeight = 20.sp,
            letterSpacing = 0.01.sp,
        )

    /** 11/16：微标签（比 labelSmall 松两行）。 */
    val microRelaxed: TextStyle
        @Composable @ReadOnlyComposable
        get() = TextStyle(
            fontFamily = LocalDshFontFamily.current,
            fontWeight = FontWeight.Normal,
            fontSize = 11.sp,
            lineHeight = 16.sp,
            letterSpacing = 0.01.sp,
        )

    val labelLarge: TextStyle
        @Composable @ReadOnlyComposable get() = MaterialTheme.typography.labelLarge

    /** 11/12 · SemiBold */
    val t11x12SB: TextStyle
        @Composable @ReadOnlyComposable
        get() = TextStyle(
            fontFamily = LocalDshFontFamily.current,
            fontWeight = FontWeight.SemiBold,
            fontSize = 11.sp,
            lineHeight = 12.sp,
            letterSpacing = 0.01.sp,
        )

    /** 11/22 · Normal */
    val t11: TextStyle
        @Composable @ReadOnlyComposable
        get() = TextStyle(
            fontFamily = LocalDshFontFamily.current,
            fontWeight = FontWeight.Normal,
            fontSize = 11.sp,
            lineHeight = 22.sp,
            letterSpacing = 0.01.sp,
        )

    /** 11/22 · Medium */
    val t11M: TextStyle
        @Composable @ReadOnlyComposable
        get() = TextStyle(
            fontFamily = LocalDshFontFamily.current,
            fontWeight = FontWeight.Medium,
            fontSize = 11.sp,
            lineHeight = 22.sp,
            letterSpacing = 0.01.sp,
        )

    /** 11/22 · SemiBold */
    val t11SB: TextStyle
        @Composable @ReadOnlyComposable
        get() = TextStyle(
            fontFamily = LocalDshFontFamily.current,
            fontWeight = FontWeight.SemiBold,
            fontSize = 11.sp,
            lineHeight = 22.sp,
            letterSpacing = 0.01.sp,
        )

    /** 11/14 · SemiBold */
    val t11x14SB: TextStyle
        @Composable @ReadOnlyComposable
        get() = TextStyle(
            fontFamily = LocalDshFontFamily.current,
            fontWeight = FontWeight.SemiBold,
            fontSize = 11.sp,
            lineHeight = 14.sp,
            letterSpacing = 0.01.sp,
        )

    /** 11/15 · Medium */
    val t11x15M: TextStyle
        @Composable @ReadOnlyComposable
        get() = TextStyle(
            fontFamily = LocalDshFontFamily.current,
            fontWeight = FontWeight.Medium,
            fontSize = 11.sp,
            lineHeight = 15.sp,
            letterSpacing = 0.01.sp,
        )

    /** 11/16 · Medium */
    val t11x16M: TextStyle
        @Composable @ReadOnlyComposable
        get() = TextStyle(
            fontFamily = LocalDshFontFamily.current,
            fontWeight = FontWeight.Medium,
            fontSize = 11.sp,
            lineHeight = 16.sp,
            letterSpacing = 0.01.sp,
        )

    /** 11/17 · Normal */
    val t11x17: TextStyle
        @Composable @ReadOnlyComposable
        get() = TextStyle(
            fontFamily = LocalDshFontFamily.current,
            fontWeight = FontWeight.Normal,
            fontSize = 11.sp,
            lineHeight = 17.sp,
            letterSpacing = 0.01.sp,
        )

    /** 11/18 · Normal */
    val t11x18: TextStyle
        @Composable @ReadOnlyComposable
        get() = TextStyle(
            fontFamily = LocalDshFontFamily.current,
            fontWeight = FontWeight.Normal,
            fontSize = 11.sp,
            lineHeight = 18.sp,
            letterSpacing = 0.01.sp,
        )

    /** 12/22 · Normal */
    val t12: TextStyle
        @Composable @ReadOnlyComposable
        get() = TextStyle(
            fontFamily = LocalDshFontFamily.current,
            fontWeight = FontWeight.Normal,
            fontSize = 12.sp,
            lineHeight = 22.sp,
            letterSpacing = 0.01.sp,
        )

    /** 12/22 · Medium */
    val t12M: TextStyle
        @Composable @ReadOnlyComposable
        get() = TextStyle(
            fontFamily = LocalDshFontFamily.current,
            fontWeight = FontWeight.Medium,
            fontSize = 12.sp,
            lineHeight = 22.sp,
            letterSpacing = 0.01.sp,
        )

    /** 12/17 · Normal */
    val t12x17: TextStyle
        @Composable @ReadOnlyComposable
        get() = TextStyle(
            fontFamily = LocalDshFontFamily.current,
            fontWeight = FontWeight.Normal,
            fontSize = 12.sp,
            lineHeight = 17.sp,
            letterSpacing = 0.01.sp,
        )

    /** 12/18 · Medium */
    val t12x18M: TextStyle
        @Composable @ReadOnlyComposable
        get() = TextStyle(
            fontFamily = LocalDshFontFamily.current,
            fontWeight = FontWeight.Medium,
            fontSize = 12.sp,
            lineHeight = 18.sp,
            letterSpacing = 0.01.sp,
        )

    /** 12/20 · Normal */
    val t12x20: TextStyle
        @Composable @ReadOnlyComposable
        get() = TextStyle(
            fontFamily = LocalDshFontFamily.current,
            fontWeight = FontWeight.Normal,
            fontSize = 12.sp,
            lineHeight = 20.sp,
            letterSpacing = 0.01.sp,
        )

    /** 12/20 · Medium */
    val t12x20M: TextStyle
        @Composable @ReadOnlyComposable
        get() = TextStyle(
            fontFamily = LocalDshFontFamily.current,
            fontWeight = FontWeight.Medium,
            fontSize = 12.sp,
            lineHeight = 20.sp,
            letterSpacing = 0.01.sp,
        )

    /** 12/24 · SemiBold */
    val t12x24SB: TextStyle
        @Composable @ReadOnlyComposable
        get() = TextStyle(
            fontFamily = LocalDshFontFamily.current,
            fontWeight = FontWeight.SemiBold,
            fontSize = 12.sp,
            lineHeight = 24.sp,
            letterSpacing = 0.01.sp,
        )

    /** 13/22 · Normal */
    val t13: TextStyle
        @Composable @ReadOnlyComposable
        get() = TextStyle(
            fontFamily = LocalDshFontFamily.current,
            fontWeight = FontWeight.Normal,
            fontSize = 13.sp,
            lineHeight = 22.sp,
            letterSpacing = 0.01.sp,
        )

    /** 13/22 · Medium */
    val t13M: TextStyle
        @Composable @ReadOnlyComposable
        get() = TextStyle(
            fontFamily = LocalDshFontFamily.current,
            fontWeight = FontWeight.Medium,
            fontSize = 13.sp,
            lineHeight = 22.sp,
            letterSpacing = 0.01.sp,
        )

    /** 13/22 · SemiBold */
    val t13SB: TextStyle
        @Composable @ReadOnlyComposable
        get() = TextStyle(
            fontFamily = LocalDshFontFamily.current,
            fontWeight = FontWeight.SemiBold,
            fontSize = 13.sp,
            lineHeight = 22.sp,
            letterSpacing = 0.01.sp,
        )

    /** 13/18 · Normal */
    val t13x18: TextStyle
        @Composable @ReadOnlyComposable
        get() = TextStyle(
            fontFamily = LocalDshFontFamily.current,
            fontWeight = FontWeight.Normal,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            letterSpacing = 0.01.sp,
        )

    /** 13/18 · SemiBold */
    val t13x18SB: TextStyle
        @Composable @ReadOnlyComposable
        get() = TextStyle(
            fontFamily = LocalDshFontFamily.current,
            fontWeight = FontWeight.SemiBold,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            letterSpacing = 0.01.sp,
        )

    /** 13/20 · Medium */
    val t13x20M: TextStyle
        @Composable @ReadOnlyComposable
        get() = TextStyle(
            fontFamily = LocalDshFontFamily.current,
            fontWeight = FontWeight.Medium,
            fontSize = 13.sp,
            lineHeight = 20.sp,
            letterSpacing = 0.01.sp,
        )

    /** 13/24 · Medium */
    val t13x24M: TextStyle
        @Composable @ReadOnlyComposable
        get() = TextStyle(
            fontFamily = LocalDshFontFamily.current,
            fontWeight = FontWeight.Medium,
            fontSize = 13.sp,
            lineHeight = 24.sp,
            letterSpacing = 0.01.sp,
        )

    /** 14/22 · Normal */
    val t14: TextStyle
        @Composable @ReadOnlyComposable
        get() = TextStyle(
            fontFamily = LocalDshFontFamily.current,
            fontWeight = FontWeight.Normal,
            fontSize = 14.sp,
            lineHeight = 22.sp,
            letterSpacing = 0.01.sp,
        )

    /** 14/22 · Medium */
    val t14M: TextStyle
        @Composable @ReadOnlyComposable
        get() = TextStyle(
            fontFamily = LocalDshFontFamily.current,
            fontWeight = FontWeight.Medium,
            fontSize = 14.sp,
            lineHeight = 22.sp,
            letterSpacing = 0.01.sp,
        )

    /** 14/22 · SemiBold */
    val t14SB: TextStyle
        @Composable @ReadOnlyComposable
        get() = TextStyle(
            fontFamily = LocalDshFontFamily.current,
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp,
            lineHeight = 22.sp,
            letterSpacing = 0.01.sp,
        )

    /** 14/20 · Normal */
    val t14x20: TextStyle
        @Composable @ReadOnlyComposable
        get() = TextStyle(
            fontFamily = LocalDshFontFamily.current,
            fontWeight = FontWeight.Normal,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            letterSpacing = 0.01.sp,
        )

    /** 14/20 · SemiBold */
    val t14x20SB: TextStyle
        @Composable @ReadOnlyComposable
        get() = TextStyle(
            fontFamily = LocalDshFontFamily.current,
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            letterSpacing = 0.01.sp,
        )

    /** 14/24 · Normal */
    val t14x24: TextStyle
        @Composable @ReadOnlyComposable
        get() = TextStyle(
            fontFamily = LocalDshFontFamily.current,
            fontWeight = FontWeight.Normal,
            fontSize = 14.sp,
            lineHeight = 24.sp,
            letterSpacing = 0.01.sp,
        )

    /** 15/22 · SemiBold */
    val t15SB: TextStyle
        @Composable @ReadOnlyComposable
        get() = TextStyle(
            fontFamily = LocalDshFontFamily.current,
            fontWeight = FontWeight.SemiBold,
            fontSize = 15.sp,
            lineHeight = 22.sp,
            letterSpacing = 0.01.sp,
        )

    /** 15/20 · Normal */
    val t15x20: TextStyle
        @Composable @ReadOnlyComposable
        get() = TextStyle(
            fontFamily = LocalDshFontFamily.current,
            fontWeight = FontWeight.Normal,
            fontSize = 15.sp,
            lineHeight = 20.sp,
            letterSpacing = 0.01.sp,
        )

    /** 15/20 · Medium */
    val t15x20M: TextStyle
        @Composable @ReadOnlyComposable
        get() = TextStyle(
            fontFamily = LocalDshFontFamily.current,
            fontWeight = FontWeight.Medium,
            fontSize = 15.sp,
            lineHeight = 20.sp,
            letterSpacing = 0.01.sp,
        )

    /** 15/21 · Medium */
    val t15x21M: TextStyle
        @Composable @ReadOnlyComposable
        get() = TextStyle(
            fontFamily = LocalDshFontFamily.current,
            fontWeight = FontWeight.Medium,
            fontSize = 15.sp,
            lineHeight = 21.sp,
            letterSpacing = 0.01.sp,
        )

    /** 15/21 · SemiBold */
    val t15x21SB: TextStyle
        @Composable @ReadOnlyComposable
        get() = TextStyle(
            fontFamily = LocalDshFontFamily.current,
            fontWeight = FontWeight.SemiBold,
            fontSize = 15.sp,
            lineHeight = 21.sp,
            letterSpacing = 0.01.sp,
        )

    /** 15/23 · Normal */
    val t15x23: TextStyle
        @Composable @ReadOnlyComposable
        get() = TextStyle(
            fontFamily = LocalDshFontFamily.current,
            fontWeight = FontWeight.Normal,
            fontSize = 15.sp,
            lineHeight = 23.sp,
            letterSpacing = 0.01.sp,
        )

    /** 16/22 · SemiBold */
    val t16SB: TextStyle
        @Composable @ReadOnlyComposable
        get() = TextStyle(
            fontFamily = LocalDshFontFamily.current,
            fontWeight = FontWeight.SemiBold,
            fontSize = 16.sp,
            lineHeight = 22.sp,
            letterSpacing = 0.01.sp,
        )

    /** 16/28 · Normal */
    val t16x28: TextStyle
        @Composable @ReadOnlyComposable
        get() = TextStyle(
            fontFamily = LocalDshFontFamily.current,
            fontWeight = FontWeight.Normal,
            fontSize = 16.sp,
            lineHeight = 28.sp,
            letterSpacing = 0.01.sp,
        )

    /** 17/22 · SemiBold */
    val t17SB: TextStyle
        @Composable @ReadOnlyComposable
        get() = TextStyle(
            fontFamily = LocalDshFontFamily.current,
            fontWeight = FontWeight.SemiBold,
            fontSize = 17.sp,
            lineHeight = 22.sp,
            letterSpacing = 0.01.sp,
        )

    /** 18/22 · SemiBold */
    val t18SB: TextStyle
        @Composable @ReadOnlyComposable
        get() = TextStyle(
            fontFamily = LocalDshFontFamily.current,
            fontWeight = FontWeight.SemiBold,
            fontSize = 18.sp,
            lineHeight = 22.sp,
            letterSpacing = 0.01.sp,
        )

    /** 20/28 · SemiBold */
    val t20x28SB: TextStyle
        @Composable @ReadOnlyComposable
        get() = TextStyle(
            fontFamily = LocalDshFontFamily.current,
            fontWeight = FontWeight.SemiBold,
            fontSize = 20.sp,
            lineHeight = 28.sp,
            letterSpacing = 0.01.sp,
        )

    /** 26/32 · Medium */
    val t26x32M: TextStyle
        @Composable @ReadOnlyComposable
        get() = TextStyle(
            fontFamily = LocalDshFontFamily.current,
            fontWeight = FontWeight.Medium,
            fontSize = 26.sp,
            lineHeight = 32.sp,
            letterSpacing = 0.01.sp,
        )
}
