package dev.dsh.mobile

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
    }
}
