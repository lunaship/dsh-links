/**
 * dsh-links — 服务端面（host face）
 *
 * 一个插件完成"手机端使用 dsh"：
 *   1. 在主 web 服务上注册 /dsh-link/* 路由（仅回环同源），给网页界面提供二维码与配对管理；
 *   2. 在 0.0.0.0:<port> 起一个带 token 校验的手机 API（只服务 App：health / pair / mobile/*）；
 *   3. 配对采用一次性 6 位配对码（默认 10 分钟有效）。默认扫码即批准；
 *      开启「配对需本机确认」后，token 先发、API 要等面板点批准才放行。
 */
import { createServer as createHttpsServer } from "node:https"
import { chmodSync, cpSync, existsSync, mkdirSync, readFileSync, renameSync, statSync, writeFileSync } from "node:fs"
import { readFile as readFileAsync } from "node:fs/promises"
import { dirname, join } from "node:path"
import { homedir, hostname, networkInterfaces } from "node:os"
import { randomBytes } from "node:crypto"
import { zstdDecompressSync } from "node:zlib"
import z from "@deepseek-ai/schemastery"
import QRCode from "qrcode"
import { clampHistoryMaxMessages, projectHistoryPage } from "./history.js"
import {
  consumePairingCode, ensurePairingCode, ensureTokenKey, findDeviceByToken, hmacDeviceToken, hydratePairing,
  persistablePairing, randomToken, readDeviceToken, revokeDevice, verifyPairingCode,
} from "./auth.js"
import { loadOrCreateTls } from "./tls.js"
import { loadEventsAfter, sseMessageFrame } from "./stream-cursor.js"
import { flushSeedQueue, respondQuestion, startMuxQuestionBridge } from "./question-bridge.js"
import { bindLocalRpcRuntime, callLocalRpc, LocalRpcError, unbindLocalRpcRuntime } from "./local-rpc.js"
import {
  MobileWorkspaceCreateError,
  ensureMobileWorkspaceDirectory,
  planMobileWorkspaceCreate,
} from "./workspace-create.js"
import { DeviceMutationGate } from "./device-mutation-gate.js"
import { deriveAddresses, generateHostKey, hostKeyFromSeed, resolveEnrollText, unb64u } from "./relay/crypto.js"
import { enroll as enrollRelay, normalizeTlsFingerprint, RelayAgent } from "./relay/agent.js"

export const name = "dsh-links"
export const inject = ["webServer", "typertGateway"]

export const Config = z.object({
  /** 手机接入代理端口（0.0.0.0） */
  port: z.natural().min(1).max(65535).default(18640),
  /** 额外可访问地址（如 frp 公网地址），会一并写进二维码供手机按可达性选择 */
  extraUrls: z.array(z.string()).default([]),
  /** 配对自动批准（个人使用默认开启）。关闭后改为面板待确认，而不是直接拒绝配对。 */
  autoApprove: z.boolean().default(true),
  /** 配对码有效期（秒） */
  pairingTtlSeconds: z.natural().default(600),
  /** 状态目录（默认 ~/.dsh/dsh-links） */
  stateDir: z.string().default(""),
  /** 请求日志（排查用） */
  debug: z.boolean().default(false),
  /** SSE 事件轮询间隔（毫秒，最低 100），默认 1000 */
  eventPollIntervalMs: z.natural().min(100).default(1000),
  /** 重连补发最大历史条数（session.history maxMessages，按消息边界计数） */
  reconnectHistoryLimit: z.natural().default(50),
})

/** App 实际会写的 settings.update 键路径（反查 AppSettingsStore / SettingsActivity）。未知 ns/键一律拒绝。 */
const SETTINGS_WRITE_ALLOWLIST = {
  "agent-presets": ["default"],
  permission: ["defaultPreset"],
  locale: ["preference"],
  "ui-theme": ["preference"],
  "ui-conversation": ["busyEnter"],
  "agent-default-model": ["provider", "model", "reasoningEffort"],
}

const DEFAULT_STATE_DIR = join(homedir(), ".dsh", "dsh-links")
const LEGACY_STATE_DIRS = [
  join(homedir(), ".dsh", "dsh-deepharness"),
  join(homedir(), ".dsh", "dshlinks"),
]

/** 一次性迁移：旧状态目录 → ~/.dsh/dsh-links，已配对设备免重扫。 */
function ensureStateDir(config) {
  const dir = config.stateDir?.trim() || DEFAULT_STATE_DIR
  if (!config.stateDir?.trim()) {
    const newState = join(dir, "state.json")
    if (!existsSync(newState)) {
      for (const legacyDir of LEGACY_STATE_DIRS) {
        const legacyState = join(legacyDir, "state.json")
        if (!existsSync(legacyState)) continue
        mkdirSync(dir, { recursive: true, mode: 0o700 })
        try {
          cpSync(legacyDir, dir, { recursive: true })
        } catch {
          writeFileSync(newState, readFileSync(legacyState), { mode: 0o600 })
        }
        break
      }
    }
  }
  mkdirSync(dir, { recursive: true, mode: 0o700 })
  try { chmodSync(dir, 0o700) } catch {}
  return dir
}

function statePathOf(config) {
  return join(ensureStateDir(config), "state.json")
}

function loadState(file) {
  if (!existsSync(file)) return {}
  let raw
  try {
    raw = readFileSync(file, "utf8")
  } catch (err) {
    throw new Error(`dsh-links: 无法读取 state.json: ${err?.message ?? err}`)
  }
  try {
    return JSON.parse(raw)
  } catch {
    throw new Error(`dsh-links: state.json 已损坏，拒绝静默清空已配对设备 (${file})`)
  }
}

function saveState(file, state) {
  const dir = dirname(file)
  mkdirSync(dir, { recursive: true, mode: 0o700 })
  try { chmodSync(dir, 0o700) } catch {}
  const persist = {
    ...state,
    pairing: persistablePairing(state.pairing),
  }
  delete persist.pairingRate
  delete persist.pairingRates
  delete persist.pairingGlobal
  delete persist.pairingChallenge
  const data = JSON.stringify(persist, null, 2)
  const tmp = `${file}.${process.pid}.tmp`
  writeFileSync(tmp, data, { mode: 0o600 })
  renameSync(tmp, file)
  try { chmodSync(file, 0o600) } catch {}
}

function now() {
  return Date.now()
}

/** 局域网 / 云端是两套配对。旧记录没有 via 时，用设备名里的云端后缀兜底。 */
function normalizeDeviceVia(device) {
  if (device?.via === "relay") return "relay"
  if (device?.via === "lan") return "lan"
  const name = String(device?.name ?? "")
  if (name.endsWith("·云") || name.includes(" · 云端")) return "relay"
  return "lan"
}

function displayRemoteAddress(addr) {
  return String(addr ?? "").replace(/^::ffff:/i, "") || ""
}

function publicDevice(device) {
  const pending = device.status === "pending"
  return {
    deviceId: device.deviceId,
    name: device.name,
    createdAt: device.createdAt,
    lastSeenAt: device.lastSeenAt,
    via: normalizeDeviceVia(device),
    status: pending ? "pending" : "active",
    pendingExpiresAt: pending ? (device.pendingExpiresAt ?? null) : null,
    pairedFrom: pending ? displayRemoteAddress(device.pairedFrom) : "",
  }
}

function pairRequireConfirm(config, state) {
  if (typeof state.pairRequireConfirm === "boolean") return state.pairRequireConfirm
  return config.autoApprove === false
}

function isDevicePending(device) {
  return device?.status === "pending"
}

function isPrivateV4Host(host) {
  if (host.startsWith("192.168.") || host.startsWith("10.")) return true
  const m = /^172\.(\d+)\./.exec(host)
  if (!m) return false
  const n = Number(m[1])
  return n >= 16 && n <= 31
}

function listenExposure(config, infos) {
  const listen = { address: "0.0.0.0", port: config.port }
  const networks = (infos ?? [])
    .filter((item) => item?.label)
    .map((item) => ({ label: item.label, category: item.category, url: item.url }))
  const extras = (config.extraUrls ?? []).length > 0
  const publicNets = networks.filter((n) => n.category === "other")
  if (publicNets.length || extras) {
    const warning = extras
      ? `已配置 extraUrls，18640 可能经隧道或公网地址暴露。当前监听 ${listen.address}:${listen.port}。`
      : `检测到非私有网卡地址。当前监听 ${listen.address}:${listen.port}，请确认这些网段可信。`
    return { listen, networks, level: "untrusted", warning }
  }
  const labels = networks.map((n) => n.label).filter(Boolean)
  const hint = labels.length
    ? `当前监听 ${listen.address}:${listen.port}，局域网可达 ${labels.join("、")}。勿把 18640 暴露到公网。`
    : `当前监听 ${listen.address}:${listen.port}。未发现可用局域网地址。`
  return { listen, networks, level: "lan", warning: null, hint }
}

