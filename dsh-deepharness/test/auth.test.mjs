/**
 * 认证与 Web 会话集成测试（Gate 2）：
 *   - 18640 匿名配对信息/二维码被拒
 *   - 配对码一次性 + 同名设备不静默替换
 *   - web-tickets 单次 ticket → web-bootstrap 302 + HttpOnly session
 *   - session cookie 访问页面/资源放行，无 cookie 拒绝
 *   - 吊销设备立即清除 ticket 与 session
 * 运行：node --test dsh-deepharness/test/auth.test.mjs
 */
import { test } from "node:test"
import assert from "node:assert/strict"
import { createServer } from "node:http"
import { mkdtempSync, rmSync } from "node:fs"
import { tmpdir } from "node:os"
import { join } from "node:path"
import { Readable } from "node:stream"
import { apply } from "../src/index.js"

const TMP = mkdtempSync(join(tmpdir(), "dsh-auth-test-"))

/** 假 DSH 上游（3080 替身）。 */
function startUpstream() {
  return new Promise((resolve) => {
    const srv = createServer((req, res) => {
      res.writeHead(200, { "content-type": "text/plain" })
      res.end("upstream-ok")
    })
    srv.listen(0, "127.0.0.1", () => resolve(srv))
  })
}

/** mock ctx：webServer 收集注册路由；effect 收集清理函数。 */
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
    effect(fn) { effects.push(fn) },
  }
  return { ctx, registered, effects }
}

/** 直接调用 3080 注册路由的 handler（mock req/res）。 */
function callRoute(route, bodyObj) {
  let status = 0, headers = {}, out = ""
  const res = {
    writeHead(c, h) { status = c; headers = h || {} },
    end(b) { out = b || "" },
  }
  let req
  if (bodyObj !== undefined) {
    req = Readable.from([Buffer.from(JSON.stringify(bodyObj))])
    req.headers = {}
  } else {
    req = { headers: {} }
  }
  const result = route.handler(req, res)
  return Promise.resolve(result).then(() => ({ status, headers, body: parseJson(out) }))
}

function parseJson(s) {
  try { return JSON.parse(s) } catch { return null }
}

let proxyPort, upstream, dispose, registered, effects

test.before(async () => {
  upstream = await startUpstream()
  const { ctx, registered: r, effects: e } = makeCtx(upstream.address().port)
  registered = r
  effects = e
  proxyPort = 21000 + Math.floor(Math.random() * 1000)
  apply(ctx, {
    port: proxyPort,
    pairingTtlSeconds: 300,
    autoApprove: true,
    stateDir: TMP,
    eventPollIntervalMs: 60000,
  })
  // 等 proxy 就绪
  await new Promise((r) => setTimeout(r, 200))
  dispose = () => {
    for (const fn of e) try { fn() } catch {}
  }
})

test.after(() => {
  dispose()
  upstream.close()
  rmSync(TMP, { recursive: true, force: true })
})

const base = () => `http://127.0.0.1:${proxyPort}`
const pairInfoRoute = () => registered.find((r) => r.path === "/dsh-link/pair-info")
const revokeRoute = () => registered.find((r) => r.path === "/dsh-link/revoke")

test("18640 匿名 pair-info / qr.png 被拒", async () => {
  const r1 = await fetch(`${base()}/dsh-link/pair-info`)
  assert.equal(r1.status, 401)
  const r2 = await fetch(`${base()}/dsh-link/qr.png`)
  assert.equal(r2.status, 401)
})

test("无配对码时配对失败", async () => {
  const r = await fetch(`${base()}/dsh-link/pair`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ code: "000000", deviceName: "测试机" }),
  })
  assert.equal(r.status, 401)
})

test("配对成功返回 deviceId + token，配对码一次性，重放失败", async () => {
  // 从 3080 面板取配对码
  const info = await callRoute(pairInfoRoute())
  assert.equal(info.status, 200)
  const code = info.body.pairingCode
  assert.ok(/^\d{6}$/.test(code))

  const r = await fetch(`${base()}/dsh-link/pair`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ code, deviceName: "测试机" }),
  })
  assert.equal(r.status, 200)
  const body = await r.json()
  assert.ok(body.token && body.token.length >= 32)
  assert.ok(body.deviceId && body.deviceId.startsWith("dev-"))
  globalThis.__testDevice = { token: body.token, deviceId: body.deviceId }

  // 重放同码 → 一次性拒绝
  const replay = await fetch(`${base()}/dsh-link/pair`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ code, deviceName: "测试机2" }),
  })
  assert.equal(replay.status, 401)
})

