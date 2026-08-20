/**
 * 认证与 18640 路由集成测试（第 1 批：fail-closed + 回环围栏 + 配对码）
 * 运行：node --test --test-timeout=30000 test/*.mjs
 */
import { test } from "node:test"
import assert from "node:assert/strict"
import { createServer } from "node:http"
import https from "node:https"
import { mkdtempSync, readFileSync, rmSync, statSync } from "node:fs"
import { tmpdir } from "node:os"
import { join } from "node:path"
import { Readable } from "node:stream"
import { apply } from "../src/index.js"
import { newPairingCode, pairingEntropy } from "../src/auth.js"

const TMP = mkdtempSync(join(tmpdir(), "dsh-auth-test-"))

function startUpstream() {
  return new Promise((resolve) => {
    const srv = createServer((req, res) => {
      res.writeHead(200, { "content-type": "text/plain" })
      res.end("upstream-ok")
    })
    srv.listen(0, "127.0.0.1", () => resolve(srv))
  })
}

function makeCtx(upstreamPort) {
  const registered = []
  const effects = []
  const ctx = {
    logger: { info() {}, warn() {} },
    get(name) {
      if (name === "webServer") {
        return {
          port: upstreamPort,
          register(route) { registered.push(route); return () => {} },
          tapIndex() { return () => {} },
        }
      }
      return null
    },
    on() {},
    // cordis 语义：立即执行 setup，收集它返回的 disposer。
    effect(fn) { effects.push(fn()) },
  }
  return { ctx, registered, effects }
}

function loopbackHost() {
  return `127.0.0.1:${upstream.address().port}`
}

function callRoute(route, { body, headers = {}, method } = {}) {
  let status = 0, outHeaders = {}, out = ""
  const res = {
    writeHead(c, h) { status = c; outHeaders = h || {} },
    end(b) { out = b == null ? "" : Buffer.isBuffer(b) ? b.toString("utf8") : String(b) },
  }
  const host = headers.host ?? headers.Host ?? loopbackHost()
  let req
  if (body !== undefined) {
    const payload = typeof body === "string" ? body : JSON.stringify(body)
    req = Readable.from([Buffer.from(payload)])
    req.method = method || "POST"
    req.headers = { host, "content-type": "application/json", ...headers }
  } else {
    req = { method: method || "GET", headers: { host, ...headers } }
  }
  return Promise.resolve(route.handler(req, res)).then(() => ({
    status,
    headers: outHeaders,
    body: parseJson(out),
    raw: out,
  }))
}

function parseJson(s) {
  try { return JSON.parse(s) } catch { return null }
}

function tokenHeaders(token) {
  return { "x-dsh-link-token": token, "content-type": "application/json" }
}

let proxyPort, upstream, dispose, registered

test.before(async () => {
  upstream = await startUpstream()
  const { ctx, registered: r, effects: e } = makeCtx(upstream.address().port)
  registered = r
  proxyPort = 21000 + Math.floor(Math.random() * 1000)
  await apply(ctx, {
    port: proxyPort,
    pairingTtlSeconds: 300,
    autoApprove: true,
    stateDir: TMP,
    eventPollIntervalMs: 60000,
  })
  dispose = () => {
    for (const fn of e) try { fn() } catch {}
  }
})

test.after(() => {
  dispose()
  upstream.close()
  rmSync(TMP, { recursive: true, force: true })
})

function tlsCreds() {
  return JSON.parse(readFileSync(join(TMP, "tls.json"), "utf8"))
}

function proxyFingerprint() {
  return tlsCreds().fingerprint
}

function pinAgent(expected = proxyFingerprint()) {
  const tls = tlsCreds()
  const want = String(expected).replace(/:/g, "").toLowerCase()
  if (want !== String(tls.fingerprint).replace(/:/g, "").toLowerCase()) {
    return new https.Agent({ rejectUnauthorized: true })
  }
  return new https.Agent({
    ca: tls.cert,
    rejectUnauthorized: true,
    checkServerIdentity: () => undefined,
  })
}

function proxyFetch(path, init = {}) {
  const url = new URL(`https://127.0.0.1:${proxyPort}${path}`)
  const method = init.method || "GET"
  const headers = init.headers || {}
  const agent = init.agent || pinAgent()
  return new Promise((resolve, reject) => {
    const req = https.request(url, { method, headers, agent }, (res) => {
      const chunks = []
      res.on("data", (c) => chunks.push(c))
      res.on("end", () => {
        const body = Buffer.concat(chunks)
        resolve(new Response(body, { status: res.statusCode, headers: res.headers }))
      })
    })
    req.on("error", reject)
    if (init.body) req.write(init.body)
    req.end()
  })
}

