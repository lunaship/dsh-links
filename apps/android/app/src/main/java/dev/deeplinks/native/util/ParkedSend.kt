package dev.deeplinks.native.util

import org.json.JSONArray
import org.json.JSONObject

/**
 * 发送中被系统杀掉时，把草稿停在本地（对照 OpenClaw outbox：不自动重放，回填输入框让用户确认）。
 */
data class ParkedSend(
    val slotKey: String,
    val sessionId: String?,
    val text: String,
    val images: List<Pair<String, String>> = emptyList(),
    val droppedImages: Boolean = false,
) {
    val isEmpty: Boolean get() = text.isBlank() && images.isEmpty()
}

const val PARKED_SEND_MAX_CHARS = 180_000

fun encodeParkedSend(send: ParkedSend): String {
    val images = JSONArray()
    send.images.forEach { (type, data) ->
        images.put(JSONObject().put("t", type).put("d", data))
    }
    return JSONObject()
        .put("slotKey", send.slotKey)
        .put("sessionId", send.sessionId ?: "")
        .put("text", send.text)
        .put("droppedImages", send.droppedImages)
        .put("images", images)
        .toString()
}

fun decodeParkedSend(raw: String?): ParkedSend? {
    if (raw.isNullOrBlank()) return null
    return try {
        val obj = JSONObject(raw)
        val imagesArr = obj.optJSONArray("images") ?: JSONArray()
        val images = buildList {
            for (i in 0 until imagesArr.length()) {
                val item = imagesArr.optJSONObject(i) ?: continue
                val type = item.optString("t").ifBlank { "image/jpeg" }
                val data = item.optString("d")
                if (data.isNotBlank()) add(type to data)
            }
        }
        ParkedSend(
            slotKey = obj.optString("slotKey"),
            sessionId = obj.optString("sessionId").takeIf { it.isNotBlank() },
            text = obj.optString("text"),
            images = images,
            droppedImages = obj.optBoolean("droppedImages", false),
        ).takeUnless { it.slotKey.isBlank() || it.isEmpty }
    } catch (_: Exception) {
        null
    }
}

/** 超过上限时丢掉附件，保住正文。 */
fun parkSendForPersistence(send: ParkedSend, maxChars: Int = PARKED_SEND_MAX_CHARS): ParkedSend? {
    if (send.isEmpty) return null
    val full = encodeParkedSend(send)
    if (full.length <= maxChars) return send
    val textOnly = send.copy(images = emptyList(), droppedImages = send.images.isNotEmpty() || send.droppedImages)
    val compact = encodeParkedSend(textOnly)
    if (compact.length <= maxChars) return textOnly
    return null
}

enum class ParkedRestoreKind {
    None,
    IntoCurrent,
    SelectSession,
}

/** 草稿回填本身就是结果；只有丢掉附件时才需要写进输入槽。 */
fun parkedRestoreComposerError(droppedImages: Boolean): Boolean = droppedImages

/**
 * 对照 OpenClaw：不自动重放。进程被杀后只在同一主机、同一会话（或新建会话尚未绑 id）回填。
 */
fun parkedSendRestoreKind(
    parked: ParkedSend,
    slotKey: String,
    currentSessionId: String?,
): ParkedRestoreKind {
    if (parked.slotKey != slotKey) return ParkedRestoreKind.None
    if (parked.sessionId == currentSessionId) return ParkedRestoreKind.IntoCurrent
    if (parked.sessionId != null && currentSessionId == null) return ParkedRestoreKind.SelectSession
    return ParkedRestoreKind.None
}
