/**
 * dsh-links e2e architecture smoke.
 *
 * Boots a second DSH web host on scratch ports with the plugin's stateDir redirected
 * to a per-run scratch directory, then walks the architecture end to end:
 *   bundle patch -> plugin load -> TLS ready + port bind -> loopback panel routes ->
 *   cert-fingerprint pinning -> LAN pairing (scratch state) -> token-authorized
 *   mobile API -> SSE -> client face.
 *
 * Readiness rule (2026-09-13 hardening): pair-info 503 proxy_not_ready is a WAITING
 * state, never success. Success requires a 200 pair-info carrying a valid 64-hex
 * cert fingerprint and the expected scratch port, plus an HTTPS /dsh-link/health
 * round trip whose peer certificate fingerprint equals the advertised one. A 200
 * pair-info without a fingerprint is an immediate hard failure (pre-ready window
 * regression; the deterministic unit-level counterpart lives in
 * test/readiness.test.mjs and runs under `npm test`).
 *
 * Read-only with respect to the operator's profile: no workspace register/create,
 * no device revoke, no prompt submission. Plugin state lives entirely in scratch,
 * and the script asserts the operator's ~/.dsh/dsh-links/state.json is not given
 * the smoke device — the isolation rule recorded in docs/COMPATIBILITY.md
 * ("Smoke-isolation warning").
 *
 * Usage:
 *   node scripts/e2e-arch-smoke.mjs
 *   WEB_PORT=3081 MOBILE_PORT=18641 node scripts/e2e-arch-smoke.mjs   # fixed ports
 *
 * Exits non-zero if any check fails. Requires the `dsh` launcher on PATH and the
 * `web` profile (with dsh-links linked) already installed. An empty session store
 * is not a failure: SSE/model checks report a SKIP with the reason instead.
 */
import { spawn, execFileSync } from "node:child_process"
import { connect as tlsConnect } from "node:tls"
import { createHash } from "node:crypto"
import { homedir, tmpdir } from "node:os"
import { createServer } from "node:http"
import { mkdirSync, writeFileSync, chmodSync, readFileSync, existsSync, rmSync, realpathSync, mkdtempSync } from "node:fs"
import { dirname, join } from "node:path"
import { fileURLToPath } from "node:url"

const ROOT = dirname(dirname(fileURLToPath(import.meta.url)))
const REAL_ROOT = realpathSync(ROOT)

const results = []
function record(name, ok, detail = "") {
  results.push({ name, ok, skipped: false })
  console.log(`${ok ? "PASS" : "FAIL"}  ${name}${detail ? ` — ${detail}` : ""}`)
}
function skip(name, reason) {
  results.push({ name, ok: true, skipped: true })
  console.log(`SKIP  ${name} — ${reason}`)
}

function curl(args) {
  try {
    const out = execFileSync("curl", ["-sS", "--max-time", "20", ...args], { encoding: "buffer" })
    return { status: 0, body: out }
  } catch (err) {
    return { status: err.status ?? 1, body: err.stdout ?? Buffer.alloc(0), stderr: String(err.stderr ?? "") }
  }
}

