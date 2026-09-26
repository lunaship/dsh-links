package dev.deeplinks.core

import androidx.compose.ui.graphics.Color

/**
 * 代码块语法高亮调色板（GitHub light/dark）。
 *
 * 从 CodeHighlight 抽出，作为语法色的唯一定义处：它是设计 token 的定义文件
 * （与 DshTheme / DshTypography 同类），允许出现字面色值；
 * 业务文件不得再写 Color(0x...)。纯对象、无 Compose 依赖，
 * tokenizer 仍是可单测的纯函数。
 */
object DshSyntaxPalette {
    val darkDefault: Color = Color(0xFFC9D1D9)
    val lightDefault: Color = Color(0xFF24292E)

    private val LIGHT = mapOf(
        "keyword" to Color(0xFFD73A49),
        "atrule" to Color(0xFFD73A49),
        "selector" to Color(0xFFD73A49),
        "important" to Color(0xFFD73A49),
        "string" to Color(0xFF0A3069),
        "char" to Color(0xFF0A3069),
        "attr-value" to Color(0xFF0A3069),
        "regex" to Color(0xFF0A3069),
        "comment" to Color(0xFF6E7781),
        "prolog" to Color(0xFF6E7781),
        "doctype" to Color(0xFF6E7781),
        "cdata" to Color(0xFF6E7781),
        "function" to Color(0xFF6F42C1),
        "method" to Color(0xFF6F42C1),
        "class-name" to Color(0xFF6F42C1),
        "builtin" to Color(0xFF6F42C1),
        "number" to Color(0xFF005CC5),
        "boolean" to Color(0xFF005CC5),
        "constant" to Color(0xFF005CC5),
        "symbol" to Color(0xFF005CC5),
        "tag" to Color(0xFF22863A),
        "attr-name" to Color(0xFF005CC5),
        "variable" to Color(0xFFE36209),
        "operator" to Color(0xFFD73A49),
        "property" to Color(0xFF005CC5),
        "parameter" to Color(0xFF24292E),
        "punctuation" to Color(0xFF24292E),
    )

    private val DARK = mapOf(
        "keyword" to Color(0xFFFF7B72),
        "atrule" to Color(0xFFFF7B72),
        "selector" to Color(0xFFFF7B72),
        "important" to Color(0xFFFF7B72),
        "string" to Color(0xFFA5D6FF),
        "char" to Color(0xFFA5D6FF),
        "attr-value" to Color(0xFFA5D6FF),
        "regex" to Color(0xFFA5D6FF),
        "comment" to Color(0xFF8B949E),
        "prolog" to Color(0xFF8B949E),
        "doctype" to Color(0xFF8B949E),
        "cdata" to Color(0xFF8B949E),
        "function" to Color(0xFFD2A8FF),
        "method" to Color(0xFFD2A8FF),
        "class-name" to Color(0xFFD2A8FF),
        "builtin" to Color(0xFFD2A8FF),
        "number" to Color(0xFF79C0FF),
        "boolean" to Color(0xFF79C0FF),
        "constant" to Color(0xFF79C0FF),
        "symbol" to Color(0xFF79C0FF),
        "tag" to Color(0xFF7EE787),
        "attr-name" to Color(0xFF79C0FF),
        "variable" to Color(0xFFFFA657),
        "operator" to Color(0xFFFF7B72),
        "property" to Color(0xFF79C0FF),
        "parameter" to Color(0xFFC9D1D9),
        "punctuation" to Color(0xFFC9D1D9),
    )

    fun color(type: String, isDark: Boolean): Color {
        val map = if (isDark) DARK else LIGHT
        return map[type] ?: if (isDark) darkDefault else lightDefault
    }
}
