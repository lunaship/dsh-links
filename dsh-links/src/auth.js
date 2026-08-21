/**
 * dsh-links 认证核心（纯逻辑，可 node --test 单测）
 * ============================================================
 * 凭据分层：
 *   配对码（一次性，内存明文 + 落盘 salt/hash）→ deviceId + deviceToken（App Keystore）
 *
 * 安全约定：
 *   - 配对码明文不落盘
 *   - 失败限流按客户端 IP 隔离，避免全局锁死配对
 *   - 冷却期内不换发新码（ensurePairingCode 仍可展示未过期码）
 */
import * as crypto from "node:crypto"

export const PAIR_FAIL_LIMIT = 5                  // 失败限流阈值（每客户端）
export const PAIR_COOLDOWN_MS = 15 * 60 * 1000    // 冷却 15 分钟

const liveCodes = new WeakMap()

export const pairingEntropy = {
  randomInt(min, max) {
    return crypto.randomInt(min, max)
  },
}

function sha256Hex(v) {
  return crypto.createHash("sha256").update(String(v)).digest("hex")
}

function hashPairing(salt, code) {
  return crypto.createHash("sha256").update(String(salt)).update(":").update(String(code)).digest()
}

function safeEqualBuf(a, b) {
  if (!Buffer.isBuffer(a) || !Buffer.isBuffer(b) || a.length !== b.length) return false
  return crypto.timingSafeEqual(a, b)
}

export function randomToken(bytes = 24) {
  return crypto.randomBytes(bytes).toString("hex")
}

/** 内存认证存储（吊销时关闭流等由调用方处理；票据/Web session 已移除）。 */
export function newAuthStore() {
  return {}
}

/** 吊销设备：插件侧不再维护 ticket/session。 */
export function revokeDevice(_store, _deviceId) {}

function normalizeClientKey(clientKey) {
  const raw = String(clientKey ?? "unknown").trim() || "unknown"
  return raw.replace(/^::ffff:/i, "")
}

/** 按客户端键隔离的限流桶（仅内存，不落盘）。 */
function pairingRateFor(state, clientKey = "unknown") {
  if (!state.pairingRates || typeof state.pairingRates !== "object") {
    state.pairingRates = Object.create(null)
  }
  const key = normalizeClientKey(clientKey)
  if (!state.pairingRates[key]) {
    state.pairingRates[key] = { failCount: 0, cooldownUntil: 0 }
  }
  return state.pairingRates[key]
}

export function getLivePairingCode(state) {
  return liveCodes.get(state) ?? null
}

/** 启动时把旧版明文 pairing.code 迁到内存 + hash。 */
export function hydratePairing(state) {
  const p = state.pairing ?? {}
  // 丢弃旧版全局 pairingRate（已改为 per-client pairingRates）
  if (state.pairingRate) delete state.pairingRate
  if (p.code) {
    const code = String(p.code)
    liveCodes.set(state, code)
    const salt = crypto.randomBytes(16).toString("hex")
    state.pairing = {
      salt,
      codeHash: hashPairing(salt, code).toString("hex"),
      expiresAt: p.expiresAt,
      consumed: Boolean(p.consumed),
    }
  }
}

/** 落盘用的 pairing 投影：不含明文 code。 */
export function persistablePairing(pairing) {
  if (!pairing?.codeHash) return {}
  return {
    salt: pairing.salt,
    codeHash: pairing.codeHash,
    expiresAt: pairing.expiresAt,
    consumed: Boolean(pairing.consumed),
  }
}

/** 生成一次性配对码（旧码立即失效）。不重置限流计数。 */
export function newPairingCode(state, ttlSeconds) {
  const code = String(pairingEntropy.randomInt(0, 1_000_000)).padStart(6, "0")
  const salt = crypto.randomBytes(16).toString("hex")
  liveCodes.set(state, code)
  state.pairing = {
    salt,
    codeHash: hashPairing(salt, code).toString("hex"),
    expiresAt: Date.now() + ttlSeconds * 1000,
    consumed: false,
  }
  return code
}

/**
 * 需要展示/使用配对码时调用。
 * @returns {string|null}
 */
export function ensurePairingCode(state, ttlSeconds) {
  const live = getLivePairingCode(state)
  const p = state.pairing
  if (live && p && !p.consumed && (p.expiresAt ?? 0) > Date.now()) return live
  return newPairingCode(state, ttlSeconds)
}

/**
 * 校验配对码：返回 ok/error。成功后由 consumePairingCode 标记一次性消费。
 * @param {string} [clientKey] 客户端标识（通常为 remoteAddress），用于隔离失败限流
 */
export function verifyPairingCode(state, code, clientKey = "unknown") {
  const rate = pairingRateFor(state, clientKey)
  if (Date.now() < (rate.cooldownUntil ?? 0)) return { ok: false, error: "尝试过于频繁，请稍后再试" }
  const p = state.pairing ?? {}
  const live = getLivePairingCode(state)
  if (!p.codeHash && !live) return { ok: false, error: "尚未生成配对码" }
  if (p.consumed) return { ok: false, error: "配对码已使用" }
  if (Date.now() > (p.expiresAt ?? 0)) return { ok: false, error: "配对码已过期" }

  const offered = String(code ?? "")
  let match = false
  if (p.salt && p.codeHash) {
    const expected = Buffer.from(String(p.codeHash), "hex")
    const actual = hashPairing(p.salt, offered)
    match = safeEqualBuf(expected, actual)
  } else if (live) {
    const a = Buffer.from(live.padStart(6, "0"))
    const b = Buffer.from(offered.padStart(6, "0"))
    match = a.length === b.length && safeEqualBuf(a, b)
  }
  if (!match) {
    rate.failCount = (rate.failCount ?? 0) + 1
    if (rate.failCount >= PAIR_FAIL_LIMIT) {
      rate.cooldownUntil = Date.now() + PAIR_COOLDOWN_MS
      rate.failCount = 0
    }
    return { ok: false, error: "配对码无效" }
  }
  // 成功：清该客户端失败计数
  rate.failCount = 0
  return { ok: true }
}

/** 配对成功后调用：配对码立即失效（一次性）。 */
export function consumePairingCode(state) {
  if (state.pairing) state.pairing.consumed = true
  liveCodes.delete(state)
}

export { sha256Hex as sha256 }
