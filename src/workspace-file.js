import { isAbsolute, relative, resolve, sep } from "node:path"
import { realpathSync, statSync } from "node:fs"

export const MAX_WORKSPACE_FILE_BYTES = 8 * 1024 * 1024

export function resolveWorkspaceFile(cwd, requested) {
  if (typeof cwd !== "string" || !cwd.trim()) {
    const err = new Error("缺少工作区")
    err.status = 400
    throw err
  }
  if (typeof requested !== "string" || !requested.trim()) {
    const err = new Error("缺少 path")
    err.status = 400
    throw err
  }
  const root = realpathSync(resolve(cwd.trim()))
  const candidate = isAbsolute(requested.trim()) ? resolve(requested.trim()) : resolve(root, requested.trim())
  const lexicalRel = relative(root, candidate)
  if (lexicalRel.startsWith("..") || isAbsolute(lexicalRel)) {
    const err = new Error("路径越出工作区")
    err.status = 403
    throw err
  }
  let real
  try {
    real = realpathSync(candidate)
  } catch {
    const err = new Error("文件不存在")
    err.status = 404
    throw err
  }
  const rel = relative(root, real)
  if (!rel || rel.startsWith("..") || isAbsolute(rel)) {
    const err = new Error("路径越出工作区")
    err.status = 403
    throw err
  }
  const st = statSync(real)
  if (!st.isFile()) {
    const err = new Error("不是文件")
    err.status = 400
    throw err
  }
  if (st.size > MAX_WORKSPACE_FILE_BYTES) {
    const err = new Error("文件过大")
    err.status = 413
    throw err
  }
  return {
    abs: real,
    rel: rel.split("\\").join("/"),
    size: st.size,
    name: real.slice(real.lastIndexOf(sep) + 1),
  }
}

export function mimeFromName(name) {
  const ext = String(name ?? "").split(".").pop()?.toLowerCase()
  return {
    png: "image/png",
    jpg: "image/jpeg",
    jpeg: "image/jpeg",
    gif: "image/gif",
    webp: "image/webp",
    svg: "image/svg+xml",
    md: "text/markdown; charset=utf-8",
    txt: "text/plain; charset=utf-8",
    json: "application/json; charset=utf-8",
    html: "text/html; charset=utf-8",
    pdf: "application/pdf",
  }[ext] ?? "application/octet-stream"
}
