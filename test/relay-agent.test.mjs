import assert from "node:assert/strict"
import test from "node:test"
import { createServer as createTlsServer } from "node:tls"
import { mkdtempSync, rmSync } from "node:fs"
import { tmpdir } from "node:os"
import { join } from "node:path"
import { PassThrough } from "node:stream"
import { loadOrCreateTls } from "../src/tls.js"
import { b64u, capabilityExpiry, generateHostKey, parseEnrollText, rememberedRelayExtras, renewTranscript, resolveEnrollText, OFFICIAL_RELAY_HOST, OFFICIAL_RELAY_TLS_SHA256 } from "../src/relay/crypto.js"
import {
  detachReader,
  applyRelayRouteRevoked,
  enroll,
  errorFromRelayFrame,
  isRelayQuotaFrame,
  isRelayRevokedFrame,
  MAX_ACTIVE_STREAMS,
  MAX_CONTROL_WRITE_QUEUE,
  normalizeHeartbeatSeconds,
  normalizeTlsFingerprint,
  readFrame,
  RELAY_PAUSED_MESSAGE,
  RELAY_QUOTA_MESSAGE,
  RELAY_RELEASED_MESSAGE,
  RELAY_REVOKED_MESSAGE,
  RelayAgent,
  relayAgentShouldRun,
  relayPluginView,
  revokeSelf,
} from "../src/relay/agent.js"

test("Relay 帧在换行前超过上限即拒绝并关闭连接", async () => {
  const socket = new PassThrough()
  const frame = readFrame(socket, 8, 1000)
  socket.write(Buffer.alloc(9, 0x61))
  await assert.rejects(frame, /frame too large/)
  assert.equal(socket.destroyed, true)
})

test("合法 READY 帧后的隧道二进制仍完整保留", async () => {
  const socket = new PassThrough()
  const tunnel = Buffer.from([0, 255, 1, 2, 3])
  const frame = readFrame(socket, 64, 1000)
  socket.write(Buffer.concat([Buffer.from('{"type":"READY"}\n'), tunnel]))
  assert.deepEqual(await frame, { type: "READY" })
  assert.deepEqual(detachReader(socket), tunnel)
  socket.destroy()
})

test("自签 TLS 必须提供完整 SHA-256 指纹", async () => {
  assert.throws(() => normalizeTlsFingerprint("abc"), /64 位/)
  await assert.rejects(
    enroll({ address: "127.0.0.1:1", inviteCode: "x", hostId: "h", keys: generateHostKey(), insecureTls: true }),
    /64 位/,
  )
})

test("自签 Relay 指纹不匹配时在发送协议帧前失败", async (t) => {
  const dir = mkdtempSync(join(tmpdir(), "dsh-relay-tls-"))
  t.after(() => rmSync(dir, { recursive: true, force: true }))
  const material = await loadOrCreateTls(dir)
  let protocolBytes = 0
  const server = createTlsServer({ key: material.key, cert: material.cert }, (socket) => {
    socket.on("data", (chunk) => { protocolBytes += chunk.length })
  })
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve))
  t.after(() => server.close())
  await assert.rejects(
    enroll({
      address: `127.0.0.1:${server.address().port}`,
      inviteCode: "x",
      hostId: "h",
      keys: generateHostKey(),
      insecureTls: true,
      tlsFingerprint: "0".repeat(64),
    }),
    /指纹不匹配/,
  )
  assert.equal(protocolBytes, 0)
})

test("REGISTERED heartbeat 被限制在安全范围内", () => {
  assert.equal(normalizeHeartbeatSeconds(0), 20)
  assert.equal(normalizeHeartbeatSeconds("bad"), 20)
  assert.equal(normalizeHeartbeatSeconds(1), 1)
  assert.equal(normalizeHeartbeatSeconds(Number.MAX_SAFE_INTEGER), 300)
})

