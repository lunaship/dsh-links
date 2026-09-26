package dev.deeplinks.native

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.deeplinks.core.Dsh
import dev.deeplinks.core.L
import dev.deeplinks.native.util.SessionFilter
import dev.deeplinks.native.util.SessionListKind
import dev.deeplinks.native.util.WorkspaceAccount
import dev.deeplinks.native.util.classifySession
import dev.deeplinks.native.util.sessionListKind
import dev.deeplinks.native.util.sessionShowsRefreshBanner
import dev.deeplinks.native.util.visibleSidebarWorkspaces
import dev.deeplinks.native.util.visibleUserWorkspaces
import dev.deeplinks.native.util.workspaceGroupKey
import dev.deeplinks.core.DshType

/** 侧栏回调集合（对齐 [ChatFeedActions] 模式：状态由参数注入，动作由此承载）。 */
internal class WorkspaceSidebarActions(
    val onOpenDevice: () -> Unit,
    val onNewSession: () -> Unit,
    val onSelectSession: (String) -> Unit,
    val onRenameSession: (MobileSession) -> Unit,
    val onArchiveSession: (MobileSession) -> Unit,
    val onDeleteSession: (MobileSession) -> Unit,
    val onForkSession: (String) -> Unit,
    val onCreateSessionIn: (String?) -> Unit,
    val onDeleteWorkspace: (String) -> Unit,
    val onToggleWorkspaceExpanded: (String) -> Unit,
    val onExpandGroup: (String) -> Unit,
    val onToggleSearch: () -> Unit,
    val onSearchQueryChange: (String) -> Unit,
    val onClearSearch: () -> Unit,
    val onRetrySearch: () -> Unit,
    val onRetrySessions: () -> Unit,
    val onOpenFilterSheet: () -> Unit,
    val onAddWorkspace: () -> Unit,
    val onOpenSettings: () -> Unit,
)

