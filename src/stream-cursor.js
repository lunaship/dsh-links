/**
 * SSE 游标：每条连接自己的 afterSeq，分页拉历史直到追上，避免 50 条窗口跳号。
 * 收集量有硬顶，避免慢消费者把补发事件堆在内存里。
 */
export const MAX_CATCHUP_PAGES = 10
export const CATCHUP_PAGE_SIZE = 50
export const MAX_CATCHUP_EVENTS = 500

export async function loadEventsAfter(afterSeq, fetchPage, {
  maxPages = MAX_CATCHUP_PAGES,
  pageSize = CATCHUP_PAGE_SIZE,
  maxEvents = MAX_CATCHUP_EVENTS,
} = {}) {
  const collected = []
  let beforeSeq
  let projections = null
  for (let i = 0; i < maxPages && collected.length < maxEvents; i++) {
    const payload = { maxMessages: pageSize }
    if (Number.isInteger(beforeSeq) && beforeSeq > 0) payload.beforeSeq = beforeSeq
    const page = await fetchPage(payload)
    const events = page?.events ?? page?.records ?? []
    if (page?.projections?.values) projections = page.projections.values
    const room = maxEvents - collected.length
    if (room <= 0) break
    collected.push(...events.slice(0, room))
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
