import { isContextInjectionText } from "./context-injection.js"

/** session.history 单页消息数上限（客户端 maxMessages 不得突破）。 */
export const MAX_HISTORY_MESSAGES = 200

export function clampHistoryMaxMessages(raw) {
  const n = Number(raw)
  if (!Number.isInteger(n) || n <= 0) return undefined
  return Math.min(n, MAX_HISTORY_MESSAGES)
}

/**
 * 历史页投影（纯函数，WI-001）
 *
 * 把一页 session.history 的原始事件投影为移动端消息列表。规则：
 *
 * - 每条消息带稳定 id 与数值 seq：id 以事件 seq 为键（msg-<seq> / reason-<seq> /
 *   tool-<seq> / …），跨页重复由客户端按 id 去重（保留更旧页位置）。
 * - reasoning 只合并到覆盖其 seq 范围的页面：来自会话文件的补全块按 block-end
 *   事件 seq 归属窗口 [页首 seq, 页尾 seq]，并按 seq 插到紧随其后的消息之前；
 *   投影已自带文本的 reasoning（子会话未剥除）不再从文件重复合并。
 * - assistant/message 的文本块通过 sourceEventSeqs 定位同页 text block-end，
 *   共享同一 id，避免同页重复；block-end 不在本页时（分页边界）仍以 block-end
 *   seq 为 id，客户端按 id 去重。
 */
