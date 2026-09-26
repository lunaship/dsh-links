package dev.deeplinks.native

import dev.deeplinks.native.util.SessionFilter
import dev.deeplinks.native.util.classifySession
import dev.deeplinks.native.util.normalizeWorkspacePath

internal data class SessionFilterData(
    val sessions: List<MobileSession>,
    val counts: Map<SessionFilter, Int>,
)

/** Installs a committed workspace.create echo without waiting for the next catalog poll. */
internal fun upsertCreatedWorkspace(
    workspaces: List<MobileWorkspace>,
    workspace: MobileWorkspace,
): List<MobileWorkspace> {
    val normalized = workspace.copy(path = normalizeWorkspacePath(workspace.path))
    val index = workspaces.indexOfFirst { current ->
        (normalized.workspaceId.isNotBlank() && current.workspaceId == normalized.workspaceId) ||
            normalizeWorkspacePath(current.path) == normalized.path
    }
    if (index == -1) return listOf(normalized) + workspaces
    return workspaces.mapIndexed { position, current ->
        if (position == index) normalized else current
    }
}

/**
 * 选择“按名称创建同级工作区”的服务端锚点。
 * 优先使用用户当前/最近选择的路径；路径失效时退回第一个仍有服务端 id 的注册工作区。
 */
internal fun workspaceCreationAnchor(
    workspaces: List<MobileWorkspace>,
    preferredPath: String?,
): MobileWorkspace? {
    val normalizedPreferred = preferredPath?.let(::normalizeWorkspacePath)?.takeIf { it.isNotBlank() }
    return workspaces.firstOrNull { workspace ->
        workspace.workspaceId.isNotBlank() &&
            normalizedPreferred != null &&
            normalizeWorkspacePath(workspace.path) == normalizedPreferred
    } ?: workspaces.firstOrNull { it.workspaceId.isNotBlank() }
}

/** Applies the Web archive snapshot while preserving a local restore override. */
internal fun reconcileArchivedSessionIds(
    serverArchivedIds: Set<String>,
    restoredSessionIds: Set<String>,
): Set<String> = serverArchivedIds - restoredSessionIds

/**
 * Reconciles the selected session against an authoritative session.list response.
 *
 * Web-side archive/delete keeps the underlying row in session.list but adds its id to
 * workspace.archivedSessionIds. [hiddenSessionIds] combines that server state with local
 * hiding. A draft new-session composer intentionally keeps a null selection so background
 * synchronization cannot pull the user back into an older session.
 */
internal fun reconciledSessionId(
    currentSessionId: String?,
    sessions: List<MobileSession>,
    hiddenSessionIds: Set<String> = emptySet(),
    preserveEmptySelection: Boolean,
    selectLatest: Boolean,
    preferredSessionId: String? = null,
): String? {
    if (preserveEmptySelection && currentSessionId == null) return null
    // 冷启动恢复：只有当前选中为空时，才让「上次打开的会话」参与竞争。
    val candidates = if (currentSessionId != null) {
        listOf(currentSessionId)
    } else {
        listOfNotNull(preferredSessionId)
    }
    for (candidate in candidates) {
        if (candidate !in hiddenSessionIds && sessions.any { it.sessionId == candidate }) {
            return candidate
        }
    }
    val staleBlankCutoff = System.currentTimeMillis() - 24 * 3600_000L
    val visibleSessions = sessions.filterNot {
        it.sessionId in hiddenSessionIds ||
            it.origin == "subagent" ||
            (it.blank && it.updatedAt < staleBlankCutoff)
    }
    return visibleSessions.firstOrNull { it.running }?.sessionId
        ?: if (selectLatest) {
            visibleSessions.maxByOrNull { it.updatedAt }?.sessionId
        } else {
            visibleSessions.firstOrNull()?.sessionId
        }
}

internal fun visibleHistorySessions(
    sessions: List<MobileSession>,
    archivedIds: Set<String>,
    deletedIds: Set<String>,
): List<MobileSession> = sessions.filter { session ->
    session.sessionId !in archivedIds &&
        session.sessionId !in deletedIds &&
        session.origin != "subagent"
}

internal fun buildSessionFilterData(
    sessions: List<MobileSession>,
    archivedIds: Set<String>,
    deletedIds: Set<String>,
    nowMillis: Long,
): SessionFilterData {
    val staleCutoff = nowMillis - 24 * 3600_000L
    val visible = ArrayList<MobileSession>(sessions.size)
    var running = 0
    var stopped = 0

    sessions.forEach { session ->
        if (
            session.sessionId in archivedIds ||
            session.sessionId in deletedIds ||
            session.origin == "subagent" ||
            (session.blank && session.updatedAt < staleCutoff)
        ) {
            return@forEach
        }

        visible += session
        when (classifySession(session)) {
            SessionFilter.RUNNING -> running += 1
            SessionFilter.STOPPED -> stopped += 1
            SessionFilter.ALL -> Unit
        }
    }

    return SessionFilterData(
        sessions = visible,
        counts = mapOf(
            SessionFilter.ALL to visible.size,
            SessionFilter.RUNNING to running,
            SessionFilter.STOPPED to stopped,
        ),
    )
}
