/**
 * 会话日志（JSONL 文本）→ reasoning 块索引。
 *
 * RPC 投影会剥掉父会话的思考内容，所以冷会话/分页历史的思考只能从会话
 * 日志文件里补：这里按块首 `seq` 建索引，与 `src/history.js` 投影的事件侧
 * 分组规则保持一致（flush 点必须同源，否则同一条思考会被拆成两段或重复）。
 *
 * 从 `src/index.js` 抽出，便于用多帧 zstd 日志做回归测试。
 */
const FLUSH_TYPES = new Set([
  "user/message",
  "approval/asked",
  "tool/call",
  "tool/result",
  "compaction/start",
  "todo/write",
])

/**
 * @param {string} text 会话日志正文（已解压）
 * @returns {Map<number, { seq: number, time: number, text: string }>}
 */
export function reasoningBlocksFromSessionLog(text) {
  const reasoning = new Map()
  let pending = null // { seq, time, text }
  const flush = (time) => {
    if (!pending) return
    const blockText = pending.text.trim()
    if (blockText) {
      reasoning.set(pending.seq, { seq: pending.seq, time: pending.time || time, text: blockText })
    }
    pending = null
  }
  for (const line of String(text ?? "").split("\n")) {
    if (!line.trim()) continue
    let e
    try { e = JSON.parse(line) } catch { continue }
    if (e?.type === "assistant/chunk") {
      const chunk = e?.data?.chunk
      if (chunk?.type === "block-end" && chunk.block?.type === "reasoning") {
        const blockText = chunk.block?.text
        if (blockText) {
          pending = pending
            ? { seq: pending.seq, time: pending.time, text: pending.text + "\n" + blockText }
            : { seq: e.seq, time: e.time, text: blockText }
        }
        continue
      }
      if (chunk?.type === "block-end" && chunk.block?.text) flush(e?.time ?? 0)
    } else if (e?.type === "assistant/message") {
      flush(e?.time ?? 0)
    } else if (FLUSH_TYPES.has(e?.type)) {
      flush(e?.time ?? 0)
    }
  }
  flush(0)
  return reasoning
}
