import { createHash, createHmac, createPrivateKey, generateKeyPairSync, sign as nodeSign } from "node:crypto"

export function b64u(buf) {
  return Buffer.from(buf).toString("base64url")
}

export function unb64u(s) {
  return Buffer.from(String(s), "base64url")
}

export function sha256(buf) {
  return createHash("sha256").update(buf).digest()
}

export function hmacSha256(key, data) {
  return createHmac("sha256", key).update(data).digest()
}

export function generateHostKey() {
  const { publicKey, privateKey } = generateKeyPairSync("ed25519")
  const pubJwk = publicKey.export({ format: "jwk" })
  const privJwk = privateKey.export({ format: "jwk" })
  return {
    seed: Buffer.from(privJwk.d, "base64url"),
    publicKey: Buffer.from(pubJwk.x, "base64url"),
  }
}

export function hostKeyFromSeed(seed, publicKey) {
  return {
    seed: Buffer.from(seed),
    publicKey: Buffer.from(publicKey),
  }
}

function ed25519PrivateKey(seed, publicKey) {
  return createPrivateKey({
    format: "jwk",
    key: {
      kty: "OKP",
      crv: "Ed25519",
      d: Buffer.from(seed).toString("base64url"),
      x: Buffer.from(publicKey).toString("base64url"),
    },
  })
}

export function signEd25519(seed, publicKey, message) {
  return nodeSign(null, Buffer.from(message), ed25519PrivateKey(seed, publicKey))
}

function be16(n) {
  const b = Buffer.alloc(2)
  b.writeUInt16BE(n)
  return b
}

function be64(n) {
  const b = Buffer.alloc(8)
  b.writeBigUInt64BE(BigInt(n))
  return b
}

export function enrollTranscript(inviteCode, hostId, hostPublicKey, ts, nonce, challenge) {
  const host = Buffer.from(String(hostId), "utf8")
  return Buffer.concat([
    Buffer.from("DLR/1"),
    Buffer.from([0]),
    Buffer.from("ENROLL"),
    Buffer.from([0]),
    sha256(Buffer.from(String(inviteCode), "utf8")),
    be16(host.length),
    host,
    Buffer.from(hostPublicKey),
    be64(ts),
    Buffer.from(nonce),
    Buffer.from(challenge),
  ])
}

export function registerTranscript(capability, ts, nonce, challenge) {
  return Buffer.concat([
    Buffer.from("DLR/1"),
    Buffer.from([0]),
    Buffer.from("REGISTER"),
    Buffer.from([0]),
    sha256(Buffer.from(String(capability), "utf8")),
    be64(ts),
    Buffer.from(nonce),
    Buffer.from(challenge),
  ])
}

export function renewTranscript(capability, ts, nonce, challenge) {
  return Buffer.concat([
    Buffer.from("DLR/1"),
    Buffer.from([0]),
    Buffer.from("RENEW"),
    Buffer.from([0]),
    sha256(Buffer.from(String(capability), "utf8")),
    be64(ts),
    Buffer.from(nonce),
    Buffer.from(challenge),
  ])
}

/** Return the signed capability expiry, rejecting malformed JWS payloads. */
export function capabilityExpiry(capability) {
  const parts = String(capability ?? "").split(".")
  if (parts.length !== 3 || !parts[1]) throw new Error("capability 无效")
  let payload
  try {
    payload = JSON.parse(Buffer.from(parts[1], "base64url").toString("utf8"))
  } catch {
    throw new Error("capability 无效")
  }
  const exp = Number(payload?.exp)
  if (!Number.isSafeInteger(exp) || exp <= 0) throw new Error("capability exp 无效")
  return exp
}

export function macTranscript(op, routeId, streamId, generation, ts, nonce, challenge) {
  const parts = [
    Buffer.from("DLR/1"),
    Buffer.from([0]),
    Buffer.from(op),
    Buffer.from([0]),
    Buffer.from(routeId),
  ]
  if (streamId) parts.push(Buffer.from(streamId))
  if (generation != null) parts.push(be64(generation))
  parts.push(be64(ts), Buffer.from(nonce), Buffer.from(challenge))
  return Buffer.concat(parts)
}

export function connectMac(routeSecret, routeId, ts, nonce, challenge) {
  return hmacSha256(routeSecret, macTranscript("CONNECT", routeId, null, null, ts, nonce, challenge))
}

export function bindMac(routeSecret, routeId, streamId, generation, ts, nonce, challenge) {
  return hmacSha256(routeSecret, macTranscript("BIND", routeId, streamId, generation, ts, nonce, challenge))
}

export function parseHostPort(address, defaultPort) {
  const raw = String(address ?? "").trim()
  if (!raw) throw new Error("缺少 Relay 地址")
  if (raw.startsWith("[")) {
    const end = raw.indexOf("]")
    if (end < 0) throw new Error("Relay 地址无效")
    const host = raw.slice(1, end)
    const rest = raw.slice(end + 1)
    const port = rest.startsWith(":") ? Number(rest.slice(1)) : defaultPort
    if (!Number.isInteger(port) || port < 1 || port > 65535) throw new Error("Relay 端口无效")
    return { host, port }
  }
  const idx = raw.lastIndexOf(":")
  if (idx > 0 && !raw.includes("://") && /^[0-9]+$/.test(raw.slice(idx + 1))) {
    const port = Number(raw.slice(idx + 1))
    if (port < 1 || port > 65535) throw new Error("Relay 端口无效")
    return { host: raw.slice(0, idx), port }
  }
  return { host: raw, port: defaultPort }
}

