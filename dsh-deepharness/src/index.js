/**
 * dsh-deepharness — 服务端面（host face）
 *
 * 一个插件完成"手机端使用 dsh"：
 *   1. 在主 web 服务上注册 /dsh-link/* 路由，给网页界面提供二维码与配对管理；
 *   2. 在 0.0.0.0:<port> 起一个带 token 校验的反向代理，手机 App 从这里进入 dsh，
 *      并重写 Host/Origin 让 dsh 的 browser-trust 防火墙放行（/api 可用）；
 *   3. 配对采用一次性 6 位配对码（默认 10 分钟有效），扫码即自动批准。
 */
import { createServer, request as httpRequest } from "node:http"
import { connect } from "node:net"
import { existsSync, mkdirSync, readFileSync, statSync, writeFileSync } from "node:fs"
import { join } from "node:path"
import { homedir, hostname, networkInterfaces } from "node:os"
import { createHash, randomBytes } from "node:crypto"
import { zstdDecompressSync } from "node:zlib"
import z from "@deepseek-ai/schemastery"
import QRCode from "qrcode"
import { projectHistoryPage } from "./history.js"
import {
  newAuthStore, issueTicket, consumeTicket, createSession, getSession,
  revokeDevice, newPairingCode, verifyPairingCode, consumePairingCode,
  randomToken, SESSION_TTL_MS, TICKET_TTL_MS,
} from "./auth.js"

export const name = "dsh-deepharness"
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
  /** 状态目录（默认 ~/.dsh/dsh-deepharness） */
  stateDir: z.string().default(""),
  /** 请求日志（排查用） */
  debug: z.boolean().default(false),
  /** SSE 事件轮询间隔（毫秒），默认 1000 */
  eventPollIntervalMs: z.natural().default(1000),
  /** 重连补发最大历史条数（session.history maxMessages，按消息边界计数） */
  reconnectHistoryLimit: z.natural().default(50),
})

const COOKIE_NAME = "dsh_link_token"
const HEADER_NAME = "x-dsh-link-token"
const SESSION_COOKIE = "dsh_web_session"

/**
 * crypto.randomUUID 只在安全上下文（https 或 localhost）可用。
 * 手机经 http://局域网IP:18640 访问时是非安全上下文，DSH 运行时用它生成
 * RPC ID，缺失会导致连接循环第一代即失败（表现为"看不到会话/建不了会话"）。
 * 通过 tapIndex 注入到每份 index.html 的 <head>，在任何客户端 bundle 之前生效。
 */
const RANDOM_UUID_POLYFILL = `<script>/* dsh-deepharness: crypto.randomUUID polyfill for non-secure contexts */
if (typeof crypto !== "undefined" && typeof crypto.randomUUID !== "function") {
  crypto.randomUUID = function () {
    return ([1e7] + -1e3 + -4e3 + -8e3 + -1e11).replace(/[018]/g, function (c) {
      return (c ^ (crypto.getRandomValues(new Uint8Array(1))[0] & (15 >> (c / 4)))).toString(16)
    })
  }
}</script>`

/**
 * dsh-ui-mobile 安装引导抑制：仅对 App WebView（UA 含 DshMobile）生效。
 * App 内不需要 PWA「安装到主屏幕」引导；桌面/手机浏览器不受影响。
 * 必须在 dsh-ui-mobile client 启动前（<head> 注入）设置 localStorage。
 */
const MOBILE_INSTALL_SUPPRESS = `<script>/* dsh-deepharness: suppress dsh-ui-mobile PWA install prompt inside App WebView */
if (/\bDshMobile\b/.test(navigator.userAgent || "")) {
  try {
    localStorage.setItem("dsh-ui-mobile:install-promotion-dismissed", "1")
    localStorage.setItem("dsh-ui-mobile:ios-install-hint", "1")
  } catch (_) {}
  /* beforeinstallprompt 触发的横幅不受 dismissed key 控制，用动态 CSS 隐藏 */
  var __s = document.createElement("style")
  __s.textContent = '[aria-label="安装应用"], [aria-label="添加到主屏幕"] { display: none !important }'
  document.head.appendChild(__s)
}</script>`

