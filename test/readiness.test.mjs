/**
 * 启动就绪窗口回归测试：pair-info / qr.png 在 TLS 加载完成且 HTTPS listen 成功之前，
 * 不得返回可用于扫码但不带证书指纹的配对信息（冷启动竞态，2026-09-13 修复）。
 * 关键点：apply() 内路由是同步注册的，测试不 await apply 的返回值，在同一个同步块里
 * 调用路由 handler，即可确定性地命中就绪前窗口（微任务尚未运行）。
 */
import { test } from "node:test"
import assert from "node:assert/strict"
import { createServer } from "node:http"
import { copyFileSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs"
import { tmpdir } from "node:os"
import { join } from "node:path"
import { apply } from "../src/index.js"

const ROOT_TMP = mkdtempSync(join(tmpdir(), "dsh-readiness-test-"))

const upstream = await new Promise((resolve) => {
  const srv = createServer((req, res) => {
    res.writeHead(200, { "content-type": "text/plain" })
    res.end("upstream-ok")
  })
  srv.listen(0, "127.0.0.1", () => resolve(srv))
})

test.after(() => {
  for (const fn of cold.effects) try { fn() } catch {}
  upstream.close()
  rmSync(ROOT_TMP, { recursive: true, force: true })
})

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
    effect(fn) { effects.push(fn()) },
  }
  return { ctx, registered, effects }
}

function freePort() {
  // 借一个临时服务器拿空闲端口再立即释放；存在极小竞争窗口，对测试可接受。
  return new Promise((resolve, reject) => {
    const srv = createServer()
    srv.unref()
    srv.once("error", reject)
    srv.listen(0, "127.0.0.1", () => {
      const port = srv.address().port
      srv.close(() => resolve(port))
    })
  })
}

