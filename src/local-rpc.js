/**
 * 向本机 dsh /api 发 RPC 的唯一通道。
 *
 * 刻意把 Host/Origin 写成回环：dsh 的 PRIVILEGED_METHODS 只认
 * isTrustedApiRequest(req, [])。方法名是写死的闭集（RPC_METHOD_ALLOWLIST），
 * callLocalRpc 在发出请求前强制校验——即使未来某个调用点把用户输入接进
 * method，18640 也不会变成开放转发。依赖 dsh 内部 API，升级时需复查。
 */
import { request as httpRequest } from "node:http"
import { randomBytes } from "node:crypto"

/** 本插件允许代调的 dsh RPC 方法闭集。新增方法：先加这里，再加调用点。 */
export const RPC_METHOD_ALLOWLIST = Object.freeze([
  "agentPreset.list",
  "llm.balance",
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

export function callLocalRpc(targetPort, method, payload) {
  if (!ALLOWED_METHODS.has(method)) {
    return Promise.reject(new Error(`RPC method not allowlisted: ${method}`))
  }
  return new Promise((resolve, reject) => {
    const rpcId = "mobile-" + randomBytes(12).toString("hex")
    const body = Buffer.from(JSON.stringify({ type: "client-request", rpcId, method, payload }))
    const request = httpRequest(
      {
        host: "127.0.0.1",
        port: targetPort,
        method: "POST",
        path: "/api/" + method,
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
            request.destroy(new Error("RPC " + method + " response too large"))
            return
          }
          chunks.push(chunk)
        })
        response.on("end", () => {
          try {
            const frame = JSON.parse(Buffer.concat(chunks).toString("utf8"))
            if (frame?.result?.ok) return resolve(frame.result.value)
            const error = frame?.result?.error
            reject(new Error(error?.message || ("RPC " + method + " failed")))
          } catch (error) {
            reject(error)
          }
        })
      },
    )
    request.setTimeout(25_000, () => request.destroy(new Error("RPC " + method + " timed out")))
    request.on("error", reject)
    request.end(body)
  })
}
