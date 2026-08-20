/**
 * dsh-links 认证核心（纯逻辑，可 node --test 单测）
 * ============================================================
 * 凭据分层：
 *   配对码（一次性）→ deviceId + deviceToken（Keystore，不进 WebView）
 *   → 启动 ticket（30s 单次）→ Web session cookie（30min 滑动，HttpOnly）
 *
 * 安全约定：
 *   - ticket 只存哈希，不落盘、不写日志
 *   - Web session 仅内存（服务重启即失效，不持久化）
 *   - 吊销设备立即清除其全部 ticket 与 session
 */
import { createHash, randomBytes } from "node:crypto"

export const SESSION_TTL_MS = 30 * 60 * 1000      // 30 分钟滑动续期
export const TICKET_TTL_MS = 30 * 1000            // 30 秒单次
export const MAX_SESSIONS_PER_DEVICE = 3
export const PAIR_FAIL_LIMIT = 5                  // 失败限流阈值
export const PAIR_COOLDOWN_MS = 15 * 60 * 1000    // 冷却 15 分钟

function sha256(v) {
  return createHash("sha256").update(String(v)).digest("hex")
}

export function randomToken(bytes = 24) {
  return randomBytes(bytes).toString("hex")
}

/** 内存认证存储：tickets / sessions 两张表。 */
export function newAuthStore() {
  return { tickets: new Map(), sessions: new Map() }
}

/* ---------------- 启动 ticket ---------------- */

export function issueTicket(store, deviceId, ttlMs = TICKET_TTL_MS) {
  const ticket = randomToken(32)
  store.tickets.set(sha256(ticket), { deviceId, expiresAt: Date.now() + ttlMs })
  return ticket
}

/** 单次消费：成功返回 deviceId 并移除；已消费/过期/不存在返回 null。 */
export function consumeTicket(store, ticket, ttlMs = TICKET_TTL_MS) {
  if (!ticket) return null
  const key = sha256(ticket)
  const rec = store.tickets.get(key)
  if (!rec) return null
  store.tickets.delete(key)
  if (Date.now() > rec.expiresAt) return null
  return rec.deviceId
}

/* ---------------- Web session ---------------- */

/** 创建短期 Web session，返回 HttpOnly cookie 值。单设备最多 3 个活跃 session。 */
export function createSession(store, deviceId, ttlMs = SESSION_TTL_MS) {
  // 超过上限：驱逐最旧的
  const mine = [...store.sessions.entries()].filter(([, s]) => s.deviceId === deviceId)
  if (mine.length >= MAX_SESSIONS_PER_DEVICE) {
    mine.sort((a, b) => a[1].expiresAt - b[1].expiresAt)
    store.sessions.delete(mine[0][0])
  }
  const value = randomToken(32)
  store.sessions.set(value, { deviceId, expiresAt: Date.now() + ttlMs })
  return value
}

/** 校验并滑动续期。返回 deviceId 或 null。 */
export function getSession(store, cookieValue, ttlMs = SESSION_TTL_MS) {
  if (!cookieValue) return null
  const rec = store.sessions.get(cookieValue)
  if (!rec) return null
  if (Date.now() > rec.expiresAt) {
    store.sessions.delete(cookieValue)
    return null
  }
  rec.expiresAt = Date.now() + ttlMs
  return rec.deviceId
}

export function destroySession(store, cookieValue) {
  store.sessions.delete(cookieValue)
}

/** 吊销设备：清除其全部 ticket 与 session。 */
export function revokeDevice(store, deviceId) {
  for (const [k, v] of store.tickets) if (v.deviceId === deviceId) store.tickets.delete(k)
  for (const [k, v] of store.sessions) if (v.deviceId === deviceId) store.sessions.delete(k)
}

/* ---------------- 配对码：一次性 + 限流 ---------------- */

/** 生成一次性配对码（旧码立即失效）。 */
export function newPairingCode(state, ttlSeconds) {
  const code = String(Math.floor(Math.random() * 1_000_000)).padStart(6, "0")
  state.pairing = {
    code,
    expiresAt: Date.now() + ttlSeconds * 1000,
    consumed: false,
    failCount: 0,
    cooldownUntil: 0,
  }
  return code
}

/** 校验配对码：返回 ok/error。成功后标记已消费（一次性）。 */
export function verifyPairingCode(state, code) {
  const p = state.pairing ?? {}
  if (Date.now() < (p.cooldownUntil ?? 0)) return { ok: false, error: "尝试过于频繁，请稍后再试" }
  if (!p.code) return { ok: false, error: "尚未生成配对码" }
  if (p.consumed) return { ok: false, error: "配对码已使用" }
  if (Date.now() > (p.expiresAt ?? 0)) return { ok: false, error: "配对码已过期" }
  if (p.code !== code) {
    p.failCount = (p.failCount ?? 0) + 1
    if (p.failCount >= PAIR_FAIL_LIMIT) {
      p.cooldownUntil = Date.now() + PAIR_COOLDOWN_MS
      p.failCount = 0
    }
    return { ok: false, error: "配对码无效" }
  }
  return { ok: true }
}

/** 配对成功后调用：配对码立即失效（一次性）。 */
export function consumePairingCode(state) {
  if (state.pairing) state.pairing.consumed = true
}
