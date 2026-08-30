import { test } from "node:test"
import assert from "node:assert/strict"
import { readFile } from "node:fs/promises"

test("Plugin only emits routable invitation Relay QR payloads", async () => {
  const indexSource = await readFile(new URL("../src/index.js", import.meta.url), "utf8")
  const panelSource = await readFile(new URL("../src/module2.js", import.meta.url), "utf8")

  assert.match(indexSource, /routeId: relay\.routeId/)
  assert.match(indexSource, /routeSecret: relay\.routeSecret/)
  assert.doesNotMatch(indexSource, /relay-qr-mode|qrMode|["']anonymous["']/)
  assert.doesNotMatch(panelSource, /relay-qr-mode|qrMode|setQrMode|["']anonymous["']/)
})
