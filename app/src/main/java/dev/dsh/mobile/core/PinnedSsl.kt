package dev.dsh.mobile.core

import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.X509TrustManager

/**
 * 局域网自签证书按 SHA-256 指纹钉死，跳过主机名校验。
 * 公网 HTTPS（Cloudflare Tunnel）不传指纹，走系统 CA。
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

    /** 私网/回环才钉插件自签证书；公网域名走系统 PKI。 */
    fun shouldPin(baseUrl: String): Boolean {
        val host = runCatching { URI(normalizeUrl(baseUrl)).host }.getOrNull()?.lowercase() ?: return true
        val name = host.trimStart('[').trimEnd(']')
        if (name == "localhost" || name == "::1" || name.endsWith(".local")) return true
        if (name.startsWith("127.")) return true
        if (name.startsWith("10.")) return true
        if (name.startsWith("192.168.")) return true
        if (name.startsWith("172.")) {
            val second = name.split(".").getOrNull(1)?.toIntOrNull() ?: return false
            return second in 16..31
        }
        return false
    }

    fun normalizeUrl(baseUrl: String): String {
        val trimmed = baseUrl.trim()
        return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed
        else "https://$trimmed"
    }

    fun apply(connection: HttpURLConnection, fingerprint: String?) {
        if (connection !is HttpsURLConnection) return
        val pin = normalizeFingerprint(fingerprint)
        if (pin.isEmpty()) {
            // 私网/回环主机必须钉死证书；空指纹不得静默回退到系统 PKI（fail-closed）
            if (shouldPin(connection.url.toString())) {
                throw CertChangedException()
            }
            return
        }
        if (pin.length != 64 || pin.any { it !in "0123456789abcdef" }) {
            throw CertChangedException()
        }
        val tm = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                if (chain.isEmpty()) throw CertChangedException()
                if (fingerprintOf(chain[0]) != pin) throw CertChangedException()
            }
        }
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, arrayOf(tm), SecureRandom())
        connection.sslSocketFactory = ctx.socketFactory
        connection.hostnameVerifier = HostnameVerifier { _, _ -> true }
    }

    /** TOFU：先看清服务器证书指纹（仅用于展示确认，随后按该指纹钉死）。 */
    fun peekFingerprint(baseUrl: String): String {
        val url = java.net.URL("${normalizeUrl(baseUrl).trimEnd('/')}/dsh-link/health")
        val conn = url.openConnection() as HttpsURLConnection
        val tm = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
        }
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, arrayOf(tm), SecureRandom())
        conn.sslSocketFactory = ctx.socketFactory
        conn.hostnameVerifier = HostnameVerifier { _, _ -> true }
        conn.connectTimeout = 6000
        conn.readTimeout = 6000
        conn.requestMethod = "GET"
        return try {
            conn.connect()
            val certs = conn.serverCertificates
            val leaf = certs[0] as X509Certificate
            fingerprintOf(leaf)
        } finally {
            conn.disconnect()
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
