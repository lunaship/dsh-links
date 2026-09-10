import { omitNullFields, optionalString } from "./optional-string.js"

export function mobileSessionSummary(item) {
  const projections = item?.projections?.values ?? {}
  const title = typeof projections.title === "string" && projections.title.trim()
    ? projections.title.trim()
    : "未命名会话"
  const parentSessionId = item.parentSessionId
    ?? item.parentSession?.sessionId
    ?? item.parentSession?.id
    ?? item.spawn?.parentSessionId
    ?? null
  const subagentCountRaw = item.subagentCount ?? item.activeSubagentCount
    ?? (Array.isArray(item.subagents) ? item.subagents.length : null)
  return omitNullFields({
    sessionId: item.sessionId,
    title,
    updatedAt: item.updatedAt,
    running: Boolean(item.running),
    blank: Boolean(item.blank),
    cwd: optionalString(item.cwd),
    agentPreset: optionalString(item.agentPreset),
    origin: optionalString(item.origin),
    parentSessionId: optionalString(parentSessionId),
    subagentCount: Number.isFinite(subagentCountRaw) ? subagentCountRaw : null,
  })
}
