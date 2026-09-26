package dev.deeplinks.native

import dev.deeplinks.core.LocaleManager

/**
 * 改动文件卡片 / 审查面的中英文案。
 *
 * 与 [dev.deeplinks.core.L] 同源（跟随 [LocaleManager.language]），单独成文件是因为
 * AppLocale.kt 受行数预算约束（见 code-hygiene-baseline.txt）。文案措辞对齐 DSH Web
 * `dsh-client-ui-deliverables` 的 `diff.*` 词条。
 */
internal object ChangesL {
    private val zh = mapOf(
        "changes" to "改动",
        "viewChanges" to "查看改动",
        "editedFile" to "已编辑 %s",
        "editedFiles" to "已编辑 %d 个文件",
        "moreFiles" to "还有 %d 个文件",
        "binary" to "二进制文件，无法显示改动",
        "oversized" to "文件过大，无法显示改动",
        "created" to "本轮新建的文件",
        "deleted" to "本轮删除的文件",
        "unchanged" to "两侧内容相同",
        "coarse" to "逐行对比超时，按整个文件替换显示",
        "truncated" to "只显示前 %d 行（共 %d 行）",
        "turn" to "第 %d 轮",
        "olderTurn" to "上一轮改动",
        "newerTurn" to "下一轮改动",
        "previousFile" to "上一个文件",
        "nextFile" to "下一个文件",
        "backToFiles" to "返回文件列表",
        "wrapLines" to "自动换行",
        "empty" to "这个会话还没有可查看的改动",
        "loadFailed" to "对比加载失败",
        "listPartial" to "文件列表不完整：%s",
    )

    private val en = mapOf(
        "changes" to "Changes",
        "viewChanges" to "View changes",
        "editedFile" to "Edited %s",
        "editedFiles" to "Edited %d files",
        "moreFiles" to "%d more files",
        "binary" to "Binary file; changes can't be shown",
        "oversized" to "File too large to show changes",
        "created" to "Created in this turn",
        "deleted" to "Deleted in this turn",
        "unchanged" to "Both sides are identical",
        "coarse" to "Line comparison timed out; shown as a whole-file replacement",
        "truncated" to "Showing the first %d of %d lines",
        "turn" to "Turn %d",
        "olderTurn" to "Previous turn's changes",
        "newerTurn" to "Next turn's changes",
        "previousFile" to "Previous file",
        "nextFile" to "Next file",
        "backToFiles" to "Back to files",
        "wrapLines" to "Wrap lines",
        "empty" to "No changes to review in this session yet",
        "loadFailed" to "Couldn't load the comparison",
        "listPartial" to "File list incomplete: %s",
    )

    private fun t(key: String): String = (if (LocaleManager.language == "en") en else zh)[key] ?: key

    val changes get() = t("changes")
    val viewChanges get() = t("viewChanges")
    val editedFile get() = t("editedFile")
    val editedFiles get() = t("editedFiles")
    val moreFiles get() = t("moreFiles")
    val binary get() = t("binary")
    val oversized get() = t("oversized")
    val created get() = t("created")
    val deleted get() = t("deleted")
    val unchanged get() = t("unchanged")
    val coarse get() = t("coarse")
    val truncated get() = t("truncated")
    val turn get() = t("turn")
    val olderTurn get() = t("olderTurn")
    val newerTurn get() = t("newerTurn")
    val previousFile get() = t("previousFile")
    val nextFile get() = t("nextFile")
    val backToFiles get() = t("backToFiles")
    val wrapLines get() = t("wrapLines")
    val empty get() = t("empty")
    val loadFailed get() = t("loadFailed")
    val listPartial get() = t("listPartial")

    fun note(note: DiffNote, diff: WorkspaceFileDiff.Text): String = when (note) {
        DiffNote.CREATED -> created
        DiffNote.DELETED -> deleted
        DiffNote.UNCHANGED -> unchanged
        DiffNote.COARSE -> coarse
        DiffNote.TRUNCATED -> truncated.format(diff.shownLines ?: 0, diff.totalLines ?: 0)
    }

    /** 卡片标题：单文件写文件名，多文件写总数（DSH Web 同规则）。 */
    fun cardTitle(summary: WorkspaceChangesSummary): String =
        if (summary.total == 1 && summary.files.size == 1) editedFile.format(summary.files[0].name)
        else editedFiles.format(summary.total)
}
