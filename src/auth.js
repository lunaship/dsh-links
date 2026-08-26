/**
 * dsh-links 认证核心（纯逻辑，可 node --test 单测）
 * ============================================================
 * 凭据分层：
 *   配对码（一次性，仅本进程内存）→ deviceId + deviceToken（App Keystore）
 *
 * 安全约定：
 *   - 配对码明文与 salt/hash 都不落盘（6 位码哈希可离线穷举）
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
export const PAIR_RATE_BUCKET_MAX = 1024
const PAIR_RATE_BUCKET_TTL_MS = PAIR_COOLDOWN_MS * 2

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

/** 两个 64 位 hex 摘要做恒定时间比较（长度不一致即 false）。 */
function safeTokenHashEqual(expectedHex, computedHex) {
  if (typeof expectedHex !== "string" || typeof computedHex !== "string") return false
  const a = Buffer.from(expectedHex, "hex")
  const b = Buffer.from(computedHex, "hex")
  if (!a.length || a.length !== b.length) return false
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
  const hmac = hmacDeviceToken(state, token)
  const byHmac = devices.find((d) => safeTokenHashEqual(d.tokenHash, hmac))
  if (byHmac) return { device: byHmac, legacy: false }
  const legacy = sha256Hex(token)
  const byLegacy = devices.find((d) => safeTokenHashEqual(d.tokenHash, legacy))
  if (byLegacy) return { device: byLegacy, legacy: true }
  return { device: null, legacy: false }
}

function headerValues(raw) {
  if (typeof raw === "string") return [raw]
  if (Array.isArray(raw)) return raw.filter((v) => typeof v === "string")
  return []
}

/**
 * 从请求头取出设备 token。兼容：
 * - `x-dsh-link-token` 字符串 / 数组 / 重复头逗号拼接
 * - `Authorization: Bearer <token>`（部分系统会丢掉自定义头）
 */
export function readDeviceToken(headers) {
  if (!headers || typeof headers !== "object") return null
  for (const v of headerValues(headers["x-dsh-link-token"])) {
    const token = v.split(",")[0].trim()
    if (token) return token
  }
  for (const v of headerValues(headers.authorization)) {
    const m = /^Bearer\s+(\S+)/i.exec(v.trim())
    if (m?.[1]) return m[1]
  }
  return null
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

function pairingRates(state) {
  if (!(state.pairingRates instanceof Map)) state.pairingRates = new Map()
  return state.pairingRates
}

function prunePairingRates(state, nowMs = Date.now()) {
  const rates = pairingRates(state)
  for (const [key, rate] of rates) {
    if (nowMs - (rate.lastSeenAt ?? 0) > PAIR_RATE_BUCKET_TTL_MS) rates.delete(key)
  }
  while (rates.size >= PAIR_RATE_BUCKET_MAX) rates.delete(rates.keys().next().value)
}

/** 按客户端键隔离的限流桶（仅内存、不落盘、有界）。 */
export function pairingRateFor(state, clientKey = "unknown", create = true) {
  const rates = pairingRates(state)
  const key = normalizeClientKey(clientKey)
  let rate = rates.get(key)
  if (!rate && create) {
    prunePairingRates(state)
    rate = { failCount: 0, cooldownUntil: 0, lastSeenAt: Date.now() }
    rates.set(key, rate)
  }
  if (rate) rate.lastSeenAt = Date.now()
  return rate ?? null
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

/**
 * 启动时丢掉任何落盘配对材料。
 * 6 位码的 salt/hash 可离线穷举；明文 code 更是直接凭证。配对码只活在本进程内存。
 */
export function hydratePairing(state) {
  if (state.pairingRate) delete state.pairingRate
  liveCodes.delete(state)
  state.pairing = {}
}

/** 配对码不落盘：盐/哈希/明文都不写入 state.json。 */
export function persistablePairing(_pairing) {
  return {}
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
  const global = pairingGlobal(state)
  if (Date.now() < (global.cooldownUntil ?? 0)) return { ok: false, error: "尝试过于频繁，请稍后再试" }
  const p = state.pairing ?? {}
  const live = getLivePairingCode(state)
  if (p.consumed) return { ok: false, error: "配对码已使用" }
  // 只认本进程内存中的码。落盘 salt/hash 即使仍在旧 state.json 里也不得用来验证。
  if (!live || !p.codeHash) return { ok: false, error: "尚未生成配对码" }
  if (Date.now() > (p.expiresAt ?? 0)) return { ok: false, error: "配对码已过期" }
  const rate = pairingRateFor(state, clientKey)
  if (Date.now() < (rate.cooldownUntil ?? 0)) return { ok: false, error: "尝试过于频繁，请稍后再试" }

  const offered = String(code ?? "")
  const expected = Buffer.from(String(p.codeHash), "hex")
  const actual = p.salt ? hashPairing(p.salt, offered) : Buffer.alloc(0)
  const match = safeEqualBuf(expected, actual)
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
