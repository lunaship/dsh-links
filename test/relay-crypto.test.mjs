import assert from "node:assert/strict"
import { readFile } from "node:fs/promises"
import test from "node:test"
import {
  b64u,
  unb64u,
  enrollTranscript,
  registerTranscript,
  revokeSelfTranscript,
  connectMac,
  bindMac,
  signEd25519,
  deriveAddresses,
  displayRelayHost,
  isRelaySwitch,
  rememberReplacedRelayHost,
  previousRelayReleaseTarget,
  relayPairSnapshot,
  relaySwitchConflict,
  normalizePublicControlURL,
  nextRelayControlURL,
  attachRelayControlUrl,
  decorateRelayConsoleMessage,
  rememberReplacedRelayControlURL,
  relayRouteRotated,
  phoneRelayRouteHint,
  showCloudPairQR,
  cloudPairHint,
  cloudPairStamp,
} from "../src/relay/crypto.js"
import { RELAY_QUOTA_MESSAGE } from "../src/relay/agent.js"

const vectors = JSON.parse(await readFile(new URL("../testdata/dlr1-vectors.json", import.meta.url), "utf8"))
const relayVectors = JSON.parse(await readFile(new URL("../relay/testdata/dlr1-vectors.json", import.meta.url), "utf8"))

test("插件 testdata 与 relay testdata 的 DLR/1 向量镜像一致", () => {
  assert.deepEqual(vectors, relayVectors)
})

test("ENROLL proof matches DLR/1 vectors", () => {
  const e = vectors.enroll
  const transcript = enrollTranscript(e.inviteCode, e.hostId, unb64u(e.hostPublicKey), Number(e.ts), unb64u(e.nonce), unb64u(e.challenge))
  const proof = signEd25519(unb64u(e.hostSeed), unb64u(e.hostPublicKey), transcript)
  assert.equal(b64u(proof), e.proof)
})

test("REGISTER proof matches DLR/1 vectors", () => {
  const r = vectors.register
  const e = vectors.enroll
  const transcript = registerTranscript(r.capability, Number(r.ts), unb64u(r.nonce), unb64u(e.challenge))
  const proof = signEd25519(unb64u(e.hostSeed), unb64u(e.hostPublicKey), transcript)
  assert.equal(b64u(proof), r.proof)
})

test("REVOKE_SELF transcript 使用裸 routeId", () => {
  const routeId = Buffer.alloc(16, 1)
  const nonce = Buffer.alloc(16, 2)
  const challenge = Buffer.alloc(32, 3)
  const got = revokeSelfTranscript(routeId, 42, nonce, challenge)
  const prefix = Buffer.concat([Buffer.from("DLR/1"), Buffer.from([0]), Buffer.from("REVOKE_SELF"), Buffer.from([0])])
  assert.equal(got.subarray(0, prefix.length).equals(prefix), true)
  assert.equal(got.subarray(prefix.length, prefix.length + 16).equals(routeId), true)
})

test("CONNECT/BIND MAC matches DLR/1 vectors", () => {
  const secret = unb64u(vectors.hkdf.routeSecret)
  const c = vectors.hmac.connect
  assert.equal(b64u(connectMac(secret, unb64u(c.routeId), Number(c.ts), unb64u(c.nonce), unb64u(c.challenge))), c.mac)
  const b = vectors.hmac.bind
  assert.equal(b64u(bindMac(secret, unb64u(b.routeId), unb64u(b.streamId), Number(b.generation), Number(b.ts), unb64u(b.nonce), unb64u(b.challenge))), b.mac)
})

test("deriveAddresses maps host to 8444/8443", () => {
  assert.equal(deriveAddresses("relay.example.com").agentAddress, "relay.example.com:8444")
  assert.equal(deriveAddresses("relay.example.com").clientAddress, "relay.example.com:8443")
  assert.equal(deriveAddresses("10.0.0.2:8444").clientAddress, "10.0.0.2:8443")
})

test("displayRelayHost hides default ports", () => {
  assert.equal(displayRelayHost("relay.example.com"), "relay.example.com")
  assert.equal(displayRelayHost("relay.example.com:8444"), "relay.example.com")
  assert.equal(displayRelayHost("relay.example.com:8443"), "relay.example.com")
  assert.equal(displayRelayHost("10.0.0.2:8444"), "10.0.0.2")
  assert.equal(displayRelayHost("[2001:db8::1]:8444"), "2001:db8::1")
  assert.equal(displayRelayHost("relay.example.com:9000"), "relay.example.com:9000")
  assert.equal(displayRelayHost(""), "")
})

