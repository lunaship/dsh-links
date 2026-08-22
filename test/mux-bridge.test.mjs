/**
 * mux `session/event` 直推：连号即时下发，空洞交给 pollSession 补，
 * 补历史未完成的连接不接推送（避免与补发交错乱序）。
 */
import { test } from "node:test"
import assert from "node:assert/strict"
import { handleMuxBlock, flushSeedQueue } from "../src/question-bridge.js"

function muxBlock(payload, rpcId = "rpc-1") {
  return `data: ${JSON.stringify({ rpcId, payload })}`
}

function sessionEventBlock(sessionId, seq, type = "assistant/chunk") {
  return muxBlock({ type: "session/event", sessionId, event: { seq, type, time: seq, data: { seq } } })
}

function fakeConn(lastSeq, { seeded = true } = {}) {
  const written = []
  return {
    written,
    conn: {
      seeded,
      missedWhileSeeding: false,
      lastSeq,
      res: {
        write(frame) {
          written.push(frame)
          return true
        },
        destroy() {},
      },
    },
  }
}

function runtimeWith(sessionId, conns) {
  return { sessionStreams: new Map([[sessionId, new Set(conns)]]) }
}

test("连号 session/event 立即下推并推进游标", () => {
  const a = fakeConn(7)
  const rt = runtimeWith("s1", [a.conn])
  const polled = []
  handleMuxBlock(sessionEventBlock("s1", 8), rt, null, (id) => polled.push(id))
  assert.equal(a.written.length, 1)
  assert.match(a.written[0], /^event: message\n/)
  assert.deepEqual(JSON.parse(a.written[0].slice(a.written[0].indexOf("data: ") + 6)), {
    seq: 8,
    type: "assistant/chunk",
    time: 8,
    data: { seq: 8 },
  })
  assert.equal(a.conn.lastSeq, 8)
  assert.deepEqual(polled, [])
})

test("跳号不下推，改为请求补洞（否则被跳过的事件永远补不回来）", () => {
  const a = fakeConn(7)
  const rt = runtimeWith("s1", [a.conn])
  const polled = []
  handleMuxBlock(sessionEventBlock("s1", 10), rt, null, (id) => polled.push(id))
  assert.equal(a.written.length, 0)
  assert.equal(a.conn.lastSeq, 7)
  assert.deepEqual(polled, ["s1"])
})

test("已下发过的 seq 不重复下推，也不触发补洞", () => {
  const a = fakeConn(9)
  const rt = runtimeWith("s1", [a.conn])
  const polled = []
  handleMuxBlock(sessionEventBlock("s1", 9), rt, null, (id) => polled.push(id))
  handleMuxBlock(sessionEventBlock("s1", 4), rt, null, (id) => polled.push(id))
  assert.equal(a.written.length, 0)
  assert.equal(a.conn.lastSeq, 9)
  assert.deepEqual(polled, [])
})

test("补历史未完成的连接把事件排队，不立刻下推也不补洞", () => {
  const a = fakeConn(0, { seeded: false })
  const rt = runtimeWith("s1", [a.conn])
  const polled = []
  handleMuxBlock(sessionEventBlock("s1", 1), rt, null, (id) => polled.push(id))
  assert.equal(a.written.length, 0)
  assert.equal(a.conn.missedWhileSeeding, true)
  assert.equal(a.conn.seedQueue.length, 1)
  assert.equal(a.conn.seedQueue[0].seq, 1)
  assert.deepEqual(polled, [])
})

test("多连接各自独立：连号的下推，落后的触发补洞", () => {
  const near = fakeConn(7)
  const behind = fakeConn(2)
  const rt = runtimeWith("s1", [near.conn, behind.conn])
  const polled = []
  handleMuxBlock(sessionEventBlock("s1", 8), rt, null, (id) => polled.push(id))
  assert.equal(near.conn.lastSeq, 8)
  assert.equal(near.written.length, 1)
  assert.equal(behind.conn.lastSeq, 2)
  assert.equal(behind.written.length, 0)
  assert.deepEqual(polled, ["s1"])
})

test("无订阅者 / 非本会话 / 坏帧一律忽略", () => {
  const a = fakeConn(7)
  const rt = runtimeWith("s1", [a.conn])
  const polled = []
  const push = (id) => polled.push(id)
  handleMuxBlock(sessionEventBlock("other", 8), rt, null, push)
  handleMuxBlock("data: not json", rt, null, push)
  handleMuxBlock(": keepalive", rt, null, push)
  handleMuxBlock(muxBlock({ type: "session/event", sessionId: "s1" }), rt, null, push)
  handleMuxBlock(muxBlock({ type: "session/jobs", sessionId: "s1", jobs: [] }), rt, null, push)
  assert.equal(a.written.length, 0)
  assert.deepEqual(polled, [])
})

test("补历史结束后把排队事件按序补发", () => {
  const a = fakeConn(0, { seeded: false })
  const rt = runtimeWith("s1", [a.conn])
  handleMuxBlock(sessionEventBlock("s1", 1), rt, null, () => {})
  handleMuxBlock(sessionEventBlock("s1", 2), rt, null, () => {})
  const polled = []
  flushSeedQueue(a.conn, "s1", (id) => polled.push(id))
  assert.equal(a.conn.seeded, true)
  assert.equal(a.written.length, 2)
  assert.equal(a.conn.lastSeq, 2)
  assert.deepEqual(polled, [])
})

test("question 帧仍走原澄清卡路径", () => {
  const a = fakeConn(7)
  const rt = runtimeWith("s1", [a.conn])
  handleMuxBlock(
    muxBlock({ type: "question/requested", sessionId: "s1", questions: [{ id: "q1", question: "ok?" }] }, "rpc-9"),
    rt,
    null,
    () => {},
  )
  assert.equal(a.written.length, 1)
  assert.match(a.written[0], /^event: question\n/)
  // 澄清卡不带 seq，不得推进 SSE 游标
  assert.equal(a.conn.lastSeq, 7)
})
