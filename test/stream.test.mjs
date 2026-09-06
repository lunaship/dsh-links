/**
 * SSE 游标分页：跨页 >50 条不丢；两路 afterSeq 互不影响。
 * 超过补发上限必须报告不完整，不得把尾部当连续补发。
 */
import { test } from "node:test"
import assert from "node:assert/strict"
import {
  loadEventsAfter,
  assessForCursor,
  MAX_CATCHUP_EVENTS,
  MAX_CATCHUP_PAGES,
} from "../src/stream-cursor.js"

function page(events, hasMore) {
  return { events: events.map((seq) => ({ event: { seq, type: "x", time: seq, data: {} } })), hasMore }
}

function pagesFromRange(oldest, newest, pageSize = 50) {
  const seqs = []
  for (let seq = newest; seq >= oldest; seq--) seqs.push(seq)
  const pages = []
  for (let i = 0; i < seqs.length; i += pageSize) {
    const chunk = seqs.slice(i, i + pageSize)
    const minSeq = Math.min(...chunk)
    pages.push(page(chunk, minSeq > oldest))
  }
  return pages
}

async function catchup(afterSeq, oldest, newest, opts) {
  const pages = pagesFromRange(oldest, newest)
  let i = 0
  return loadEventsAfter(afterSeq, async () => pages[i++] ?? page([], false), opts)
}

test("单轮超过 50 条时分页追上 afterSeq，无跳号", async () => {
  const { events, complete } = await catchup(0, 1, 100)
  assert.equal(complete, true)
  assert.equal(events.length, 100)
  assert.equal(events[0].seq, 1)
  assert.equal(events[99].seq, 100)
})

test("两个连接的 afterSeq 独立", async () => {
  const all = page(Array.from({ length: 10 }, (_, i) => i + 1), false)
  const a = await loadEventsAfter(2, async () => all)
  const b = await loadEventsAfter(8, async () => all)
  assert.equal(a.complete, true)
  assert.equal(b.complete, true)
  assert.deepEqual(a.events.map((e) => e.seq), [3, 4, 5, 6, 7, 8, 9, 10])
  assert.deepEqual(b.events.map((e) => e.seq), [9, 10])
})

test("补发事件数有硬顶且不得把截断尾部当完整", async () => {
  const pages = pagesFromRange(1, 100)
  let i = 0
  const batch = await loadEventsAfter(0, async () => pages[i++], { maxEvents: 60, maxPages: 10 })
  assert.equal(batch.complete, false)
  assert.equal(batch.truncated, true)
  assert.ok(batch.events.length <= MAX_CATCHUP_EVENTS)
  assert.notEqual(batch.events[0]?.seq, 1)
})

test("0/1/50/499/500 连续事件完整", async () => {
  for (const n of [0, 1, 50, 499, 500]) {
    const batch = n === 0
      ? await loadEventsAfter(0, async () => page([], false))
      : await catchup(0, 1, n)
    assert.equal(batch.complete, true, `n=${n}`)
    assert.equal(batch.events.length, n, `n=${n}`)
  }
})

test("501 与 1200 个待同步事件必须进入不完整/重同步", async () => {
  const over = await catchup(0, 1, 501)
  assert.equal(over.complete, false)
  assert.equal(over.truncated, true)
  assert.ok(over.events.length <= MAX_CATCHUP_EVENTS)

  const far = await catchup(100, 1, 1200)
  assert.equal(far.complete, false)
  assert.equal(far.truncated, true)
  assert.equal(far.events[0]?.seq, 701)
  const forCursor = assessForCursor(100, far)
  assert.equal(forCursor.complete, false)
  assert.equal(forCursor.events.length, 0)
  assert.notEqual(forCursor.oldestAvailableSeq, 101)
})

test("afterSeq=100 最新=1200 时靠近尾部的连接仍可连续交付", async () => {
  const batch = await catchup(100, 1, 1200)
  const near = assessForCursor(700, batch)
  assert.equal(near.complete, true)
  assert.equal(near.events[0].seq, 701)
  assert.equal(near.events.at(-1).seq, 1200)
  const caughtUp = assessForCursor(1200, batch)
  assert.equal(caughtUp.complete, true)
  assert.equal(caughtUp.events.length, 0)
})

test("日志起点晚于游标视为缺口", async () => {
  const batch = await catchup(100, 701, 800)
  assert.equal(batch.complete, false)
  assert.equal(batch.reason, "log-gap")
  assert.equal(assessForCursor(100, batch).complete, false)
})

test("页数上限与默认 10 页一致", async () => {
  const batch = await catchup(0, 1, MAX_CATCHUP_PAGES * 50 + 1)
  assert.equal(batch.complete, false)
  assert.equal(batch.truncated, true)
})
