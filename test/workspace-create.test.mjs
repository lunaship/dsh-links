import { test } from "node:test"
import assert from "node:assert/strict"
import { mkdtemp, mkdir, rm, writeFile } from "node:fs/promises"
import { join } from "node:path"
import { tmpdir } from "node:os"
import {
  MobileWorkspaceCreateError,
  ensureMobileWorkspaceDirectory,
  planMobileWorkspaceCreate,
} from "../src/workspace-create.js"

const anchor = (path) => [{ workspaceId: "ws-current", path }]

test("绝对路径保持注册已有目录语义，不创建目录", () => {
  const plan = planMobileWorkspaceCreate({
    input: "/Volumes/Space/Dev/existing",
    parentWorkspaceId: "ws-current",
    workspaces: anchor("/Volumes/Space/Dev/current"),
  })
  assert.deepEqual(plan, {
    path: "/Volumes/Space/Dev/existing",
    inputKind: "absolute-path",
    shouldCreateDirectory: false,
    parentWorkspaceId: null,
  })
})

test("单层名称解析为当前工作区的同级目录", () => {
  const plan = planMobileWorkspaceCreate({
    input: " space ",
    parentWorkspaceId: "ws-current",
    workspaces: anchor("/Volumes/Space/Dev/dsh-links-relay"),
  })
  assert.equal(plan.path, "/Volumes/Space/Dev/space")
  assert.equal(plan.inputKind, "name")
  assert.equal(plan.shouldCreateDirectory, true)
})

test("名称模式拒绝路径穿越和多层相对路径", () => {
  for (const input of ["..", ".", "../escape", "nested/child", "nested\\child"]) {
    assert.throws(
      () => planMobileWorkspaceCreate({ input, parentWorkspaceId: "ws-current", workspaces: anchor("/tmp/current") }),
      (error) => error instanceof MobileWorkspaceCreateError && error.code === "workspace-invalid-name",
      input,
    )
  }
})

test("名称模式拒绝控制字符", () => {
  for (const input of ["bad\u0000name", "bad\u001fname", "bad\u007fname"]) {
    assert.throws(
      () => planMobileWorkspaceCreate({ input, parentWorkspaceId: "ws-current", workspaces: anchor("/tmp/current") }),
      (error) => error instanceof MobileWorkspaceCreateError && error.code === "workspace-invalid-name",
      JSON.stringify(input),
    )
  }
})

test("多个工作区时名称模式必须提供有效锚点", () => {
  const workspaces = [
    { workspaceId: "one", path: "/tmp/one" },
    { workspaceId: "two", path: "/tmp/two" },
  ]
  assert.throws(
    () => planMobileWorkspaceCreate({ input: "space", workspaces }),
    (error) => error.code === "workspace-parent-required",
  )
  assert.throws(
    () => planMobileWorkspaceCreate({ input: "space", parentWorkspaceId: "gone", workspaces }),
    (error) => error.code === "workspace-parent-not-found" && error.status === 404,
  )
})

test("名称模式创建目录；已有目录可直接注册", async () => {
  const root = await mkdtemp(join(tmpdir(), "dsh-links-workspace-"))
  try {
    const current = join(root, "current")
    await mkdir(current)
    const plan = planMobileWorkspaceCreate({
      input: "space",
      parentWorkspaceId: "ws-current",
      workspaces: anchor(current),
    })
    assert.deepEqual(await ensureMobileWorkspaceDirectory(plan), { directoryCreated: true })
    assert.deepEqual(await ensureMobileWorkspaceDirectory(plan), { directoryCreated: false })
  } finally {
    await rm(root, { recursive: true, force: true })
  }
})

test("名称模式拒绝覆盖同名文件", async () => {
  const root = await mkdtemp(join(tmpdir(), "dsh-links-workspace-"))
  try {
    const current = join(root, "current")
    await mkdir(current)
    await writeFile(join(root, "space"), "not a directory")
    const plan = planMobileWorkspaceCreate({
      input: "space",
      parentWorkspaceId: "ws-current",
      workspaces: anchor(current),
    })
    await assert.rejects(
      () => ensureMobileWorkspaceDirectory(plan),
      (error) => error.code === "workspace-name-conflict" && error.status === 409,
    )
  } finally {
    await rm(root, { recursive: true, force: true })
  }
})