export const DEFAULT_AGENT_PORT = 8444
export const DEFAULT_CLIENT_PORT = 8443
export const OFFICIAL_RELAY_HOST = "relay.dshlinks.com"
export const OFFICIAL_RELAY_TLS_SHA256 = "6fbe09cb8809714ec1c9eec1b982212bdc78e06870abd5ed21442bd4e6d3f9ea"

export function looksLikeInviteCode(raw) {
  return /^[A-Za-z0-9_-]{16,64}$/.test(String(raw ?? "").trim())
}

export function isOfficialRelayHost(address) {
  const raw = String(address ?? "").trim()
  if (!raw) return true
  try {
    const { host } = parseHostPort(raw, DEFAULT_AGENT_PORT)
    return host.toLowerCase() === OFFICIAL_RELAY_HOST
  } catch {
    return false
  }
}

export function officialEnroll(inviteCode) {
  return {
    address: OFFICIAL_RELAY_HOST,
    inviteCode: String(inviteCode ?? "").trim(),
    insecureTls: true,
    tlsFingerprint: OFFICIAL_RELAY_TLS_SHA256,
  }
}

/** Parse a control-console enroll token. Returns null when the text is a plain invite. */
export function parseEnrollText(raw) {
  const text = String(raw ?? "").trim()
  if (!text) return null
  const token = text.split(/\s+/).find((part) => part.startsWith("dsh-relay://")) ?? ""
  if (!token) return null
  let parsed
  try {
    parsed = new URL(token)
  } catch {
    throw new Error("接入信息无效")
  }
  if (parsed.protocol !== "dsh-relay:") throw new Error("接入信息无效")
  const host = parsed.hostname
  if (!host) throw new Error("接入信息缺少主机")
  const invite = String(parsed.searchParams.get("i") || parsed.searchParams.get("invite") || "").trim()
  if (!invite) throw new Error("接入信息缺少接入码")
  const tlsFingerprint = normalizeEnrollFingerprint(parsed.searchParams.get("fp") || "")
  const port = parsed.port
  return {
    address: port ? `${host}:${port}` : host,
    inviteCode: invite,
    insecureTls: Boolean(tlsFingerprint),
    tlsFingerprint,
  }
}

export function resolveEnrollText(raw, extras = {}) {
  const parsed = parseEnrollText(raw)
  if (parsed) {
    if (parsed.insecureTls) return parsed
    if (isOfficialRelayHost(parsed.address)) {
      return { ...officialEnroll(parsed.inviteCode), address: parsed.address }
    }
    return parsed
  }
  const invite = String(raw ?? "").trim()
  if (!looksLikeInviteCode(invite)) return null
  const address = String(extras.address ?? "").trim()
  if (address && !isOfficialRelayHost(address)) {
    return {
      address,
      inviteCode: invite,
      insecureTls: extras.insecureTls === true,
      tlsFingerprint: extras.tlsFingerprint || "",
    }
  }
  return officialEnroll(invite)
}

export function deriveAddresses(input) {
  const { host, port } = parseHostPort(input, DEFAULT_AGENT_PORT)
  const agentPort = port === DEFAULT_CLIENT_PORT ? DEFAULT_AGENT_PORT : port
  const clientPort = agentPort === DEFAULT_AGENT_PORT ? DEFAULT_CLIENT_PORT : agentPort
  return {
    host,
    agentAddress: `${host}:${agentPort}`,
    clientAddress: `${host}:${clientPort}`,
    agentPort,
    clientPort,
  }
}

/** UI 展示用：默认 8444/8443 不带端口；自定义端口仍保留。 */
export function displayRelayHost(address) {
  const raw = String(address ?? "").trim()
  if (!raw) return ""
  try {
    const { host, port } = parseHostPort(raw, DEFAULT_AGENT_PORT)
    if (port === DEFAULT_AGENT_PORT || port === DEFAULT_CLIENT_PORT) return host
    return host.includes(":") ? `[${host}]:${port}` : `${host}:${port}`
  } catch {
    return raw
  }
}

export function certFingerprintSha256(raw) {
  return sha256(raw).toString("hex")
}

function normalizeEnrollFingerprint(value) {
  const raw = String(value ?? "").trim()
  if (!raw) return ""
  if (!/^[0-9a-f:\s]+$/i.test(raw)) throw new Error("自签 TLS 需要 64 位 SHA-256 指纹")
  const normalized = raw.replace(/[:\s]/g, "").toLowerCase()
  if (!/^[0-9a-f]{64}$/.test(normalized)) throw new Error("自签 TLS 需要 64 位 SHA-256 指纹")
  return normalized
}
