import { connect as tlsConnect } from "node:tls"
import { createConnection, isIP } from "node:net"
import { randomBytes } from "node:crypto"
import {
  b64u,
  unb64u,
  enrollTranscript,
  registerTranscript,
  renewTranscript,
  revokeSelfTranscript,
  capabilityExpiry,
  bindMac,
  signEd25519,
  certFingerprintSha256,
  parseHostPort,
  decorateRelayConsoleMessage,
} from "./crypto.js"

const MAX_HELLO = 768
const MAX_ENROLLED = 2048
const MAX_REGISTERED = 768
const MAX_OPEN = 2048
const PLUGIN_HOST = "127.0.0.1"
const CONNECT_TIMEOUT_MS = 8000
export const MAX_ACTIVE_STREAMS = 8
export const CAPABILITY_RENEW_WINDOW_SECONDS = 7 * 24 * 60 * 60
export const REGISTERED_HEARTBEAT_MIN_SECONDS = 1
export const REGISTERED_HEARTBEAT_MAX_SECONDS = 300
export const MAX_CONTROL_WRITE_QUEUE = 8
export const MAX_CONTROL_WRITE_BYTES = 16 * 1024
export const CONTROL_WRITE_DEADLINE_MS = 8_000
export const CONTROL_WRITE_HIGH_WATER_BYTES = 32 * 1024
export const RELAY_REVOKED_MESSAGE = "接入已被控制台吊销。请到控制台签发新接入码后再接入。"
export const RELAY_RELEASED_MESSAGE = "已从控制台移除这台电脑。名额已空出。请签发新接入码后再接入。"
export const RELAY_PAUSED_MESSAGE = "已断开。点重新连接即可，不用新接入码。名额仍占用；要空出名额请点「释放名额」。"
export const RELAY_QUOTA_MESSAGE = "接入名额已满。请到控制台吊销不用的电脑，或在已接入的电脑插件里点「释放名额」。这张接入码仍有效，吊销后不用重新签发，再点接入即可。"

export function isRelayRevokedFrame(frame) {
  return frame?.type === "ERROR" && String(frame.code ?? "") === "REVOKED"
}

export function isRelayQuotaFrame(frame) {
  return frame?.type === "ERROR" && String(frame.code ?? "") === "QUOTA_EXCEEDED"
}

export function errorFromRelayFrame(frame, fallback = "relay error") {
  if (isRelayRevokedFrame(frame)) {
    const err = new Error(RELAY_REVOKED_MESSAGE)
    err.code = "REVOKED"
    return err
  }
  if (isRelayQuotaFrame(frame)) {
    const err = new Error(RELAY_QUOTA_MESSAGE)
    err.code = "QUOTA_EXCEEDED"
    return err
  }
  return new Error(frame?.message || frame?.code || fallback)
}

export function relayPluginView({ agent, routeId, routeSecret, inactiveReason, paused, controlUrl } = {}) {
  const released = inactiveReason === "released"
  const revoked = agent?.status === "revoked" || inactiveReason === "revoked" || released
  if (revoked) {
    return {
      status: "revoked",
      error: decorateRelayConsoleMessage(released ? RELAY_RELEASED_MESSAGE : RELAY_REVOKED_MESSAGE, controlUrl),
      enrolled: false,
    }
  }
  const enrolled = Boolean(routeId && routeSecret)
  if (paused && enrolled) {
    return { status: "paused", error: RELAY_PAUSED_MESSAGE, enrolled: true }
  }
  return {
    status: agent?.status ?? (routeId ? "offline" : "idle"),
    error: decorateRelayConsoleMessage(agent?.error ?? "", controlUrl),
    enrolled,
  }
}

export function relayAgentShouldRun(relay = {}) {
  if (!relay?.routeSecret || !relay?.agentAddress) return false
  if (relay.inactiveReason === "revoked" || relay.inactiveReason === "released") return false
  if (relay.paused) return false
  return true
}

