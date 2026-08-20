/**
 * 历史投影契约测试（WI-001）：reasoning 只属于覆盖其 sequence 范围的页面；
 * 消息 id 以事件 seq 为键，跨页稳定；assistant/message 与同页 text block-end 去重。
 * 运行：node --test dsh-links/test/history.test.mjs
 */
import { test } from "node:test"
import assert from "node:assert/strict"
import { projectHistoryPage } from "../src/history.js"

const T = 1_700_000_000_000

function ev(seq, type, data, extra = {}) {
  return { event: { seq, type, time: T + seq, data, ...extra } }
}

function userMsg(seq, text) {
  return ev(seq, "user/message", { content: [{ type: "text", text }] })
}

function blockEnd(seq, blockType, text = "") {
  return ev(seq, "assistant/chunk", { chunk: { type: "block-end", index: 0, block: { type: blockType, text } } })
}

function assistantMsg(seq, text, sourceEventSeqs) {
  return ev(seq, "assistant/message", { message: { role: "assistant", content: [{ type: "text", text }] } }, { sourceEventSeqs })
}

function toolCall(seq, callId, name) {
  return ev(seq, "tool/call", { callId, name, arguments: "{}", turn: 1, step: 1 })
}

function toolResult(seq, callId) {
  return ev(seq, "tool/result", { turn: 1, step: 1, message: { source: { callId }, content: [{ type: "text", text: "ok" }] } })
}

function todoWrite(seq, items) {
  return ev(seq, "todo/write", { todos: items })
}

test("runtime context user message projects as context_injection", () => {
  const text = "Current runtime context:\n- Host OS: macOS\n- Current DSH file policy: danger-full-access"
  const { messages } = projectHistoryPage({
    events: [userMsg(10, text), blockEnd(11, "text", "ok"), assistantMsg(12, "ok", [11])],
    reasoningBySeq: new Map(),
    hasMore: false,
  })
  assert.equal(messages[0].role, "context_injection")
  assert.equal(messages[1].role, "assistant")
})

test("plain user message stays user role", () => {
  const { messages } = projectHistoryPage({
    events: [userMsg(10, "测试")],
    reasoningBySeq: new Map(),
    hasMore: false,
  })
  assert.equal(messages[0].role, "user")
})

test("尾页只合并本页 seq 窗口内的 reasoning，页外早期 reasoning 不混入", () => {
  // 页窗口 [100, 103]；文件里 reasoning 块 seq=50（早期）与 seq=101（本页）
  const events = [
    userMsg(100, "问题"),
    blockEnd(101, "reasoning"), // 投影剥掉文本，需文件补全
    blockEnd(102, "text", "回答"),
    assistantMsg(103, "回答", [102]),
  ]
  const reasoningBySeq = new Map([
    [50, { seq: 50, time: T + 50, text: "早期思考（不应出现）" }],
    [101, { seq: 101, time: T + 101, text: "本页思考" }],
  ])
  const { messages } = projectHistoryPage({ events, reasoningBySeq, hasMore: false })

  const ids = messages.map((m) => m.id)
  assert.deepEqual(ids, ["msg-100", "reason-101", "msg-102"])
  assert.equal(messages[1].role, "reasoning")
  assert.equal(messages[1].text, "本页思考")
  assert.equal(messages[1].seq, 101)
  assert.ok(!messages.some((m) => m.text.includes("早期思考")))
})

test("assistant/message 文本块与同页 text block-end 共享 id（按 sourceEventSeqs 定位）", () => {
  const events = [
    blockEnd(201, "text", "第一段"),
    blockEnd(203, "text", "第二段"),
    assistantMsg(204, "第一段", [200, 201]),
    assistantMsg(205, "第二段", [202, 203]),
  ]
  const { messages } = projectHistoryPage({ events, reasoningBySeq: new Map(), hasMore: false })
  const texts = messages.map((m) => `${m.id}:${m.text}`)
  assert.deepEqual(texts, ["msg-201:第一段", "msg-203:第二段"])
})

test("连续 reasoning 块追加而非覆盖，id 用首块 seq", () => {
  const events = [
    blockEnd(10, "reasoning", "第一段思考"),
    blockEnd(11, "reasoning", "第二段思考"),
    blockEnd(12, "text", "回答"),
  ]
  const { messages } = projectHistoryPage({ events, reasoningBySeq: new Map(), hasMore: false })
  const reasons = messages.filter((m) => m.role === "reasoning")
  assert.equal(reasons.length, 1)
  assert.equal(reasons[0].id, "reason-10")
  assert.equal(reasons[0].seq, 10)
  assert.equal(reasons[0].text, "第一段思考\n第二段思考")
})

