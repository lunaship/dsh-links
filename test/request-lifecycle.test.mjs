import { test } from "node:test"
import assert from "node:assert/strict"
import {
  createRequestRegistry,
  canDeviceHandle,
  graceDurationMs,
  mapApprovalUiStatus,
  APPROVAL_TIMEOUT_MS,
  RECONNECT_GRACE_MS,
} from "../src/request-lifecycle.js"

function pending({ id = "a1", sessionId = "s1", type = "approval", now = 1_000, extra = {} } = {}) {
  let settled = null
  const rec = {
    id,
    sessionId,
    type,
    createdAt: now,
    deadlineAt: now + APPROVAL_TIMEOUT_MS,
    eligibleDeviceIds: new Set(["dev-1"]),
    settled: false,
    settle(outcome) { settled = outcome },
    ...extra,
  }
  return { rec, getOutcome: () => settled }
}

test("断线宽限不超过剩余期限且不重置总超时", () => {
  const rec = pending({ now: 0 }).rec
  rec.deadlineAt = 20_000
  assert.equal(graceDurationMs(rec, 0, RECONNECT_GRACE_MS), 20_000)
  rec.deadlineAt = 5 * 60 * 1000
  assert.equal(graceDurationMs(rec, 0, RECONNECT_GRACE_MS), RECONNECT_GRACE_MS)
})

test("宽限内原设备可处理，吊销后立即拒绝", () => {
  const { rec } = pending()
  assert.equal(canDeviceHandle(rec, { deviceId: "dev-1", authorized: true, subscribed: false, inGrace: true }), true)
  assert.equal(canDeviceHandle(rec, { deviceId: "dev-1", authorized: false, subscribed: false, inGrace: true }), false)
  assert.equal(canDeviceHandle(rec, { deviceId: "dev-2", authorized: true, subscribed: false, inGrace: true }), false)
  assert.equal(canDeviceHandle(rec, { deviceId: "dev-1", authorized: true, subscribed: true, inGrace: false }), true)
})

test("终态只 settle 一次，重试返回已记录结果", () => {
  const timers = []
  const registry = createRequestRegistry({
    now: () => 1_000,
    setTimeoutFn: (fn, ms) => {
      const handle = { fn, ms }
      timers.push(handle)
      return handle
    },
    clearTimeoutFn: () => {},
  })
  const { rec, getOutcome } = pending()
  registry.addApproval(rec)
  const first = registry.finishApproval(rec, "allowed-once")
  const second = registry.finishApproval(rec, "rejected")
  assert.equal(first.outcome, "allowed-once")
  assert.equal(second.outcome, "allowed-once")
  assert.equal(getOutcome(), "allowed-once")
  assert.equal(registry.getTerminal("a1").outcome, "allowed-once")
  assert.equal(mapApprovalUiStatus("allowed-once"), "resolved")
})

test("无订阅者时进入宽限，到期后只结束一次", () => {
  const timers = []
  const registry = createRequestRegistry({
    now: () => 1_000,
    setTimeoutFn: (fn, ms) => {
      const handle = { fn, ms, id: timers.length }
      timers.push(handle)
      return handle
    },
    clearTimeoutFn: (handle) => {
      handle.cleared = true
    },
  })
  const { rec, getOutcome } = pending()
  registry.addApproval(rec)
  let expired = 0
  registry.scheduleGrace("s1", () => {
    expired++
    registry.settleSession("s1")
  })
  assert.equal(timers.length, 1)
  assert.equal(registry.inGrace("s1"), true)
  registry.addEligible("s1", "dev-1")
  registry.clearGrace("s1")
  assert.equal(timers[0].cleared, true)
  registry.scheduleGrace("s1", () => {
    expired++
    registry.settleSession("s1")
  })
  timers.at(-1).fn()
  assert.equal(expired, 1)
  assert.equal(getOutcome(), "cancelled")
  registry.settleSession("s1")
  assert.equal(getOutcome(), "cancelled")
})

test("插件退出立即失效，不能把内存回调假装可恢复", () => {
  const registry = createRequestRegistry({ now: () => 1_000, setTimeoutFn: () => ({}), clearTimeoutFn: () => {} })
  const { rec, getOutcome } = pending()
  registry.addApproval(rec)
  registry.disposeAll("cancelled")
  assert.equal(getOutcome(), "cancelled")
  assert.equal(registry.getApproval("a1"), null)
})