// Drop route creds only when Control revoked THIS route. A same-host re-enroll
// (更换) revokes the old route while new creds are already in state; wiping
// blindly would strand the tenant with a live Control slot and no plugin creds.
export function applyRelayRouteRevoked(relay, revokedRouteId, { released = false } = {}) {
  if (!relay || !revokedRouteId || relay.routeId !== revokedRouteId) return false
  delete relay.routeId
  delete relay.routeSecret
  delete relay.capability
  delete relay.generation
  delete relay.paused
  relay.inactiveReason = released ? "released" : "revoked"
  return true
}

export function normalizeHeartbeatSeconds(value) {
  const seconds = Number(value)
  if (!Number.isSafeInteger(seconds) || seconds <= 0) return 20
  return Math.min(
    Math.max(seconds, REGISTERED_HEARTBEAT_MIN_SECONDS),
    REGISTERED_HEARTBEAT_MAX_SECONDS,
  )
}

const readers = new WeakMap()

function readerFor(socket) {
  let state = readers.get(socket)
  if (state) return state
  state = { socket, buf: Buffer.alloc(0), waiters: [] }
  state.onData = (chunk) => {
    state.buf = Buffer.concat([state.buf, chunk])
    flushReader(state)
  }
  state.onError = (err) => failReader(state, err)
  state.onClose = () => failReader(state, new Error("connection closed"))
  readers.set(socket, state)
  socket.on("data", state.onData)
  socket.on("error", state.onError)
  socket.on("close", state.onClose)
  return state
}

export function detachReader(socket) {
  const state = readers.get(socket)
  if (!state) return Buffer.alloc(0)
  readers.delete(socket)
  socket.off("data", state.onData)
  socket.off("error", state.onError)
  socket.off("close", state.onClose)
  return state.buf
}

function flushReader(state) {
  while (state.waiters.length) {
    const nl = state.buf.indexOf(10)
    const waiter = state.waiters[0]
    if (nl < 0) {
      if (state.buf.length > waiter.maxBytes) {
        state.buf = Buffer.alloc(0)
        failReader(state, new Error("frame too large"))
        state.socket.destroy()
      }
      return
    }
    if (nl > waiter.maxBytes) {
      state.buf = Buffer.alloc(0)
      failReader(state, new Error("frame too large"))
      state.socket.destroy()
      return
    }
    const line = state.buf.subarray(0, nl)
    state.buf = state.buf.subarray(nl + 1)
    state.waiters.shift()
    try {
      waiter.resolve(JSON.parse(line.toString("utf8")))
    } catch {
      waiter.reject(new Error("invalid frame json"))
    }
  }
}

function failReader(state, err) {
  state.buf = Buffer.alloc(0)
  const waiters = state.waiters.splice(0)
  for (const waiter of waiters) waiter.reject(err)
}

export function readFrame(socket, maxBytes, timeoutMs) {
  const state = readerFor(socket)
  return new Promise((resolve, reject) => {
    const waiter = {
      maxBytes,
      resolve: (v) => {
        clearTimeout(timer)
        resolve(v)
      },
      reject: (err) => {
        clearTimeout(timer)
        reject(err)
      },
    }
    const timer = setTimeout(() => {
      state.waiters = state.waiters.filter((item) => item !== waiter)
      state.buf = Buffer.alloc(0)
      socket.destroy()
      reject(new Error("frame timeout"))
    }, timeoutMs)
    state.waiters.push(waiter)
    flushReader(state)
  })
}

function writeFrame(socket, obj, deadlineMs = CONTROL_WRITE_DEADLINE_MS) {
  return new Promise((resolve, reject) => {
    const line = Buffer.from(`${JSON.stringify(obj)}\n`, "utf8")
    const timer = setTimeout(() => {
      socket.destroy()
      reject(new Error("control write timeout"))
    }, deadlineMs)
    socket.write(line, (err) => {
      clearTimeout(timer)
      if (err) reject(err)
      else resolve()
    })
  })
}

function controlWritableLength(socket) {
  return Number(socket?.writableLength) || 0
}

