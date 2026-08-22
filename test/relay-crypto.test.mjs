import assert from "node:assert/strict"
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
} from "../src/relay/crypto.js"

const vectors = {
  enroll: {
    challenge: "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8",
    hostId: "host-7f4e-vectors",
    hostPublicKey: "nYVamYbiyDILpl3yUC1oQE8OZinJavHOcSsjsNjPK1Q",
    hostSeed: "BKXnLn4kw9Yr1rJI_MQtoFVQUZrN09Bk41NMCmNPDpk",
    inviteCode: "test-invite-code-for-vectors",
    nonce: "qrvM3e7_ABEiM0RVZneImQ",
    proof: "PWge4Q96yApZ4RN0W1UBPg682JOZLms8YVTJhFBZa4_YYgN5jK-zJlXwMNVR_hryJk9sfko59uTikl9fwSOICA",
    ts: "1787300000",
  },
  register: {
    capability: "eyJhbGciOiJFZERTQSIsInR5cCI6IkRMUi1DQVAiLCJ2IjoxfQ.eyJpc3MiOiJkc2gtbGlua3MtcmVsYXkiLCJqdGkiOiJBUUlEQkFVR0J3Z0pDZ3NNRFE0UEVBIiwiaG9zdCI6Imhvc3QtN2Y0ZS12ZWN0b3JzIiwicm91dGUiOiJBUUlEQkFVR0J3Z0pDZ3NNRFE0UEVBIiwiaG9zdF9wayI6Im5ZVmFtWWJpeURJTHBsM3lVQzFvUUU4T1ppbkphdkhPY1NzanNOalBLMVEiLCJnZW5lcmF0aW9uIjoxLCJtYXhfc3RyZWFtcyI6OCwiaWF0IjoxNzg3MzAwMDAwLCJleHAiOjE3ODk4OTIwMDB9.x3x5XHsSbognh14Ok0q9jRn394xjTyKfaRmOHxDfWCgfDMVNwLw4S1rtC-cFldbtTp56AouasXoBvurm1gYHCw",
    nonce: "qrvM3e7_ABEiM0RVZneImQ",
    proof: "F6fhfdwwGZzKoteTJXPI9RmEO4sgVyCCHknxwwsKyfLFjLViXg1ujuM7zJ2yVJuUe8EB8IgTrACGM2BV4K6fAg",
    ts: "1787300000",
  },
  hmac: {
    routeSecret: "Qez-v5W9wsdVyezjjIIEvTD6BHLNoT4HEmMICmEhX8A",
    connect: {
      challenge: "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8",
      mac: "3KPdYjS0C94YeavYpkmCspJ1SYRA-1q27H2Ie4448CY",
      nonce: "qrvM3e7_ABEiM0RVZneImQ",
      routeId: "AQIDBAUGBwgJCgsMDQ4PEA",
      ts: "1787300000",
    },
    bind: {
      challenge: "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8",
      generation: "1",
      mac: "q5-KIZ0HPHmgL2NZZWpit4pQgf2hYwcrrNyudONezPU",
      nonce: "qrvM3e7_ABEiM0RVZneImQ",
      routeId: "AQIDBAUGBwgJCgsMDQ4PEA",
      streamId: "EA8ODQwLCgkIBwYFBAMCAQ",
      ts: "1787300000",
    },
  },
}

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
  const secret = unb64u(vectors.hmac.routeSecret)
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
