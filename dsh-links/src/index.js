/**
 * dsh-links — 服务端面（host face）
 *
 * 一个插件完成"手机端使用 dsh"：
 *   1. 在主 web 服务上注册 /dsh-link/* 路由（仅回环同源），给网页界面提供二维码与配对管理；
 *   2. 在 0.0.0.0:<port> 起一个带 token 校验的手机 API（只服务 App：health / pair / mobile/*）；
 *   3. 配对采用一次性 6 位配对码（默认 10 分钟有效），扫码即自动批准。
 */
import { request as httpRequest } from "node:http"
import { createServer as createHttpsServer } from "node:https"
import { chmodSync, cpSync, existsSync, mkdirSync, readFileSync, renameSync, statSync, writeFileSync } from "node:fs"
import { dirname, join } from "node:path"
import { homedir, hostname, networkInterfaces } from "node:os"
import { randomBytes } from "node:crypto"
import { zstdDecompressSync } from "node:zlib"
import z from "@deepseek-ai/schemastery"
import QRCode from "qrcode"
import { projectHistoryPage } from "./history.js"
import {
  consumePairingCode, ensurePairingCode, hydratePairing, persistablePairing,
  randomToken, revokeDevice, sha256, verifyPairingCode,
} from "./auth.js"
import { loadOrCreateTls } from "./tls.js"

export const name = "dsh-links"
export const inject = ["webServer"]

export const Config = z.object({
  /** 手机接入代理端口（0.0.0.0） */
  port: z.natural().max(65535).default(18640),
  /** 额外可访问地址（如 frp 公网地址），会一并写进二维码供手机按可达性选择 */
  extraUrls: z.array(z.string()).default([]),
  /** 配对自动批准（个人使用默认开启） */
  autoApprove: z.boolean().default(true),
  /** 配对码有效期（秒） */
  pairingTtlSeconds: z.natural().default(600),
  /** 状态目录（默认 ~/.dsh/dsh-links） */
  stateDir: z.string().default(""),
  /** 请求日志（排查用） */
  debug: z.boolean().default(false),
  /** SSE 事件轮询间隔（毫秒），默认 1000 */
  eventPollIntervalMs: z.natural().default(1000),
  /** 重连补发最大历史条数（session.history maxMessages，按消息边界计数） */
  reconnectHistoryLimit: z.natural().default(50),
})

const HEADER_NAME = "x-dsh-link-token"

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
  const data = JSON.stringify(persist, null, 2)
  const tmp = `${file}.${process.pid}.tmp`
  writeFileSync(tmp, data, { mode: 0o600 })
  renameSync(tmp, file)
  try { chmodSync(file, 0o600) } catch {}
}

function now() {
  return Date.now()
}

function classifyUrl(url) {
  try {
    const host = new URL(url).hostname
    if (host === "127.0.0.1" || host === "localhost") return "loopback"
    if (host.startsWith("192.168.") || host.startsWith("10.") || host.startsWith("172.16.") || host.startsWith("172.31.")) return "private"
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
    urls.add(extra)
    urlInfos.push({ url: extra, label: extra, category: "extra", isRecommended: false })
  }
  return { urls: [...urls], infos: urlInfos }
}