function sha256(text) {
  return createHash("sha256").update(String(text)).digest("hex")
}

function statePathOf(config) {
  return join(config.stateDir || join(homedir(), ".dsh", "dsh-deepharness"), "state.json")
}

function loadState(file) {
  try {
    return JSON.parse(readFileSync(file, "utf8"))
  } catch {
    return {}
  }
}

function saveState(file, state) {
  writeFileSync(file, JSON.stringify(state, null, 2))
}

function now() {
  return Date.now()
}

function ensurePairingCode(state, ttlSeconds) {
  const p = state.pairing
  if (p && p.code && !p.consumed && p.expiresAt > now() && Date.now() >= (p.cooldownUntil ?? 0)) return p.code
  return newPairingCode(state, ttlSeconds)
}

function lanUrls(config) {
  const urls = new Set()
  for (const list of Object.values(networkInterfaces())) {
    for (const iface of list ?? []) {
      if (iface && iface.family === "IPv4" && !iface.internal && !iface.address.startsWith("198.18.")) {
        urls.add(`http://${iface.address}:${config.port}`)
      }
    }
  }
  for (const extra of config.extraUrls ?? []) urls.add(extra)
  return [...urls]
}

function pairInfo(config, state) {
  return {
    v: 1,
    type: "dsh-link",
    deviceId: state.deviceId,
    name: hostname(),
    port: config.port,
    urls: lanUrls(config),
    pairingCode: ensurePairingCode(state, config.pairingTtlSeconds),
  }
}

function json(res, code, obj) {
  res.writeHead(code, { "content-type": "application/json; charset=utf-8" })
  res.end(JSON.stringify(obj))
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

function authorize(req, state, authStore) {
  // 1) Web session cookie（WebView 页面/资源/WebSocket；HttpOnly，原生层不可读）
  const raw = req.headers.cookie ?? ""
  for (const part of raw.split(";")) {
    const kv = part.trim()
    if (kv.startsWith(`${SESSION_COOKIE}=`)) {
      const deviceId = getSession(authStore, decodeURIComponent(kv.slice(SESSION_COOKIE.length + 1)))
      if (deviceId) return (state.devices ?? []).find((d) => d.deviceId === deviceId) ?? null
    }
  }
  // 2) 长期设备 token（仅原生层 header 请求；永不进入 WebView/Cookie/URL）
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

async function qrPng(res, config, state) {
  try {
    const payload = pairInfo(config, state)
    const buf = await QRCode.toBuffer(JSON.stringify(payload), { type: "png", margin: 2, width: 320 })
    res.writeHead(200, { "content-type": "image/png", "cache-control": "no-store" })
    res.end(buf)
  } catch (err) {
    json(res, 500, { error: String(err?.message ?? err) })
  }
}

async function handlePair(req, res, config, state, stateFile) {
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
  json(res, 200, { ok: true, token, deviceId, name: deviceName, urls: lanUrls(config) })
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
        groups: (value.groups ?? []).map((g) => ({
          provider: g.name || g.id || "未知",
          models: (g.models ?? []).map((m) => ({
            id: m.id,
            name: m.name ?? m.id,
            contextWindow: m.contextWindow ?? null,
            maxTokens: m.maxTokens ?? null,
            reasoningEfforts: (m.reasoning?.efforts ?? []).map((e) => e.id),
            defaultEffort: m.reasoning?.defaultEffort ?? null,
          })),
        })),
        failures: value.failures ?? [],
      })
    }

    if (req.method === "GET" && pathname === "/dsh-link/mobile/llm-models") {
      const value = await callLocalRpc(targetPort, "llm.models", {})
      return json(res, 200, {
        version: 1,
        groups: (value.groups ?? []).map((g) => ({
          provider: g.name || g.id || "未知",
          models: (g.models ?? []).map((m) => ({
            id: m.id,
            name: m.name ?? m.id,
            contextWindow: m.contextWindow ?? null,
            maxTokens: m.maxTokens ?? null,
            reasoningEfforts: (m.reasoning?.efforts ?? []).map((e) => e.id),
            defaultEffort: m.reasoning?.defaultEffort ?? null,
          })),
        })),
        failures: value.failures ?? [],
      })
    }

    const modelMatch = pathname.match(/^\/dsh-link\/mobile\/sessions\/([^/]+)\/model$/)
    if (req.method === "POST" && modelMatch) {
      const sessionId = decodeURIComponent(modelMatch[1])
      const body = await readBody(req)
      const provider = String(body.provider ?? "").trim()
      const model = String(body.model ?? "").trim()
      if (!provider || !model) return json(res, 400, { error: "缺少 provider 或 model" })
      const payload = { sessionId, provider, model }
      if (typeof body.reasoningEffort === "string" && body.reasoningEffort.trim()) payload.reasoningEffort = body.reasoningEffort.trim()
      const value = await callLocalRpc(targetPort, "session.selectModel", payload)
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
    return json(res, 502, { error: error?.message ?? "mobile API unavailable" })
  }
}