function callRoute(route, { headers = {}, remoteAddress = "127.0.0.1", url } = {}) {
  let status = 0, outHeaders = {}, out = ""
  const res = {
    writeHead(c, h) { status = c; outHeaders = h || {} },
    end(b) { out = b == null ? "" : Buffer.isBuffer(b) ? b.toString("utf8") : String(b) },
  }
  const req = { method: "GET", headers: { host: `127.0.0.1:${upstream.address().port}`, ...headers } }
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

function pairInfoRoute(registered) {
  return registered.find((r) => r.path === "/dsh-link/pair-info")
}
function qrRoute(registered) {
  return registered.find((r) => r.path === "/dsh-link/qr.png")
}

function configFor(stateDir, port) {
  return {
    port,
    pairingTtlSeconds: 300,
    autoApprove: true,
    stateDir,
    eventPollIntervalMs: 60000,
  }
}

const READY_PORT = await freePort()
const READY_DIR = join(ROOT_TMP, "cold-start")
const cold = (() => {
  const { ctx, registered, effects } = makeCtx(upstream.address().port)
  // 不 await：路由同步注册完成后立即在同步块内打路由，确定性命中就绪前窗口。
  const readyPromise = apply(ctx, configFor(READY_DIR, READY_PORT))
  const earlyPairPromise = callRoute(pairInfoRoute(registered))
  const earlyQrPromise = callRoute(qrRoute(registered))
  return { readyPromise, earlyPairPromise, earlyQrPromise, registered, effects }
})()

test("就绪前窗口：pair-info / qr.png 返回可识别 503，不含配对码与指纹", async () => {
  const pair = await cold.earlyPairPromise
  assert.equal(pair.status, 503)
  assert.equal(pair.body?.error, "proxy_not_ready")
  assert.equal(pair.body?.phase, "starting")
  assert.equal(pair.body?.pairingCode, undefined)
  assert.equal(pair.body?.certFingerprint, undefined)
  assert.equal(pair.headers?.["retry-after"], "1")

  const qr = await cold.earlyQrPromise
  assert.equal(qr.status, 503)
  assert.equal(qr.body?.error, "proxy_not_ready")
  assert.equal(qr.raw.includes("png"), false)
})

test("listen 完成后：pair-info 指纹与落盘证书一致，qr.png 正常出图", async () => {
  await cold.readyPromise
  const tls = JSON.parse(readFileSync(join(READY_DIR, "tls.json"), "utf8"))

  const pair = await callRoute(pairInfoRoute(cold.registered))
  assert.equal(pair.status, 200)
  assert.match(pair.body?.certFingerprint, /^[0-9a-f]{64}$/)
  assert.equal(pair.body.certFingerprint, tls.fingerprint)
  assert.ok(/^\d{6}$/.test(pair.body?.pairingCode ?? ""))

  const qr = await callRoute(qrRoute(cold.registered))
  assert.equal(qr.status, 200)
  assert.equal(qr.headers?.["content-type"], "image/png")
  assert.ok(qr.raw.length > 0)
})

test("loopback / 同源围栏在就绪后不回退", async () => {
  const nonLoopback = await callRoute(pairInfoRoute(cold.registered), { remoteAddress: "192.168.1.50" })
  assert.equal(nonLoopback.status, 403)
  const crossSite = await callRoute(pairInfoRoute(cold.registered), { headers: { "sec-fetch-site": "cross-site" } })
  assert.equal(crossSite.status, 403)
})

test("TLS 凭据损坏：不假就绪、路由报 phase=failed、无 unhandled rejection、可清理", async () => {
  const dir = join(ROOT_TMP, "corrupt-tls")
  mkdirSync(dir, { recursive: true, mode: 0o700 })
  writeFileSync(join(dir, "tls.json"), JSON.stringify({ key: "not-a-key", cert: "not-a-cert", fingerprint: "a".repeat(64) }))
  const port = await freePort()
  const { ctx, registered, effects } = makeCtx(upstream.address().port)
  const ready = apply(ctx, configFor(dir, port))
  await assert.rejects(() => ready)
  const pair = await callRoute(pairInfoRoute(registered))
  assert.equal(pair.status, 503)
  assert.equal(pair.body?.error, "proxy_not_ready")
  assert.equal(pair.body?.phase, "failed")
  assert.equal(pair.body?.pairingCode, undefined)
  for (const fn of effects) try { fn() } catch (e) { assert.fail(`dispose threw: ${e}`) }
})

test("端口被占用：不假就绪、路由报 phase=failed、可清理", async () => {
  const dir = join(ROOT_TMP, "occupied-port")
  mkdirSync(dir, { recursive: true, mode: 0o700 })
  copyFileSync(join(READY_DIR, "tls.json"), join(dir, "tls.json"))
  const occupier = createServer()
  await new Promise((resolve) => occupier.listen(0, "0.0.0.0", resolve))
  const port = occupier.address().port
  const { ctx, registered, effects } = makeCtx(upstream.address().port)
  const ready = apply(ctx, configFor(dir, port))
  await assert.rejects(() => ready, (err) => err?.code === "EADDRINUSE")
  const pair = await callRoute(pairInfoRoute(registered))
  assert.equal(pair.status, 503)
  assert.equal(pair.body?.phase, "failed")
  for (const fn of effects) try { fn() } catch (e) { assert.fail(`dispose threw: ${e}`) }
  occupier.close()
})

test("已有证书的正常启动：就绪后指纹一致、qr.png 正常", async () => {
  const dir = join(ROOT_TMP, "existing-cert")
  mkdirSync(dir, { recursive: true, mode: 0o700 })
  copyFileSync(join(READY_DIR, "tls.json"), join(dir, "tls.json"))
  const port = await freePort()
  const { ctx, registered, effects } = makeCtx(upstream.address().port)
  await apply(ctx, configFor(dir, port))
  const tls = JSON.parse(readFileSync(join(dir, "tls.json"), "utf8"))
  const pair = await callRoute(pairInfoRoute(registered))
  assert.equal(pair.status, 200)
  assert.equal(pair.body.certFingerprint, tls.fingerprint)
  const qr = await callRoute(qrRoute(registered))
  assert.equal(qr.status, 200)
  for (const fn of effects) try { fn() } catch (e) { assert.fail(`dispose threw: ${e}`) }
})