test("switching Relays is a hostname change, not a port or same-host re-enroll", () => {
  assert.equal(isRelaySwitch("relay.example.com:8444", "relay.example.com:8443"), false)
  assert.equal(isRelaySwitch("relay.example.com", "relay.example.com:8444"), false)
  assert.equal(isRelaySwitch("relay.dshlinks.com:8444", "selfhost.example:8444"), true)
  assert.equal(isRelaySwitch("", "selfhost.example:8444"), false)
  assert.equal(isRelaySwitch("selfhost.example:8444", ""), false)
  const conflict = relaySwitchConflict("old.example:8444", "new.example:8444")
  assert.equal(conflict.code, "relay_switch")
  assert.equal(conflict.previousHost, "old.example")
  assert.equal(conflict.nextHost, "new.example")
  assert.match(conflict.error, /old\.example/)
  assert.match(conflict.error, /尝试从/)
  assert.match(
    relaySwitchConflict("old.example:8444", "new.example:8444", { controlUrl: "https://control.old.example/panel" }).error,
    /打开 https:\/\/control\.old\.example\/panel/,
  )
  assert.equal(relaySwitchConflict("old.example:8444", "new.example:8444", { confirm: true }), null)
  assert.equal(relaySwitchConflict("old.example:8444", "old.example:8443"), null)
  assert.equal(
    rememberReplacedRelayHost({ agentAddress: "old.example:8444" }, "new.example:8444"),
    "old.example",
  )
  assert.equal(
    rememberReplacedRelayHost(
      { agentAddress: "new.example:8444", replacedRelayHost: "old.example" },
      "new.example:8444",
    ),
    "old.example",
  )
  assert.equal(
    rememberReplacedRelayControlURL(
      { agentAddress: "old.example:8444", controlUrl: "https://control.old.example/panel" },
      "new.example:8444",
    ),
    "https://control.old.example/panel",
  )
  assert.equal(
    rememberReplacedRelayControlURL(
      { agentAddress: "new.example:8444", replacedRelayControlUrl: "https://control.old.example/panel" },
      "new.example:8444",
    ),
    "https://control.old.example/panel",
  )
  const live = {
    agentAddress: "old.example:8444",
    routeId: "route",
    hostSeed: "seed",
    hostPublicKey: "pub",
    insecureTls: true,
    tlsFingerprint: "a".repeat(64),
  }
  assert.deepEqual(previousRelayReleaseTarget(live, "new.example:8444"), {
    address: "old.example:8444",
    routeId: "route",
    hostSeed: "seed",
    hostPublicKey: "pub",
    insecureTls: true,
    tlsFingerprint: "a".repeat(64),
    controlUrl: "",
  })
  assert.equal(
    previousRelayReleaseTarget({ ...live, controlUrl: "https://control.old.example/panel" }, "new.example:8444").controlUrl,
    "https://control.old.example/panel",
  )
  assert.equal(previousRelayReleaseTarget({ ...live, paused: true }, "new.example:8444")?.routeId, "route")
  assert.equal(previousRelayReleaseTarget(live, "old.example:8443"), null)
  assert.equal(previousRelayReleaseTarget({ ...live, inactiveReason: "released" }, "new.example:8444"), null)
  assert.equal(previousRelayReleaseTarget({ agentAddress: "old.example:8444" }, "new.example:8444"), null)
})

test("relayPairSnapshot 只在已接入且未作废时给出云端配对字段", () => {
  const live = {
    clientAddress: "relay.example:8443",
    routeId: "rid",
    routeSecret: "sec",
    tlsFingerprint: "ab",
    inactiveReason: undefined,
  }
  assert.deepEqual(relayPairSnapshot(live), {
    v: 2,
    client: "relay.example:8443",
    routeId: "rid",
    routeSecret: "sec",
    tlsFingerprint: "ab",
  })
  assert.deepEqual(relayPairSnapshot({ ...live, paused: true }), relayPairSnapshot(live))
  assert.equal(relayPairSnapshot({ ...live, inactiveReason: "revoked" }), null)
  assert.equal(relayPairSnapshot({ ...live, inactiveReason: "released" }), null)
  assert.equal(relayPairSnapshot({ clientAddress: "relay.example:8443" }), null)
  assert.equal(relayPairSnapshot(null), null)
  const withControl = relayPairSnapshot({ ...live, controlUrl: "https://control.example.com/panel" })
  assert.equal(withControl.controlUrl, undefined)
  assert.equal(withControl.pairStamp, undefined)
  assert.deepEqual(withControl, relayPairSnapshot(live))
})

