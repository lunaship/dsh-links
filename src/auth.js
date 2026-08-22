/**
 * dsh-links 认证核心（纯逻辑，可 node --test 单测）
 * ============================================================
 * 凭据分层：
 *   配对码（一次性，内存明文 + 落盘 salt/hash）→ deviceId + deviceToken（App Keystore）
 *
 * 安全约定：
 *   - 配对码明文不落盘
 *   - 失败限流按客户端 IP 隔离，避免单 IP 全局锁死配对
 *   - 同一配对码跨 IP 总失败次数封顶后作废（防分布式猜测）
 *   - 全局预算 + 短冷却，降低爆破速率
 *   - 冷却期内不换发新码（ensurePairingCode 仍可展示未过期码）
 */
import * as crypto from "node:crypto"

export const PAIR_FAIL_LIMIT = 5                  // 失败限流阈值（每客户端）
export const PAIR_COOLDOWN_MS = 15 * 60 * 1000    // 冷却 15 分钟
export const PAIR_CHALLENGE_FAIL_LIMIT = 25       // 当前配对码跨 IP 总失败上限
export const PAIR_GLOBAL_FAIL_LIMIT = 40          // 进程级失败预算
export const PAIR_GLOBAL_COOLDOWN_MS = 60 * 1000  // 全局短冷却

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

/**
 * 设备 token 落盘哈希：HMAC-SHA256，密钥每安装随机一次（state.tokenKey）。
 * token 本身是 192 位随机数，爆破不可行；加 per-install 密钥是稳健性加固
 * （同 token 在不同安装下哈希不同），不是为了抵御算力。
 */
export function ensureTokenKey(state) {
  if (typeof state.tokenKey !== "string" || !state.tokenKey) {
    state.tokenKey = crypto.randomBytes(32).toString("base64url")
  }
  return state.tokenKey
}

export function hmacDeviceToken(state, token) {
  const key = Buffer.from(ensureTokenKey(state), "base64url")
  return crypto.createHmac("sha256", key).update(String(token)).digest("hex")
}

/**
 * 按 token 找已配对设备。先按现行 HMAC 匹配；旧安装的裸 SHA-256 哈希命中时
 * 返回 legacy=true，调用方用原始 token 重算 HMAC 落盘完成迁移，手机无需重新配对。
 */
export function findDeviceByToken(state, token) {
  const devices = state.devices ?? []
  const byHmac = devices.find((d) => d.tokenHash === hmacDeviceToken(state, token))
  if (byHmac) return { device: byHmac, legacy: false }
  const byLegacy = devices.find((d) => d.tokenHash === sha256Hex(token))
  if (byLegacy) return { device: byLegacy, legacy: true }
  return { device: null, legacy: false }
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

function pairingGlobal(state) {
  if (!state.pairingGlobal || typeof state.pairingGlobal !== "object") {
    state.pairingGlobal = { failCount: 0, cooldownUntil: 0 }
  }
  return state.pairingGlobal
}

function pairingChallenge(state) {
  const codeHash = state.pairing?.codeHash ?? "none"
  if (!state.pairingChallenge || state.pairingChallenge.codeHash !== codeHash) {
    state.pairingChallenge = { codeHash, failCount: 0 }
  }
  return state.pairingChallenge
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
  const global = pairingGlobal(state)
  if (Date.now() < (global.cooldownUntil ?? 0)) return { ok: false, error: "尝试过于频繁，请稍后再试" }
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
    const challenge = pairingChallenge(state)
    challenge.failCount = (challenge.failCount ?? 0) + 1
    global.failCount = (global.failCount ?? 0) + 1
    if (challenge.failCount >= PAIR_CHALLENGE_FAIL_LIMIT) {
      consumePairingCode(state)
      return { ok: false, error: "尝试过多，配对码已失效，请刷新二维码" }
    }
    if (global.failCount >= PAIR_GLOBAL_FAIL_LIMIT) {
      global.cooldownUntil = Date.now() + PAIR_GLOBAL_COOLDOWN_MS
      global.failCount = 0
      consumePairingCode(state)
      return { ok: false, error: "尝试过于频繁，请稍后再试" }
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