export function projectHistoryPage({ events, reasoningBySeq = new Map(), hasMore = false }) {
  const messages = []
  // 页窗口：本页最早/最晚事件的 seq（reasoning 归属边界）
  const firstEvent = events[0]?.event ?? null
  const lastEvent = events[events.length - 1]?.event ?? null
  const pageFirstSeq = firstEvent?.seq
  const pageLastSeq = lastEvent?.seq

  // 原始事件按 seq 索引（assistant/message 的 sourceEventSeqs 定位用）
  const eventBySeq = new Map()
  for (const item of events) {
    const e = item?.event
    if (e && typeof e.seq === "number") eventBySeq.set(e.seq, e)
  }

  let pendingReasoning = null // { seq, time, text }
  const flushReasoning = (time) => {
    if (pendingReasoning) {
      const text = (pendingReasoning.text ?? "").trim()
      if (text) {
        push({
          id: `reason-${pendingReasoning.seq}`,
          seq: pendingReasoning.seq,
          role: "reasoning",
          text,
          time: pendingReasoning.time || time,
          type: "reasoning",
        })
      }
      pendingReasoning = null
    }
  }
  const emittedIds = new Set()
  const push = (m) => {
    if (!emittedIds.has(m.id)) {
      emittedIds.add(m.id)
      messages.push(m)
    }
  }

  // tool/result 耗时：同 callId 的 tool/call 到 result 时差
  const toolCalls = new Map()
  let lastTurnEndReason = null

  for (const item of events) {
    const e = item?.event
    if (!e || typeof e.seq !== "number") continue
    if (e.type === "user/message") {
      flushReasoning(e.time)
      const text = (e.data?.content ?? []).map((c) => c.text || "").join("")
      const role = isContextInjectionText(text) ? "context_injection" : "user"
      push({ id: `msg-${e.seq}`, seq: e.seq, role, text, time: e.time, type: "text" })
    } else if (e.type === "assistant/chunk" || e.type === "assistant/message") {
      const chunk = e.data?.chunk
      if (chunk?.type === "block-end" && chunk.block?.text) {
        if (chunk.block.type === "reasoning") {
          // 连续 reasoning block-end 追加文本，保留首块 seq/time（与文件侧分组一致）
          const piece = chunk.block.text || reasoningBySeq.get(e.seq)?.text || ""
          if (pendingReasoning) {
            if (piece) pendingReasoning.text = pendingReasoning.text ? pendingReasoning.text + "\n" + piece : piece
          } else {
            pendingReasoning = { seq: e.seq, time: e.time, text: piece }
          }
        } else {
          flushReasoning(e.time)
          if (chunk.block.type === "text") {
            push({ id: `msg-${e.seq}`, seq: e.seq, role: "assistant", text: chunk.block.text, time: e.time, type: "text" })
          }
        }
      }
      const message = e.data?.message
      if (message) {
        flushReasoning(e.time)
        // 本消息内 text block-end 的 seq（sourceEventSeqs 定位；跨页时以该 seq 为 id，客户端去重）
        const textBlockEndSeqs = []
        for (const s of e.sourceEventSeqs ?? []) {
          const se = eventBySeq.get(s)
          const c = se?.data?.chunk
          if (c?.type === "block-end" && c.block?.type === "text") textBlockEndSeqs.push(s)
        }
        let ti = 0
        for (const block of message.content ?? []) {
          const blockText = String(block?.text ?? "").trim()
          if (block?.type !== "text" || !blockText) continue
          const blockEndSeq = textBlockEndSeqs[ti]
          ti++
          // 与同页 text block-end 共享 id；block-end 已产出时跳过（不重复渲染）
          const id = blockEndSeq !== undefined ? `msg-${blockEndSeq}` : `msg-${e.seq}-${ti}`
          push({ id, seq: blockEndSeq ?? e.seq, role: "assistant", text: blockText, time: e.time, type: "text" })
        }
      }
    } else if (e.type === "approval/asked") {
      flushReasoning(e.time)
      push({
        id: `approval-${e.seq}`,
        seq: e.seq,
        role: "approval",
        text: e.data?.reason || `请求授权执行 ${e.data?.toolName || "工具"}`,
        toolName: e.data?.toolName || "tool",
        approvalId: e.data?.id ?? "",
        callId: e.data?.callId ?? "",
        time: e.time,
        type: "approval",
      })
    } else if (e.type === "tool/call") {
      flushReasoning(e.time)
      toolCalls.set(e.data?.callId, { time: e.time })
      push({
        id: `tool-${e.seq}`,
        seq: e.seq,
        role: "tool_call",
        name: e.data?.name || "tool",
        args: typeof e.data?.arguments === "string" ? e.data.arguments : JSON.stringify(e.data?.arguments || {}),
        callId: e.data?.callId,
        turn: e.data?.turn,
        step: e.data?.step,
        time: e.time,
        type: "tool_call",
      })
    } else if (e.type === "tool/result") {
      flushReasoning(e.time)
      const content = (e.data?.message?.content ?? []).map((c) => c.text || JSON.stringify(c)).join("\n")
      const callId = e.data?.message?.source?.callId
      const start = toolCalls.get(callId)?.time
      push({
        id: `tool-res-${e.seq}`,
        seq: e.seq,
        role: "tool_result",
        text: content,
        callId,
        turn: e.data?.turn,
        step: e.data?.step,
        durationMs: Number.isFinite(start) ? e.time - start : undefined,
        time: e.time,
        type: "tool_result",
      })
    } else if (e.type === "compaction/start") {
      flushReasoning(e.time)
      push({ id: `compact-${e.seq}`, seq: e.seq, role: "compaction", text: "", running: true, time: e.time, type: "compaction" })
    } else if (e.type === "compaction/summary") {
      const summaryText = (e.data?.summary ?? []).map((c) => c.text || "").join("\n").trim()
      const last = messages[messages.length - 1]
      if (last?.role === "compaction") {
        last.text = summaryText
        last.running = false
      } else {
        push({ id: `compact-${e.seq}`, seq: e.seq, role: "compaction", text: summaryText, running: false, time: e.time, type: "compaction" })
      }
    } else if (e.type === "compaction/end") {
      const last = messages[messages.length - 1]
      if (last?.role === "compaction") last.running = false
    } else if (e.type === "todo/write") {
      flushReasoning(e.time)
      push({ id: `todo-${e.seq}`, seq: e.seq, role: "todo", todos: Array.isArray(e.data?.todos) ? e.data.todos : [], time: e.time, type: "todo" })
    } else if (e.type === "turn/end") {
      lastTurnEndReason = e.data?.reason?.kind ?? null
    }
  }
  flushReasoning(messages.length ? messages[messages.length - 1].time : 0)

  // 文件补全：只合并 seq 落在本页窗口内的 reasoning 块，按 seq 逆序插入
  // （插到紧随其后的消息之前；页尾没有后继消息时留在页尾，客户端按 id 去重）
  if (reasoningBySeq.size > 0 && typeof pageFirstSeq === "number" && typeof pageLastSeq === "number") {
    const inWindow = [...reasoningBySeq.values()]
      .filter((r) => r && typeof r.seq === "number" && r.seq >= pageFirstSeq && r.seq <= pageLastSeq && !emittedIds.has(`reason-${r.seq}`))
      .sort((a, b) => a.seq - b.seq)
    for (const r of inWindow.reverse()) {
      const text = String(r.text ?? "").trim()
      if (!text) continue
      let idx = messages.findIndex((m) => m.seq > r.seq)
      if (idx < 0) idx = messages.length
      messages.splice(idx, 0, {
        id: `reason-${r.seq}`,
        seq: r.seq,
        role: "reasoning",
        text,
        time: r.time ?? 0,
        type: "reasoning",
      })
    }
  }

  return {
    messages,
    hasMore: Boolean(hasMore),
    nextBeforeSeq: typeof firstEvent?.seq === "number" ? firstEvent.seq : null,
    maxSeq: typeof lastEvent?.seq === "number" ? lastEvent.seq : null,
    stoppedReason: lastTurnEndReason !== null && lastTurnEndReason !== "completed" ? lastTurnEndReason : null,
  }
}