function httpJson(url, extra = []) {
  const r = curl(["-o", "-", "-w", "\n%{http_code}", ...extra, url])
  const text = Buffer.from(r.body).toString("utf8")
  const idx = text.lastIndexOf("\n")
  const code = Number(text.slice(idx + 1).trim())
  const raw = text.slice(0, idx)
  let json
  try { json = JSON.parse(raw) } catch {}
  return { code, json, raw, curlExit: r.status }
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

function freePort() {
  return new Promise((resolve, reject) => {
    const srv = createServer()
    srv.unref()
    srv.once("error", reject)
    srv.listen(0, "127.0.0.1", () => {
      const port = srv.address().port
      srv.close(() => resolve(port))
    })
  })
}

const WEB_PORT = Number(process.env.WEB_PORT ?? 0) || (await freePort())
const MOBILE_PORT = Number(process.env.MOBILE_PORT ?? 0) || (await freePort())

// Per-run scratch directory: two concurrent runs can never share state.
const SCRATCH = mkdtempSync(join(tmpdir(), "dsh-links-smoke-"))
const STATE_DIR = join(SCRATCH, "state")
const PATCH_FILE = join(SCRATCH, "patch.yml")
const LOG_FILE = join(SCRATCH, "host.log")

// Proof surface: the operator's real plugin state must not receive this smoke's
// device. A raw hash is not a stable proof — a live host legitimately rewrites
// this file whenever a real phone polls (device lastSeenAt) — so assert identity
// ownership instead: the smoke device exists in scratch and nowhere in real state.
const REAL_STATE = join(homedir(), ".dsh", "dsh-links", "state.json")
function readText(p) {
  return existsSync(p) ? readFileSync(p, "utf8") : ""
}
function hashFile(p) {
  return existsSync(p) ? createHash("sha256").update(readFileSync(p)).digest("hex") : "absent"
}
const realStateHashBefore = hashFile(REAL_STATE)

// ---------- plugin-path proof ----------
// The launcher resolves the plugin from the web profile's node_modules link;
// it must point back at this working copy, not a globally installed copy.
const PROFILE_PLUGIN = join(homedir(), ".dsh", "profiles", "web", "node_modules", "dsh-links")
let profilePluginReal = null
try { profilePluginReal = realpathSync(PROFILE_PLUGIN) } catch {}
record("web profile resolves dsh-links to this repo", profilePluginReal === REAL_ROOT,
  profilePluginReal ? `loaded from ${profilePluginReal}` : "profile link missing (is the web profile installed?)")

// ---------- scratch setup ----------
mkdirSync(STATE_DIR, { recursive: true, mode: 0o700 })
try { chmodSync(STATE_DIR, 0o700) } catch {}
writeFileSync(PATCH_FILE, [
  "# one-shot overlay: isolate the plugin's global state dir and move off the live ports",
  "- id: dsh-links",
  "  config:",
  `    stateDir: ${STATE_DIR}`,
  `    port: ${MOBILE_PORT}`,
  "    autoApprove: true",
  "",
].join("\n"))
record("scratch isolation prepared", existsSync(PATCH_FILE), `stateDir=${STATE_DIR}`)

// ---------- boot ----------
const log = []
// launcher flags must precede the app's own flags: once --port appears, the rest
// is forwarded to the web app.
const child = spawn("dsh", ["--profile", "web", "--patch", PATCH_FILE, "--port", String(WEB_PORT), "--no-open"], {
  cwd: ROOT,
  env: { ...process.env },
  stdio: ["ignore", "pipe", "pipe"],
})
child.stdout.on("data", (d) => log.push(String(d)))
child.stderr.on("data", (d) => log.push(String(d)))
writeFileSync(LOG_FILE, "")

let exitInfo = null
child.on("exit", (code, sig) => { exitInfo = { code, sig } })

/** Peer certificate SHA-256 fingerprint (colon-stripped hex), or null. */
function peerFingerprint(port) {
  return new Promise((resolve, reject) => {
    const socket = tlsConnect({ host: "127.0.0.1", port, rejectUnauthorized: false }, () => {
      const fp = socket.getPeerCertificate()?.fingerprint256
      socket.end()
      resolve(fp ? fp.replace(/:/g, "").toLowerCase() : null)
    })
    socket.once("error", reject)
    socket.setTimeout(5000, () => { socket.destroy(); reject(new Error("tls probe timeout")) })
  })
}

/** HTTPS request pinned to the expected fingerprint (self-signed-safe pinning). */
function pinnedRequest(port, path, expectedFp) {
  return new Promise((resolve, reject) => {
    const socket = tlsConnect({
      host: "127.0.0.1",
      port,
      rejectUnauthorized: false,
      checkServerIdentity: () => {
        const fp = socket.getPeerCertificate()?.fingerprint256?.replace(/:/g, "").toLowerCase()
        if (fp !== expectedFp) return new Error(`certificate fingerprint mismatch: ${fp}`)
        return undefined
      },
    }, () => {
      socket.write(`GET ${path} HTTP/1.1\r\nhost: 127.0.0.1\r\nconnection: close\r\n\r\n`)
    })
    let body = ""
    socket.setEncoding("utf8")
    socket.on("data", (d) => { body += d })
    socket.on("end", () => {
      const status = Number(body.match(/^HTTP\/1\.[01] (\d{3})/)?.[1] ?? 0)
      resolve({ status, body })
    })
    socket.once("error", reject)
    socket.setTimeout(5000, () => { socket.destroy(); reject(new Error("pinned request timeout")) })
  })
}

const FINGERPRINT_RE = /^[0-9a-f]{64}$/

async function waitForReady(timeoutMs = 90_000) {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    if (exitInfo) return { ok: false, reason: "host exited during boot" }
    const r = httpJson(`http://127.0.0.1:${WEB_PORT}/dsh-link/pair-info`)
    if (r.code === 503) {
      // 明确的启动中状态：等待，但 phase=failed 时立即放弃。
      if (r.json?.error === "proxy_not_ready") {
        if (r.json?.phase === "failed") return { ok: false, reason: "plugin reported proxy failed (phase=failed)" }
        await sleep(1000)
        continue
      }
    }
    if (r.code === 200 && r.json?.type === "dsh-link") {
      const fp = r.json?.certFingerprint
      if (!FINGERPRINT_RE.test(String(fp ?? ""))) {
        return { ok: false, reason: "REGRESSION: pair-info answered 200 without a valid cert fingerprint (pre-ready window leaked pairable info)" }
      }
      if (r.json?.port !== MOBILE_PORT) {
        return { ok: false, reason: `pair-info advertises port ${r.json?.port}, expected ${MOBILE_PORT}` }
      }
      // 指纹必须与 HTTPS 实际证书一致；-k 只证明连通，不证明钉扎正确。
      let peerFp = null
      try { peerFp = await peerFingerprint(MOBILE_PORT) } catch (err) {
        return { ok: false, reason: `https peer probe failed: ${err?.message ?? err}` }
      }
      if (peerFp !== String(fp).toLowerCase()) {
        return { ok: false, reason: `cert fingerprint mismatch: pair-info=${fp} peer=${peerFp ?? "none"}` }
      }
      let pinned
      try { pinned = await pinnedRequest(MOBILE_PORT, "/dsh-link/health", String(fp).toLowerCase()) } catch (err) {
        return { ok: false, reason: `pinned health check failed: ${err?.message ?? err}` }
      }
      if (pinned.status !== 200) {
        return { ok: false, reason: `pinned /dsh-link/health returned ${pinned.status}` }
      }
      return { ok: true }
    }
    await sleep(1500)
  }
  return { ok: false, reason: "pair-info never reached a ready 200 within deadline; see host.log" }
}