test("投影已带文本的 reasoning（子会话未剥除）不再从文件重复合并", () => {
  const events = [
    blockEnd(301, "reasoning", "事件自带思考"),
    blockEnd(302, "text", "回答"),
  ]
  const reasoningBySeq = new Map([[301, { seq: 301, time: T + 301, text: "文件重复思考（不应出现）" }]])
  const { messages } = projectHistoryPage({ events, reasoningBySeq, hasMore: false })
  assert.deepEqual(messages.map((m) => m.id), ["reason-301", "msg-302"])
  assert.equal(messages.filter((m) => m.role === "reasoning").length, 1)
  assert.equal(messages[0].text, "事件自带思考")
})

test("更早页（beforeSeq 窗口）只带窗口内的 reasoning，且按 seq 插在正确位置", () => {
  // 更早页窗口 [400, 405]；reasoning 401 属于本页（其文本 402 也在本页）；reasoning 500 属于更晚页，排除
  const events = [
    userMsg(400, "旧问题"),
    blockEnd(401, "reasoning"),
    blockEnd(402, "text", "旧回答"),
    assistantMsg(405, "旧回答", [402]),
  ]
  const reasoningBySeq = new Map([
    [401, { seq: 401, time: T + 401, text: "旧思考" }],
    [500, { seq: 500, time: T + 500, text: "更新页思考（不应出现在本页）" }],
  ])
  const { messages } = projectHistoryPage({ events, reasoningBySeq, hasMore: true })
  assert.deepEqual(messages.map((m) => m.id), ["msg-400", "reason-401", "msg-402"])
  assert.equal(messages[1].text, "旧思考")
})

test("页面最早事件是 reasoning 且其文本在更晚页时，reasoning 落在页首（其实际位置之上）", () => {
  const events = [
    blockEnd(601, "reasoning"), // 页窗口第一事件；文本 block-end 602 在本页之外（更晚页）
    userMsg(610, "问题"),
  ]
  const reasoningBySeq = new Map([[601, { seq: 601, time: T + 601, text: "跨页思考" }]])
  const { messages } = projectHistoryPage({ events, reasoningBySeq, hasMore: false })
  assert.deepEqual(messages.map((m) => m.id), ["reason-601", "msg-610"])
})

test("工具事件、todo、compaction 保持稳定 id 与顺序", () => {
  const events = [
    userMsg(700, "做一下"),
    toolCall(701, "call-1", "bash"),
    toolResult(702, "call-1"),
    todoWrite(703, [{ content: "第一步", status: "pending" }]),
    blockEnd(704, "reasoning"),
    blockEnd(705, "text", "结果"),
    ev(706, "compaction/start", {}),
    ev(707, "compaction/summary", { summary: [{ type: "text", text: "摘要" }] }),
  ]
  const reasoningBySeq = new Map([[704, { seq: 704, time: T + 704, text: "思考" }]])
  const { messages } = projectHistoryPage({ events, reasoningBySeq, hasMore: false })
  assert.deepEqual(
    messages.map((m) => m.id),
    ["msg-700", "tool-701", "tool-res-702", "todo-703", "reason-704", "msg-705", "compact-706"],
  )
  const compact = messages.find((m) => m.role === "compaction")
  assert.equal(compact.text, "摘要")
  assert.equal(compact.running, false)
  // 全部消息必须带稳定 seq
  for (const m of messages) assert.equal(typeof m.seq, "number")
})

test("重复请求同一页：结果与 id 完全一致（幂等）", () => {
  const events = [
    userMsg(800, "问题"),
    blockEnd(801, "reasoning"),
    blockEnd(802, "text", "回答"),
  ]
  const reasoningBySeq = new Map([[801, { seq: 801, time: T + 801, text: "思考" }]])
  const a = projectHistoryPage({ events, reasoningBySeq, hasMore: true })
  const b = projectHistoryPage({ events, reasoningBySeq, hasMore: true })
  assert.deepEqual(a, b)
})

test("缺少可选字段时响应仍稳定（无 sourceEventSeqs / 空 events / reasoning 无文本）", () => {
  const noSource = [
    blockEnd(900, "text", "回答"),
    assistantMsg(901, "回答"), // 无 sourceEventSeqs
  ]
  const { messages: m1 } = projectHistoryPage({ events: noSource, reasoningBySeq: new Map(), hasMore: false })
  assert.equal(m1.length, 2) // 保留事件顺序，不抛错

  const { messages: m2, hasMore: h2, nextBeforeSeq: n2, maxSeq: x2 } = projectHistoryPage({
    events: [], reasoningBySeq: new Map(), hasMore: false,
  })
  assert.equal(m2.length, 0)
  assert.equal(h2, false)
  assert.equal(n2, null)
  assert.equal(x2, null)

  // 文件里 reasoning 无文本（压缩已丢弃）：跳过，不产出空壳 reasoning
  const emptyText = [blockEnd(910, "reasoning"), blockEnd(911, "text", "回答")]
  const { messages: m3 } = projectHistoryPage({
    events: emptyText,
    reasoningBySeq: new Map([[910, { seq: 910, time: T + 910, text: "" }]]),
    hasMore: false,
  })
  assert.deepEqual(m3.map((m) => m.id), ["msg-911"])
})
