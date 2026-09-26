package dev.deeplinks.core

import android.annotation.SuppressLint
import java.io.IOException
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.X509TrustManager
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 局域网自签证书按 SHA-256 指纹钉死，跳过主机名校验。
 * 公网 HTTPS（例如用户自建隧道）不传指纹，走系统 CA。
 *
 * 安全不变量：
 * - 正式请求必须有 64 位 SHA-256 指纹才会安装自定义 TrustManager。
 * - 私网 / 回环空指纹 fail-closed，不得静默回退到系统 PKI。
 * - [peekFingerprint] 的 TOFU 结果只能展示给用户确认，不能直接写入 HostStore。
 */
object PinnedSsl {
    class CertChangedException : SSLHandshakeException("主机证书已变更，请重新配对")

    fun normalizeFingerprint(raw: String?): String =
        (raw ?: "").lowercase().replace(":", "").replace(" ", "").trim()

    fun formatFingerprint(raw: String): String {
        val hex = normalizeFingerprint(raw)
        if (hex.length != 64) return hex
        return hex.chunked(4).joinToString(" ")
    }

    fun fingerprintOf(cert: X509Certificate): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(cert.encoded)
        return digest.joinToString("") { b -> "%02x".format(b) }
    }

    /**
     * 私网/回环/保留地址才钉插件自签证书；公网域名与公网单播地址走系统 PKI。
     *
     * 实现是**白名单式**：只有能明确判定为公网单播的 IP 字面量才豁免钉扎，
     * 其余（RFC1918、CGNAT、链路本地 169.254/16、基准测试 198.18/15、组播/保留段、
     * IPv6 ULA/链路本地、`.local`、解析失败的地址）一律钉扎，杜绝漏段 fail-open。
     * DNS 名默认交给系统 PKI（Tailscale MagicDNS 等公网证书场景）。
     */
    fun shouldPin(baseUrl: String): Boolean {
        val host = runCatching { URI(normalizeUrl(baseUrl)).host }.getOrNull()?.lowercase()
            ?: return true
        val name = host.trimStart('[').trimEnd(']').trim()
        if (name.isEmpty()) return true
        if (name == "localhost" || name == "::1" || name.endsWith(".local")) return true
        if (name.contains(":")) return !isPublicIpv6(name)
        if (!IPV4_LITERAL.matches(name)) return false
        return !isPublicIpv4(name)
    }

    private val IPV4_LITERAL = Regex("""\d{1,3}(\.\d{1,3}){3}""")

    /** 仅公网单播 IPv4 返回 true；私网、CGNAT、链路本地、组播、保留段与非法写法一律 false。 */
    private fun isPublicIpv4(name: String): Boolean {
        val parts = name.split(".").map { it.toIntOrNull() ?: return false }
        if (parts.size != 4 || parts.any { it !in 0..255 }) return false
        val a = parts[0]
        val b = parts[1]
        return when {
            a == 0 -> false                    // 0.0.0.0/8
            a == 10 -> false                   // 10.0.0.0/8
            a == 100 && b in 64..127 -> false  // 100.64.0.0/10 CGNAT（含 Tailscale）
            a == 127 -> false                  // 127.0.0.0/8
            a == 169 && b == 254 -> false      // 169.254.0.0/16 链路本地
            a == 172 && b in 16..31 -> false   // 172.16.0.0/12
            a == 192 && b == 168 -> false      // 192.168.0.0/16
            a == 192 && b == 0 -> false        // 192.0.0.0/24、192.0.2.0/24
            a == 192 && b == 88 -> false       // 192.88.99.0/24
            a == 198 && b in 18..19 -> false   // 198.18.0.0/15 基准测试
            a == 198 && b == 51 -> false       // 198.51.100.0/24
            a == 203 && b == 0 -> false        // 203.0.113.0/24
            a >= 224 -> false                  // 组播 224/4 与保留 240/4
            else -> true
        }
    }

    /** 仅全局单播 IPv6（2000::/3）返回 true；ULA、链路本地、回环、映射地址按内嵌 IPv4 判定。 */
    private fun isPublicIpv6(name: String): Boolean {
        if (name.startsWith("::ffff:")) {
            val embedded = name.removePrefix("::ffff:")
            return embedded.contains('.') && isPublicIpv4(embedded)
        }
        val groups = name.split(":")
        val first = groups.getOrNull(0)?.toIntOrNull(16) ?: return false
        if (first !in 0x2000..0x3fff) return false
        if (first == 0x2001 && groups.getOrNull(1)?.toIntOrNull(16) == 0x0db8) return false // 2001:db8::/32 文档地址
        return true
    }

    fun normalizeUrl(baseUrl: String): String {
        val trimmed = baseUrl.trim()
        return when {
            trimmed.startsWith("https://", ignoreCase = true) -> trimmed
            trimmed.startsWith("http://", ignoreCase = true) -> "https://" + trimmed.substring(7)
            else -> "https://$trimmed"
        }
    }

    /**
     * 指纹格式校验：必须是 64 位十六进制 SHA-256。
     * 非法指纹一律 [CertChangedException]，不得静默降级为系统 PKI。
     */
    fun requireValidPin(pin: String) {
        if (pin.length != 64 || pin.any { it !in "0123456789abcdef" }) {
            throw CertChangedException()
        }
    }

    /**
     * 正式请求：只信任叶证书 SHA-256 与 [expectedPin] 完全一致的服务器。
     * 空链或指纹不匹配一律失败。这不是“信任所有证书”。
     */
    @SuppressLint("CustomX509TrustManager", "TrustAllX509TrustManager")
    internal fun pinnedTrustManager(expectedPin: String): X509TrustManager =
        object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                if (chain.isEmpty()) throw CertChangedException()
                if (fingerprintOf(chain[0]) != expectedPin) throw CertChangedException()
            }
        }

    /**
     * TOFU 读取：在用户确认前临时接受当前叶证书，只为展示指纹。
     * 返回值不得在未经用户确认时写入 HostStore。
     */
    @SuppressLint("CustomX509TrustManager", "TrustAllX509TrustManager")
    internal fun tofuReadTrustManager(): X509TrustManager =
        object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
        }

    /** TOFU：先看清服务器证书指纹（仅用于展示确认，随后按该指纹钉死）。 */
    fun peekFingerprint(baseUrl: String): String {
        val url = "${normalizeUrl(baseUrl).trimEnd('/')}/dsh-link/health"
        val trustManager = tofuReadTrustManager()
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, arrayOf(trustManager), SecureRandom())
        val client = OkHttpClient.Builder()
            .sslSocketFactory(ctx.socketFactory, trustManager)
            .hostnameVerifier(HostnameVerifier { _, _ -> true })
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .build()
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            val certs = response.handshake?.peerCertificates
                ?: throw IOException("no server certificate")
            return fingerprintOf(certs[0] as X509Certificate)
        }
    }

    fun unwrap(error: Throwable): Throwable {
        var cur: Throwable? = error
        while (cur != null) {
            if (cur is CertChangedException) return cur
            cur = cur.cause
        }
        return error
    }
}
