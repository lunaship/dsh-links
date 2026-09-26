package dev.deeplinks.native

import org.json.JSONObject

/**
 * 本轮改动文件（插件转发 DSH `workspaceChanges`，契约见 dsh-links `docs/MOBILE_SYNC_CONTRACT.md`）。
 *
 * 数据只在 Host 进程内随 Session 存活：Host 重启后旧轮次没有摘要，也就没有卡片——
 * 这是 DSH 的既定行为，App 不补、不报错。本文件只放模型、解析与纯推导，UI 在
 * [WorkspaceChangesCard] / [WorkspaceChangesPanel]。
 */

internal const val ROLE_WORKSPACE_CHANGES = "workspace_changes"

data class ChangedFile(
    /** 工作目录内为相对路径，否则为 Host 绝对路径（仅作标识，不拼 URL）。 */
    val path: String,
    /** 斜杠分隔的展示路径：相对 / `../` / `~` / 绝对。 */
    val display: String,
    val added: Int = 0,
    val deleted: Int = 0,
    val binary: Boolean = false,
    val oversized: Boolean = false,
) {
    val name: String get() = display.substringAfterLast('/').ifBlank { display }
    val directory: String get() = display.substringBeforeLast('/', "")
}

data class WorkspaceChangesSummary(
    /** `workspace/changes` 事件的 seq：摘要与对比路由的坐标。 */
    val seq: Long,
    val turn: Int,
    /** 完整改动文件数（可能大于 [files]，被上限裁掉的部分需走摘要路由取）。 */
    val total: Int,
    val added: Int,
    val deleted: Int,
    val files: List<ChangedFile>,
) {
    val complete: Boolean get() = files.size >= total
}

data class DiffHunk(
    val oldStart: Int,
    val oldLines: Int,
    val newStart: Int,
    val newLines: Int,
    /** 每行保留 `+` / `-` / 空格前缀。 */
    val lines: List<String>,
)

sealed interface WorkspaceFileDiff {
    val path: String
    val display: String

    data class Text(
        override val path: String,
        override val display: String,
        val before: Boolean,
        val after: Boolean,
        val coarse: Boolean,
        val hunks: List<DiffHunk>,
        val shownLines: Int? = null,
        val totalLines: Int? = null,
    ) : WorkspaceFileDiff {
        val truncated: Boolean get() = shownLines != null && totalLines != null && shownLines < totalLines
    }

    data class Binary(override val path: String, override val display: String) : WorkspaceFileDiff
    data class Oversized(override val path: String, override val display: String) : WorkspaceFileDiff
}

internal fun parseChangedFile(obj: JSONObject): ChangedFile? {
    val path = obj.optString("path").trim()
    if (path.isEmpty()) return null
    return ChangedFile(
        path = path,
        display = obj.optString("display").trim().ifEmpty { path },
        added = obj.optInt("added", 0).coerceAtLeast(0),
        deleted = obj.optInt("deleted", 0).coerceAtLeast(0),
        binary = obj.optBoolean("binary", false),
        oversized = obj.optBoolean("oversized", false),
    )
}

/** 解析摘要（历史内嵌的 `changes` 或摘要路由的根对象）；没有文件时返回 null（不出卡片）。 */
internal fun parseWorkspaceChanges(obj: JSONObject, seq: Long): WorkspaceChangesSummary? {
    val arr = obj.optJSONArray("files") ?: return null
    val files = (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let(::parseChangedFile) }
    if (files.isEmpty()) return null
    return WorkspaceChangesSummary(
        seq = seq,
        turn = obj.optInt("turn", 0),
        total = obj.optInt("total", files.size).coerceAtLeast(files.size),
        added = obj.optInt("added", files.sumOf { it.added }),
        deleted = obj.optInt("deleted", files.sumOf { it.deleted }),
        files = files,
    )
}

internal fun parseWorkspaceFileDiff(obj: JSONObject): WorkspaceFileDiff? {
    val path = obj.optString("path")
    val display = obj.optString("display").ifEmpty { path }
    return when (obj.optString("kind")) {
        "binary" -> WorkspaceFileDiff.Binary(path, display)
        "oversized" -> WorkspaceFileDiff.Oversized(path, display)
        "text" -> {
            val arr = obj.optJSONArray("hunks") ?: org.json.JSONArray()
            val hunks = (0 until arr.length()).mapNotNull { i ->
                val h = arr.optJSONObject(i) ?: return@mapNotNull null
                val lines = h.optJSONArray("lines") ?: org.json.JSONArray()
                DiffHunk(
                    oldStart = h.optInt("oldStart"),
                    oldLines = h.optInt("oldLines"),
                    newStart = h.optInt("newStart"),
                    newLines = h.optInt("newLines"),
                    lines = (0 until lines.length()).map { lines.optString(it) },
                )
            }
            val truncated = obj.optJSONObject("truncated")
            WorkspaceFileDiff.Text(
                path = path,
                display = display,
                before = obj.optBoolean("before", true),
                after = obj.optBoolean("after", true),
                coarse = obj.optBoolean("coarse", false),
                hunks = hunks,
                shownLines = truncated?.optInt("shownLines"),
                totalLines = truncated?.optInt("totalLines"),
            )
        }
        else -> null
    }
}

