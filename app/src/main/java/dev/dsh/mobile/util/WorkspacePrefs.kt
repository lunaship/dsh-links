package dev.dsh.mobile.util

import android.content.Context
import dev.dsh.mobile.util.SessionFilter

/**
 * 工作区本地 SharedPreferences 类型化封装（WI-005 / WI-006）。
 *
 * 设计目标：
 * - **保留现有 key 不变**：archived_sessions / deleted_sessions / deleted_workspaces / notif_permission_asked。
 *   已有 App 升级到新版本时不会丢数据。
 * - **新增扁平视图与会话过滤**：flatView / sessionFilter，键名加 `workspace_` 前缀避免与现有键冲突。
 * - 集中暴露 typed 读写 API：调用方不再手动 `getStringSet("...")`。
 *
 * Prefs 文件名：`"dsh_workspace"` —— 与 [dev.dsh.mobile.WorkspaceActivity] / [dev.dsh.mobile.SettingsActivity] 既有调用保持一致。
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

    // ===== 新增键：flatView / sessionFilter（WI-005 / WI-006） =====

    /** 单列表 vs 按工作区分组。 */
    var flatView: Boolean
        get() = prefs.getBoolean(KEY_FLAT_VIEW, false)
        set(value) {
            prefs.edit().putBoolean(KEY_FLAT_VIEW, value).apply()
        }

    /** 当前 chip 选中状态：序列化 / 反序列化走 [SessionFilter.name]。 */
    var sessionFilter: SessionFilter
        get() = SessionFilter.values().firstOrNull {
            it.name == prefs.getString(KEY_SESSION_FILTER, SessionFilter.ALL.name)
        } ?: SessionFilter.ALL
        set(value) {
            prefs.edit().putString(KEY_SESSION_FILTER, value.name).apply()
        }

    companion object {
        const val PREFS_NAME = "dsh_workspace"

        // 既有键：保留字面量，确保升级不丢数据
        const val KEY_ARCHIVED = "archived_sessions"
        const val KEY_DELETED = "deleted_sessions"
        const val KEY_DELETED_WORKSPACES = "deleted_workspaces"
        const val KEY_NOTIF_ASKED = "notif_permission_asked"

        // 新增键：加 `workspace_` 前缀避免与旧键混淆
        const val KEY_FLAT_VIEW = "workspace_flat_view"
        const val KEY_SESSION_FILTER = "workspace_session_filter"
    }
}