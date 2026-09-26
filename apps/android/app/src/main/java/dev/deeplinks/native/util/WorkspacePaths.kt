package dev.deeplinks.native.util

/**
 * Host 路径展示与本轮产出文件分类。
 * 对标 DSH Web：HOME 缩写成 `~`；产出文件按后缀决定预览方式。
 */
private val POSIX_HOME = Regex("^/(?:Users|home)/[^/]+")

fun abbreviateHomePath(path: String?): String {
    if (path.isNullOrBlank()) return ""
    return POSIX_HOME.replace(path.trim(), "~")
}

fun producedFileName(path: String): String {
    val trimmed = path.trim()
    return trimmed.substringAfterLast('/').ifBlank { trimmed }
}

enum class ProducedFileKind { IMAGE, TEXT, OTHER }

private val IMAGE_EXT = setOf("png", "jpg", "jpeg", "gif", "webp")
private val TEXT_EXT = setOf(
    "md", "txt", "json", "kt", "kts", "js", "ts", "tsx", "jsx",
    "py", "yml", "yaml", "xml", "csv", "html", "css", "sh", "toml", "svg",
)

fun producedFileKind(path: String): ProducedFileKind {
    val ext = path.trim().substringAfterLast('.', missingDelimiterValue = "").lowercase()
    return when (ext) {
        in IMAGE_EXT -> ProducedFileKind.IMAGE
        in TEXT_EXT -> ProducedFileKind.TEXT
        else -> ProducedFileKind.OTHER
    }
}

fun decodeProducedText(bytes: ByteArray, maxChars: Int = 32_768): String {
    val text = String(bytes, Charsets.UTF_8)
    if (text.length <= maxChars) return text
    return text.take(maxChars) + "…"
}

fun isProducedTextMime(mime: String): Boolean {
    val kind = mime.substringBefore(';').trim().lowercase()
    return kind.startsWith("text/") ||
        kind == "application/json" ||
        kind == "application/xml" ||
        kind.endsWith("+json") ||
        kind.endsWith("+xml")
}
