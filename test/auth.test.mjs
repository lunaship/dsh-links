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
import { apply, Config } from "../src/index.js"
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

function callRoute(route, { body, headers = {}, method, remoteAddress = "127.0.0.1", url } = {}) {
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
  req.url = url || route.path || "/"
  req.socket = { remoteAddress }
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

test("拒绝无效的代理端口与过短 SSE 轮询间隔", () => {
  assert.throws(() => Config({ port: 0 }), /expected number >= 1/)
  assert.throws(() => Config({ eventPollIntervalMs: 0 }), /expected number >= 100/)
  assert.equal(Config({ port: 1, eventPollIntervalMs: 100 }).port, 1)
  assert.equal(Config({ port: 1, eventPollIntervalMs: 100 }).eventPollIntervalMs, 100)
})

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
const revokeAllRoute = () => registered.find((r) => r.path === "/dsh-link/revoke-all")
const pairApproveRoute = () => registered.find((r) => r.path === "/dsh-link/pair-approve")
const pairSettingsRoute = () => registered.find((r) => r.path === "/dsh-link/pair-settings")
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
  for (const route of [pairInfoRoute(), qrRoute(), revokeRoute(), revokeAllRoute(), pairApproveRoute(), pairSettingsRoute(), devicesRoute()]) {
    const r = await callRoute(route, { headers: { host: "evil.com:3080" }, body: route === revokeRoute() ? { name: "x" } : undefined })
    assert.equal(r.status, 403, route.path)
    assert.equal(r.body?.pairingCode, undefined)
  }
})

test("主端口伪造 Host 回环但 remote 非回环 → 403", async () => {
  const r = await callRoute(pairInfoRoute(), {
    headers: { host: loopbackHost() },
    remoteAddress: "192.168.1.50",
  })
  assert.equal(r.status, 403)
  assert.equal(r.body?.pairingCode, undefined)
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

test("配对成功返回 deviceId + token，重复 requestId 只复用原设备", async () => {
  const info = await callRoute(pairInfoRoute())
  assert.equal(info.status, 200)
  const code = info.body.pairingCode
  assert.ok(/^\d{6}$/.test(code))

  const r = await proxyFetch(`/dsh-link/pair`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ code, deviceName: "测试机", requestId: "pair-test-replay-1" }),
  })
  assert.equal(r.status, 200)
  const body = await r.json()
  assert.ok(body.token && body.token.length >= 32)
  assert.ok(body.deviceId && body.deviceId.startsWith("dev-"))
  globalThis.__testDevice = { token: body.token, deviceId: body.deviceId }

  const replaySame = await proxyFetch(`/dsh-link/pair`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ code, deviceName: "测试机", via: "relay", requestId: "pair-test-replay-1" }),
  })
  assert.equal(replaySame.status, 200)
  assert.deepEqual(await replaySame.json(), body)

  const replay = await proxyFetch(`/dsh-link/pair`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ code, deviceName: "测试机2" }),
  })
  assert.equal(replay.status, 401)
})

