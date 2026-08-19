package dev.dsh.mobile.native

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString

/**
 * 代码块语法高亮（轻量正则 tokenizer，纯原生无 WebView，零外部依赖）。
 * 配色采用 GitHub light/dark 双套 token 色，由调用方按当前主题传入 [isDark]。
 *
 * 设计取舍：覆盖常用语言的「关键字 / 字符串 / 注释 / 数字 / 函数调用」着色，
 * 满足移动够用线；不引入重语法引擎，维护成本与崩溃面都最低。
 */

/** Prism token 类型 → 配色（GitHub 调色板）。未知类型回退普通文本色。纯函数、可单测。 */
fun tokenColor(type: String, isDark: Boolean): Color {
    val map = if (isDark) DARK_SYNTAX else LIGHT_SYNTAX
    return map[type] ?: if (isDark) DEFAULT_DARK_TEXT else DEFAULT_LIGHT_TEXT
}

private val DEFAULT_DARK_TEXT = Color(0xFFC9D1D9)
private val DEFAULT_LIGHT_TEXT = Color(0xFF24292E)

private val LIGHT_SYNTAX = mapOf(
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

private val DARK_SYNTAX = mapOf(
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

private fun keywordColor(isDark: Boolean) = tokenColor("keyword", isDark)
private fun stringColor(isDark: Boolean) = tokenColor("string", isDark)
private fun commentColor(isDark: Boolean) = tokenColor("comment", isDark)
private fun functionColor(isDark: Boolean) = tokenColor("function", isDark)
private fun numberColor(isDark: Boolean) = tokenColor("number", isDark)

/** 语言别名归一化。 */
private fun normalizeLang(language: String?): String = when ((language ?: "").lowercase()) {
    "kotlin", "kt" -> "kt"
    "java" -> "java"
    "javascript", "js", "ts", "typescript", "jsx", "tsx" -> "js"
    "python", "py" -> "py"
    "go", "golang" -> "go"
    "rust", "rs" -> "rust"
    "c", "cpp", "c++", "cxx" -> "c"
    "json" -> "json"
    "bash", "sh", "shell", "zsh" -> "bash"
    "sql" -> "sql"
    "xml", "html", "svg" -> "xml"
    "swift" -> "swift"
    "ruby", "rb" -> "ruby"
    else -> "plain"
}

/** 各语言关键字集合（够用线：覆盖常用语言）。 */
private val KEYWORDS: Map<String, Set<String>> = mapOf(
    "kt" to setOf(
        "val", "var", "fun", "class", "object", "interface", "data", "sealed", "enum",
        "when", "if", "else", "for", "while", "return", "suspend", "import", "package",
        "private", "public", "internal", "override", "init", "companion", "typealias",
        "inline", "reified", "try", "catch", "finally", "throw", "get", "set",
        "constructor", "this", "super", "null", "true", "false", "abstract", "open", "lateinit",
    ),
    "java" to setOf(
        "public", "private", "protected", "class", "interface", "enum", "void", "int", "long",
        "double", "float", "boolean", "char", "byte", "short", "static", "final", "abstract",
        "if", "else", "for", "while", "do", "switch", "case", "break", "continue", "return",
        "new", "import", "package", "try", "catch", "finally", "throw", "extends", "implements",
        "this", "super", "null", "true", "false", "synchronized", "volatile", "transient",
    ),
    "js" to setOf(
        "const", "let", "var", "function", "return", "if", "else", "for", "while", "do",
        "class", "extends", "new", "import", "export", "from", "async", "await", "try",
        "catch", "finally", "throw", "typeof", "instanceof", "switch", "case", "break",
        "continue", "default", "yield", "null", "true", "false", "undefined", "of", "in",
    ),
    "py" to setOf(
        "def", "class", "return", "if", "elif", "else", "for", "while", "import", "from",
        "as", "try", "except", "finally", "with", "lambda", "yield", "break", "continue",
        "pass", "raise", "assert", "global", "nonlocal", "async", "await", "None", "True",
        "False", "and", "or", "not", "in", "is", "del", "print",
    ),
    "go" to setOf(
        "func", "var", "const", "type", "struct", "interface", "map", "chan", "go", "defer",
        "return", "if", "else", "for", "range", "import", "package", "switch", "case", "break",
        "continue", "select", "default", "nil", "true", "false", "fallthrough",
    ),
    "rust" to setOf(
        "fn", "let", "mut", "struct", "enum", "impl", "trait", "pub", "use", "mod", "match",
        "if", "else", "for", "while", "loop", "return", "break", "continue", "async", "await",
        "move", "ref", "dyn", "where", "as", "in", "self", "Self", "crate", "super", "unsafe",
        "const", "static", "true", "false",
    ),
    "c" to setOf(
        "int", "float", "double", "char", "void", "struct", "class", "public", "private",
        "protected", "if", "else", "for", "while", "return", "typedef", "enum", "union",
        "const", "static", "namespace", "template", "virtual", "try", "catch", "throw", "new",
        "delete", "auto", "sizeof", "unsigned", "signed", "long", "short", "bool", "true",
        "false", "nullptr",
    ),
    "json" to setOf("true", "false", "null"),
    "bash" to setOf(
        "if", "then", "else", "fi", "for", "in", "do", "done", "while", "case", "esac",
        "function", "echo", "export", "local", "return", "exit", "cd", "source", "select",
    ),
    "sql" to setOf(
        "select", "from", "where", "insert", "update", "delete", "create", "table", "drop",
        "alter", "add", "join", "on", "group", "by", "order", "having", "limit", "values",
        "set", "into", "distinct", "as", "and", "or", "not", "null", "primary", "foreign",
        "key", "index", "view", "begin", "commit", "rollback", "case", "when", "then", "end",
    ),
)

private fun isLineComment(code: String, i: Int, lang: String): Boolean = when (lang) {
    "py", "ruby", "bash", "yaml", "toml" -> code.startsWith("#", i)
    "sql" -> code.startsWith("--", i)
    else -> code.startsWith("//", i)
}

/** 将代码按语言高亮为 [AnnotatedString]。任何异常都降级为纯文本，保证不崩。 */
fun highlightCode(code: String, language: String?, isDark: Boolean): AnnotatedString {
    return runCatching { tokenize(code, normalizeLang(language), isDark) }
        .getOrDefault(buildAnnotatedString { append(code) })
}

private fun tokenize(code: String, lang: String, isDark: Boolean): AnnotatedString = buildAnnotatedString {
    val keywords = KEYWORDS[lang].orEmpty()
    var i = 0
    val n = code.length
    while (i < n) {
        val c = code[i]
        // 行注释
        if (isLineComment(code, i, lang)) {
            val end = code.indexOf('\n', i).let { if (it < 0) n else it }
            pushStyle(SpanStyle(color = commentColor(isDark)))
            append(code.substring(i, end))
            pop()
            i = end
            continue
        }
        // 块注释 /* */
        if (code.startsWith("/*", i)) {
            val end = code.indexOf("*/", i).let { if (it < 0) n else it + 2 }
            pushStyle(SpanStyle(color = commentColor(isDark)))
            append(code.substring(i, end))
            pop()
            i = end
            continue
        }
        // 字符串（含 ` 模板串），处理转义
        if (c == '"' || c == '\'' || c == '`') {
            val quote = c
            var j = i + 1
            while (j < n) {
                if (code[j] == '\\') { j += 2; continue }
                if (code[j] == quote) { j++; break }
                j++
            }
            pushStyle(SpanStyle(color = stringColor(isDark)))
            append(code.substring(i, j))
            pop()
            i = j
            continue
        }
        // 数字
        if (c.isDigit() || (c == '.' && i + 1 < n && code[i + 1].isDigit())) {
            var j = i
            while (j < n && (code[j].isDigit() || code[j] == '.' || code[j] == 'x' || code[j] == 'X' ||
                code[j] in 'a'..'f' || code[j] in 'A'..'F' || code[j] == '_')
            ) j++
            pushStyle(SpanStyle(color = numberColor(isDark)))
            append(code.substring(i, j))
            pop()
            i = j
            continue
        }
        // 标识符：关键字 / 函数调用 / 普通
        if (c.isLetter() || c == '_') {
            var j = i
            while (j < n && (code[j].isLetterOrDigit() || code[j] == '_')) j++
            val word = code.substring(i, j)
            var k = j
            while (k < n && code[k].isWhitespace()) k++
            when {
                keywords.contains(word) -> {
                    pushStyle(SpanStyle(color = keywordColor(isDark))); append(word); pop()
                }
                k < n && code[k] == '(' -> {
                    pushStyle(SpanStyle(color = functionColor(isDark))); append(word); pop()
                }
                else -> append(word)
            }
            i = j
            continue
        }
        // 其余原样
        append(c)
        i++
    }
}
