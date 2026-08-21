package dev.dsh.mobile.devices

import org.json.JSONArray
import org.json.JSONObject

data class PairingQr(
    val code: String,
    val urls: List<String>,
    val name: String,
    val certFingerprint: String,
)

sealed class PairingQrResult {
    data class Ok(val qr: PairingQr) : PairingQrResult()
    data object NotDsh : PairingQrResult()
    data object Invalid : PairingQrResult()
}

fun parsePairingQr(text: String): PairingQrResult {
    val payload = runCatching { JSONObject(text) }.getOrNull() ?: return PairingQrResult.NotDsh
    if (payload.optString("type") != "dsh-link") return PairingQrResult.NotDsh
    val code = payload.optString("pairingCode", payload.optString("code")).trim()
    val urls = stringList(payload.optJSONArray("urls")) ?: return PairingQrResult.Invalid
    if (code.isEmpty() || urls.isEmpty()) return PairingQrResult.Invalid
    val name = payload.optString("name", "dsh").ifBlank { "dsh" }
    val fp = payload.optString("certFingerprint").trim()
    return PairingQrResult.Ok(PairingQr(code, urls, name, fp))
}

private fun stringList(arr: JSONArray?): List<String>? {
    if (arr == null) return null
    val out = ArrayList<String>(arr.length())
    for (i in 0 until arr.length()) {
        val value = arr.opt(i)
        if (value !is String) return null
        val text = value.trim()
        if (text.isEmpty()) return null
        out.add(text)
    }
    return out
}
