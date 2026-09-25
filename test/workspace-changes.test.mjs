/**
 * 本轮改动文件投影：摘要 / 对比裁剪、坐标解析、历史内嵌与同轮取代。
 * 运行：node --test test/workspace-changes.test.mjs
 */
import { test } from "node:test"
import assert from "node:assert/strict"
import {
  MAX_DIFF_LINES,
  parseChangesCoordinates,
  projectChangesSummary,
  projectFileDiff,
  workspaceChangesService,
} from "../src/workspace-changes.js"
import { projectHistoryPage } from "../src/history.js"
import { pluginCapabilities } from "../src/protocol-caps.js"

const T = 1_700_000_000_000

function ev(seq, type, data) {
  return { event: { seq, type, time: T + seq, data } }
}

const summary = {
  turn: 3,
  cwd: "/Users/me/proj",
  files: [
    { path: "../up.txt", display: "../up.txt", added: 1, deleted: 0 },
    { path: "src/a.ts", display: "src/a.ts", added: 12, deleted: 3 },
    { path: "img.png", display: "img.png", added: 0, deleted: 0, binary: true },
    { path: "dist/big.map", display: "dist/big.map", added: 0, deleted: 0, oversized: true },
  ],
  total: 4,
  added: 13,
  deleted: 3,
  snapshot: { before: "aaa", after: "bbb" },
}

test("summary keeps order, flags and totals but drops cwd and snapshot ids", () => {
  const out = projectChangesSummary(summary)
  assert.deepEqual(out, {
    turn: 3,
    total: 4,
    added: 13,
    deleted: 3,
    files: [
      { path: "../up.txt", display: "../up.txt", added: 1, deleted: 0 },
      { path: "src/a.ts", display: "src/a.ts", added: 12, deleted: 3 },
      { path: "img.png", display: "img.png", added: 0, deleted: 0, binary: true },
      { path: "dist/big.map", display: "dist/big.map", added: 0, deleted: 0, oversized: true },
    ],
  })
  assert.equal("cwd" in out, false)
  assert.equal("snapshot" in out, false)
})

test("summary without files yields no card", () => {
  assert.equal(projectChangesSummary(undefined), undefined)
  assert.equal(projectChangesSummary({ ...summary, files: [], total: 0 }), undefined)
})

test("summary caps files but keeps the complete total", () => {
  const out = projectChangesSummary(summary, { maxFiles: 2 })
  assert.equal(out.files.length, 2)
  assert.equal(out.total, 4)
})

test("coordinates require non-negative integers", () => {
  const q = (s) => new URLSearchParams(s)
  assert.deepEqual(parseChangesCoordinates(q("seq=12")), { seq: 12 })
  assert.equal(parseChangesCoordinates(q("seq=-1")), null)
  assert.equal(parseChangesCoordinates(q("seq=1.5")), null)
  assert.equal(parseChangesCoordinates(q("")), null)
  assert.deepEqual(parseChangesCoordinates(q("seq=12&index=0"), { needIndex: true }), { seq: 12, index: 0 })
  assert.equal(parseChangesCoordinates(q("seq=12"), { needIndex: true }), null)
})

test("text diff passes through and truncates at the line cap", () => {
  const diff = {
    kind: "text",
    path: "src/a.ts",
    display: "src/a.ts",
    before: true,
    after: true,
    coarse: false,
    hunks: [
      { oldStart: 1, oldLines: 2, newStart: 1, newLines: 3, lines: [" a", "-b", "+c", "+d"] },
      { oldStart: 40, oldLines: 1, newStart: 41, newLines: 1, lines: ["-x", "+y"] },
    ],
  }
  const full = projectFileDiff(diff)
  assert.equal(full.hunks.length, 2)
  assert.equal(full.truncated, undefined)

  const cut = projectFileDiff(diff, { maxLines: 5 })
  assert.equal(cut.hunks.length, 2)
  assert.deepEqual(cut.hunks[1].lines, ["-x"])
  assert.equal(cut.hunks[1].oldStart, 40)
  assert.deepEqual(cut.truncated, { shownLines: 5, totalLines: 6 })

  const head = projectFileDiff(diff, { maxLines: 4 })
  assert.equal(head.hunks.length, 1)
  assert.deepEqual(head.truncated, { shownLines: 4, totalLines: 6 })
  assert.equal(MAX_DIFF_LINES, 5000)
})