@Composable
internal fun WorkspaceSidebar(
    sessions: List<MobileSession>,
    archivedIds: Set<String>,
    deletedIds: Set<String>,
    currentSessionId: String?,
    searchQuery: String,
    searchState: SearchUiState,
    searchResults: List<MobileSearchResult>,
    sidebarSearchOpen: Boolean,
    sessionFilter: SessionFilter,
    workspaceAccounts: List<WorkspaceAccount>,
    deletedWorkspaces: Set<String>,
    workspaceRegistry: List<String>,
    workspaceRegistryReady: Boolean,
    sessionsInitialLoad: Boolean,
    sessionsLoadError: String?,
    expandedWorkspaces: Set<String>,
    expandedGroups: Set<String>,
    hostName: String,
    collapsed: Boolean = false,
    goalSummaries: Map<String, String> = emptyMap(),
    actions: WorkspaceSidebarActions,
) {
    if (collapsed) {
        WorkspaceSidebarCollapsed(actions = actions)
        return
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(top = 4.dp)
    ) {
        // 头部：当前主机行（整行可点 → 切换设备）
        SidebarHostRow(hostName = hostName, onOpenDevice = { actions.onOpenDevice() })

        Spacer(Modifier.height(2.dp))

        // 新会话（列表行，品牌 + 图标）
        SidebarNewSessionRow(onClick = { actions.onNewSession() })

        Spacer(Modifier.height(8.dp))

        SidebarSectionHeader(
            title = L.workspace,
            searchActive = sidebarSearchOpen || searchQuery.isNotBlank(),
            filterActive = sessionFilter != SessionFilter.ALL,
            onToggleSearch = { actions.onToggleSearch() },
            onOpenFilterSheet = { actions.onOpenFilterSheet() },
            onAddWorkspace = { actions.onAddWorkspace() },
        )

        AnimatedVisibility(
            visible = sidebarSearchOpen,
            enter = fadeIn(tween(motionDuration(150))) + expandVertically(tween(motionDuration(180), easing = FastOutSlowInEasing)),
            exit = fadeOut(tween(motionDuration(100))) + shrinkVertically(tween(motionDuration(150), easing = FastOutSlowInEasing)),
        ) {
            Box(Modifier.padding(bottom = 4.dp)) {
                SidebarSearchField(
                    value = searchQuery,
                    onValueChange = actions.onSearchQueryChange,
                    onClear = { actions.onClearSearch() },
                    loading = searchState is SearchUiState.Loading,
                )
            }
        }

        Spacer(Modifier.height(2.dp))

        val searchNeedle = searchQuery.trim()
        val visibleCandidates = remember(sessions, archivedIds, deletedIds, searchNeedle, searchResults) {
            filterSidebarSessions(
                sessions = sessions,
                archivedIds = archivedIds,
                deletedIds = deletedIds,
                searchNeedle = searchNeedle,
                searchResultIds = searchResults.map { it.sessionId },
                nowMillis = System.currentTimeMillis(),
            )
        }
        // 注册表就绪后以已注册工作区为准；无会话的新工作区也必须可见。
        val knownWorkspaces = remember(
            visibleCandidates,
            deletedWorkspaces,
            workspaceRegistry,
            workspaceRegistryReady,
        ) {
            visibleUserWorkspaces(
                sessionCwds = visibleCandidates.map { it.cwd },
                deletedWorkspaces = deletedWorkspaces,
                registeredPaths = workspaceRegistry,
                requireRegistered = workspaceRegistryReady,
            )
        }
        val sidebarGrouped = remember(visibleCandidates, deletedWorkspaces, sessionFilter, workspaceAccounts) {
            visibleCandidates
                .filter { sessionFilter == SessionFilter.ALL || classifySession(it) == sessionFilter }
                .groupBy { workspaceGroupKey(it.sessionId, workspaceAccounts, deletedWorkspaces) }
        }
        val workspacesToShow = remember(knownWorkspaces, sidebarGrouped, searchNeedle, sessionFilter) {
            visibleSidebarWorkspaces(
                knownWorkspaces = knownWorkspaces,
                workspacesWithVisibleSessions = sidebarGrouped
                    .filterValues { it.isNotEmpty() }
                    .keys
                    .filterNotNull()
                    .toSet(),
                searchQuery = searchNeedle,
                sessionFilterActive = sessionFilter != SessionFilter.ALL,
            )
        }

        // 会话列表
        val sessionKind = sessionListKind(
            hasSessions = sessions.isNotEmpty(),
            initialLoad = sessionsInitialLoad,
            hasError = sessionsLoadError != null,
        )
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            if (searchNeedle.isNotEmpty() && searchShowsDegradedHint(searchState)) {
                item(key = "sidebar-search-degraded") {
                    Box(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                        SearchStatusBanner(
                            message = L.fullTextSearchUnavailable,
                            onRetry = { actions.onRetrySearch() },
                        )
                    }
                }
            }
            if (searchNeedle.isNotEmpty() && visibleCandidates.isEmpty() && workspacesToShow.isEmpty()) {
                item(key = "sidebar-search-empty") {
                    val searchError = searchState as? SearchUiState.Error
                    if (searchError != null) {
                        ChatHistoryError(
                            title = L.searchFailed,
                            message = searchError.message,
                            onRetry = { actions.onRetrySearch() },
                        )
                    } else {
                        Text(
                            if (searchState is SearchUiState.Loading) L.searching else L.noMatchingSessions,
                            color = Dsh.labelTertiary,
                            style = DshType.t13,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 20.dp)
                        )
                    }
                }
            } else if (sessionKind == SessionListKind.Loading) {
                item(key = "sidebar-sessions-loading") {
                    Text(
                        L.loading,
                        color = Dsh.labelTertiary,
                        style = DshType.t13,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 20.dp)
                    )
                }
            } else if (sessionKind == SessionListKind.Error) {
                item(key = "sidebar-sessions-error") {
                    ChatHistoryError(
                        title = L.loadSessionListFailed,
                        message = sessionsLoadError ?: L.loadSessionListFailed,
                        onRetry = { actions.onRetrySessions() },
                    )
                }
            } else {
                if (sessionShowsRefreshBanner(sessions.isNotEmpty(), sessionsLoadError != null)) {
                    item(key = "sidebar-sessions-refresh") {
                        LoadOlderRow(
                            loading = false,
                            failed = true,
                            failedMessage = sessionsLoadError,
                            onClick = { actions.onRetrySessions() },
                        )
                    }
                }
                // 按 workspace.sessionIds 分组（对齐 Web）；cwd 碰巧等于注册路径仍是未分组
                val grouped = sidebarGrouped
                grouped[null].orEmpty().filter { !it.blank }.forEach { s ->
                    item(key = "ungrouped-${s.sessionId}") {
                        AnimatedVisibility(
                            visible = true,
                            enter = fadeIn(animationSpec = tween(motionDuration(180))) + slideInHorizontally(animationSpec = tween(motionDuration(220), easing = FastOutSlowInEasing), initialOffsetX = { -it }),
                            modifier = Modifier.animateItem()
                        ) {
                            SessionRowItem(
                                session = s,
                                isSelected = s.sessionId == currentSessionId,
                                onClick = { actions.onSelectSession(s.sessionId) },
                                onRename = { actions.onRenameSession(s) },
                                onArchive = { actions.onArchiveSession(s) },
                                onDelete = { actions.onDeleteSession(s) },
                                onFork = { actions.onForkSession(s.sessionId) },
                                goalSummary = goalSummaries[s.sessionId],
                            )
                        }
                    }
                }
                workspacesToShow.forEach { cwd ->
                    val groupSessions = grouped[cwd].orEmpty()
                    val collapsed = cwd !in expandedWorkspaces
                    item(key = "ws-$cwd") {
                        SidebarWorkspaceRow(
                            name = cwd.substringAfterLast('/'),
                            collapsed = collapsed,
                            sessionCount = groupSessions.size,
                            onToggle = { actions.onToggleWorkspaceExpanded(cwd) },
                            onCreateSession = { actions.onCreateSessionIn(cwd) },
                            onDeleteWorkspace = { actions.onDeleteWorkspace(cwd) },
                        )
                    }
                    // 组内默认预览 5 条 + 「显示全部」占位行（对齐 Web UI SessionListPage）
                    val showMore = groupSessions.size > 5 && cwd !in expandedGroups
                    val preview = if (showMore) groupSessions.take(5) else groupSessions
                    val rows: List<MobileSession?> = if (showMore) preview + null else preview
                    item(key = "ws-body-$cwd") {
                        AnimatedVisibility(
                            visible = !collapsed,
                            enter = fadeIn(animationSpec = tween(motionDuration(150))) + expandVertically(animationSpec = tween(motionDuration(220), easing = FastOutSlowInEasing), expandFrom = Alignment.Top),
                            exit = fadeOut(animationSpec = tween(motionDuration(110))) + shrinkVertically(animationSpec = tween(motionDuration(150), easing = FastOutSlowInEasing), shrinkTowards = Alignment.Top)
                        ) {
                            Column {
                                rows.forEach { s ->
                                    if (s == null) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .heightIn(min = 48.dp)
                                                .padding(
                                                    start = DrawerEdgePadding + 12.dp,
                                                    end = DrawerEdgePadding,
                                                )
                                                .clip(RoundedCornerShape(DshRadius.md))
                                                .clickable { actions.onExpandGroup(cwd) }
                                                .padding(horizontal = DrawerInnerPadding),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                L.expandMoreSessions.format(groupSessions.size - 5),
                                                color = Dsh.labelTertiary,
                                                style = DshType.captionRelaxed,
                                                lineHeight = 18.sp
                                            )
                                        }
                                    } else {
                                        SessionRowItem(
                                            session = s,
                                            isSelected = s.sessionId == currentSessionId,
                                            indent = 12.dp,
                                            onClick = { actions.onSelectSession(s.sessionId) },
                                            onRename = { actions.onRenameSession(s) },
                                            onArchive = { actions.onArchiveSession(s) },
                                            onDelete = { actions.onDeleteSession(s) },
                                            onFork = { actions.onForkSession(s.sessionId) },
                                            goalSummary = goalSummaries[s.sessionId],
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // 底部：设置与主题切换入口
        val context = androidx.compose.ui.platform.LocalContext.current
        val isDarkTheme = Dsh.isDark
        SidebarFooter(
            isDarkTheme = isDarkTheme,
            onOpenSettings = { actions.onOpenSettings() },
            onToggleTheme = { dev.deeplinks.core.ThemeManager.toggleTheme(context, isDarkTheme) },
        )
    }
}