test("没有 requestId 的旧客户端重放仍被一次性配对码拒绝", async () => {
  const info = await callRoute(pairInfoRoute())
  const body = { code: info.body.pairingCode, deviceName: "旧客户端" }
  const first = await proxyFetch(`/dsh-link/pair`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify(body),
  })
  assert.equal(first.status, 200)
  const replay = await proxyFetch(`/dsh-link/pair`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify(body),
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

test("pair via=relay 与局域网设备分开列出", async () => {
  const info = await callRoute(pairInfoRoute())
  const code = info.body.pairingCode
  const pair = await proxyFetch(`/dsh-link/pair`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ code, deviceName: "测试机-云端·云", via: "relay" }),
  })
  assert.equal(pair.status, 200)
  const extra = await pair.json()
  const list = await callRoute(devicesRoute())
  assert.equal(list.status, 200)
  const cloud = list.body.devices.find((d) => d.deviceId === extra.deviceId)
  const lan = list.body.devices.find((d) => d.deviceId === globalThis.__testDevice.deviceId)
  assert.equal(cloud?.via, "relay")
  assert.equal(lan?.via, "lan")
  const rev = await callRoute(revokeRoute(), { body: { deviceId: extra.deviceId } })
  assert.equal(rev.status, 200)
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
  for (const path of [
    "/dsh-link/revoke",
    "/dsh-link/revoke-all",
    "/dsh-link/pair-approve",
    "/dsh-link/pair-settings",
    "/dsh-link/devices",
  ]) {
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

test("prompt 拒绝非图片 mediaType", async () => {
  const token = globalThis.__testDevice.token
  const r = await proxyFetch(`/dsh-link/mobile/sessions/sess-1/prompt`, {
    method: "POST",
    headers: tokenHeaders(token),
    body: JSON.stringify({
      text: "hi",
      images: [{ mediaType: "image/svg+xml", data: "PHN2Zy8+" }],
    }),
  })
  assert.equal(r.status, 400)
  assert.match((await r.json()).error, /图片类型不允许/)
})

test("mobile GET /devices 列出已配对设备", async () => {
  const token = globalThis.__testDevice.token
  const r = await proxyFetch(`/dsh-link/mobile/devices`, {
    headers: tokenHeaders(token),
  })
  assert.equal(r.status, 200)
  const body = await r.json()
  assert.ok(Array.isArray(body.devices))
  const self = body.devices.find((d) => d.deviceId === globalThis.__testDevice.deviceId)
  assert.ok(self)
  assert.equal(self.via, "lan")
})

test("mobile POST /revoke 可吊销其他设备", async () => {
  const info = await callRoute(pairInfoRoute())
  const code = info.body.pairingCode
  const pair = await proxyFetch(`/dsh-link/pair`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ code, deviceName: "测试机-吊销" }),
  })
  assert.equal(pair.status, 200)
  const extra = await pair.json()

  const revoke = await proxyFetch(`/dsh-link/mobile/revoke`, {
    method: "POST",
    headers: tokenHeaders(globalThis.__testDevice.token),
    body: JSON.stringify({ deviceId: extra.deviceId }),
  })
  assert.equal(revoke.status, 200)

  const list = await proxyFetch(`/dsh-link/mobile/devices`, {
    headers: tokenHeaders(globalThis.__testDevice.token),
  })
  const body = await list.json()
  assert.ok(!body.devices.some((d) => d.deviceId === extra.deviceId))
  assert.ok(body.devices.some((d) => d.deviceId === globalThis.__testDevice.deviceId))
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

test("pair-info 含监听暴露信息与 requireConfirm", async () => {
  const info = await callRoute(pairInfoRoute())
  assert.equal(info.status, 200)
  assert.equal(info.body.requireConfirm, false)
  assert.equal(info.body.exposure?.listen?.address, "0.0.0.0")
  assert.equal(info.body.exposure?.listen?.port, proxyPort)
  assert.ok(info.body.exposure?.level === "lan" || info.body.exposure?.level === "untrusted")
  assert.ok(Array.isArray(info.body.exposure?.networks))
})

test("开启配对确认后 token 待批准，API 403，批准后放行", async () => {
  const settings = await callRoute(pairSettingsRoute(), { body: { requireConfirm: true } })
  assert.equal(settings.status, 200)
  assert.equal(settings.body.requireConfirm, true)

  const info = await callRoute(pairInfoRoute())
  assert.equal(info.body.requireConfirm, true)
  const pair = await proxyFetch(`/dsh-link/pair`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ code: info.body.pairingCode, deviceName: "测试机-待确认" }),
  })
  assert.equal(pair.status, 200)
  const pending = await pair.json()
  assert.equal(pending.pending, true)
  assert.ok(pending.token)
  assert.ok(pending.deviceId)

  const denied = await proxyFetch(`/dsh-link/mobile/devices`, {
    headers: tokenHeaders(pending.token),
  })
  assert.equal(denied.status, 403)
  assert.equal((await denied.json()).pending, true)

  const listed = await callRoute(devicesRoute())
  const row = listed.body.devices.find((d) => d.deviceId === pending.deviceId)
  assert.equal(row?.status, "pending")
  assert.ok(row?.pairedFrom)

  const twice = await callRoute(pairApproveRoute(), { body: { deviceId: pending.deviceId } })
  assert.equal(twice.status, 200)
  const again = await callRoute(pairApproveRoute(), { body: { deviceId: pending.deviceId } })
  assert.equal(again.status, 409)

  const allowed = await proxyFetch(`/dsh-link/mobile/devices`, {
    headers: tokenHeaders(pending.token),
  })
  assert.equal(allowed.status, 200)

  const rev = await callRoute(revokeRoute(), { body: { deviceId: pending.deviceId } })
  assert.equal(rev.status, 200)
  const off = await callRoute(pairSettingsRoute(), { body: { requireConfirm: false } })
  assert.equal(off.status, 200)
  assert.equal(off.body.requireConfirm, false)
})

