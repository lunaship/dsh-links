package dev.dsh.mobile.native.util
import dev.dsh.mobile.native.MobileMessage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 消息分组（WI-005 / WI-006 抽出）单元测试。
 *
 * 验证：
 * - 连续 tool_call/tool_result 合并为 ToolGroup；
 * - <2 退化为 Single；
 * - groupKey 稳定 —— LazyColumn 用作 key 时不会因重组抖动。
 */
class MessageGroupingTest {

    private fun msg(id: String, role: String, seq: Long = 0L): MobileMessage =
        MobileMessage(id = id, role = role, text = "t-$id", time = 1_700_000_000_000L + seq)

    @Test
    fun `空输入返回空列表`() {
        assertEquals(emptyList<MessageGroup>(), groupMessages(emptyList()))
    }

    @Test
    fun `非工具消息保持 Single`() {
        val msgs = listOf(
            msg("u-1", "user", 1),
            msg("a-1", "assistant", 2),
            msg("r-1", "reasoning", 3),
        )
        val groups = groupMessages(msgs)
        assertEquals(3, groups.size)
        assertTrue(groups.all { it is MessageGroup.Single })
        assertEquals(listOf("u-1", "a-1", "r-1"), groups.map { it.groupKey })
    }

    @Test
    fun `连续 tool_call 聚合为 ToolGroup`() {
        val msgs = listOf(
            msg("u-1", "user", 1),
            msg("tc-1", "tool_call", 2),
            msg("tr-1", "tool_result", 3),
            msg("tc-2", "tool_call", 4),
            msg("tr-2", "tool_result", 5),
            msg("a-1", "assistant", 6),
        )
        val groups = groupMessages(msgs)
        assertEquals(3, groups.size)
        assertTrue(groups[0] is MessageGroup.Single)
        assertEquals("u-1", groups[0].groupKey)
        assertTrue(groups[1] is MessageGroup.ToolGroup)
        val toolGroup = groups[1] as MessageGroup.ToolGroup
        assertEquals(4, toolGroup.items.size)
        assertEquals(listOf("tc-1", "tr-1", "tc-2", "tr-2"), toolGroup.items.map { it.id })
        assertTrue(groups[2] is MessageGroup.Single)
    }

    @Test
    fun `单条 tool_call 退化为 Single`() {
        val msgs = listOf(
            msg("u-1", "user", 1),
            msg("tc-1", "tool_call", 2),
            msg("a-1", "assistant", 3),
        )
        val groups = groupMessages(msgs)
        assertEquals(3, groups.size)
        // tool_call 单独一条 → Single，不聚合
        assertTrue(groups[1] is MessageGroup.Single)
        assertEquals("tc-1", groups[1].groupKey)
    }

    @Test
    fun `工具段被非工具消息分隔时分别聚合`() {
        val msgs = listOf(
            msg("tc-1", "tool_call", 1),
            msg("tr-1", "tool_result", 2),
            msg("a-1", "assistant", 3),
            msg("tc-2", "tool_call", 4),
            msg("tr-2", "tool_result", 5),
            msg("tr-3", "tool_result", 6),
        )
        val groups = groupMessages(msgs)
        assertEquals(3, groups.size)
        assertTrue(groups[0] is MessageGroup.ToolGroup)
        assertEquals(2, (groups[0] as MessageGroup.ToolGroup).items.size)
        assertTrue(groups[1] is MessageGroup.Single)
        assertEquals("a-1", groups[1].groupKey)
        assertTrue(groups[2] is MessageGroup.ToolGroup)
        assertEquals(3, (groups[2] as MessageGroup.ToolGroup).items.size)
    }

    @Test
    fun `groupKey 包含 size，用于 LazyColumn 稳定 key`() {
        val msgs = listOf(
            msg("tc-1", "tool_call", 1),
            msg("tr-1", "tool_result", 2),
        )
        val group = groupMessages(msgs).first() as MessageGroup.ToolGroup
        assertEquals("tc-1-group-2", group.groupKey)
    }
}