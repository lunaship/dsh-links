/**
 * 暂停期间的吊销对账回归。
 *
 * Agent 暂停时不连 Control（relayAgentShouldRun），控制台的吊销没有任何途径
 * 推送过来；面板于是会一直说「仍占用名额」并把自助领取入口藏起来。面板轮询的
 * /dsh-link/relay-status 必须主动探一次，把视图翻成 revoked 并清掉本地凭据。
 *
 * state 隔离：全程只用临时 stateDir 与本地假 Relay，绝不读写
 * ~/.dsh/dsh-links/state.json（见 CLAUDE.md 红线）。
 */
import { test } from "node:test"
import assert from "node:assert/strict"
import { createServer } from "node:http"
import { createServer as createTlsServer } from "node:tls"
import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs"
import { tmpdir } from "node:os"
import { join } from "node:path"
import { apply } from "../src/index.js"
import { loadOrCreateTls } from "../src/tls.js"
import { b64u, generateHostKey } from "../src/relay/crypto.js"

const ROOT_TMP = mkdtempSync(join(tmpdir(), "dsh-relay-paused-probe-"))

const upstream = await new Promise((resolve) => {
  const srv = createServer((req, res) => {
    res.writeHead(200, { "content-type": "text/plain" })
    res.end("upstream-ok")
  })
  srv.listen(0, "127.0.0.1", () => resolve(srv))
})

test.after(() => {
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

function parseJson(s) {
  try { return JSON.parse(s) } catch { return null }
}

function callRoute(route, { headers = {}, remoteAddress = "127.0.0.1", url } = {}) {
  let status = 0, out = ""
  const res = {
    writeHead(c) { status = c },
    end(b) { out = b == null ? "" : Buffer.isBuffer(b) ? b.toString("utf8") : String(b) },
  }
  const req = { method: "GET", headers: { host: `127.0.0.1:${upstream.address().port}`, ...headers } }
  req.url = url || route.path || "/"
  req.socket = { remoteAddress }
  return Promise.resolve(route.handler(req, res)).then(() => ({ status, body: parseJson(out), raw: out }))
}

/** 只回 HELLO / REGISTER 的最小假 Relay；registerReply 决定探测看到什么。 */
async function fakeRelay({ registerReply, onRegister }) {
  const dir = mkdtempSync(join(ROOT_TMP, "tls-"))
  const material = await loadOrCreateTls(dir)
  const server = createTlsServer({ key: material.key, cert: material.cert }, (socket) => {
    socket.write(`${JSON.stringify({ type: "HELLO", challenge: b64u(Buffer.alloc(32, 7)) })}\n`)
    let frames = ""
    socket.on("data", (chunk) => {
      frames += chunk.toString("utf8")
      for (;;) {
        const nl = frames.indexOf("\n")
        if (nl < 0) break
        const frame = JSON.parse(frames.slice(0, nl))
        frames = frames.slice(nl + 1)
        if (frame.type === "REGISTER") {
          onRegister?.()
          socket.write(`${JSON.stringify(registerReply)}\n`)
        }
      }
    })
  })
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve))
  return {
    address: `127.0.0.1:${server.address().port}`,
    fingerprint: material.fingerprint,
    close: () => new Promise((resolve) => server.close(resolve)),
  }
}

/** 暂停中的接入：凭据齐全、inactiveReason 为空。 */
function pausedRelayState(relay, address, fingerprint) {
  const keys = generateHostKey()
  return {
    deviceId: "probe-test-device",
    devices: [],
    pairRequireConfirm: false,
    relay: {
      agentAddress: address,
      clientAddress: address,
      hostSeed: b64u(keys.seed),
      hostPublicKey: b64u(keys.publicKey),
      routeId: b64u(Buffer.alloc(16, 4)),
      routeSecret: b64u(Buffer.alloc(32, 5)),
      capability: "probe-test-capability",
      generation: 1,
      paused: true,
      insecureTls: true,
      tlsFingerprint: fingerprint,
      tlsPinTrusted: true,
      ...relay,
    },
  }
}

