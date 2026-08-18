package dev.dsh.mobile

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * DeepSeek Harness 设计系统颜色 Token 接口定义与主题管理器。
 * 支持暗色（Dark）和亮色（Light）两套 1:1 对齐 DSH 设计规范的配色，
 * 并支持通过系统设置 / App 内部偏好进行动态切换。
 */
@Stable
class DshColors(
    val isDark: Boolean,
    val bgBase: Color,
    val bgSidebar: Color,
    val bgLayer1: Color,
    val bgInput: Color,
    val bgLayer3: Color,
    val bgCode: Color,
    val bgCodeBanner: Color,
    val bgNavActive: Color,
    val bgNavHover: Color,
    val bgSelector: Color,
    val bgOverlay: Color,
    val labelPrimary: Color,
    val labelSecondary: Color,
    val labelTertiary: Color,
    val labelCaption: Color,
    val labelDimmed: Color,
    val borderL1: Color,
    val borderL2: Color,
    val borderL3: Color,
    val borderL4: Color,
    val hover: Color,
    val active: Color,
    val brand400: Color,
    val brand450: Color,
    val brand500: Color,
    val brand200: Color,
    val brand800: Color,
    val success: Color,
    val warn: Color,
    val warnLabel: Color,
    val error: Color,
    val errorBg: Color,
    val buttonElevated: Color,
    val buttonFloating: Color,
)

val DarkDshColors = DshColors(
    isDark = true,
    bgBase = Color(0xFF151517),
    bgSidebar = Color(0xFF1B1B1C),
    bgLayer1 = Color(0xFF232324),
    bgInput = Color(0xFF2C2C2E),
    bgLayer3 = Color(0xFF353638),
    bgCode = Color(0xFF1B1B1C),
    bgCodeBanner = Color(0xFF2C2C2E),
    bgNavActive = Color(0xFF43454A),
    bgNavHover = Color(0xFF2C2C2E),
    bgSelector = Color(0xFF353638),
    bgOverlay = Color(0x80000000),
    labelPrimary = Color(0xFFF9FAFB),
    labelSecondary = Color(0xFFCFD3D6),
    labelTertiary = Color(0xFFADB2B8),
    labelCaption = Color(0xFF81858C),
    labelDimmed = Color(0xFF43454A),
    borderL1 = Color(0x0FFFFFFF),
    borderL2 = Color(0x1FFFFFFF),
    borderL3 = Color(0x29FFFFFF),
    borderL4 = Color(0x33FFFFFF),
    hover = Color(0x14FFFFFF),
    active = Color(0x24FFFFFF),
    brand400 = Color(0xFF679EFE),
    brand450 = Color(0xFF5686FE),
    brand500 = Color(0xFF4176E6),
    brand200 = Color(0xFFD3E2FF),
    brand800 = Color(0xFF34415B),
    success = Color(0xFF22C55E),
    warn = Color(0xFFF59E0B),
    warnLabel = Color(0xFFDD8629),
    error = Color(0xFFF25A5A),
    errorBg = Color(0x26F25A5A),
    buttonElevated = Color(0xFF43454A),
    buttonFloating = Color(0xFF2C2C2E),
)

