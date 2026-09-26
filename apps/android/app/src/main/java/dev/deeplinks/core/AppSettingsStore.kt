package dev.deeplinks.core
import dev.deeplinks.native.MobileSettingsNamespace
import dev.deeplinks.core.Host
import dev.deeplinks.core.AppSettingsStore
import dev.deeplinks.native.AppSettings
import dev.deeplinks.native.MobileApiClient

import android.content.Context
import org.json.JSONObject
import java.security.MessageDigest

/**
 * AppSettings 存储（WI-004）：服务端 settings seam 为唯一真实配置源。
 *
 * - [fetch]：先读服务端（脱敏视图），成功则更新本地缓存；失败回退缓存，保证离线可用。
 * - [save]：写服务端并校验读回值一致（只对提交过的键校验），不一致视为保存失败；
 *   成功后才更新本地缓存 —— 不出现"保存成功但实际未生效"。
 * - API key 等 secret 字段由服务端 seam 脱敏，值不会进入本模型、日志或 UI。
 */
object AppSettingsStore {

    private const val PREFS = "dsh_settings"

    suspend fun fetch(host: Host, context: Context): AppSettings {
        return try {
            val view = MobileApiClient(host).getSettings()
            val settings = AppSettings.fromServer(view.namespaces)
            // Server-controlled values are cached per host slot. Locale/theme remain
            // device experience preferences and are never imported into another host's
            // offline cache merely because that host was opened last.
            settings.persist(context, host, updateLocalExperience = true)
            settings
        } catch (e: Exception) {
            cached(context, host)
        }
    }

    fun cached(context: Context, host: Host? = null): AppSettings {
        val global = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return if (host == null) {
            appSettingsFromPrefs(global)
        } else {
            appSettingsFromPrefs(hostPrefs(context, host), global)
        }
    }

    /**
     * 写服务端并校验读回值。返回服务端新视图；校验通过后本地缓存已更新。
     * 失败抛异常：消息可直接用于行内错误展示。
     */
    suspend fun save(
        host: Host,
        context: Context,
        ns: String,
        patch: JSONObject,
        expectedRevision: Long? = null,
    ): MobileSettingsNamespace {
        val updated = MobileApiClient(host).updateSettings(ns, patch, expectedRevision)
        verifyPatchApplied(updated.value, patch, ns)
        val merged = cached(context, host).withNamespace(ns, updated.value)
        merged.persist(
            context,
            host,
            updateLocalExperience = ns == "locale" || ns == "ui-theme",
        )
        return updated
    }

    private fun hostPrefs(context: Context, host: Host): android.content.SharedPreferences =
        context.getSharedPreferences(appSettingsHostPrefsName(host), Context.MODE_PRIVATE)
}

/** Stable cache identity: renaming a host must not select a new settings namespace. */
internal fun appSettingsHostCacheId(host: Host): String {
    val identity = listOf(
        PinnedSsl.normalizeUrl(host.baseUrl).trimEnd('/').lowercase(),
        PinnedSsl.normalizeFingerprint(host.certFingerprint),
        host.deviceId.trim(),
        host.relayClient.trim().lowercase(),
        host.relayRouteId.trim(),
    ).joinToString("\u001f")
    return MessageDigest.getInstance("SHA-256")
        .digest(identity.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
        .take(24)
}

private fun appSettingsHostPrefsName(host: Host): String =
    "dsh_settings_host_${appSettingsHostCacheId(host)}"

/** 读回值必须包含提交的每个键且值一致，否则视为未生效（保存失败）。 */
fun verifyPatchApplied(readBack: JSONObject, patch: JSONObject, ns: String) {
    val mismatch = patch.keys().asSequence().any { key ->
        !jsonSettingValuesEqual(patch.opt(key), readBack.opt(key))
    }
    if (mismatch) {
        throw IllegalStateException(LocaleManager.strings.saveVerifyFailed.format(ns))
    }
}

private fun jsonSettingValuesEqual(expected: Any?, actual: Any?): Boolean {
    if (expected == null || expected == JSONObject.NULL) {
        return actual == null || actual == JSONObject.NULL
    }
    return expected == actual
}

private fun JSONObject.readSettingString(key: String, fallback: String?): String? {
    if (!has(key)) return fallback
    if (isNull(key)) return null
    return optString(key).takeIf { it.isNotBlank() }
}

/** 本地缓存：保存成功后写入，启动/离线时回退。 */
fun AppSettings.persist(
    context: Context,
    host: Host? = null,
    updateLocalExperience: Boolean = host == null,
) {
    val prefs = if (host == null) {
        context.getSharedPreferences("dsh_settings", Context.MODE_PRIVATE)
    } else {
        context.getSharedPreferences(appSettingsHostPrefsName(host), Context.MODE_PRIVATE)
    }
    prefs.edit()
        .putString("app_settings_agent_preset", agentPreset)
        .putString("app_settings_permission", permissionPreset)
        .putString("app_settings_busy_enter", busyEnter)
        .putString("app_settings_model_provider", defaultModelProvider)
        .putString("app_settings_model", defaultModel)
        .putString("app_settings_reasoning_effort", defaultReasoningEffort)
        .apply()
    if (host == null || updateLocalExperience) {
        context.getSharedPreferences("dsh_settings", Context.MODE_PRIVATE).edit()
            .putString("app_settings_language", language)
            .putString("app_settings_theme", theme)
            .putString("theme", theme)
            .apply()
    }
}

fun appSettingsFromPrefs(
    prefs: android.content.SharedPreferences,
    localPrefs: android.content.SharedPreferences = prefs,
): AppSettings = AppSettings(
    agentPreset = prefs.getString("app_settings_agent_preset", "standard") ?: "standard",
    permissionPreset = prefs.getString("app_settings_permission", "workspace-write") ?: "workspace-write",
    language = localPrefs.getString("app_settings_language", "zh") ?: "zh",
    theme = localPrefs.getString("app_settings_theme", "system") ?: "system",
    busyEnter = prefs.getString("app_settings_busy_enter", "queue") ?: "queue",
    defaultModelProvider = prefs.getString("app_settings_model_provider", null),
    defaultModel = prefs.getString("app_settings_model", null),
    defaultReasoningEffort = prefs.getString("app_settings_reasoning_effort", null),
)

/** 用服务端读回的命名空间值覆盖对应字段。 */
fun AppSettings.withNamespace(ns: String, value: JSONObject): AppSettings = when (ns) {
    "agent-presets" -> copy(agentPreset = value.optString("default").ifBlank { agentPreset })
    "permission" -> copy(permissionPreset = value.optString("defaultPreset").ifBlank { permissionPreset })
    "locale" -> copy(language = value.optString("preference").ifBlank { language })
    "ui-theme" -> copy(theme = value.optString("preference").ifBlank { theme })
    "ui-conversation" -> copy(busyEnter = value.optString("busyEnter").ifBlank { busyEnter })
    "agent-default-model" -> copy(
        defaultModelProvider = value.readSettingString("provider", defaultModelProvider),
        defaultModel = value.readSettingString("model", defaultModel),
        defaultReasoningEffort = value.readSettingString("reasoningEffort", defaultReasoningEffort),
    )
    else -> this
}
