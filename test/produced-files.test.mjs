import assert from "node:assert/strict"
import test from "node:test"
import { mkdirSync, writeFileSync, rmSync } from "node:fs"
import { tmpdir } from "node:os"
import { join } from "node:path"
import { mutationPath, toolResultContent, toolResultIsError, toolResultMeta, uniquePaths } from "../src/produced-files.js"
import { mimeFromName, resolveWorkspaceFile } from "../src/workspace-file.js"

test("mutationPath 只收录成功变更型工具的路径", () => {
  assert.equal(
    mutationPath("write", JSON.stringify({ file_path: "out/a.md", content: "hi" })),
    "out/a.md",
  )
  assert.equal(mutationPath("write", JSON.stringify({ file_path: "out/a.md" })), null)
  assert.equal(mutationPath("read", JSON.stringify({ file_path: "out/a.md" })), null)
  assert.equal(
    mutationPath("str_replace_editor", JSON.stringify({
      command: "create",
      path: "out/b.ts",
      file_text: "x",
    })),
    "out/b.ts",
  )
})

test("toolResultIsError 仅在 isError true 时排除", () => {
  assert.equal(toolResultIsError({ data: { message: { content: [{ isError: true }] } } }), true)
  assert.equal(toolResultIsError({ data: { message: { content: [{ text: "ok" }] } } }), false)
})

// DSH 0.1.7 起 session 日志升 V4：tool/result 从「user 角色 + tool-result 包装块」
// 变成一等 tool 角色消息（toolCallId / isError 提到 message 上，content 直接是内层数组）。
// 同一个历史窗口里可能同时有 v3 与 v4 会话，两种形状都必须认。
const v3ToolResult = (isError) => ({
  data: {
    message: {
      role: "user",
      source: { kind: "tool", callId: "c1" },
      content: [{
        type: "tool-result",
        toolCallId: "c1",
        content: [{ type: "text", text: "ok" }],
        ...(isError === undefined ? {} : { isError }),
      }],
    },
  },
})

const v4ToolResult = (isError) => ({
  data: {
    message: {
      role: "tool",
      source: { kind: "tool", callId: "c1" },
      toolCallId: "c1",
      content: [{ type: "text", text: "ok" }],
      ...(isError === undefined ? {} : { isError }),
    },
  },
})

test("toolResultMeta 同时认 V3 包装块与 V4 一等 tool 消息", () => {
  assert.deepEqual(toolResultMeta(v3ToolResult()), { callId: "c1", isError: false })
  assert.deepEqual(toolResultMeta(v3ToolResult(true)), { callId: "c1", isError: true })
  assert.deepEqual(toolResultMeta(v4ToolResult()), { callId: "c1", isError: false })
  assert.deepEqual(toolResultMeta(v4ToolResult(true)), { callId: "c1", isError: true })
  assert.equal(toolResultMeta({ data: {} }), null)
  // V3 没有 source.callId 时退回包装块上的 toolCallId
  const orphan = v3ToolResult()
  delete orphan.data.message.source
  assert.equal(toolResultMeta(orphan).callId, "c1")
  // V4 的 source 是保留字段，但 toolCallId 才是主键：去掉 source 仍能关联
  const lifted = v4ToolResult()
  delete lifted.data.message.source
  assert.deepEqual(toolResultMeta(lifted), { callId: "c1", isError: false })
})

test("toolResultIsError 对 V4 的 message.isError 生效", () => {
  assert.equal(toolResultIsError(v4ToolResult(true)), true)
  assert.equal(toolResultIsError(v4ToolResult(false)), false)
  assert.equal(toolResultIsError(v3ToolResult(true)), true)
})

test("toolResultContent 对 V3 包装块取内层正文，对 V4 直取", () => {
  assert.deepEqual(toolResultContent(v3ToolResult()), [{ type: "text", text: "ok" }])
  assert.deepEqual(toolResultContent(v4ToolResult()), [{ type: "text", text: "ok" }])
  // 非包装块（历史遗留的扁平形状）原样返回
  assert.deepEqual(
    toolResultContent({ data: { message: { content: [{ type: "text", text: "flat" }] } } }),
    [{ type: "text", text: "flat" }],
  )
  assert.deepEqual(toolResultContent({ data: {} }), [])
})

test("uniquePaths 保序去重", () => {
  assert.deepEqual(uniquePaths([{ path: "a" }, { path: "b" }, { path: "a" }]), ["a", "b"])
})

test("resolveWorkspaceFile 拒绝越出 cwd", () => {
  const root = join(tmpdir(), `dsh-ws-file-${Date.now()}`)
  mkdirSync(root)
  writeFileSync(join(root, "ok.txt"), "ok")
  try {
    const inside = resolveWorkspaceFile(root, "ok.txt")
    assert.equal(inside.name, "ok.txt")
    assert.throws(() => resolveWorkspaceFile(root, "../secret"), { status: 403 })
    assert.equal(mimeFromName("shot.png"), "image/png")
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
})
