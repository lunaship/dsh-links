package dev.deeplinks.core

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import androidx.core.view.WindowCompat
import dev.deeplinks.R
import dev.deeplinks.native.DshRadius

/**
 * DeepSeek Harness 设计系统颜色 Token 接口定义与主题管理器。
 * 支持暗色（Dark）和亮色（Light）两套 1:1 对齐 DSH 设计规范的配色，
 * 并支持通过系统设置 / App 内部偏好进行动态切换。
 */
@Stable
data class DshColors(
    val isDark: Boolean,
    val bgBase: Color,
    val bgSidePanel: Color,
    val bgCard: Color,
    val bgInput: Color,
    val bgSubtle: Color,
    val bgCode: Color,
    val bgCodeBanner: Color,
    val bgSelected: Color,
    val bgPressed: Color,
    // 原生抽屉（M3）：sheet 容器底 + 选中项胶囊底
    // 容器底与 bgSidePanel 同档（surfaceContainerLow，静态/动态取色一致），与内容面靠发丝线分层
    val bgDrawer: Color = Color.Unspecified,
    val bgNavSelected: Color = Color.Unspecified,
    val bgTrack: Color,
    val bgOverlay: Color,
    /** 思考轨迹等「凹进」面板：比画布更深一档（DeepSeek 签名块）。 */
    val bgRecessed: Color = Color.Unspecified,
    val labelPrimary: Color,
    val labelSecondary: Color,
    val labelTertiary: Color,
    val labelDimmed: Color,
    val borderSubtle: Color,
    val borderStrong: Color,
    val pressed: Color,
    val activated: Color,
    val brand400: Color,
    val brand500: Color,
    val success: Color,
    val warn: Color,
    val warnLabel: Color,
    val error: Color,
    val errorBg: Color,
    val buttonElevated: Color,
    val buttonFloating: Color,
    val bgSurface: Color = Color.Unspecified,
    val shadowCard: Color = Color.Unspecified,
    val bubbleBg: Color = Color.Unspecified,
    val bubbleHighlight: Color = Color.Unspecified,
    // 分段色：系统提示词 / 工具调用的语义色（跨主题稳定）
    val systemAccent: Color = Color.Unspecified,
    val toolsAccent: Color = Color.Unspecified,
    // 轨迹角色语义色（跨主题稳定，与 systemAccent/toolsAccent 同类）
    val traceReasoning: Color = Color.Unspecified,
    val traceApproval: Color = Color.Unspecified,
    val traceTodo: Color = Color.Unspecified,
    // 品牌色调叠加层（按钮/标签底），跨主题稳定
    val brandTint: Color = Color.Unspecified,
    /** 品牌/语义实心底上的内容色（brand500 / error 等），替代散落的硬编码 Color.White。 */
    val onBrand: Color = Color.Unspecified,
    // 状态容器配对（M3 container/on-container 语义，保证 WCAG AA）
    val successContent: Color = Color.Unspecified,
    val successContainer: Color = Color.Unspecified,
    val cloudContent: Color = Color.Unspecified,
    val cloudContainer: Color = Color.Unspecified,
)

