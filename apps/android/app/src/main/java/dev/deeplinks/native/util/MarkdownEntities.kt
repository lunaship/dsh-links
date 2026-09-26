package dev.deeplinks.native.util

/** 解码服务端文本中的 HTML 实体（Web 端渲染会转义，原生端需手动解码）。 */
fun decodeHtmlEntities(text: String): String {
    if (!text.contains('&')) return text
    val sb = StringBuilder(text.length)
    var i = 0
    while (i < text.length) {
        val amp = text.indexOf('&', i)
        if (amp == -1 || amp > text.length - 2) {
            sb.append(text.substring(i))
            break
        }
        sb.append(text, i, amp)
        val semi = text.indexOf(';', amp)
        if (semi == -1 || semi - amp > 12) {
            sb.append('&')
            i = amp + 1
            continue
        }
        val entity = text.substring(amp + 1, semi)
        val decoded = when {
            entity == "amp" -> "&"
            entity == "lt" -> "<"
            entity == "gt" -> ">"
            entity == "quot" -> "\""
            entity == "apos" -> "'"
            entity.startsWith("#x") || entity.startsWith("#X") ->
                decodeNumericEntity(entity.substring(2).toIntOrNull(16))
            entity.startsWith("#") ->
                decodeNumericEntity(entity.substring(1).toIntOrNull(10))
            else -> null
        }
        if (decoded != null) {
            sb.append(decoded)
            i = semi + 1
        } else {
            sb.append('&')
            i = amp + 1
        }
    }
    return sb.toString()
}

private fun decodeNumericEntity(codePoint: Int?): String? {
    if (codePoint == null || !Character.isValidCodePoint(codePoint)) return null
    if (codePoint in 0xD800..0xDFFF) return null
    return String(Character.toChars(codePoint))
}
