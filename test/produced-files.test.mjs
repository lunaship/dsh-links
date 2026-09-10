import assert from "node:assert/strict"
import test from "node:test"
import { mkdirSync, writeFileSync, rmSync } from "node:fs"
import { tmpdir } from "node:os"
import { join } from "node:path"
import { mutationPath, toolResultIsError, uniquePaths } from "../src/produced-files.js"
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
