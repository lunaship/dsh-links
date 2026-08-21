/**
 * SSE 游标分页：跨页 >50 条不丢；两路 afterSeq 互不影响。
 */
import { test } from "node:test"
import assert from "node:assert/strict"
import { loadEventsAfter, MAX_CATCHUP_EVENTS } from "../src/stream-cursor.js"

function page(events, hasMore) {
  return { events: events.map((seq) => ({ event: { seq, type: "x", time: seq, data: {} } })), hasMore }
}

test("单轮超过 50 条时分页追上 afterSeq，无跳号", async () => {
  const pages = [
    page(Array.from({ length: 50 }, (_, i) => 51 + i), true), // 51..100 tail
    page(Array.from({ length: 50 }, (_, i) => 1 + i), false), // 1..50
  ]
  let i = 0
  const { events } = await loadEventsAfter(0, async () => pages[i++])
  assert.equal(events.length, 100)
  assert.equal(events[0].seq, 1)
  assert.equal(events[99].seq, 100)
})

test("两个连接的 afterSeq 独立", async () => {
  const all = page(Array.from({ length: 10 }, (_, i) => i + 1), false)
  const a = await loadEventsAfter(2, async () => all)
  const b = await loadEventsAfter(8, async () => all)
  assert.deepEqual(a.events.map((e) => e.seq), [3, 4, 5, 6, 7, 8, 9, 10])
  assert.deepEqual(b.events.map((e) => e.seq), [9, 10])
})

test("补发事件数有硬顶", async () => {
  const pages = [
    page(Array.from({ length: 50 }, (_, i) => 51 + i), true),
    page(Array.from({ length: 50 }, (_, i) => 1 + i), false),
  ]
  let i = 0
  const { events } = await loadEventsAfter(0, async () => pages[i++], { maxEvents: 60, maxPages: 10 })
  assert.equal(events.length, 60)
  assert.ok(events.length <= MAX_CATCHUP_EVENTS)
})