const boot = await waitForReady()
writeFileSync(LOG_FILE, log.join(""))
record("host boot + plugin load + TLS ready + port bind", boot.ok, boot.ok ? `web=${WEB_PORT} mobile=${MOBILE_PORT}` : boot.reason)

// pairing 结果在 finally 的隔离断言里复用；未走到配对时保持 null。
let pairRef = null
try {
  if (!boot.ok) throw new Error("boot failed")

  // ---------- loopback panel face ----------
  const info = httpJson(`http://127.0.0.1:${WEB_PORT}/dsh-link/pair-info`)
  record("GET /dsh-link/pair-info 200", info.code === 200, `code=${info.code}`)
  record("pair-info advertises scratch port", info.json?.port === MOBILE_PORT, `port=${info.json?.port}`)
  record("pair-info carries one-time pairing code", typeof info.json?.pairingCode === "string" && info.json.pairingCode.length > 0)
  record("pair-info carries valid cert fingerprint", FINGERPRINT_RE.test(String(info.json?.certFingerprint ?? "")))
  record("pair-info exposure computed", typeof info.json?.exposure === "object" && info.json.exposure !== null && typeof info.json.exposure?.level === "string",
    `level=${info.json?.exposure?.level}`)

  const qr = curl(["-o", "/dev/null", "-w", "%{http_code} %{content_type}", `http://127.0.0.1:${WEB_PORT}/dsh-link/qr.png`])
  const qrLine = Buffer.from(qr.body).toString("utf8")
  record("GET /dsh-link/qr.png 200 image/png", /^200 image\/png/.test(qrLine), qrLine)

  const devices = httpJson(`http://127.0.0.1:${WEB_PORT}/dsh-link/devices`)
  record("GET /dsh-link/devices 200 (scratch state empty)", devices.code === 200 && Array.isArray(devices.json?.devices) && devices.json.devices.length === 0,
    `devices=${devices.json?.devices?.length}`)

  const relayStatus = httpJson(`http://127.0.0.1:${WEB_PORT}/dsh-link/relay-status`)
  record("GET /dsh-link/relay-status 200 (no route in scratch)", relayStatus.code === 200, `code=${relayStatus.code}`)

  const crossSite = httpJson(`http://127.0.0.1:${WEB_PORT}/dsh-link/devices`, ["-H", "sec-fetch-site: cross-site"])
  record("cross-site request rejected on loopback face", crossSite.code === 403, `code=${crossSite.code}`)

  // ---------- mobile face: unauthenticated ----------
  const mobileBase = `https://127.0.0.1:${MOBILE_PORT}`
  const health = httpJson(`${mobileBase}/dsh-link/health`, ["-k"])
  record("GET /dsh-link/health 200 (no token)", health.code === 200 && health.json?.ok === true, `code=${health.code}`)

  const noToken = httpJson(`${mobileBase}/dsh-link/mobile/bootstrap`, ["-k"])
  record("mobile API without token -> 401", noToken.code === 401, `code=${noToken.code}`)

  const forged = httpJson(`${mobileBase}/dsh-link/mobile/bootstrap`, ["-k", "-H", "x-dsh-link-token: deadbeef"])
  record("mobile API with forged token -> 401", forged.code === 401, `code=${forged.code}`)

  // ---------- LAN pairing into scratch state ----------
  const pair = httpJson(`${mobileBase}/dsh-link/pair`, [
    "-k", "-X", "POST", "-H", "content-type: application/json",
    "--data", JSON.stringify({ code: info.json.pairingCode, deviceName: "arch-smoke", requestId: "arch-smoke-request-0001" }),
  ])
  record("POST /dsh-link/pair 200 with token", pair.code === 200 && typeof pair.json?.token === "string", `code=${pair.code}${pair.json?.error ? ` err=${pair.json.error}` : ""}`)
  const token = pair.json?.token
  if (pair.code === 200) pairRef = pair.json ?? null

  const replay = httpJson(`${mobileBase}/dsh-link/pair`, [
    "-k", "-X", "POST", "-H", "content-type: application/json",
    "--data", JSON.stringify({ code: info.json.pairingCode, deviceName: "arch-smoke", requestId: "arch-smoke-request-0001" }),
  ])
  record("requestId replay is idempotent (same device)", replay.code === 200 && replay.json?.deviceId === pair.json?.deviceId,
    `code=${replay.code} sameDevice=${replay.json?.deviceId === pair.json?.deviceId}`)

  const reusedCode = httpJson(`${mobileBase}/dsh-link/pair`, [
    "-k", "-X", "POST", "-H", "content-type: application/json",
    "--data", JSON.stringify({ code: info.json.pairingCode, deviceName: "arch-smoke-2", requestId: "arch-smoke-request-0002" }),
  ])
  record("consumed pairing code cannot be reused", reusedCode.code === 401 || reusedCode.code === 429, `code=${reusedCode.code}`)

  const authHeader = ["-H", `x-dsh-link-token: ${token}`]

  // ---------- mobile face: authorized reads ----------
  if (token) {
    for (const [path, key] of [
      ["/dsh-link/mobile/bootstrap", "protocol"],
      ["/dsh-link/mobile/sessions", null],
      ["/dsh-link/mobile/llm-models", null],
      ["/dsh-link/mobile/workspaces", null],
      ["/dsh-link/mobile/settings", null],
      ["/dsh-link/mobile/agent-presets", null],
      ["/dsh-link/mobile/devices", null],
    ]) {
      const r = httpJson(`${mobileBase}${path}`, ["-k", ...authHeader])
      record(`GET ${path} 200`, r.code === 200, `code=${r.code}`)
      if (path.endsWith("/bootstrap") && r.code === 200) {
        record("bootstrap advertises protocol caps", r.json?.protocol != null, `caps=${JSON.stringify(r.json?.protocol ?? r.json?.caps ?? null)?.slice(0, 160)}`)
      }
    }

    const sessions = httpJson(`${mobileBase}/dsh-link/mobile/sessions`, ["-k", ...authHeader])
    const items = sessions.json?.items ?? sessions.json?.sessions ?? []
    record("session list carries archivedSessionIds set", Array.isArray(sessions.json?.archivedSessionIds), `archived=${sessions.json?.archivedSessionIds?.length ?? "absent"}`)

    // 空会话环境不是失败：明确跳过并说明原因，而不是把"真实账号必须有历史"当成功前提。
    if (!Array.isArray(items) || items.length === 0) {
      skip("SSE + models against a real session", "shared session store has no sessions in this environment")
    } else {
      const sessionId = items[0]?.sessionId
      if (sessionId) {
        const models = httpJson(`${mobileBase}/dsh-link/mobile/models?sessionId=${encodeURIComponent(sessionId)}`, ["-k", ...authHeader])
        record("GET /dsh-link/mobile/models?sessionId= 200", models.code === 200, `code=${models.code}`)

        const noSession = httpJson(`${mobileBase}/dsh-link/mobile/models`, ["-k", ...authHeader])
        record("GET /dsh-link/mobile/models without sessionId -> 400", noSession.code === 400, `code=${noSession.code}`)
        try {
          const sse = execFileSync("curl", ["-sS", "-k", "--max-time", "8", "-N", ...authHeader,
            `${mobileBase}/dsh-link/mobile/sessions/${encodeURIComponent(sessionId)}/stream`], { encoding: "utf8" })
          record("SSE stream opens with ready frame", /event:\s*ready/.test(sse), `bytes=${sse.length}`)
        } catch (err) {
          const partial = String(err.stdout ?? "")
          record("SSE stream opens with ready frame", /event:\s*ready/.test(partial), `partial=${partial.slice(0, 120)}`)
        }
      } else {
        record("SSE stream opens with ready frame", false, "session rows present but first row lacks sessionId")
      }
    }
  } else {
    record("mobile face: authorized reads", false, "no token from pairing")
  }

  // ---------- client face wiring (static, host-independent) ----------
  const pkg = JSON.parse(readFileSync(join(ROOT, "package.json"), "utf8"))
  record("package.json declares web client face with layout+settings inject",
    pkg.dsh?.client?.platform === "web" &&
    (pkg.dsh?.client?.inject ?? []).includes("@deepseek-ai/dsh-client-ui-layout") &&
    (pkg.dsh?.client?.inject ?? []).includes("@deepseek-ai/dsh-client-ui-settings"),
    JSON.stringify(pkg.dsh?.client))

  try {
    execFileSync(process.execPath, ["--check", join(ROOT, "src", "client.js")], { stdio: "pipe" })
    record("generated client bundle parses", true, "src/client.js")
  } catch (err) {
    record("generated client bundle parses", false, String(err.stderr ?? err).slice(0, 160))
  }

  const clientSrc = readFileSync(join(ROOT, "src", "client.js"), "utf8")
  record("client bundle registers the settings.section panel",
    /settings\.section|createPanelModule|__ModuleLoader__/.test(clientSrc),
    `bytes=${clientSrc.length}`)
} catch (err) {
  record("smoke body completed", false, String(err?.message ?? err))
} finally {
  // ---------- teardown: 进程与临时目录都必须释放 ----------
  try { child.kill("SIGTERM") } catch {}
  for (let i = 0; i < 20 && !exitInfo; i++) await sleep(250)
  if (!exitInfo) { try { child.kill("SIGKILL") } catch {} }
  record("host shut down", true, exitInfo ? `exit=${exitInfo.code}` : "killed")

  const realStateAfter = readText(REAL_STATE)
  const realStateHashAfter = hashFile(REAL_STATE)
  const smokeDeviceId = pairRef?.deviceId ?? ""
  const scratchStatePath = join(STATE_DIR, "state.json")
  const scratchState = readText(scratchStatePath)
  if (!pairRef) {
    skip("smoke device lives only in scratch state", "pairing not reached, no smoke device was created")
  } else {
    record("smoke device lives only in scratch state",
      !!smokeDeviceId && scratchState.includes(smokeDeviceId) && !realStateAfter.includes(smokeDeviceId) && !realStateAfter.includes("arch-smoke"),
      `realStateHasSmokeDevice=${realStateAfter.includes("arch-smoke")}`)
  }
  // Informational: the real state file is legitimately rewritten by a live host
  // when a real phone polls, so this is evidence, not an assertion.
  console.log(`INFO  real state sha256 ${realStateHashBefore.slice(0, 12)} → ${realStateHashAfter.slice(0, 12)}` +
    (realStateHashBefore === realStateHashAfter ? " (unchanged)" : " (live-host activity; no smoke device present)"))

  const failed = results.filter((r) => !r.ok)
  const skipped = results.filter((r) => r.skipped)
  console.log(`\n${results.length - failed.length}/${results.length} checks passed (${skipped.length} skipped)`)
  if (failed.length === 0) {
    rmSync(SCRATCH, { recursive: true, force: true })
  } else {
    // 失败时保留现场供排查；日志中不包含 token / 二维码载荷，配对码只在 host 内存。
    console.log(`failures:\n${failed.map((f) => `  - ${f.name} (${f.detail})`).join("\n")}`)
    console.log(`scratch preserved for debugging: ${SCRATCH} (log: ${LOG_FILE})`)
  }
  process.exit(failed.length ? 1 : 0)
}