test("同名设备不能静默替换", async () => {
  const info = await callRoute(pairInfoRoute())
  const code = info.body.pairingCode
  const r = await fetch(`${base()}/dsh-link/pair`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ code, deviceName: "测试机" }),
  })
  assert.equal(r.status, 409)
})

test("web-tickets：无凭据 401；有效 token 返回单次 ticket", async () => {
  const anon = await fetch(`${base()}/dsh-link/mobile/web-tickets`, { method: "POST" })
  assert.equal(anon.status, 401)

  const ok = await fetch(`${base()}/dsh-link/mobile/web-tickets`, {
    method: "POST",
    headers: { "x-dsh-link-token": globalThis.__testDevice.token },
  })
  assert.equal(ok.status, 200)
  const body = await ok.json()
  assert.ok(body.ticket && body.expiresAt > Date.now())
  assert.equal(body.bootstrapPath, "/dsh-link/web-bootstrap")
  globalThis.__testTicket = body.ticket
})

test("web-bootstrap：有效 ticket → 302 + HttpOnly session cookie；二次消费失败", async () => {
  const r = await fetch(`${base()}/dsh-link/web-bootstrap?ticket=${globalThis.__testTicket}`, { redirect: "manual" })
  assert.equal(r.status, 302)
  assert.equal(r.headers.get("location"), "/")
  const setCookie = r.headers.get("set-cookie") || ""
  assert.match(setCookie, /dsh_web_session=/)
  assert.match(setCookie, /HttpOnly/)
  assert.match(setCookie, /SameSite=Strict/)
  assert.match(setCookie, /Max-Age=1800/)
  globalThis.__testSession = setCookie.split(";")[0]

  // 二次消费同 ticket → 401
  const replay = await fetch(`${base()}/dsh-link/web-bootstrap?ticket=${globalThis.__testTicket}`, { redirect: "manual" })
  assert.equal(replay.status, 401)

  // 无 ticket → 401
  const none = await fetch(`${base()}/dsh-link/web-bootstrap`, { redirect: "manual" })
  assert.equal(none.status, 401)
})

test("带 session cookie 访问页面放行；无 cookie 拒绝", async () => {
  const authed = await fetch(`${base()}/`, { headers: { cookie: globalThis.__testSession } })
  assert.equal(authed.status, 200)
  assert.equal(await authed.text(), "upstream-ok")

  const anon = await fetch(`${base()}/`)
  assert.equal(anon.status, 401)
})

test("吊销设备后 session 与 ticket 立即失效", async () => {
  const rev = await callRoute(revokeRoute(), { deviceId: globalThis.__testDevice.deviceId })
  assert.equal(rev.status, 200)

  // 旧 session → 401
  const old = await fetch(`${base()}/`, { headers: { cookie: globalThis.__testSession } })
  assert.equal(old.status, 401)

  // 旧 token → 401（设备已删除）
  const tickets = await fetch(`${base()}/dsh-link/mobile/web-tickets`, {
    method: "POST",
    headers: { "x-dsh-link-token": globalThis.__testDevice.token },
  })
  assert.equal(tickets.status, 401)
})

test("5 次失败配对触发冷却（限流）", async () => {
  let last = null
  for (let i = 0; i < 6; i++) {
    last = await fetch(`${base()}/dsh-link/pair`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ code: "000000", deviceName: `限流机${i}` }),
    })
  }
  assert.equal(last.status, 401)
  const body = await last.json()
  assert.match(body.error, /频繁/)
})

test("lanUrls 分类功能：返回结构含 urls + infos", async () => {
  // pair-info 仅在 3080 面板可见（18640 返回 401），从注册路由取
  const info = await callRoute(pairInfoRoute())
  assert.equal(info.status, 200)
  assert.ok(info.body.urls && Array.isArray(info.body.urls))
  assert.ok(info.body.infos && Array.isArray(info.body.infos))
  assert.ok(info.body.infos.length === info.body.urls.length)
  // 验证每个条目都有分类标记
  for (const item of info.body.infos) {
    assert.ok(item.category && typeof item.category === "string")
    assert.ok(typeof item.isRecommended === "boolean")
  }
})

test("pair-info 分类信息：至少一条局域网 IP 且推荐标记合理", async () => {
  const info = await callRoute(pairInfoRoute())
  const infos = info.body.infos ?? []
  assert.ok(infos.length > 0, "应至少展示一个可用地址")
  // 推荐标记只有一个
  const recommended = infos.filter((u) => u.isRecommended)
  assert.equal(recommended.length, 1, "推荐地址应唯一")
})
