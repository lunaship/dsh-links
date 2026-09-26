package dev.deeplinks.native.util

import android.content.Context
import org.json.JSONObject

/**
 * 侧边栏归档/删除后，设置页仍需展示标题等信息；服务端列表可能不再返回这些会话。
 */
data class SessionSnapshot(
    val sessionId: String,
    val title: String,
    val cwd: String?,
    val updatedAt: Long,
)

/**
 * 工作区本地 SharedPreferences 类型化封装（WI-005 / WI-006）。
 *
 * 设计目标：
 * - **保留现有 key 不变**：archived_sessions / deleted_sessions / deleted_workspaces / notif_permission_asked。
 *   已有 App 升级到新版本时不会丢数据。
 * - **新增会话过滤**：sessionFilter，键名加 `workspace_` 前缀避免与现有键冲突。
 * - 集中暴露 typed 读写 API：调用方不再手动 `getStringSet("...")`。
 *
 * Prefs 文件名：`"dsh_workspace"` —— 与 [dev.deeplinks.native.WorkspaceActivity] / [dev.deeplinks.native.SettingsActivity] 既有调用保持一致。
 *
 * 单测：纯 Android API 路径无法 JVM 单测；行为通过设备集成测试覆盖；下游纯函数
 * （[filterSessions] / [classifySession] / [filterSlashCommands]）走 [WorkspacePrefsTest]。
 */
