/**
 * 向本机 DSH 发 Remote 调用的唯一通道。
 *
 * 0.1.2 起 ApiProxy 已移除：unary 走 Typert `@Remote`（in-process `typertGateway`，
 * 测试/无 gateway 时回退 HTTP `POST /api/{namespace}/{method}`）。
 * 插件内部仍用点号方法名（RPC_METHOD_ALLOWLIST）；发出前映射成 `namespace/method`
 * 并把 payload 包进 `{ args }`。方法名是写死的闭集，callLocalRpc 在发出前强制校验。
 */
import { request as httpRequest } from "node:http"
import { randomBytes } from "node:crypto"

/** 本插件允许代调的 DSH 方法闭集（插件面点号名）。新增方法：先加这里，再加调用点。 */
export const RPC_METHOD_ALLOWLIST = Object.freeze([
  "agentPreset.list",
  "llm.models",
  "session.cancel",
  "session.create",
  "session.fork",
  "session.history",
  "session.list",
  "session.models",
  "session.prompt",
  "session.rename",
  "session.search",
  "session.selectModel",
  "settings.describe",
  "settings.update",
  "workspace.archiveSession",
  "workspace.create",
  "workspace.delete",
  "workspace.list",
])

const ALLOWED_METHODS = new Set(RPC_METHOD_ALLOWLIST)

const MAX_RPC_RESPONSE_BYTES = 8 * 1024 * 1024

/** 保留 DSH typed RPC error，供 mobile HTTP 层映射为可操作的状态码与提示。 */
export class LocalRpcError extends Error {
  constructor(method, error) {
    super(error?.message || (`RPC ${method} failed`))
    this.name = "LocalRpcError"
    this.method = method
    this.code = typeof error?.code === "string" && error.code ? error.code : "internal"
    this.details = error?.details && typeof error.details === "object" ? error.details : {}
  }
}

/** @type {{ invoke?: Function, stream?: Function } | null} */
let runtime = null

/** 由 host apply() 绑上 `ctx.typertGateway`，避免 /api 的浏览器 cookie 401。 */
export function bindLocalRpcRuntime(next) {
  runtime = next && typeof next === "object" ? next : null
}

export function unbindLocalRpcRuntime() {
  runtime = null
}

function mintRequestId() {
  return "mobile-" + randomBytes(12).toString("hex")
}

function asObject(value) {
  return value && typeof value === "object" && !Array.isArray(value) ? value : {}
}

function requestArgs(payload) {
  return { request: asObject(payload) }
}

function listRequestArgs(payload) {
  const src = asObject(payload)
  const _request = {}
  if (typeof src.cursor === "string" && src.cursor) _request.cursor = src.cursor
  return { _request }
}

function promptArgs(payload) {
  const src = asObject(payload)
  const request = {
    requestId: typeof src.requestId === "string" && src.requestId ? src.requestId : mintRequestId(),
    sessionId: src.sessionId,
    mode: src.mode === "steer" ? "steer" : "queue",
    content: Array.isArray(src.content) ? src.content : [],
  }
  if (typeof src.clientTimeZone === "string" && src.clientTimeZone) {
    request.clientTimeZone = src.clientTimeZone
  }
  return { request }
}

function settingsUpdateArgs(payload) {
  const src = asObject(payload)
  const args = {
    ns: src.ns,
    patch: asObject(src.patch),
  }
  if (Number.isInteger(src.expectedRevision)) args.expectedRevision = src.expectedRevision
  return args
}

/**
 * 插件面方法 → Typert Remote。session.history / workspace.list 在 callLocalRpc 里另走适配。
 * @type {Record<string, { namespace: string, method: string, args: (payload: object) => object }>}
 */
const WIRE = Object.freeze({
  "agentPreset.list": { namespace: "agentPresets", method: "list", args: () => ({}) },
  "llm.models": { namespace: "session", method: "modelCatalog", args: () => ({}) },
  "session.cancel": { namespace: "session", method: "cancel", args: requestArgs },
  "session.create": { namespace: "session", method: "create", args: requestArgs },
  "session.fork": { namespace: "session", method: "fork", args: requestArgs },
  "session.list": { namespace: "session", method: "list", args: listRequestArgs },
  "session.models": { namespace: "session", method: "modelCatalog", args: () => ({}) },
  "session.prompt": { namespace: "session", method: "prompt", args: promptArgs },
  "session.rename": { namespace: "session", method: "rename", args: requestArgs },
  "session.search": { namespace: "session", method: "search", args: requestArgs },
  "session.selectModel": { namespace: "session", method: "selectModel", args: requestArgs },
  "settings.describe": { namespace: "settings", method: "describe", args: () => ({}) },
  "settings.update": { namespace: "settings", method: "update", args: settingsUpdateArgs },
  "workspace.archiveSession": { namespace: "workspace", method: "archiveSession", args: requestArgs },
  "workspace.create": { namespace: "workspace", method: "create", args: requestArgs },
  "workspace.delete": { namespace: "workspace", method: "delete", args: requestArgs },
})

