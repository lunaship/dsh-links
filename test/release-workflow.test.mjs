import assert from "node:assert/strict"
import test from "node:test"
import { readFileSync } from "node:fs"
import { fileURLToPath } from "node:url"

const workflow = readFileSync(fileURLToPath(new URL("../.github/workflows/publish-npm.yml", import.meta.url)), "utf8")

test("npm 发布工作流将 pnpm setup 固定到已核验提交", () => {
  assert.match(workflow, /pnpm\/action-setup@b906affcce14559ad1aafd4ab0e942779e9f58b1\s+# v4/)
  assert.doesNotMatch(workflow, /pnpm\/action-setup@v4\b/)
})