class WorkspacePrefs(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ===== 已存在的键（保留以兼容既有数据） =====

    /** 已归档会话 id 集合。 */
    var archivedSessionIds: Set<String>
        get() = prefs.getStringSet(KEY_ARCHIVED, emptySet()) ?: emptySet()
        set(value) {
            prefs.edit().putStringSet(KEY_ARCHIVED, value).apply()
        }

    /** 已删除会话 id 集合（本地软删）。 */
    var deletedSessionIds: Set<String>
        get() = prefs.getStringSet(KEY_DELETED, emptySet()) ?: emptySet()
        set(value) {
            prefs.edit().putStringSet(KEY_DELETED, value).apply()
        }

    /**
     * 用户在设置页主动恢复的会话。服务端 archivedSessionIds 同步时跳过这些 id，
     * 避免刚恢复又被重新隐藏。
     */
    var restoredSessionIds: Set<String>
        get() = prefs.getStringSet(KEY_RESTORED, emptySet()) ?: emptySet()
        set(value) {
            prefs.edit().putStringSet(KEY_RESTORED, value).apply()
        }

    /**
     * 设置页「清除本机记录」后不再展示，但仍保留 archived/deleted 隐藏态，
     * 避免会话重新出现在侧边栏。
     */
    var settingsHiddenSessionIds: Set<String>
        get() = prefs.getStringSet(KEY_SETTINGS_HIDDEN, emptySet()) ?: emptySet()
        set(value) {
            prefs.edit().putStringSet(KEY_SETTINGS_HIDDEN, value).apply()
        }

    /** 本地软删的工作区路径集合（对齐 server 删注册后 cwd 残留）。 */
    var deletedWorkspacePaths: Set<String>
        get() = prefs.getStringSet(KEY_DELETED_WORKSPACES, emptySet()) ?: emptySet()
        set(value) {
            prefs.edit().putStringSet(KEY_DELETED_WORKSPACES, value).apply()
        }

    /** 是否已询问过通知权限。 */
    var notifPermissionAsked: Boolean
        get() = prefs.getBoolean(KEY_NOTIF_ASKED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_NOTIF_ASKED, value).apply()
        }

    // ===== 新增键：sessionFilter（WI-006） =====

    /** 当前 chip 选中状态：序列化 / 反序列化走 [SessionFilter.name]。 */
    var sessionFilter: SessionFilter
        get() = SessionFilter.values().firstOrNull {
            it.name == prefs.getString(KEY_SESSION_FILTER, SessionFilter.ALL.name)
        } ?: SessionFilter.ALL
        set(value) {
            prefs.edit().putString(KEY_SESSION_FILTER, value.name).apply()
        }

    /** 输入区工作区选择：记住上次选中的 cwd，避免始终显示排序第一项。 */
    var lastSelectedWorkspace: String?
        get() = prefs.getString(KEY_LAST_WORKSPACE, null)
        set(value) {
            prefs.edit().putString(KEY_LAST_WORKSPACE, value).apply()
        }

    /** 归档/删除会话的本地快照（设置页展示用）。 */
    var sessionSnapshots: Map<String, SessionSnapshot>
        get() {
            val raw = prefs.getString(KEY_SESSION_SNAPSHOTS, null) ?: return emptyMap()
            return try {
                val root = JSONObject(raw)
                buildMap {
                    for (key in root.keys()) {
                        val obj = root.optJSONObject(key) ?: continue
                        put(
                            key,
                            SessionSnapshot(
                                sessionId = key,
                                title = obj.optString("title").ifBlank { key },
                                cwd = obj.optString("cwd").takeIf { it.isNotBlank() },
                                updatedAt = obj.optLong("updatedAt", 0L),
                            ),
                        )
                    }
                }
            } catch (_: Exception) {
                emptyMap()
            }
        }
        set(value) {
            val root = JSONObject()
            value.forEach { (id, snap) ->
                root.put(
                    id,
                    JSONObject()
                        .put("title", snap.title)
                        .put("cwd", snap.cwd ?: "")
                        .put("updatedAt", snap.updatedAt),
                )
            }
            prefs.edit().putString(KEY_SESSION_SNAPSHOTS, root.toString()).apply()
        }

    /**
     * 发送中被系统杀掉时停住的草稿。用 [commit] 保证进网前落盘。
     * 成功或失败回填后清空；冷启动只回填输入框，不自动重发。
     */
    var parkedSend: ParkedSend?
        get() = decodeParkedSend(prefs.getString(KEY_PARKED_SEND, null))
        set(value) {
            val toStore = value?.let { parkSendForPersistence(it) }
            val editor = prefs.edit()
            if (toStore == null) editor.remove(KEY_PARKED_SEND)
            else editor.putString(KEY_PARKED_SEND, encodeParkedSend(toStore))
            editor.commit()
        }

    fun rememberSessionSnapshot(sessionId: String, title: String, cwd: String?, updatedAt: Long) {
        val next = sessionSnapshots.toMutableMap()
        next[sessionId] = SessionSnapshot(sessionId, title.ifBlank { sessionId }, cwd, updatedAt)
        sessionSnapshots = next
    }

    fun forgetSessionSnapshot(sessionId: String) {
        forgetSessionSnapshots(listOf(sessionId))
    }

    fun forgetSessionSnapshots(ids: Collection<String>) {
        if (ids.isEmpty()) return
        val toRemove = ids.toSet()
        val next = sessionSnapshots.toMutableMap()
        var changed = false
        for (id in toRemove) {
            if (next.remove(id) != null) changed = true
        }
        if (changed) sessionSnapshots = next
    }

    /** 设置页「清除本机记录」：从列表隐藏，但保留 archived/deleted 以免回到侧边栏。 */
    fun hideFromSettings(ids: Collection<String>) {
        val set = ids.toSet()
        if (set.isEmpty()) return
        settingsHiddenSessionIds = settingsHiddenSessionIds + set
        forgetSessionSnapshots(set)
    }

    /** 记住每台设备最近打开过的会话，供下次冷启动恢复。 */
    fun rememberLastSession(hostIdentity: String, sessionId: String) {
        val identity = hostIdentity.trim()
        val id = sessionId.trim()
        if (identity.isEmpty() || id.isEmpty()) return
        prefs.edit().putString(KEY_LAST_SESSION_PREFIX + identity, id).apply()
    }

    /** 最近会话 id；会话已被删除/归档时仍可能返回陈旧值，调用方需自行校验可见性。 */
    fun lastSessionId(hostIdentity: String): String? {
        val identity = hostIdentity.trim()
        if (identity.isEmpty()) return null
        return prefs.getString(KEY_LAST_SESSION_PREFIX + identity, null)?.takeIf { it.isNotBlank() }
    }

    /** 会话被删除或归档后清掉记录，避免下次启动自恢复时去拉一个不存在的会话。 */
    fun forgetLastSession(hostIdentity: String, sessionId: String) {
        val identity = hostIdentity.trim()
        if (identity.isEmpty()) return
        val key = KEY_LAST_SESSION_PREFIX + identity
        if (prefs.getString(key, null) != sessionId) return
        prefs.edit().remove(key).apply()
    }

    companion object {
        const val PREFS_NAME = "dsh_workspace"

        // 既有键：保留字面量，确保升级不丢数据
        const val KEY_ARCHIVED = "archived_sessions"
        const val KEY_DELETED = "deleted_sessions"
        const val KEY_RESTORED = "workspace_restored_sessions"
        const val KEY_SETTINGS_HIDDEN = "workspace_settings_hidden_sessions"
        const val KEY_DELETED_WORKSPACES = "deleted_workspaces"
        const val KEY_NOTIF_ASKED = "notif_permission_asked"

        // 新增键：加 `workspace_` 前缀避免与旧键混淆
        const val KEY_SESSION_FILTER = "workspace_session_filter"
        const val KEY_LAST_WORKSPACE = "workspace_last_selected_cwd"
        const val KEY_SESSION_SNAPSHOTS = "workspace_session_snapshots"
        const val KEY_PARKED_SEND = "workspace_parked_send"

        /** 每台设备一条：`last_session_id:<stable_host_identity>`。 */
        const val KEY_LAST_SESSION_PREFIX = "workspace_last_session_id:"
    }
}