package dev.dsh.mobile

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class Host(val name: String, val baseUrl: String, val token: String)

object HostStore {
    private const val PREFS = "dsh_hosts"
    private const val KEY = "hosts"

    fun load(ctx: Context): List<Host> {
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return emptyList()
        val hosts = if (TokenCrypto.isEncrypted(raw)) {
            val plain = TokenCrypto.decrypt(ctx, raw) ?: return emptyList()
            hostsFromJson(plain)
        } else {
            // 旧版明文数据：读取后立即迁移为加密存储并清掉明文
            val legacy = hostsFromJson(raw)
            if (legacy.isNotEmpty()) {
                save(ctx, legacy)
            } else {
                ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply()
            }
            legacy
        }
        return hosts
    }

    fun save(ctx: Context, hosts: List<Host>) {
        val encrypted = TokenCrypto.encrypt(ctx, hostsToJson(hosts))
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, encrypted).apply()
    }

    /** 加入/更新主机：同一名称或同一地址只保留一条，最新记录置顶。 */
    fun upsert(ctx: Context, host: Host) {
        save(ctx, dedupeHosts(load(ctx), host))
    }

    /** 按地址移除（手动配对覆盖旧记录时用）。 */
    fun removeByUrl(ctx: Context, baseUrl: String) {
        save(ctx, load(ctx).filter { it.baseUrl != baseUrl })
    }

    /** 重命名设备（本地别名）。 */
    fun rename(ctx: Context, baseUrl: String, newName: String) {
        save(ctx, load(ctx).map {
            if (it.baseUrl == baseUrl) it.copy(name = newName) else it
        })
    }

    fun remove(ctx: Context, name: String) {
        save(ctx, load(ctx).filter { it.name != name })
    }

    // ---- 纯函数（单元测试直测，无 Android 依赖） ----

    internal fun hostsToJson(hosts: List<Host>): String {
        val arr = JSONArray()
        for (h in hosts) {
            arr.put(JSONObject().put("name", h.name).put("baseUrl", h.baseUrl).put("token", h.token))
        }
        return arr.toString()
    }

    internal fun hostsFromJson(json: String): List<Host> {
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Host(o.getString("name"), o.getString("baseUrl"), o.getString("token"))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    internal fun dedupeHosts(list: List<Host>, host: Host): List<Host> =
        listOf(host) + list.filter { it.name != host.name && it.baseUrl != host.baseUrl }
}
