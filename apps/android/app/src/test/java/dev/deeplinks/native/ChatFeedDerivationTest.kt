package dev.deeplinks.native

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 消息流数据推导测试：这些规则原先内联在 WorkspaceScreen 的 LazyColumn 里，无法单测。
 */
class ChatFeedDerivationTest {

    private fun msg(
        id: String,
        role: String,
        text: String = "text",
        running: Boolean? = null,
        toolName: String? = null,
    ) = MobileMessage(id = id, role = role, text = text, running = running, toolName = toolName)

    @Test
    fun blankCompletedReasoningRowsAreDropped() {
        val feed = deriveChatFeed(
            olderMessages = emptyList(),
            messages = listOf(
                msg("r1", "reasoning", text = "", running = false),
                msg("a1", "assistant", text = "hi"),
            ),
            toolQuery = "",
        )
        assertEquals(1, feed.visibleGroups.size)
    }

    @Test
    fun runningReasoningRowsAreKept() {
        val feed = deriveChatFeed(emptyList(), listOf(msg("r1", "reasoning", text = "", running = true)), "")
        assertEquals(1, feed.visibleGroups.size)
    }

    @Test
    fun lastCompletedAssistantIgnoresStreamingOne() {
        val feed = deriveChatFeed(
            emptyList(),
            listOf(
                msg("a1", "assistant", "done"),
                msg("a2", "assistant", "streaming", running = true),
            ),
            "",
        )
        assertEquals("a1", feed.lastCompletedAssistantId)
    }

    @Test
    fun lastCompletedAssistantIsNullWithoutAssistant() {
        assertNull(deriveChatFeed(emptyList(), listOf(msg("u1", "user")), "").lastCompletedAssistantId)
    }

    @Test
    fun olderHistoryPagesMergeWithLiveMessages() {
        val feed = deriveChatFeed(
            olderMessages = listOf(msg("a1", "assistant", "old")),
            messages = listOf(msg("a2", "assistant", "new")),
            toolQuery = "",
        )
        assertEquals(2, feed.visibleGroups.size)
    }

    @Test
    fun sweepingIdIsNullWhenNotRunning() {
        assertNull(resolveSweepingId(listOf(msg("t1", "tool_call", running = true)), running = false))
    }

    @Test
    fun sweepingIdPicksLastRunningToolOrReasoningRow() {
        val messages = listOf(
            msg("t1", "tool_call", running = true),
            msg("a1", "assistant", "settled"),
            msg("r1", "reasoning", text = "…", running = true),
        )
        assertEquals("r1", resolveSweepingId(messages, running = true))
    }

    @Test
    fun completedRowsNeverSweep() {
        val messages = listOf(
            msg("t1", "tool_call", running = false),
            msg("a1", "assistant", "done", running = false),
        )
        assertNull(resolveSweepingId(messages, running = true))
    }

    @Test
    fun blankToolQueryKeepsEveryGroup() {
        val feed = deriveChatFeed(emptyList(), listOf(msg("u1", "user")), "   ")
        assertEquals(1, feed.visibleGroups.size)
        assertTrue(feed.visibleGroups.isNotEmpty())
    }
}