/** 从 SSE 原始 `workspace/changes` 事件构造的卡片占位（摘要随后由摘要路由补上）。 */
internal fun workspaceChangesMessage(summary: WorkspaceChangesSummary, time: Long, entrance: Boolean = false) =
    MobileMessage(
        id = "changes-${summary.seq}",
        role = ROLE_WORKSPACE_CHANGES,
        text = "",
        time = time,
        type = ROLE_WORKSPACE_CHANGES,
        seq = summary.seq,
        turn = summary.turn,
        changes = summary,
        entrance = entrance,
    )

/**
 * 同一轮的后一条宣告取代前一条（DSH：`The latest event for one turn replaces earlier ones`）。
 * 跨分页 / SSE 与历史合并后按 turn 只保留 seq 最大的一张卡；不含改动卡的列表原样返回。
 */
fun coalesceWorkspaceChanges(messages: List<MobileMessage>): List<MobileMessage> {
    if (messages.none { it.role == ROLE_WORKSPACE_CHANGES }) return messages
    val latestSeqByTurn = HashMap<Int, Long>()
    for (m in messages) {
        if (m.role != ROLE_WORKSPACE_CHANGES) continue
        val turn = m.turn ?: continue
        val seq = m.changes?.seq ?: m.seq
        if (seq >= (latestSeqByTurn[turn] ?: Long.MIN_VALUE)) latestSeqByTurn[turn] = seq
    }
    return messages.filter { m ->
        if (m.role != ROLE_WORKSPACE_CHANGES) return@filter true
        val turn = m.turn ?: return@filter true
        (m.changes?.seq ?: m.seq) == latestSeqByTurn[turn]
    }
}

/** 会话内全部改动卡片摘要，按轮次从新到旧（审查面的轮次切换顺序）。 */
fun sessionChangeSummaries(messages: List<MobileMessage>): List<WorkspaceChangesSummary> =
    coalesceWorkspaceChanges(messages)
        .mapNotNull { if (it.role == ROLE_WORKSPACE_CHANGES) it.changes else null }
        .sortedByDescending { it.seq }

/** 渲染行：hunk 头 / 上下文 / 新增 / 删除，带双列行号。 */
data class DiffRow(val kind: Kind, val oldNo: Int?, val newNo: Int?, val text: String) {
    enum class Kind { HUNK, CONTEXT, ADD, DELETE }
}

fun diffRows(hunks: List<DiffHunk>): List<DiffRow> = buildList {
    for (hunk in hunks) {
        add(DiffRow(DiffRow.Kind.HUNK, null, null, "@@ -${hunk.oldStart},${hunk.oldLines} +${hunk.newStart},${hunk.newLines} @@"))
        var oldNo = hunk.oldStart
        var newNo = hunk.newStart
        for (line in hunk.lines) {
            val body = if (line.isEmpty()) "" else line.substring(1)
            when (line.firstOrNull()) {
                '+' -> add(DiffRow(DiffRow.Kind.ADD, null, newNo++, body))
                '-' -> add(DiffRow(DiffRow.Kind.DELETE, oldNo++, null, body))
                else -> add(DiffRow(DiffRow.Kind.CONTEXT, oldNo++, newNo++, body))
            }
        }
    }
}

/** 对比说明行：新建 / 删除 / 两侧相同 / 逐行超时 / 截断。 */
enum class DiffNote { CREATED, DELETED, UNCHANGED, COARSE, TRUNCATED }

fun diffNotes(diff: WorkspaceFileDiff.Text): List<DiffNote> = buildList {
    if (!diff.before && diff.after) add(DiffNote.CREATED)
    if (diff.before && !diff.after) add(DiffNote.DELETED)
    if (diff.hunks.isEmpty()) add(DiffNote.UNCHANGED)
    if (diff.coarse) add(DiffNote.COARSE)
    if (diff.truncated) add(DiffNote.TRUNCATED)
}

/** 卡片里默认展开的文件行数（DSH Web：折叠前 4 行）。 */
internal const val CHANGES_CARD_VISIBLE_FILES = 4

/**
 * 审查面宽度（dp）。窄屏（<600dp）全屏——对齐 DSH 桌面「窗口低于 768px 时打开右栏自动全屏」
 * 的手机形态；宽屏首开 45% 容器宽，至少 360dp，且给主区留足 400dp 可读宽度。
 */
fun changesPanelWidthDp(containerDp: Float): Float {
    if (containerDp < 600f) return containerDp
    val preferred = (containerDp * 0.45f).coerceAtLeast(360f)
    return preferred.coerceAtMost(containerDp - 400f).coerceAtLeast(360f).coerceAtMost(containerDp)
}

/** 松手判定：拖过 35% 或向打开方向快速甩出即打开，反向快速甩回即关闭。 */
fun settleChangesPanelOpen(fraction: Float, velocityTowardOpenPxPerSec: Float, flingThresholdPxPerSec: Float): Boolean = when {
    velocityTowardOpenPxPerSec > flingThresholdPxPerSec -> true
    velocityTowardOpenPxPerSec < -flingThresholdPxPerSec -> false
    else -> fraction > 0.35f
}
