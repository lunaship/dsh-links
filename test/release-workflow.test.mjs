import assert from "node:assert/strict"
import test from "node:test"
import { execFileSync } from "node:child_process"
import { readFileSync } from "node:fs"
import { fileURLToPath } from "node:url"
import { dirname } from "node:path"

const root = dirname(fileURLToPath(new URL("../package.json", import.meta.url)))
const workflow = readFileSync(fileURLToPath(new URL("../.github/workflows/publish-npm.yml", import.meta.url)), "utf8")
const ci = readFileSync(fileURLToPath(new URL("../.github/workflows/ci.yml", import.meta.url)), "utf8")
const pkg = JSON.parse(readFileSync(fileURLToPath(new URL("../package.json", import.meta.url)), "utf8"))

test("npm 发布工作流将第三方 Action 固定到已核验提交", () => {
  assert.match(workflow, /actions\/checkout@d23441a48e516b6c34aea4fa41551a30e30af803\s+# v6/)
  assert.match(workflow, /actions\/setup-node@249970729cb0ef3589644e2896645e5dc5ba9c38\s+# v6/)
  assert.match(workflow, /pnpm\/action-setup@b906affcce14559ad1aafd4ab0e942779e9f58b1\s+# v4/)
  assert.doesNotMatch(workflow, /actions\/checkout@v\d+\b/)
  assert.doesNotMatch(workflow, /actions\/setup-node@v\d+\b/)
  assert.doesNotMatch(workflow, /pnpm\/action-setup@v4\b/)
})

test("npm files 不含 Go Relay 树，CI 比对同仓 DLR/1 镜像", () => {
  assert.ok(pkg.files.includes("src"))
  assert.ok(pkg.files.includes("SECURITY.md"))
  assert.ok(pkg.files.includes("PRIVACY.md"))
  assert.ok(pkg.files.includes("REMOTE_ACCESS.md"))
  assert.ok(pkg.files.includes("docs/*.md"))
  assert.ok(pkg.files.includes("docs/images/*-latest*.png"))
  assert.ok(!pkg.files.some((entry) => entry === "relay" || String(entry).startsWith("relay/")))
  assert.match(ci, /check-dlr1-vectors\.mjs testdata\/dlr1-vectors\.json relay\/testdata\/dlr1-vectors\.json/)
  assert.match(ci, /go test \.\/\.\.\. -race/)
  assert.doesNotMatch(ci, /CGO_ENABLED=0 go test \.\/\.\.\. -race/)
  const pack = JSON.parse(execFileSync("npm", ["pack", "--dry-run", "--ignore-scripts", "--json"], { encoding: "utf8", cwd: root }))
  const packedFiles = new Set(pack[0].files.map((file) => file.path))
  assert.ok(packedFiles.has("SECURITY.md"))
  assert.ok(packedFiles.has("PRIVACY.md"))
  assert.ok(packedFiles.has("REMOTE_ACCESS.md"))
  assert.ok(packedFiles.has("docs/COMPATIBILITY.md"))
  assert.ok(packedFiles.has("docs/MOBILE_SYNC_CONTRACT.md"))
  assert.ok(packedFiles.has("docs/images/dsh-workbench-latest.png"))
  assert.ok(packedFiles.has("docs/images/android-workspace-latest.png"))
  assert.ok(![...packedFiles].some((entry) => /^relay\/.+\.go$/.test(entry)))
  assert.ok(!packedFiles.has("cmd/dsh-links-relay"))
  assert.ok(!packedFiles.has("docs/images/android-workspace-dark.jpg"))
})