export function wireEndpoint(method) {
  const spec = WIRE[method]
  if (!spec) return null
  return `${spec.namespace}/${spec.method}`
}

function toLocalRpcError(method, error) {
  if (error instanceof LocalRpcError) return error
  const details = error?.details && typeof error.details === "object" ? error.details : {}
  return new LocalRpcError(method, {
    code: typeof error?.code === "string" && error.code ? error.code : "internal",
    message: error?.message || `RPC ${method} failed`,
    details,
  })
}

export function historyRecordsToEvents(records) {
  if (!Array.isArray(records)) return []
  const events = []
  for (const record of records) {
    if (record?.event) events.push({ event: record.event })
  }
  return events
}

function adaptModelCatalog(catalog, current) {
  const src = catalog && typeof catalog === "object" ? catalog : {}
  return {
    current: current ?? src.default ?? null,
    default: src.default ?? null,
    groups: src.groups ?? [],
    failures: src.failures ?? [],
    routableProviders: src.routableProviders ?? [],
  }
}

async function firstStreamValue(request) {
  if (typeof runtime?.stream !== "function") {
    throw new Error("stream Remote methods require typertGateway")
  }
  const ac = new AbortController()
  try {
    const iter = await runtime.stream({ ...request, signal: request.signal ?? ac.signal })
    const first = await iter.next()
    return first.value
  } finally {
    ac.abort()
  }
}

function postHttpRpc(targetPort, endpoint, args, pluginMethod) {
  return new Promise((resolve, reject) => {
    const rpcId = "mobile-" + randomBytes(12).toString("hex")
    const body = Buffer.from(JSON.stringify({
      type: "client-request",
      rpcId,
      method: endpoint,
      payload: { args },
    }))
    const request = httpRequest(
      {
        host: "127.0.0.1",
        port: targetPort,
        method: "POST",
        path: "/api/" + endpoint,
        headers: {
          host: "127.0.0.1:" + targetPort,
          origin: "http://127.0.0.1:" + targetPort,
          "content-type": "application/json",
          "content-length": String(body.length),
        },
      },
      (response) => {
        const chunks = []
        let received = 0
        response.on("data", (chunk) => {
          received += chunk.length
          if (received > MAX_RPC_RESPONSE_BYTES) {
            request.destroy(new Error("RPC " + pluginMethod + " response too large"))
            return
          }
          chunks.push(chunk)
        })
        response.on("end", () => {
          try {
            if (response.statusCode === 401) {
              reject(new LocalRpcError(pluginMethod, {
                code: "gateway/unauthorized",
                message: "DSH /api requires browser session cookie; bind typertGateway instead",
              }))
              return
            }
            const frame = JSON.parse(Buffer.concat(chunks).toString("utf8"))
            if (frame?.result?.ok) return resolve(frame.result.value)
            const error = frame?.result?.error
            reject(new LocalRpcError(pluginMethod, error))
          } catch (error) {
            reject(error)
          }
        })
      },
    )
    request.setTimeout(25_000, () => request.destroy(new Error("RPC " + pluginMethod + " timed out")))
    request.on("error", reject)
    request.end(body)
  })
}

async function invokeMapped(targetPort, pluginMethod, payload) {
  const spec = WIRE[pluginMethod]
  if (!spec) throw new Error(`RPC method not mapped: ${pluginMethod}`)
  const args = spec.args(payload ?? {})
  if (typeof runtime?.invoke === "function") {
    try {
      return await runtime.invoke({
        namespace: spec.namespace,
        method: spec.method,
        args,
      })
    } catch (error) {
      throw toLocalRpcError(pluginMethod, error)
    }
  }
  return postHttpRpc(targetPort, `${spec.namespace}/${spec.method}`, args, pluginMethod)
}

async function sessionThroughSeq(targetPort, sessionId) {
  const list = await invokeMapped(targetPort, "session.list", {})
  const item = (list?.items ?? []).find((row) => row?.sessionId === sessionId)
  const asOf = item?.projections?.asOfSeq
  return {
    throughSeq: Number.isInteger(asOf) ? asOf : -1,
    projections: item?.projections ?? null,
  }
}