async function bootWithState(t, state, stateDir, port) {
  mkdirSync(stateDir, { recursive: true, mode: 0o700 })
  writeFileSync(join(stateDir, "state.json"), JSON.stringify(state, null, 2))
  const { ctx, registered, effects } = makeCtx(upstream.address().port)
  // 必须回收 disposer，否则插件的事件轮询定时器吊住事件循环，测试跑完也不退出。
  t.after(() => { for (const fn of effects) try { fn() } catch {} })
  await apply(ctx, { port, pairingTtlSeconds: 300, autoApprove: true, stateDir, eventPollIntervalMs: 60000 })
  return { registered, effects }
}

async function waitFor(route, predicate, { tries = 60, gapMs = 50 } = {}) {
  for (let i = 0; i < tries; i++) {
    const res = await callRoute(route)
    if (predicate(res.body)) return res
    await new Promise((resolve) => setTimeout(resolve, gapMs))
  }
  return callRoute(route)
}

test("暂停期间被控制台吊销：面板轮询翻成 revoked 并清掉本地凭据", async (t) => {
  let registers = 0
  const spy = await fakeRelay({
    registerReply: { type: "ERROR", code: "REVOKED", message: "revoked" },
    onRegister: () => { registers++ },
  })
  t.after(() => spy.close())
  const stateDir = join(ROOT_TMP, "revoked")
  const port = await freePort()
  const state = pausedRelayState({}, spy.address, spy.fingerprint)
  const { registered } = await bootWithState(t, state, stateDir, port)
  const route = registered.find((r) => r.path === "/dsh-link/relay-status")

  // 探测前：本地凭据还在，视图只能是「暂停但已接入」——正是面板藏起自助领取入口的那一态。
  const first = await callRoute(route)
  assert.equal(first.status, 200)
  assert.equal(first.body?.status, "paused")
  assert.equal(first.body?.enrolled, true)

  const settled = await waitFor(route, (body) => body?.status === "revoked")
  assert.equal(settled.body?.status, "revoked")
  assert.equal(settled.body?.enrolled, false)
  assert.ok(registers >= 1, "应当对 Control 做过一次探测注册")

  const persisted = JSON.parse(readFileSync(join(stateDir, "state.json"), "utf8"))
  assert.equal(persisted.relay.routeId, undefined)
  assert.equal(persisted.relay.routeSecret, undefined)
  assert.equal(persisted.relay.capability, undefined)
  assert.equal(persisted.relay.paused, undefined)
  assert.equal(persisted.relay.inactiveReason, "revoked")
})

test("暂停期间路由仍有效：探测不改本地凭据，视图保持 paused", async (t) => {
  const relay = await fakeRelay({ registerReply: { type: "REGISTERED", generation: 1, heartbeat: 30 } })
  t.after(() => relay.close())
  const stateDir = join(ROOT_TMP, "still-valid")
  const port = await freePort()
  const state = pausedRelayState({}, relay.address, relay.fingerprint)
  const { registered } = await bootWithState(t, state, stateDir, port)
  const route = registered.find((r) => r.path === "/dsh-link/relay-status")

  await callRoute(route)
  await new Promise((resolve) => setTimeout(resolve, 150))
  const after = await callRoute(route)
  assert.equal(after.body?.status, "paused")
  assert.equal(after.body?.enrolled, true)

  const persisted = JSON.parse(readFileSync(join(stateDir, "state.json"), "utf8"))
  assert.equal(persisted.relay.routeId, state.relay.routeId)
  assert.equal(persisted.relay.inactiveReason, undefined)
  assert.equal(persisted.relay.paused, true)
})

test("探测只按间隔触发：连续轮询不会反复注册", async (t) => {
  let registers = 0
  const relay = await fakeRelay({
    registerReply: { type: "REGISTERED", generation: 1, heartbeat: 30 },
    onRegister: () => { registers++ },
  })
  t.after(() => relay.close())
  const stateDir = join(ROOT_TMP, "throttle")
  const port = await freePort()
  const { registered } = await bootWithState(
    t,
    pausedRelayState({}, relay.address, relay.fingerprint),
    stateDir,
    port,
  )
  const route = registered.find((r) => r.path === "/dsh-link/relay-status")
  for (let i = 0; i < 5; i++) {
    await callRoute(route)
    await new Promise((resolve) => setTimeout(resolve, 30))
  }
  assert.equal(registers, 1)
})
