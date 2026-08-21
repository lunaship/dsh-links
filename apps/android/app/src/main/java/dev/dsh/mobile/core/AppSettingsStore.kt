package dev.dsh.mobile.core
import dev.dsh.mobile.native.MobileSettingsNamespace
import dev.dsh.mobile.core.Host
import dev.dsh.mobile.core.AppSettingsStore
import dev.dsh.mobile.native.AppSettings
import dev.dsh.mobile.native.MobileApiClient

import android.content.Context
import org.json.JSONObject

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
            settings.persist(context)
            settings
        } catch (e: Exception) {
            cached(context)
        }
    }

    fun cached(context: Context): AppSettings = appSettingsFromPrefs(
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    )

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
        val merged = cached(context).withNamespace(ns, updated.value)
        merged.persist(context)
        return updated
    }
}

/** 读回值必须包含提交的每个键且值一致，否则视为未生效（保存失败）。 */
fun verifyPatchApplied(readBack: JSONObject, patch: JSONObject, ns: String) {
    val mismatch = patch.keys().asSequence().any { key ->
        !jsonSettingValuesEqual(patch.opt(key), readBack.opt(key))
    }
    if (mismatch) {
        throw IllegalStateException("保存校验失败：服务端未采用提交值（$ns），请重试")
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
fun AppSettings.persist(context: Context) {
    context.getSharedPreferences("dsh_settings", Context.MODE_PRIVATE).edit()
        .putString("app_settings_agent_preset", agentPreset)
        .putString("app_settings_permission", permissionPreset)
        .putString("app_settings_language", language)
        .putString("app_settings_theme", theme)
        .putString("app_settings_busy_enter", busyEnter)
        .putString("app_settings_model_provider", defaultModelProvider)
        .putString("app_settings_model", defaultModel)
        .putString("app_settings_reasoning_effort", defaultReasoningEffort)
        .apply()
}

fun appSettingsFromPrefs(prefs: android.content.SharedPreferences): AppSettings = AppSettings(
    agentPreset = prefs.getString("app_settings_agent_preset", "standard") ?: "standard",
    permissionPreset = prefs.getString("app_settings_permission", "workspace-write") ?: "workspace-write",
    language = prefs.getString("app_settings_language", "zh") ?: "zh",
    theme = prefs.getString("app_settings_theme", "system") ?: "system",
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
