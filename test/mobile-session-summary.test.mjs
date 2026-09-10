import assert from "node:assert/strict"
import test from "node:test"
import { mobileSessionSummary } from "../src/mobile-session-summary.js"
import { omitNullFields, optionalString } from "../src/optional-string.js"

test("optionalString 丢掉 JSON 空值与字面量 null", () => {
  assert.equal(optionalString(null), null)
  assert.equal(optionalString("  "), null)
  assert.equal(optionalString("null"), null)
  assert.equal(optionalString("NULL"), null)
  assert.equal(optionalString("undefined"), null)
  assert.equal(optionalString("standard"), "standard")
})

test("omitNullFields 不把 null 写进 JSON，避免旧 App optString 显示 null", () => {
  const json = JSON.stringify(omitNullFields({ a: 1, b: null, c: "ok" }))
  assert.equal(json.includes("b"), false)
  assert.deepEqual(JSON.parse(json), { a: 1, c: "ok" })
})

test("session.list 的 agentPreset 为 null 时不下发该键", () => {
  const summary = mobileSessionSummary({
    sessionId: "s1",
    updatedAt: 1,
    running: false,
    cwd: null,
    agentPreset: null,
    projections: { values: { title: "Reply with exactly PONG015" } },
  })
  assert.equal("agentPreset" in summary, false)
  assert.equal("cwd" in summary, false)
  assert.equal(summary.title, "Reply with exactly PONG015")
  assert.equal(JSON.parse(JSON.stringify(summary)).agentPreset, undefined)
})

test("真实预设与 cwd 仍会下发", () => {
  const summary = mobileSessionSummary({
    sessionId: "s1",
    cwd: "/Volumes/Space/Dev/workspace",
    agentPreset: "standard",
    projections: { values: {} },
  })
  assert.equal(summary.agentPreset, "standard")
  assert.equal(summary.cwd, "/Volumes/Space/Dev/workspace")
  assert.equal(summary.title, "未命名会话")
})