export function normalizeTlsFingerprint(value) {
  const raw = String(value ?? "").trim()
  if (!/^[0-9a-f:\s]+$/i.test(raw)) throw new Error("自签 TLS 需要 64 位 SHA-256 指纹")
  const normalized = raw.replace(/[:\s]/g, "").toLowerCase()
  if (!/^[0-9a-f]{64}$/.test(normalized)) throw new Error("自签 TLS 需要 64 位 SHA-256 指纹")
  return normalized
}

function exactB64u(value, bytes, label) {
  if (typeof value !== "string" || !/^[A-Za-z0-9_-]+$/.test(value)) throw new Error(`${label} 无效`)
  const decoded = unb64u(value)
  if (decoded.length !== bytes || b64u(decoded) !== value) throw new Error(`${label} 无效`)
  return decoded
}

function validateGeneration(value) {
  const generation = Number(value)
  if (!Number.isSafeInteger(generation) || generation < 1) throw new Error("generation 无效")
  return generation
}

function connectTls(address, { insecureTls = false, tlsFingerprint = "", signal } = {}) {
  const { host, port } = parseHostPort(address, 8444)
  const expectedFingerprint = insecureTls ? normalizeTlsFingerprint(tlsFingerprint) : ""
  return new Promise((resolve, reject) => {
    let settled = false
    const socket = tlsConnect({
      host,
      port,
      servername: isIP(host) ? undefined : host,
      rejectUnauthorized: !insecureTls,
    })
    const finish = (err) => {
      if (settled) return
      settled = true
      clearTimeout(timer)
      signal?.removeEventListener("abort", onAbort)
      socket.off("error", onError)
      if (err) {
        socket.destroy()
        reject(err)
      } else {
        resolve(socket)
      }
    }
    const onAbort = () => finish(new Error("connection aborted"))
    const onError = (err) => finish(err)
    const timer = setTimeout(() => finish(new Error("TLS connection timeout")), CONNECT_TIMEOUT_MS)
    socket.setNoDelay(true)
    socket.once("secureConnect", () => {
      if (insecureTls) {
        const cert = socket.getPeerCertificate()
        const actual = cert?.raw ? certFingerprintSha256(cert.raw).toLowerCase() : ""
        if (actual !== expectedFingerprint) return finish(new Error("Relay TLS 指纹不匹配"))
      }
      finish()
    })
    socket.once("error", onError)
    if (signal?.aborted) onAbort()
    else signal?.addEventListener("abort", onAbort, { once: true })
  })
}

async function readHello(socket) {
  const hello = await readFrame(socket, MAX_HELLO, 5000)
  if (hello?.type !== "HELLO" || !hello.challenge) throw new Error("expected HELLO")
  return exactB64u(hello.challenge, 32, "HELLO challenge")
}

export async function enroll({ address, inviteCode, hostId, keys, insecureTls = false, tlsFingerprint = "", hostName = "" }) {
  const socket = await connectTls(address, { insecureTls, tlsFingerprint })
  try {
    const challenge = await readHello(socket)
    const nonce = randomBytes(16)
    const ts = Math.floor(Date.now() / 1000)
    const transcript = enrollTranscript(inviteCode, hostId, keys.publicKey, ts, nonce, challenge)
    const proof = signEd25519(keys.seed, keys.publicKey, transcript)
    const frame = {
      type: "ENROLL",
      inviteCode,
      hostId,
      hostPublicKey: b64u(keys.publicKey),
      ts,
      nonce: b64u(nonce),
      proof: b64u(proof),
    }
    const label = String(hostName ?? "").trim()
    if (label) frame.hostName = label
    await writeFrame(socket, frame)
    const enrolled = await readFrame(socket, MAX_ENROLLED, 8000)
    if (enrolled?.type === "ERROR") throw errorFromRelayFrame(enrolled, "enroll failed")
    if (enrolled?.type !== "ENROLLED") throw new Error("expected ENROLLED")
    exactB64u(enrolled.routeId, 16, "routeId")
    exactB64u(enrolled.routeSecret, 32, "routeSecret")
    if (typeof enrolled.capability !== "string" || !enrolled.capability || enrolled.capability.length > 4096) {
      throw new Error("capability 无效")
    }
    const generation = validateGeneration(enrolled.generation)
    const cert = socket.getPeerCertificate()
    const tlsFingerprint = cert?.raw ? certFingerprintSha256(cert.raw) : ""
    return {
      routeId: enrolled.routeId,
      routeSecret: enrolled.routeSecret,
      capability: enrolled.capability,
      generation,
      tlsFingerprint,
    }
  } finally {
    socket.destroy()
  }
}

