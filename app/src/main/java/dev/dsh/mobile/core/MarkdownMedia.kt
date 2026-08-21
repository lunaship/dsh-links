package dev.dsh.mobile.core

import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI

/**
 * Markdown 图片 URL 白名单：只允许 https 公网目标，挡住私网/回环/链路本地/元数据地址。
 */
object MarkdownMedia {
    fun isSafeImageUrl(raw: String): Boolean {
        val uri = runCatching { URI(raw.trim()) }.getOrNull() ?: return false
        if (!uri.isAbsolute || uri.scheme?.lowercase() != "https") return false
        if (!uri.userInfo.isNullOrEmpty()) return false
        val host = uri.host?.lowercase()?.trim('.') ?: return false
        if (host.isEmpty()) return false
        if (isBlockedHostname(host)) return false
        parseIpv4(host)?.let { return !isBlockedIpv4(it) }
        if (':' in host) return !isBlockedIpv6Literal(host)
        return true
    }

    fun takeIfSafe(raw: String): String? = raw.takeIf { isSafeImageUrl(it) }

    fun assertPublicHttps(url: String) {
        if (!isSafeImageUrl(url)) throw IOException("blocked markdown image url")
        val host = URI(url).host ?: throw IOException("blocked markdown image url")
        val addrs = InetAddress.getAllByName(host)
        if (addrs.isEmpty() || addrs.any { isBlockedInetAddress(it) }) {
            throw IOException("blocked markdown image destination")
        }
    }

    fun isBlockedInetAddress(addr: InetAddress): Boolean {
        if (addr.isAnyLocalAddress || addr.isLoopbackAddress || addr.isLinkLocalAddress) return true
        if (addr.isSiteLocalAddress || addr.isMulticastAddress) return true
        return when (addr) {
            is Inet4Address -> isBlockedIpv4(addr.address.map { it.toInt() and 0xff }.toIntArray())
            is Inet6Address -> {
                if (addr.isIPv4CompatibleAddress || addr.address[0] == 0xfc.toByte() || addr.address[0] == 0xfd.toByte()) return true
                val mapped = ipv4Mapped(addr) ?: return false
                isBlockedIpv4(mapped)
            }
            else -> true
        }
    }

    internal fun isBlockedHostname(host: String): Boolean {
        if (host == "localhost" || host == "metadata.google.internal") return true
        if (host.endsWith(".localhost") || host.endsWith(".local") || host.endsWith(".internal")) return true
        if (host.endsWith(".lan") || host.endsWith(".home") || host.endsWith(".corp")) return true
        return false
    }

    internal fun parseIpv4(host: String): IntArray? {
        val parts = host.split('.')
        if (parts.size != 4) return null
        val nums = IntArray(4)
        for (i in 0..3) {
            val n = parts[i].toIntOrNull() ?: return null
            if (n !in 0..255) return null
            nums[i] = n
        }
        return nums
    }

    internal fun isBlockedIpv4(o: IntArray): Boolean {
        if (o.size != 4) return true
        val a = o[0]
        val b = o[1]
        if (a == 0 || a == 127 || a == 10) return true
        if (a == 169 && b == 254) return true
        if (a == 192 && b == 168) return true
        if (a == 172 && b in 16..31) return true
        if (a == 100 && b in 64..127) return true // CGNAT
        if (a == 192 && b == 0 && o[2] == 2) return true
        if (a >= 224) return true // multicast / reserved
        return false
    }

    internal fun isBlockedIpv6Literal(host: String): Boolean {
        val h = host.lowercase().trimStart('[').trimEnd(']')
        if (h == "::1" || h == "0:0:0:0:0:0:0:1") return true
        if (h.startsWith("fe80:") || h.startsWith("fc") || h.startsWith("fd")) return true
        if (h.startsWith("::ffff:")) {
            val v4 = h.removePrefix("::ffff:")
            parseIpv4(v4)?.let { return isBlockedIpv4(it) }
        }
        return false
    }

    private fun ipv4Mapped(addr: Inet6Address): IntArray? {
        val b = addr.address
        if (b.size != 16) return null
        val mapped = (0..9).all { b[it] == 0.toByte() } && b[10] == 0xff.toByte() && b[11] == 0xff.toByte()
        if (!mapped) return null
        return intArrayOf(b[12].toInt() and 0xff, b[13].toInt() and 0xff, b[14].toInt() and 0xff, b[15].toInt() and 0xff)
    }
}
