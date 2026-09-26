package dev.deeplinks.native

import dev.deeplinks.core.L

/**
 * UI 状态机：侧边栏搜索状态。
 *  - Idle：未触发请求
 *  - Loading：等待服务端响应
 *  - Empty：响应为空（无匹配）；degraded 表示全文检索不可用、只按标题匹配
 *  - Error：网络/服务端异常（附 message，可恢复）
 *  - Results：正常返回（query/results 配套）；degraded 时结果仍展示，横幅说明降级
 *
 * 纯数据类，无 Compose 依赖，便于在 palette 测试环境下做一致性校验。
 */
sealed interface SearchUiState {
    data object Idle : SearchUiState
    data object Loading : SearchUiState
    data class Empty(val degraded: Boolean = false) : SearchUiState
    data class Error(val message: String) : SearchUiState
    data class Results(val query: String, val count: Int, val degraded: Boolean = false) : SearchUiState
}

/** 全文检索降级时不能只显示「无匹配」，否则看起来像搜过了其实没搜全。 */
fun searchShowsDegradedHint(state: SearchUiState): Boolean = when (state) {
    is SearchUiState.Empty -> state.degraded
    is SearchUiState.Results -> state.degraded
    else -> false
}

/**
 * Typed command-palette model (DSH CommandPalette).
 *
 * Replaces the previous static `List<Pair<String, List<Pair<String, String>>>>`
 * with categories and a sealed palette item hierarchy. Filtering and grouping
 * are exposed as pure functions so they can be covered by JVM unit tests.
 *
 * Items fall into two broad kinds:
 *  - **Completable**: the picker should *submit directly* to the session and
 *    clear the composer (only valid when the text is a complete command).
 *  - **Insertable**: the picker should *insert* the template into the composer
 *    with focus, leaving the prompt to the user (e.g. /plan, /goal, /subagent).
 *  - **Local**: not a server command at all — these are app-level
 *    actions (search sessions, switch model, open settings) that intercept
 *    locally and never reach the server.
 */
sealed interface PaletteCommand {
    val id: String
    /** The slash text the user typed / matched against. */
    val trigger: String
    /** Human description shown in the picker. */
    val description: String

    /** Submit the trigger verbatim to the server. Pick → submit. */
    data class Completable(
        override val trigger: String,
        override val description: String,
    ) : PaletteCommand {
        override val id: String get() = "complete:$trigger"
    }

    /**
     * Insert the trigger into the composer with focus. Used when the command
     * needs the user to provide additional arguments (e.g. /plan <task>).
     */
    data class Insertable(
        override val trigger: String,
        override val description: String,
    ) : PaletteCommand {
        override val id: String get() = "insert:$trigger"
    }

    /**
     * Local-only action. When picked, the picker fires an [LocalKind]
     * action — the suggestion never reaches the network layer.
     *
     * Triggers stay slash-prefixed (e.g. `/search`, `/new-session`,
     * `/settings`) so the model and the trigger surface stay uniform, but
     * the action dispatcher recognizes them and short-circuits locally.
     */
    data class Local(
        override val trigger: String,
        override val description: String,
        val kind: LocalKind,
    ) : PaletteCommand {
        override val id: String get() = "local:${kind.name}:$trigger"
    }
}

/** Local (non-server) actions the palette can dispatch. */
enum class LocalKind {
    /** Open session search in the sidebar. */
    SEARCH_SESSIONS,
    /** Create a brand-new session. */
    NEW_SESSION,
    /** Open the settings activity (App-local). */
    OPEN_SETTINGS,
    /** Switch to the chat view tab. */
    SWITCH_CHAT,
    /** Switch to the trajectory (trace) view tab. */
    SWITCH_TRACE,
    /** Open the model picker. */
    OPEN_MODEL_PICKER,
    /** Open the permission picker. */
    OPEN_PERMISSION_PICKER,
}

/** Top-level group in the picker UI; mirrors the DSH commands/skills/subagents buckets. */
enum class PaletteGroup {
    COMMANDS,
    SKILLS,
    SUBAGENTS,
    ACTIONS,
    ;

    val displayName: String
        get() = when (this) {
            COMMANDS -> L.paletteCommands
            SKILLS -> L.paletteSkills
            SUBAGENTS -> L.paletteSubagents
            ACTIONS -> L.paletteActions
        }
}

/**
 * A grouped entry in the rendered picker — a [PaletteCommand] belongs to a
 * [PaletteGroup]. Order within a group is preserved by the source list.
 */
data class PaletteEntry(
    val command: PaletteCommand,
    val group: PaletteGroup,
)

/**
 * The static palette source of truth. Order within each group determines
 * display order; the picker does not sort.
 *
 * Local actions (session search / new session / settings / chat / trace /
 * model / permission) are kept here so the slash trigger surface stays
 * uniform — the dispatcher only forwards text to the server for non-Local
 * entries.
 */