async function followHistorySnapshot(sessionId, maxMessages) {
  const snapshot = await firstStreamValue({
    namespace: "session",
    method: "follow",
    args: {
      request: {
        address: { kind: "session", sessionId },
        ...(maxMessages !== undefined ? { maxMessages } : {}),
      },
    },
  })
  if (snapshot?.type !== "snapshot") return null
  return {
    events: historyRecordsToEvents(snapshot.records),
    hasMore: Boolean(snapshot.hasMore),
    projections: snapshot.projections ?? null,
  }
}

async function adaptSessionHistory(targetPort, payload) {
  const src = asObject(payload)
  const sessionId = src.sessionId
  if (!sessionId) {
    throw new LocalRpcError("session.history", { code: "internal", message: "session.history requires sessionId" })
  }
  const maxMessages = Number.isInteger(src.maxMessages) ? src.maxMessages : undefined
  const beforeSeq = Number.isInteger(src.beforeSeq) ? src.beforeSeq : undefined
  // 手机打开历史尾页（无 beforeSeq / maxMessages）才走 follow 快照。
  // SSE 轮询与补洞都会带 maxMessages，必须走 page，否则每秒都会开一条 follow 再 abort。
  const isUnboundedTail = beforeSeq === undefined && maxMessages === undefined

  if (isUnboundedTail && typeof runtime?.stream === "function") {
    try {
      const snapshot = await followHistorySnapshot(sessionId, maxMessages)
      if (snapshot) return snapshot
    } catch {
      // follow 失败时回退 page（例如 AbortError 竞态）；list+page 仍能给出一页
    }
  }

  const hint = Number.isInteger(src.throughSeq)
    ? { throughSeq: src.throughSeq, projections: null }
    : await sessionThroughSeq(targetPort, sessionId)

  // list 没有 asOfSeq 时 page(throughSeq=-1) 是空页；冷会话或未入列表时再试一次 follow。
  if (hint.throughSeq < 0 && typeof runtime?.stream === "function") {
    try {
      const snapshot = await followHistorySnapshot(sessionId, maxMessages)
      if (snapshot) return snapshot
    } catch {
      // 仍回退 page；空页比抛错更接近旧 session.history 语义
    }
  }

  const page = await (async () => {
    const args = {
      request: {
        address: { kind: "session", sessionId },
        throughSeq: hint.throughSeq,
        ...(beforeSeq !== undefined ? { beforeSeq } : {}),
        ...(maxMessages !== undefined ? { maxMessages } : {}),
      },
    }
    if (typeof runtime?.invoke === "function") {
      try {
        return await runtime.invoke({ namespace: "session", method: "page", args })
      } catch (error) {
        throw toLocalRpcError("session.history", error)
      }
    }
    return postHttpRpc(targetPort, "session/page", args, "session.history")
  })()

  return {
    events: historyRecordsToEvents(page?.records),
    hasMore: Boolean(page?.hasMore),
    projections: hint.projections,
  }
}

async function adaptWorkspaceList(targetPort) {
  if (typeof runtime?.stream === "function") {
    try {
      const frame = await firstStreamValue({
        namespace: "workspace",
        method: "follow",
        args: {},
      })
      if (frame?.type === "baseline") {
        return { items: frame.value?.items ?? [] }
      }
      return { items: [] }
    } catch (error) {
      throw toLocalRpcError("workspace.list", error)
    }
  }
  // 单测假 HTTP 服务没有 stream；仍发一条可观察的 unary，便于路径锁定。
  return postHttpRpc(targetPort, "workspace/follow", {}, "workspace.list")
}

export function callLocalRpc(targetPort, method, payload) {
  if (!ALLOWED_METHODS.has(method)) {
    return Promise.reject(new Error(`RPC method not allowlisted: ${method}`))
  }
  return (async () => {
    if (method === "session.history") return adaptSessionHistory(targetPort, payload)
    if (method === "workspace.list") return adaptWorkspaceList(targetPort)
    const value = await invokeMapped(targetPort, method, payload)
    if (method === "session.models" || method === "llm.models") {
      let current = value?.default ?? null
      const sessionId = payload?.sessionId
      if (method === "session.models" && sessionId) {
        try {
          const list = await invokeMapped(targetPort, "session.list", {})
          const item = (list?.items ?? []).find((row) => row?.sessionId === sessionId)
          current = item?.projections?.values?.modelSelection?.next ?? current
        } catch {
          // 目录本身仍可用
        }
      }
      return adaptModelCatalog(value, current)
    }
    return value
  })()
}
