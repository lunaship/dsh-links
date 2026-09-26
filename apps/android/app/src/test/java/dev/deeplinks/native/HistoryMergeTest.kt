package dev.deeplinks.native

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 历史分页合并契约（WI-002）：更旧页必须插到现有消息前面；
 * 相同事件在页边界重叠时按稳定 id 去重（保留更旧页位置，即先出现的副本）。
 */
class HistoryMergeTest {

    private fun msg(id: String, role: String = "assistant", seq: Long = 0L) =
        MobileMessage(id = id, role = role, text = "text-$id", time = 1_700_000_000_000L + seq, type = "text")

    @Test
    fun `更旧页插入现有消息前面，顺序保持旧到新`() {
        val existing = listOf(msg("msg-30", "user", 30), msg("msg-31", "assistant", 31))
        val older = listOf(msg("msg-10", "user", 10), msg("msg-11", "assistant", 11))
        val merged = mergeHistoryPages(older, existing)
        assertEquals(listOf("msg-10", "msg-11", "msg-30", "msg-31"), merged.map { it.id })
    }

    @Test
    fun `连续三次加载更早，消息仍按旧到新排列`() {
        val p3 = listOf(msg("msg-1", "user", 1), msg("msg-2", "assistant", 2))
        val p2 = listOf(msg("msg-11", "user", 11), msg("msg-12", "assistant", 12))
        val p1 = listOf(msg("msg-21", "user", 21), msg("msg-22", "assistant", 22))
        var merged = p1
        merged = mergeHistoryPages(p2, merged)
        merged = mergeHistoryPages(p3, merged)
        assertEquals(listOf("msg-1", "msg-2", "msg-11", "msg-12", "msg-21", "msg-22"), merged.map { it.id })
    }

    @Test
    fun `边界重叠按稳定 id 去重，保留更旧页副本`() {
        // 更旧页（先渲染）含 reason-101 与其文本 msg-102；尾页因页尾无后继消息也带了 reason-101
        val older = listOf(msg("msg-100", "user", 100), msg("reason-101", "reasoning", 101), msg("msg-102", "assistant", 102))
        val tail = listOf(msg("msg-103", "assistant", 103), msg("reason-101", "reasoning", 101))
        val merged = mergeHistoryPages(older, tail)
        assertEquals(listOf("msg-100", "reason-101", "msg-102", "msg-103"), merged.map { it.id })
        // 保留的是更旧页里位于正确位置的副本
        assertTrue(merged.indexOfFirst { it.id == "reason-101" } < merged.indexOfFirst { it.id == "msg-103" })
    }

    @Test
    fun `重叠的 user 消息与工具事件同样去重`() {
        val older = listOf(msg("msg-5", "user", 5), msg("tool-6", "tool_call", 6))
        val tail = listOf(msg("tool-6", "tool_call", 6), msg("tool-res-7", "tool_result", 7))
        val merged = mergeHistoryPages(older, tail)
        assertEquals(listOf("msg-5", "tool-6", "tool-res-7"), merged.map { it.id })
    }

    @Test
    fun `空输入与单页输入稳定`() {
        assertEquals(emptyList<MobileMessage>(), mergeHistoryPages(emptyList(), emptyList()))
        val one = listOf(msg("msg-1"))
        assertEquals(one, mergeHistoryPages(emptyList(), one))
        assertEquals(one, mergeHistoryPages(one, emptyList()))
    }

    @Test
    fun `合并结果无重复 id`() {
        val older = listOf(msg("a", "user", 1), msg("b", "assistant", 2))
        val tail = listOf(msg("b", "assistant", 2), msg("c", "assistant", 3), msg("a", "user", 1))
        val merged = mergeHistoryPages(older, tail)
        assertEquals(merged.size, merged.map { it.id }.toSet().size)
    }

    @Test
    fun `内容签名：相同列表相等，中间变化也能检测`() {
        val a = listOf(msg("m1", "assistant", 1), msg("m2", "tool_call", 2).copy(running = true), msg("m3", "assistant", 3))
        val same = listOf(msg("m1", "assistant", 1), msg("m2", "tool_call", 2).copy(running = true), msg("m3", "assistant", 3))
        assertEquals(a.contentSignature(), same.contentSignature())
        // 中间消息状态变化（running 定稿）必须触发刷新 —— 不能只比较 size/首尾 id
        val changed = listOf(msg("m1", "assistant", 1), msg("m2", "tool_call", 2).copy(running = false), msg("m3", "assistant", 3))
        assertNotEquals(a.contentSignature(), changed.contentSignature())
        // 文本内容变化也能检测
        val textChanged = listOf(msg("m1", "assistant", 1), msg("m2", "tool_call", 2).copy(running = true, text = "other"), msg("m3", "assistant", 3))
        assertNotEquals(a.contentSignature(), textChanged.contentSignature())
        val statusChanged = listOf(msg("m1", "assistant", 1), msg("m2", "tool_call", 2).copy(running = true, requestStatus = "resolved"), msg("m3", "assistant", 3))
        assertNotEquals(a.contentSignature(), statusChanged.contentSignature())
    }

