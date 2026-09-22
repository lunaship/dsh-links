/**
 * 多帧 zstd 解压测试（DSH 会话日志按追加逐帧写）。
 *
 * 回归点：Node 的 `zstdDecompressSync` / `createZstdDecompress` 只解第一帧，
 * 所以「单次调用」版本在真实会话文件上只拿到会话头，reasoning 补全静默失效。
 * 这些用例在旧实现下会失败。
 */
import assert from "node:assert/strict"
import test from "node:test"
import { zstdCompressSync } from "node:zlib"
import { decompressZstdFrames } from "../src/zstd-frames.js"

const frame = (text) => zstdCompressSync(Buffer.from(text, "utf8"))

test("多帧拼接全部解压，不只是第一帧", async () => {
  const buf = Buffer.concat([frame("第一帧\n"), frame("第二帧\n"), frame("第三帧\n")])
  assert.equal((await decompressZstdFrames(buf)).toString("utf8"), "第一帧\n第二帧\n第三帧\n")
})

test("单帧行为不变", async () => {
  assert.equal((await decompressZstdFrames(frame("only"))).toString("utf8"), "only")
})

test("空缓冲返回空", async () => {
  assert.equal((await decompressZstdFrames(Buffer.alloc(0))).length, 0)
})

test("会话日志形态：逐帧写的 jsonl 行全部还原且保序", async () => {
  const header = { type: "session", version: 3, id: "session-x" }
  const rows = [header, { type: "user/message", seq: 0 }, { type: "assistant/chunk", seq: 1 }]
  const buf = Buffer.concat(rows.map((r) => frame(JSON.stringify(r) + "\n")))
  const lines = (await decompressZstdFrames(buf)).toString("utf8").split("\n").filter(Boolean)
  assert.equal(lines.length, 3)
  assert.deepEqual(lines.map((l) => JSON.parse(l).type), ["session", "user/message", "assistant/chunk"])
})

test("zstd 容器之后跟非 zstd 字节：返回已解出的前缀，不抛", async () => {
  const buf = Buffer.concat([frame("head\n"), Buffer.from("这不是 zstd 数据", "utf8")])
  assert.equal((await decompressZstdFrames(buf)).toString("utf8"), "head\n")
})

test("完全不是 zstd：返回空而不是抛错", async () => {
  assert.equal((await decompressZstdFrames(Buffer.from("plain text", "utf8"))).length, 0)
})

test("累计输出超过上限抛错（防压缩炸弹）", async () => {
  const buf = Buffer.concat([frame("a".repeat(4096)), frame("b".repeat(4096))])
  await assert.rejects(
    () => decompressZstdFrames(buf, { maxOutputBytes: 1024 }),
    /exceeds 1024 bytes/,
  )
  // 上限够大时正常返回
  assert.equal((await decompressZstdFrames(buf, { maxOutputBytes: 1 << 20 })).length, 8192)
})

test("帧数较多时完整覆盖（模拟长会话的数十次追加）", async () => {
  const count = 64
  const buf = Buffer.concat(Array.from({ length: count }, (_, i) => frame(`row-${i}\n`)))
  const out = (await decompressZstdFrames(buf)).toString("utf8").split("\n").filter(Boolean)
  assert.equal(out.length, count)
  assert.equal(out[0], "row-0")
  assert.equal(out[count - 1], `row-${count - 1}`)
})
