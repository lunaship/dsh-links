import { connect as tlsConnect } from "node:tls"
import { createConnection } from "node:net"
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

const readers = new WeakMap()

function readerFor(socket) {
  let state = readers.get(socket)
  if (state) return state
  state = { buf: Buffer.alloc(0), waiters: [] }
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

function detachReader(socket) {
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
    if (nl < 0) return
    const waiter = state.waiters[0]
    if (nl > waiter.maxBytes) {
      state.waiters.shift()
      waiter.reject(new Error("frame too large"))
      continue
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
  const waiters = state.waiters.splice(0)
  for (const waiter of waiters) waiter.reject(err)
}

function readFrame(socket, maxBytes, timeoutMs) {
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

function connectTls(address, insecureTls) {
  const { host, port } = parseHostPort(address, 8444)
  return new Promise((resolve, reject) => {
    const socket = tlsConnect({
      host,
      port,
      servername: host,
      rejectUnauthorized: !insecureTls,
    })
    socket.setNoDelay(true)
    socket.once("secureConnect", () => resolve(socket))
    socket.once("error", reject)
  })
}

async function readHello(socket) {
  const hello = await readFrame(socket, MAX_HELLO, 5000)
  if (hello?.type !== "HELLO" || !hello.challenge) throw new Error("expected HELLO")
  return unb64u(hello.challenge)
}

export async function enroll({ address, inviteCode, hostId, keys, insecureTls = false }) {
  const socket = await connectTls(address, insecureTls)
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
    const cert = socket.getPeerCertificate()
    const tlsFingerprint = cert?.raw ? certFingerprintSha256(cert.raw) : ""
    return {
      routeId: enrolled.routeId,
      routeSecret: enrolled.routeSecret,
      capability: enrolled.capability,
      generation: Number(enrolled.generation || 1),
      tlsFingerprint,
    }
  } finally {
    socket.destroy()
  }
}

export class RelayAgent {
  constructor({ address, credentials, pluginPort, logger, insecureTls = false }) {
    this.address = address
    this.credentials = credentials
    this.pluginPort = pluginPort
    this.logger = logger
    this.insecureTls = insecureTls
    this.stopped = false
    this.control = null
    this.status = "offline"
    this.error = ""
  }

  stop() {
    this.stopped = true
    try { this.control?.destroy() } catch {}
    this.control = null
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
    const socket = await connectTls(this.address, this.insecureTls)
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
    const ping = setInterval(() => {
      writeFrame(socket, { type: "PING" }).catch(() => {})
    }, 20_000)
    try {
      while (!this.stopped) {
        const frame = await readFrame(socket, MAX_OPEN, 65_000)
        if (frame?.type === "PONG") continue
        if (frame?.type === "ERROR") throw new Error(frame.message || frame.code || "relay error")
        if (frame?.type === "OPEN") {
          this.handleOpen(frame).catch((err) => {
            this.logger?.warn?.(`dsh-links relay: BIND 失败 ${err?.message ?? err}`)
          })
        }
      }
    } finally {
      clearInterval(ping)
      try { socket.destroy() } catch {}
      if (this.control === socket) this.control = null
      if (!this.stopped) {
        this.status = "offline"
      }
    }
  }

  async handleOpen(open) {
    const socket = await connectTls(this.address, this.insecureTls)
    try {
      const challenge = await readHello(socket)
      const nonce = randomBytes(16)
      const ts = Math.floor(Date.now() / 1000)
      const routeId = unb64u(this.credentials.routeId)
      const routeSecret = unb64u(this.credentials.routeSecret)
      const streamId = unb64u(open.stream)
      const generation = Number(open.generation || this.credentials.generation || 1)
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
      await this.bridgeToPlugin(socket)
    } catch (err) {
      try { socket.destroy() } catch {}
      throw err
    }
  }

  bridgeToPlugin(relaySocket) {
    return new Promise((resolve, reject) => {
      const local = createConnection({ host: PLUGIN_HOST, port: this.pluginPort })
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