/** extraUrls 只接受 https；http 一律升为 https。非法项丢弃。 */
function requireHttpsUrl(raw) {
  const s = String(raw ?? "").trim()
  if (!s) return null
  const withScheme = /^[a-z][a-z0-9+.-]*:/i.test(s) ? s : `https://${s}`
  const https = withScheme.replace(/^http:\/\//i, "https://")
  try {
    const u = new URL(https)
    if (u.protocol !== "https:") return null
    return https
  } catch {
    return null
  }
}

function classifyUrl(url) {
  try {
    const host = new URL(url).hostname
    if (host === "127.0.0.1" || host === "localhost") return "loopback"
    if (isPrivateV4Host(host)) return "private"
    return "other"
  } catch {
    return "unknown"
  }
}

function lanUrls(config) {
  const urls = new Set()
  const urlInfos = []
  let ifaces = {}
  try {
    ifaces = networkInterfaces() ?? {}
  } catch {
    ifaces = {}
  }
  for (const list of Object.values(ifaces)) {
    for (const iface of list ?? []) {
      if (iface && iface.family === "IPv4" && !iface.internal && !iface.address.startsWith("198.18.")) {
        const fullUrl = `https://${iface.address}:${config.port}`
        urls.add(fullUrl)
        urlInfos.push({ url: fullUrl, label: iface.address, category: classifyUrl(fullUrl), isRecommended: false })
      }
    }
  }
  // 推荐排序后第一个私有 IP（手机上最容易识别的入口）
  if (urlInfos.length > 0) {
    const firstPrivate = urlInfos.find((u) => u.category === "private") ?? urlInfos[0]
    firstPrivate.isRecommended = true
  }
  for (const extra of config.extraUrls ?? []) {
    const httpsUrl = requireHttpsUrl(extra)
    if (!httpsUrl) continue
    urls.add(httpsUrl)
    urlInfos.push({ url: httpsUrl, label: httpsUrl, category: "extra", isRecommended: false })
  }
  return { urls: [...urls], infos: urlInfos }
}

function pairVia(req) {
  try {
    const via = new URL(req?.url ?? "/", "http://x").searchParams.get("via")
    return via === "relay" ? "relay" : "lan"
  } catch {
    return "lan"
  }
}

function pairInfo(config, state, certFingerprint, via = "lan") {
  const lan = lanUrls(config)
  const info = {
    v: 1,
    type: "dsh-link",
    deviceId: state.deviceId,
    name: hostname(),
    port: config.port,
    urls: lan.urls,
    infos: lan.infos,
    pairingCode: ensurePairingCode(state, config.pairingTtlSeconds),
    certFingerprint,
  }
  const relay = state.relay
  if (via === "relay" && relay?.routeId && relay?.routeSecret && relay?.clientAddress) {
    info.relay = {
      v: 2,
      client: relay.clientAddress,
      routeId: relay.routeId,
      routeSecret: relay.routeSecret,
    }
    if (relay.tlsFingerprint) info.relay.tlsFingerprint = relay.tlsFingerprint
  }
  info.requireConfirm = pairRequireConfirm(config, state)
  info.exposure = listenExposure(config, lan.infos)
  return info
}

function json(res, code, obj) {
  const body = Buffer.from(JSON.stringify(obj), "utf8")
  res.writeHead(code, {
    "content-type": "application/json; charset=utf-8",
    "content-length": String(body.length),
    connection: "close",
  })
  res.end(body)
}

function headerVal(headers, name) {
  const value = headers?.[name] ?? headers?.[name.toLowerCase()]
  return typeof value === "string" ? value : undefined
}

function isLoopbackHostname(hostname) {
  const host = String(hostname ?? "").toLowerCase().replace(/^\[|\]$/g, "")
  if (host === "localhost" || host === "::1") return true
  const parts = host.split(".")
  return parts.length === 4 && parts[0] === "127" && parts.every((part) => /^\d{1,3}$/.test(part) && Number(part) <= 255)
}

/**
 * 对齐 dsh-client-connection isTrustedApiRequest（trustedHosts 为空，只认回环）。
 * Host/Origin 之外必须校验 TCP 对端地址，防止伪造 Host: 127.0.0.1 绕过围栏。
 */
function isLoopbackAddress(addr) {
  if (!addr) return false
  const a = String(addr).replace(/^::ffff:/i, "").toLowerCase()
  return a === "127.0.0.1" || a === "::1" || a === "localhost"
}

function requireLoopbackSameOrigin(req, res) {
  const remote = req.socket?.remoteAddress ?? req.connection?.remoteAddress
  if (!isLoopbackAddress(remote)) {
    json(res, 403, { error: "forbidden" })
    return false
  }
  const host = headerVal(req.headers, "host")
  if (!host) {
    json(res, 403, { error: "forbidden" })
    return false
  }
  let hostUrl
  try {
    hostUrl = new URL(`http://${host}`)
  } catch {
    json(res, 403, { error: "forbidden" })
    return false
  }
  if (!isLoopbackHostname(hostUrl.hostname)) {
    json(res, 403, { error: "forbidden" })
    return false
  }
  if (headerVal(req.headers, "sec-fetch-site") === "cross-site") {
    json(res, 403, { error: "forbidden" })
    return false
  }
  const origin = headerVal(req.headers, "origin")
  if (origin !== undefined) {
    try {
      if (new URL(origin).host !== hostUrl.host) {
        json(res, 403, { error: "forbidden" })
        return false
      }
    } catch {
      json(res, 403, { error: "forbidden" })
      return false
    }
  }
  return true
}

/** 状态变更请求：强制 JSON media type（触发 CORS 预检）+ 拒绝跨站 Origin。 */
function requireJsonWrite(req, res) {
  if (headerVal(req.headers, "sec-fetch-site") === "cross-site") {
    json(res, 403, { error: "forbidden" })
    return false
  }
  const origin = headerVal(req.headers, "origin")
  const host = headerVal(req.headers, "host")
  if (origin) {
    if (!host) {
      json(res, 403, { error: "forbidden" })
      return false
    }
    try {
      if (new URL(origin).host !== host) {
        json(res, 403, { error: "forbidden" })
        return false
      }
    } catch {
      json(res, 403, { error: "forbidden" })
      return false
    }
  }
  const ct = (headerVal(req.headers, "content-type") ?? "").toLowerCase()
  if (ct !== "application/json" && !ct.startsWith("application/json;")) {
    json(res, 415, { error: "content-type must be application/json" })
    return false
  }
  return true
}

function filterSettingsPatch(ns, patch) {
  const allowed = SETTINGS_WRITE_ALLOWLIST[ns]
  if (!allowed) return { error: "该设置命名空间不允许从手机端写入" }
  const keys = Object.keys(patch)
  if (keys.length === 0) return { error: "patch 为空" }
  const unknown = keys.filter((k) => !allowed.includes(k))
  if (unknown.length > 0) return { error: "包含不允许写入的键" }
  return { ok: true }
}

class HttpBodyError extends Error {
  constructor(status, message) {
    super(message)
    this.status = status
  }
}

const PROMPT_BODY_LIMIT = 16 * 1024 * 1024
const PROMPT_IMAGE_DATA_LIMIT = 4 * 1024 * 1024
const PROMPT_IMAGE_MEDIA_TYPES = new Set(["image/png", "image/jpeg", "image/jpg", "image/webp", "image/gif"])

function readBody(req, limit = 64 * 1024) {
  return new Promise((resolve, reject) => {
    const chunks = []
    let size = 0
    let done = false
    const fail = (err) => {
      if (done) return
      done = true
      reject(err)
    }
    req.on("data", (c) => {
      size += c.length
      if (size > limit) {
        req.destroy()
        fail(new HttpBodyError(413, "payload too large"))
        return
      }
      chunks.push(c)
    })
    req.on("end", () => {
      if (done) return
      if (size === 0) return fail(new HttpBodyError(400, "empty body"))
      try {
        resolve(JSON.parse(Buffer.concat(chunks).toString("utf8")))
      } catch {
        fail(new HttpBodyError(400, "invalid json"))
      }
    })
    req.on("error", () => fail(new HttpBodyError(400, "invalid json")))
  })
}

async function readJson(req, res, limit) {
  try {
    return await readBody(req, limit)
  } catch (err) {
    json(res, err.status ?? 400, { error: err.message || "invalid body" })
    return null
  }
}

function authorize(req, state, stateFile) {
  const token = readDeviceToken(req.headers)
  if (!token) return null
  const { device, legacy } = findDeviceByToken(state, token)
  if (!device) return null
  if (legacy) {
    // 旧版裸 SHA-256 哈希命中：用原始 token 重算 HMAC 落盘，下次走新格式
    device.tokenHash = hmacDeviceToken(state, token)
    saveState(stateFile, state)
  }
  return device
}

function touchDevice(state, device, file) {
  if (now() - (device.lastSeenAt ?? 0) > 60_000) {
    device.lastSeenAt = now()
    saveState(file, state)
  }
}

async function qrPng(res, config, state, certFingerprint, via = "lan") {
  try {
    const payload = pairInfo(config, state, certFingerprint, via)
    const buf = await QRCode.toBuffer(JSON.stringify(payload), { type: "png", margin: 2, width: 320 })
    res.writeHead(200, { "content-type": "image/png", "cache-control": "no-store" })
    res.end(buf)
  } catch (err) {
    console.error(`dsh-links: qr.png: ${err?.message ?? err}`)
    json(res, 500, { error: "failed to render qr" })
  }
}

const PAIR_REQUEST_CACHE_MAX = 256
const PAIR_REQUEST_CACHE_TTL_MS = 15 * 60 * 1000

function rememberPairRequest(rt, requestId, fingerprint, body) {
  if (!requestId) return
  const cache = rt.pairingRequests
  cache.set(requestId, { fingerprint, body, expiresAt: Date.now() + PAIR_REQUEST_CACHE_TTL_MS })
  while (cache.size > PAIR_REQUEST_CACHE_MAX) cache.delete(cache.keys().next().value)
}

function cachedPairRequest(rt, requestId, fingerprint) {
  if (!requestId) return null
  const cache = rt.pairingRequests
  const hit = cache.get(requestId)
  if (!hit) return null
  if (Date.now() >= hit.expiresAt) {
    cache.delete(requestId)
    return null
  }
  if (hit.fingerprint !== fingerprint) return { conflict: true }
  return hit.body
}

async function handlePair(req, res, config, state, stateFile, rt) {
  if (!requireJsonWrite(req, res)) return
  const body = await readJson(req, res)
  if (!body) return
  sweepExpiredPending(state, stateFile, rt)
  const code = String(body.code ?? "").trim()
  const deviceName = (String(body.deviceName ?? "手机").trim().slice(0, 32) || "手机")
  const via = String(body.via ?? "").trim() === "relay" ? "relay" : "lan"
  const suppliedRequestId = String(body.requestId ?? "").trim()
  if (suppliedRequestId && !/^[A-Za-z0-9._:-]{8,128}$/.test(suppliedRequestId)) {
    return json(res, 400, { error: "requestId 无效" })
  }
  // App reuses this identifier when LAN fails after the request may have
  // reached the host. The fingerprint intentionally excludes `via`: the
  // same logical pair may retry through Relay. Legacy clients without the
  // field deliberately do not enter the replay cache, preserving one-time
  // pairing semantics for older clients.
  const requestFingerprint = `${code}\n${deviceName}`
  const requestId = suppliedRequestId
  const cached = cachedPairRequest(rt, requestId, requestFingerprint)
  if (cached?.conflict) return json(res, 409, { error: "requestId 已用于另一配对请求" })
  if (cached) return json(res, 200, cached)
  // 局域网按对端 IP 隔离限流；经 Relay 的所有远端都会表现为本地回环地址，
  // 无法拿到真实对端 IP，因此把 via 纳入 key，让 Relay 尝试共享一个显式桶，
  // 并叠加 per-challenge / global 预算兜底（真 IP 隔离需 Relay 协议透传对端）。
  const clientKey =
    (via === "relay" ? "relay" : "lan") +
    "::" +
    String(req.socket?.remoteAddress ?? req.connection?.remoteAddress ?? "unknown")
  const ver = verifyPairingCode(state, code, clientKey)
  if (!ver.ok) {
    // pairingRates 仅内存；勿写入 state.json
    const throttled = /频繁|失效|稍后再试/.test(ver.error ?? "")
    json(res, throttled ? 429 : 401, { error: ver.error })
    return
  }
  // 同名设备不允许静默替换：先吊销旧设备或改名
  const existing = (state.devices ?? []).find((d) => d.name === deviceName)
  if (existing) {
    json(res, 409, { error: "已存在同名设备，请先吊销旧设备或更换名称" })
    return
  }
  const token = randomToken(24)
  const deviceId = `dev-${randomToken(8)}`
  const requireConfirm = pairRequireConfirm(config, state)
  const device = {
    deviceId,
    name: deviceName,
    tokenHash: hmacDeviceToken(state, token),
    createdAt: now(),
    lastSeenAt: now(),
    via,
  }
  if (requireConfirm) {
    // token 仍发给 App（旧客户端可存），但 API 在面板批准前一律 403。
    device.status = "pending"
    device.pendingExpiresAt = Date.now() + config.pairingTtlSeconds * 1000
    device.pairedFrom = displayRemoteAddress(
      req.socket?.remoteAddress ?? req.connection?.remoteAddress ?? "",
    )
  }
  state.devices = state.devices ?? []
  state.devices.push(device)
  consumePairingCode(state) // 配对码一次性：成功后立即失效
  saveState(stateFile, state)
  const result = {
    ok: true,
    token,
    deviceId,
    name: deviceName,
    urls: lanUrls(config).urls,
    pending: requireConfirm,
    pendingExpiresAt: requireConfirm ? device.pendingExpiresAt : undefined,
  }
  rememberPairRequest(rt, requestId, requestFingerprint, result)
  json(res, 200, result)
}

const MAX_ZSTD_OUTPUT_BYTES = 32 * 1024 * 1024

/** 拼接多帧 zstd 解压（DSH 存储按帧追加写入）。输出字节数硬顶，防压缩炸弹。 */
function zstdDecompressAll(buf) {
  return zstdDecompressSync(buf, { maxOutputLength: MAX_ZSTD_OUTPUT_BYTES })
}

/**
 * 从会话存储文件（jsonl.zstd）读取 reasoning 块，按块首事件 seq 索引
 * （RPC 投影会剥掉父会话的思考内容，文件是补全源）。
 * 返回 Map<seq, { seq, time, text }>；连续 reasoning block-end 合并为一个
 * 块（与投影的事件侧分组规则一致），id 以首个 block-end 的 seq 为键。
 */
function pageNeedsReasoningFile(events) {
  return (events ?? []).some((item) => {
    const c = item?.event?.data?.chunk
    return c?.type === "block-end" && c.block?.type === "reasoning" && !String(c.block?.text ?? "")
  })
}

async function readSessionReasoning(targetPort, sessionId, rt) {
  try {
    const list = await callLocalRpc(targetPort, "session.list", {})
    const row = (list.items ?? []).find((s) => s.sessionId === sessionId)
    const cwd = row?.cwd
    if (!cwd) return new Map()
    const enc = "--" + cwd.split("/").filter(Boolean).join("-") + "--"
    const p = join(homedir(), ".dsh", "sessions", enc, sessionId, "session.jsonl.zstd")
    if (!existsSync(p)) return new Map()
    let st
    try { st = statSync(p) } catch { return new Map() }
    if (st.size > 32 * 1024 * 1024) return new Map()
    const hit = rt?.reasoningCache?.get(p)
    if (hit && hit.mtime === st.mtimeMs && hit.size === st.size) return hit.map
    const text = zstdDecompressAll(await readFileAsync(p)).toString("utf8")
    const reasoning = new Map()
    let pending = null // { seq, time, text }
    const flush = (time) => {
      if (pending) {
        const text = pending.text.trim()
        if (text) reasoning.set(pending.seq, { seq: pending.seq, time: pending.time || time, text })
        pending = null
      }
    }
    // 与 src/history.js 的投影 flush 点保持一致，保证事件侧与文件侧分组相同
    const FLUSH_TYPES = new Set(["user/message", "approval/asked", "tool/call", "tool/result", "compaction/start", "todo/write"])
    for (const line of text.split("\n")) {
      if (!line.trim()) continue
      let e
      try { e = JSON.parse(line) } catch { continue }
      if (e?.type === "assistant/chunk") {
        const chunk = e?.data?.chunk
        if (chunk?.type === "block-end" && chunk.block?.type === "reasoning") {
          const blockText = chunk.block?.text
          if (blockText) {
            pending = pending
              ? { seq: pending.seq, time: pending.time, text: pending.text + "\n" + blockText }
              : { seq: e.seq, time: e.time, text: blockText }
          }
          continue
        }
        if (chunk?.type === "block-end" && chunk.block?.text) flush(e?.time ?? 0)
      } else if (e?.type === "assistant/message") {
        flush(e?.time ?? 0)
      } else if (FLUSH_TYPES.has(e?.type)) {
        flush(e?.time ?? 0)
      }
    }
    flush(0)
    rt?.reasoningCache?.set(p, { mtime: st.mtimeMs, size: st.size, map: reasoning })
    return reasoning
  } catch (err) {
    return new Map()
  }
}

function mapModelGroups(groups) {
  return (groups ?? []).map((g) => ({
    provider: g.id || g.provider || g.name || "未知",
    providerName: g.name || g.id || g.provider || "未知",
    models: (g.models ?? []).map((m) => ({
      id: m.id,
      name: m.name ?? m.id,
      contextWindow: m.contextWindow ?? null,
      maxTokens: m.maxTokens ?? null,
      reasoningEfforts: (m.reasoning?.efforts ?? [])
        .map((e) => (typeof e === "string" ? e : e.id))
        .filter(Boolean),
      defaultEffort: m.reasoning?.defaultEffort ?? null,
    })),
  }))
}

function uniqueRpcPayloads(payloads) {
  const seen = new Set()
  const out = []
  for (const payload of payloads) {
    if (!payload) continue
    const key = JSON.stringify(payload)
    if (seen.has(key)) continue
    seen.add(key)
    out.push(payload)
  }
  return out
}

async function selectSessionModel(targetPort, sessionId, provider, model, reasoningEffort, stillAuthorized = () => true) {
  let groups = []
  try {
    const catalog = await callLocalRpc(targetPort, "session.models", { sessionId })
    if (!stillAuthorized()) throw new Error("设备已被吊销")
    groups = catalog?.groups ?? []
  } catch {
    groups = []
  }
  const group =
    groups.find((g) => g.id === provider) ||
    groups.find((g) => g.name === provider) ||
    groups.find((g) => (g.models ?? []).some((m) => m.id === model || m.name === model))
  const resolvedProvider = group?.id || provider
  const resolvedModel = (group?.models ?? []).find((m) => m.id === model || m.name === model)?.id || model
  const modelMeta = (group?.models ?? []).find((m) => m.id === resolvedModel)
  const allowed = (modelMeta?.reasoning?.efforts ?? [])
    .map((e) => (typeof e === "string" ? e : e.id))
    .filter(Boolean)
  const defaultEffort = modelMeta?.reasoning?.defaultEffort
  const requested = typeof reasoningEffort === "string" && reasoningEffort.trim() ? reasoningEffort.trim() : null
  const effort = requested && (allowed.length === 0 || allowed.includes(requested))
    ? requested
    : defaultEffort && (allowed.length === 0 || allowed.includes(defaultEffort))
      ? defaultEffort
      : null
  const base = { sessionId, provider: resolvedProvider, model: resolvedModel }
  const attempts = uniqueRpcPayloads([
    effort ? { ...base, reasoningEffort: effort } : base,
    base,
    group?.id ? { sessionId, provider: group.id, model: resolvedModel } : null,
    group?.name ? { sessionId, provider: group.name, model: resolvedModel } : null,
  ])
  let lastErr
  for (const payload of attempts) {
    if (!stillAuthorized()) throw new Error("设备已被吊销")
    try {
      return await callLocalRpc(targetPort, "session.selectModel", payload)
    } catch (err) {
      lastErr = err
    }
  }
  throw lastErr ?? new Error("切换模型失败")
}

/**
 * 向本机 dsh /api 发 RPC 统一走 ./local-rpc.js 的 callLocalRpc：
 * 方法闭集在该模块的 RPC_METHOD_ALLOWLIST 锁定并有单测覆盖。
 */

// ---------- SSE 实时推送（/dsh-link/mobile/sessions/:id/stream） ----------
// 数据流：DSH 产生事件 → 写入 JSONL → 插件轮询 session.history RPC（增量 seq）→
// SSE emit → App 订阅 → Compose state 更新。轮询间隔可配，空闲会话用文件大小跳过 RPC。
const MAX_SSE_GLOBAL = 64
const MAX_SSE_PER_DEVICE = 8

function createRuntime() {
  return {
    sessionStreams: new Map(),
    sessionFiles: new Map(),
    inflightPolls: new Set(),
    // 单飞期间又被要求补洞的会话：轮询结束后立刻再跑一次
    pendingPolls: new Set(),
    // sessionId → { at, timer }，给 mux 补洞请求限流
    gapPolls: new Map(),
    pendingApprovals: new Map(),
    pendingQuestions: new Map(),
    reasoningCache: new Map(),
    deviceRequests: new Map(),
    pairingRequests: new Map(),
    deviceMutations: new DeviceMutationGate(),
  }
}

function sseCounts(rt) {
  let global = 0
  const perDevice = new Map()
  for (const writers of rt.sessionStreams.values()) {
    for (const conn of writers) {
      global++
      perDevice.set(conn.deviceId, (perDevice.get(conn.deviceId) ?? 0) + 1)
    }
  }
  return { global, perDevice }
}

function closeSseForDevice(rt, deviceId) {
  for (const [sessionId, writers] of [...rt.sessionStreams]) {
    for (const conn of [...writers]) {
      if (conn.deviceId !== deviceId) continue
      writers.delete(conn)
      try { conn.res.destroy() } catch {}
    }
    if (writers.size === 0) dropSession(rt, sessionId)
  }
  settleOrphanApprovals(rt)
}

function trackDeviceRequest(rt, deviceId, req, res) {
  let requests = rt.deviceRequests.get(deviceId)
  if (!requests) {
    requests = new Set()
    rt.deviceRequests.set(deviceId, requests)
  }
  const record = { req, res }
  requests.add(record)
  const done = () => {
    requests.delete(record)
    if (requests.size === 0) rt.deviceRequests.delete(deviceId)
  }
  req.once?.("aborted", done)
  res.once?.("finish", done)
  res.once?.("close", done)
  return done
}

function closeRequestsForDevice(rt, deviceId, exceptReq) {
  const requests = rt.deviceRequests.get(deviceId)
  if (!requests) return
  for (const record of [...requests]) {
    if (record.req === exceptReq) continue
    requests.delete(record)
    try { record.req.destroy() } catch {}
    try { record.res.destroy() } catch {}
  }
  if (requests.size === 0) rt.deviceRequests.delete(deviceId)
}

const DEVICE_REVOKING = Symbol("deviceRevoking")

function isDeviceAuthorized(state, device) {
  if (!device?.deviceId) return false
  const current = (state.devices ?? []).find((item) => item === device || item.deviceId === device.deviceId)
  return Boolean(current && !current[DEVICE_REVOKING])
}

/** 设备是否对该会话存在活跃 SSE 订阅（审批只能由正在操作该会话的设备处理）。 */
function isDeviceSubscribedToSession(rt, sessionId, deviceId) {
  const writers = rt.sessionStreams.get(sessionId)
  if (!writers || writers.size === 0) return false
  for (const conn of writers) {
    if (conn && conn.deviceId === deviceId) return true
  }
  return false
}

async function readAuthorizedJson(req, res, state, device, limit) {
  const body = await readJson(req, res, limit)
  if (!body) return null
  if (!isDeviceAuthorized(state, device)) {
    if (!res.headersSent) json(res, 401, { error: "设备已被吊销" })
    return null
  }
  return body
}

function settleOrphanApprovals(rt) {
  for (const rec of [...rt.pendingApprovals.values()]) {
    const writers = rec.sessionId ? rt.sessionStreams.get(rec.sessionId) : null
    if (!writers || writers.size === 0) {
      try { rec.settle("cancelled") } catch {}
    }
  }
  for (const rec of [...rt.pendingQuestions.values()]) {
    const writers = rec.sessionId ? rt.sessionStreams.get(rec.sessionId) : null
    if (!writers || writers.size === 0) {
      try { rec.settle(null, "cancelled") } catch {}
    }
  }
}

async function sessionFilePath(targetPort, sessionId, rt) {
  const cached = rt.sessionFiles.get(sessionId)
  if (cached?.missingUntil && Date.now() < cached.missingUntil) return null
  if (cached?.path) return cached.path
  try {
    const list = await callLocalRpc(targetPort, "session.list", {})
    const row = (list.items ?? []).find((s) => s.sessionId === sessionId)
    const cwd = row?.cwd
    if (!cwd) {
      rt.sessionFiles.set(sessionId, { missingUntil: Date.now() + 10_000 })
      return null
    }
    const enc = "--" + cwd.split("/").filter(Boolean).join("-") + "--"
    const p = join(homedir(), ".dsh", "sessions", enc, sessionId, "session.jsonl.zstd")
    if (!existsSync(p)) {
      rt.sessionFiles.set(sessionId, { missingUntil: Date.now() + 10_000 })
      return null
    }
    rt.sessionFiles.set(sessionId, { path: p, lastSize: undefined, lastMtime: undefined })
    return p
  } catch {
    return null
  }
}

function dropSession(rt, sessionId) {
  rt.sessionStreams.delete(sessionId)
  rt.sessionFiles.delete(sessionId)
  rt.pendingPolls.delete(sessionId)
  const gap = rt.gapPolls.get(sessionId)
  if (gap?.timer) clearTimeout(gap.timer)
  rt.gapPolls.delete(sessionId)
  settleOrphanApprovals(rt)
}

function writeSse(writers, frame) {
  for (const conn of [...writers]) {
    try {
      const ok = conn.res.write(frame)
      try { conn.res.flush?.() } catch {}
      if (ok === false) {
        writers.delete(conn)
        try { conn.res.destroy() } catch {}
      }
    } catch {
      writers.delete(conn)
      try { conn.res.destroy() } catch {}
    }
  }
}

/** mux 补洞的最小间隔：正常只在订阅初期抖一下，限流是为了防住 seq 空洞补不平时的 RPC 打转。 */
const GAP_POLL_MIN_INTERVAL_MS = 200

/**
 * mux 发现 seq 空洞时请求一次强制轮询。首次立刻跑，其余排一个尾随定时器，
 * 保证「不打转」的同时也「不丢最后一次修复请求」。
 */
function requestGapPoll(sessionId, targetPort, rt) {
  const rec = rt.gapPolls.get(sessionId)
  if (rec?.timer) return
  const wait = Math.max(0, GAP_POLL_MIN_INTERVAL_MS - (Date.now() - (rec?.at ?? 0)))
  if (wait === 0) {
    rt.gapPolls.set(sessionId, { at: Date.now(), timer: null })
    pollSession(sessionId, targetPort, rt, true)
    return
  }
  const timer = setTimeout(() => {
    rt.gapPolls.set(sessionId, { at: Date.now(), timer: null })
    pollSession(sessionId, targetPort, rt, true)
  }, wait)
  timer.unref?.()
  rt.gapPolls.set(sessionId, { at: rec?.at ?? 0, timer })
}

/**
 * 拉取并补发落后事件。force=true 用于补洞：跳过"文件未变化"短路，
 * 因为 mux 推送的事件可能还没落盘，此时 size/mtime 与上次完全一致。
 */
async function pollSession(sessionId, targetPort, rt, force = false) {
  const writers = rt.sessionStreams.get(sessionId)
  if (!writers || writers.size === 0) return
  if (rt.inflightPolls.has(sessionId)) {
    if (force) rt.pendingPolls.add(sessionId)
    return
  }
  rt.inflightPolls.add(sessionId)
  try {
    const filePath = await sessionFilePath(targetPort, sessionId, rt)
    if (!filePath && !force) return
    let st = null
    if (filePath) {
      try {
        st = statSync(filePath)
      } catch {
        if (!force) return
      }
    }
    const info = rt.sessionFiles.get(sessionId)
    if (!force && info && st && info.lastSize === st.size && info.lastMtime === st.mtimeMs) return
    const minCursor = Math.min(...[...writers].map((c) => c.lastSeq))
    const { events, projections } = await loadEventsAfter(minCursor, (payload) =>
      callLocalRpc(targetPort, "session.history", { sessionId, ...payload }),
    )
    for (const conn of [...writers]) {
      const forConn = events.filter((e) => e.seq > conn.lastSeq)
      for (const e of forConn) {
        try {
          const ok = conn.res.write(sseMessageFrame(e))
          try { conn.res.flush?.() } catch {}
          conn.lastSeq = e.seq
          if (ok === false) {
            writers.delete(conn)
            try { conn.res.destroy() } catch {}
            break
          }
        } catch {
          writers.delete(conn)
          try { conn.res.destroy() } catch {}
          break
        }
      }
      if (forConn.length > 0 && projections) {
        try { conn.res.write(`event: stats\ndata: ${JSON.stringify(projections)}\n\n`) } catch {}
      }
    }
    if (info && st) {
      info.lastSize = st.size
      info.lastMtime = st.mtimeMs
    }
  } catch {
    // RPC 失败/会话消失：静默跳过，下个周期重试
  } finally {
    rt.inflightPolls.delete(sessionId)
    if (rt.pendingPolls.delete(sessionId)) {
      pollSession(sessionId, targetPort, rt, true)
    }
  }
}

async function handleStreamRoute(sessionId, res, targetPort, config, req, rt, device) {
  try {
    const list = await callLocalRpc(targetPort, "session.list", {})
    if (!(list.items ?? []).some((s) => s.sessionId === sessionId)) {
      return json(res, 404, { error: "session not found" })
    }
  } catch {
    return json(res, 502, { error: "session lookup failed" })
  }
  const counts = sseCounts(rt)
  if (counts.global >= MAX_SSE_GLOBAL || (counts.perDevice.get(device.deviceId) ?? 0) >= MAX_SSE_PER_DEVICE) {
    return json(res, 429, { error: "too many streams" })
  }
  res.writeHead(200, {
    "content-type": "text/event-stream; charset=utf-8",
    "cache-control": "no-store, no-cache, must-revalidate",
    connection: "keep-alive",
    "x-accel-buffering": "no",
  })
  res.flushHeaders?.()
  try { res.socket?.setNoDelay?.(true) } catch {}
  let writers = rt.sessionStreams.get(sessionId)
  if (!writers) {
    writers = new Set()
    rt.sessionStreams.set(sessionId, writers)
  }
  const afterRaw = Number(new URL(req?.url ?? "/", "http://x").searchParams.get("afterSeq") ?? 0)
  const conn = {
    res,
    lastSeq: Number.isFinite(afterRaw) && afterRaw > 0 ? afterRaw : 0,
    deviceId: device.deviceId,
    // 补历史完成前不接受 mux 直推，避免与补发交错乱序
    seeded: false,
    missedWhileSeeding: false,
    seedQueue: [],
  }
  writers.add(conn)
  res.write(`event: ready\ndata: ${JSON.stringify({ resumeSeq: conn.lastSeq })}\n\n`)
  try { res.flush?.() } catch {}
  ;(async () => {
    try {
      const { events, projections } = await loadEventsAfter(conn.lastSeq, (payload) =>
        callLocalRpc(targetPort, "session.history", {
          sessionId,
          ...payload,
          maxMessages: payload.maxMessages ?? config.reconnectHistoryLimit,
        }),
      )
      for (const e of events) {
        if (e.seq <= conn.lastSeq) continue
        try {
          conn.res.write(sseMessageFrame(e))
          try { conn.res.flush?.() } catch {}
          conn.lastSeq = e.seq
        } catch {
          return
        }
      }
      if (projections) {
        try { conn.res.write(`event: stats\ndata: ${JSON.stringify(projections)}\n\n`) } catch {}
      }
    } catch {} finally {
      conn.seeded = true
      flushSeedQueue(conn, sessionId, (id) => requestGapPoll(id, targetPort, rt))
      // 补历史期间 mux 到的事件没人接，立刻补一次洞
      if (conn.missedWhileSeeding) pollSession(sessionId, targetPort, rt, true)
    }
  })()
  res.on("close", () => {
    const set = rt.sessionStreams.get(sessionId)
    set?.delete(conn)
    if (set && set.size === 0) dropSession(rt, sessionId)
    else settleOrphanApprovals(rt)
  })
}

function mobileSessionSummary(item) {
  const projections = item?.projections?.values ?? {}
  const title = typeof projections.title === "string" && projections.title.trim()
    ? projections.title.trim()
    : "未命名会话"
  const parentSessionId = item.parentSessionId
    ?? item.parentSession?.sessionId
    ?? item.parentSession?.id
    ?? item.spawn?.parentSessionId
    ?? null
  const subagentCountRaw = item.subagentCount ?? item.activeSubagentCount
    ?? (Array.isArray(item.subagents) ? item.subagents.length : null)
  return {
    sessionId: item.sessionId,
    title,
    updatedAt: item.updatedAt,
    running: Boolean(item.running),
    blank: Boolean(item.blank),
    cwd: item.cwd ?? null,
    agentPreset: item.agentPreset ?? null,
    origin: item.origin ?? null,
    parentSessionId: parentSessionId ?? null,
    subagentCount: Number.isFinite(subagentCountRaw) ? subagentCountRaw : null,
  }
}

function dropDevice(state, rt, device, exceptReq) {
  revokeDevice(null, device.deviceId)
  closeSseForDevice(rt, device.deviceId)
  closeRequestsForDevice(rt, device.deviceId, exceptReq)
}

async function revokeDeviceEntry(state, stateFile, rt, { name, deviceId }, exceptReq) {
  const targetName = String(name ?? "").trim()
  const targetId = String(deviceId ?? "").trim()
  if (!targetName && !targetId) return { status: 400, body: { error: "缺少设备名或 deviceId" } }
  const target = (state.devices ?? []).find((d) => d.name === targetName || d.deviceId === targetId)
  if (!target) return { status: 404, body: { error: "设备不存在" } }
  if (target[DEVICE_REVOKING]) return { status: 409, body: { error: "设备正在吊销" } }
  target[DEVICE_REVOKING] = true
  state.devices = (state.devices ?? []).filter((d) => d.deviceId !== target.deviceId)
  dropDevice(state, rt, target, exceptReq)
  // Linearization boundary: operations already registered finish before this
  // revocation is acknowledged; removal above prevents any new operation.
  await rt.deviceMutations.drain(target.deviceId)
  saveState(stateFile, state)
  return { status: 200, body: { ok: true, removed: 1, deviceId: target.deviceId } }
}

async function revokeAllDevices(state, stateFile, rt) {
  const devices = [...(state.devices ?? [])]
  for (const device of devices) device[DEVICE_REVOKING] = true
  state.devices = []
  for (const device of devices) dropDevice(state, rt, device)
  // As with single-device revocation, success means all earlier registered
  // mutations are settled and no later mutation can be admitted.
  await Promise.all(devices.map((device) => rt.deviceMutations.drain(device.deviceId)))
  saveState(stateFile, state)
  return { status: 200, body: { ok: true, removed: devices.length } }
}

function sweepExpiredPending(state, stateFile, rt) {
  const nowMs = Date.now()
  const kept = []
  const expired = []
  for (const device of state.devices ?? []) {
    if (isDevicePending(device) && nowMs >= (device.pendingExpiresAt ?? 0)) expired.push(device)
    else kept.push(device)
  }
  if (!expired.length) return
  state.devices = kept
  for (const device of expired) dropDevice(state, rt, device)
  saveState(stateFile, state)
}

function activatePendingDevice(state, stateFile, deviceId) {
  const id = String(deviceId ?? "").trim()
  if (!id) return { status: 400, body: { error: "缺少 deviceId" } }
  const target = (state.devices ?? []).find((d) => d.deviceId === id)
  if (!target) return { status: 404, body: { error: "设备不存在" } }
  if (!isDevicePending(target)) return { status: 409, body: { error: "设备无需确认" } }
  if (Date.now() >= (target.pendingExpiresAt ?? 0)) {
    return { status: 404, body: { error: "待确认已过期，请重新配对" } }
  }
  delete target.status
  delete target.pendingExpiresAt
  delete target.pairedFrom
  saveState(stateFile, state)
  return { status: 200, body: { ok: true, deviceId: target.deviceId, name: target.name } }
}

async function handleMobileApi(req, res, targetPort, state, stateFile, device, pathname, rt) {
  try {
    if (req.method !== "GET" && req.method !== "HEAD") {
      if (!requireJsonWrite(req, res)) return
    }
    if (req.method === "GET" && pathname === "/dsh-link/mobile/bootstrap") {
      const value = await callLocalRpc(targetPort, "session.list", {})
      const sessions = (value.items ?? []).map(mobileSessionSummary)
      return json(res, 200, {
        version: 1,
        host: { name: hostname(), deviceId: state.deviceId },
        device: { name: device.name },
        sessions,
        webPath: "/",
      })
    }

    if (req.method === "GET" && pathname === "/dsh-link/mobile/sessions") {
      const value = await callLocalRpc(targetPort, "session.list", {})
      return json(res, 200, { version: 1, sessions: (value.items ?? []).map(mobileSessionSummary) })
    }

    if (req.method === "GET" && pathname === "/dsh-link/mobile/sessions/search") {
      const query = String(new URL(req.url ?? "/", "http://x").searchParams.get("q") ?? "").trim()
      if (!query) return json(res, 400, { error: "缺少搜索关键词" })
      try {
        const value = await callLocalRpc(targetPort, "session.search", { query })
        return json(res, 200, { version: 1, items: value.items ?? [], hasMore: Boolean(value.hasMore), degraded: false })
      } catch (err) {
        // 内容搜索不可用（索引禁用）时降级为名称匹配（DSH search.unavailable 行为）
        const list = await callLocalRpc(targetPort, "session.list", {})
        const withTitle = (list.items ?? []).map((s) => ({ ...s, title: mobileSessionSummary(s).title }))
        const items = withTitle
          .filter((s) => String(s.title ?? "").toLowerCase().includes(query.toLowerCase()))
          .slice(0, 20)
          .map((s) => ({ sessionId: s.sessionId, snippet: s.title ?? "" }))
        return json(res, 200, { version: 1, items, hasMore: false, degraded: true })
      }
    }

    if (req.method === "POST" && pathname === "/dsh-link/mobile/sessions") {
      const body = await readAuthorizedJson(req, res, state, device)
  if (!body) return
      const payload = {}
      if (typeof body.workspaceId === "string" && body.workspaceId.trim()) payload.workspaceId = body.workspaceId.trim()
      else if (typeof body.cwd === "string" && body.cwd.trim()) payload.cwd = body.cwd.trim()
      if (typeof body.agentPreset === "string" && body.agentPreset.trim()) payload.agentPreset = body.agentPreset.trim()
      const value = await callLocalRpc(targetPort, "session.create", payload)
      return json(res, 201, { version: 1, sessionId: value.sessionId, agentPreset: value.agentPreset ?? null })
    }

    if (req.method === "GET" && pathname === "/dsh-link/mobile/models") {
      const sessionId = String(new URL(req.url ?? "/", "http://x").searchParams.get("sessionId") ?? "").trim()
      if (!sessionId) return json(res, 400, { error: "缺少 sessionId" })
      const value = await callLocalRpc(targetPort, "session.models", { sessionId })
      return json(res, 200, {
        version: 1,
        current: value.current ?? null,
        groups: mapModelGroups(value.groups),
        failures: value.failures ?? [],
      })
    }

    if (req.method === "GET" && pathname === "/dsh-link/mobile/llm-models") {
      const value = await callLocalRpc(targetPort, "llm.models", {})
      return json(res, 200, {
        version: 1,
        groups: mapModelGroups(value.groups),
        failures: value.failures ?? [],
      })
    }

    const modelMatch = pathname.match(/^\/dsh-link\/mobile\/sessions\/([^/]+)\/model$/)
    if (req.method === "POST" && modelMatch) {
      const sessionId = decodeURIComponent(modelMatch[1])
      const body = await readAuthorizedJson(req, res, state, device)
  if (!body) return
      const provider = String(body.provider ?? "").trim()
      const model = String(body.model ?? "").trim()
      if (!provider || !model) return json(res, 400, { error: "缺少 provider 或 model" })
      const value = await selectSessionModel(targetPort, sessionId, provider, model, body.reasoningEffort, () => isDeviceAuthorized(state, device))
      return json(res, 200, { ok: true, selected: value.selected ?? null })
    }

    if (req.method === "GET" && pathname === "/dsh-link/mobile/workspaces") {
      const value = await callLocalRpc(targetPort, "workspace.list", {})
      return json(res, 200, { version: 1, workspaces: value.items ?? [], archivedSessionIds: value.archivedSessionIds ?? [] })
    }

    if (req.method === "POST" && pathname === "/dsh-link/mobile/workspaces") {
      const body = await readAuthorizedJson(req, res, state, device)
  if (!body) return
      try {
        const list = await callLocalRpc(targetPort, "workspace.list", {})
        if (!isDeviceAuthorized(state, device)) return json(res, 401, { error: "设备已被吊销" })
        const plan = planMobileWorkspaceCreate({
          input: body.input ?? body.path,
          parentWorkspaceId: body.parentWorkspaceId,
          workspaces: list.items ?? [],
        })
        const { directoryCreated, value } = await rt.deviceMutations.run(device.deviceId, async () => {
          const { directoryCreated } = await ensureMobileWorkspaceDirectory(plan)
          const value = await callLocalRpc(targetPort, "workspace.create", { path: plan.path })
          return { directoryCreated, value }
        })
        return json(res, 200, {
          ok: true,
          workspace: value.workspace ?? null,
          created: Boolean(value.created),
          directoryCreated,
          inputKind: plan.inputKind,
          resolvedPath: plan.path,
        })
      } catch (error) {
        if (error instanceof MobileWorkspaceCreateError) {
          return json(res, error.status, {
            error: error.message,
            code: error.code,
            details: error.details,
          })
        }
        if (error instanceof LocalRpcError && error.code === "workspace-invalid-path") {
          return json(res, 400, {
            error: error.message,
            code: error.code,
            details: error.details,
          })
        }
        throw error
      }
    }

    // 删除工作区（取消注册；会话日志不删除，DSH workspace.delete 语义）
    if (req.method === "POST" && pathname === "/dsh-link/mobile/workspaces/delete") {
      const body = await readAuthorizedJson(req, res, state, device)
  if (!body) return
      const path = String(body.path ?? "").trim()
      if (!path) return json(res, 400, { error: "缺少工作区路径" })
      const list = await callLocalRpc(targetPort, "workspace.list", {})
      if (!isDeviceAuthorized(state, device)) return json(res, 401, { error: "设备已被吊销" })
      const item = (list.items ?? []).find((w) => w.path === path)
      if (!item) return json(res, 404, { error: "工作区不存在" })
      const value = await callLocalRpc(targetPort, "workspace.delete", { workspaceId: item.workspaceId })
      return json(res, 200, { ok: true, deleted: Boolean(value?.deleted), workspaceId: item.workspaceId })
    }

    // 移动端设置（WI-004）：透传 DSH settings seam（loopback-only，由插件代调）。
    // describe 响应已由 seam 脱敏（role=secret 字段不携带值，只暴露 path+set）。
    if (req.method === "GET" && pathname === "/dsh-link/mobile/settings") {
      const value = await callLocalRpc(targetPort, "settings.describe", {})
      return json(res, 200, {
        version: 1,
        writable: Boolean(value.writable),
        namespaces: (value.namespaces ?? []).map((ns) => ({
          ns: ns.ns,
          value: ns.value ?? null,
          user: ns.user ?? null,
          applies: ns.applies ?? "restart",
          secrets: (ns.secrets ?? []).map((s) => ({ path: s.path ?? [], set: Boolean(s.set) })),
          revision: typeof ns.revision === "number" ? ns.revision : 0,
        })),
      })
    }

    if (req.method === "POST" && pathname === "/dsh-link/mobile/settings/update") {
      const body = await readAuthorizedJson(req, res, state, device)
  if (!body) return
      const ns = String(body.ns ?? "").trim()
      if (!ns) return json(res, 400, { error: "缺少命名空间" })
      const patch = body.patch
      if (typeof patch !== "object" || patch === null || Array.isArray(patch)) {
        return json(res, 400, { error: "patch 必须是对象" })
      }
      const filtered = filterSettingsPatch(ns, patch)
      if (filtered.error) return json(res, 403, { error: filtered.error })
      const payload = { ns, patch }
      if (Number.isInteger(body.expectedRevision)) payload.expectedRevision = body.expectedRevision
      const value = await callLocalRpc(targetPort, "settings.update", payload)
      const nsView = value
      return json(res, 200, {
        version: 1,
        ns: nsView.ns ?? ns,
        value: nsView.value ?? null,
        user: nsView.user ?? null,
        applies: nsView.applies ?? "restart",
        secrets: (nsView.secrets ?? []).map((s) => ({ path: s.path ?? [], set: Boolean(s.set) })),
        revision: typeof nsView.revision === "number" ? nsView.revision : 0,
      })
    }

    if (req.method === "GET" && pathname === "/dsh-link/mobile/agent-presets") {
      const value = await callLocalRpc(targetPort, "agentPreset.list", {})
      return json(res, 200, {
        version: 1,
        presets: (value.presets ?? []).map((p) => ({
          id: p.id,
          name: p.name ?? p.id,
          description: p.description ?? "",
          isDefault: Boolean(p.isDefault),
        })),
      })
    }

    if (req.method === "GET" && pathname === "/dsh-link/mobile/devices") {
      const devices = [...(state.devices ?? [])]
        .sort((a, b) => (b.lastSeenAt ?? 0) - (a.lastSeenAt ?? 0))
        .map(publicDevice)
      return json(res, 200, {
        version: 1,
        devices,
      })
    }

    if (req.method === "POST" && pathname === "/dsh-link/mobile/revoke") {
      const body = await readAuthorizedJson(req, res, state, device)
      if (!body) return
      const result = await revokeDeviceEntry(state, stateFile, rt, {
        name: body.name,
        deviceId: body.deviceId,
      }, req)
      return json(res, result.status, result.body)
    }

    const renameMatch = pathname.match(/^\/dsh-link\/mobile\/sessions\/([^/]+)\/rename$/)
    if (req.method === "POST" && renameMatch) {
      const sessionId = decodeURIComponent(renameMatch[1])
      const body = await readAuthorizedJson(req, res, state, device)
  if (!body) return
      const title = String(body.title ?? "").trim()
      if (!title) return json(res, 400, { error: "缺少会话名称" })
      const value = await callLocalRpc(targetPort, "session.rename", { sessionId, title })
      return json(res, 200, { ok: true, title: value.title ?? title })
    }

    const forkMatch = pathname.match(/^\/dsh-link\/mobile\/sessions\/([^/]+)\/fork$/)
    if (req.method === "POST" && forkMatch) {
      const sessionId = decodeURIComponent(forkMatch[1])
      const value = await callLocalRpc(targetPort, "session.fork", { sessionId })
      return json(res, 200, { ok: true, sessionId: value.sessionId ?? null })
    }

    // 删除会话（服务端归档，workspace.archiveSession 语义；对标 web 删除）
    const archiveMatch = pathname.match(/^\/dsh-link\/mobile\/sessions\/([^/]+)\/archive$/)
    if (req.method === "POST" && archiveMatch) {
      const sessionId = decodeURIComponent(archiveMatch[1])
      const value = await callLocalRpc(targetPort, "workspace.archiveSession", { sessionId })
      return json(res, 200, { ok: true, archived: Boolean(value?.ok), sessionId })
    }

    const approvalMatch = pathname.match(/^\/dsh-link\/mobile\/sessions\/([^/]+)\/approval$/)
    if (req.method === "POST" && approvalMatch) {
      const sessionId = decodeURIComponent(approvalMatch[1])
      const body = await readAuthorizedJson(req, res, state, device)
  if (!body) return
      const approvalId = String(body.approvalId ?? "").trim()
      const outcome = String(body.outcome ?? "").trim()
      if (!approvalId || !["allowed-once", "rejected"].includes(outcome)) {
        return json(res, 400, { error: "缺少 approvalId 或 outcome 无效" })
      }
      const pending = rt.pendingApprovals.get(approvalId)
      if (!pending) {
        return json(res, 409, { ok: false, accepted: false, error: "审批已结束或不存在" })
      }
      // 审批绑定会话：URL sessionId 必须与审批记录一致，且只能由当前订阅该会话 SSE 的设备处理。
      // 防止另一台只读过 history、并未在该会话上活跃连接的设备代答（放行危险工具或 DoS）。
      if (pending.sessionId !== sessionId) {
        return json(res, 409, { ok: false, accepted: false, error: "审批会话不匹配" })
      }
      if (!isDeviceSubscribedToSession(rt, pending.sessionId, device.deviceId)) {
        return json(res, 403, { ok: false, accepted: false, error: "仅该会话的当前连接设备可处理审批" })
      }
      pending.settle(outcome)
      return json(res, 200, { ok: true, accepted: true, handledBy: "plugin" })
    }

    const questionMatch = pathname.match(/^\/dsh-link\/mobile\/sessions\/([^/]+)\/question$/)
    if (req.method === "POST" && questionMatch) {
      const sessionId = decodeURIComponent(questionMatch[1])
      const body = await readAuthorizedJson(req, res, state, device)
      if (!body) return
      const rpcId = String(body.rpcId ?? "").trim()
      const answer = body.answer
      if (!rpcId || !answer || !Array.isArray(answer.answers)) {
        return json(res, 400, { error: "缺少 rpcId 或 answer.answers" })
      }
      try {
        const pending = rt.pendingQuestions.get(rpcId)
        if (pending) {
          if (pending.sessionId !== sessionId) {
            return json(res, 409, { ok: false, accepted: false, error: "澄清会话不匹配" })
          }
          pending.settle(answer)
          return json(res, 200, { ok: true, accepted: true, handledBy: "plugin" })
        }
        const result = await respondQuestion(targetPort, rpcId, sessionId, answer)
        const accepted = result?.accepted === true
        return json(res, accepted ? 200 : 409, { ok: accepted, accepted, result })
      } catch (err) {
        return json(res, 502, { error: err?.message ?? "question respond failed" })
      }
    }

    const historyMatch = pathname.match(/^\/dsh-link\/mobile\/sessions\/([^/]+)\/history$/)
    if (req.method === "GET" && historyMatch) {
      const sessionId = decodeURIComponent(historyMatch[1])
      // 分页（session.history RPC：beforeSeq 从窗口尾部向前翻页，maxMessages 按消息边界计数）
      const search = new URL(req.url ?? "/", "http://x").searchParams
      const rpcPayload = { sessionId }
      const rawBeforeSeq = search.get("beforeSeq")
      const rawMaxMessages = search.get("maxMessages")
      const beforeSeq = Number(rawBeforeSeq ?? "")
      const maxMessages = clampHistoryMaxMessages(rawMaxMessages)
      const isTailPage = rawBeforeSeq === null && rawMaxMessages === null
      if (!isTailPage && Number.isInteger(beforeSeq) && beforeSeq > 0) rpcPayload.beforeSeq = beforeSeq
      if (maxMessages !== undefined) rpcPayload.maxMessages = maxMessages
      const value = await callLocalRpc(targetPort, "session.history", rpcPayload)
      const rawEvents = value.events ?? []
      // 会话统计（tokenUsage/sessionStats/contextPressure/contextBreakdown/todos projections）
      const projValues = value.projections?.values ?? {}
      const statsPayload = {
        tokenUsage: projValues.tokenUsage ?? null,
        sessionStats: projValues.sessionStats ?? null,
        contextPressure: projValues.contextPressure ?? null,
        contextBreakdown: projValues.contextBreakdown ?? null,
        todos: projValues.todos ?? null,
      }
      // 投影为稳定消息列表（WI-001）：reasoning 只合并到覆盖其 seq 窗口的页面，
      // 消息 id 以事件 seq 为键（跨页稳定），assistant/message 与同页 block-end 共享 id。
      const reasoningBySeq = pageNeedsReasoningFile(rawEvents)
        ? await readSessionReasoning(targetPort, sessionId, rt)
        : new Map()
      const projected = projectHistoryPage({
        events: rawEvents,
        reasoningBySeq,
        hasMore: value.hasMore ?? false,
      })
      const messages = projected.messages
      // 会话被停止/失败/截断时，最后一条 turn/end reason 非 completed（如 interrupted/stopped/error/maxTokens）
      return json(res, 200, {
        ok: true,
        sessionId,
        messages,
        hasMore: projected.hasMore,
        // 翻页游标：本页最早事件的 seq（App 下一次请求 beforeSeq=该值）
        nextBeforeSeq: projected.nextBeforeSeq,
        // 本页最新事件的 seq（App 作为 SSE 去重基线，只随 tail 页有意义）
        maxSeq: projected.maxSeq,
        stoppedReason: projected.stoppedReason,
        stats: statsPayload,
      })
    }

    const promptMatch = pathname.match(/^\/dsh-link\/mobile\/sessions\/([^/]+)\/prompt$/)
    if (req.method === "POST" && promptMatch) {
      const sessionId = decodeURIComponent(promptMatch[1])
      const body = await readAuthorizedJson(req, res, state, device, PROMPT_BODY_LIMIT)
      if (!body) return
      const text = String(body.text ?? "").trim()
      // 图片附件（DSH prompt image 块：base64 data + mediaType）
      const images = Array.isArray(body.images) ? body.images : []
      const content = []
      for (const img of images.slice(0, 4)) {
        const mediaType = String(img.mediaType ?? "").trim().toLowerCase()
        const data = String(img.data ?? "").trim()
        if (!mediaType || !data) continue
        if (!PROMPT_IMAGE_MEDIA_TYPES.has(mediaType)) {
          return json(res, 400, { error: "图片类型不允许" })
        }
        if (data.length > PROMPT_IMAGE_DATA_LIMIT) {
          return json(res, 413, { error: "图片过大" })
        }
        content.push({ type: "image", mediaType, data })
      }
      if (!text && content.length === 0) return json(res, 400, { error: "消息内容不能为空" })
      if (text) content.push({ type: "text", text })
      const resVal = await callLocalRpc(targetPort, "session.prompt", {
        sessionId,
        requestId: "mobile-" + randomBytes(12).toString("hex"),
        mode: body.mode || "queue",
        content,
      })
      return json(res, 200, { ok: true, result: resVal })
    }

    const cancel = pathname.match(/^\/dsh-link\/mobile\/sessions\/([^/]+)\/cancel$/)
    if (req.method === "POST" && cancel) {
      const sessionId = decodeURIComponent(cancel[1])
      await callLocalRpc(targetPort, "session.cancel", { sessionId })
      return json(res, 200, { ok: true, sessionId })
    }

    return json(res, 404, { error: "mobile endpoint not found" })
  } catch (error) {
    console.error(`dsh-links: mobile API error: ${error?.message ?? error}`)
    return json(res, 502, { error: "mobile API unavailable" })
  }
}

const PANEL_ONLY_PATHS = new Set([
  "/dsh-link/pair-info",
  "/dsh-link/qr.png",
  "/dsh-link/revoke",
  "/dsh-link/revoke-all",
  "/dsh-link/pair-approve",
  "/dsh-link/pair-settings",
  "/dsh-link/devices",
  "/dsh-link/relay-status",
  "/dsh-link/relay-enroll",
  "/dsh-link/relay-disconnect",
])

const APPROVAL_OUTCOMES = new Set(["allowed-once", "rejected", "cancelled", "unavailable"])
const APPROVAL_TIMEOUT_MS = 5 * 60 * 1000

function sessionEvents(session) {
  if (typeof session?.snapshotEvents === "function") {
    try {
      const events = session.snapshotEvents()
      if (Array.isArray(events)) return events
    } catch {}
  }
  return Array.isArray(session?.events) ? session.events : []
}

function findApprovalId(req) {
  const events = sessionEvents(req?.agent?.session)
  const decided = new Set()
  for (let i = events.length - 1; i >= 0; i--) {
    const event = events[i]
    if (event?.type === "approval/decided") decided.add(event.data?.id)
    else if (event?.type === "approval/asked") {
      if (decided.has(event.data?.id)) continue
      if ((req.callId ?? null) !== (event.data?.callId ?? null)) continue
      return event.data.id
    }
  }
  return req?.id ?? req?.approvalId ?? req?.payload?.id ?? null
}

export function apply(ctx, config) {
  const rt = createRuntime()
  const web = ctx.get("webServer")
  const gateway = ctx.get("typertGateway") ?? ctx.typertGateway
  if (gateway && typeof gateway.invoke === "function") {
    bindLocalRpcRuntime({
      invoke: (request) => gateway.invoke(request),
      stream: typeof gateway.stream === "function" ? (request) => gateway.stream(request) : undefined,
    })
  }
  const targetPort = web.port
  const stateFile = statePathOf(config)
  const state = loadState(stateFile)
  hydratePairing(state)
  if (!state.deviceId) state.deviceId = `dsh-${randomBytes(8).toString("hex")}`
  if (!state.devices) state.devices = []
  if (!state.pairing) state.pairing = {}
  ensureTokenKey(state)
  const tlsHolder = { fingerprint: "" }
  // 旧版设备（无 deviceId）一次性迁移：自动补发，手机无需重新配对（token 不变）
  let migrated = false
  for (const d of state.devices) {
    if (!d.deviceId) {
      d.deviceId = `dev-${randomToken(8)}`
      migrated = true
    }
    const via = normalizeDeviceVia(d)
    if (d.via !== via) {
      d.via = via
      migrated = true
    }
  }
  saveState(stateFile, state)
  sweepExpiredPending(state, stateFile, rt)
  if (migrated) ctx.logger.info("dsh-links: 已为旧设备补发 deviceId")

  const fp = () => tlsHolder.fingerprint
  let relayAgent = null
  const stopRelayAgent = () => {
    try { relayAgent?.stop() } catch {}
    relayAgent = null
  }
  const startRelayAgent = () => {
    stopRelayAgent()
    if (!state.relay?.routeSecret || !state.relay?.agentAddress) return
    if (state.relay.insecureTls && !state.relay.tlsPinTrusted) {
      const fp = String(state.relay.tlsFingerprint ?? "").replace(/[:\s]/g, "").toLowerCase()
      if (/^[0-9a-f]{64}$/.test(fp)) {
        state.relay.tlsFingerprint = fp
        state.relay.tlsPinTrusted = true
        saveState(stateFile, state)
      } else {
        ctx.logger.warn("dsh-links relay: 旧版自签 TLS 配置未经过指纹确认，请重新接入")
        return
      }
    }
    relayAgent = new RelayAgent({
      address: state.relay.agentAddress,
      credentials: {
        keys: hostKeyFromSeed(unb64u(state.relay.hostSeed), unb64u(state.relay.hostPublicKey)),
        routeId: state.relay.routeId,
        routeSecret: state.relay.routeSecret,
        capability: state.relay.capability,
        generation: state.relay.generation || 1,
      },
      onCapabilityRenewed: (capability) => {
        if (!state.relay) throw new Error("Relay 配置已不存在")
        const previous = state.relay.capability
        state.relay.capability = capability
        try {
          saveState(stateFile, state)
        } catch (err) {
          state.relay.capability = previous
          throw err
        }
      },
      pluginPort: config.port,
      logger: ctx.logger,
      insecureTls: Boolean(state.relay.insecureTls),
      tlsFingerprint: state.relay.tlsFingerprint ?? "",
    })
    relayAgent.start().catch((err) => ctx.logger.warn(`dsh-links relay: ${err?.message ?? err}`))
  }

  // ---------- 主 web 服务上的路由（网页界面「手机连接」面板用；回环同源围栏） ----------
  const disposers = [
    web.register({
      kind: "exact",
      path: "/dsh-link/pair-info",
      handler: (req, res) => {
        if (!requireLoopbackSameOrigin(req, res)) return
        sweepExpiredPending(state, stateFile, rt)
        json(res, 200, pairInfo(config, state, fp(), pairVia(req)))
      },
    }),
    web.register({
      kind: "exact",
      path: "/dsh-link/qr.png",
      handler: (req, res) => {
        if (!requireLoopbackSameOrigin(req, res)) return
        return qrPng(res, config, state, fp(), pairVia(req))
      },
    }),
    web.register({
      kind: "exact",
      path: "/dsh-link/revoke",
      handler: async (req, res) => {
        if (!requireLoopbackSameOrigin(req, res)) return
        if (!requireJsonWrite(req, res)) return
        const body = await readJson(req, res)
  if (!body) return
        const targetName = String(body.name ?? "").trim()
        const targetId = String(body.deviceId ?? "").trim()
        if (!targetName && !targetId) return json(res, 400, { error: "缺少设备名或 deviceId" })
        const result = await revokeDeviceEntry(state, stateFile, rt, { name: targetName, deviceId: targetId })
        json(res, result.status, result.body)
      },
    }),
    web.register({
      kind: "exact",
      path: "/dsh-link/revoke-all",
      handler: async (req, res) => {
        if (!requireLoopbackSameOrigin(req, res)) return
        if (!requireJsonWrite(req, res)) return
        const body = await readJson(req, res)
        if (!body) return
        const result = await revokeAllDevices(state, stateFile, rt)
        json(res, result.status, result.body)
      },
    }),
    web.register({
      kind: "exact",
      path: "/dsh-link/pair-approve",
      handler: async (req, res) => {
        if (!requireLoopbackSameOrigin(req, res)) return
        if (!requireJsonWrite(req, res)) return
        const body = await readJson(req, res)
        if (!body) return
        sweepExpiredPending(state, stateFile, rt)
        const result = activatePendingDevice(state, stateFile, body.deviceId)
        json(res, result.status, result.body)
      },
    }),
    web.register({
      kind: "exact",
      path: "/dsh-link/pair-settings",
      handler: async (req, res) => {
        if (!requireLoopbackSameOrigin(req, res)) return
        if (!requireJsonWrite(req, res)) return
        const body = await readJson(req, res)
        if (!body) return
        if (typeof body.requireConfirm !== "boolean") {
          return json(res, 400, { error: "缺少 requireConfirm" })
        }
        // 只改此后新配对的默认行为；已排队的 pending 必须逐台批准/拒绝/到期，避免误关开关放行攻击者。
        state.pairRequireConfirm = body.requireConfirm
        saveState(stateFile, state)
        json(res, 200, { ok: true, requireConfirm: pairRequireConfirm(config, state) })
      },
    }),
    web.register({
      kind: "exact",
      path: "/dsh-link/devices",
      handler: (req, res) => {
        if (!requireLoopbackSameOrigin(req, res)) return
        sweepExpiredPending(state, stateFile, rt)
        json(res, 200, {
          devices: state.devices.map(publicDevice),
        })
      },
    }),
    web.register({
      kind: "exact",
      path: "/dsh-link/relay-status",
      handler: (req, res) => {
        if (!requireLoopbackSameOrigin(req, res)) return
        json(res, 200, {
          status: relayAgent?.status ?? (state.relay?.routeId ? "offline" : "idle"),
          error: relayAgent?.error ?? "",
          enrolled: Boolean(state.relay?.routeId && state.relay?.routeSecret),
          agentAddress: state.relay?.agentAddress ?? "",
          clientAddress: state.relay?.clientAddress ?? "",
          insecureTls: Boolean(state.relay?.insecureTls),
          tlsFingerprint: state.relay?.tlsFingerprint ?? "",
          tlsPinTrusted: Boolean(state.relay?.tlsPinTrusted),
        })
      },
    }),
    web.register({
      kind: "exact",
      path: "/dsh-link/relay-enroll",
      handler: async (req, res) => {
        if (!requireLoopbackSameOrigin(req, res)) return
        if (!requireJsonWrite(req, res)) return
        const body = await readJson(req, res)
        if (!body) return
        const inviteCodeRaw = String(body.inviteCode ?? "").trim()
        const addressRaw = String(body.address ?? "").trim()
        let inviteCode = inviteCodeRaw
        let address = addressRaw
        let insecureTls = body.insecureTls === true
        let tlsFingerprint = ""
        let fromEnroll = null
        try {
          fromEnroll = resolveEnrollText(body.enroll, { address: addressRaw, insecureTls, tlsFingerprint: body.tlsFingerprint })
            || resolveEnrollText(inviteCodeRaw, { address: addressRaw, insecureTls, tlsFingerprint: body.tlsFingerprint })
        } catch (err) {
          return json(res, 400, { error: err?.message ?? "接入码无效" })
        }
        if (fromEnroll) {
          address = fromEnroll.address
          inviteCode = fromEnroll.inviteCode
          insecureTls = fromEnroll.insecureTls
          tlsFingerprint = fromEnroll.tlsFingerprint
        } else if (insecureTls) {
          try {
            tlsFingerprint = normalizeTlsFingerprint(body.tlsFingerprint)
          } catch (err) {
            return json(res, 400, { error: err?.message ?? "TLS 指纹无效" })
          }
        }
        if (!inviteCode || !address) return json(res, 400, { error: "请填写 Relay 地址和接入码" })
        try {
          const derived = deriveAddresses(address)
          let keys
          if (state.relay?.hostSeed && state.relay?.hostPublicKey) {
            keys = hostKeyFromSeed(unb64u(state.relay.hostSeed), unb64u(state.relay.hostPublicKey))
          } else {
            keys = generateHostKey()
          }
          const enrolled = await enrollRelay({
            address: derived.agentAddress,
            inviteCode,
            hostId: state.deviceId,
            keys,
            insecureTls,
            tlsFingerprint,
          })
          state.relay = {
            agentAddress: derived.agentAddress,
            clientAddress: derived.clientAddress,
            hostSeed: keys.seed.toString("base64url"),
            hostPublicKey: keys.publicKey.toString("base64url"),
            routeId: enrolled.routeId,
            routeSecret: enrolled.routeSecret,
            capability: enrolled.capability,
            generation: enrolled.generation,
            tlsFingerprint: enrolled.tlsFingerprint,
            insecureTls,
            tlsPinTrusted: insecureTls,
          }
          saveState(stateFile, state)
          startRelayAgent()
          json(res, 200, { ok: true, agentAddress: derived.agentAddress, clientAddress: derived.clientAddress })
        } catch (err) {
          const raw = String(err?.message ?? "接入失败")
          const error = raw === "enroll failed" ? "接入未成功。请到控制台新创建一次接入码后再试。" : raw
          json(res, 400, { error })
        }
      },
    }),
    web.register({
      kind: "exact",
      path: "/dsh-link/relay-disconnect",
      handler: async (req, res) => {
        if (!requireLoopbackSameOrigin(req, res)) return
        if (req.method !== "POST") return json(res, 405, { error: "method not allowed" })
        stopRelayAgent()
        if (state.relay) {
          delete state.relay.routeId
          delete state.relay.routeSecret
          delete state.relay.capability
          delete state.relay.generation
          delete state.relay.tlsFingerprint
          delete state.relay.tlsPinTrusted
        }
        saveState(stateFile, state)
        json(res, 200, { ok: true })
      },
    }),
  ]

  /**
   * 接管 approval/request waterfall（dsh 内部 API：事件名是「approval/request」，
   * 签名是 (req, next)，不是元事件 "waterfall"）。无手机 SSE 时必须 next()，
   * 否则会把桌面审批一并挂死。
   */
  ctx.on("approval/request", (req, next) => {
    if (req?.signal?.aborted === true) return Promise.resolve("cancelled")
    const sessionId = req?.agent?.session?.id
    const writers = sessionId ? rt.sessionStreams.get(sessionId) : null
    if (!writers || writers.size === 0) return next()
    const id = findApprovalId(req)
    if (!id) return next()
    return new Promise((resolve) => {
      const settle = (outcome) => {
        const rec = rt.pendingApprovals.get(id)
        if (!rec) return
        rt.pendingApprovals.delete(id)
        if (rec.timer) clearTimeout(rec.timer)
        try { req.signal?.removeEventListener("abort", rec.onAbort) } catch {}
        resolve(APPROVAL_OUTCOMES.has(outcome) ? outcome : "unavailable")
      }
      const onAbort = () => settle("cancelled")
      const timer = setTimeout(() => settle("unavailable"), APPROVAL_TIMEOUT_MS)
      rt.pendingApprovals.set(id, { settle, timer, onAbort, sessionId })
      req.signal?.addEventListener("abort", onAbort, { once: true })
    })
  })

  /**
   * 0.1.2 起澄清卡走 `user-questions/request` waterfall，不再经 /api/events.mux。
   * 有手机 SSE 时由插件代答；否则 next() 把问题交给网页 UI。
   */
  ctx.on("user-questions/request", (req, next) => {
    if (req?.signal?.aborted === true) return next()
    const sessionId = req?.agent?.session?.id
    const writers = sessionId ? rt.sessionStreams.get(sessionId) : null
    if (!writers || writers.size === 0) return next()
    const rpcId = "q-" + randomBytes(12).toString("hex")
    return new Promise((resolve, reject) => {
      let settled = false
      const settle = (answer) => {
        if (settled) return
        settled = true
        const rec = rt.pendingQuestions.get(rpcId)
        rt.pendingQuestions.delete(rpcId)
        if (rec?.timer) clearTimeout(rec.timer)
        try { req.signal?.removeEventListener("abort", rec?.onAbort) } catch {}
        if (answer && Array.isArray(answer.answers)) resolve(answer)
        else Promise.resolve().then(() => next()).then(resolve, reject)
      }
      const onAbort = () => settle(null)
      const timer = setTimeout(() => settle(null), APPROVAL_TIMEOUT_MS)
      rt.pendingQuestions.set(rpcId, { settle, timer, onAbort, sessionId })
      req.signal?.addEventListener("abort", onAbort, { once: true })
      const body = JSON.stringify({
        rpcId,
        sessionId,
        questions: req.questions ?? [],
      })
      writeSse(writers, `event: question\ndata: ${body}\n\n`)
      ctx.logger.info(`dsh-links: question → mobile session=${String(sessionId).slice(0, 8)} rpc=${rpcId.slice(0, 8)}`)
    })
  })

  // ---------- 手机接入代理（0.0.0.0:<port> HTTPS）：仅 health / pair / mobile/* ----------
  const requestHandler = async (req, res) => {
    try {
      const pathname = new URL(req.url ?? "/", "http://x").pathname
      if (pathname === "/dsh-link/health" && req.method === "GET") {
        return json(res, 200, { ok: true })
      }
      if (pathname === "/dsh-link/pair" && req.method === "POST") {
        return handlePair(req, res, config, state, stateFile, rt)
      }
      if (PANEL_ONLY_PATHS.has(pathname)) {
        return json(res, 404, { error: "not found" })
      }
      const device = authorize(req, state, stateFile)
      if (config.debug) {
        ctx.logger.info(`dsh-links: ${req.method} ${pathname} → ${device ? `device:${device.deviceId}` : "denied"}`)
      }
      if (!device) {
        return json(res, 401, { error: "缺少或无效的连接 token" })
      }
      sweepExpiredPending(state, stateFile, rt)
      if (!isDeviceAuthorized(state, device)) {
        return json(res, 401, { error: "缺少或无效的连接 token" })
      }
      if (isDevicePending(device)) {
        return json(res, 403, { error: "设备待主机确认", pending: true })
      }
      trackDeviceRequest(rt, device.deviceId, req, res)
      touchDevice(state, device, stateFile)
      if (pathname.startsWith("/dsh-link/mobile/")) {
        const permissionMatch = pathname.match(/^\/dsh-link\/mobile\/sessions\/([^/]+)\/permission$/)
        if (req.method === "POST" && permissionMatch) {
          if (!requireJsonWrite(req, res)) return
          const sessionId = decodeURIComponent(permissionMatch[1])
          const body = await readAuthorizedJson(req, res, state, device)
  if (!body) return
          const preset = String(body.preset ?? "").trim()
          const PRESET_SPECS = {
            "read-only": { sandbox: "read-only", approval: "ask" },
            "workspace-write": { sandbox: "workspace-write", approval: "ask" },
            "danger-full-access": { sandbox: "danger-full-access", approval: "never" }, // dsh: never=需审批的操作自动拒绝；是否符合「Full access」需真机核对
          }
          const spec = PRESET_SPECS[preset]
          if (!spec) return json(res, 400, { error: "preset 无效" })
          try {
            const sessions = ctx.get("sessions")
            const session = typeof sessions?.get === "function" ? await sessions.get(sessionId) : undefined
            if (!isDeviceAuthorized(state, device)) return json(res, 401, { error: "设备已被吊销" })
            if (!session) return json(res, 404, { error: "会话不存在" })
            session.append("permission/preset", { preset })
            session.append("approval/policy", { policy: spec.approval })
            session.append("sandbox/mode", { mode: spec.sandbox })
            return json(res, 200, { ok: true, preset, approval: spec.approval, sandbox: spec.sandbox })
          } catch (err) {
            ctx.logger.warn(`dsh-links: permission update: ${err?.message ?? err}`)
            return json(res, 500, { error: "permission update failed" })
          }
        }
        const streamMatch = pathname.match(/^\/dsh-link\/mobile\/sessions\/([^/]+)\/stream$/)
        if (req.method === "GET" && streamMatch) {
          return handleStreamRoute(decodeURIComponent(streamMatch[1]), res, targetPort, config, req, rt, device)
        }
        return handleMobileApi(req, res, targetPort, state, stateFile, device, pathname, rt)
      }
      return json(res, 404, { error: "not found" })
    } catch (err) {
      ctx.logger.warn(`dsh-links: proxy request error: ${err?.message ?? err}`)
      if (!res.headersSent) json(res, 500, { error: "proxy error" })
      else res.destroy()
    }
  }

  let proxy
  let pollTimer
  let keepAliveTimer
  let muxBridge = null
  const ready = loadOrCreateTls(ensureStateDir(config)).then((tls) => {
    tlsHolder.fingerprint = tls.fingerprint
    proxy = createHttpsServer({ key: tls.key, cert: tls.cert, minVersion: "TLSv1.2" }, requestHandler)
    proxy.on("error", (err) => {
      ctx.logger.warn(`dsh-links: proxy error: ${err?.message ?? err}`)
    })
    pollTimer = setInterval(() => {
      for (const sessionId of rt.sessionStreams.keys()) {
        if ((rt.sessionStreams.get(sessionId)?.size ?? 0) > 0) {
          pollSession(sessionId, targetPort, rt)
        }
      }
    }, config.eventPollIntervalMs)
    keepAliveTimer = setInterval(() => {
      for (const [sessionId, writers] of rt.sessionStreams) {
        writeSse(writers, ": keepalive\n\n")
        if (writers.size === 0) dropSession(rt, sessionId)
      }
    }, 15_000)
    return new Promise((resolve, reject) => {
      proxy.once("error", reject)
      proxy.listen(config.port, "0.0.0.0", () => {
        ctx.logger.info(`dsh-links: 手机接入代理已启动，https 端口 ${config.port}（指纹 ${tls.fingerprint.slice(0, 12)}…）`)
        for (const u of lanUrls(config).urls) ctx.logger.info(`dsh-links: 可访问地址 ${u}`)
        startRelayAgent()
        muxBridge = startMuxQuestionBridge({
          targetPort,
          rt,
          logger: ctx.logger,
          requestPoll: (sessionId) => requestGapPoll(sessionId, targetPort, rt),
        })
        resolve(tls)
      })
    })
  })

  ctx.effect(
    () => () => {
      for (const dispose of disposers) dispose()
      if (pollTimer) clearInterval(pollTimer)
      if (keepAliveTimer) clearInterval(keepAliveTimer)
      try { muxBridge?.stop() } catch {}
      muxBridge = null
      stopRelayAgent()
      for (const writers of rt.sessionStreams.values()) {
        for (const conn of writers) {
          try { conn.res.end() } catch {}
        }
      }
      rt.sessionStreams.clear()
      rt.sessionFiles.clear()
      rt.inflightPolls.clear()
      rt.pendingPolls.clear()
      for (const gap of rt.gapPolls.values()) {
        if (gap.timer) clearTimeout(gap.timer)
      }
      rt.gapPolls.clear()
      rt.reasoningCache.clear()
      for (const rec of [...rt.pendingApprovals.values()]) {
        try { rec.settle("cancelled") } catch {}
      }
      rt.pendingApprovals.clear()
      for (const rec of [...rt.pendingQuestions.values()]) {
        try { rec.settle(null) } catch {}
      }
      rt.pendingQuestions.clear()
      unbindLocalRpcRuntime()
      try { proxy?.closeAllConnections?.() } catch {}
      try { proxy?.close() } catch {}
    },
    "dsh-links: proxy + routes",
  )

  return ready
}