const base = () => `https://127.0.0.1:${proxyPort}`
const pairInfoRoute = () => registered.find((r) => r.path === "/dsh-link/pair-info")
const qrRoute = () => registered.find((r) => r.path === "/dsh-link/qr.png")
const revokeRoute = () => registered.find((r) => r.path === "/dsh-link/revoke")
const devicesRoute = () => registered.find((r) => r.path === "/dsh-link/devices")

test("18640 匿名 pair-info / qr.png 被拒", async () => {
  const r1 = await proxyFetch(`/dsh-link/pair-info`)
  assert.equal(r1.status, 404)
  const r2 = await proxyFetch(`/dsh-link/qr.png`)
  assert.equal(r2.status, 404)
})

test("health 不返回设备指纹", async () => {
  const r = await proxyFetch(`/dsh-link/health`)
  assert.equal(r.status, 200)
  const body = await r.json()
  assert.deepEqual(body, { ok: true })
})

test("主端口 Host 非回环 → 403", async () => {
  for (const route of [pairInfoRoute(), qrRoute(), revokeRoute(), devicesRoute()]) {
    const r = await callRoute(route, { headers: { host: "evil.com:3080" }, body: route === revokeRoute() ? { name: "x" } : undefined })
    assert.equal(r.status, 403, route.path)
    assert.equal(r.body?.pairingCode, undefined)
  }
})

test("主端口 sec-fetch-site: cross-site → 403", async () => {
  const r = await callRoute(pairInfoRoute(), { headers: { "sec-fetch-site": "cross-site" } })
  assert.equal(r.status, 403)
})

test("主端口 Origin 与 Host 不一致 → 403", async () => {
  const r = await callRoute(pairInfoRoute(), {
    headers: { origin: "http://evil.com", host: loopbackHost() },
  })
  assert.equal(r.status, 403)
})

test("无配对码时配对失败", async () => {
  const r = await proxyFetch(`/dsh-link/pair`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ code: "000000", deviceName: "测试机" }),
  })
  assert.equal(r.status, 401)
})

test("配对成功返回 deviceId + token，配对码一次性，重放失败", async () => {
  const info = await callRoute(pairInfoRoute())
  assert.equal(info.status, 200)
  const code = info.body.pairingCode
  assert.ok(/^\d{6}$/.test(code))

  const r = await proxyFetch(`/dsh-link/pair`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ code, deviceName: "测试机" }),
  })
  assert.equal(r.status, 200)
  const body = await r.json()
  assert.ok(body.token && body.token.length >= 32)
  assert.ok(body.deviceId && body.deviceId.startsWith("dev-"))
  globalThis.__testDevice = { token: body.token, deviceId: body.deviceId }

  const replay = await proxyFetch(`/dsh-link/pair`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ code, deviceName: "测试机2" }),
  })
  assert.equal(replay.status, 401)
})

test("同名设备不能静默替换", async () => {
  const info = await callRoute(pairInfoRoute())
  const code = info.body.pairingCode
  const r = await proxyFetch(`/dsh-link/pair`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ code, deviceName: "测试机" }),
  })
  assert.equal(r.status, 409)
})

test("带 token 的 POST pair-info 不得返回配对码", async () => {
  const r = await proxyFetch(`/dsh-link/pair-info`, {
    method: "POST",
    headers: tokenHeaders(globalThis.__testDevice.token),
    body: "{}",
  })
  assert.ok(r.status === 404 || r.status === 401)
  const body = await r.json().catch(() => ({}))
  assert.equal(body.pairingCode, undefined)
})

test("带 token 的 GET/POST revoke、devices → 404", async () => {
  const token = globalThis.__testDevice.token
  for (const path of ["/dsh-link/revoke", "/dsh-link/devices"]) {
    for (const method of ["GET", "POST"]) {
      const r = await proxyFetch(`${path}`, {
        method,
        headers: tokenHeaders(token),
        body: method === "POST" ? "{}" : undefined,
      })
      assert.equal(r.status, 404, `${method} ${path}`)
    }
  }
})

test("带 token 的未知路径 → 404（catch-all 已删）", async () => {
  const r = await proxyFetch(`/api/session.prompt`, {
    method: "POST",
    headers: tokenHeaders(globalThis.__testDevice.token),
    body: "{}",
  })
  assert.equal(r.status, 404)
  const page = await proxyFetch(`/`, {
    headers: { "x-dsh-link-token": globalThis.__testDevice.token },
  })
  assert.equal(page.status, 404)
})

