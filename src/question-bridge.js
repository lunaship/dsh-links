/**
 * 把本机 apiproxy 的 mux 帧转发给手机 SSE：
 * - `question/requested|resolved`：澄清卡不在 session.history 事件流里，只能走 mux。
 * - `session/event`：与 web 同源的实时推送，绕开"轮询 session.history"的秒级延迟。
 * 并用 /api/respond 回传答案。
 *
 * 传输：新版 apiproxy 的 /api/events.mux 只接受 WebSocket 升级（SSE GET 返回 426），
 * 旧版只有 SSE。两条都实现，首次连接失败就换另一条，之后固定用能通的那条。
 */
import { request as httpRequest } from "node:http"
import { randomBytes } from "node:crypto"
import { sseMessageFrame } from "./stream-cursor.js"

function sleep(ms) {
  return new Promise((r) => setTimeout(r, ms))
}

function flushSse(res) {
  try { res.flush?.() } catch {}
}

function writeSse(writers, frame) {
  for (const conn of [...writers]) {
    try {
      const ok = conn.res.write(frame)
      flushSse(conn.res)
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

function parseSseBlocks(buf, onBlock) {
  let rest = buf
  let idx
  while ((idx = rest.indexOf("\n\n")) >= 0) {
    const raw = rest.slice(0, idx)
    rest = rest.slice(idx + 2)
    onBlock(raw)
  }
  return rest
}

/**
 * mux 上的 `session/event` 与 session.history 里的事件同构（seq/type/time/data），
 * 直接按 SSE 帧下推。只走连号快路径：一旦发现空洞就交给 pollSession 补，
 * 否则 conn.lastSeq 会跳号，被跳过的事件永远补不回来。
 */
function handleSessionEvent(payload, rt, requestPoll) {
  const sessionId = payload.sessionId
  const event = payload.event
  if (!sessionId || !event || typeof event.seq !== "number") return
  const writers = rt.sessionStreams.get(sessionId)
  if (!writers || writers.size === 0) return
  let needsPoll = false
  for (const conn of [...writers]) {
    if (!conn.seeded) {
      conn.missedWhileSeeding = true
      if (!Array.isArray(conn.seedQueue)) conn.seedQueue = []
      conn.seedQueue.push(event)
      continue
    }
    if (event.seq <= conn.lastSeq) continue
    if (event.seq > conn.lastSeq + 1) {
      needsPoll = true
      continue
    }
    try {
      const ok = conn.res.write(sseMessageFrame(event))
      flushSse(conn.res)
      conn.lastSeq = event.seq
      if (ok === false) {
        writers.delete(conn)
        try { conn.res.destroy() } catch {}
      }
    } catch {
      writers.delete(conn)
      try { conn.res.destroy() } catch {}
    }
  }
  if (needsPoll) requestPoll?.(sessionId)
}

/** SSE 块 → 帧。WebSocket 那条直接拿到帧对象，走 handleMuxFrame。 */
export function handleMuxBlock(block, rt, logger, requestPoll) {
  const data = block
    .split("\n")
    .filter((line) => line.startsWith("data:"))
    .map((line) => line.slice(5).replace(/^ /, ""))
    .join("\n")
    .trim()
  if (!data || data.startsWith(":")) return
  let frame
  try {
    frame = JSON.parse(data)
  } catch {
    return
  }
  return handleMuxFrame(frame, rt, logger, requestPoll)
}

/** 补历史结束后把排队的 mux 事件按序补发，避免思考中的 delta 被丢掉。 */
export function flushSeedQueue(conn, sessionId, requestPoll) {
  const queued = conn.seedQueue ?? []
  conn.seedQueue = []
  conn.seeded = true
  queued.sort((a, b) => (a.seq ?? 0) - (b.seq ?? 0))
  for (const event of queued) {
    if (typeof event?.seq !== "number" || event.seq <= conn.lastSeq) continue
    if (event.seq > conn.lastSeq + 1) {
      requestPoll?.(sessionId)
      return
    }
    try {
      const ok = conn.res.write(sseMessageFrame(event))
      flushSse(conn.res)
      conn.lastSeq = event.seq
      if (ok === false) {
        try { conn.res.destroy() } catch {}
        return
      }
    } catch {
      try { conn.res.destroy() } catch {}
      return
    }
  }
}

export function handleMuxFrame(frame, rt, logger, requestPoll) {
  const payload = frame?.payload
  const type = payload?.type
  if (type === "session/event") return handleSessionEvent(payload, rt, requestPoll)
  if (type !== "question/requested" && type !== "question/resolved") return
  const sessionId = payload.sessionId
  if (!sessionId) return
  const writers = rt.sessionStreams.get(sessionId)
  if (!writers || writers.size === 0) return

  if (type === "question/requested") {
    const body = JSON.stringify({
      rpcId: frame.rpcId,
      sessionId,
      questions: payload.questions ?? [],
    })
    writeSse(writers, `event: question\ndata: ${body}\n\n`)
    logger?.info?.(`dsh-links: question → mobile session=${String(sessionId).slice(0, 8)} rpc=${String(frame.rpcId).slice(0, 8)}`)
  } else {
    const body = JSON.stringify({
      rpcId: payload.questionRpcId ?? frame.rpcId,
      sessionId,
      outcome: payload.outcome ?? "cancelled",
    })
    writeSse(writers, `event: question-resolved\ndata: ${body}\n\n`)
  }
}

/** POST /api/respond（client-response），回答 ask_user_question。 */
export function respondQuestion(targetPort, rpcId, sessionId, answer) {
  return new Promise((resolve, reject) => {
    const body = Buffer.from(JSON.stringify({
      type: "client-response",
      rpcId,
      result: {
        ok: true,
        value: { sessionId, answer },
      },
    }))
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
        response.on("data", (c) => chunks.push(c))
        response.on("end", () => {
          try {
            const frame = JSON.parse(Buffer.concat(chunks).toString("utf8"))
            resolve(frame)
          } catch (err) {
            reject(err)
          }
        })
      },
    )
    request.setTimeout(15_000, () => request.destroy(new Error("respond timed out")))
    request.on("error", reject)
    request.end(body)
  })
}

/**
 * 常驻订阅本机 events.mux；断线自动重连。
 * @returns {{ stop: () => void }}
 */
export function startMuxQuestionBridge({ targetPort, rt, logger, requestPoll }) {
  let stopped = false
  let activeReq = null
  let activeWs = null
  // 新版主机走 WebSocket，旧版走 SSE；首连失败会翻到另一条
  let useWs = typeof globalThis.WebSocket === "function"

  function connectWs() {
    return new Promise((resolve, reject) => {
      let ws
      try {
        ws = new globalThis.WebSocket(`ws://127.0.0.1:${targetPort}/api/events.mux`)
      } catch (err) {
        reject(err)
        return
      }
      activeWs = ws
      let opened = false
      let done = false
      // 插件先于主机 API 就绪时，升级请求会一直悬着：不设超时这个 Promise 永不落地，
      // 重连循环就死在这里。超时后主动放弃，交给下一轮重试。
      const handshakeTimer = setTimeout(() => {
        if (opened || done) return
        done = true
        try { ws.close() } catch {}
        reject(new Error("mux ws 握手超时"))
      }, 5_000)
      handshakeTimer.unref?.()
      const settle = (fn, arg) => {
        if (done) return
        done = true
        clearTimeout(handshakeTimer)
        fn(arg)
      }
      ws.addEventListener("open", () => {
        opened = true
        clearTimeout(handshakeTimer)
        logger?.info?.("dsh-links: mux 桥已连接（WebSocket）")
      })
      ws.addEventListener("message", (ev) => {
        // 超时后被放弃的旧连接不得继续喂帧
        if (done && !opened) return
        if (typeof ev.data !== "string") return
        let frame
        try {
          frame = JSON.parse(ev.data)
        } catch {
          return
        }
        handleMuxFrame(frame, rt, logger, requestPoll)
      })
      // undici 的 error 后必定跟 close，统一在 close 里收尾
      ws.addEventListener("error", () => {})
      ws.addEventListener("close", (ev) => {
        if (opened) settle(resolve)
        else settle(reject, new Error(`mux ws 未能建立（code ${ev?.code ?? "?"}）`))
      })
    })
  }

  function connectSse() {
    return new Promise((resolve, reject) => {
      const req = httpRequest(
        {
          host: "127.0.0.1",
          port: targetPort,
          path: "/api/events.mux",
          method: "GET",
          headers: {
            host: "127.0.0.1:" + targetPort,
            origin: "http://127.0.0.1:" + targetPort,
            accept: "text/event-stream",
          },
        },
        (res) => {
          clearTimeout(connectTimer)
          if (res.statusCode !== 200) {
            reject(new Error("mux HTTP " + res.statusCode))
            res.resume()
            return
          }
          logger?.info?.("dsh-links: mux 桥已连接（SSE）")
          let buf = ""
          res.on("data", (chunk) => {
            buf += chunk.toString("utf8")
            buf = parseSseBlocks(buf, (block) => handleMuxBlock(block, rt, logger, requestPoll))
          })
          res.on("end", () => resolve())
          res.on("error", reject)
        },
      )
      activeReq = req
      req.on("error", (err) => {
        clearTimeout(connectTimer)
        reject(err)
      })
      // 建流后不设读超时（SSE 本来就长时间静默），但握手阶段必须有超时
      req.setTimeout(0)
      const connectTimer = setTimeout(() => {
        req.destroy(new Error("mux sse 握手超时"))
      }, 5_000)
      connectTimer.unref?.()
      req.end()
    })
  }

  ;(async () => {
    let everConnected = false
    while (!stopped) {
      try {
        await (useWs ? connectWs() : connectSse())
        everConnected = true
      } catch (err) {
        if (!stopped) {
          logger?.warn?.(`dsh-links: mux 桥（${useWs ? "ws" : "sse"}）：${err?.message ?? err}`)
          // 首连就失败：可能是主机传输与预期相反，翻到另一条再试
          if (!everConnected && typeof globalThis.WebSocket === "function") useWs = !useWs
        }
      }
      activeReq = null
      activeWs = null
      if (stopped) break
      await sleep(2_000)
    }
  })()

  return {
    stop() {
      stopped = true
      try { activeReq?.destroy() } catch {}
      try { activeWs?.close() } catch {}
      activeReq = null
      activeWs = null
    },
  }
}

/** 生成调试 id（未使用时可忽略）。 */
export function ephemeralId() {
  return randomBytes(6).toString("hex")
}
