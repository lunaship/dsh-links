/**
 * mobile API 错误映射回归：会话被网页端/其他设备占用时返回 409 session_busy 与可读文案，
 * 而不是笼统的 502 mobile API unavailable（2026-09-13 真机验收发现）。
 */
import { test } from "node:test"
import assert from "node:assert/strict"
import { createServer } from "node:http"
import { mkdtempSync, rmSync } from "node:fs"
import { tmpdir } from "node:os"
import { join } from "node:path"
import { apply } from "../src/index.js"
import { bindLocalRpcRuntime, unbindLocalRpcRuntime } from "../src/local-rpc.js"

const TMP = mkdtempSync(join(tmpdir(), "dsh-busy-test-"))
const PORT = 21000 + Math.floor(Math.random() * 1000)

const upstream = await new Promise((resolve) => {
  const srv = createServer((req, res) => {
    res.writeHead(200, { "content-type": "text/plain" })
    res.end("upstream-ok")
  })
  srv.listen(0, "127.0.0.1", () => resolve(srv))
})

const registered = []
const effects = []
const ctx = {
  logger: { info() {}, warn() {} },
  get(name) {
    if (name === "webServer") {
      return {
        port: upstream.address().port,
        register(route) { registered.push(route); return () => {} },
        tapIndex() { return () => {} },
      }
    }
    return null
  },
  on() {},
  effect(fn) { effects.push(fn()) },
}

await apply(ctx, { port: PORT, pairingTtlSeconds: 300, autoApprove: true, stateDir: TMP, eventPollIntervalMs: 60000 })

async function proxyFetch(path, init = {}) {
  const { mkcert } = { mkcert: null }
  void mkcert
  const https = await import("node:https")
  const tls = JSON.parse((await import("node:fs")).readFileSync(join(TMP, "tls.json"), "utf8"))
  const agent = new https.Agent({ ca: tls.cert, rejectUnauthorized: true, checkServerIdentity: () => undefined })
  return new Promise((resolve, reject) => {
    const req = https.request(new URL(`https://127.0.0.1:${PORT}${path}`), {
      method: init.method || "GET",
      headers: init.headers || {},
      agent,
    }, (res) => {
      const chunks = []
      res.on("data", (c) => chunks.push(c))
      res.on("end", () => resolve(new Response(Buffer.concat(chunks), { status: res.statusCode, headers: res.headers })))
    })
    req.on("error", reject)
    if (init.body) req.write(init.body)
    req.end()
  })
}

// 配对拿 token
const pairInfoRoute = registered.find((r) => r.path === "/dsh-link/pair-info")
{
  let status = 0, out = ""
  const res = { writeHead(c) { status = c }, end(b) { out = String(b ?? "") } }
  await pairInfoRoute.handler({ method: "GET", headers: { host: `127.0.0.1:${upstream.address().port}` }, url: "/dsh-link/pair-info", socket: { remoteAddress: "127.0.0.1" } }, res)
  const code = JSON.parse(out).pairingCode
  const pair = await proxyFetch(`/dsh-link/pair`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ code, deviceName: "busy-test", requestId: "busy-test-1" }),
  })
  assert.equal(pair.status, 200)
  var token = (await pair.json()).token
}

test("被占用的会话发送返回 409 session_busy 与可读文案", async () => {
  bindLocalRpcRuntime({
    invoke: async () => {
      throw new Error(`resume failed for session "session-x": SessionAlreadyOwnedError: session "session-x" is already owned by an active write handle`)
    },
  })
  try {
    const r = await proxyFetch(`/dsh-link/mobile/sessions/sess-1/prompt`, {
      method: "POST",
      headers: { "x-dsh-link-token": token, "content-type": "application/json" },
      body: JSON.stringify({ text: "hi" }),
    })
    assert.equal(r.status, 409)
    const body = await r.json()
    assert.equal(body.code, "session_busy")
    assert.match(body.error, /会话正被网页端|换一个会话/)
  } finally {
    unbindLocalRpcRuntime()
  }
})

test("其他移动 API 错误仍为 502", async () => {
  bindLocalRpcRuntime({
    invoke: async () => {
      throw new Error("connection refused")
    },
  })
  try {
    const r = await proxyFetch(`/dsh-link/mobile/sessions/sess-1/prompt`, {
      method: "POST",
      headers: { "x-dsh-link-token": token, "content-type": "application/json" },
      body: JSON.stringify({ text: "hi" }),
    })
    assert.equal(r.status, 502)
    assert.equal((await r.json()).error, "mobile API unavailable")
  } finally {
    unbindLocalRpcRuntime()
  }
})

test.after(() => {
  for (const fn of effects) try { fn() } catch {}
  upstream.close()
  rmSync(TMP, { recursive: true, force: true })
})
