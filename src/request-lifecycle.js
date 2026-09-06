/**
 * 审批 / 澄清请求生命周期：与 SSE socket 分离，终态只写一次，
 * 断线给有限重连宽限，吊销与插件退出立即结束。
 */
import { RECONNECT_GRACE_MS } from "./protocol-caps.js"

export { RECONNECT_GRACE_MS }
export const APPROVAL_TIMEOUT_MS = 5 * 60 * 1000
export const MAX_TERMINAL_RESULTS = 256
export const TERMINAL_TTL_MS = 10 * 60 * 1000
export const APPROVAL_OUTCOMES = new Set(["allowed-once", "rejected", "cancelled", "unavailable"])

export function remainingMs(rec, now) {
  return Math.max(0, (rec?.deadlineAt ?? now) - now)
}

export function graceDurationMs(rec, now, grace = RECONNECT_GRACE_MS) {
  return Math.min(grace, remainingMs(rec, now))
}

export function canDeviceHandle(rec, { deviceId, authorized, subscribed, inGrace }) {
  if (!rec || !deviceId || !authorized) return false
  if (subscribed) return true
  return Boolean(inGrace && rec.eligibleDeviceIds?.has(deviceId))
}

export function mapApprovalUiStatus(outcome) {
  if (!outcome) return "pending"
  if (outcome === "allowed-once" || outcome === "rejected") return "resolved"
  if (outcome === "cancelled") return "cancelled"
  if (outcome === "unavailable") return "expired"
  return "unknown"
}