const MAX_REVOKED = 512

export async function revokeSelf({ address, routeId, keys, insecureTls = false, tlsFingerprint = "" }) {
  const route = exactB64u(routeId, 16, "routeId")
  const socket = await connectTls(address, { insecureTls, tlsFingerprint })
  try {
    const challenge = await readHello(socket)
    const nonce = randomBytes(16)
    const ts = Math.floor(Date.now() / 1000)
    const transcript = revokeSelfTranscript(route, ts, nonce, challenge)
    const proof = signEd25519(keys.seed, keys.publicKey, transcript)
    await writeFrame(socket, {
      type: "REVOKE_SELF",
      routeId,
      ts,
      nonce: b64u(nonce),
      proof: b64u(proof),
    })
    const reply = await readFrame(socket, MAX_REVOKED, 8000)
    if (reply?.type === "ERROR") throw errorFromRelayFrame(reply, "revoke failed")
    if (reply?.type !== "REVOKED") throw new Error("expected REVOKED")
  } finally {
    socket.destroy()
  }
}

export class RelayAgent {
  constructor({ address, credentials, pluginPort, logger, onCapabilityRenewed, onRevoked, insecureTls = false, tlsFingerprint = "" }) {
    this.address = address
    this.credentials = credentials
    this.pluginPort = pluginPort
    this.logger = logger
    this.onCapabilityRenewed = onCapabilityRenewed
    this.onRevoked = onRevoked
    this.insecureTls = insecureTls
    this.tlsFingerprint = tlsFingerprint
    this.stopped = false
    this.control = null
    this.controlAbort = null
    this.streams = new Map()
    this.status = "offline"
    this.error = ""
  }

  stop() {
    this.stopped = true
    this.controlAbort?.abort()
    this.controlAbort = null
    try { this.control?.destroy() } catch {}
    this.control = null
    for (const record of this.streams.values()) {
      record.abort.abort()
      try { record.relaySocket?.destroy() } catch {}
      try { record.localSocket?.destroy() } catch {}
    }
    this.streams.clear()
    if (this.status !== "revoked") this.status = "offline"
  }

  async start() {
    this.stopped = false
    while (!this.stopped) {
      try {
        await this.registerLoop()
      } catch (err) {
        const revoked = err?.code === "REVOKED"
        this.status = revoked ? "revoked" : "error"
        this.error = revoked ? RELAY_REVOKED_MESSAGE : (err?.message ?? String(err))
        this.logger?.warn?.(`dsh-links relay: ${this.error}`)
        if (revoked) {
          this.stopped = true
          try { await this.onRevoked?.() } catch (cbErr) {
            this.logger?.warn?.(`dsh-links relay: ${cbErr?.message ?? cbErr}`)
          }
          return
        }
      }
      if (this.stopped) return
      await new Promise((r) => setTimeout(r, 3000))
    }
  }

