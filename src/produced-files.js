/**
 * 对齐 DSH Web deliverables：产出文件只来自成功的 write / edit / 变更型
 * str_replace_editor，不从收尾散文猜测。
 */
function isRecord(value) {
  return value !== null && typeof value === "object" && !Array.isArray(value)
}

function pathValue(value) {
  return typeof value === "string" && value.trim().length > 0 ? value : null
}

function validEditArgs(args) {
  return typeof args.old_string === "string" && args.old_string.length > 0
    && typeof args.new_string === "string"
    && args.old_string !== args.new_string
    && (args.replace_all === undefined || typeof args.replace_all === "boolean")
}

function editorMutationPath(args) {
  const path = pathValue(args.path)
  if (path === null) return null
  switch (args.command) {
    case "create":
      return typeof args.file_text === "string" ? path : null
    case "str_replace":
      return typeof args.old_str === "string" && args.old_str.length > 0
        && (args.new_str === undefined || typeof args.new_str === "string")
        ? path
        : null
    case "insert":
      return typeof args.insert_line === "number" && Number.isInteger(args.insert_line)
        && args.insert_line >= 0 && typeof args.new_str === "string"
        ? path
        : null
    default:
      return null
  }
}

export function mutationPath(name, argsRaw) {
  let args
  try {
    args = typeof argsRaw === "string" ? JSON.parse(argsRaw) : argsRaw
  } catch {
    return null
  }
  if (!isRecord(args)) return null
  switch (name) {
    case "write":
      return typeof args.content === "string" ? pathValue(args.file_path) : null
    case "edit":
      return validEditArgs(args) ? pathValue(args.file_path) : null
    case "str_replace_editor":
      return editorMutationPath(args)
    default:
      return null
  }
}

export function toolResultIsError(event) {
  const first = event?.data?.message?.content?.[0]
  return first?.isError === true
}

export function uniquePaths(entries) {
  const seen = new Set()
  const paths = []
  for (const item of entries ?? []) {
    const path = pathValue(item?.path ?? item)
    if (!path || seen.has(path)) continue
    seen.add(path)
    paths.push(path)
  }
  return paths
}
