import assert from "node:assert/strict"
import test from "node:test"
import { readFileSync } from "node:fs"
import { fileURLToPath } from "node:url"

const workflow = readFileSync(fileURLToPath(new URL("../.github/workflows/publish-npm.yml", import.meta.url)), "utf8")

test("npm 发布工作流将第三方 Action 固定到已核验提交", () => {
  assert.match(workflow, /actions\/checkout@d23441a48e516b6c34aea4fa41551a30e30af803\s+# v6/)
  assert.match(workflow, /actions\/setup-node@249970729cb0ef3589644e2896645e5dc5ba9c38\s+# v6/)
  assert.match(workflow, /pnpm\/action-setup@b906affcce14559ad1aafd4ab0e942779e9f58b1\s+# v4/)
  assert.doesNotMatch(workflow, /actions\/checkout@v\d+\b/)
  assert.doesNotMatch(workflow, /actions\/setup-node@v\d+\b/)
  assert.doesNotMatch(workflow, /pnpm\/action-setup@v4\b/)
})
