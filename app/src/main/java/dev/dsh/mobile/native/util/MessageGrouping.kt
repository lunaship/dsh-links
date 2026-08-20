package dev.dsh.mobile.native.util

import androidx.compose.runtime.Stable
import dev.dsh.mobile.native.MobileMessage

/**
 * 消息流分组（WI-005 / WI-006 抽出）：把相邻 tool_call/tool_result 消息
 * 合并为一个聚合组渲染，提升 LazyColumn 性能与视觉聚合。
 *
 * 拆分规则：
 * - 连续 ≥2 条 [MobileMessage.role] == `"tool_call"` 或 `"tool_result"` 合并为一个 [ToolGroup]；
 * - <2 退化为 [Single]，逐条渲染；
 * - 其他 role 始终是 [Single]。
 *
 * 纯函数 + 数据类，无 Android 依赖 → 可 JVM 单测。
 */
@Stable
interface MessageGroup {
    val groupKey: String

    data class Single(val msg: MobileMessage) : MessageGroup {
        override val groupKey: String get() = msg.id
    }

    data class ToolGroup(val items: List<MobileMessage>) : MessageGroup {
        override val groupKey: String get() = items.first().id + "-group"
    }
}

/** 把消息列表按相邻 tool_call/tool_result 切分（≥2 聚合；其他退化为 Single）。 */
fun groupMessages(messages: List<MobileMessage>): List<MessageGroup> {
    if (messages.isEmpty()) return emptyList()
    val out = mutableListOf<MessageGroup>()
    var i = 0
    while (i < messages.size) {
        val msg = messages[i]
        if (msg.role == "tool_call" || msg.role == "tool_result") {
            var j = i
            while (j < messages.size && (messages[j].role == "tool_call" || messages[j].role == "tool_result")) j++
            val slice = messages.subList(i, j)
            out.add(if (slice.size >= 2) MessageGroup.ToolGroup(slice) else MessageGroup.Single(slice[0]))
            i = j
        } else {
            out.add(MessageGroup.Single(msg))
            i++
        }
    }
    return out
}