val DarkDshColors = DshColors(
    isDark = true,
    // 近黑画布（对齐 DeepSeek Harness / Chat），表面阶梯叠微弱品牌蓝
    bgBase = Color(0xFF0E0E10),
    bgSidePanel = Color(0xFF141416),
    bgCard = Color(0xFF1A1A1E),
    bgInput = Color(0xFF222226),
    bgSubtle = Color(0xFF2A2A2F),
    bgCode = Color(0xFF141416),
    bgCodeBanner = Color(0xFF222226),
    bgSelected = Color(0xFF323238),
    bgPressed = Color(0xFF222226),
    bgDrawer = Color(0xFF141416),        // 抽屉容器底：与 bgSidePanel 同档
    bgNavSelected = Color(0xFF1A2744),   // 选中会话：DeepSeek Blue 弱底
    bgTrack = Color(0xFF2A2A2F),
    bgOverlay = Color(0x80000000),
    bgRecessed = Color(0xFF08080A),      // ThinkingTrace：比画布更深一档
    bgSurface = Color(0xFF161618),
    labelPrimary = Color(0xFFF9FAFB),
    labelSecondary = Color(0xFFCFD3D6),
    labelTertiary = Color(0xFF9AA0A6),
    labelDimmed = Color(0xFF43454A),
    borderSubtle = Color(0x1FFFFFFF),
    borderStrong = Color(0x29FFFFFF),
    pressed = Color(0x14FFFFFF),
    activated = Color(0x24FFFFFF),
    // DeepSeek Blue 一族：#4D6BFE 为主；深色上抬一档作链接/次强调
    brand400 = Color(0xFF6B86FE),
    brand500 = Color(0xFF4D6BFE),
    success = Color(0xFF22C55E),
    warn = Color(0xFFD97706),
    warnLabel = Color(0xFFB45309),
    error = Color(0xFFF25A5A),
    errorBg = Color(0x26F25A5A),
    buttonElevated = Color(0xFF2A2A2F),
    buttonFloating = Color(0xFF1A1A1E),
    shadowCard = Color(0x1F000000),
    bubbleBg = Color(0xFF1A2340),       // 用户气泡：蓝系弱底，不用灰墨
    bubbleHighlight = Color(0xFF2A3A66),
    systemAccent = Color(0xFF94A3B8),
    toolsAccent = Color(0xFF8BA3C7),    // 蓝灰弱强调，避免亮紫与品牌蓝抢层级
    traceReasoning = Color(0xFF7B93F8), // 推理：蓝系弱强调（非紫）
    traceApproval = Color(0xFFE07A3A),  // 审批：降噪橙
    traceTodo = Color(0xFF5BB8C9),
    brandTint = Color(0x1A4D6BFE),
    onBrand = Color(0xFFFFFFFF),
    successContent = Color(0xFF86EFAC),
    successContainer = Color(0xFF14532D),
    cloudContent = Color(0xFFB8C7FF),
    cloudContainer = Color(0xFF1E2A4A),
)

val LightDshColors = DshColors(
    isDark = false,
    // 背景阶梯
    bgBase = Color(0xFFFFFFFF),
    bgSidePanel = Color(0xFFF9FAFB),
    bgCard = Color(0xFFFFFFFF),
    bgInput = Color(0xFFFFFFFF),
    bgSubtle = Color(0xFFF1F3F5),
    bgCode = Color(0xFFF9FAFB),
    bgCodeBanner = Color(0xFFF9FAFB),
    bgSelected = Color(0xFFEBEEF2),
    bgPressed = Color(0xFFF1F3F5),
    bgDrawer = Color(0xFFF9FAFB),
    bgNavSelected = Color(0xFFE5EDFF),   // 选中会话：DeepSeek Blue 弱底
    // 与暗色同语义（bgTrack == bgSubtle）：F9FAFB 与白画布/抽屉底同档，胶囊与轨道会整块消失
    bgTrack = Color(0xFFF1F3F5),
    bgOverlay = Color(0x52000000),
    bgRecessed = Color(0xFFF3F4F6),      // ThinkingTrace：比白画布略凹
    bgSurface = Color(0xFFFFFFFF),
    // 文字
    labelPrimary = Color(0xFF0F1115),
    // 与 tertiary 拉开一档（7.5:1 vs 4.7:1）；原 #61666B 与 #70757A 几乎同色，浅色只剩两级灰
    labelSecondary = Color(0xFF50555C),
    // #70757A on white is 4.66:1（10–13sp 正文需 AA）
    labelTertiary = Color(0xFF70757A),
    labelDimmed = Color(0xFFE1E5EE),
    // 边框
    borderSubtle = Color(0x1A000000),
    borderStrong = Color(0x1F000000),
    // 交互反馈
    pressed = Color(0x0F263148),
    activated = Color(0x19263148),
    // DeepSeek Blue：实心主操作 #4D6BFE；链接/小字用更深一档保证 AA（#4D6BFE on white ≈4.0）
    brand400 = Color(0xFF3B5BDB),
    brand500 = Color(0xFF4D6BFE),
    // 状态色（语义保留、降噪）
    success = Color(0xFF22C55E),
    warn = Color(0xFFD97706),
    warnLabel = Color(0xFFB45309),
    // 降饱和一档：#EC1113 纯红配粉底过刺眼；#D92D20 on white ≈4.8:1
    error = Color(0xFFD92D20),
    errorBg = Color(0x1AD92D20),
    buttonElevated = Color(0xFFD1D5DB),
    buttonFloating = Color(0xFFFFFFFF),
    shadowCard = Color(0x0D000000),
    bubbleBg = Color(0xFFE9EDFF),
    bubbleHighlight = Color(0xFFD3DCFF),
    systemAccent = Color(0xFF64748B),
    toolsAccent = Color(0xFF5B7A9D),    // 蓝灰，避免紫与品牌蓝抢层级
    // 蓝系弱强调：降饱和、不与 brand500 同色（visual-rules 第 6 条）；11sp 标签需 AA，≈4.8:1
    traceReasoning = Color(0xFF5B6FB8),
    traceApproval = Color(0xFFD97706),
    traceTodo = Color(0xFF0E8A9A),
    brandTint = Color(0x1A4D6BFE),
    onBrand = Color(0xFFFFFFFF),
    successContent = Color(0xFF166534),
    successContainer = Color(0xFFDCFCE7),
    cloudContent = Color(0xFF3B5BDB),
    cloudContainer = Color(0xFFE9EDFF),
)

