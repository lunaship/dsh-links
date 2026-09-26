package dev.deeplinks.native

import dev.deeplinks.native.util.MessageGroup
import dev.deeplinks.native.util.groupMessages
import dev.deeplinks.native.util.matchesTool

internal data class ChatFeedModel(
    val lastCompletedAssistantId: String?,
    val visibleGroups: List<MessageGroup>,
)

/**
 * 消息流数据推导（从 WorkspaceScreen 抽出，COM-001 拆解）。
 *
 * 纯函数、无 Compose 依赖，因此这几条原本埋在 composable 里无法验证的规则现在可单测：
 * 1. 合并更早的历史页；
 * 2. 丢弃「空的、且不在运行中的」思考行；
 * 3. 按相邻 tool_call/tool_result 聚合（见 groupMessages）；
 * 4. 工具查找查询非空时只保留命中的组。
 */
internal fun deriveChatFeed(
    olderMessages: List<MobileMessage>,
    messages: List<MobileMessage>,
    toolQuery: String,
): ChatFeedModel {
    val allMessages = mergeHistoryPages(olderMessages, messages)
    val displayMessages = allMessages.filterNot {
        it.role == "reasoning" && it.text.isBlank() && it.running != true
    }
    val lastCompletedAssistantId = displayMessages.lastOrNull {
        it.role == "assistant" && it.running != true
    }?.id
    val messageGroups = groupMessages(displayMessages)
    val visibleGroups = if (toolQuery.isBlank()) {
        messageGroups
    } else {
        messageGroups.filter { group ->
            when (group) {
                is MessageGroup.Single -> matchesTool(group.msg, toolQuery)
                is MessageGroup.ToolGroup -> group.items.any { matchesTool(it, toolQuery) }
                else -> false
            }
        }
    }
    return ChatFeedModel(lastCompletedAssistantId, visibleGroups)
}
/**
 * 「正在扫过」的行 id：只有运行中的 tool_call / tool_result / reasoning 才能抢状态条，
 * 已定稿的「已思考」不行。纯函数、可单测。
 */
internal fun resolveSweepingId(messages: List<MobileMessage>, running: Boolean): String? {
    if (!running) return null
    return messages.lastOrNull {
        (it.role == "tool_call" || it.role == "tool_result" || it.role == "reasoning") &&
            it.running == true
    }?.id
}