test("关闭配对确认不放行已有 pending 设备", async () => {
  await callRoute(pairSettingsRoute(), { body: { requireConfirm: true } })
  const info = await callRoute(pairInfoRoute())
  const pair = await proxyFetch(`/dsh-link/pair`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ code: info.body.pairingCode, deviceName: "测试机-保持等待" }),
  })
  assert.equal(pair.status, 200)
  const pending = await pair.json()
  assert.equal(pending.pending, true)

  const off = await callRoute(pairSettingsRoute(), { body: { requireConfirm: false } })
  assert.equal(off.status, 200)
  assert.equal(off.body.requireConfirm, false)

  const stillDenied = await proxyFetch(`/dsh-link/mobile/devices`, {
    headers: tokenHeaders(pending.token),
  })
  assert.equal(stillDenied.status, 403)
  assert.equal((await stillDenied.json()).pending, true)
  const listed = await callRoute(devicesRoute())
  assert.equal(listed.body.devices.find((d) => d.deviceId === pending.deviceId)?.status, "pending")

  const nextInfo = await callRoute(pairInfoRoute())
  const nextPair = await proxyFetch(`/dsh-link/pair`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ code: nextInfo.body.pairingCode, deviceName: "测试机-关闭后新配" }),
  })
  assert.equal(nextPair.status, 200)
  const fresh = await nextPair.json()
  assert.equal(fresh.pending, false)
  const allowed = await proxyFetch(`/dsh-link/mobile/devices`, {
    headers: tokenHeaders(fresh.token),
  })
  assert.equal(allowed.status, 200)

  assert.equal((await callRoute(revokeRoute(), { body: { deviceId: pending.deviceId } })).status, 200)
  assert.equal((await callRoute(revokeRoute(), { body: { deviceId: fresh.deviceId } })).status, 200)
})

test("吊销全部设备后列表为空，可重新配对", async () => {
  const before = await callRoute(devicesRoute())
  assert.ok((before.body.devices ?? []).length >= 1)
  const all = await callRoute(revokeAllRoute(), { body: {} })
  assert.equal(all.status, 200)
  assert.ok(all.body.removed >= 1)
  const empty = await callRoute(devicesRoute())
  assert.equal(empty.body.devices.length, 0)

  const info = await callRoute(pairInfoRoute())
  const pair = await proxyFetch(`/dsh-link/pair`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ code: info.body.pairingCode, deviceName: "测试机" }),
  })
  assert.equal(pair.status, 200)
  const body = await pair.json()
  assert.equal(body.pending, false)
  globalThis.__testDevice = { token: body.token, deviceId: body.deviceId }
})

test("POST pair 非 JSON content-type → 415", async () => {
  const r = await proxyFetch(`/dsh-link/pair`, {
    method: "POST",
    headers: { "content-type": "text/plain" },
    body: JSON.stringify({ code: "000000", deviceName: "x" }),
  })
  assert.equal(r.status, 415)
})

