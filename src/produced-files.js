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

/**
 * tool/result 消息的两种物理形状都要认——同一个历史窗口里可能混着 v3 与 v4 会话：
 *
 * - V3：`role: "user"` + `content: [ToolResultBlock]`，callId / 内层 content / isError
 *   挂在这个 `tool-result` 包装块上，`message.source.callId` 另有一份。
 * - V4（DSH 0.1.7 起）：提升为一等 `role: "tool"` 消息，`toolCallId` / `isError`
 *   提到 message 上，`content` 直接是内层内容数组。
 *
 * 判定以 `toolCallId` 为准：V4 必填，V3 不存在。
 */
export function toolResultMeta(event) {
  const message = event?.data?.message
  if (!isRecord(message)) return null
  const blocks = Array.isArray(message.content) ? message.content : []
  const head = isRecord(blocks[0]) ? blocks[0] : null
  if (typeof message.toolCallId === "string" && message.toolCallId.length > 0) {
    return { callId: message.toolCallId, isError: message.isError === true }
  }
  const sourceCallId = message.source?.callId
  return {
    callId: typeof sourceCallId === "string" && sourceCallId.length > 0
      ? sourceCallId
      : (typeof head?.toolCallId === "string" && head.toolCallId.length > 0 ? head.toolCallId : null),
    isError: head?.isError === true,
  }
}

/** 结果正文块：V3 在 `tool-result` 包装块内层，V4 直接就是 `message.content`。 */
export function toolResultContent(event) {
  const message = event?.data?.message
  if (!isRecord(message)) return []
  const blocks = Array.isArray(message.content) ? message.content : []
  const head = isRecord(blocks[0]) ? blocks[0] : null
  return head?.type === "tool-result" && Array.isArray(head.content) ? head.content : blocks
}

export function toolResultIsError(event) {
  return toolResultMeta(event)?.isError === true
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