val LocalDshColors = staticCompositionLocalOf { DarkDshColors }

// Shared Material shape roles. Screens may still use DSH-specific shapes for
// expressive details, but Material components now receive stable semantic
// defaults instead of falling back to the library's unrelated defaults.
// 全部由 DshRadius 推导（单一真源）：extraSmall←sm、small←md、medium←lg、large←xl、extraLarge←dialog。
private val DshMaterialShapes = Shapes(
    extraSmall = RoundedCornerShape(DshRadius.sm),
    small = RoundedCornerShape(DshRadius.md),
    medium = RoundedCornerShape(DshRadius.lg),
    large = RoundedCornerShape(DshRadius.xl),
    extraLarge = RoundedCornerShape(DshRadius.dialog),
)

/** 全局主题设置管理器 */
object ThemeManager {
    private const val PREFS_NAME = "dsh_settings"
    private const val KEY_THEME = "theme"
    private const val KEY_DYNAMIC = "dynamic_color"

    var currentThemeMode by mutableStateOf("system")
        private set

    /** Material You 动态取色开关（Android 12+ 才有效）。 */
    var dynamicColor by mutableStateOf(false)
        private set

    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        currentThemeMode = prefs.getString(KEY_THEME, "system") ?: "system"
        dynamicColor = prefs.getBoolean(KEY_DYNAMIC, false)
    }

    fun setDynamicColor(context: Context, enabled: Boolean) {
        dynamicColor = enabled
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_DYNAMIC, enabled)
            .apply()
    }

    fun setThemeMode(context: Context, mode: String) {
        currentThemeMode = mode
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_THEME, mode)
            .apply()
    }

    fun toggleTheme(context: Context, isCurrentlyDark: Boolean) {
        val nextMode = if (isCurrentlyDark) "light" else "dark"
        setThemeMode(context, nextMode)
    }
}

/** 应用内字号（对照 DeepSeek 官方 App 1.2.6+；仅本地，不进服务端 AppSettings）。 */
object FontScaleManager {
    private const val PREFS_NAME = "dsh_settings"
    private const val KEY_FONT_SCALE = "font_scale"

    const val SMALL = "small"
    const val DEFAULT = "default"
    const val LARGE = "large"

    var currentScale by mutableStateOf(DEFAULT)
        private set

    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        currentScale = canonicalizeFontScale(prefs.getString(KEY_FONT_SCALE, DEFAULT))
    }

    fun setScale(context: Context, id: String) {
        currentScale = canonicalizeFontScale(id)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_FONT_SCALE, currentScale)
            .apply()
    }
}

fun canonicalizeFontScale(id: String?): String = when (id) {
    FontScaleManager.SMALL, FontScaleManager.LARGE -> id
    else -> FontScaleManager.DEFAULT
}

fun fontScaleMultiplier(id: String?): Float = when (canonicalizeFontScale(id)) {
    FontScaleManager.SMALL -> 0.88f
    FontScaleManager.LARGE -> 1.18f
    else -> 1f
}

/**
 * UI 字体偏好：默认跟随系统字体（原生观感，中文用户基线稳）；
 * 可选关闭改用 Plus Jakarta Sans 品牌字（拉丁 UI / Logo 位）。
 */
object UiFontManager {
    private const val PREFS_NAME = "dsh_settings"
    private const val KEY_SYSTEM_FONT = "ui_system_font"

    /** 默认 true：系统字体更像原生 App；Jakarta 仅作可选品牌字。 */
    var useSystemFont by mutableStateOf(true)
        private set

    fun init(context: Context) {
        useSystemFont = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_SYSTEM_FONT, true)
    }

    fun setUseSystemFont(context: Context, enabled: Boolean) {
        useSystemFont = enabled
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_SYSTEM_FONT, enabled)
            .apply()
    }
}

/**
 * 透明系统栏；图标亮暗由 [DshTheme] 按当前主题写入 InsetsController。
 * 不用 SystemBarStyle.dark：浅色主题下会把状态栏图标固定成浅色。
 */
