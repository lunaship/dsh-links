import assert from "node:assert/strict"
import test from "node:test"
import { createServer as createTlsServer } from "node:tls"
import { mkdtempSync, rmSync } from "node:fs"
import { tmpdir } from "node:os"
import { join } from "node:path"
import { PassThrough } from "node:stream"
import { loadOrCreateTls } from "../src/tls.js"
import { b64u, generateHostKey, parseEnrollText } from "../src/relay/crypto.js"
import {
  detachReader,
  enroll,
  MAX_ACTIVE_STREAMS,
  normalizeTlsFingerprint,
  readFrame,
  RelayAgent,
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
  assert.equal(parseEnrollText("INVITECODE"), null)
})
