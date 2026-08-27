import { createHash } from "node:crypto"
import { readFile } from "node:fs/promises"
import { access } from "node:fs/promises"

const expected = "e46ddf3ebe091b544376d70cd81b0489d621d2f232fcb705e90b8e49312f7467"
const local = process.argv[2] ?? "testdata/dlr1-vectors.json"
const reference = process.argv[3] ?? ""

async function canonicalHash(path) {
  const value = JSON.parse(await readFile(path, "utf8"))
  return createHash("sha256").update(JSON.stringify(value)).digest("hex")
}

const localHash = await canonicalHash(local)
if (localHash !== expected) {
  throw new Error(`DLR/1 vectors drifted: ${local} has ${localHash}, expected ${expected}`)
}

if (reference) {
  await access(reference)
  const referenceHash = await canonicalHash(reference)
  if (referenceHash !== localHash) {
    throw new Error(`DLR/1 vectors mismatch: ${local} != ${reference}`)
  }
}

console.log(`DLR/1 vectors OK (${localHash})`)
