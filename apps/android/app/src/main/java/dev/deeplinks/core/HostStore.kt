package dev.deeplinks.core

import android.content.Context
import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject

data class Host(
    val name: String,
    val baseUrl: String,
    val token: String,
    val deviceId: String = "",
    val certFingerprint: String = "",
    val relayClient: String = "",
    val relayRouteId: String = "",
    val relayRouteSecret: String = "",
    val relayTlsFingerprint: String = "",
    val preferRelay: Boolean = false,
    val needsCloudRescan: Boolean = false,
) {
    val hasRelay: Boolean
        get() = relayClient.isNotBlank() && relayRouteId.isNotBlank() && relayRouteSecret.isNotBlank()

    /** 局域网与云端可以共用 baseUrl，必须用名称 + 是否走 Relay 区分。 */
    val slotKey: String
        get() = "${if (hasRelay) "relay" else "lan"}|$name|$baseUrl"

    fun withoutRelay(): Host {
        val lostCloud = hasRelay
        return copy(
            relayClient = "",
            relayRouteId = "",
            relayRouteSecret = "",
            relayTlsFingerprint = "",
            preferRelay = false,
            needsCloudRescan = needsCloudRescan || lostCloud,
        )
    }

    fun putInto(intent: Intent): Intent {
        intent.putExtra(EXTRA_HOST_NAME, name)
        intent.putExtra(EXTRA_HOST_BASE_URL, baseUrl)
        intent.putExtra(EXTRA_HOST_RELAY, hasRelay)
        return intent
    }
}

const val EXTRA_HOST_NAME = "hostName"
const val EXTRA_HOST_BASE_URL = "hostBaseUrl"
const val EXTRA_HOST_RELAY = "hostRelay"
const val EXTRA_AUTH_NOTICE = "authNotice"

fun List<Host>.resolveFromIntent(intent: Intent): Host? {
    val name = intent.getStringExtra(EXTRA_HOST_NAME)
    val url = intent.getStringExtra(EXTRA_HOST_BASE_URL)
    val relay = if (intent.hasExtra(EXTRA_HOST_RELAY)) intent.getBooleanExtra(EXTRA_HOST_RELAY, false) else null
    return resolveHost(name, url, relay)
}

internal fun List<Host>.resolveHost(name: String?, baseUrl: String?, hasRelay: Boolean?): Host? {
    if (!name.isNullOrBlank()) {
        val named = filter { it.name == name }
        if (hasRelay != null) named.firstOrNull { it.hasRelay == hasRelay }?.let { return it }
        if (named.size == 1) return named.first()
        if (!baseUrl.isNullOrBlank()) named.firstOrNull { it.baseUrl == baseUrl }?.let { return it }
        named.firstOrNull()?.let { return it }
    }
    if (!baseUrl.isNullOrBlank()) {
        val urls = filter { it.baseUrl == baseUrl }
        if (hasRelay != null) urls.firstOrNull { it.hasRelay == hasRelay }?.let { return it }
        urls.firstOrNull()?.let { return it }
    }
    return firstOrNull()
}

sealed class HostLoadResult {
    data class Ok(val hosts: List<Host>) : HostLoadResult()
    data object Empty : HostLoadResult()
    data object Undecryptable : HostLoadResult()
}

/**
 * 本机配对的电脑：**单设备**。只保存一台；配对新电脑即替换旧的。
 *
 * 存储格式仍是 JSON 数组（与旧版多设备数据互相可读）；读到旧版多台记录时
 * 只取最近使用的那一台（[singleHostOf]），其余在下一次写入时自然丢弃。
 */
object HostStore {
    private const val PREFS = "dsh_hosts"
    private const val KEY = "hosts"
    private const val LOCK = "hosts_locked"

    /** 最近成功进入 Workspace 的设备身份（[stableIdentity]，重命名安全）。 */
    private const val KEY_LAST_HOST = "last_host_identity"