test("设备在请求体尚未完成时被吊销，连接立即终止", async () => {
  const info = await callRoute(pairInfoRoute())
  const pair = await proxyFetch(`/dsh-link/pair`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ code: info.body.pairingCode, deviceName: "测试机-并发吊销" }),
  })
  assert.equal(pair.status, 200)
  const stagedDevice = await pair.json()
  const outcome = new Promise((resolve) => {
    const req = https.request(new URL(`https://127.0.0.1:${proxyPort}/dsh-link/mobile/sessions/demo/prompt`), {
      method: "POST",
      agent: pinAgent(),
      headers: {
        ...tokenHeaders(stagedDevice.token),
        "content-length": "128",
      },
    }, (res) => resolve({ type: "response", status: res.statusCode }))
    req.once("error", (err) => resolve({ type: "closed", error: err }))
    req.flushHeaders()
    globalThis.__stagedRequest = req
  })
  await new Promise((resolve) => setTimeout(resolve, 30))
  const revoked = await callRoute(revokeRoute(), { body: { deviceId: stagedDevice.deviceId } })
  assert.equal(revoked.status, 200)
  if (!globalThis.__stagedRequest.destroyed) globalThis.__stagedRequest.end(JSON.stringify({ text: "不应送达" }))
  const result = await Promise.race([
    outcome,
    new Promise((resolve) => setTimeout(() => resolve({ type: "timeout" }), 1000)),
  ])
  delete globalThis.__stagedRequest
  assert.equal(result.type, "closed")
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
  assert.equal(again.status, 429)
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
  assert.equal(parsed.pairing?.codeHash, undefined)
  assert.equal(parsed.pairing?.salt, undefined)
  assert.ok(!JSON.stringify(parsed).includes('"code":'))
})

test("落盘 pairing hash 不能在无内存码时通过校验", async () => {
  const { hydratePairing, newPairingCode, persistablePairing, verifyPairingCode } = await import("../src/auth.js")
  const state = {}
  const code = newPairingCode(state, 600)
  assert.equal(verifyPairingCode(state, code).ok, true)
  assert.deepEqual(persistablePairing(state.pairing), {})

  const stolen = {
    pairing: {
      salt: state.pairing.salt,
      codeHash: state.pairing.codeHash,
      expiresAt: state.pairing.expiresAt,
      consumed: false,
    },
  }
  hydratePairing(stolen)
  const fromDisk = verifyPairingCode(stolen, code)
  assert.equal(fromDisk.ok, false)
  assert.match(fromDisk.error, /尚未生成配对码/)
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
  assert.equal(info.body.relay, undefined)
})

