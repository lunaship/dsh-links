/**
 * 本机 RPC 白名单锁定：18640 上的 mobile API 只能触达 RPC_METHOD_ALLOWLIST
 * 闭集，防止未来重构把用户输入接进 method 变成开放转发。
 */
import { test } from "node:test"
import assert from "node:assert/strict"
import { createServer } from "node:http"
import { readFileSync, readdirSync } from "node:fs"
import { dirname, join } from "node:path"
import { fileURLToPath } from "node:url"
import { LocalRpcError, RPC_METHOD_ALLOWLIST, bindLocalRpcRuntime, callLocalRpc, unbindLocalRpcRuntime, wireEndpoint } from "../src/local-rpc.js"

const ROOT = dirname(dirname(fileURLToPath(import.meta.url)))

test("白名单是预期闭集（增删方法需同步本测试）", () => {
  assert.deepEqual([...RPC_METHOD_ALLOWLIST], [
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
})

test("白名单方法正常转发到 127.0.0.1 目标端口", async () => {
  const hits = []
  const bodies = []
  const srv = createServer((req, res) => {
    hits.push(req.url)
    let body = ""
    req.on("data", (c) => { body += c })
    req.on("end", () => {
      const frame = JSON.parse(body)
      bodies.push(frame)
      res.writeHead(200, { "content-type": "application/json" })
      res.end(JSON.stringify({ result: { ok: true, value: { echo: frame.method } } }))
    })
  })
  await new Promise((resolve) => srv.listen(0, "127.0.0.1", resolve))
  try {
    const value = await callLocalRpc(srv.address().port, "session.list", {})
    assert.deepEqual(value, { echo: "session/list" })
    assert.deepEqual(hits, ["/api/session/list"])
    assert.equal(bodies[0].method, "session/list")
    assert.deepEqual(bodies[0].payload, { args: { _request: {} } })
    assert.equal(wireEndpoint("session.list"), "session/list")
  } finally {
    srv.close()
  }
})

test("session.history 经 session/list + session/page，并把 records 投影为 events", async () => {
  const hits = []
  const srv = createServer((req, res) => {
    hits.push(req.url)
    let body = ""
    req.on("data", (c) => { body += c })
    req.on("end", () => {
      const frame = JSON.parse(body)
      let value = {}
      if (frame.method === "session/list") {
        value = {
          items: [{
            sessionId: "s1",
            projections: { asOfSeq: 3, values: { title: "hi" } },
          }],
        }
      } else if (frame.method === "session/page") {
        assert.equal(frame.payload.args.request.throughSeq, 3)
        assert.equal(frame.payload.args.request.address.sessionId, "s1")
        value = {
          records: [{ type: "event", event: { seq: 3, type: "user/message", time: 1, data: {} } }],
          hasMore: false,
        }
      }
      res.writeHead(200, { "content-type": "application/json" })
      res.end(JSON.stringify({ result: { ok: true, value } }))
    })
  })
  await new Promise((resolve) => srv.listen(0, "127.0.0.1", resolve))
  try {
    const value = await callLocalRpc(srv.address().port, "session.history", { sessionId: "s1", maxMessages: 50 })
    assert.equal(value.hasMore, false)
    assert.equal(value.events[0].event.seq, 3)
    assert.equal(value.projections.asOfSeq, 3)
    assert.deepEqual(hits, ["/api/session/list", "/api/session/page"])
  } finally {
    srv.close()
  }
})

test("带 maxMessages 的 history 不走 follow（SSE 轮询路径）", async () => {
  let streamHits = 0
  const invokes = []
  bindLocalRpcRuntime({
    invoke: async ({ namespace, method, args }) => {
      invokes.push(`${namespace}/${method}`)
      if (method === "list") {
        return { items: [{ sessionId: "s1", projections: { asOfSeq: 4 } }] }
      }
      if (method === "page") {
        assert.equal(args.request.throughSeq, 4)
        return {
          records: [{ event: { seq: 4, type: "user/message", time: 1, data: {} } }],
          hasMore: false,
        }
      }
      throw new Error(`unexpected invoke ${namespace}/${method}`)
    },
    stream: async () => {
      streamHits++
      throw new Error("poll path must not open follow")
    },
  })
  try {
    const value = await callLocalRpc(1, "session.history", { sessionId: "s1", maxMessages: 50 })
    assert.equal(value.events[0].event.seq, 4)
    assert.deepEqual(invokes, ["session/list", "session/page"])
    assert.equal(streamHits, 0)
  } finally {
    unbindLocalRpcRuntime()
  }
})

test("无 maxMessages 的 history 尾页走 follow 快照", async () => {
  let invokeHits = 0
  bindLocalRpcRuntime({
    invoke: async () => {
      invokeHits++
      throw new Error("unbounded tail should not invoke list/page")
    },
    stream: async ({ namespace, method }) => {
      assert.equal(namespace, "session")
      assert.equal(method, "follow")
      return {
        next: async () => ({
          value: {
            type: "snapshot",
            records: [{ event: { seq: 9, type: "user/message", time: 1, data: {} } }],
            hasMore: false,
            projections: { asOfSeq: 9 },
          },
        }),
      }
    },
  })
  try {
    const value = await callLocalRpc(1, "session.history", { sessionId: "s1" })
    assert.equal(value.events[0].event.seq, 9)
    assert.equal(value.projections.asOfSeq, 9)
    assert.equal(invokeHits, 0)
  } finally {
    unbindLocalRpcRuntime()
  }
})

test("list 没有 asOfSeq 时 history 回退 follow，避免 page(-1) 空页", async () => {
  const invokes = []
  bindLocalRpcRuntime({
    invoke: async ({ namespace, method }) => {
      invokes.push(`${namespace}/${method}`)
      if (method === "list") return { items: [{ sessionId: "other" }] }
      throw new Error(`unexpected invoke ${namespace}/${method}`)
    },
    stream: async () => ({
      next: async () => ({
        value: {
          type: "snapshot",
          records: [{ event: { seq: 2, type: "user/message", time: 1, data: {} } }],
          hasMore: false,
        },
      }),
    }),
  })
  try {
    const value = await callLocalRpc(1, "session.history", { sessionId: "s1", maxMessages: 50 })
    assert.equal(value.events[0].event.seq, 2)
    assert.deepEqual(invokes, ["session/list"])
  } finally {
    unbindLocalRpcRuntime()
  }
})

test("in-process typertGateway 优先于 HTTP，避免 /api cookie 401", async () => {
  let httpHits = 0
  const srv = createServer((_req, res) => {
    httpHits++
    res.writeHead(401)
    res.end("unauthorized")
  })
  await new Promise((resolve) => srv.listen(0, "127.0.0.1", resolve))
  bindLocalRpcRuntime({
    invoke: async ({ namespace, method, args }) => {
      assert.equal(namespace, "session")
      assert.equal(method, "list")
      assert.deepEqual(args, { _request: {} })
      return { items: [{ sessionId: "gw" }] }
    },
  })
  try {
    const value = await callLocalRpc(srv.address().port, "session.list", {})
    assert.deepEqual(value, { items: [{ sessionId: "gw" }] })
    assert.equal(httpHits, 0)
  } finally {
    unbindLocalRpcRuntime()
    srv.close()
  }
})

test("白名单之外的方法在发出 HTTP 请求前即被拒绝", async () => {
  let hit = 0
  const srv = createServer((req, res) => {
    hit++
    res.writeHead(200, { "content-type": "application/json" })
    res.end(JSON.stringify({ result: { ok: true, value: {} } }))
  })
  await new Promise((resolve) => srv.listen(0, "127.0.0.1", resolve))
  try {
    for (const method of ["session.delete", "settings.reset", "evil/exec", ""]) {
      await assert.rejects(
        () => callLocalRpc(srv.address().port, method, {}),
        /not allowlisted/,
        method,
      )
    }
    assert.equal(hit, 0, "拒绝必须发生在任何 HTTP 请求之前")
  } finally {
    srv.close()
  }
})

test("typed RPC 错误保留 code、message 与 details", async () => {
  const srv = createServer((req, res) => {
    req.resume()
    req.on("end", () => {
      res.writeHead(200, { "content-type": "application/json" })
      res.end(JSON.stringify({
        result: {
          ok: false,
          error: {
            code: "workspace-invalid-path",
            message: "directory does not exist",
            details: { path: "/missing" },
          },
        },
      }))
    })
  })
  await new Promise((resolve) => srv.listen(0, "127.0.0.1", resolve))
  try {
    await assert.rejects(
      () => callLocalRpc(srv.address().port, "workspace.create", { path: "/missing" }),
      (error) => {
        assert.ok(error instanceof LocalRpcError)
        assert.equal(error.method, "workspace.create")
        assert.equal(error.code, "workspace-invalid-path")
        assert.equal(error.message, "directory does not exist")
        assert.deepEqual(error.details, { path: "/missing" })
        return true
      },
    )
  } finally {
    srv.close()
  }
})

test("src/ 内所有 callLocalRpc 调用点的方法字面量都在白名单内", () => {
  const offenders = []
  for (const file of readdirSync(join(ROOT, "src"))) {
    if (!file.endsWith(".js")) continue
    const text = readFileSync(join(ROOT, "src", file), "utf8")
    for (const match of text.matchAll(/callLocalRpc\([^,)]+,\s*["']([^"']+)["']/g)) {
      if (!RPC_METHOD_ALLOWLIST.includes(match[1])) {
        offenders.push(`${file}: ${match[1]}`)
      }
    }
  }
  assert.deepEqual(offenders, [], "新增 RPC 方法必须先登记进 RPC_METHOD_ALLOWLIST")
})
