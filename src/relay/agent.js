import { connect as tlsConnect } from "node:tls"
import { createConnection, isIP } from "node:net"
import { randomBytes } from "node:crypto"
import {
  b64u,
  unb64u,
  enrollTranscript,
  registerTranscript,
  bindMac,
  signEd25519,
  certFingerprintSha256,
  parseHostPort,
} from "./crypto.js"

const MAX_HELLO = 768
const MAX_ENROLLED = 2048
const MAX_REGISTERED = 768
const MAX_OPEN = 2048
const PLUGIN_HOST = "127.0.0.1"
const CONNECT_TIMEOUT_MS = 8000
export const MAX_ACTIVE_STREAMS = 8

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

function writeFrame(socket, obj) {
  return new Promise((resolve, reject) => {
    const line = Buffer.from(`${JSON.stringify(obj)}\n`, "utf8")
    socket.write(line, (err) => (err ? reject(err) : resolve()))
  })
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

export async function enroll({ address, inviteCode, hostId, keys, insecureTls = false, tlsFingerprint = "" }) {
  const socket = await connectTls(address, { insecureTls, tlsFingerprint })
  try {
    const challenge = await readHello(socket)
    const nonce = randomBytes(16)
    const ts = Math.floor(Date.now() / 1000)
    const transcript = enrollTranscript(inviteCode, hostId, keys.publicKey, ts, nonce, challenge)
    const proof = signEd25519(keys.seed, keys.publicKey, transcript)
    await writeFrame(socket, {
      type: "ENROLL",
      inviteCode,
      hostId,
      hostPublicKey: b64u(keys.publicKey),
      ts,
      nonce: b64u(nonce),
      proof: b64u(proof),
    })
    const enrolled = await readFrame(socket, MAX_ENROLLED, 8000)
    if (enrolled?.type === "ERROR") throw new Error(enrolled.message || enrolled.code || "enroll failed")
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

export class RelayAgent {
  constructor({ address, credentials, pluginPort, logger, insecureTls = false, tlsFingerprint = "" }) {
    this.address = address
    this.credentials = credentials
    this.pluginPort = pluginPort
    this.logger = logger
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
    this.status = "offline"
  }

  async start() {
    this.stopped = false
    while (!this.stopped) {
      try {
        await this.registerLoop()
      } catch (err) {
        this.status = "error"
        this.error = err?.message ?? String(err)
        this.logger?.warn?.(`dsh-links relay: ${this.error}`)
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
      if (registered?.type === "ERROR") throw new Error(registered.message || registered.code || "register failed")
      if (registered?.type !== "REGISTERED") throw new Error("expected REGISTERED")
      this.status = "online"
      this.error = ""
      this.logger?.info?.("dsh-links relay: Agent 已注册")
      ping = setInterval(() => {
        writeFrame(socket, { type: "PING" }).catch(() => {})
      }, 20_000)
      while (!this.stopped) {
        const frame = await readFrame(socket, MAX_OPEN, 65_000)
        if (frame?.type === "PONG") continue
        if (frame?.type === "ERROR") throw new Error(frame.message || frame.code || "relay error")
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
      if (ready?.type === "ERROR") throw new Error(ready.message || ready.code || "bind failed")
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
