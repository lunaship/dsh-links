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

export function revokeSelfTranscript(routeId, ts, nonce, challenge) {
  return Buffer.concat([
    Buffer.from("DLR/1"),
    Buffer.from([0]),
    Buffer.from("REVOKE_SELF"),
    Buffer.from([0]),
    Buffer.from(routeId),
    be64(ts),
    Buffer.from(nonce),
    Buffer.from(challenge),
  ])
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
  const controlUrl = normalizePublicControlURL(parsed.searchParams.get("c") || parsed.searchParams.get("control") || "")
  return {
    address: port ? `${host}:${port}` : host,
    inviteCode: invite,
    insecureTls: Boolean(tlsFingerprint),
    tlsFingerprint,
    controlUrl,
  }
}

export function normalizePublicControlURL(raw) {
  const text = String(raw ?? "").trim()
  if (!text || text.length > 512) return ""
  let parsed
  try {
    parsed = new URL(text)
  } catch {
    return ""
  }
  if (parsed.protocol !== "https:") return ""
  if (parsed.username || parsed.password) return ""
  if (parsed.search || parsed.hash) return ""
  const host = String(parsed.hostname || "").toLowerCase().replace(/^\[|\]$/g, "")
  if (!host) return ""
  if (host === "localhost" || host === "localhost." || host === "::1" || host === "0.0.0.0" || host === "::") return ""
  if (host === "0:0:0:0:0:0:0:0" || host === "0:0:0:0:0:0:0:1") return ""
  if (isIPAddressLoopback(host)) return ""
  const path = String(parsed.pathname || "").replace(/\/+$/, "")
  return path && path !== "/" ? `https://${parsed.host}${path}` : `https://${parsed.host}`
}

function isIPAddressLoopback(host) {
  return host === "127.0.0.1" || host.startsWith("127.")
}

/** Keep a hosted Control URL across same-Relay re-enroll; drop it when switching Relays. Never invent one. */
export function nextRelayControlURL(previousRelay, nextAgentAddress, fromEnroll = {}) {
  const fromPaste = normalizePublicControlURL(fromEnroll?.controlUrl)
  if (fromPaste) return fromPaste
  if (isRelaySwitch(previousRelay?.agentAddress, nextAgentAddress)) return ""
  return normalizePublicControlURL(previousRelay?.controlUrl)
}

/** Persist a hosted Control URL after ENROLL fails (quota, auth). Never stores invites or loopback. */
export function attachRelayControlUrl(relay, controlUrl, nextAgentAddress) {
  const url = normalizePublicControlURL(controlUrl)
  if (!url) return relay
  if (relay && typeof relay === "object" && normalizePublicControlURL(relay.controlUrl) === url) return relay
  const live = Boolean(String(relay?.routeId ?? "").trim() && String(relay?.routeSecret ?? "").trim())
    && relay?.inactiveReason !== "revoked"
    && relay?.inactiveReason !== "released"
  if (live && isRelaySwitch(relay?.agentAddress, nextAgentAddress)) return relay
  return { ...(relay && typeof relay === "object" ? relay : {}), controlUrl: url }
}

export function decorateRelayConsoleMessage(message, controlUrl) {
  const text = String(message ?? "")
  const url = normalizePublicControlURL(controlUrl)
  if (!url) return text
  return text
    .replaceAll("请到控制台签发新接入码后再接入。", `打开 ${url} 签发新接入码后再接入。`)
    .replaceAll("请到控制台新创建一次接入码后再试。", `打开 ${url} 签发新接入码后再试。`)
    .replaceAll("请到控制台吊销不用的电脑", `打开 ${url} 吊销不用的电脑`)
}

