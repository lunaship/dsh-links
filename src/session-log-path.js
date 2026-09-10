/**
 * DSH JSONL 会话日志的当前 generation 文件名。
 * v0 是 `session.jsonl[.zstd]`；之后是 `session.vN.jsonl[.zstd]`（当前为 v3）。
 * 与 `@deepseek-ai/dsh-session-format` 的 canonical basename 规则对齐。
 */
import { existsSync, readdirSync } from "node:fs"
import { join } from "node:path"

const CANONICAL = /^session(?:\.v([1-9][0-9]*))?\.jsonl(\.zstd)?$/u

export function parseSessionLogFilename(filename) {
  if (typeof filename !== "string") return null
  const match = CANONICAL.exec(filename)
  if (!match) return null
  const version = match[1] === undefined ? 0 : Number(match[1])
  if (!Number.isSafeInteger(version)) return null
  return { version, compressed: match[2] === ".zstd" }
}

export function pickCurrentSessionLogFilename(names) {
  let best = null
  for (const name of names ?? []) {
    const parsed = parseSessionLogFilename(name)
    if (!parsed) continue
    if (
      !best
      || parsed.version > best.version
      || (parsed.version === best.version && parsed.compressed && !best.compressed)
    ) {
      best = { ...parsed, filename: name }
    }
  }
  return best?.filename ?? null
}

export function resolveSessionLogPath(sessionDir) {
  if (!sessionDir || !existsSync(sessionDir)) return null
  let names
  try {
    names = readdirSync(sessionDir)
  } catch {
    return null
  }
  const filename = pickCurrentSessionLogFilename(names)
  return filename ? join(sessionDir, filename) : null
}
