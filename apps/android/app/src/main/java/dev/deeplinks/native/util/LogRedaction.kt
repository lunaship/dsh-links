package dev.deeplinks.native.util

/**
 * 日志脱敏工具：会话 id / 请求路径 / 内容 URI 不落完整值到 logcat，
 * 只保留稳定可排查的短前缀或短哈希。纯字符串逻辑，便于 JVM 单测。
 */
internal const val LOG_PREFIX_LEN = 8

/** 保留短前缀；空值原样返回。 */
internal fun redactLogValue(value: String, keep: Int = LOG_PREFIX_LEN): String {
    val trimmed = value.trim()
    if (trimmed.isEmpty()) return ""
    return if (trimmed.length <= keep) trimmed else trimmed.take(keep) + "…"
}

/**
 * 请求路径脱敏：query 的具体值截成短前缀，/sessions/{id} 段同样截断。
 * 例：/dsh-link/mobile/sessions/sess-abcdef123/prompt -> /dsh-link/mobile/sessions/sess-abc…/prompt
 */
internal fun redactRequestPath(path: String): String {
    val queryIndex = path.indexOf('?')
    val route = if (queryIndex >= 0) path.substring(0, queryIndex) else path
    val safeRoute = SESSION_SEGMENT.replace(route) { match ->
        "/sessions/" + redactLogValue(match.groupValues[1])
    }
    if (queryIndex < 0) return safeRoute
    val query = path.substring(queryIndex + 1)
    if (query.isEmpty()) return "$safeRoute?"
    val safeQuery = query.split('&').joinToString("&") { part ->
        val eq = part.indexOf('=')
        if (eq < 0) part else part.substring(0, eq + 1) + redactLogValue(part.substring(eq + 1))
    }
    return "$safeRoute?$safeQuery"
}

private val SESSION_SEGMENT = Regex("/sessions/([^/?#]+)")

/** URI 脱敏：只留 scheme://authority 与短哈希，丢弃 document id / 路径。 */
internal fun redactUriForLog(uri: String): String {
    val trimmed = uri.trim()
    if (trimmed.isEmpty()) return ""
    val schemeEnd = trimmed.indexOf("://")
    val scheme = if (schemeEnd > 0) trimmed.substring(0, schemeEnd) else "uri"
    val rest = if (schemeEnd > 0) trimmed.substring(schemeEnd + 3) else trimmed
    val authority = rest.substringBefore('/').substringBefore('?').substringBefore('#')
    val hash = Integer.toHexString(trimmed.hashCode())
    val host = if (authority.isEmpty()) "" else "://$authority"
    return "$scheme$host~$hash"
}