  async registerLoop() {
    const abort = new AbortController()
    this.controlAbort = abort
    let socket = null
    let ping = null
    let renewInFlight = false
    let writeChain = Promise.resolve()
    let queuedFrames = 0
    let queuedBytes = 0
    let pingQueued = false
    let expectedPongs = 0
    try {
      socket = await connectTls(this.address, {
        insecureTls: this.insecureTls,
        tlsFingerprint: this.tlsFingerprint,
        signal: abort.signal,
      })
      this.control = socket
      const challenge = await readHello(socket)
      const nonce = randomBytes(16)
      const ts = Math.floor(Date.now() / 1000)
      const { capability, keys } = this.credentials
      const transcript = registerTranscript(capability, ts, nonce, challenge)
      const proof = signEd25519(keys.seed, keys.publicKey, transcript)
      await writeFrame(socket, {
        type: "REGISTER",
        capability,
        ts,
        nonce: b64u(nonce),
        proof: b64u(proof),
      })
      const registered = await readFrame(socket, MAX_REGISTERED, 8000)
      if (registered?.type === "ERROR") throw errorFromRelayFrame(registered, "register failed")
      if (registered?.type !== "REGISTERED") throw new Error("expected REGISTERED")
      const heartbeatMs = normalizeHeartbeatSeconds(registered.heartbeat) * 1000
      this.status = "online"
      this.error = ""
      this.logger?.info?.("dsh-links relay: Agent 已注册")
      const failWrite = (reason) => {
        socket.destroy()
        return Promise.reject(new Error(reason))
      }
      const enqueue = (frame, { coalescePing = false } = {}) => {
        if (coalescePing && pingQueued) return writeChain
        const lineBytes = Buffer.byteLength(`${JSON.stringify(frame)}\n`, "utf8")
        if (queuedFrames >= MAX_CONTROL_WRITE_QUEUE || queuedBytes + lineBytes > MAX_CONTROL_WRITE_BYTES) {
          return failWrite("control write queue overflow")
        }
        if (controlWritableLength(socket) > CONTROL_WRITE_HIGH_WATER_BYTES) {
          return failWrite("control write backpressure")
        }
        if (coalescePing) pingQueued = true
        queuedFrames++
        queuedBytes += lineBytes
        writeChain = writeChain
          .then(() => {
            if (socket.destroyed) throw new Error("connection closed")
            if (controlWritableLength(socket) > CONTROL_WRITE_HIGH_WATER_BYTES) {
              throw new Error("control write backpressure")
            }
            return writeFrame(socket, frame)
          })
          .then(() => {
            if (frame.type === "PING") expectedPongs++
          })
          .finally(() => {
            queuedFrames = Math.max(0, queuedFrames - 1)
            queuedBytes = Math.max(0, queuedBytes - lineBytes)
            if (coalescePing) pingQueued = false
          })
        return writeChain.catch((err) => {
          socket.destroy()
          throw err
        })
      }
      const maybeRenew = () => {
        if (renewInFlight) return
        let exp
        try { exp = capabilityExpiry(this.credentials.capability) } catch (err) {
          this.logger?.warn?.(`dsh-links relay: capability 续期跳过 ${err?.message ?? err}`)
          return
        }
        if (exp - Math.floor(Date.now() / 1000) > CAPABILITY_RENEW_WINDOW_SECONDS) return
        const nonce = randomBytes(16)
        const ts = Math.floor(Date.now() / 1000)
        const proof = signEd25519(
          this.credentials.keys.seed,
          this.credentials.keys.publicKey,
          renewTranscript(this.credentials.capability, ts, nonce, challenge),
        )
        renewInFlight = true
        enqueue({ type: "RENEW", ts, nonce: b64u(nonce), proof: b64u(proof) }).catch(() => {
          renewInFlight = false
        })
      }
      const tick = () => {
        enqueue({ type: "PING" }, { coalescePing: true }).catch(() => {})
        maybeRenew()
      }
      ping = setInterval(tick, heartbeatMs)
      // Do not wait for the first heartbeat when the persisted capability is already inside the window.
      maybeRenew()
      while (!this.stopped) {
        const frame = await readFrame(socket, MAX_OPEN, 65_000)
        if (frame?.type === "PONG") {
          if (expectedPongs < 1) throw new Error("unexpected PONG")
          expectedPongs--
          continue
        }
        if (frame?.type === "RENEWED") {
          if (typeof frame.capability !== "string" || frame.capability.length > 4096) {
            throw new Error("续期 capability 无效")
          }
          capabilityExpiry(frame.capability)
          await this.onCapabilityRenewed?.(frame.capability)
          this.credentials.capability = frame.capability
          renewInFlight = false
          this.logger?.info?.("dsh-links relay: Agent capability 已续期")
          continue
        }
        if (frame?.type === "ERROR") throw errorFromRelayFrame(frame, "relay error")
        if (frame?.type === "OPEN") {
          this.acceptOpen(frame)
        }
      }
    } finally {
      if (ping) clearInterval(ping)
      try { socket?.destroy() } catch {}
      if (this.control === socket) this.control = null
      if (this.controlAbort === abort) this.controlAbort = null
      if (!this.stopped) {
        this.status = "offline"
      }
    }
  }

