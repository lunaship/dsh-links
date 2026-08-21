package dev.dsh.mobile.core
import dev.dsh.mobile.core.Host
import dev.dsh.mobile.core.HostStore
import dev.dsh.mobile.core.TokenCrypto

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class Host(
    val name: String,
    val baseUrl: String,
    val token: String,
    val deviceId: String = "",
    val certFingerprint: String = "",
)

sealed class HostLoadResult {
    data class Ok(val hosts: List<Host>) : HostLoadResult()
    data object Empty : HostLoadResult()
    data object Undecryptable : HostLoadResult()
}

object HostStore {
    private const val PREFS = "dsh_hosts"
    private const val KEY = "hosts"
    private const val LOCK = "hosts_locked"

    fun loadResult(ctx: Context): HostLoadResult {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY, null) ?: return HostLoadResult.Empty
        return if (TokenCrypto.isEncrypted(raw)) {
            val plain = TokenCrypto.decrypt(ctx, raw)
            if (plain == null) {
                prefs.edit().putBoolean(LOCK, true).apply()
                HostLoadResult.Undecryptable
            } else {
                prefs.edit().putBoolean(LOCK, false).apply()
                HostLoadResult.Ok(hostsFromJson(plain))
            }
        } else {
            val legacy = hostsFromJson(raw)
            if (legacy.isEmpty()) {
                prefs.edit().remove(KEY).apply()
                return HostLoadResult.Empty
            }
            if (!save(ctx, legacy)) return HostLoadResult.Undecryptable
            val stored = prefs.getString(KEY, null)
            if (stored.isNullOrEmpty() || !TokenCrypto.isEncrypted(stored)) {
                return HostLoadResult.Undecryptable
            }
            HostLoadResult.Ok(legacy)
        }
    }

    fun load(ctx: Context): List<Host> = when (val r = loadResult(ctx)) {
        is HostLoadResult.Ok -> r.hosts
        HostLoadResult.Empty, HostLoadResult.Undecryptable -> emptyList()
    }

    fun save(ctx: Context, hosts: List<Host>): Boolean {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(LOCK, false)) return false
        return try {
            val encrypted = TokenCrypto.encrypt(ctx, hostsToJson(hosts))
            prefs.edit().putString(KEY, encrypted).commit()
        } catch (_: Exception) {
            false
        }
    }

    fun upsert(ctx: Context, host: Host): Boolean {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(LOCK, false)) {
            prefs.edit().putBoolean(LOCK, false).apply()
            return save(ctx, listOf(host))
        }
        return save(ctx, dedupeHosts(load(ctx), host))
    }

    /** 按地址移除（手动配对覆盖旧记录时用）。 */
    fun removeByUrl(ctx: Context, baseUrl: String): Boolean {
        return save(ctx, load(ctx).filter { it.baseUrl != baseUrl })
    }

    fun rename(ctx: Context, baseUrl: String, newName: String): Boolean {
        return save(ctx, load(ctx).map {
            if (it.baseUrl == baseUrl) it.copy(name = newName) else it
        })
    }

    fun remove(ctx: Context, name: String): Boolean {
        return save(ctx, load(ctx).filter { it.name != name })
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
                    .put("certFingerprint", h.certFingerprint),
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
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    internal fun dedupeHosts(list: List<Host>, host: Host): List<Host> =
        listOf(host) + list.filter { it.name != host.name && it.baseUrl != host.baseUrl }
}