test("capability 在 7 天窗口内按 REGISTERED heartbeat 续期并回调持久化", async (t) => {
  const dir = mkdtempSync(join(tmpdir(), "dsh-relay-renew-"))
  t.after(() => rmSync(dir, { recursive: true, force: true }))
  const material = await loadOrCreateTls(dir)
  const oldCapability = `h.${b64u(Buffer.from(JSON.stringify({ exp: Math.floor(Date.now() / 1000) + 3600 })))}.s`
  const newCapability = `h.${b64u(Buffer.from(JSON.stringify({ exp: Math.floor(Date.now() / 1000) + 30 * 86400 })))}.s`
  const challenge = Buffer.alloc(32, 7)
  const keys = generateHostKey()
  let frames = ""
  let sawRenew = false
  let resolveRenew
  const renewed = new Promise((resolve) => { resolveRenew = resolve })
  const server = createTlsServer({ key: material.key, cert: material.cert }, (socket) => {
    socket.write(`${JSON.stringify({ type: "HELLO", challenge: b64u(challenge) })}\n`)
    socket.on("data", (chunk) => {
      frames += chunk.toString("utf8")
      for (;;) {
        const nl = frames.indexOf("\n")
        if (nl < 0) break
        const frame = JSON.parse(frames.slice(0, nl))
        frames = frames.slice(nl + 1)
        if (frame.type === "REGISTER") {
          socket.write(`${JSON.stringify({ type: "REGISTERED", generation: 1, heartbeat: 1 })}\n`)
        } else if (frame.type === "RENEW") {
          sawRenew = true
          socket.write(`${JSON.stringify({ type: "RENEWED", capability: newCapability })}\n`)
        } else if (frame.type === "PING") {
          socket.write('{"type":"PONG"}\n')
        }
      }
    })
  })
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve))
  t.after(() => server.close())
  const agent = new RelayAgent({
    address: `127.0.0.1:${server.address().port}`,
    credentials: { keys, capability: oldCapability, generation: 1 },
    pluginPort: 1,
    insecureTls: true,
    tlsFingerprint: material.fingerprint,
    onCapabilityRenewed: (capability) => resolveRenew(capability),
  })
  const loop = agent.registerLoop().catch(() => {})
  assert.equal(await renewed, newCapability)
  await new Promise((resolve) => setImmediate(resolve))
  assert.equal(sawRenew, true)
  assert.equal(capabilityExpiry(agent.credentials.capability), capabilityExpiry(newCapability))
  agent.stop()
  await loop
  assert.deepEqual(renewTranscript(oldCapability, 1, Buffer.alloc(16), challenge).subarray(0, 12), Buffer.from("DLR/1\0RENEW\0"))
})

test("控制连接在 HELLO 前失败也会被清理", async (t) => {
  const dir = mkdtempSync(join(tmpdir(), "dsh-relay-register-"))
  t.after(() => rmSync(dir, { recursive: true, force: true }))
  const material = await loadOrCreateTls(dir)
  let markPeerClosed
  let didPeerClose = false
  const peerClosed = new Promise((resolve) => { markPeerClosed = resolve })
  const server = createTlsServer({ key: material.key, cert: material.cert }, (socket) => {
    socket.on("close", () => {
      didPeerClose = true
      markPeerClosed()
    })
    socket.end('{"type":"BROKEN"}\n')
  })
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve))
  t.after(() => server.close())
  const agent = new RelayAgent({
    address: `127.0.0.1:${server.address().port}`,
    credentials: { keys: generateHostKey(), capability: "x", generation: 1 },
    pluginPort: 1,
    insecureTls: true,
    tlsFingerprint: material.fingerprint,
  })
  await assert.rejects(agent.registerLoop(), /expected HELLO/)
  await Promise.race([peerClosed, new Promise((resolve) => setTimeout(resolve, 500))])
  assert.equal(agent.control, null)
  assert.equal(didPeerClose, true)
})

