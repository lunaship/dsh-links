package dev.dsh.mobile.native.util

import dev.dsh.mobile.native.MobileSession

/**
 * 会话过滤状态（WI-005 / WI-006 抽出）。
 * 本地纯客户端判定，零后端依赖；用于侧边栏 3 chip（全部 / 运行中 / 已停止）。
 *
 * 顺序敏感：[entries] / [name] 在 UI 直接用 `enum.name` 显示前请保持稳定。
 */
enum class SessionFilter { ALL, RUNNING, STOPPED }

/**
 * 把会话映射到过滤 chip 状态：
 * - `running = true` → [SessionFilter.RUNNING]；
 * - `running = false && blank = false` → [SessionFilter.STOPPED]（覆盖完成 + 失败 + 人工停止）；
 * - `blank = true` → 归 [SessionFilter.ALL]（空白会话 "已停止" 语义上不算）。
 */
fun classifySession(s: MobileSession): SessionFilter = when {
    s.running -> SessionFilter.RUNNING
    !s.blank -> SessionFilter.STOPPED
    else -> SessionFilter.ALL
}

/** 过滤一组会话：未选中 ALL 时按 [classifySession] 命中保留。 */
fun filterSessions(
    sessions: Collection<MobileSession>,
    filter: SessionFilter,
): List<MobileSession> = when (filter) {
    SessionFilter.ALL -> sessions.toList()
    else -> sessions.filter { classifySession(it) == filter }
}

/** 把一组会话按 [SessionFilter] 分桶（用于 chip 计数）。 */
fun countSessionsByFilter(sessions: Collection<MobileSession>): Map<SessionFilter, Int> =
    SessionFilter.values().associateWith { f ->
        if (f == SessionFilter.ALL) sessions.size
        else sessions.count { classifySession(it) == f }
    }