/**
 * 「从会话日志补全 reasoning」端到端回归。
 *
 * 2026-09-22 修复的 bug：DSH 的 `session*.jsonl.zstd` 逐帧追加，而单次
 * `zstdDecompressSync` 只解第一帧 → 文件侧只能读到会话头 → reasoning Map 恒为空。
 * 这里用「每行一帧」的真实容器形状，从压缩字节一路走到手机消息投影。
 */
import assert from "node:assert/strict"
import test from "node:test"
import { zstdCompressSync } from "node:zlib"
import { decompressZstdFrames } from "../src/zstd-frames.js"
import { reasoningBlocksFromSessionLog } from "../src/session-log-reasoning.js"
import { projectHistoryPage } from "../src/history.js"

const row = (value) => zstdCompressSync(Buffer.from(JSON.stringify(value) + "\n", "utf8"))

const chunk = (seq, text) => ({
  type: "assistant/chunk",
  seq,
  time: 1_700_000_000_000 + seq,
  data: { chunk: { type: "block-end", index: 0, block: { type: "reasoning", text } } },
})

/** 会话头一帧、之后每个事件一帧（DSH 的追加写形状）。 */
const logFrames = (...events) => Buffer.concat([
  row({ type: "session", version: 3, id: "session-x" }),
  ...events.map(row),
])

test("多帧日志能补出 reasoning 并按 flush 点分组", async () => {
  const buf = logFrames(
    chunk(1, "第一段"),
    chunk(2, "第二段"),
    { type: "tool/call", seq: 3, time: 1_700_000_000_003, data: { callId: "c1" } },
    chunk(4, "第二组"),
    { type: "assistant/message", seq: 5, time: 1_700_000_000_005, data: {} },
  )
  const text = (await decompressZstdFrames(buf)).toString("utf8")
  const map = reasoningBlocksFromSessionLog(text)
  assert.deepEqual([...map.keys()], [1, 4], "连续 reasoning 合并为一块，键取首块 seq")
  assert.equal(map.get(1).text, "第一段\n第二段")
  assert.equal(map.get(4).text, "第二组")
})

test("旧实现（只解第一帧）在这份日志上会拿到空 Map", async () => {
  const buf = logFrames(chunk(1, "思考正文"))
  // 单帧解码只覆盖第一个帧：正文在第二帧，读不到
  const firstFrameOnly = (await decompressZstdFrames(buf.subarray(0, buf.length), { maxOutputBytes: 1 << 20 }))
  assert.ok(firstFrameOnly.length > 0)
  const onlyHeader = Buffer.from(JSON.stringify({ type: "session", version: 3 }) + "\n")
  assert.equal(reasoningBlocksFromSessionLog(onlyHeader.toString("utf8")).size, 0)
})

test("文件侧补出的思考会进入手机消息投影", async () => {
  const buf = logFrames(
    chunk(1, "第一段思考"),
    { type: "tool/call", seq: 2, time: 1_700_000_000_002, data: { callId: "c1" } },
    chunk(3, "第二段思考"),
    { type: "assistant/message", seq: 4, time: 1_700_000_000_004, data: { message: { role: "assistant", content: [{ type: "text", text: "答复" }] } } },
  )
  const reasoningBySeq = reasoningBlocksFromSessionLog((await decompressZstdFrames(buf)).toString("utf8"))
  const events = [
    { event: { seq: 0, type: "user/message", time: 1_700_000_000_000, data: { content: [{ type: "text", text: "问题" }] } } },
    { event: { seq: 2, type: "tool/call", time: 1_700_000_000_002, data: { callId: "c1", name: "read", arguments: "{}" } } },
    { event: { seq: 4, type: "assistant/message", time: 1_700_000_000_004, data: { message: { role: "assistant", content: [{ type: "text", text: "答复" }] } } } },
  ]
  const { messages } = projectHistoryPage({ events, reasoningBySeq, hasMore: false })
  const texts = messages.filter((m) => m.role === "reasoning").map((m) => m.text)
  assert.deepEqual(texts, ["第一段思考", "第二段思考"])
  // 思考块仍插在对应的助手答复之前
  const lastReasoning = messages.findLastIndex((m) => m.role === "reasoning")
  const answer = messages.findIndex((m) => m.text === "答复")
  assert.ok(lastReasoning < answer)
})
