package dev.deeplinks.native
import dev.deeplinks.native.MobileMessage

/**
 * 历史分页合并（WI-002）—— 不依赖 Compose 的纯函数：
 *
 * - 更旧页必须插到现有消息前面，保持旧→新的时间顺序；
 * - 相同事件在 tail/older 页边界重叠时，按稳定 id 去重，保留更旧页出现的副本
 *   （更旧页在前，即"先出现者胜"），避免 reasoning 边界重复错位。
 */
fun mergeHistoryPages(older: List<MobileMessage>, existing: List<MobileMessage>): List<MobileMessage> {
    if (older.isEmpty()) return coalesceWorkspaceChanges(coalesceRequestMessages(existing))
    if (existing.isEmpty()) return coalesceWorkspaceChanges(coalesceRequestMessages(older))
    val seen = HashSet<String>(older.size + existing.size)
    val merged = buildList(older.size + existing.size) {
        for (m in older) if (seen.add(m.id)) add(m)
        for (m in existing) if (seen.add(m.id)) add(m)
    }
    return coalesceWorkspaceChanges(coalesceRequestMessages(merged))
}

/**
 * 内容签名：用于轮询刷新时判断列表是否需要整体替换。
 * 不能只比较 size/首尾 id —— 中间消息的状态更新（running 定稿、文本变化）
 * 也必须触发必要刷新。O(n) 整数运算，比逐条字符串比较便宜。
 */
fun List<MobileMessage>.contentSignature(): Int = fold(1) { hash, m ->
    var x = m.id.hashCode()
    x = x * 31 + (if (m.running == true) 1 else 0)
    x = x * 31 + m.text.hashCode()
    x = x * 31 + (m.durationMs?.toInt() ?: 0)
    x = x * 31 + (m.requestStatus ?: "").hashCode()
    x = x * 31 + (m.outcome ?: "").hashCode()
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
        val status = mergeRequestStatus(m.requestStatus, cur.requestStatus)
        m.copy(
            running = cur.running ?: m.running,
            text = text,
            requestStatus = status,
            outcome = mergeRequestOutcome(m.outcome, cur.outcome, status),
        )
    }
    val freshIds = merged.map { it.id }.toSet()
    val serverUserTexts = merged
        .filter { it.role == "user" && it.id != LOCAL_PENDING_ID }
        .map { it.text }
        .toSet()
    val historyAssistants = merged.filter { it.role == "assistant" }
    val historyReasoning = merged.filter { it.role == "reasoning" }
    val pending = live.filter { msg ->
        if (msg.id in freshIds) return@filter false
        when {
            msg.id == LOCAL_PENDING_ID -> msg.text !in serverUserTexts
            // 仍在生成：必须保留
            msg.running == true -> true
            // 澄清/审批卡不在 history 投影里，刷新或重同步后必须留下
            msg.role == "question" || msg.role == "approval" -> true
            // SSE 先到的改动卡：history 尚未覆盖到该宣告时保留；已覆盖却没有它，
            // 说明同轮被取代为空或 Host 已取不到摘要，丢弃。同轮取代交给 coalesce。
            msg.role == ROLE_WORKSPACE_CHANGES -> merged.none { it.seq > msg.seq }
            // 已定稿的流式气泡：若 history 已有对应内容则丢弃，否则会「一句变两句」
            isEphemeralStreamId(msg.id) -> !historySupersedesEphemeral(msg, historyAssistants, historyReasoning)
            else -> false
        }
    }
    return coalesceWorkspaceChanges(coalesceRequestMessages(merged + pending))
}

/** history 是否已包含可替代该 SSE 临时气泡的定稿消息。 */
internal fun historySupersedesEphemeral(
    ephemeral: MobileMessage,
    historyAssistants: List<MobileMessage>,
    historyReasoning: List<MobileMessage>,
): Boolean {
    val text = ephemeral.text.trim()
    if (text.isEmpty()) return true
    val pool = when (ephemeral.role) {
        "assistant" -> historyAssistants
        "reasoning" -> historyReasoning
        else -> return false // tool/compact 等仍按 id 保留到 history 收录
    }
    return pool.any { h ->
        val ht = h.text.trim()
        when {
            ht == text -> true
            // 流式半句被定稿全文覆盖（或反之）：要求较短方是较长方前缀，且长度接近
            text.length >= 24 && ht.startsWith(text) && ht.length <= text.length * 2 -> true
            ht.length >= 24 && text.startsWith(ht) && text.length <= ht.length * 2 -> true
            else -> false
        }
    }
}

/** 仅存在于客户端 SSE 增量路径的临时 id（服务端 history 通常尚未用同 id 收录）。 */
internal fun isEphemeralStreamId(id: String): Boolean =
    id.startsWith("msg-stream-") ||
        id.startsWith("reason-") ||
        id.startsWith("tool-stream-") ||
        id.startsWith("compact-")