test("hosted Control URL 只来自接入串，且不把回环交给插件", () => {
  assert.equal(normalizePublicControlURL("https://control.example.com/panel/"), "https://control.example.com/panel")
  assert.equal(normalizePublicControlURL("http://control.example.com"), "")
  assert.equal(normalizePublicControlURL("https://127.0.0.1:8080"), "")
  assert.equal(normalizePublicControlURL("https://user:pass@control.example.com"), "")
  const previous = { agentAddress: "relay.example:8444", controlUrl: "https://control.example.com/panel" }
  assert.equal(nextRelayControlURL(previous, "relay.example:8444", {}), "https://control.example.com/panel")
  assert.equal(nextRelayControlURL(previous, "other.example:8444", {}), "")
  assert.equal(
    nextRelayControlURL(previous, "other.example:8444", { controlUrl: "https://control.other.example" }),
    "https://control.other.example",
  )
  assert.match(
    decorateRelayConsoleMessage("接入已被控制台吊销。请到控制台签发新接入码后再接入。", "https://control.example.com/panel"),
    /打开 https:\/\/control\.example\.com\/panel/,
  )
  assert.match(
    decorateRelayConsoleMessage(RELAY_QUOTA_MESSAGE, "https://control.example.com/panel"),
    /打开 https:\/\/control\.example\.com\/panel 吊销不用的电脑/,
  )
  assert.match(decorateRelayConsoleMessage(RELAY_QUOTA_MESSAGE, "https://control.example.com/panel"), /仍有效/)
})

test("满额失败后记住公网控制台地址，不把回环交给状态或 App", () => {
  assert.equal(attachRelayControlUrl(undefined, "https://127.0.0.1:8080"), undefined)
  assert.equal(attachRelayControlUrl(null, "http://control.example.com"), null)
  const onlyUrl = attachRelayControlUrl(undefined, "https://control.example.com/panel/")
  assert.deepEqual(onlyUrl, { controlUrl: "https://control.example.com/panel" })
  assert.equal(relayPairSnapshot(onlyUrl), null)
  const live = {
    routeId: "rid",
    routeSecret: "sec",
    clientAddress: "relay.example:8443",
    agentAddress: "relay.example:8444",
  }
  const kept = attachRelayControlUrl(live, "https://control.example.com/panel", "relay.example:8444")
  assert.equal(kept.routeId, "rid")
  assert.equal(kept.routeSecret, "sec")
  assert.equal(kept.controlUrl, "https://control.example.com/panel")
  assert.equal(relayPairSnapshot(kept).controlUrl, undefined)
  const same = { controlUrl: "https://control.example.com/panel" }
  assert.equal(attachRelayControlUrl(same, "https://control.example.com/panel/"), same)
  assert.equal(
    attachRelayControlUrl(
      { ...live, controlUrl: "https://control.old.example/panel" },
      "https://control.new.example/panel",
      "other.example:8444",
    ).controlUrl,
    "https://control.old.example/panel",
  )
})

test("换路由后提示手机扫码，不把控制台交给 App", () => {
  const live = { routeId: "rid", routeSecret: "sec" }
  assert.equal(relayRouteRotated(live), true)
  assert.equal(relayRouteRotated({ ...live, paused: true }), true)
  assert.equal(relayRouteRotated({ ...live, inactiveReason: "revoked" }), false)
  assert.equal(relayRouteRotated({ ...live, inactiveReason: "released" }), false)
  assert.equal(relayRouteRotated({}), false)
  assert.equal(phoneRelayRouteHint({ routeRotated: false }), "")
  assert.match(phoneRelayRouteHint({ routeRotated: true }), /纯远程请重新扫云端配对码/)
  assert.match(phoneRelayRouteHint({ routeRotated: true }), /手机不登录控制台/)
  assert.match(phoneRelayRouteHint({ routeRotated: true, previousReleased: false }), /原 Relay 名额可能仍占用/)
  assert.doesNotMatch(phoneRelayRouteHint({ routeRotated: true }), /登录账号|\/v1\/login/)
})

test("云端配对码在有有效路由时即可扫，不必等心跳在线", () => {
  assert.equal(showCloudPairQR({ enrolled: true, status: "online" }), true)
  assert.equal(showCloudPairQR({ enrolled: true, status: "offline" }), true)
  assert.equal(showCloudPairQR({ enrolled: true, status: "error" }), true)
  assert.equal(showCloudPairQR({ enrolled: true, status: "paused" }), false)
  assert.equal(showCloudPairQR({ enrolled: false, status: "revoked" }), false)
  assert.equal(showCloudPairQR({ routeId: "rid", routeSecret: "sec", status: "offline" }), true)
  assert.equal(showCloudPairQR({ routeId: "rid", routeSecret: "sec", inactiveReason: "revoked" }), false)
  assert.equal(showCloudPairQR({}), false)
  assert.match(cloudPairHint(true), /手机不登录控制台/)
  assert.match(cloudPairHint(false), /连上后即可扫这张云端码/)
})

test("云端 QR 在换路由后用非密钥戳刷新，不含 routeSecret", () => {
  const a = cloudPairStamp({ routeId: "routeAaaaaaaaa", routeSecret: "super-secret", clientAddress: "relay.example:8443" })
  const b = cloudPairStamp({ routeId: "routeBbbbbbbbb", routeSecret: "super-secret", clientAddress: "relay.example:8443" })
  assert.ok(a)
  assert.notEqual(a, b)
  assert.doesNotMatch(a, /super-secret/)
  assert.equal(cloudPairStamp({}), "")
})