    fun loadResult(ctx: Context): HostLoadResult {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY, null) ?: return HostLoadResult.Empty
        val lastIdentity = prefs.getString(KEY_LAST_HOST, null)
        return if (TokenCrypto.isEncrypted(raw)) {
            val plain = TokenCrypto.decrypt(ctx, raw)
            if (plain == null) {
                prefs.edit().putBoolean(LOCK, true).apply()
                HostLoadResult.Undecryptable
            } else {
                prefs.edit().putBoolean(LOCK, false).apply()
                val host = singleHostOf(hostsFromJson(plain), lastIdentity)
                if (host == null) HostLoadResult.Empty else HostLoadResult.Ok(listOf(host))
            }
        } else {
            val legacy = singleHostOf(hostsFromJson(raw), lastIdentity)
            if (legacy == null) {
                prefs.edit().remove(KEY).apply()
                return HostLoadResult.Empty
            }
            if (!save(ctx, legacy)) return HostLoadResult.Undecryptable
            val stored = prefs.getString(KEY, null)
            if (stored.isNullOrEmpty() || !TokenCrypto.isEncrypted(stored)) {
                return HostLoadResult.Undecryptable
            }
            HostLoadResult.Ok(listOf(legacy))
        }
    }

    /** 已配对设备（至多一台）。 */
    fun load(ctx: Context): List<Host> = when (val r = loadResult(ctx)) {
        is HostLoadResult.Ok -> r.hosts
        HostLoadResult.Empty, HostLoadResult.Undecryptable -> emptyList()
    }

    /** 当前配对的电脑；未配对或凭据不可读时为 null。 */
    fun current(ctx: Context): Host? = load(ctx).firstOrNull()

    private fun save(ctx: Context, host: Host?): Boolean {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(LOCK, false)) return false
        val hosts = listOfNotNull(host)
        return try {
            val encrypted = TokenCrypto.encrypt(ctx, hostsToJson(hosts))
            val committed = prefs.edit().putString(KEY, encrypted).commit()
            if (committed) syncLastHostIdentity(prefs, host)
            committed
        } catch (_: Exception) {
            false
        }
    }

    private fun syncLastHostIdentity(prefs: android.content.SharedPreferences, host: Host?) {
        val current = prefs.getString(KEY_LAST_HOST, null)
        val next = host?.stableIdentity()
        if (next == current) return
        val editor = prefs.edit()
        if (next == null) editor.remove(KEY_LAST_HOST) else editor.putString(KEY_LAST_HOST, next)
        editor.apply()
    }

    /** 最近成功使用的设备身份；null 表示尚无记录（首次启动或已解除配对）。 */
    fun lastHostIdentity(ctx: Context): String? =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LAST_HOST, null)

    /** 设备成功连接并进入 Workspace 后调用。 */
    fun rememberLastHost(ctx: Context, host: Host) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_HOST, host.stableIdentity())
            .apply()
    }

    fun isLocked(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(LOCK, false)

    /**
     * 写入配对设备：同一台的更新（Relay 刷新、连接偏好）与配对新电脑都走这里，
     * 后者直接替换旧设备。
     */
    @Synchronized
    fun upsert(ctx: Context, host: Host): Boolean {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(LOCK, false)) {
            // 解锁态禁止静默覆盖：调用方须显式 clearLockAndReplace
            return false
        }
        return save(ctx, if (host.hasRelay) host.copy(needsCloudRescan = false) else host)
    }

    /** 密钥不可用后的显式恢复：清掉不可读的旧记录并写入这一台。 */
    @Synchronized
    fun clearLockAndReplace(ctx: Context, host: Host): Boolean {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(LOCK, false).apply()
        return save(ctx, host)
    }

    /** 解除配对（本机侧）。只在存的还是这台时清空，避免误删刚换上的新设备。 */
    @Synchronized
    fun remove(ctx: Context, host: Host): Boolean {
        val current = current(ctx) ?: return true
        if (current.slotKey != host.slotKey && current.baseUrl != host.baseUrl) return true
        return save(ctx, null)
    }

    /** 云端路由失效：降级为仅局域网，并标记需要重新扫码恢复云端。 */
    @Synchronized
    fun demoteRelay(ctx: Context, host: Host): Boolean {
        if (!host.hasRelay) return true
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(LOCK, false)) return false
        val current = current(ctx)
        if (current != null && current.baseUrl != host.baseUrl) return true
        return save(ctx, (current ?: host).withoutRelay())
    }

    @Synchronized
    fun clearCloudRescan(ctx: Context): Boolean {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(LOCK, false)) return false
        val current = current(ctx) ?: return true
        if (!current.needsCloudRescan) return true
        return save(ctx, current.copy(needsCloudRescan = false))
    }

    // ---- 纯函数（单元测试直测，无 Android 依赖） ----

    internal fun hostsToJson(hosts: List<Host>): String {
        val arr = JSONArray()
        for (h in hosts) {
            arr.put(
                JSONObject()
                    .put("name", h.name)
                    .put("baseUrl", h.baseUrl)
                    .put("token", h.token)
                    .put("deviceId", h.deviceId)
                    .put("certFingerprint", h.certFingerprint)
                    .put("relayClient", h.relayClient)
                    .put("relayRouteId", h.relayRouteId)
                    .put("relayRouteSecret", h.relayRouteSecret)
                    .put("relayTlsFingerprint", h.relayTlsFingerprint)
                    .put("preferRelay", h.preferRelay)
                    .put("needsCloudRescan", h.needsCloudRescan),
            )
        }
        return arr.toString()
    }

    internal fun hostsFromJson(json: String): List<Host> {
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val rawUrl = o.getString("baseUrl")
                val url = if (rawUrl.startsWith("http://")) "https://" + rawUrl.removePrefix("http://") else rawUrl
                Host(
                    o.getString("name"),
                    url,
                    o.getString("token"),
                    o.optString("deviceId"),
                    o.optString("certFingerprint"),
                    o.optString("relayClient"),
                    o.optString("relayRouteId"),
                    o.optString("relayRouteSecret"),
                    o.optString("relayTlsFingerprint"),
                    o.optBoolean("preferRelay", false),
                    o.optBoolean("needsCloudRescan", false),
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** 旧版多设备数据收敛为一台：优先最近使用的，否则取列表首项（最近配对）。 */
    internal fun singleHostOf(hosts: List<Host>, lastIdentity: String?): Host? =
        pickStartupHost(hosts, intentHost = null, lastIdentity = lastIdentity)
}

/**
 * Plugin bootstrap `relay` field: object updates cloud creds; JSON null clears them;
 * missing key leaves a stored route alone so older plugins do not wipe pairing.
 */
internal fun applyBootstrapRelay(host: Host, root: JSONObject): Host {
    if (!root.has("relay")) return host
    if (root.isNull("relay")) return host.withoutRelay()
    val obj = root.optJSONObject("relay") ?: return host
    val client = obj.optString("client").trim()
    val routeId = obj.optString("routeId").trim()
    val routeSecret = obj.optString("routeSecret").trim()
    if (client.isEmpty() || routeId.isEmpty() || routeSecret.isEmpty()) return host
    return host.copy(
        relayClient = client,
        relayRouteId = routeId,
        relayRouteSecret = routeSecret,
        relayTlsFingerprint = obj.optString("tlsFingerprint").trim(),
        needsCloudRescan = false,
    )
}
