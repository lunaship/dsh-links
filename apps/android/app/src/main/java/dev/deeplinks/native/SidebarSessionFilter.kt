package dev.deeplinks.native

/**
 * 侧边栏会话过滤（从 WorkspaceScreen 抽出，COM-001 拆解）。
 *
 * 规则（原本内联在 composable 里、无法单测）：
 * 1. 排除归档 / 已删除 / 子智能体会话；
 * 2. 排除超过 24h 的空白会话（陈旧占位）；
 * 3. 无查询 → 原样返回；有查询 → 只保留标题或路径命中，或服务端检索命中的会话。
 *
 * 纯函数、无 Compose 依赖。
 */
internal fun filterSidebarSessions(
    sessions: List<MobileSession>,
    archivedIds: Set<String>,
    deletedIds: Set<String>,
    searchNeedle: String,
    searchResultIds: List<String>,
    nowMillis: Long,
): List<MobileSession> {
    val staleCutoff = nowMillis - 24 * 3600_000L
    val candidates = sessions.filter {
        it.sessionId !in archivedIds && it.sessionId !in deletedIds &&
            it.origin != "subagent" &&
            !(it.blank && it.updatedAt < staleCutoff)
    }
    if (searchNeedle.isEmpty()) return candidates
    val matched = (
        candidates.filter {
            it.title.contains(searchNeedle, ignoreCase = true) ||
                (it.cwd?.contains(searchNeedle, ignoreCase = true) == true)
        }.map { it.sessionId } + searchResultIds
    ).toSet()
    return candidates.filter { it.sessionId in matched }
}
/**
 * 当前会话的活跃子智能体数量（从 WorkspaceScreen 抽出）。
 * 优先用会话自带的计数；为 0/缺失时回退到「按 parentSessionId 统计子会话」。
 */
internal fun resolveActiveSubagentCount(
    sessions: List<MobileSession>,
    currentSessionId: String?,
    currentSubagentCount: Int?,
): Int {
    val sid = currentSessionId ?: return 0
    return currentSubagentCount?.takeIf { it > 0 }
        ?: sessions.count { it.origin == "subagent" && it.parentSessionId == sid }
}