function forwardHttp(req, res, targetPort) {
  const headers = {}
  for (const [k, v] of Object.entries(req.headers)) {
    if (v === undefined) continue
    if (["host", "origin", "connection", "keep-alive", "proxy-connection", "upgrade", "transfer-encoding"].includes(k)) continue
    headers[k] = v
  }
  headers.host = `127.0.0.1:${targetPort}`
  headers.origin = `http://127.0.0.1:${targetPort}`

  const upstream = httpRequest(
    { host: "127.0.0.1", port: targetPort, method: req.method, path: req.url, headers },
    (upRes) => {
      res.writeHead(upRes.statusCode ?? 502, upRes.headers)
      upRes.pipe(res)
    },
  )
  upstream.on("error", () => {
    if (!res.headersSent) json(res, 502, { error: "upstream unreachable" })
    else res.destroy()
  })
  res.on("close", () => upstream.destroy())
  req.pipe(upstream)
}

// 审批接管：挂起 approval/request waterfall，由移动端 HTTP 接口 resolve
const pendingApprovals = new Map() // approvalId → resolve(outcome)

export function apply(ctx, config) {
  const web = ctx.get("webServer")
  const targetPort = web.port
  const authStore = newAuthStore()
  mkdirSync(config.stateDir || join(homedir(), ".dsh", "dsh-deepharness"), { recursive: true })
  const stateFile = statePathOf(config)
  const state = loadState(stateFile)
  if (!state.deviceId) state.deviceId = `dsh-${randomBytes(8).toString("hex")}`
  if (!state.devices) state.devices = []
  if (!state.pairing) state.pairing = {}
  // 旧版设备（无 deviceId）一次性迁移：自动补发，手机无需重新配对（token 不变）
  let migrated = false
  for (const d of state.devices) {
    if (!d.deviceId) {
      d.deviceId = `dev-${randomToken(8)}`
      migrated = true
    }
  }
  saveState(stateFile, state)
  if (migrated) ctx.logger.info("dsh-deepharness: 已为旧设备补发 deviceId")

  // ---------- 主 web 服务上的路由（网页界面「手机连接」面板用） ----------
  const disposers = [
    web.tapIndex((html) => html.replace(/<head([^>]*)>/i, `<head$1>${MOBILE_INSTALL_SUPPRESS}${RANDOM_UUID_POLYFILL}`)),
    web.register({
      kind: "exact",
      path: "/dsh-link/pair-info",
      handler: (req, res) => {
        saveState(stateFile, state)
        json(res, 200, pairInfo(config, state))
      },
    }),
    web.register({
      kind: "exact",
      path: "/dsh-link/qr.png",
      handler: (req, res) => qrPng(res, config, state),
    }),
    web.register({
      kind: "exact",
      path: "/dsh-link/revoke",
      handler: async (req, res) => {
        const body = await readBody(req)
        const targetName = String(body.name ?? "").trim()
        const targetId = String(body.deviceId ?? "").trim()
        if (!targetName && !targetId) return json(res, 400, { error: "缺少设备名或 deviceId" })
        const target = (state.devices ?? []).find((d) => d.name === targetName || d.deviceId === targetId)
        if (!target) return json(res, 404, { error: "设备不存在" })
        state.devices = (state.devices ?? []).filter((d) => d.deviceId !== target.deviceId)
        revokeDevice(authStore, target.deviceId) // 吊销：立即清除其全部 ticket 与 Web session
        saveState(stateFile, state)
        json(res, 200, { ok: true, removed: 1, deviceId: target.deviceId })
      },
    }),
    web.register({
      kind: "exact",
      path: "/dsh-link/devices",
      handler: (req, res) => {
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

  // ---------- 手机接入代理（0.0.0.0:<port>，token 校验 + Host/Origin 重写） ----------
  const proxy = createServer(async (req, res) => {
    try {
      const pathname = new URL(req.url ?? "/", "http://x").pathname
      if (pathname === "/dsh-link/health" && req.method === "GET") {
        return json(res, 200, { ok: true, name: "dsh-deepharness", deviceId: state.deviceId })
      }
      if (pathname === "/dsh-link/pair" && req.method === "POST") {
        return handlePair(req, res, config, state, stateFile)
      }
      // 18640 不提供匿名配对信息/二维码：配对码只能从本机 3080 管理界面获得
      if ((pathname === "/dsh-link/pair-info" || pathname === "/dsh-link/qr.png") && req.method === "GET") {
        return json(res, 401, { error: "配对信息仅限本机管理界面（3080）" })
      }
      // 一次性启动 ticket → 短期 HttpOnly Web session（WebView 入口）
      if (pathname === "/dsh-link/mobile/web-tickets" && req.method === "POST") {
        const device = authorize(req, state, authStore)
        if (!device) return json(res, 401, { error: "无效设备凭据" })
        const ticket = issueTicket(authStore, device.deviceId)
        return json(res, 200, {
          ticket,
          expiresAt: Date.now() + TICKET_TTL_MS,
          bootstrapPath: "/dsh-link/web-bootstrap",
        })
      }
      if (pathname === "/dsh-link/web-bootstrap" && req.method === "GET") {
        const ticket = new URL(req.url ?? "/", "http://x").searchParams.get("ticket") ?? ""
        const deviceId = consumeTicket(authStore, ticket)
        if (!deviceId) return json(res, 401, { error: "ticket 无效、已消费或已过期" })
        const device = (state.devices ?? []).find((d) => d.deviceId === deviceId)
        if (!device) return json(res, 401, { error: "设备不存在或已吊销" })
        const sessionValue = createSession(authStore, deviceId)
        const secure = req.headers["x-forwarded-proto"] === "https"
        res.writeHead(302, {
          "set-cookie": `${SESSION_COOKIE}=${sessionValue}; HttpOnly; SameSite=Strict; Path=/; Max-Age=${Math.floor(SESSION_TTL_MS / 1000)}${secure ? "; Secure" : ""}`,
          location: "/",
          "referrer-policy": "no-referrer",
          "cache-control": "no-store",
        })
        res.end()
        return
      }
      const device = authorize(req, state, authStore)
      if (config.debug) {
        ctx.logger.info(`dsh-deepharness: ${req.method} ${pathname} → ${device ? `device:${device.deviceId}` : "denied"}`)
      }
      if (!device) {
        return json(res, 401, { error: "缺少或无效的连接 token" })
      }
      touchDevice(state, device, stateFile)
      if (pathname.startsWith("/dsh-link/mobile/")) {
        const permissionMatch = pathname.match(/^\/dsh-link\/mobile\/sessions\/([^/]+)\/permission$/)
        if (req.method === "POST" && permissionMatch) {
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
            // 通过 DSH sessions 服务写入策略事件（与 /permission 命令等效）
            const sessions = ctx.get("sessions")
            const session = typeof sessions?.get === "function" ? await sessions.get(sessionId) : undefined
            if (!session) return json(res, 404, { error: "会话不存在" })
            session.append("permission/preset", { preset })
            session.append("approval/policy", { policy: spec.approval })
            session.append("sandbox/mode", { mode: spec.sandbox })
            return json(res, 200, { ok: true, preset, approval: spec.approval, sandbox: spec.sandbox })
          } catch (err) {
            return json(res, 500, { error: String(err?.message ?? err) })
          }
        }
        const streamMatch = pathname.match(/^\/dsh-link\/mobile\/sessions\/([^/]+)\/stream$/)
        if (req.method === "GET" && streamMatch) {
          return handleStreamRoute(decodeURIComponent(streamMatch[1]), res, targetPort, config)
        }
        return handleMobileApi(req, res, targetPort, state, device, pathname)
      }
      forwardHttp(req, res, targetPort)
    } catch (err) {
      ctx.logger.warn(`dsh-deepharness: proxy request error: ${err?.message ?? err}`)
      if (!res.headersSent) json(res, 500, { error: "proxy error" })
      else res.destroy()
    }
  })

  proxy.on("upgrade", (req, socket, head) => {
    const device = authorize(req, state, authStore)
    if (!device) {
      socket.destroy()
      return
    }
    touchDevice(state, device, stateFile)
    if (config.debug) {
      ctx.logger.info(`dsh-deepharness: upgrade ${req.url}`)
    }
    const upstream = connect(targetPort, "127.0.0.1", () => {
      const lines = [`${req.method} ${req.url} HTTP/${req.httpVersion}`]
      for (const [k, v] of Object.entries(req.headers)) {
        if (v === undefined) continue
        if (k === "host") {
          lines.push(`host: 127.0.0.1:${targetPort}`)
          continue
        }
        if (k === "origin") {
          lines.push(`origin: http://127.0.0.1:${targetPort}`)
          continue
        }
        for (const vv of Array.isArray(v) ? v : [v]) lines.push(`${k}: ${vv}`)
      }
      upstream.write(`${lines.join("\r\n")}\r\n\r\n`)
      if (head && head.length > 0) upstream.write(head)
      socket.pipe(upstream)
      upstream.pipe(socket)
      if (config.debug) {
        upstream.once("data", (d) => ctx.logger.info(`dsh-deepharness: upstream first bytes: ${d.toString().slice(0, 160)}`))
        socket.once("data", (d) => ctx.logger.info(`dsh-deepharness: client first frame bytes: ${d.toString("hex").slice(0, 120)}`))
        socket.on("close", () => ctx.logger.info("dsh-deepharness: client socket closed"))
        upstream.on("close", () => ctx.logger.info("dsh-deepharness: upstream socket closed"))
        socket.on("end", () => ctx.logger.info("dsh-deepharness: client socket end"))
        upstream.on("end", () => ctx.logger.info("dsh-deepharness: upstream socket end"))
      }
    })
    upstream.on("error", (e) => {
      if (config.debug) ctx.logger.info(`dsh-deepharness: upstream connect error: ${e?.message ?? e}`)
      socket.destroy()
    })
    socket.on("error", () => upstream.destroy())
  })

  proxy.on("error", (err) => {
    ctx.logger.warn(`dsh-deepharness: proxy error: ${err?.message ?? err}`)
  })

  // ---------- SSE 后台事件轮询（所有有活跃连接的会话） ----------
  const pollTimer = setInterval(() => {
    for (const sessionId of sessionStreams.keys()) {
      if ((sessionStreams.get(sessionId)?.size ?? 0) > 0) {
        pollSession(sessionId, targetPort)
      }
    }
  }, config.eventPollIntervalMs)

  // 心跳：15s 发一次注释帧，防止中间代理断开 idle 连接（App 读超时 60s，两倍余量）
  const keepAliveTimer = setInterval(() => {
    for (const [sessionId, writers] of sessionStreams) {
      writeSse(writers, ": keepalive\n\n")
      if (writers.size === 0) dropSession(sessionId)
    }
  }, 15_000)

  proxy.listen(config.port, "0.0.0.0", () => {
    ctx.logger.info(`dsh-deepharness: 手机接入代理已启动，端口 ${config.port}（token 校验）`)
    for (const u of lanUrls(config)) ctx.logger.info(`dsh-deepharness: 可访问地址 ${u}`)
  })

  ctx.effect(
    () => () => {
      for (const dispose of disposers) dispose()
      clearInterval(pollTimer)
      clearInterval(keepAliveTimer)
      for (const writers of sessionStreams.values()) {
        for (const w of writers) {
          try { w.end() } catch {}
        }
      }
      sessionStreams.clear()
      lastEventSeqs.clear()
      sessionFiles.clear()
      proxy.closeAllConnections?.()
      proxy.close()
    },
    "dsh-deepharness: proxy + routes",
  )
}
