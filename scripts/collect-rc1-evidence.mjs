import { execFileSync } from "node:child_process"
import { createHash } from "node:crypto"
import { existsSync, readFileSync, writeFileSync } from "node:fs"
import { resolve } from "node:path"
import { fileURLToPath } from "node:url"

const root = resolve(process.argv[2] ?? fileURLToPath(new URL("../", import.meta.url)))
const repos = {
  plugin: root,
  app: resolve(root, "../dsh-links-app"),
  relay: resolve(root, "../dsh-links-relay"),
}

function git(dir, args) {
  return execFileSync("git", ["-C", dir, ...args], { encoding: "utf8" }).trim()
}

function revision(dir) {
  const status = git(dir, ["status", "--porcelain=v1", "--untracked-files=all"])
  return {
    revision: git(dir, ["rev-parse", "HEAD"]),
    branch: git(dir, ["branch", "--show-current"]),
    dirty: Boolean(status),
    worktreeChanges: status ? status.split("\n") : [],
  }
}

const packageJson = JSON.parse(readFileSync(resolve(repos.plugin, "package.json"), "utf8"))
const appGradle = readFileSync(resolve(repos.app, "app/build.gradle.kts"), "utf8")
const appVersion = appGradle.match(/versionName\s*=\s*"([^"]+)"/)?.[1] ?? "unknown"
const apkPath = resolve(process.env.APK_PATH ?? resolve(repos.app, "app/build/outputs/apk/debug/app-debug.apk"))
const apkSha256 = existsSync(apkPath)
  ? createHash("sha256").update(readFileSync(apkPath)).digest("hex")
  : "not-built"
const evidence = {
  generatedAt: new Date().toISOString(),
  dshVersion: process.env.DSH_VERSION ?? "record from the tested DSH installation",
  components: {
    plugin: { version: packageJson.version, ...revision(repos.plugin) },
    app: { version: appVersion, apkPath, apkSha256, ...revision(repos.app) },
    relay: revision(repos.relay),
  },
  commands: {
    plugin: "pnpm install --frozen-lockfile && pnpm build:client && pnpm test && pnpm audit --prod",
    app: "./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug",
    relay: "CGO_ENABLED=0 go test ./... -race -count=1 -timeout 120s && CGO_ENABLED=0 go vet ./...",
  },
  boundaries: {
    verified: ["source-level tests and local build gates only"],
    unverified: [
      "real Android -> Relay -> Plugin end-to-end",
      "real public CA/DNS/TLS",
      "24h soak",
      "capacity and threshold measurements",
    ],
  },
}

const output = process.argv[3]
  ? resolve(process.argv[3])
  : resolve(root, "docs/rc1-evidence.current.json")
writeFileSync(output, `${JSON.stringify(evidence, null, 2)}\n`, { mode: 0o600 })
console.log(`Wrote ${output}`)