    @Test
    fun `mergeHistoryWithLive 保留流式更长文本并去掉已被服务端确认的 pending`() {
        val fresh = listOf(msg("msg-1", "user", 1).copy(text = "你好"))
        val live = listOf(
            msg("msg-2", "assistant", 2).copy(text = "流式", running = true),
            MobileMessage(id = "local-pending", role = "user", text = "你好", time = 1L, type = "text"),
        )
        val merged = mergeHistoryWithLive(fresh + msg("msg-2", "assistant", 2).copy(text = "流"), live)
        assertTrue(merged.none { it.id == "local-pending" })
        val assistant = merged.first { it.id == "msg-2" }
        assertEquals("流式", assistant.text)
        assertEquals(true, assistant.running)
    }

    @Test
    fun `mergeHistoryWithLive 保留 history 尚未收录的 SSE 在途消息`() {
        val fresh = listOf(msg("msg-1", "user", 1))
        val live = listOf(
            msg("msg-1", "user", 1),
            MobileMessage(id = "msg-stream-3-0", role = "assistant", text = "半句", time = 2L, type = "text", running = true),
            MobileMessage(id = "reason-3-0", role = "reasoning", text = "想", time = 2L, type = "reasoning", running = true),
        )
        val merged = mergeHistoryWithLive(fresh, live)
        assertEquals(listOf("msg-1", "msg-stream-3-0", "reason-3-0"), merged.map { it.id })
    }

    @Test
    fun `mergeHistoryWithLive 丢弃已被 history 定稿的流式气泡避免双回复`() {
        val fresh = listOf(
            msg("msg-1", "user", 1).copy(text = "你好"),
            msg("msg-50", "assistant", 50).copy(text = "完整回复内容在这里足够长了"),
        )
        val live = listOf(
            msg("msg-1", "user", 1).copy(text = "你好"),
            MobileMessage(
                id = "msg-stream-3-0",
                role = "assistant",
                text = "完整回复内容在这里足够长了",
                time = 2L,
                type = "text",
                running = false,
            ),
        )
        val merged = mergeHistoryWithLive(fresh, live)
        assertEquals(listOf("msg-1", "msg-50"), merged.map { it.id })
    }

    @Test
    fun `mergeHistoryWithLive 丢弃已被 history 定稿的思考行避免双已思考`() {
        val fresh = listOf(
            msg("msg-1", "user", 1).copy(text = "你好"),
            msg("reason-50", "reasoning", 50).copy(text = "先看一下完整思考内容在这里"),
        )
        val live = listOf(
            msg("msg-1", "user", 1).copy(text = "你好"),
            MobileMessage(
                id = "reason-3-0",
                role = "reasoning",
                text = "先看一下完整思考内容在这里",
                time = 2L,
                type = "reasoning",
                running = false,
            ),
        )
        val merged = mergeHistoryWithLive(fresh, live)
        assertEquals(listOf("msg-1", "reason-50"), merged.map { it.id })
    }

    @Test
    fun `mergeHistoryWithLive 丢弃空壳已思考`() {
        val fresh = listOf(msg("msg-1", "user", 1).copy(text = "你好"))
        val live = listOf(
            msg("msg-1", "user", 1).copy(text = "你好"),
            MobileMessage(
                id = "reason-3-0",
                role = "reasoning",
                text = "   ",
                time = 2L,
                type = "reasoning",
                running = false,
            ),
        )
        val merged = mergeHistoryWithLive(fresh, live)
        assertEquals(listOf("msg-1"), merged.map { it.id })
    }

    @Test
    fun `mergeHistoryWithLive 定稿流式气泡在 history 尚未收录时仍保留`() {
        val fresh = listOf(msg("msg-1", "user", 1).copy(text = "你好"))
        val live = listOf(
            msg("msg-1", "user", 1).copy(text = "你好"),
            MobileMessage(
                id = "msg-stream-3-0",
                role = "assistant",
                text = "只有流式有",
                time = 2L,
                type = "text",
                running = false,
            ),
        )
        val merged = mergeHistoryWithLive(fresh, live)
        assertEquals(listOf("msg-1", "msg-stream-3-0"), merged.map { it.id })
    }

    @Test
    fun `mergeHistoryWithLive 保留 history 未收录的澄清与审批卡`() {
        val fresh = listOf(msg("msg-1", "user", 1).copy(text = "你好"))
        val live = listOf(
            msg("msg-1", "user", 1).copy(text = "你好"),
            MobileMessage(
                id = "question-q-1",
                role = "question",
                text = "选一个",
                type = "question",
                questionRpcId = "q-1",
                questionPayloadJson = """[{"id":"q1","question":"选一个"}]""",
                requestStatus = REQUEST_PENDING,
            ),
            MobileMessage(
                id = "approval-ap-1",
                role = "approval",
                text = "bash",
                type = "approval",
                approvalId = "ap-1",
                requestStatus = REQUEST_PENDING,
            ),
        )
        val merged = mergeHistoryWithLive(fresh, live)
        assertEquals(listOf("msg-1", "question-q-1", "approval-ap-1"), merged.map { it.id })
        assertEquals("q-1", merged[1].questionRpcId)
        assertEquals("ap-1", merged[2].approvalId)
    }
}
