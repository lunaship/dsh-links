package dev.dsh.mobile.core

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object PairClient {
    data class Result(
        val baseUrl: String,
        val name: String,
        val token: String,
        val deviceId: String = "",
        val certFingerprint: String = "",
    )

    fun normalize(baseUrl: String): String = PinnedSsl.normalizeUrl(baseUrl)

    /** 用一次性配对码向主机换取连接 token（自动批准）。 */
    fun pair(baseUrl: String, code: String, deviceName: String, certFingerprint: String? = null): Result {
        val normalized = normalize(baseUrl)
        val pin = certFingerprint?.takeIf { it.isNotBlank() }
        val conn = URL("${normalized.trimEnd('/')}/dsh-link/pair").openConnection() as HttpURLConnection
        PinnedSsl.apply(conn, pin)
        try {
            conn.connectTimeout = 6000
            conn.readTimeout = 8000
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use {
                it.write(JSONObject().put("code", code).put("deviceName", deviceName).toString().toByteArray())
            }
            val respCode = conn.responseCode
            val body = (if (respCode in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            if (respCode != 200) throw Exception(friendlyPairError(respCode, body))
            val o = JSONObject(body)
            return Result(normalized, o.optString("name", deviceName), o.getString("token"), o.optString("deviceId"), pin.orEmpty())
        } catch (e: Exception) {
            throw PinnedSsl.unwrap(e)
        } finally {
            conn.disconnect()
        }
    }

    /** 探测主机在线状态，返回延迟毫秒数；不可达返回 null。 */
    fun health(baseUrl: String, certFingerprint: String? = null): Long? {
        val start = System.currentTimeMillis()
        val conn = URL("${normalize(baseUrl).trimEnd('/')}/dsh-link/health").openConnection() as HttpURLConnection
        PinnedSsl.apply(conn, certFingerprint)
        return try {
            conn.connectTimeout = 2500
            conn.readTimeout = 2500
            conn.requestMethod = "GET"
            if (conn.responseCode == 200) System.currentTimeMillis() - start else null
        } catch (e: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }

    /** 将配对 HTTP 错误转为用户可读文案。 */
    fun friendlyPairError(code: Int, body: String): String {
        val hint = runCatching {
            JSONObject(body).optString("error").takeIf { it.isNotBlank() }
        }.getOrNull()
        return when (code) {
            401 -> hint ?: "配对码无效或已过期，请在电脑端刷新后重试"
            409 -> hint ?: "设备名已存在，请换一个名称"
            415 -> "请求格式不正确，请更新 App 后重试"
            429 -> hint ?: "尝试过多，请稍后再试"
            in 500..599 -> hint ?: "电脑端暂时无法配对（$code），请确认 dsh 已启动"
            else -> hint ?: "配对失败（HTTP $code）"
        }
    }
}
