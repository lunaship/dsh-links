package dev.dsh.mobile.native
import dev.dsh.mobile.native.MobileMessage

/**
 * 历史分页合并（WI-002）—— 不依赖 Compose 的纯函数：
 *
 * - 更旧页必须插到现有消息前面，保持旧→新的时间顺序；
 * - 相同事件在 tail/older 页边界重叠时，按稳定 id 去重，保留更旧页出现的副本
 *   （更旧页在前，即"先出现者胜"），避免 reasoning 边界重复错位。
 */
fun mergeHistoryPages(older: List<MobileMessage>, existing: List<MobileMessage>): List<MobileMessage> {
    if (older.isEmpty()) return existing
    if (existing.isEmpty()) return older
    val seen = HashSet<String>(older.size + existing.size)
    return buildList(older.size + existing.size) {
        for (m in older) if (seen.add(m.id)) add(m)
        for (m in existing) if (seen.add(m.id)) add(m)
    }
}

/**
 * 内容签名：用于轮询刷新时判断列表是否需要整体替换。
 * 不能只比较 size/首尾 id —— 中间消息的状态更新（running 定稿、文本变化）
 * 也必须触发必要刷新。O(n) 整数运算，比逐条字符串比较便宜。
 */
fun List<MobileMessage>.contentSignature(): Int = fold(1) { hash, m ->
    var x = m.id.hashCode()
    x = x * 31 + (if (m.running == true) 1 else 0)
    x = x * 31 + m.text.length
    x = x * 31 + (m.durationMs?.toInt() ?: 0)
    hash * 31 + x
}

private const val LOCAL_PENDING_ID = "local-pending"

/** SSE 在途消息与 history 全量刷新合并：保留更长流式文本与 running 态。 */
fun mergeHistoryWithLive(fresh: List<MobileMessage>, live: List<MobileMessage>): List<MobileMessage> {
    if (live.isEmpty()) return fresh
    val liveById = live.associateBy { it.id }
    val merged = fresh.map { m ->
        val cur = liveById[m.id] ?: return@map m
        val text = if (cur.running == true && cur.text.length > m.text.length) cur.text else m.text
        m.copy(running = cur.running ?: m.running, text = text)
    }
    val freshIds = merged.map { it.id }.toSet()
    val serverUserTexts = merged
        .filter { it.role == "user" && it.id != LOCAL_PENDING_ID }
        .map { it.text }
        .toSet()
    val pending = live.filter { msg ->
        msg.id == LOCAL_PENDING_ID && msg.id !in freshIds && msg.text !in serverUserTexts
    }
    return merged + pending
}

