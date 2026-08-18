package dev.dsh.mobile.core
import dev.dsh.mobile.core.PairClient

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object PairClient {
    data class Result(val baseUrl: String, val name: String, val token: String)

    /** 补全协议头：漏填 http:// 时自动补上。 */
    private fun normalize(baseUrl: String): String {
        val trimmed = baseUrl.trim()
        return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed
        else "http://$trimmed"
    }

    /** 用一次性配对码向主机换取连接 token（自动批准）。 */
    fun pair(baseUrl: String, code: String, deviceName: String): Result {
        val normalized = normalize(baseUrl)
        val conn = URL("${normalized.trimEnd('/')}/dsh-link/pair").openConnection() as HttpURLConnection
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
            if (respCode != 200) throw Exception("HTTP $respCode: ${body.take(120)}")
            val o = JSONObject(body)
            return Result(normalized, o.optString("name", deviceName), o.getString("token"))
        } finally {
            conn.disconnect()
        }
    }

    /** 探测主机在线状态，返回延迟毫秒数；不可达返回 null。 */
    fun health(baseUrl: String): Long? {
        val start = System.currentTimeMillis()
        val conn = URL("${normalize(baseUrl).trimEnd('/')}/dsh-link/health").openConnection() as HttpURLConnection
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
}