fun ComponentActivity.enableDshEdgeToEdge() {
    enableEdgeToEdge(
        statusBarStyle = SystemBarStyle.auto(
            android.graphics.Color.TRANSPARENT,
            android.graphics.Color.TRANSPARENT,
        ),
        navigationBarStyle = SystemBarStyle.auto(
            android.graphics.Color.TRANSPARENT,
            android.graphics.Color.TRANSPARENT,
        ),
    )
}

/**
 * DSH 根主题包装组件。
 * 负责：
 * 1. 提供 LocalDshColors（深浅色两套 1:1 DSH token）；
 * 2. 全局状态栏/导航栏图标颜色动态适配：深色主题 → 浅色图标（isAppearanceLight=false），
 *    浅色主题 → 深色图标（isAppearanceLight=true），随 App 内主题切换实时生效。
 */
@Composable
fun DshTheme(
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        ThemeManager.init(context)
        LocaleManager.init(context)
        FontScaleManager.init(context)
        UiFontManager.init(context)
    }

    val systemDark = isSystemInDarkTheme()
    val isDark = when (ThemeManager.currentThemeMode) {
        "dark" -> true
        "light" -> false
        else -> systemDark
    }

    // Material You：开启且系统支持时用动态取色，否则回退静态调色板
    val colors = if (ThemeManager.dynamicColor) {
        dynamicDshColors(context, isDark) ?: if (isDark) DarkDshColors else LightDshColors
    } else if (isDark) {
        DarkDshColors
    } else {
        LightDshColors
    }
    val lang = LocaleManager.language
    val strings = if (lang == "en") DshStringsEn else DshStringsZh
    val recentsLabel = stringResource(R.string.app_name)

    // 系统栏图标亮暗随主题联动（背景保持透明，由 enableEdgeToEdge 设置）
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val activity = view.context.findActivity() ?: return@SideEffect
            val window = activity.window
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !isDark
            controller.isAppearanceLightNavigationBars = !isDark
            val recentsColor = colors.bgBase.toArgb()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                activity.setTaskDescription(
                    ActivityManager.TaskDescription.Builder()
                        .setLabel(recentsLabel)
                        .setPrimaryColor(recentsColor)
                        .build(),
                )
            } else {
                @Suppress("DEPRECATION")
                activity.setTaskDescription(
                    ActivityManager.TaskDescription(recentsLabel, null, recentsColor),
                )
            }
        }
    }

    val materialColors = if (isDark) {
        darkColorScheme(
            primary = colors.brand400,
            onPrimary = Color(0xFF0F1115),
            // container/on-container 配对：深蓝容器 + 浅字（DeepSeek Blue 族）
            primaryContainer = Color(0xFF1A2744),
            onPrimaryContainer = Color(0xFFDCE7FF),
            secondary = colors.brand400,
            onSecondary = Color(0xFF0F1115),
            secondaryContainer = colors.bgSubtle,
            onSecondaryContainer = colors.labelPrimary,
            tertiary = Color(0xFF86EFAC),
            onTertiary = Color(0xFF14532D),
            tertiaryContainer = Color(0xFF14532D),
            onTertiaryContainer = Color(0xFFBBF7D0),
            background = colors.bgBase,
            onBackground = colors.labelPrimary,
            surface = colors.bgSurface,
            onSurface = colors.labelPrimary,
            surfaceVariant = colors.bgSubtle,
            onSurfaceVariant = colors.labelSecondary,
            outline = colors.labelTertiary,
            outlineVariant = colors.borderStrong,
            error = colors.error,
            onError = Color(0xFF690005),
            errorContainer = Color(0xFF93000A),
            onErrorContainer = Color(0xFFFFDAD6),
            inverseSurface = colors.labelPrimary,
            inverseOnSurface = colors.bgBase,
            inversePrimary = colors.brand400,
            scrim = Color.Black,
        )
    } else {
        lightColorScheme(
            primary = colors.brand400,
            onPrimary = Color.White,
            // #2563D8 上放 #0F1115 只有 ~3.47:1；改用浅蓝容器 + 深蓝文字（≥ 9:1）
            primaryContainer = Color(0xFFD8E4FF),
            onPrimaryContainer = Color(0xFF0A2A66),
            secondary = colors.brand400,
            onSecondary = Color.White,
            secondaryContainer = Color(0xFFE5EDFF),
            onSecondaryContainer = colors.labelPrimary,
            tertiary = Color(0xFF166534),
            onTertiary = Color.White,
            tertiaryContainer = Color(0xFFDCFCE7),
            onTertiaryContainer = Color(0xFF14532D),
            background = colors.bgBase,
            onBackground = colors.labelPrimary,
            surface = colors.bgSurface,
            onSurface = colors.labelPrimary,
            surfaceVariant = colors.bgSubtle,
            onSurfaceVariant = colors.labelSecondary,
            outline = colors.labelSecondary,
            outlineVariant = colors.borderStrong,
            error = colors.error,
            onError = Color.White,
            errorContainer = Color(0xFFFFDAD6),
            onErrorContainer = Color(0xFF410002),
            inverseSurface = colors.labelPrimary,
            inverseOnSurface = colors.bgBase,
            inversePrimary = colors.brand500,
            scrim = Color.Black,
        )
    }

    val baseDensity = LocalDensity.current
    val fontMultiplier = fontScaleMultiplier(FontScaleManager.currentScale)
    val uiFontFamily = if (UiFontManager.useSystemFont) FontFamily.Default else DshFontFamily
    val typography = remember(uiFontFamily) { dshTypography(uiFontFamily) }
    MaterialTheme(
        colorScheme = materialColors,
        typography = typography,
        shapes = DshMaterialShapes,
    ) {
        CompositionLocalProvider(
            LocalDshColors provides colors,
            LocalDshStrings provides strings,
            LocalTextStyle provides typography.bodyMedium,
            LocalDshFontFamily provides uiFontFamily,
            LocalDensity provides Density(
                density = baseDensity.density,
                fontScale = baseDensity.fontScale * fontMultiplier,
            ),
        ) {
            content()
        }
    }
}