test("text diff also stops at the character budget", () => {
  const long = "+" + "x".repeat(99)
  const diff = {
    kind: "text", path: "min.js", display: "min.js", before: true, after: true, coarse: false,
    hunks: [{ oldStart: 1, oldLines: 0, newStart: 1, newLines: 3, lines: [long, long, long] }],
  }
  const cut = projectFileDiff(diff, { maxChars: 250 })
  assert.deepEqual(cut.hunks[0].lines, [long, long])
  assert.deepEqual(cut.truncated, { shownLines: 2, totalLines: 3 })
})

test("binary / oversized diffs carry no lines", () => {
  assert.deepEqual(projectFileDiff({ kind: "binary", path: "img.png", display: "img.png" }), {
    kind: "binary", path: "img.png", display: "img.png",
  })
  assert.deepEqual(projectFileDiff({ kind: "oversized", path: "a", display: "a", hunks: [] }), {
    kind: "oversized", path: "a", display: "a",
  })
  assert.equal(projectFileDiff({ kind: "weird" }), undefined)
})

test("service lookup tolerates hosts without workspaceChanges", () => {
  assert.equal(workspaceChangesService(() => undefined), undefined)
  assert.equal(workspaceChangesService(() => { throw new Error("not provided") }), undefined)
  assert.equal(workspaceChangesService(() => ({ summary() {} })), undefined)
  const svc = { summary() {}, diff: async () => undefined }
  assert.equal(workspaceChangesService(() => svc), svc)
})

test("capabilities advertise changes only when the host serves them", () => {
  assert.equal(pluginCapabilities().files.changes, undefined)
  const caps = pluginCapabilities({ changes: true })
  assert.equal(caps.files.changes, true)
  assert.equal(caps.files.diff, true)
  assert.equal(caps.files.diffMaxLines, MAX_DIFF_LINES)
  assert.equal(caps.files.workspace, true)
})

test("history embeds the announced summary as a workspace_changes card", () => {
  const events = [
    ev(1, "user/message", { content: [{ type: "text", text: "改一下" }] }),
    ev(5, "workspace/changes", { turn: 3 }),
    ev(6, "turn/end", { turn: 3, reason: { kind: "completed" } }),
  ]
  const seen = []
  const page = projectHistoryPage({
    events,
    changesSummary: (seq) => { seen.push(seq); return summary },
  })
  assert.deepEqual(seen, [5])
  const card = page.messages.find((m) => m.role === "workspace_changes")
  assert.equal(card.id, "changes-5")
  assert.equal(card.seq, 5)
  assert.equal(card.turn, 3)
  assert.equal(card.changes.total, 4)
  assert.equal(card.changes.files[1].display, "src/a.ts")
})

test("history drops the card when the host no longer has the summary", () => {
  const events = [ev(5, "workspace/changes", { turn: 3 })]
  assert.equal(projectHistoryPage({ events, changesSummary: () => undefined }).messages.length, 0)
  assert.equal(projectHistoryPage({ events }).messages.length, 0)
  assert.equal(projectHistoryPage({ events, changesSummary: () => { throw new Error("boom") } }).messages.length, 0)
})

test("a later announcement for the same turn replaces the earlier card", () => {
  const later = { ...summary, files: [summary.files[1]], total: 1, added: 12, deleted: 3 }
  const events = [
    ev(5, "workspace/changes", { turn: 3 }),
    ev(9, "workspace/changes", { turn: 3 }),
  ]
  const page = projectHistoryPage({ events, changesSummary: (seq) => (seq === 5 ? summary : later) })
  const cards = page.messages.filter((m) => m.role === "workspace_changes")
  assert.equal(cards.length, 1)
  assert.equal(cards[0].id, "changes-9")
  assert.equal(cards[0].changes.total, 1)

  const emptied = projectHistoryPage({
    events,
    changesSummary: (seq) => (seq === 5 ? summary : { ...summary, files: [], total: 0 }),
  })
  assert.equal(emptied.messages.filter((m) => m.role === "workspace_changes").length, 0)
})