test("POST pair 非 JSON content-type → 415", async () => {
  const r = await proxyFetch(`/dsh-link/pair`, {
    method: "POST",
    headers: { "content-type": "text/plain" },
    body: JSON.stringify({ code: "000000", deviceName: "x" }),
  })
  assert.equal(r.status, 415)
})

test("5 次失败后 pair-info 不换发新码，冷却仍在", async () => {
  const before = await callRoute(pairInfoRoute())
  assert.equal(before.status, 200)
  const code = before.body.pairingCode
  assert.ok(/^\d{6}$/.test(code))
  const wrong = code === "000000" ? "111111" : "000000"

  let last = null
  for (let i = 0; i < 5; i++) {
    last = await proxyFetch(`/dsh-link/pair`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ code: wrong, deviceName: `限流机${i}` }),
    })
    assert.equal(last.status, 401)
  }
  assert.equal((await last.json()).error, "配对码无效")

  const after = await callRoute(pairInfoRoute())
  assert.equal(after.status, 200)
  assert.equal(after.body.pairingCode, code)

  const again = await proxyFetch(`/dsh-link/pair`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ code: wrong, deviceName: "限流机后再试" }),
  })
  assert.equal(again.status, 401)
  assert.match((await again.json()).error, /频繁/)
})

test("配对码使用 crypto.randomInt", () => {
  let called = 0
  const orig = pairingEntropy.randomInt
  pairingEntropy.randomInt = (...args) => {
    called++
    assert.equal(args[0], 0)
    assert.equal(args[1], 1_000_000)
    return orig.apply(pairingEntropy, args)
  }
  try {
    const state = {}
    const code = newPairingCode(state, 60)
    assert.equal(called, 1)
    assert.match(code, /^\d{6}$/)
  } finally {
    pairingEntropy.randomInt = orig
  }
})

test("state.json 权限与不含明文 code", () => {
  const file = join(TMP, "state.json")
  const dirStat = statSync(TMP)
  const fileStat = statSync(file)
  assert.equal(dirStat.mode & 0o777, 0o700)
  assert.equal(fileStat.mode & 0o777, 0o600)
  const tlsStat = statSync(join(TMP, "tls.json"))
  assert.equal(tlsStat.mode & 0o777, 0o600)
  const parsed = JSON.parse(readFileSync(file, "utf8"))
  assert.equal(parsed.pairing?.code, undefined)
  assert.ok(!JSON.stringify(parsed).includes('"code":'))
})

test("吊销设备后 token 立即失效", async () => {
  const rev = await callRoute(revokeRoute(), { body: { deviceId: globalThis.__testDevice.deviceId } })
  assert.equal(rev.status, 200)

  const r = await proxyFetch(`/dsh-link/mobile/bootstrap`, {
    headers: { "x-dsh-link-token": globalThis.__testDevice.token },
  })
  assert.equal(r.status, 401)
})

test("lanUrls 分类功能：返回结构含 urls + infos", async () => {
  const info = await callRoute(pairInfoRoute())
  assert.equal(info.status, 200)
  assert.ok(info.body.urls && Array.isArray(info.body.urls))
  assert.ok(info.body.infos && Array.isArray(info.body.infos))
  assert.ok(info.body.infos.length === info.body.urls.length)
  for (const item of info.body.infos) {
    assert.ok(item.category && typeof item.category === "string")
    assert.ok(typeof item.isRecommended === "boolean")
  }
})

test("pair-info 含证书指纹且局域网 URL 为 https", async () => {
  const info = await callRoute(pairInfoRoute())
  assert.equal(info.status, 200)
  assert.match(info.body.certFingerprint, /^[0-9a-f]{64}$/)
  assert.equal(info.body.certFingerprint, proxyFingerprint())
  for (const url of info.body.urls ?? []) {
    if (url.includes(`:${proxyPort}`)) assert.match(url, /^https:\/\//)
  }
})

test("明文 HTTP 访问 18640 被拒绝", async () => {
  await assert.rejects(
    () => fetch(`http://127.0.0.1:${proxyPort}/dsh-link/health`),
  )
})

test("错误指纹拒绝 TLS", async () => {
  await assert.rejects(
    () => proxyFetch("/dsh-link/health", { agent: pinAgent("0".repeat(64)) }),
  )
})