  acceptOpen(open) {
    let streamId
    try {
      exactB64u(open?.stream, 16, "stream")
      streamId = open.stream
      const generation = validateGeneration(open?.generation)
      if (generation !== validateGeneration(this.credentials.generation)) throw new Error("generation 不匹配")
      if (this.streams.has(streamId)) throw new Error("重复 stream")
      if (this.streams.size >= MAX_ACTIVE_STREAMS) throw new Error("活动 stream 已达上限")
    } catch (err) {
      this.logger?.warn?.(`dsh-links relay: 拒绝 OPEN ${err?.message ?? err}`)
      return false
    }
    const record = { abort: new AbortController(), relaySocket: null, localSocket: null }
    this.streams.set(streamId, record)
    record.promise = this.handleOpen(open, record)
      .catch((err) => this.logger?.warn?.(`dsh-links relay: BIND 失败 ${err?.message ?? err}`))
      .finally(() => this.streams.delete(streamId))
    return true
  }

  async handleOpen(open, record = { abort: new AbortController() }) {
    const socket = await connectTls(this.address, {
      insecureTls: this.insecureTls,
      tlsFingerprint: this.tlsFingerprint,
      signal: record.abort.signal,
    })
    record.relaySocket = socket
    try {
      const challenge = await readHello(socket)
      const nonce = randomBytes(16)
      const ts = Math.floor(Date.now() / 1000)
      const routeId = unb64u(this.credentials.routeId)
      const routeSecret = unb64u(this.credentials.routeSecret)
      const streamId = exactB64u(open.stream, 16, "stream")
      const generation = validateGeneration(open.generation)
      if (generation !== validateGeneration(this.credentials.generation)) throw new Error("generation 不匹配")
      const mac = bindMac(routeSecret, routeId, streamId, generation, ts, nonce, challenge)
      await writeFrame(socket, {
        type: "BIND",
        route: this.credentials.routeId,
        stream: open.stream,
        generation,
        ts,
        nonce: b64u(nonce),
        mac: b64u(mac),
      })
      const ready = await readFrame(socket, MAX_HELLO, 10_000)
      if (ready?.type === "ERROR") throw errorFromRelayFrame(ready, "bind failed")
      if (ready?.type !== "READY") throw new Error("expected READY")
      const leftover = detachReader(socket)
      if (leftover.length) socket.unshift(leftover)
      await this.bridgeToPlugin(socket, record)
    } catch (err) {
      try { socket.destroy() } catch {}
      throw err
    }
  }

  bridgeToPlugin(relaySocket, record = {}) {
    return new Promise((resolve, reject) => {
      const local = createConnection({ host: PLUGIN_HOST, port: this.pluginPort })
      record.localSocket = local
      const fail = (err) => {
        try { relaySocket.destroy() } catch {}
        try { local.destroy() } catch {}
        reject(err)
      }
      local.once("error", fail)
      relaySocket.once("error", fail)
      local.once("connect", () => {
        relaySocket.pipe(local)
        local.pipe(relaySocket)
        const done = () => {
          try { relaySocket.destroy() } catch {}
          try { local.destroy() } catch {}
          resolve()
        }
        relaySocket.once("close", done)
        local.once("close", done)
      })
    })
  }
}