val DSH_PALETTE: List<PaletteEntry>
    get() = listOf(
    // ------- 服务端命令（直接发送） -------
    // 注意：/permission 三条不可逆预设全部走本地 picker（见下方 ACTIONS），
    // 不能让它们以 Completable 形式绕过 PermissionPickerSheet 的二次确认。
    PaletteEntry(PaletteCommand.Insertable("/plan", L.palettePlan), PaletteGroup.COMMANDS),
    PaletteEntry(PaletteCommand.Insertable("/goal", L.paletteGoal), PaletteGroup.COMMANDS),
    PaletteEntry(PaletteCommand.Insertable("/feedback", L.paletteFeedback), PaletteGroup.COMMANDS),
    PaletteEntry(PaletteCommand.Completable("/pause", L.palettePause), PaletteGroup.COMMANDS),
    PaletteEntry(PaletteCommand.Completable("/resume", L.paletteResume), PaletteGroup.COMMANDS),
    PaletteEntry(PaletteCommand.Completable("/clear", L.paletteClear), PaletteGroup.COMMANDS),

    // ------- 技能 / 子智能体（插入式） -------
    PaletteEntry(PaletteCommand.Completable("/skills", L.paletteSkillsList), PaletteGroup.SKILLS),
    PaletteEntry(PaletteCommand.Insertable("/subagent", L.paletteSubagent), PaletteGroup.SUBAGENTS),

    // ------- 本地动作（直接生效，不走服务端） -------
    PaletteEntry(PaletteCommand.Local("/search", L.paletteSearchSessions, LocalKind.SEARCH_SESSIONS), PaletteGroup.ACTIONS),
    PaletteEntry(PaletteCommand.Local("/new-session", L.paletteNewSession, LocalKind.NEW_SESSION), PaletteGroup.ACTIONS),
    PaletteEntry(PaletteCommand.Local("/settings", L.paletteOpenSettings, LocalKind.OPEN_SETTINGS), PaletteGroup.ACTIONS),
    PaletteEntry(PaletteCommand.Local("/chat", L.paletteSwitchChat, LocalKind.SWITCH_CHAT), PaletteGroup.ACTIONS),
    PaletteEntry(PaletteCommand.Local("/trace", L.paletteSwitchTrace, LocalKind.SWITCH_TRACE), PaletteGroup.ACTIONS),
    PaletteEntry(PaletteCommand.Local("/model", L.paletteSelectModel, LocalKind.OPEN_MODEL_PICKER), PaletteGroup.ACTIONS),
    // 三条权限预设只做发现入口：选中后打开 PermissionPickerSheet，由它完成写入与二次确认。
    PaletteEntry(PaletteCommand.Local("/permission read-only", L.palettePermissionReadOnly, LocalKind.OPEN_PERMISSION_PICKER), PaletteGroup.ACTIONS),
    PaletteEntry(PaletteCommand.Local("/permission workspace-write", L.palettePermissionWorkspaceWrite, LocalKind.OPEN_PERMISSION_PICKER), PaletteGroup.ACTIONS),
    PaletteEntry(PaletteCommand.Local("/permission danger-full-access", L.palettePermissionFullAccess, LocalKind.OPEN_PERMISSION_PICKER), PaletteGroup.ACTIONS),
)

/**
 * 最高权限预设的 slash 形式。
 *
 * 它是不可逆的权限升级，任何入口（候选、外部分享文本、hero 模式前缀）都不能直接提交：
 * 必须经过 [dev.deeplinks.native.PermissionPickerSheet] 那条带二次确认的写入路径。
 */
const val DANGER_FULL_ACCESS_COMMAND = "/permission danger-full-access"

/** 视化比较：去首尾空白、小写、压缩连续空白。 */
internal fun normalizeSlashCommand(text: String): String =
    text.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")

/**
 * 即将提交的文本是否就是高权限升级命令。
 *
 * 用在发送路径上做兵底：即使前面的入口（候选列表、外部分享）都被绕过，
 * 真正发出去之前仍会弹确认。
 */
fun isDangerPermissionCommand(text: String): Boolean =
    normalizeSlashCommand(text) == DANGER_FULL_ACCESS_COMMAND

/**
 * Filter [source] by [query] and return the surviving entries grouped by
 * [PaletteGroup], preserving the source order *within* each group and the
 * group display order from [PaletteGroup].
 *
 * Rules (matching the original DSH semantics):
 *  - Empty / only-`/` query returns the full palette grouped normally.
 *  - A query longer than one character filters within each group by
 *    case-insensitive substring match on the trigger text (the leading
 *    `/` of the query is dropped before matching).
 *  - Empty groups are dropped.
 *
 * Exposed as a pure function so the behavior can be exercised by JVM tests
 * without an Android runtime.
 */
fun filterPalette(
    source: List<PaletteEntry>,
    query: String,
): List<Pair<PaletteGroup, List<PaletteEntry>>> {
    // guard against empty query — substring(1) on "" throws StringIndexOutOfBoundsException
    val needle = if (query.length <= 1) "" else query.substring(1) // drop leading `/`
    val perGroup: MutableMap<PaletteGroup, MutableList<PaletteEntry>> = LinkedHashMap()
    for (entry in source) {
        val matches = if (query.length <= 1) {
            true
        } else {
            entry.command.trigger.contains(needle, ignoreCase = true)
        }
        if (matches) {
            perGroup.getOrPut(entry.group) { mutableListOf() }.add(entry)
        }
    }
    // Emit in enum order so the order is stable even if the source list is reordered.
    val ordered = PaletteGroup.values().toList()
    return ordered.mapNotNull { g ->
        val items = perGroup[g]
        if (items.isNullOrEmpty()) null else g to items.toList()
    }
}

/** 把 `/feedback` 填进输入框，已有该命令时不重复插入。 */
fun insertFeedbackCommand(current: String): String {
    val leading = current.takeWhile { it.isWhitespace() }
    val rest = current.substring(leading.length)
    if (rest.startsWith("/feedback")) return current
    if (rest.isEmpty()) return "${leading}/feedback "
    return "${leading}/feedback $rest"
}