val LightDshColors = DshColors(
    isDark = false,
    // 背景 — CSS body{} alias tokens
    bgBase = Color(0xFFFFFFFF),           // alias-bg-base: bluish-00
    bgSidebar = Color(0xFFF9FAFB),        // specific-sidebar-fill: bluish-50
    bgLayer1 = Color(0xFFFFFFFF),         // alias-bg-layer-1: bluish-00
    bgInput = Color(0xFFFFFFFF),          // specific-input-major: bluish-00
    bgLayer3 = Color(0xFFF1F3F5),         // alias-bg-layer-3: bluish-75
    bgCode = Color(0xFFF9FAFB),           // alias-markdown-code-block: bluish-50
    bgCodeBanner = Color(0xFFF9FAFB),     // alias-markdown-code-block-banner: bluish-50
    bgNavActive = Color(0xFFEBEEF2),      // specific-sidebar-nav-item-active: bluish-100
    bgNavHover = Color(0xFFF1F3F5),       // specific-sidebar-nav-item-hover: bluish-75
    bgSelector = Color(0xFFF9FAFB),       // specific-selector: bluish-60
    bgOverlay = Color(0x80000000),        // 弹窗/抽屉遮罩跨主题一致：50% 半透明黑（dark 一致）；light 原 alias-bg-overlay 偏白导致弹窗背景大白
    // 文字 — CSS body{} alias tokens
    labelPrimary = Color(0xFF0F1115),     // alias-label-primary: bluish-1000
    labelSecondary = Color(0xFF61666B),   // alias-label-secondary: bluish-700
    labelTertiary = Color(0xFF81858C),    // alias-label-tertiary: bluish-600
    labelCaption = Color(0xFF81858C),     // 浅色主题下偏浅导致对比度 2.0:1 不可读；改与 labelTertiary 同色以保 4.5:1 WCAG AA
    labelDimmed = Color(0xFFE1E5EE),      // alias-label-dimmed: bluish-200（DshComponents disabled 态使用）
    // 边框 — CSS body{} alias tokens
    borderL1 = Color(0x0A000000),         // alias-border-l1: rgba(0,0,0,.04)
    borderL2 = Color(0x1A000000),         // alias-border-l2: rgba(0,0,0,.1)
    borderL3 = Color(0x1F000000),         // alias-border-l3: rgba(0,0,0,.12)
    borderL4 = Color(0x29000000),         // alias-border-l4: rgba(0,0,0,.16)
    // 交互 — CSS body{} alias tokens
    hover = Color(0x0F263148),            // alias-interactive-bg-hover: rgba(38,49,72,.06)
    active = Color(0x19263148),           // alias-interactive-bg-active: rgba(38,49,72,.1)
    // 品牌 — CSS body{} alias tokens
    brand400 = Color(0xFF4D88FF),         // deepseek-400 (light variant)
    brand450 = Color(0xFF3B7BF6),         // deepseek-450 (light variant)
    brand500 = Color(0xFF0F1115),         // alias-brand-primary: bluish-1000
    brand200 = Color(0xFFBFDBFE),         // deepseek-200
    brand800 = Color(0xFF0F1115),         // alias-brand-primary fallback
    // 状态色 — CSS body{} alias tokens
    success = Color(0xFF22C55E),          // alias-state-success-primary: green-500
    warn = Color(0xFFD97706),             // alias-state-warn-primary: amber-500
    warnLabel = Color(0xFFDD8629),        // alias-state-warn-label: amber-600
    error = Color(0xFFEC1113),            // alias-state-error-primary: red-600
    errorBg = Color(0x26EC1113),          // error hover: rgba(236,19,19,.15)——与 dark 同 alpha 层级（原 5% 浅色下几乎不可见）
    // 按钮 — CSS body{} alias tokens
    buttonElevated = Color(0xFFD1D5DB),   // 浅色主题下不能用纯白（白底白=消失，禁用态看不见）；改用中性浅灰保证轮廓
    buttonFloating = Color(0xFFFFFFFF),   // alias-button-floating-fill: bluish-00
)

val LocalDshColors = staticCompositionLocalOf { DarkDshColors }

/** 全局主题设置管理器 */
object ThemeManager {
    private const val PREFS_NAME = "dsh_settings"
    private const val KEY_THEME = "theme"

    var currentThemeMode by mutableStateOf("system")
        private set

    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        currentThemeMode = prefs.getString(KEY_THEME, "system") ?: "system"
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
    }

    val systemDark = isSystemInDarkTheme()
    val isDark = when (ThemeManager.currentThemeMode) {
        "dark" -> true
        "light" -> false
        else -> systemDark
    }

    val colors = if (isDark) DarkDshColors else LightDshColors

    // 系统栏图标亮暗随主题联动（背景保持透明，由 enableEdgeToEdge 设置）
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = view.context.findActivity()?.window ?: return@SideEffect
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !isDark
            controller.isAppearanceLightNavigationBars = !isDark
        }
    }

    CompositionLocalProvider(LocalDshColors provides colors) {
        content()
    }
}

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

    val bgSidebar: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalDshColors.current.bgSidebar

    val bgLayer1: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalDshColors.current.bgLayer1

    val bgInput: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalDshColors.current.bgInput

    val bgLayer3: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalDshColors.current.bgLayer3

    val bgCode: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalDshColors.current.bgCode

    val bgCodeBanner: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalDshColors.current.bgCodeBanner

    val bgNavActive: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalDshColors.current.bgNavActive

    val bgNavHover: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalDshColors.current.bgNavHover

    val bgSelector: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalDshColors.current.bgSelector

    val bgOverlay: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalDshColors.current.bgOverlay

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

    val labelCaption: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalDshColors.current.labelCaption

    val labelDimmed: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalDshColors.current.labelDimmed

    val borderL1: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalDshColors.current.borderL1

    val borderL2: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalDshColors.current.borderL2

    val borderL3: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalDshColors.current.borderL3

    val borderL4: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalDshColors.current.borderL4

    val hover: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalDshColors.current.hover

    val active: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalDshColors.current.active

    val brand400: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalDshColors.current.brand400

    val brand450: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalDshColors.current.brand450

    val brand500: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalDshColors.current.brand500

    val brand200: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalDshColors.current.brand200

    val brand800: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalDshColors.current.brand800

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
}
