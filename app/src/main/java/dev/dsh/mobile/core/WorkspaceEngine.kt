package dev.dsh.mobile.core

import android.content.Context

/**
 * 工作台引擎开关（Gate 3）：
 * - web：默认。WebActivity（DSH Web UI + App 注入移动层）
 * - native：故障回退入口。原生工作台（冻结区，观察期结束即归档）
 */
object WorkspaceEngine {
    private const val PREFS = "dsh_engine"
    private const val KEY = "workspace_engine"

    const val WEB = "web"
    const val NATIVE = "native"

    fun get(ctx: Context): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, WEB) ?: WEB

    fun set(ctx: Context, engine: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, engine).apply()
    }
}