/** App-wide UI font (Plus Jakarta Sans). Code blocks keep [FontFamily.Monospace]. */
val LocalDshFontFamily = staticCompositionLocalOf<FontFamily> { FontFamily.Default }

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * 兼容原有 Dsh 访问方式的代理对象，所有属性自动读取当前 Composable 上下文的真实主题色。
 */
object Dsh {
    val isDark: Boolean
        @Composable
        @ReadOnlyComposable
        get() = LocalDshColors.current.isDark

    val bgBase: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalDshColors.current.bgBase

    val bgSidePanel: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalDshColors.current.bgSidePanel

    val bgCard: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalDshColors.current.bgCard

    val bgInput: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalDshColors.current.bgInput

    val bgSubtle: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalDshColors.current.bgSubtle

    val bgCode: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalDshColors.current.bgCode

    val bgCodeBanner: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalDshColors.current.bgCodeBanner

    val bgSelected: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalDshColors.current.bgSelected

    val bgDrawer: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalDshColors.current.bgDrawer

    val bgNavSelected: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalDshColors.current.bgNavSelected

    val bgPressed: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalDshColors.current.bgPressed

    val bgTrack: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalDshColors.current.bgTrack

    val bgOverlay: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalDshColors.current.bgOverlay

    val bgRecessed: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalDshColors.current.bgRecessed

    val labelPrimary: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalDshColors.current.labelPrimary

    val labelSecondary: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalDshColors.current.labelSecondary

    val labelTertiary: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalDshColors.current.labelTertiary


    val labelDimmed: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalDshColors.current.labelDimmed

    val borderSubtle: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalDshColors.current.borderSubtle


    val borderStrong: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalDshColors.current.borderStrong


    val pressed: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalDshColors.current.pressed

    val activated: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalDshColors.current.activated

    val brand400: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalDshColors.current.brand400


    val brand500: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalDshColors.current.brand500



    val success: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalDshColors.current.success

    val warn: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalDshColors.current.warn

    val warnLabel: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalDshColors.current.warnLabel

    val error: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalDshColors.current.error

    val errorBg: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalDshColors.current.errorBg

    val buttonElevated: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalDshColors.current.buttonElevated

    val buttonFloating: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalDshColors.current.buttonFloating

    val bgSurface: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalDshColors.current.bgSurface


    val shadowCard: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalDshColors.current.shadowCard

    val bubbleBg: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalDshColors.current.bubbleBg

    val bubbleHighlight: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalDshColors.current.bubbleHighlight

    val systemAccent: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalDshColors.current.systemAccent

    val toolsAccent: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalDshColors.current.toolsAccent

    val traceReasoning: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalDshColors.current.traceReasoning

    val traceApproval: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalDshColors.current.traceApproval

    val traceTodo: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalDshColors.current.traceTodo

    val brandTint: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalDshColors.current.brandTint

    val onBrand: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalDshColors.current.onBrand

    val successContent: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalDshColors.current.successContent

    val successContainer: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalDshColors.current.successContainer

    val cloudContent: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalDshColors.current.cloudContent

    val cloudContainer: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalDshColors.current.cloudContainer
}