test("未请求的 PONG 会断开，写队列有硬上限", async (t) => {
  assert.equal(MAX_CONTROL_WRITE_QUEUE, 8)
  const dir = mkdtempSync(join(tmpdir(), "dsh-relay-pong-"))
  t.after(() => rmSync(dir, { recursive: true, force: true }))
  const material = await loadOrCreateTls(dir)
  const server = createTlsServer({ key: material.key, cert: material.cert }, (socket) => {
    socket.write(`${JSON.stringify({ type: "HELLO", challenge: b64u(Buffer.alloc(32, 3)) })}\n`)
    socket.on("data", (chunk) => {
      if (chunk.toString("utf8").includes('"REGISTER"')) {
        socket.write(`${JSON.stringify({ type: "REGISTERED", generation: 1, heartbeat: 20 })}\n`)
        socket.write('{"type":"PONG"}\n')
      }
    })
  })
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve))
  t.after(() => server.close())
  const agent = new RelayAgent({
    address: `127.0.0.1:${server.address().port}`,
    credentials: { keys: generateHostKey(), capability: "x", generation: 1 },
    pluginPort: 1,
    insecureTls: true,
    tlsFingerprint: material.fingerprint,
  })
  await assert.rejects(agent.registerLoop(), /unexpected PONG/)
})

test("OPEN 并发有硬上限、拒绝重复 stream，stop 关闭全部桥接", async () => {
  const agent = new RelayAgent({
    address: "127.0.0.1:1",
    credentials: { generation: 1 },
    pluginPort: 1,
  })
  const resolvers = []
  agent.handleOpen = (_open, record) => new Promise((resolve) => {
    record.relaySocket = new PassThrough()
    record.localSocket = new PassThrough()
    resolvers.push(resolve)
  })
  const ids = Array.from({ length: MAX_ACTIVE_STREAMS + 1 }, (_, i) => b64u(Buffer.alloc(16, i + 1)))
  for (const id of ids.slice(0, MAX_ACTIVE_STREAMS)) assert.equal(agent.acceptOpen({ stream: id, generation: 1 }), true)
  assert.equal(agent.acceptOpen({ stream: ids[0], generation: 1 }), false)
  assert.equal(agent.acceptOpen({ stream: ids.at(-1), generation: 1 }), false)
  const sockets = [...agent.streams.values()].flatMap((record) => [record.relaySocket, record.localSocket])
  agent.stop()
  assert.equal(agent.streams.size, 0)
  assert.ok(sockets.every((socket) => socket.destroyed))
  for (const resolve of resolvers) resolve()
})

test("接入信息 URI 解析主机、邀请码和指纹", () => {
  const fp = "ab".repeat(32)
  const parsed = parseEnrollText(`dsh-relay://relay.dshlinks.com/?i=INVITECODE&fp=${fp}`)
  assert.equal(parsed.address, "relay.dshlinks.com")
  assert.equal(parsed.inviteCode, "INVITECODE")
  assert.equal(parsed.insecureTls, true)
  assert.equal(parsed.tlsFingerprint, fp)
  const publicCa = parseEnrollText("dsh-relay://relay.dshlinks.com/?i=INVITECODE")
  assert.equal(publicCa.insecureTls, false)
  assert.equal(publicCa.tlsFingerprint, "")
  assert.equal(publicCa.controlUrl, "")
  const hosted = parseEnrollText("dsh-relay://relay.example.com/?i=INVITECODE&c=https%3A%2F%2Fcontrol.example.com%2Fpanel")
  assert.equal(hosted.controlUrl, "https://control.example.com/panel")
  assert.equal(parseEnrollText("dsh-relay://relay.example.com/?i=INVITECODE&c=https%3A%2F%2F127.0.0.1%3A8080").controlUrl, "")
  assert.equal(parseEnrollText("INVITECODE"), null)
  const invite = "INVITECODEINVITECODEINVITECODE12"
  const official = resolveEnrollText(invite)
  assert.equal(official.address, OFFICIAL_RELAY_HOST)
  assert.equal(official.inviteCode, invite)
  assert.equal(official.insecureTls, true)
  assert.equal(official.tlsFingerprint, OFFICIAL_RELAY_TLS_SHA256)
  const fromUri = resolveEnrollText(`dsh-relay://relay.dshlinks.com/?i=${invite}&fp=${fp}`)
  assert.equal(fromUri.inviteCode, invite)
  assert.equal(fromUri.tlsFingerprint, fp)
  const publicCaSelf = resolveEnrollText(`dsh-relay://relay.example/?i=${invite}`)
  assert.equal(publicCaSelf.address, "relay.example")
  assert.equal(publicCaSelf.insecureTls, false)
  const remembered = rememberedRelayExtras({
    inactiveReason: "revoked",
    agentAddress: "relay.example:8444",
    insecureTls: true,
    tlsFingerprint: fp,
  })
  const reenroll = resolveEnrollText(invite, remembered)
  assert.equal(reenroll.address, "relay.example:8444")
  assert.equal(reenroll.inviteCode, invite)
  assert.equal(reenroll.tlsFingerprint, fp)
  assert.deepEqual(rememberedRelayExtras({ status: "online", agentAddress: "relay.example:8444" }), {})
  assert.deepEqual(rememberedRelayExtras({ inactiveReason: "revoked", agentAddress: OFFICIAL_RELAY_HOST }), {})
})

