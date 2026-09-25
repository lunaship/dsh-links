/** 业务层能力协商。DLR/1 与内层证书固定不因这些字段改变。 */

import { MAX_WORKSPACE_FILE_BYTES } from "./workspace-file.js"
import { MAX_DIFF_LINES } from "./workspace-changes.js"

export const PLUGIN_PROTOCOL = 2
export const CAP_SYNC2 = "sync2"
export const CAP_MULTI_QUESTION = "multiQuestion"
export const CAP_REQUEST_STATE = "requestState"
export const RECONNECT_GRACE_MS = 30_000

export const CLIENT_CAPS_QUERY = `${CAP_SYNC2},${CAP_MULTI_QUESTION},${CAP_REQUEST_STATE}`

export function parseClientCaps(raw) {
  const set = new Set(String(raw ?? "").split(",").map((item) => item.trim()).filter(Boolean))
  return {
    sync2: set.has(CAP_SYNC2),
    multiQuestion: set.has(CAP_MULTI_QUESTION),
    requestState: set.has(CAP_REQUEST_STATE),
  }
}

/**
 * @param {{ changes?: boolean }} [host] changes：Host 挂载了 workspaceChanges 服务
 *   （DSH 0.1.7 起的 `@deepseek-ai/dsh-workspace-changes`）。旧 App 忽略未知字段。
 */
export function pluginCapabilities({ changes = false } = {}) {
  return {
    protocol: PLUGIN_PROTOCOL,
    sync: { resync: true, catchupIntegrity: true },
    questions: { multi: true, serverValidation: true },
    requests: { snapshot: true, reconnectGraceMs: RECONNECT_GRACE_MS },
    files: {
      workspace: true,
      maxBytes: MAX_WORKSPACE_FILE_BYTES,
      ...(changes ? { changes: true, diff: true, diffMaxLines: MAX_DIFF_LINES } : {}),
    },
  }
}

export function anyConnHasCap(writers, cap) {
  for (const conn of writers ?? []) {
    if (conn?.caps?.[cap]) return true
  }
  return false
}

export function subscribedDeviceIds(writers) {
  const ids = new Set()
  for (const conn of writers ?? []) {
    if (conn?.deviceId) ids.add(conn.deviceId)
  }
  return ids
}