export function createRequestRegistry({
  now = () => Date.now(),
  setTimeoutFn = setTimeout,
  clearTimeoutFn = clearTimeout,
} = {}) {
  const pendingApprovals = new Map()
  const pendingQuestions = new Map()
  const terminalResults = new Map()
  const graceTimers = new Map()
  const sessionEligible = new Map()

  function pruneTerminal(at = now()) {
    for (const [id, rec] of terminalResults) {
      if (at - rec.at > TERMINAL_TTL_MS) terminalResults.delete(id)
    }
    while (terminalResults.size > MAX_TERMINAL_RESULTS) {
      const oldest = terminalResults.keys().next().value
      terminalResults.delete(oldest)
    }
  }

  function rememberTerminal(rec, outcome, extra = {}) {
    pruneTerminal()
    const result = {
      id: rec.id,
      type: rec.type,
      sessionId: rec.sessionId,
      outcome,
      status: rec.type === "approval" ? mapApprovalUiStatus(outcome) : (outcome ? "resolved" : "cancelled"),
      at: now(),
      ...extra,
    }
    terminalResults.set(rec.id, result)
    return result
  }

  function clearTimers(rec) {
    if (rec?.timer) {
      clearTimeoutFn(rec.timer)
      rec.timer = null
    }
    try { rec.signal?.removeEventListener?.("abort", rec.onAbort) } catch {}
  }

  function finishApproval(rec, outcome) {
    if (!rec || rec.settled) return rec?.terminal ?? null
    rec.settled = true
    pendingApprovals.delete(rec.id)
    clearTimers(rec)
    const resolved = APPROVAL_OUTCOMES.has(outcome) ? outcome : "unavailable"
    const terminal = rememberTerminal(rec, resolved)
    rec.terminal = terminal
    try { rec.settle(resolved) } catch {}
    return terminal
  }

  function finishQuestion(rec, answer) {
    if (!rec || rec.settled) return rec?.terminal ?? null
    rec.settled = true
    pendingQuestions.delete(rec.id)
    clearTimers(rec)
    const ok = Boolean(answer && Array.isArray(answer.answers))
    const terminal = rememberTerminal(rec, ok ? "answered" : "cancelled", ok ? { answer } : {})
    rec.terminal = terminal
    try { rec.settle(ok ? answer : null) } catch {}
    return terminal
  }

  function pendingForSession(sessionId) {
    return [
      ...pendingApprovals.values(),
      ...pendingQuestions.values(),
    ].filter((rec) => rec.sessionId === sessionId)
  }

  function addEligible(sessionId, deviceId) {
    if (!sessionId || !deviceId) return
    let set = sessionEligible.get(sessionId)
    if (!set) {
      set = new Set()
      sessionEligible.set(sessionId, set)
    }
    set.add(deviceId)
    for (const rec of pendingForSession(sessionId)) rec.eligibleDeviceIds.add(deviceId)
  }

  function removeEligible(sessionId, deviceId) {
    sessionEligible.get(sessionId)?.delete(deviceId)
    for (const rec of pendingForSession(sessionId)) rec.eligibleDeviceIds.delete(deviceId)
  }

  function clearGrace(sessionId) {
    const timer = graceTimers.get(sessionId)
    if (timer) clearTimeoutFn(timer)
    graceTimers.delete(sessionId)
  }

  function settleSession(sessionId, { approvalOutcome = "cancelled", questionsToDesktop = true } = {}) {
    clearGrace(sessionId)
    for (const rec of [...pendingApprovals.values()]) {
      if (rec.sessionId === sessionId) finishApproval(rec, approvalOutcome)
    }
    for (const rec of [...pendingQuestions.values()]) {
      if (rec.sessionId === sessionId) {
        if (questionsToDesktop) finishQuestion(rec, null)
        else finishQuestion(rec, null)
      }
    }
  }

  function scheduleGrace(sessionId, onExpire) {
    if (graceTimers.has(sessionId)) return
    const recs = pendingForSession(sessionId)
    if (recs.length === 0) return
    const wait = Math.min(...recs.map((rec) => graceDurationMs(rec, now())))
    if (wait <= 0) {
      onExpire(sessionId)
      return
    }
    const timer = setTimeoutFn(() => {
      graceTimers.delete(sessionId)
      onExpire(sessionId)
    }, wait)
    timer?.unref?.()
    graceTimers.set(sessionId, timer)
  }

  function inGrace(sessionId) {
    return graceTimers.has(sessionId)
  }

  function snapshot(sessionId) {
    pruneTerminal()
    const approvals = []
    const questions = []
    for (const rec of pendingApprovals.values()) {
      if (rec.sessionId !== sessionId) continue
      approvals.push({
        approvalId: rec.id,
        status: "pending",
        outcome: null,
        sessionId,
        createdAt: rec.createdAt,
        deadlineAt: rec.deadlineAt,
        callId: rec.callId ?? null,
        toolName: rec.toolName ?? null,
      })
    }
    for (const rec of pendingQuestions.values()) {
      if (rec.sessionId !== sessionId) continue
      questions.push({
        rpcId: rec.id,
        status: "pending",
        sessionId,
        createdAt: rec.createdAt,
        deadlineAt: rec.deadlineAt,
        questions: rec.questions ?? [],
      })
    }
    for (const rec of terminalResults.values()) {
      if (rec.sessionId !== sessionId) continue
      if (rec.type === "approval") {
        approvals.push({
          approvalId: rec.id,
          status: rec.status,
          outcome: rec.outcome,
          sessionId,
          createdAt: rec.at,
          deadlineAt: rec.at,
        })
      } else {
        questions.push({
          rpcId: rec.id,
          status: rec.status,
          sessionId,
          createdAt: rec.at,
          deadlineAt: rec.at,
        })
      }
    }
    return { approvals, questions, graceActive: inGrace(sessionId) }
  }

  function disposeAll(outcome = "cancelled") {
    for (const sessionId of [...graceTimers.keys()]) clearGrace(sessionId)
    for (const rec of [...pendingApprovals.values()]) finishApproval(rec, outcome)
    for (const rec of [...pendingQuestions.values()]) finishQuestion(rec, null)
    pendingApprovals.clear()
    pendingQuestions.clear()
    sessionEligible.clear()
  }

  return {
    pendingApprovals,
    pendingQuestions,
    terminalResults,
    graceTimers,
    addEligible,
    removeEligible,
    clearGrace,
    scheduleGrace,
    inGrace,
    settleSession,
    finishApproval,
    finishQuestion,
    rememberTerminal,
    snapshot,
    disposeAll,
    pruneTerminal,
    pendingForSession,
    addApproval(rec) {
      pendingApprovals.set(rec.id, rec)
    },
    addQuestion(rec) {
      pendingQuestions.set(rec.id, rec)
    },
    getApproval(id) {
      return pendingApprovals.get(id) ?? null
    },
    getQuestion(id) {
      return pendingQuestions.get(id) ?? null
    },
    getTerminal(id) {
      pruneTerminal()
      return terminalResults.get(id) ?? null
    },
  }
}