test("QUOTA_EXCEEDED 告诉租户去吊销，而不是再签发接入码", () => {
  assert.equal(isRelayQuotaFrame({ type: "ERROR", code: "QUOTA_EXCEEDED", message: "host limit" }), true)
  assert.equal(isRelayQuotaFrame({ type: "ERROR", code: "AUTH_FAILED", message: "enroll failed" }), false)
  const err = errorFromRelayFrame({ type: "ERROR", code: "QUOTA_EXCEEDED", message: "host limit" })
  assert.equal(err.code, "QUOTA_EXCEEDED")
  assert.equal(err.message, RELAY_QUOTA_MESSAGE)
  assert.match(err.message, /仍有效/)
  assert.doesNotMatch(err.message, /签发新接入码/)
  assert.equal(errorFromRelayFrame({ type: "ERROR", code: "AUTH_FAILED", message: "enroll failed" }).message, "enroll failed")
})

test("REVOKED 帧是终态，面板不再当作已接入", () => {
  assert.equal(isRelayRevokedFrame({ type: "ERROR", code: "REVOKED", message: "revoked" }), true)
  assert.equal(isRelayRevokedFrame({ type: "ERROR", code: "AUTH_FAILED", message: "revoked" }), false)
  const err = errorFromRelayFrame({ type: "ERROR", code: "REVOKED", message: "revoked" })
  assert.equal(err.code, "REVOKED")
  assert.equal(err.message, RELAY_REVOKED_MESSAGE)
  assert.deepEqual(relayPluginView({
    agent: { status: "revoked", error: "revoked" },
    routeId: "route",
    routeSecret: "secret",
  }), {
    status: "revoked",
    error: RELAY_REVOKED_MESSAGE,
    enrolled: false,
  })
  assert.deepEqual(relayPluginView({
    inactiveReason: "revoked",
    routeId: undefined,
    routeSecret: undefined,
  }), {
    status: "revoked",
    error: RELAY_REVOKED_MESSAGE,
    enrolled: false,
  })
  assert.match(
    relayPluginView({
      inactiveReason: "revoked",
      controlUrl: "https://control.example.com/panel",
    }).error,
    /打开 https:\/\/control\.example\.com\/panel/,
  )
})

test("RATE_LIMITED 日流量挂起不是终态", () => {
  assert.equal(isRelayRevokedFrame({ type: "ERROR", code: "RATE_LIMITED", message: "daily budget" }), false)
  const err = errorFromRelayFrame({ type: "ERROR", code: "RATE_LIMITED", message: "daily budget" })
  assert.notEqual(err.code, "REVOKED")
  assert.equal(err.message, "daily budget")
})

