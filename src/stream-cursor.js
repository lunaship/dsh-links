/**
 * SSE 游标：每条连接自己的 afterSeq，分页拉历史直到追上，避免 50 条窗口跳号。
 * 收集量有硬顶，避免慢消费者把补发事件堆在内存里。
 * 无法证明 afterSeq 到尾部连续覆盖时，不得把尾部当连续补发交付。
 */
export const MAX_CATCHUP_PAGES = 10
export const CATCHUP_PAGE_SIZE = 50
export const MAX_CATCHUP_EVENTS = 500

function isConsecutive(events) {
  for (let i = 1; i < events.length; i++) {
    if (events[i].seq !== events[i - 1].seq + 1) return false
  }
  return true
}

function sortedUniqueAfter(collected, afterSeq) {
  const seen = new Set()
  const events = []
  for (const item of collected) {
    const e = item?.event
    if (!e || typeof e.seq !== "number" || e.seq <= afterSeq || seen.has(e.seq)) continue
    seen.add(e.seq)
    events.push(e)
  }
  events.sort((a, b) => a.seq - b.seq)
  return events
}

export async function loadEventsAfter(afterSeq, fetchPage, {
  maxPages = MAX_CATCHUP_PAGES,
  pageSize = CATCHUP_PAGE_SIZE,
  maxEvents = MAX_CATCHUP_EVENTS,
} = {}) {
  const collected = []
  let beforeSeq
  let projections = null
  let reachedStart = false
  let reachedCursor = false
  let hitEventLimit = false
  let pageTruncated = false
  let pagesUsed = 0

  for (; pagesUsed < maxPages && collected.length < maxEvents; pagesUsed++) {
    const payload = { maxMessages: pageSize }
    if (Number.isInteger(beforeSeq) && beforeSeq > 0) payload.beforeSeq = beforeSeq
    const page = await fetchPage(payload)
    const events = page?.events ?? page?.records ?? []
    if (page?.projections?.values) projections = page.projections.values
    const room = maxEvents - collected.length
    if (room <= 0) {
      hitEventLimit = true
      break
    }
    if (events.length > room) {
      collected.push(...events.slice(0, room))
      pageTruncated = true
      hitEventLimit = true
      break
    }
    collected.push(...events)
    if (events.length === 0) {
      reachedStart = true
      break
    }
    const seqs = events.map((item) => item?.event?.seq).filter((n) => typeof n === "number")
    if (seqs.length === 0) {
      reachedStart = page?.hasMore === false
      break
    }
    const minSeq = Math.min(...seqs)
    if (minSeq <= afterSeq) {
      reachedCursor = true
      break
    }
    if (page?.hasMore === false) {
      reachedStart = true
      break
    }
    beforeSeq = minSeq
  }

  const events = sortedUniqueAfter(collected, afterSeq)
  const oldestSeq = events.length ? events[0].seq : null
  const newestSeq = events.length ? events[events.length - 1].seq : null
  const hitPageLimit = pagesUsed >= maxPages && !reachedCursor && !reachedStart && !hitEventLimit
  const attached = events.length === 0
    ? Boolean(reachedCursor || reachedStart || pagesUsed > 0)
    : oldestSeq === afterSeq + 1
  const contiguous = events.length === 0 || isConsecutive(events)
  let reason = null
  if (hitEventLimit || pageTruncated) reason = "hit-limit"
  else if (hitPageLimit) reason = "hit-limit"
  else if (!contiguous) reason = "discontiguous"
  else if (events.length > 0 && !attached && reachedStart) reason = "log-gap"
  else if (events.length > 0 && !attached) reason = "gap"
  const complete = attached && contiguous && !pageTruncated && !hitEventLimit && !hitPageLimit
    && (events.length === 0 || oldestSeq === afterSeq + 1)

  return {
    events,
    projections,
    complete,
    truncated: Boolean(hitEventLimit || hitPageLimit || pageTruncated),
    gap: reason === "log-gap" || reason === "gap" || reason === "discontiguous",
    reachedStart,
    reachedCursor,
    oldestAvailableSeq: oldestSeq,
    newestSeq,
    nextCursor: newestSeq ?? afterSeq,
    reason: complete ? null : (reason ?? "incomplete"),
    afterSeq,
  }
}

/**
 * 一次从 minCursor 拉取的批次，按某条连接自己的 afterSeq 判定能否连续交付。
 * 从尾部向前收集时，截断丢掉的是旧侧；比 newest 更新的连接可以视为已追上尾部。
 */
export function assessForCursor(afterSeq, batch) {
  const newest = batch?.newestSeq
  const projections = batch?.projections ?? null
  if (typeof newest !== "number") {
    return {
      events: [],
      complete: Boolean(batch?.complete || batch?.reachedStart || batch?.reachedCursor),
      truncated: Boolean(batch?.truncated),
      reason: batch?.complete ? null : (batch?.reason ?? "incomplete"),
      oldestAvailableSeq: batch?.oldestAvailableSeq ?? null,
      nextCursor: afterSeq,
      projections,
    }
  }
  if (afterSeq >= newest) {
    return {
      events: [],
      complete: true,
      truncated: false,
      reason: null,
      oldestAvailableSeq: batch.oldestAvailableSeq ?? null,
      nextCursor: afterSeq,
      projections,
    }
  }
  const events = (batch.events ?? []).filter((e) => e.seq > afterSeq)
  if (events.length === 0) {
    return {
      events: [],
      complete: false,
      truncated: Boolean(batch?.truncated),
      reason: batch?.reason ?? "gap",
      oldestAvailableSeq: batch?.oldestAvailableSeq ?? null,
      nextCursor: afterSeq,
      projections,
    }
  }
  if (events[0].seq !== afterSeq + 1 || !isConsecutive(events)) {
    return {
      events: [],
      complete: false,
      truncated: true,
      reason: events[0].seq !== afterSeq + 1 ? (batch?.reason ?? "hit-limit") : "discontiguous",
      oldestAvailableSeq: events[0].seq,
      nextCursor: afterSeq,
      projections,
    }
  }
  return {
    events,
    complete: true,
    truncated: false,
    reason: null,
    oldestAvailableSeq: events[0].seq,
    nextCursor: events[events.length - 1].seq,
    projections,
  }
}

export function sseMessageFrame(e) {
  return `event: message\ndata: ${JSON.stringify({ seq: e.seq, type: e.type, time: e.time, data: e.data })}\n\n`
}

export function sseNamedFrame(eventName, data) {
  return `event: ${eventName}\ndata: ${JSON.stringify(data)}\n\n`
}

export function sseResyncFrame({ sessionId, reason, afterSeq, oldestAvailableSeq, nextCursor }) {
  return sseNamedFrame("resync-required", {
    sessionId,
    reason: reason ?? "incomplete",
    afterSeq: afterSeq ?? 0,
    oldestAvailableSeq: oldestAvailableSeq ?? null,
    nextCursor: nextCursor ?? afterSeq ?? 0,
  })
}
