package dev.deeplinks.devices

import dev.deeplinks.core.Host
import dev.deeplinks.core.L
import org.json.JSONArray
import org.json.JSONObject

data class PairingQr(
    val code: String,
    val urls: List<String>,
    val name: String,
    val certFingerprint: String,
    val relay: RelayRoute? = null,
)

data class RelayRoute(
    val client: String,
    val routeId: String,
    val routeSecret: String,
    val tlsFingerprint: String = "",
) {
    val hasRoute: Boolean get() = routeId.isNotEmpty() && routeSecret.isNotEmpty()
}

sealed class PairingQrResult {
    data class Ok(val qr: PairingQr) : PairingQrResult()
    data object NotDsh : PairingQrResult()
    data object Invalid : PairingQrResult()
}

fun parsePairingQr(text: String): PairingQrResult {
    val payload = runCatching { JSONObject(text) }.getOrNull() ?: return PairingQrResult.NotDsh
    if (payload.optString("type") != "dsh-link") return PairingQrResult.NotDsh
    val code = payload.optString("pairingCode", payload.optString("code")).trim()
    val urls = stringList(payload.optJSONArray("urls")) ?: emptyList()
    val relayObject = payload.optJSONObject("relay")
    // Anonymous Relay QR does not identify the target plugin route. Reject it
    // instead of silently enrolling the phone into an unrelated client route.
    if (relayObject?.optString("mode")?.trim() == "anonymous") return PairingQrResult.Invalid
    val relay = parseRelay(relayObject)
    val fp = payload.optString("certFingerprint").trim()
    // Relay still carries the plugin's inner TLS session. Without its pin, pairing
    // would parse successfully and only fail later when the first Relay request opens.
    if (code.isEmpty() || (urls.isEmpty() && relay == null) || (relay != null && fp.isEmpty())) {
        return PairingQrResult.Invalid
    }
    val name = payload.optString("name", "dsh").ifBlank { "dsh" }
    return PairingQrResult.Ok(PairingQr(code, urls, name, fp, relay))
}

private fun parseRelay(obj: JSONObject?): RelayRoute? {
    if (obj == null) return null
    val client = obj.optString("client").trim()
    val tlsFingerprint = obj.optString("tlsFingerprint").trim()
    if (client.isEmpty()) return null
    val routeId = obj.optString("routeId").trim()
    val routeSecret = obj.optString("routeSecret").trim()
    if (routeId.isEmpty() || routeSecret.isEmpty()) return null
    return RelayRoute(client, routeId, routeSecret, tlsFingerprint)
}

fun hostFromPair(name: String, result: dev.deeplinks.core.PairClient.Result, relay: RelayRoute?, preferRelay: Boolean): Host {
    val display = if (relay != null) "$name · ${L.viaCloud}" else name
    return Host(
        name = display,
        baseUrl = result.baseUrl,
        token = result.token,
        deviceId = result.deviceId,
        certFingerprint = result.certFingerprint,
        relayClient = relay?.client.orEmpty(),
        relayRouteId = relay?.routeId.orEmpty(),
        relayRouteSecret = relay?.routeSecret.orEmpty(),
        relayTlsFingerprint = relay?.tlsFingerprint.orEmpty(),
        preferRelay = preferRelay,
    )
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