test("插件断开保留路由凭据，暂停 Agent 而不是作废接入", () => {
  assert.equal(relayPluginView({ controlUrl: "https://control.example.com/panel" }).enrolled, false)
  assert.deepEqual(relayPluginView({
    paused: true,
    routeId: "route",
    routeSecret: "secret",
  }), {
    status: "paused",
    error: RELAY_PAUSED_MESSAGE,
    enrolled: true,
  })
  assert.equal(relayAgentShouldRun({
    routeSecret: "secret",
    agentAddress: "relay.example:8444",
    paused: true,
  }), false)
  assert.equal(relayAgentShouldRun({
    routeSecret: "secret",
    agentAddress: "relay.example:8444",
  }), true)
  assert.equal(relayAgentShouldRun({
    routeSecret: "secret",
    agentAddress: "relay.example:8444",
    inactiveReason: "revoked",
  }), false)
  assert.equal(relayAgentShouldRun({
    routeSecret: "secret",
    agentAddress: "relay.example:8444",
    inactiveReason: "released",
  }), false)
  const pausedRemembered = rememberedRelayExtras({
    paused: true,
    agentAddress: "relay.example:8444",
    insecureTls: true,
    tlsFingerprint: "a".repeat(64),
  })
  assert.equal(pausedRemembered.address, "relay.example:8444")
})

test("同一电脑换新码时旧路由 REVOKED 不得清掉新凭据", () => {
  const relay = { routeId: "new-route", routeSecret: "new-secret", capability: "cap", generation: 2 }
  assert.equal(applyRelayRouteRevoked(relay, "old-route"), false)
  assert.equal(relay.routeId, "new-route")
  assert.equal(relay.routeSecret, "new-secret")
  assert.equal(applyRelayRouteRevoked(relay, "new-route"), true)
  assert.equal(relay.routeId, undefined)
  assert.equal(relay.routeSecret, undefined)
  assert.equal(relay.inactiveReason, "revoked")
  const released = { routeId: "route-2", routeSecret: "secret-2" }
  assert.equal(applyRelayRouteRevoked(released, "route-2", { released: true }), true)
  assert.equal(released.inactiveReason, "released")
  assert.deepEqual(relayPluginView({ inactiveReason: "released" }), {
    status: "revoked",
    error: RELAY_RELEASED_MESSAGE,
    enrolled: false,
  })
  const releasedRemembered = rememberedRelayExtras({
    inactiveReason: "released",
    agentAddress: "relay.example:8444",
  })
  assert.equal(releasedRemembered.address, "relay.example:8444")
  assert.equal(applyRelayRouteRevoked(undefined, "new-route"), false)
})

test("REGISTER 收到 REVOKED 后停止重连并回调", async (t) => {
  const dir = mkdtempSync(join(tmpdir(), "dsh-relay-revoked-"))
  t.after(() => rmSync(dir, { recursive: true, force: true }))
  const material = await loadOrCreateTls(dir)
  const challenge = Buffer.alloc(32, 7)
  let registers = 0
  let frames = ""
  const server = createTlsServer({ key: material.key, cert: material.cert }, (socket) => {
    socket.write(`${JSON.stringify({ type: "HELLO", challenge: b64u(challenge) })}\n`)
    socket.on("data", (chunk) => {
      frames += chunk.toString("utf8")
      for (;;) {
        const nl = frames.indexOf("\n")
        if (nl < 0) break
        const frame = JSON.parse(frames.slice(0, nl))
        frames = frames.slice(nl + 1)
        if (frame.type === "REGISTER") {
          registers++
          socket.write(`${JSON.stringify({ type: "ERROR", code: "REVOKED", message: "revoked" })}\n`)
        }
      }
    })
  })
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve))
  t.after(() => server.close())
  let revokedCalls = 0
  const agent = new RelayAgent({
    address: `127.0.0.1:${server.address().port}`,
    credentials: { keys: generateHostKey(), capability: "x", generation: 1 },
    pluginPort: 1,
    insecureTls: true,
    tlsFingerprint: material.fingerprint,
    onRevoked: async () => { revokedCalls++ },
  })
  await agent.start()
  assert.equal(agent.status, "revoked")
  assert.equal(agent.error, RELAY_REVOKED_MESSAGE)
  assert.equal(agent.stopped, true)
  assert.equal(registers, 1)
  assert.equal(revokedCalls, 1)
})

