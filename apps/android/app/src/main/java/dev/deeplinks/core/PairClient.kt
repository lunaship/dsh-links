package dev.deeplinks.core

import org.json.JSONObject
import java.io.IOException
import java.util.UUID

sealed class HostHealth {
    data class Ok(val latencyMs: Long) : HostHealth()
    data object Unreachable : HostHealth()
    data class AuthFailed(val error: Throwable) : HostHealth()
}

internal fun classifyHostHealthError(error: Throwable): HostHealth {
    val unwrapped = if (error is Exception) PinnedSsl.unwrap(error) else error
    if (unwrapped is PinnedSsl.CertChangedException) return HostHealth.AuthFailed(unwrapped)
    if (isRelayRouteRevoked(unwrapped)) {
        val asEx = if (unwrapped is Exception) unwrapped else IOException(unwrapped.message, unwrapped)
        return HostHealth.AuthFailed(asEx)
    }
    return HostHealth.Unreachable
}

object PairClient {
    data class Result(
        val baseUrl: String,
        val name: String,
        val token: String,
        val deviceId: String = "",
        val certFingerprint: String = "",
        val pending: Boolean = false,
    )

    fun normalize(baseUrl: String): String = PinnedSsl.normalizeUrl(baseUrl)

    /** 用一次性配对码向主机换取连接 token。主机开启本机确认时 `pending=true`。 */
    fun pair(
        baseUrl: String,
        code: String,
        deviceName: String,
        certFingerprint: String? = null,
        relay: dev.deeplinks.devices.RelayRoute? = null,
        preferRelay: Boolean = false,
        requestId: String = UUID.randomUUID().toString(),
    ): Result {
        val normalized = normalize(baseUrl)
        val pin = certFingerprint?.takeIf { it.isNotBlank() }
        if (preferRelay && relay != null) {
            return pairOn(normalized, pin, relay, code, deviceName, "relay", requestId)
        }
        return try {
            pairOn(normalized, pin, null, code, deviceName, "lan", requestId)
        } catch (e: Exception) {
            if (relay == null) throw e
            pairOn(normalized, pin, relay, code, deviceName, "relay", requestId)
        }
    }

    /** 配对 host 视图：LAN 直连或 Relay 隧道（单路由，不走 AUTO failover）。 */
    private fun pairHost(
        normalized: String,
        pin: String?,
        relay: dev.deeplinks.devices.RelayRoute?,
    ): Host = Host(
        name = "pair",
        baseUrl = normalized,
        token = "",
        certFingerprint = pin.orEmpty(),
        relayClient = relay?.client.orEmpty(),
        relayRouteId = relay?.routeId.orEmpty(),
        relayRouteSecret = relay?.routeSecret.orEmpty(),
        relayTlsFingerprint = relay?.tlsFingerprint.orEmpty(),
        preferRelay = relay != null,
    )

    private fun pairOn(
        normalized: String,
        pin: String?,
        relay: dev.deeplinks.devices.RelayRoute?,
        code: String,
        deviceName: String,
        via: String,
        requestId: String,
    ): Result {
        val host = pairHost(normalized, pin, relay)
        val route = if (relay != null) FailoverRoute.RELAY else FailoverRoute.LAN
        try {
            HostHttp.execute(
                host,
                HostHttp.DshRequest(
                    method = "POST",
                    path = "/dsh-link/pair",
                    body = pairRequestBody(code, deviceName, via, requestId).toString().toByteArray(),
                    headers = listOf("Content-Type" to "application/json"),
                    connectTimeoutMs = 8_000,
                    readTimeoutMs = 8_000,
                ),
                forceRoute = route,
            ).use { response ->
                val respCode = response.code
                val body = response.body?.byteStream()?.use { BoundedIo.readText(it) } ?: ""
                if (respCode != 200) throw Exception(friendlyPairError(respCode, body))
                return parsePairSuccess(normalized, body, deviceName, pin)
            }
        } catch (t: Throwable) {
            if (t is Exception) throw PinnedSsl.unwrap(t)
            throw IOException(t.message ?: t.javaClass.simpleName, t)
        }
    }

    internal fun pairRequestBody(code: String, deviceName: String, via: String, requestId: String): JSONObject =
        JSONObject()
            .put("code", code)
            .put("deviceName", deviceName)
            .put("via", via)
            .put("requestId", requestId)

    /** 探测主机：在线、暂时不可达，或云端路由/证书已失效。 */
    fun probe(host: Host): HostHealth {
        val start = System.currentTimeMillis()
        return try {
            HostHttp.execute(
                host,
                HostHttp.DshRequest("GET", "/dsh-link/health", connectTimeoutMs = 2_500, readTimeoutMs = 2_500),
            ).use { response ->
                if (response.code == 200) {
                    runCatching {
                        response.body?.byteStream()?.use { BoundedIo.readText(it, BoundedIo.MAX_HEALTH_BODY_BYTES) }
                    }
                    HostHealth.Ok(System.currentTimeMillis() - start)
                } else HostHealth.Unreachable
            }
        } catch (t: Throwable) {
            classifyHostHealthError(t)
        }
    }

    /** 探测主机在线状态，返回延迟毫秒数；不可达返回 null。凭据失效时抛出。 */
    fun health(host: Host): Long? = when (val result = probe(host)) {
        is HostHealth.Ok -> result.latencyMs
        HostHealth.Unreachable -> null
        is HostHealth.AuthFailed -> throw result.error
    }

    fun health(baseUrl: String, certFingerprint: String? = null): Long? {
        return health(Host("probe", normalize(baseUrl), "", certFingerprint = certFingerprint.orEmpty()))
    }

    /** 解析成功配对 JSON。`pending=true` 表示主机开启了本机确认，token 已保存但尚未放行。 */
    internal fun parsePairSuccess(normalized: String, body: String, deviceName: String, pin: String?): Result {
        val o = JSONObject(body)
        return Result(
            normalized,
            o.optString("name", deviceName),
            o.getString("token"),
            o.optString("deviceId"),
            pin.orEmpty(),
            o.optBoolean("pending"),
        )
    }

    /** 将配对 HTTP 错误转为用户可读文案。 */
    fun friendlyPairError(code: Int, body: String): String {
        val hint = runCatching {
            JSONObject(body).optString("error").takeIf { it.isNotBlank() }
        }.getOrNull()
        return when (code) {
            401 -> hint ?: LocaleManager.strings.pairCodeInvalid
            409 -> hint ?: LocaleManager.strings.pairNameTaken
            415 -> LocaleManager.strings.pairBadRequest
            429 -> hint ?: LocaleManager.strings.pairTooManyAttempts
            in 500..599 -> hint ?: LocaleManager.strings.pairHostUnavailable.format(code)
            else -> hint ?: LocaleManager.strings.pairFailedHttp.format(code)
        }
    }
}
