package dev.dsh.mobile.util

import dev.dsh.mobile.MobileMessage

/**
 * DSH 斜杠命令/工具搜索（WI-005 / WI-006 抽出）。
 *
 * 行为对齐 DSH Web UI 的命令面板：
 * - 输入 `/` + 至少 2 字符时按命令 token 前缀/子串匹配（大小写不敏感）；
 * - < 2 字符时不过滤，原样返回全列表；
 * - 空查询（不含 `/`）视为不在命令面板，返回空列表（避免 UI 误显示）。
 *
 * 与 [dev.dsh.mobile.ui.DshFilterChip] 一起使用 —— 单纯纯函数，零 Compose 依赖，可 JVM 单测。
 */

/** 单个斜杠命令条目：`token` 是 `/name` 这种可输入片段，`description` 是右栏说明。 */
data class SlashCommand(val token: String, val description: String)

/** 一组同类的命令（例如 "Built-in"、"Permissions"、"Workspaces"）。 */
data class SlashCommandGroup(val title: String, val items: List<SlashCommand>)

/**
 * 把带搜索前缀的查询字符串过滤为命中的分组。
 *
 * @param groups 完整命令分组（保持输入顺序）
 * @param query 用户已输入的文本（含前导 `/`），允许为空或非命令输入
 * @return 过滤后的分组 —— 完全无命中时返回空列表
 */
fun filterSlashCommands(
    groups: List<SlashCommandGroup>,
    query: String,
): List<SlashCommandGroup> {
    if (!query.startsWith("/")) return emptyList()
    val prefix = query.removePrefix("/")
    if (prefix.length < 2) return groups // 至少 2 字符才过滤（避免输入 `/` 就把列表缩小）
    return groups.mapNotNull { g ->
        val filtered = g.items.filter { it.token.contains(prefix, ignoreCase = true) }
        if (filtered.isEmpty()) null else SlashCommandGroup(g.title, filtered)
    }
}

/**
 * 当前会话内的工具调用查找：判断 [message] 是否为工具消息且命中 [query]。
 *
 * 匹配范围（大小写不敏感）：
 * - 工具名 [MobileMessage.toolName]（例如 "bash"、"Write"）；
 * - 工具参数 [MobileMessage.toolArgs]（JSON 参数原文）；
 * - 结果文本 [MobileMessage.text]（tool_result 的执行输出摘要）。
 *
 * 非工具消息（user/assistant/reasoning/...）一律不命中——查找目标是"这条对话里
 * 哪几步工具调用跟 X 有关"，不把普通聊天内容卷进来。
 *
 * 空查询 = 命中所有工具消息（UI 层通常用空查询表示"不过滤"）。
 */
fun matchesTool(message: MobileMessage, query: String): Boolean {
    if (message.role != "tool_call" && message.role != "tool_result") return false
    if (query.isBlank()) return true
    val q = query.trim()
    return (message.toolName?.contains(q, ignoreCase = true) == true) ||
        (message.toolArgs?.contains(q, ignoreCase = true) == true) ||
        message.text.contains(q, ignoreCase = true)
}