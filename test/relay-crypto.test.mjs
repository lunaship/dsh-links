import assert from "node:assert/strict"
import { readFile } from "node:fs/promises"
import test from "node:test"
import {
  b64u,
  unb64u,
  enrollTranscript,
  registerTranscript,
  connectMac,
  bindMac,
  signEd25519,
  deriveAddresses,
  displayRelayHost,
} from "../src/relay/crypto.js"

const vectors = JSON.parse(await readFile(new URL("../testdata/dlr1-vectors.json", import.meta.url), "utf8"))

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
