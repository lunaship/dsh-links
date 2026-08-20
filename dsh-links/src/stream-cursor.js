/**
 * SSE 游标：每条连接自己的 afterSeq，分页拉历史直到追上，避免 50 条窗口跳号。
 */
export async function loadEventsAfter(afterSeq, fetchPage, { maxPages = 40, pageSize = 50 } = {}) {
  const collected = []
  let beforeSeq
  let projections = null
  for (let i = 0; i < maxPages; i++) {
    const payload = { maxMessages: pageSize }
    if (Number.isInteger(beforeSeq) && beforeSeq > 0) payload.beforeSeq = beforeSeq
    const page = await fetchPage(payload)
    const events = page?.events ?? []
    if (page?.projections?.values) projections = page.projections.values
    collected.push(...events)
    if (events.length === 0) break
    const seqs = events.map((item) => item?.event?.seq).filter((n) => typeof n === "number")
    if (seqs.length === 0) break
    const minSeq = Math.min(...seqs)
    if (minSeq <= afterSeq || page?.hasMore === false) break
    beforeSeq = minSeq
  }
  const seen = new Set()
  const events = []
  for (const item of collected) {
    const e = item?.event
    if (!e || typeof e.seq !== "number" || e.seq <= afterSeq || seen.has(e.seq)) continue
    seen.add(e.seq)
    events.push(e)
  }
  events.sort((a, b) => a.seq - b.seq)
  return { events, projections }
}

export function sseMessageFrame(e) {
  return `event: message\ndata: ${JSON.stringify({ seq: e.seq, type: e.type, time: e.time, data: e.data })}\n\n`
}