test("pair-info via=relay 无中继时仍不含 relay", async () => {
  const info = await callRoute(pairInfoRoute(), { url: "/dsh-link/pair-info?via=relay" })
  assert.equal(info.status, 200)
  assert.equal(info.body.relay, undefined)
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

test("配对失败限流按 IP 隔离，不全局锁死", async () => {
  const { PAIR_FAIL_LIMIT, newPairingCode, verifyPairingCode } = await import("../src/auth.js")
  const state = {}
  const code = newPairingCode(state, 600)
  for (let i = 0; i < PAIR_FAIL_LIMIT; i++) {
    const r = verifyPairingCode(state, "000000", "10.0.0.1")
    assert.equal(r.ok, false)
  }
  const locked = verifyPairingCode(state, code, "10.0.0.1")
  assert.equal(locked.ok, false)
  assert.match(locked.error, /频繁|稍后再试/)
  // 另一 IP 仍可用正确码配对（未打到跨 IP 挑战预算）
  const other = verifyPairingCode(state, code, "10.0.0.2")
  assert.equal(other.ok, true)
})

test("配对码跨 IP 总失败次数达到上限后作废", async () => {
  const { PAIR_CHALLENGE_FAIL_LIMIT, newPairingCode, verifyPairingCode } = await import("../src/auth.js")
  const state = {}
  const code = newPairingCode(state, 600)
  for (let i = 0; i < PAIR_CHALLENGE_FAIL_LIMIT; i++) {
    const r = verifyPairingCode(state, "000000", `10.1.${Math.floor(i / 250)}.${i % 250}`)
    assert.equal(r.ok, false)
  }
  const after = verifyPairingCode(state, code, "10.2.0.1")
  assert.equal(after.ok, false)
  assert.match(after.error, /失效|已使用|频繁/)
})

test("无效或已消费的配对请求不分配限流桶，桶总量有硬上限", async () => {
  const { PAIR_RATE_BUCKET_MAX, pairingRateFor, verifyPairingCode } = await import("../src/auth.js")
  const invalid = {}
  for (let i = 0; i < 2000; i++) verifyPairingCode(invalid, "000000", `192.0.2.${i}`)
  assert.equal(invalid.pairingRates, undefined)

  const bounded = {}
  for (let i = 0; i < PAIR_RATE_BUCKET_MAX + 100; i++) pairingRateFor(bounded, `198.51.100.${i}`)
  assert.equal(bounded.pairingRates.size, PAIR_RATE_BUCKET_MAX)
})

test("设备 token 落盘哈希为每安装 HMAC：跨安装不同、同安装稳定", async () => {
  const { hmacDeviceToken } = await import("../src/auth.js")
  const a = {}
  const b = {}
  const token = "a".repeat(48)
  const hashA = hmacDeviceToken(a, token)
  assert.match(hashA, /^[0-9a-f]{64}$/)
  assert.equal(hashA, hmacDeviceToken(a, token))
  assert.notEqual(hashA, hmacDeviceToken(b, token))
  assert.ok(a.tokenKey && b.tokenKey && a.tokenKey !== b.tokenKey)
})

test("旧版裸 SHA-256 tokenHash 命中标记 legacy，迁移为 HMAC 后走新格式", async () => {
  const { createHash } = await import("node:crypto")
  const { findDeviceByToken, hmacDeviceToken } = await import("../src/auth.js")
  const token = "legacy-token-0123456789abcdef"
  const state = {
    devices: [
      { deviceId: "dev-1", tokenHash: createHash("sha256").update(token).digest("hex") },
    ],
  }
  const hit = findDeviceByToken(state, token)
  assert.equal(hit.device?.deviceId, "dev-1")
  assert.equal(hit.legacy, true)
  // 与 authorize 相同的迁移写回
  hit.device.tokenHash = hmacDeviceToken(state, token)
  const again = findDeviceByToken(state, token)
  assert.equal(again.device?.deviceId, "dev-1")
  assert.equal(again.legacy, false)
  assert.equal(findDeviceByToken(state, "wrong-token").device, null)
})

test("readDeviceToken 接受自定义头、重复头与 Bearer", async () => {
  const { readDeviceToken } = await import("../src/auth.js")
  assert.equal(readDeviceToken({ "x-dsh-link-token": "abc" }), "abc")
  assert.equal(readDeviceToken({ "x-dsh-link-token": ["abc"] }), "abc")
  assert.equal(readDeviceToken({ "x-dsh-link-token": "abc, abc" }), "abc")
  assert.equal(readDeviceToken({ authorization: "Bearer tok-1" }), "tok-1")
  assert.equal(readDeviceToken({ "x-dsh-link-token": "  ", authorization: "Bearer tok-2" }), "tok-2")
  assert.equal(readDeviceToken({}), null)
  assert.equal(readDeviceToken({ "x-dsh-link-token": ["", "  "] }), null)
})

test("token 哈希比较是定时安全且等长错误 token 不命中", async () => {
  const { findDeviceByToken, hmacDeviceToken } = await import("../src/auth.js")
  const token = "a".repeat(24)
  const wrong = "b".repeat(24)
  const state = { tokenKey: "key", devices: [] }
  state.devices.push({ deviceId: "dev-1", tokenHash: hmacDeviceToken(state, token) })
  // HMAC 匹配
  const hit = findDeviceByToken(state, token)
  assert.equal(hit.device?.deviceId, "dev-1")
  assert.equal(hit.legacy, false)
  // 等长但不同的 token：HMAC 与 legacy 均不得命中
  assert.equal(findDeviceByToken(state, wrong).device, null)
})
