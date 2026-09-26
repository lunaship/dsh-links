package dev.deeplinks.native.util

/**
 * 用户工作区过滤：排除隐藏目录与系统/依赖目录（DSH 只显示用户项目工作区）。
 */
fun isUserWorkspace(cwd: String?): Boolean {
    if (cwd.isNullOrBlank()) return true
    val parts = cwd.split('/').filter { it.isNotBlank() }
    if (parts.isEmpty()) return false
    return parts.none { part ->
        part.startsWith(".") ||
            part == "node_modules" ||
            part == ".npm" ||
            part == ".bin" ||
            part == "Library" ||
            part == "Applications" ||
            part == "System" ||
            part == "tmp" ||
            part == "private"
    }
}

fun normalizeWorkspacePath(path: String): String = path.trimEnd('/')

data class WorkspaceAccount(
    val path: String,
    val sessionIds: Collection<String>,
)

/**
 * Web 分组只认 workspace.sessionIds，不认 session.cwd。
 * cwd 碰巧等于某个已注册路径（例如进程启动目录）时仍是未分组。
 */
fun workspaceGroupKey(
    sessionId: String,
    accounts: Collection<WorkspaceAccount>,
    deletedWorkspaces: Set<String> = emptySet(),
): String? {
    val deleted = deletedWorkspaces.map(::normalizeWorkspacePath).toSet()
    val owned = accounts.firstOrNull { sessionId in it.sessionIds } ?: return null
    val path = normalizeWorkspacePath(owned.path)
    if (path.isBlank() || path in deleted || !isUserWorkspace(path)) return null
    return path
}

/**
 * 可见工作区列表：
 * - 侧栏 / 选择器在已拉到服务端注册表后：[requireRegistered]=true，只显示注册路径（对齐 Web）。
 * - 注册表尚未就绪时：可合并会话 cwd 作为临时回退。
 * 均排除本地已删与非用户目录。
 */
fun visibleUserWorkspaces(
    sessionCwds: Collection<String?>,
    deletedWorkspaces: Set<String>,
    registeredPaths: Collection<String> = emptyList(),
    requireRegistered: Boolean = false,
): List<String> {
    val deleted = deletedWorkspaces.map(::normalizeWorkspacePath).toSet()
    val registered = registeredPaths
        .map(::normalizeWorkspacePath)
        .filter { it.isNotBlank() }
    val fromSessions = sessionCwds
        .mapNotNull { it?.let(::normalizeWorkspacePath) }
        .filter { it.isNotBlank() }
    val candidates = if (requireRegistered) registered else registered + fromSessions
    return candidates
        .filter { it.isNotBlank() && isUserWorkspace(it) && it !in deleted }
        .distinct()
        .sorted()
}

/**
 * 侧栏工作区可见性：默认展示全部已注册工作区，包括尚无会话的新工作区。
 * 搜索或会话状态筛选时，才按匹配路径或可见会话收窄。
 */
fun visibleSidebarWorkspaces(
    knownWorkspaces: List<String>,
    workspacesWithVisibleSessions: Set<String>,
    searchQuery: String,
    sessionFilterActive: Boolean,
): List<String> {
    val needle = searchQuery.trim()
    return when {
        needle.isNotEmpty() -> knownWorkspaces.filter { path ->
            path.contains(needle, ignoreCase = true) ||
                path.substringAfterLast('/').contains(needle, ignoreCase = true) ||
                path in workspacesWithVisibleSessions
        }
        sessionFilterActive -> knownWorkspaces.filter { it in workspacesWithVisibleSessions }
        else -> knownWorkspaces
    }
}

/**
 * 会话是否应出现在侧栏：cwd 为空视为未绑定；已删或（注册表就绪且未注册）则隐藏。
 * 这样 Web 取消注册后，残留 session.cwd 不会再撑出工作区文件夹。
 */
fun isSessionWorkspaceVisible(
    cwd: String?,
    deletedWorkspaces: Set<String>,
    registeredPaths: Collection<String>,
    registryReady: Boolean,
): Boolean {
    if (cwd.isNullOrBlank()) return true
    val path = normalizeWorkspacePath(cwd)
    val deleted = deletedWorkspaces.map(::normalizeWorkspacePath).toSet()
    if (path in deleted) return false
    if (!registryReady) return isUserWorkspace(path)
    val registered = registeredPaths.map(::normalizeWorkspacePath).filter { it.isNotBlank() }.toSet()
    return path in registered && isUserWorkspace(path)
}

/**
 * 服务端注册表刷新后收敛本地 soft-hide：
 * - 仍在注册表中的路径 → 取消隐藏（Web/App 重新添加）
 * - 已不在注册表中的路径 → 保留隐藏（含本机乐观删除）
 */
fun reconcileDeletedWorkspaces(
    nextRegistered: Collection<String>,
    deletedWorkspaces: Set<String>,
): Set<String> {
    val next = nextRegistered.map(::normalizeWorkspacePath).filter { it.isNotBlank() }.toSet()
    return deletedWorkspaces
        .map(::normalizeWorkspacePath)
        .filter { it.isNotBlank() && it !in next }
        .toSet()
}
