package dev.dsh.mobile.native

/**
 * UI 状态机：history 视图搜索状态。
 *  - Idle：未触发请求
 *  - Loading：等待服务端响应
 *  - Empty：响应为空（无匹配）
 *  - Error：网络/服务端异常（附 message，可恢复）
 *  - Results：正常返回（query/results 配套）
 *
 * 纯数据类，无 Compose 依赖，便于在 palette 测试环境下做一致性校验。
 */
sealed interface SearchUiState {
    data object Idle : SearchUiState
    data object Loading : SearchUiState
    data object Empty : SearchUiState
    data class Error(val message: String) : SearchUiState
    data class Results(val query: String, val count: Int) : SearchUiState
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
enum class PaletteGroup(val displayName: String) {
    COMMANDS("命令"),
    SKILLS("技能"),
    SUBAGENTS("子智能体"),
    ACTIONS("快捷操作"),
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
val DSH_PALETTE: List<PaletteEntry> = listOf(
    // ------- 服务端命令（直接发送） -------
    PaletteEntry(PaletteCommand.Completable("/permission read-only", "设置只读权限"), PaletteGroup.COMMANDS),
    PaletteEntry(PaletteCommand.Completable("/permission workspace-write", "设置工作区写入权限"), PaletteGroup.COMMANDS),
    PaletteEntry(PaletteCommand.Completable("/permission danger-full-access", "设置完全访问权限"), PaletteGroup.COMMANDS),
    PaletteEntry(PaletteCommand.Insertable("/plan", "生成执行计划"), PaletteGroup.COMMANDS),
    PaletteEntry(PaletteCommand.Insertable("/goal", "设定持续执行目标"), PaletteGroup.COMMANDS),
    PaletteEntry(PaletteCommand.Completable("/pause", "暂停当前任务"), PaletteGroup.COMMANDS),
    PaletteEntry(PaletteCommand.Completable("/resume", "继续当前任务"), PaletteGroup.COMMANDS),
    PaletteEntry(PaletteCommand.Completable("/clear", "清除上下文"), PaletteGroup.COMMANDS),

    // ------- 技能 / 子智能体（插入式） -------
    PaletteEntry(PaletteCommand.Completable("/skills", "查看可用技能"), PaletteGroup.SKILLS),
    PaletteEntry(PaletteCommand.Insertable("/subagent", "派生子智能体"), PaletteGroup.SUBAGENTS),

    // ------- 本地动作（直接生效，不走服务端） -------
    PaletteEntry(PaletteCommand.Local("/search", "搜索会话", LocalKind.SEARCH_SESSIONS), PaletteGroup.ACTIONS),
    PaletteEntry(PaletteCommand.Local("/new-session", "新建会话", LocalKind.NEW_SESSION), PaletteGroup.ACTIONS),
    PaletteEntry(PaletteCommand.Local("/settings", "打开设置", LocalKind.OPEN_SETTINGS), PaletteGroup.ACTIONS),
    PaletteEntry(PaletteCommand.Local("/chat", "切换到对话视图", LocalKind.SWITCH_CHAT), PaletteGroup.ACTIONS),
    PaletteEntry(PaletteCommand.Local("/trace", "切换到轨迹视图", LocalKind.SWITCH_TRACE), PaletteGroup.ACTIONS),
    PaletteEntry(PaletteCommand.Local("/model", "选择模型", LocalKind.OPEN_MODEL_PICKER), PaletteGroup.ACTIONS),
    PaletteEntry(PaletteCommand.Local("/permission", "选择权限", LocalKind.OPEN_PERMISSION_PICKER), PaletteGroup.ACTIONS),
)

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