export function resolveEnrollText(raw, extras = {}) {
  const parsed = parseEnrollText(raw)
  if (parsed) {
    if (parsed.insecureTls) return parsed
    if (isOfficialRelayHost(parsed.address)) {
      return { ...officialEnroll(parsed.inviteCode), address: parsed.address, controlUrl: parsed.controlUrl }
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

/** After Control revokes a Host or the plugin pauses, a bare invite belongs to the same remembered Relay. */
export function rememberedRelayExtras(relay = {}) {
  const revoked = relay.status === "revoked" || relay.inactiveReason === "revoked" || relay.inactiveReason === "released"
  const paused = relay.status === "paused" || relay.paused === true
  if (!revoked && !paused) return {}
  const address = String(relay.agentAddress ?? "").trim()
  if (!address || isOfficialRelayHost(address)) return {}
  return {
    address,
    insecureTls: relay.insecureTls === true,
    tlsFingerprint: String(relay.tlsFingerprint ?? ""),
  }
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

/** Compare Relays by hostname; agent/client default ports are the same host. */
export function relayIdentityHost(address) {
  const raw = String(address ?? "").trim()
  if (!raw) return ""
  try {
    return parseHostPort(raw, DEFAULT_AGENT_PORT).host.toLowerCase()
  } catch {
    return raw.toLowerCase()
  }
}

export function isRelaySwitch(currentAddress, nextAddress) {
  const current = relayIdentityHost(currentAddress)
  const next = relayIdentityHost(nextAddress)
  if (!current || !next) return false
  return current !== next
}

/** Same-Relay 更换 does not consume a slot. A different hostname still occupies
 *  the old Control until that route is revoked; the plugin tries REVOKE_SELF
 *  after the new enroll succeeds. */
export function relaySwitchConflict(currentAddress, nextAddress, { confirm = false, controlUrl } = {}) {
  if (!isRelaySwitch(currentAddress, nextAddress) || confirm === true) return null
  const previousHost = displayRelayHost(currentAddress) || relayIdentityHost(currentAddress)
  const nextHost = displayRelayHost(nextAddress) || relayIdentityHost(nextAddress)
  const previousControl = normalizePublicControlURL(controlUrl)
  return {
    error: previousControl
      ? `换到 ${nextHost}：插件会尝试从 ${previousHost} 移除这台电脑以空出名额。原 Relay 若已不可达，请打开 ${previousControl} 吊销。确认继续？`
      : `换到 ${nextHost}：插件会尝试从 ${previousHost} 移除这台电脑以空出名额。原 Relay 若已不可达，仍要去原控制台吊销。确认继续？`,
    code: "relay_switch",
    previousHost,
    nextHost,
  }
}

/** Pairing/bootstrap snapshot of the live plugin route. Null if not enrolled or already voided. */
export function relayPairSnapshot(relay) {
  if (!relay || typeof relay !== "object") return null
  const reason = String(relay.inactiveReason ?? "")
  if (reason === "revoked" || reason === "released") return null
  const client = String(relay.clientAddress ?? "").trim()
  const routeId = String(relay.routeId ?? "").trim()
  const routeSecret = String(relay.routeSecret ?? "").trim()
  if (!client || !routeId || !routeSecret) return null
  const snap = { v: 2, client, routeId, routeSecret }
  const tlsFingerprint = String(relay.tlsFingerprint ?? "").trim()
  if (tlsFingerprint) snap.tlsFingerprint = tlsFingerprint
  return snap
}

/** Snapshot used to REVOKE_SELF the previous Control after a hostname switch. */
export function previousRelayReleaseTarget(relay, nextAgentAddress) {
  if (!isRelaySwitch(relay?.agentAddress, nextAgentAddress)) return null
  if (!relay?.routeId || !relay?.hostSeed || !relay?.hostPublicKey || !relay?.agentAddress) return null
  if (relay.inactiveReason === "revoked" || relay.inactiveReason === "released") return null
  return {
    address: String(relay.agentAddress),
    routeId: String(relay.routeId),
    hostSeed: String(relay.hostSeed),
    hostPublicKey: String(relay.hostPublicKey),
    insecureTls: relay.insecureTls === true,
    tlsFingerprint: String(relay.tlsFingerprint ?? ""),
    controlUrl: normalizePublicControlURL(relay.controlUrl),
  }
}

export function rememberReplacedRelayHost(relay, nextAgentAddress) {
  if (isRelaySwitch(relay?.agentAddress, nextAgentAddress)) {
    return displayRelayHost(relay.agentAddress) || relayIdentityHost(relay.agentAddress)
  }
  return String(relay?.replacedRelayHost ?? "").trim()
}

export function rememberReplacedRelayControlURL(relay, nextAgentAddress) {
  if (isRelaySwitch(relay?.agentAddress, nextAgentAddress)) {
    return normalizePublicControlURL(relay?.controlUrl)
  }
  return normalizePublicControlURL(relay?.replacedRelayControlUrl)
}

/** True when enroll will rotate a live route that phones may still be using. */
export function relayRouteRotated(relay) {
  const reason = String(relay?.inactiveReason ?? "")
  if (reason === "revoked" || reason === "released") return false
  return Boolean(String(relay?.routeId ?? "").trim() && String(relay?.routeSecret ?? "").trim())
}

/** Plugin copy after a live route rotates. Phones do not log into Control. */
export function phoneRelayRouteHint({ routeRotated, previousReleased } = {}) {
  if (!routeRotated) return ""
  if (previousReleased === false) {
    return "云端路由已更换，原 Relay 名额可能仍占用。同一网络下的手机下次打开即可跟上；纯远程请重新扫云端配对码。手机不登录控制台。"
  }
  return "云端路由已更换。同一网络下的手机下次打开即可跟上；纯远程请重新扫云端配对码。手机不登录控制台。"
}

/** Cloud QR is for a live plugin route, not Agent heartbeat. Pause/revoke hide it. */
export function showCloudPairQR(relay) {
  if (!relay || typeof relay !== "object") return false
  const status = String(relay.status ?? "")
  if (status === "revoked" || status === "paused") return false
  const reason = String(relay.inactiveReason ?? "")
  if (reason === "revoked" || reason === "released") return false
  if (relay.enrolled === true) return true
  return Boolean(String(relay.routeId ?? "").trim() && String(relay.routeSecret ?? "").trim())
}

export function cloudPairHint(online) {
  return online
    ? "扫这张云端配对码。手机不登录控制台。"
    : "Relay 连上后即可扫这张云端码。同一网络也可先用局域网。手机不登录控制台。"
}

/** Non-secret cache buster so the cloud QR refreshes when the route rotates. */
export function cloudPairStamp(relay) {
  const routeId = String(relay?.routeId ?? "").trim()
  const client = String(relay?.clientAddress ?? "").trim()
  if (!routeId) return ""
  const id = routeId.replace(/[^A-Za-z0-9_-]/g, "").slice(0, 12)
  if (!id) return ""
  const host = client.replace(/[^A-Za-z0-9._:-]/g, "").slice(0, 48)
  return host ? `${host}.${id}` : id
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
