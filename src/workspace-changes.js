/**
 * 本轮改动文件（DSH `workspaceChanges` 服务）的手机端投影。
 *
 * DSH 的 `@deepseek-ai/dsh-workspace-changes` 在每个顶层轮次末尾追加一条只带轮号的
 * `workspace/changes` 事件；摘要与逐文件对比留在 Host 内存里，按事件 seq 取。
 * 本插件不自己做快照，只转发并裁剪到手机可承受的体积：
 *
 * - 摘要只送路径 + 行数 + 降级标记（与 Web 摘要路由同形，不送 cwd / 快照 tree id）；
 * - 对比按行数截断（DSH Web 同样只显示前 5000 行），`coarse` 时两侧整文件可达 2×2MiB；
 * - Host 重启后旧轮次取不到摘要：返回 undefined，调用方不出卡片（DSH 的既定行为）。
 */

/** 历史页内嵌摘要携带的最大文件数；完整列表走摘要路由。 */
export const MAX_EMBEDDED_CHANGED_FILES = 100
/** 摘要路由携带的最大文件数（DSH 默认 maxFiles 为 500）。 */
export const MAX_CHANGED_FILES = 500
/** 单个文件对比最多送出的 hunk 行数。 */
export const MAX_DIFF_LINES = 5000
/** 单个文件对比最多送出的字符数（压缩脚本等超长行；手机端按 4MB 读取 JSON）。 */
export const MAX_DIFF_CHARS = 1_500_000

/** 取 Host 的 workspaceChanges 服务；旧 Host 未挂载该插件时为 undefined。 */
export function workspaceChangesService(get) {
  try {
    const service = get?.()
    if (service && typeof service.summary === "function" && typeof service.diff === "function") return service
  } catch {}
  return undefined
}

function nonNegativeInt(raw) {
  if (raw === null || raw === undefined || raw === "") return undefined
  const n = Number(raw)
  return Number.isSafeInteger(n) && n >= 0 ? n : undefined
}

/** 解析 `?seq=` 与（对比路由的）`?index=`；非法时返回 null。 */
export function parseChangesCoordinates(search, { needIndex = false } = {}) {
  const seq = nonNegativeInt(search?.get?.("seq"))
  if (seq === undefined) return null
  if (!needIndex) return { seq }
  const index = nonNegativeInt(search?.get?.("index"))
  if (index === undefined) return null
  return { seq, index }
}

function projectChangedFile(file) {
  const out = {
    path: String(file?.path ?? ""),
    display: String(file?.display ?? file?.path ?? ""),
    added: Number.isFinite(file?.added) ? file.added : 0,
    deleted: Number.isFinite(file?.deleted) ? file.deleted : 0,
  }
  if (file?.binary) out.binary = true
  if (file?.oversized) out.oversized = true
  return out
}

/**
 * 摘要投影：DSH `WorkspaceChangesSummary` → 手机摘要。
 * 空摘要（没有列出任何文件）返回 undefined——与 Web「摘要没有列出任何文件时没有卡片」一致。
 * `files` 保持 DSH 的顺序，文件下标即对比路由的 `index`。
 */
export function projectChangesSummary(summary, { maxFiles = MAX_CHANGED_FILES } = {}) {
  if (!summary || !Array.isArray(summary.files) || summary.files.length === 0) return undefined
  const files = summary.files.slice(0, Math.max(0, maxFiles)).map(projectChangedFile)
  const total = Number.isFinite(summary.total) ? summary.total : summary.files.length
  return {
    turn: Number.isFinite(summary.turn) ? summary.turn : 0,
    total,
    added: Number.isFinite(summary.added) ? summary.added : 0,
    deleted: Number.isFinite(summary.deleted) ? summary.deleted : 0,
    files,
  }
}

/**
 * 对比投影：DSH `WorkspaceFileDiff` → 手机对比，按 hunk 行数截断。
 * 截断只丢尾部整段 hunk / 行，不改前面的行号；`truncated` 给出总行数供 UI 说明。
 */
export function projectFileDiff(diff, { maxLines = MAX_DIFF_LINES, maxChars = MAX_DIFF_CHARS } = {}) {
  if (!diff || typeof diff !== "object") return undefined
  const base = { path: String(diff.path ?? ""), display: String(diff.display ?? diff.path ?? "") }
  if (diff.kind === "binary" || diff.kind === "oversized") return { kind: diff.kind, ...base }
  if (diff.kind !== "text") return undefined
  const hunks = []
  let totalLines = 0
  let shown = 0
  let chars = 0
  let full = false
  for (const hunk of Array.isArray(diff.hunks) ? diff.hunks : []) {
    const lines = Array.isArray(hunk?.lines) ? hunk.lines.map((line) => String(line)) : []
    totalLines += lines.length
    if (full) continue
    const kept = []
    for (const line of lines) {
      if (shown >= maxLines || chars + line.length > maxChars) {
        full = true
        break
      }
      kept.push(line)
      shown++
      chars += line.length
    }
    if (kept.length === 0) continue
    hunks.push({
      oldStart: Number(hunk?.oldStart) || 0,
      oldLines: Number(hunk?.oldLines) || 0,
      newStart: Number(hunk?.newStart) || 0,
      newLines: Number(hunk?.newLines) || 0,
      lines: kept,
    })
  }
  const out = {
    kind: "text",
    ...base,
    before: Boolean(diff.before),
    after: Boolean(diff.after),
    coarse: Boolean(diff.coarse),
    hunks,
  }
  if (shown < totalLines) out.truncated = { shownLines: shown, totalLines }
  return out
}