test("已注册控制连接收到 REVOKED 后也停止重连", async (t) => {
  const dir = mkdtempSync(join(tmpdir(), "dsh-relay-revoked-live-"))
  t.after(() => rmSync(dir, { recursive: true, force: true }))
  const material = await loadOrCreateTls(dir)
  const challenge = Buffer.alloc(32, 3)
  let registers = 0
  let frames = ""
  const server = createTlsServer({ key: material.key, cert: material.cert }, (socket) => {
    socket.write(`${JSON.stringify({ type: "HELLO", challenge: b64u(challenge) })}\n`)
    socket.on("data", (chunk) => {
      frames += chunk.toString("utf8")
      for (;;) {
        const nl = frames.indexOf("\n")
        if (nl < 0) break
        const frame = JSON.parse(frames.slice(0, nl))
        frames = frames.slice(nl + 1)
        if (frame.type === "REGISTER") {
          registers++
          socket.write(`${JSON.stringify({ type: "REGISTERED", generation: 1, heartbeat: 30 })}\n`)
          socket.write(`${JSON.stringify({ type: "ERROR", code: "REVOKED", message: "revoked" })}\n`)
        }
      }
    })
  })
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve))
  t.after(() => server.close())
  const agent = new RelayAgent({
    address: `127.0.0.1:${server.address().port}`,
    credentials: { keys: generateHostKey(), capability: "x", generation: 1 },
    pluginPort: 1,
    insecureTls: true,
    tlsFingerprint: material.fingerprint,
  })
  await agent.start()
  assert.equal(agent.status, "revoked")
  assert.equal(agent.stopped, true)
  assert.equal(registers, 1)
})

test("REVOKE_SELF 成功即释放控制台名额", async (t) => {
  const dir = mkdtempSync(join(tmpdir(), "dsh-relay-release-"))
  t.after(() => rmSync(dir, { recursive: true, force: true }))
  const material = await loadOrCreateTls(dir)
  const challenge = Buffer.alloc(32, 9)
  const keys = generateHostKey()
  const routeId = b64u(Buffer.alloc(16, 4))
  let saw = false
  let frames = ""
  const server = createTlsServer({ key: material.key, cert: material.cert }, (socket) => {
    socket.write(`${JSON.stringify({ type: "HELLO", challenge: b64u(challenge) })}\n`)
    socket.on("data", (chunk) => {
      frames += chunk.toString("utf8")
      for (;;) {
        const nl = frames.indexOf("\n")
        if (nl < 0) break
        const frame = JSON.parse(frames.slice(0, nl))
        frames = frames.slice(nl + 1)
        if (frame.type === "REVOKE_SELF") {
          saw = true
          assert.equal(frame.routeId, routeId)
          socket.write(`${JSON.stringify({ type: "REVOKED" })}\n`)
        }
      }
    })
  })
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve))
  t.after(() => server.close())
  await revokeSelf({
    address: `127.0.0.1:${server.address().port}`,
    routeId,
    keys,
    insecureTls: true,
    tlsFingerprint: material.fingerprint,
  })
  assert.equal(saw, true)
})

test("REVOKE_SELF 被拒绝时接入仍保留", async (t) => {
  const dir = mkdtempSync(join(tmpdir(), "dsh-relay-release-fail-"))
  t.after(() => rmSync(dir, { recursive: true, force: true }))
  const material = await loadOrCreateTls(dir)
  const challenge = Buffer.alloc(32, 5)
  const server = createTlsServer({ key: material.key, cert: material.cert }, (socket) => {
    socket.write(`${JSON.stringify({ type: "HELLO", challenge: b64u(challenge) })}\n`)
    socket.on("data", () => {
      socket.write(`${JSON.stringify({ type: "ERROR", code: "AUTH_FAILED", message: "revoke failed" })}\n`)
    })
  })
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve))
  t.after(() => server.close())
  await assert.rejects(revokeSelf({
    address: `127.0.0.1:${server.address().port}`,
    routeId: b64u(Buffer.alloc(16, 8)),
    keys: generateHostKey(),
    insecureTls: true,
    tlsFingerprint: material.fingerprint,
  }), /revoke failed/)
})
