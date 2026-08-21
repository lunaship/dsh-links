/**
 * 把本机 apiproxy 的 mux `question/requested|resolved` 转发给手机 SSE，
 * 并用 /api/respond 回传答案。澄清卡不在 session.history 事件流里。
 */
import { request as httpRequest } from "node:http"
import { randomBytes } from "node:crypto"

function sleep(ms) {
  return new Promise((r) => setTimeout(r, ms))
}

function writeSse(writers, frame) {
  for (const conn of [...writers]) {
    try {
      const ok = conn.res.write(frame)
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

function handleMuxBlock(block, rt, logger) {
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
  const payload = frame?.payload
  const type = payload?.type
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
export function startMuxQuestionBridge({ targetPort, rt, logger }) {
  let stopped = false
  let activeReq = null

  async function connectOnce() {
    await new Promise((resolve, reject) => {
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
          if (res.statusCode !== 200) {
            reject(new Error("mux HTTP " + res.statusCode))
            res.resume()
            return
          }
          let buf = ""
          res.on("data", (chunk) => {
            buf += chunk.toString("utf8")
            buf = parseSseBlocks(buf, (block) => handleMuxBlock(block, rt, logger))
          })
          res.on("end", () => resolve())
          res.on("error", reject)
        },
      )
      activeReq = req
      req.on("error", reject)
      req.setTimeout(0)
      req.end()
    })
  }

  ;(async () => {
    while (!stopped) {
      try {
        await connectOnce()
      } catch (err) {
        if (!stopped) logger?.warn?.(`dsh-links: mux question bridge: ${err?.message ?? err}`)
      }
      activeReq = null
      if (stopped) break
      await sleep(2_000)
    }
  })()

  return {
    stop() {
      stopped = true
      try { activeReq?.destroy() } catch {}
      activeReq = null
    },
  }
}

/** 生成调试 id（未使用时可忽略）。 */
export function ephemeralId() {
  return randomBytes(6).toString("hex")
}