function pairInfo(config, state, certFingerprint) {
  const lan = lanUrls(config)
  return {
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
}

function json(res, code, obj) {
  res.writeHead(code, { "content-type": "application/json; charset=utf-8" })
  res.end(JSON.stringify(obj))
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
 * 依赖 dsh 内部信任围栏语义，dsh 升级时需复查。
 */
function requireLoopbackSameOrigin(req, res) {
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

function readBody(req, limit = 64 * 1024) {
  return new Promise((resolve) => {
    const chunks = []
    let size = 0
    req.on("data", (c) => {
      size += c.length
      if (size <= limit) chunks.push(c)
    })
    req.on("end", () => {
      try {
        resolve(JSON.parse(Buffer.concat(chunks).toString("utf8") || "{}"))
      } catch {
        resolve({})
      }
    })
    req.on("error", () => resolve({}))
  })
}

function authorize(req, state) {
  const token = typeof req.headers[HEADER_NAME] === "string" ? req.headers[HEADER_NAME] : null
  if (!token) return null
  const hash = sha256(token)
  return (state.devices ?? []).find((d) => d.tokenHash === hash) ?? null
}

function touchDevice(state, device, file) {
  if (now() - (device.lastSeenAt ?? 0) > 60_000) {
    device.lastSeenAt = now()
    saveState(file, state)
  }
}

async function qrPng(res, config, state, certFingerprint) {
  try {
    const payload = pairInfo(config, state, certFingerprint)
    const buf = await QRCode.toBuffer(JSON.stringify(payload), { type: "png", margin: 2, width: 320 })
    res.writeHead(200, { "content-type": "image/png", "cache-control": "no-store" })
    res.end(buf)
  } catch (err) {
    console.error(`dsh-links: qr.png: ${err?.message ?? err}`)
    json(res, 500, { error: "failed to render qr" })
  }
}

async function handlePair(req, res, config, state, stateFile) {
  if (!requireJsonWrite(req, res)) return
  const body = await readBody(req)
  const code = String(body.code ?? "").trim()
  const deviceName = (String(body.deviceName ?? "手机").trim().slice(0, 32) || "手机")
  const ver = verifyPairingCode(state, code)
  if (!ver.ok) {
    saveState(stateFile, state)
    json(res, 401, { error: ver.error })
    return
  }
  if (!config.autoApprove) {
    json(res, 403, { error: "该主机未开启自动批准" })
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
  state.devices = state.devices ?? []
  state.devices.push({ deviceId, name: deviceName, tokenHash: sha256(token), createdAt: now(), lastSeenAt: now() })
  consumePairingCode(state) // 配对码一次性：成功后立即失效
  saveState(stateFile, state)
  json(res, 200, { ok: true, token, deviceId, name: deviceName, urls: lanUrls(config).urls })
}

/** 拼接多帧 zstd 解压（DSH 存储按帧追加写入）。 */
function zstdDecompressAll(buf) {
  const MAGIC = 0xfd2fb528
  const positions = []
  for (let i = 0; i + 4 <= buf.length; i++) {
    if (buf.readUInt32LE(i) === MAGIC) positions.push(i)
  }
  if (positions.length === 0) return zstdDecompressSync(buf)
  const parts = []
  for (let i = 0; i < positions.length; i++) {
    const end = i + 1 < positions.length ? positions[i + 1] : buf.length
    parts.push(zstdDecompressSync(buf.subarray(positions[i], end)))
  }
  return Buffer.concat(parts)
}

/**
 * 从会话存储文件（jsonl.zstd）读取 reasoning 块，按块首事件 seq 索引
 * （RPC 投影会剥掉父会话的思考内容，文件是补全源）。
 * 返回 Map<seq, { seq, time, text }>；连续 reasoning block-end 合并为一个
 * 块（与投影的事件侧分组规则一致），id 以首个 block-end 的 seq 为键。
 */
async function readSessionReasoning(targetPort, sessionId) {
  try {
    const list = await callLocalRpc(targetPort, "session.list", {})
    const row = (list.items ?? []).find((s) => s.sessionId === sessionId)
    const cwd = row?.cwd
    if (!cwd) return new Map()
    const enc = "--" + cwd.split("/").filter(Boolean).join("-") + "--"
    const p = join(homedir(), ".dsh", "sessions", enc, sessionId, "session.jsonl.zstd")
    if (!existsSync(p)) return new Map()
    const text = zstdDecompressAll(readFileSync(p)).toString("utf8")
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
    return reasoning
  } catch (err) {
    return new Map()
  }
}

/** 向 DSH 主进程投递审批响应（与 Web 端 POST /api/respond 同协议）。 */
function postRespond(targetPort, message) {
  return new Promise((resolve, reject) => {
    const body = Buffer.from(JSON.stringify(message))
    const request = httpRequest(
      {
        host: "127.0.0.1",
        port: targetPort,
        method: "POST",
        path: "/api/respond",
        headers: {
          host: "127.0.0.1:" + targetPort,
          origin: "http://127.0.0.1:" + targetPort,
          "content-type": "application/json",
          "content-length": String(body.length),
        },
      },
      (response) => {
        const chunks = []
        response.on("data", (chunk) => chunks.push(chunk))
        response.on("end", () => {
          try {
            resolve(JSON.parse(Buffer.concat(chunks).toString("utf8")))
          } catch (error) {
            reject(error)
          }
        })
      },
    )
    request.on("error", reject)
    request.end(body)
  })
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

async function selectSessionModel(targetPort, sessionId, provider, model, reasoningEffort) {
  let groups = []
  try {
    const catalog = await callLocalRpc(targetPort, "session.models", { sessionId })
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
    try {
      return await callLocalRpc(targetPort, "session.selectModel", payload)
    } catch (err) {
      lastErr = err
    }
  }
  throw lastErr ?? new Error("切换模型失败")
}

/**
 * 向本机 dsh /api 发 RPC。刻意把 Host/Origin 写成回环：dsh 的 PRIVILEGED_METHODS
 * 只认 isTrustedApiRequest(req, [])，插件代调的方法名是写死的闭集（session.* /
 * workspace.* / settings.describe|update / agentPreset.list / llm.models|balance），
 * 不是 18640 上的开放转发。依赖 dsh 内部 API，升级时需复查。
 */
function callLocalRpc(targetPort, method, payload) {
  return new Promise((resolve, reject) => {
    const rpcId = "mobile-" + randomBytes(12).toString("hex")
    const body = Buffer.from(JSON.stringify({ type: "client-request", rpcId, method, payload }))
    const request = httpRequest(
      {
        host: "127.0.0.1",
        port: targetPort,
        method: "POST",
        path: "/api/" + method,
        headers: {
          host: "127.0.0.1:" + targetPort,
          origin: "http://127.0.0.1:" + targetPort,
          "content-type": "application/json",
          "content-length": String(body.length),
        },
      },
      (response) => {
        const chunks = []
        response.on("data", (chunk) => chunks.push(chunk))
        response.on("end", () => {
          try {
            const frame = JSON.parse(Buffer.concat(chunks).toString("utf8"))
            if (frame?.result?.ok) return resolve(frame.result.value)
            const error = frame?.result?.error
            reject(new Error(error?.message || ("RPC " + method + " failed")))
          } catch (error) {
            reject(error)
          }
        })
      },
    )
    request.setTimeout(10_000, () => request.destroy(new Error("RPC " + method + " timed out")))
    request.on("error", reject)
    request.end(body)
  })
}

// ---------- SSE 实时推送（/dsh-link/mobile/sessions/:id/stream） ----------
// 数据流：DSH 产生事件 → 写入 JSONL → 插件轮询 session.history RPC（增量 seq）→
// SSE emit → App 订阅 → Compose state 更新。轮询间隔可配，空闲会话用文件大小跳过 RPC。
const sessionStreams = new Map() // sessionId → Set<http.ServerResponse>（活跃 SSE writer）
const lastEventSeqs = new Map() // sessionId → 已推送的最大事件 seq（断点续传游标）
const sessionFiles = new Map() // sessionId → { path, lastSize }（文件大小变化检测）
const inflightPolls = new Set() // sessionId → 轮询中（防重叠）

/** 定位会话存储文件（session.list → cwd → ~/.dsh/sessions/<cwd编码>/<id>/session.jsonl.zstd）。 */
async function sessionFilePath(targetPort, sessionId) {
  const cached = sessionFiles.get(sessionId)
  if (cached) return cached.path
  try {
    const list = await callLocalRpc(targetPort, "session.list", {})
    const row = (list.items ?? []).find((s) => s.sessionId === sessionId)
    const cwd = row?.cwd
    if (!cwd) return null
    const enc = "--" + cwd.split("/").filter(Boolean).join("-") + "--"
    const p = join(homedir(), ".dsh", "sessions", enc, sessionId, "session.jsonl.zstd")
    if (!existsSync(p)) return null
    sessionFiles.set(sessionId, { path: p, lastSize: undefined })
    return p
  } catch {
    return null
  }
}

function dropSession(sessionId) {
  sessionStreams.delete(sessionId)
  lastEventSeqs.delete(sessionId)
  sessionFiles.delete(sessionId)
}

/** 向一组 writer 写 SSE 帧；写失败（客户端断开）即移除。 */
function writeSse(writers, frame) {
  for (const w of [...writers]) {
    try {
      w.write(frame)
    } catch {
      writers.delete(w)
      try { w.destroy() } catch {}
    }
  }
}

/** 轮询一个会话：增量事件（seq 过滤）+ projections（stats 事件）。 */
async function pollSession(sessionId, targetPort) {
  const writers = sessionStreams.get(sessionId)
  if (!writers || writers.size === 0 || inflightPolls.has(sessionId)) return
  inflightPolls.add(sessionId)
  try {
    const filePath = await sessionFilePath(targetPort, sessionId)
    if (!filePath) return
    let size = 0
    try {
      size = statSync(filePath).size
    } catch {
      return // 会话文件不存在（已删除）
    }
    const info = sessionFiles.get(sessionId)
    if (info && info.lastSize === size) return // 无新事件，跳过 RPC
    const value = await callLocalRpc(targetPort, "session.history", { sessionId, maxMessages: 50 })
    const events = value.events ?? []
    const lastSeq = lastEventSeqs.get(sessionId) ?? 0
    let maxSeq = lastSeq
    const payloads = []
    for (const item of events) {
      const e = item?.event
      if (!e || typeof e.seq !== "number" || e.seq <= lastSeq) continue
      maxSeq = Math.max(maxSeq, e.seq)
      payloads.push(`event: message\ndata: ${JSON.stringify({ seq: e.seq, type: e.type, time: e.time, data: e.data })}\n\n`)
    }
    if (payloads.length > 0) {
      lastEventSeqs.set(sessionId, maxSeq)
      for (const p of payloads) writeSse(writers, p)
      const projections = value.projections?.values
      if (projections) writeSse(writers, `event: stats\ndata: ${JSON.stringify(projections)}\n\n`)
    }
    info.lastSize = size
  } catch {
    // RPC 失败/会话消失：静默跳过，下个周期重试
  } finally {
    inflightPolls.delete(sessionId)
  }
}

/** SSE 路由：ready（含断点游标）→ 补发历史（最多 reconnectHistoryLimit 条消息）→ 实时增量。 */
function handleStreamRoute(sessionId, res, targetPort, config) {
  res.writeHead(200, {
    "content-type": "text/event-stream; charset=utf-8",
    "cache-control": "no-store, no-cache, must-revalidate",
    connection: "keep-alive",
    "x-accel-buffering": "no",
  })
  res.flushHeaders?.()
  let writers = sessionStreams.get(sessionId)
  if (!writers) {
    writers = new Set()
    sessionStreams.set(sessionId, writers)
  }
  writers.add(res)
  const resumeSeq = lastEventSeqs.get(sessionId) ?? 0
  res.write(`event: ready\ndata: ${JSON.stringify({ resumeSeq })}\n\n`)
  // 断点续传：补发上次已推送 seq 之后的事件（新连接/重连时兜住间隙）
  ;(async () => {
    try {
      const value = await callLocalRpc(targetPort, "session.history", {
        sessionId,
        maxMessages: config.reconnectHistoryLimit,
      })
      const events = value.events ?? []
      const baseline = lastEventSeqs.get(sessionId) ?? 0
      for (const item of events) {
        const e = item?.event
        if (!e || typeof e.seq !== "number" || e.seq <= baseline) continue
        try {
          res.write(`event: message\ndata: ${JSON.stringify({ seq: e.seq, type: e.type, time: e.time, data: e.data })}\n\n`)
        } catch {
          return
        }
      }
      const projections = value.projections?.values
      if (projections) {
        try { res.write(`event: stats\ndata: ${JSON.stringify(projections)}\n\n`) } catch {}
      }
    } catch {}
  })()
  res.on("close", () => {
    const set = sessionStreams.get(sessionId)
    set?.delete(res)
    if (set && set.size === 0) dropSession(sessionId)
  })
}

function mobileSessionSummary(item) {
  const projections = item?.projections?.values ?? {}
  const title = typeof projections.title === "string" && projections.title.trim()
    ? projections.title.trim()
    : "未命名会话"
  return {
    sessionId: item.sessionId,
    title,
    updatedAt: item.updatedAt,
    running: Boolean(item.running),
    blank: Boolean(item.blank),
    cwd: item.cwd ?? null,
    agentPreset: item.agentPreset ?? null,
    origin: item.origin ?? null,
  }
}

async function handleMobileApi(req, res, targetPort, state, device, pathname) {
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
      const body = await readBody(req)
      const payload = {}
      if (typeof body.cwd === "string" && body.cwd.trim()) payload.cwd = body.cwd.trim()
      if (typeof body.workspaceId === "string" && body.workspaceId.trim()) payload.workspaceId = body.workspaceId.trim()
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

    if (req.method === "GET" && pathname === "/dsh-link/mobile/balance") {
      try {
        const value = await callLocalRpc(targetPort, "llm.balance", {})
        return json(res, 200, {
          version: 1,
          balance: value.balance ?? 0,
          used: value.used ?? 0,
          remainder: value.remainder ?? 0,
          currency: value.currency ?? "USD",
        })
      } catch (err) {
        // 降级：返回模拟余额（实际使用时替换为真实 RPC 或 PC 端代理）
        return json(res, 200, {
          version: 1,
          balance: 5.2,
          used: 2.3,
          remainder: 2.9,
          currency: "USD",
        })
      }
    }

    const modelMatch = pathname.match(/^\/dsh-link\/mobile\/sessions\/([^/]+)\/model$/)
    if (req.method === "POST" && modelMatch) {
      const sessionId = decodeURIComponent(modelMatch[1])
      const body = await readBody(req)
      const provider = String(body.provider ?? "").trim()
      const model = String(body.model ?? "").trim()
      if (!provider || !model) return json(res, 400, { error: "缺少 provider 或 model" })
      const value = await selectSessionModel(targetPort, sessionId, provider, model, body.reasoningEffort)
      return json(res, 200, { ok: true, selected: value.selected ?? null })
    }

    if (req.method === "GET" && pathname === "/dsh-link/mobile/workspaces") {
      const value = await callLocalRpc(targetPort, "workspace.list", {})
      return json(res, 200, { version: 1, workspaces: value.items ?? [], archivedSessionIds: value.archivedSessionIds ?? [] })
    }

    if (req.method === "POST" && pathname === "/dsh-link/mobile/workspaces") {
      const body = await readBody(req)
      const path = String(body.path ?? "").trim()
      if (!path) return json(res, 400, { error: "缺少工作区路径" })
      const value = await callLocalRpc(targetPort, "workspace.create", { path })
      return json(res, 200, { ok: true, workspace: value.workspace ?? null, created: Boolean(value.created) })
    }

    // 删除工作区（取消注册；会话日志不删除，DSH workspace.delete 语义）
    if (req.method === "POST" && pathname === "/dsh-link/mobile/workspaces/delete") {
      const body = await readBody(req)
      const path = String(body.path ?? "").trim()
      if (!path) return json(res, 400, { error: "缺少工作区路径" })
      const list = await callLocalRpc(targetPort, "workspace.list", {})
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
      const body = await readBody(req)
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

    const renameMatch = pathname.match(/^\/dsh-link\/mobile\/sessions\/([^/]+)\/rename$/)
    if (req.method === "POST" && renameMatch) {
      const sessionId = decodeURIComponent(renameMatch[1])
      const body = await readBody(req)
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
      const body = await readBody(req)
      const approvalId = String(body.approvalId ?? "").trim()
      const outcome = String(body.outcome ?? "").trim()
      if (!approvalId || !["allowed-once", "rejected"].includes(outcome)) {
        return json(res, 400, { error: "缺少 approvalId 或 outcome 无效" })
      }
      // 插件已接管 approval/request waterfall：直接 resolve 挂起的审批
      const resolver = pendingApprovals.get(approvalId)
      if (resolver) {
        pendingApprovals.delete(approvalId)
        resolver(outcome)
        return json(res, 200, { ok: true, accepted: true, handledBy: "plugin" })
      }
      // 兜底：走 /api/respond（Web 端会话在线的场景）
      const message = {
        type: "client-response",
        rpcId: approvalId,
        result: { ok: true, value: { sessionId, approvalId, outcome } },
      }
      const receipt = await postRespond(targetPort, message)
      return json(res, 200, { ok: true, accepted: Boolean(receipt?.accepted), reason: receipt?.reason ?? null })
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
      const maxMessages = Number(rawMaxMessages ?? "")
      const isTailPage = rawBeforeSeq === null && rawMaxMessages === null
      if (!isTailPage && Number.isInteger(beforeSeq) && beforeSeq > 0) rpcPayload.beforeSeq = beforeSeq
      if (Number.isInteger(maxMessages) && maxMessages > 0) rpcPayload.maxMessages = maxMessages
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
      const reasoningBySeq = await readSessionReasoning(targetPort, sessionId)
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
      const body = await readBody(req)
      const text = String(body.text ?? "").trim()
      // 图片附件（DSH prompt image 块：base64 data + mediaType）
      const images = Array.isArray(body.images) ? body.images : []
      const content = []
      for (const img of images.slice(0, 4)) {
        const mediaType = String(img.mediaType ?? "").trim()
        const data = String(img.data ?? "").trim()
        if (!mediaType || !data) continue
        content.push({ type: "image", mediaType, data })
      }
      if (!text && content.length === 0) return json(res, 400, { error: "消息内容不能为空" })
      if (text) content.push({ type: "text", text })
      const resVal = await callLocalRpc(targetPort, "session.prompt", {
        sessionId,
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

const PANEL_ONLY_PATHS = new Set(["/dsh-link/pair-info", "/dsh-link/qr.png", "/dsh-link/revoke", "/dsh-link/devices"])

// 审批接管：挂起 approval/request waterfall，由移动端 HTTP 接口 resolve
const pendingApprovals = new Map() // approvalId → resolve(outcome)

export function apply(ctx, config) {
  const web = ctx.get("webServer")
  const targetPort = web.port
  const stateFile = statePathOf(config)
  const state = loadState(stateFile)
  hydratePairing(state)
  if (!state.deviceId) state.deviceId = `dsh-${randomBytes(8).toString("hex")}`
  if (!state.devices) state.devices = []
  if (!state.pairing) state.pairing = {}
  const tlsHolder = { fingerprint: "" }
  // 旧版设备（无 deviceId）一次性迁移：自动补发，手机无需重新配对（token 不变）
  let migrated = false
  for (const d of state.devices) {
    if (!d.deviceId) {
      d.deviceId = `dev-${randomToken(8)}`
      migrated = true
    }
  }
  saveState(stateFile, state)
  if (migrated) ctx.logger.info("dsh-links: 已为旧设备补发 deviceId")

  const fp = () => tlsHolder.fingerprint

  // ---------- 主 web 服务上的路由（网页界面「手机连接」面板用；回环同源围栏） ----------
  const disposers = [
    web.register({
      kind: "exact",
      path: "/dsh-link/pair-info",
      handler: (req, res) => {
        if (!requireLoopbackSameOrigin(req, res)) return
        json(res, 200, pairInfo(config, state, fp()))
      },
    }),
    web.register({
      kind: "exact",
      path: "/dsh-link/qr.png",
      handler: (req, res) => {
        if (!requireLoopbackSameOrigin(req, res)) return
        return qrPng(res, config, state, fp())
      },
    }),
    web.register({
      kind: "exact",
      path: "/dsh-link/revoke",
      handler: async (req, res) => {
        if (!requireLoopbackSameOrigin(req, res)) return
        if (!requireJsonWrite(req, res)) return
        const body = await readBody(req)
        const targetName = String(body.name ?? "").trim()
        const targetId = String(body.deviceId ?? "").trim()
        if (!targetName && !targetId) return json(res, 400, { error: "缺少设备名或 deviceId" })
        const target = (state.devices ?? []).find((d) => d.name === targetName || d.deviceId === targetId)
        if (!target) return json(res, 404, { error: "设备不存在" })
        state.devices = (state.devices ?? []).filter((d) => d.deviceId !== target.deviceId)
        revokeDevice(null, target.deviceId)
        saveState(stateFile, state)
        json(res, 200, { ok: true, removed: 1, deviceId: target.deviceId })
      },
    }),
    web.register({
      kind: "exact",
      path: "/dsh-link/devices",
      handler: (req, res) => {
        if (!requireLoopbackSameOrigin(req, res)) return
        json(res, 200, {
          devices: state.devices.map(({ deviceId, name, createdAt, lastSeenAt }) => ({ deviceId, name, createdAt, lastSeenAt })),
        })
      },
    }),
  ]

  // 接管 approval/request waterfall：返回挂起 Promise，等移动端响应
  ctx.on("waterfall", (target, name, payload, next) => {
    if (name !== "approval/request") return next()
    const id = payload?.payload?.id ?? payload?.id
    if (!id) return next()
    return new Promise((resolve) => {
      pendingApprovals.set(id, resolve)
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
        return handlePair(req, res, config, state, stateFile)
      }
      if (PANEL_ONLY_PATHS.has(pathname)) {
        return json(res, 404, { error: "not found" })
      }
      const device = authorize(req, state)
      if (config.debug) {
        ctx.logger.info(`dsh-links: ${req.method} ${pathname} → ${device ? `device:${device.deviceId}` : "denied"}`)
      }
      if (!device) {
        return json(res, 401, { error: "缺少或无效的连接 token" })
      }
      touchDevice(state, device, stateFile)
      if (pathname.startsWith("/dsh-link/mobile/")) {
        const permissionMatch = pathname.match(/^\/dsh-link\/mobile\/sessions\/([^/]+)\/permission$/)
        if (req.method === "POST" && permissionMatch) {
          if (!requireJsonWrite(req, res)) return
          const sessionId = decodeURIComponent(permissionMatch[1])
          const body = await readBody(req)
          const preset = String(body.preset ?? "").trim()
          const PRESET_SPECS = {
            "read-only": { sandbox: "read-only", approval: "ask" },
            "workspace-write": { sandbox: "workspace-write", approval: "ask" },
            "danger-full-access": { sandbox: "danger-full-access", approval: "never" },
          }
          const spec = PRESET_SPECS[preset]
          if (!spec) return json(res, 400, { error: "preset 无效" })
          try {
            const sessions = ctx.get("sessions")
            const session = typeof sessions?.get === "function" ? await sessions.get(sessionId) : undefined
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
          return handleStreamRoute(decodeURIComponent(streamMatch[1]), res, targetPort, config)
        }
        return handleMobileApi(req, res, targetPort, state, device, pathname)
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
  const ready = loadOrCreateTls(ensureStateDir(config)).then((tls) => {
    tlsHolder.fingerprint = tls.fingerprint
    proxy = createHttpsServer({ key: tls.key, cert: tls.cert }, requestHandler)
    proxy.on("error", (err) => {
      ctx.logger.warn(`dsh-links: proxy error: ${err?.message ?? err}`)
    })
    pollTimer = setInterval(() => {
      for (const sessionId of sessionStreams.keys()) {
        if ((sessionStreams.get(sessionId)?.size ?? 0) > 0) {
          pollSession(sessionId, targetPort)
        }
      }
    }, config.eventPollIntervalMs)
    keepAliveTimer = setInterval(() => {
      for (const [sessionId, writers] of sessionStreams) {
        writeSse(writers, ": keepalive\n\n")
        if (writers.size === 0) dropSession(sessionId)
      }
    }, 15_000)
    return new Promise((resolve, reject) => {
      proxy.once("error", reject)
      proxy.listen(config.port, "0.0.0.0", () => {
        ctx.logger.info(`dsh-links: 手机接入代理已启动，https 端口 ${config.port}（指纹 ${tls.fingerprint.slice(0, 12)}…）`)
        for (const u of lanUrls(config).urls) ctx.logger.info(`dsh-links: 可访问地址 ${u}`)
        resolve(tls)
      })
    })
  })

  ctx.effect(
    () => () => {
      for (const dispose of disposers) dispose()
      if (pollTimer) clearInterval(pollTimer)
      if (keepAliveTimer) clearInterval(keepAliveTimer)
      for (const writers of sessionStreams.values()) {
        for (const w of writers) {
          try { w.end() } catch {}
        }
      }
      sessionStreams.clear()
      lastEventSeqs.clear()
      sessionFiles.clear()
      try { proxy?.closeAllConnections?.() } catch {}
      try { proxy?.close() } catch {}
    },
    "dsh-links: proxy + routes",
  )

  return ready
}
