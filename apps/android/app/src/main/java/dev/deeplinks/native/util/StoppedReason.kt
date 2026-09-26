package dev.deeplinks.native.util

import org.json.JSONObject

/**
 * 读取可空字符串。JSON `null` / 缺键 / 空白视为没有值。
 * 不要用 [JSONObject.optString]：它对 JSON null 会返回字面量 `"null"`。
 */
fun String?.presentOrNull(): String? {
    val kind = this?.trim().orEmpty()
    if (kind.isEmpty()) return null
    return when (kind.lowercase()) {
        "null", "undefined" -> null
        else -> kind
    }
}

fun JSONObject.optNullableString(key: String): String? {
    if (!has(key) || isNull(key)) return null
    return optString(key).presentOrNull()
}

/**
 * 会话停止原因。正常结束（completed）或无效值不展示徽章。
 */
fun parseStoppedReason(raw: String?): String? {
    val kind = raw?.trim().orEmpty()
    if (kind.isEmpty()) return null
    return when (kind.lowercase()) {
        "null", "undefined", "completed", "none" -> null
        else -> kind
    }
}
