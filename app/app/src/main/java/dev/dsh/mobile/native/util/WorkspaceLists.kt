package dev.dsh.mobile.native.util

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

/**
 * 侧边栏与新建会话弹层共用的可见工作区列表：
 * 服务端已注册路径 ∪ 会话 cwd，排除本地已删与非用户目录。
 */
fun visibleUserWorkspaces(
    sessionCwds: Collection<String?>,
    deletedWorkspaces: Set<String>,
    registeredPaths: Collection<String> = emptyList(),
): List<String> = (registeredPaths + sessionCwds.mapNotNull { it })
    .map { it.trimEnd('/') }
    .filter { it.isNotBlank() && isUserWorkspace(it) && it !in deletedWorkspaces }
    .distinct()
    .sorted()
