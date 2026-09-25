import { readFileSync, statSync } from "node:fs"
import { readFile as readFileAsync } from "node:fs/promises"
import { join } from "node:path"
import { randomBytes } from "node:crypto"
import { homedir, hostname } from "node:os"
import { callLocalRpc, LocalRpcError } from "./local-rpc.js"
import { mobileSessionSummary } from "./mobile-session-summary.js"
import { pluginCapabilities, PLUGIN_PROTOCOL } from "./protocol-caps.js"
import { workspaceChangesService, parseChangesCoordinates, projectChangesSummary, projectFileDiff } from "./workspace-changes.js"
import { relayPairSnapshot } from "./relay/crypto.js"
import { clampHistoryMaxMessages, projectHistoryPage } from "./history.js"
import { mimeFromName, resolveWorkspaceFile } from "./workspace-file.js"
import { optionalString, omitNullFields } from "./optional-string.js"
import { MobileWorkspaceCreateError, planMobileWorkspaceCreate, ensureMobileWorkspaceDirectory } from "./workspace-create.js"
import { normalizeQuestions, validateAnswers } from "./question-answers.js"
import { canDeviceHandle, requestBelongsToSession, mapApprovalUiStatus } from "./request-lifecycle.js"
import { resolveSessionLogPath } from "./session-log-path.js"
import { reasoningBlocksFromSessionLog } from "./session-log-reasoning.js"
import { decompressZstdFrames } from "./zstd-frames.js"

const MAX_ZSTD_OUTPUT_BYTES = 32 * 1024 * 1024
const PROMPT_BODY_LIMIT = 16 * 1024 * 1024
const PROMPT_IMAGE_DATA_LIMIT = 4 * 1024 * 1024
const PROMPT_IMAGE_MEDIA_TYPES = new Set(["image/png", "image/jpeg", "image/jpg", "image/webp", "image/gif"])

function pageNeedsReasoningFile(events) {
  return (events ?? []).some((item) => {
    const c = item?.event?.data?.chunk
    return c?.type === "block-end" && c.block?.type === "reasoning" && !String(c.block?.text ?? "")
  })
}

async function readSessionReasoning(targetPort, sessionId, rt) {
  try {
    const list = await callLocalRpc(targetPort, "session.list", {})
    const row = (list.items ?? []).find((s) => s.sessionId === sessionId)
    const cwd = row?.cwd
    if (!cwd) return new Map()
    const enc = "--" + cwd.split("/").filter(Boolean).join("-") + "--"
    const p = resolveSessionLogPath(join(homedir(), ".dsh", "sessions", enc, sessionId))
    if (!p) return new Map()
    let st
    try { st = statSync(p) } catch { return new Map() }
    if (st.size > 32 * 1024 * 1024) return new Map()
    const hit = rt?.reasoningCache?.get(p)
    if (hit && hit.mtime === st.mtimeMs && hit.size === st.size) return hit.map
    const text = await readSessionLogText(p)
    const reasoning = reasoningBlocksFromSessionLog(text)
    rt?.reasoningCache?.set(p, { mtime: st.mtimeMs, size: st.size, map: reasoning })
    return reasoning
  } catch (err) {
    return new Map()
  }
}

function mapModelGroups(groups) {
  return (groups ?? []).map((g) => ({
    provider: g.id || g.provider || g.name || "未知",
    providerName: g.name || g.id || g.provider || "未知",
    models: (g.models ?? []).map((m) => ({
      id: m.id,
      name: m.name ?? m.id,
      contextWindow: m.contextWindow ?? null,
      maxTokens: m.maxTokens ?? null,
      reasoningEfforts: (m.reasoning?.efforts ?? [])
        .map((e) => (typeof e === "string" ? e : e.id))
        .filter(Boolean),
      defaultEffort: m.reasoning?.defaultEffort ?? null,
    })),
  }))
}

function uniqueRpcPayloads(payloads) {
  const seen = new Set()
  const out = []
  for (const payload of payloads) {
    if (!payload) continue
    const key = JSON.stringify(payload)
    if (seen.has(key)) continue
    seen.add(key)
    out.push(payload)
  }
  return out
}

async function readSessionLogText(path) {
  const raw = await readFileAsync(path)
  if (!path.endsWith(".zstd")) return raw.toString("utf8")
  return (await decompressZstdFrames(raw, { maxOutputBytes: MAX_ZSTD_OUTPUT_BYTES })).toString("utf8")
}

async function mobileSessionList(targetPort) {
  const [sessions, workspace] = await Promise.all([
    callLocalRpc(targetPort, "session.list", {}),
    callLocalRpc(targetPort, "workspace.list", {}),
  ])
  const archivedSessionIds = normalizeArchivedSessionIds(workspace.archivedSessionIds)
  return {
    // Keep archived rows in the transport for Settings restore. The App owns
    // the visible projection after applying this authoritative set.
    items: sessions.items ?? [],
    archivedSessionIds,
  }
}

function normalizeArchivedSessionIds(ids) {
  return [...new Set((Array.isArray(ids) ? ids : [])
    .map((id) => String(id ?? "").trim())
    .filter(Boolean))]
}

function filterArchivedMobileSearchItems(items, archivedSessionIds) {
  const archived = new Set(normalizeArchivedSessionIds(archivedSessionIds))
  return (Array.isArray(items) ? items : [])
    .filter((item) => !archived.has(String(item?.sessionId ?? "").trim()))
}

export async function handleMobileApi(req, res, targetPort, state, stateFile, device, pathname, rt, config, logger, deps) {
  const {
    json,
    requireJsonWrite,
    readAuthorizedJson,
    validateSessionCreateWorkspace,
    runMobileDeviceMutation,
    mobileMutationWasRevoked,
    respondDeviceRevoked,
    isDeviceAuthorized,
    isDeviceSubscribedToSession,
    revokeDeviceEntry,
    filterSettingsPatch,
    publicDevice,
  } = deps
  try {
    if (req.method !== "GET" && req.method !== "HEAD") {
      if (!requireJsonWrite(req, res)) return
    }
    if (req.method === "GET" && pathname === "/dsh-link/mobile/bootstrap") {
      const { items, archivedSessionIds } = await mobileSessionList(targetPort)
      const sessions = items.map(mobileSessionSummary)
      return json(res, 200, {
        version: 1,
        protocol: PLUGIN_PROTOCOL,
        capabilities: pluginCapabilities({ changes: Boolean(workspaceChangesService(rt.workspaceChanges)) }),
        host: { name: hostname(), deviceId: state.deviceId },
        device: { name: device.name },
        sessions,
        archivedSessionIds,
        webPath: "/",
        relay: relayPairSnapshot(state.relay),
      })
    }

    if (req.method === "GET" && pathname === "/dsh-link/mobile/sessions") {
      const { items, archivedSessionIds } = await mobileSessionList(targetPort)
      return json(res, 200, {
        version: 1,
        sessions: items.map(mobileSessionSummary),
        archivedSessionIds,
      })
    }

    if (req.method === "GET" && pathname === "/dsh-link/mobile/sessions/search") {
      const query = String(new URL(req.url ?? "/", "http://x").searchParams.get("q") ?? "").trim()
      if (!query) return json(res, 400, { error: "缺少搜索关键词" })
      try {
        const value = await callLocalRpc(targetPort, "session.search", { query })
        const workspace = await callLocalRpc(targetPort, "workspace.list", {})
        const items = filterArchivedMobileSearchItems(value.items, workspace.archivedSessionIds)
        return json(res, 200, { version: 1, items, hasMore: Boolean(value.hasMore), degraded: false })
      } catch (err) {
        // 内容搜索不可用（索引禁用）时降级为名称匹配（DSH search.unavailable 行为）
        const { items: listItems, archivedSessionIds } = await mobileSessionList(targetPort)
        const withTitle = listItems.map((s) => ({ ...s, title: mobileSessionSummary(s).title }))
        const items = withTitle
          .filter((s) => String(s.title ?? "").toLowerCase().includes(query.toLowerCase()))
          .slice(0, 20)
          .map((s) => ({ sessionId: s.sessionId, snippet: s.title ?? "" }))
        return json(res, 200, {
          version: 1,
          items: filterArchivedMobileSearchItems(items, archivedSessionIds),
          hasMore: false,
          degraded: true,
        })
      }
    }

    if (req.method === "POST" && pathname === "/dsh-link/mobile/sessions") {
      const body = await readAuthorizedJson(req, res, state, device)
  if (!body) return
      const requestedWorkspaceId = typeof body.workspaceId === "string" && body.workspaceId.trim()
        ? body.workspaceId.trim()
        : ""
      const requestedCwd = typeof body.cwd === "string" && body.cwd.trim() ? body.cwd.trim() : ""
      const payload = {}
      if (requestedWorkspaceId || requestedCwd) {
        // cwd/workspaceId 必须落在当前已注册工作区内（不接受任意路径）；列表缺失/不可用时拒绝。
        let list
        try {
          list = await callLocalRpc(targetPort, "workspace.list", {})
        } catch (err) {
          return json(res, 503, { error: "无法获取已注册工作区列表，已拒绝创建会话" })
        }
        const items = list?.items
        if (!Array.isArray(items)) {
          return json(res, 503, { error: "无法解析已注册工作区列表，已拒绝创建会话" })
        }
        const checked = validateSessionCreateWorkspace({ cwd: requestedCwd, workspaceId: requestedWorkspaceId, workspaces: items })
        if (checked.error) return json(res, 400, { error: checked.error })
        if (requestedWorkspaceId) payload.workspaceId = requestedWorkspaceId
        else payload.cwd = checked.cwd
      }
      if (typeof body.agentPreset === "string" && body.agentPreset.trim()) payload.agentPreset = body.agentPreset.trim()
      const value = await runMobileDeviceMutation(rt, state, device, () =>
        callLocalRpc(targetPort, "session.create", payload))
      if (mobileMutationWasRevoked(value)) return respondDeviceRevoked(res)
      return json(res, 201, omitNullFields({
        version: 1,
        sessionId: value.sessionId,
        agentPreset: optionalString(value.agentPreset),
      }))
    }

    if (req.method === "GET" && pathname === "/dsh-link/mobile/models") {
      const sessionId = String(new URL(req.url ?? "/", "http://x").searchParams.get("sessionId") ?? "").trim()
      if (!sessionId) return json(res, 400, { error: "缺少 sessionId" })
      const value = await callLocalRpc(targetPort, "session.models", { sessionId })
      return json(res, 200, {
        version: 1,
        current: value.current ?? null,
        groups: mapModelGroups(value.groups),
        failures: value.failures ?? [],
      })
    }

    if (req.method === "GET" && pathname === "/dsh-link/mobile/llm-models") {
      const value = await callLocalRpc(targetPort, "llm.models", {})
      return json(res, 200, {
        version: 1,
        groups: mapModelGroups(value.groups),
        failures: value.failures ?? [],
      })
    }

    const modelMatch = pathname.match(/^\/dsh-link\/mobile\/sessions\/([^/]+)\/model$/)
    if (req.method === "POST" && modelMatch) {
      const sessionId = decodeURIComponent(modelMatch[1])
      const body = await readAuthorizedJson(req, res, state, device)
  if (!body) return
      const provider = String(body.provider ?? "").trim()
      const model = String(body.model ?? "").trim()
      if (!provider || !model) return json(res, 400, { error: "缺少 provider 或 model" })
      const value = await runMobileDeviceMutation(rt, state, device, () =>
        selectSessionModel(targetPort, sessionId, provider, model, body.reasoningEffort, () => isDeviceAuthorized(state, device)))
      if (mobileMutationWasRevoked(value)) return respondDeviceRevoked(res)
      return json(res, 200, { ok: true, selected: value.selected ?? null })
    }

    if (req.method === "GET" && pathname === "/dsh-link/mobile/workspaces") {
      const value = await callLocalRpc(targetPort, "workspace.list", {})
      return json(res, 200, { version: 1, workspaces: value.items ?? [], archivedSessionIds: value.archivedSessionIds ?? [] })
    }

    if (req.method === "POST" && pathname === "/dsh-link/mobile/workspaces") {
      const body = await readAuthorizedJson(req, res, state, device)
  if (!body) return
      try {
        const list = await callLocalRpc(targetPort, "workspace.list", {})
        const plan = planMobileWorkspaceCreate({
          input: body.input ?? body.path,
          parentWorkspaceId: body.parentWorkspaceId,
          workspaces: list.items ?? [],
        })
        const result = await runMobileDeviceMutation(rt, state, device, async () => {
          const { directoryCreated } = await ensureMobileWorkspaceDirectory(plan)
          const value = await callLocalRpc(targetPort, "workspace.create", { path: plan.path })
          return { directoryCreated, value }
        })
        if (mobileMutationWasRevoked(result)) return respondDeviceRevoked(res)
        const { directoryCreated, value } = result
        return json(res, 200, {
          ok: true,
          workspace: value.workspace ?? null,
          created: Boolean(value.created),
          directoryCreated,
          inputKind: plan.inputKind,
          resolvedPath: plan.path,
        })
      } catch (error) {
        if (error instanceof MobileWorkspaceCreateError) {
          return json(res, error.status, {
            error: error.message,
            code: error.code,
            details: error.details,
          })
        }
        if (error instanceof LocalRpcError && error.code === "workspace-invalid-path") {
          return json(res, 400, {
            error: error.message,
            code: error.code,
            details: error.details,
          })
        }
        throw error
      }
    }

    // 删除工作区（取消注册；会话日志不删除，DSH workspace.delete 语义）
    if (req.method === "POST" && pathname === "/dsh-link/mobile/workspaces/delete") {
      const body = await readAuthorizedJson(req, res, state, device)
  if (!body) return
      const path = String(body.path ?? "").trim()
      if (!path) return json(res, 400, { error: "缺少工作区路径" })
      const result = await runMobileDeviceMutation(rt, state, device, async () => {
        const list = await callLocalRpc(targetPort, "workspace.list", {})
        const item = (list.items ?? []).find((w) => w.path === path)
        if (!item) return { missing: true }
        const value = await callLocalRpc(targetPort, "workspace.delete", { workspaceId: item.workspaceId })
        return { item, value }
      })
      if (mobileMutationWasRevoked(result)) return respondDeviceRevoked(res)
      if (result.missing) return json(res, 404, { error: "工作区不存在" })
      const { item, value } = result
      return json(res, 200, { ok: true, deleted: Boolean(value?.deleted), workspaceId: item.workspaceId })
    }

    // 移动端设置（WI-004）：透传 DSH settings seam（loopback-only，由插件代调）。
    // describe 响应已由 seam 脱敏（role=secret 字段不携带值，只暴露 path+set）。
    if (req.method === "GET" && pathname === "/dsh-link/mobile/settings") {
      const value = await callLocalRpc(targetPort, "settings.describe", {})
      return json(res, 200, {
        version: 1,
        writable: Boolean(value.writable),
        namespaces: (value.namespaces ?? []).map((ns) => ({
          ns: ns.ns,
          value: ns.value ?? null,
          user: ns.user ?? null,
          applies: ns.applies ?? "restart",
          secrets: (ns.secrets ?? []).map((s) => ({ path: s.path ?? [], set: Boolean(s.set) })),
          revision: typeof ns.revision === "number" ? ns.revision : 0,
        })),
      })
    }

    if (req.method === "POST" && pathname === "/dsh-link/mobile/settings/update") {
      const body = await readAuthorizedJson(req, res, state, device)
  if (!body) return
      const ns = String(body.ns ?? "").trim()
      if (!ns) return json(res, 400, { error: "缺少命名空间" })
      const patch = body.patch
      if (typeof patch !== "object" || patch === null || Array.isArray(patch)) {
        return json(res, 400, { error: "patch 必须是对象" })
      }
      const filtered = filterSettingsPatch(ns, patch, config)
      if (filtered.error) return json(res, 403, { error: filtered.error })
      const payload = { ns, patch }
      if (Number.isInteger(body.expectedRevision)) payload.expectedRevision = body.expectedRevision
      const value = await runMobileDeviceMutation(rt, state, device, () =>
        callLocalRpc(targetPort, "settings.update", payload))
      if (mobileMutationWasRevoked(value)) return respondDeviceRevoked(res)
      const nsView = value
      return json(res, 200, {
        version: 1,
        ns: nsView.ns ?? ns,
        value: nsView.value ?? null,
        user: nsView.user ?? null,
        applies: nsView.applies ?? "restart",
        secrets: (nsView.secrets ?? []).map((s) => ({ path: s.path ?? [], set: Boolean(s.set) })),
        revision: typeof nsView.revision === "number" ? nsView.revision : 0,
      })
    }

    if (req.method === "GET" && pathname === "/dsh-link/mobile/agent-presets") {
      const value = await callLocalRpc(targetPort, "agentPreset.list", {})
      return json(res, 200, {
        version: 1,
        presets: (value.presets ?? []).map((p) => ({
          id: p.id,
          name: p.name ?? p.id,
          description: p.description ?? "",
          isDefault: Boolean(p.isDefault),
        })),
      })
    }

    if (req.method === "GET" && pathname === "/dsh-link/mobile/devices") {
      const devices = [...(state.devices ?? [])]
        .sort((a, b) => (b.lastSeenAt ?? 0) - (a.lastSeenAt ?? 0))
        .map(publicDevice)
      return json(res, 200, {
        version: 1,
        devices,
      })
    }

    if (req.method === "POST" && pathname === "/dsh-link/mobile/revoke") {
      const body = await readAuthorizedJson(req, res, state, device)
      if (!body) return
      const result = await revokeDeviceEntry(state, stateFile, rt, {
        name: body.name,
        deviceId: body.deviceId,
      }, req)
      if (result.status === 200 && logger) {
        logger.info(`dsh-links: device revoke device=${String(result.body?.deviceId ?? body.deviceId ?? "").slice(0, 8)}`)
      }
      return json(res, result.status, result.body)
    }

    const renameMatch = pathname.match(/^\/dsh-link\/mobile\/sessions\/([^/]+)\/rename$/)
    if (req.method === "POST" && renameMatch) {
      const sessionId = decodeURIComponent(renameMatch[1])
      const body = await readAuthorizedJson(req, res, state, device)
  if (!body) return
      const title = String(body.title ?? "").trim()
      if (!title) return json(res, 400, { error: "缺少会话名称" })
      const value = await runMobileDeviceMutation(rt, state, device, () =>
        callLocalRpc(targetPort, "session.rename", { sessionId, title }))
      if (mobileMutationWasRevoked(value)) return respondDeviceRevoked(res)
      return json(res, 200, { ok: true, title: value.title ?? title })
    }

    const forkMatch = pathname.match(/^\/dsh-link\/mobile\/sessions\/([^/]+)\/fork$/)
    if (req.method === "POST" && forkMatch) {
      const sessionId = decodeURIComponent(forkMatch[1])
      const value = await runMobileDeviceMutation(rt, state, device, () =>
        callLocalRpc(targetPort, "session.fork", { sessionId }))
      if (mobileMutationWasRevoked(value)) return respondDeviceRevoked(res)
      return json(res, 200, { ok: true, sessionId: value.sessionId ?? null })
    }

    // 删除会话（服务端归档，workspace.archiveSession 语义；对标 web 删除）
    const archiveMatch = pathname.match(/^\/dsh-link\/mobile\/sessions\/([^/]+)\/archive$/)
    if (req.method === "POST" && archiveMatch) {
      const sessionId = decodeURIComponent(archiveMatch[1])
      const value = await runMobileDeviceMutation(rt, state, device, () =>
        callLocalRpc(targetPort, "workspace.archiveSession", { sessionId }))
      if (mobileMutationWasRevoked(value)) return respondDeviceRevoked(res)
      return json(res, 200, { ok: true, archived: Boolean(value?.ok), sessionId })
    }

    const approvalMatch = pathname.match(/^\/dsh-link\/mobile\/sessions\/([^/]+)\/approval$/)
    if (req.method === "POST" && approvalMatch) {
      const sessionId = decodeURIComponent(approvalMatch[1])
      const body = await readAuthorizedJson(req, res, state, device)
  if (!body) return
      const approvalId = String(body.approvalId ?? "").trim()
      const outcome = String(body.outcome ?? "").trim()
      if (!approvalId || !["allowed-once", "rejected"].includes(outcome)) {
        return json(res, 400, { error: "缺少 approvalId 或 outcome 无效" })
      }
      const terminal = rt.requests.getTerminal(approvalId)
      if (terminal?.type === "approval") {
        if (!requestBelongsToSession(terminal, sessionId)) {
          return json(res, 409, { ok: false, accepted: false, error: "审批会话不匹配" })
        }
        return json(res, 200, {
          ok: true,
          accepted: true,
          alreadySettled: true,
          outcome: terminal.outcome,
          status: terminal.status,
          handledBy: "plugin",
        })
      }
      const pending = rt.requests.getApproval(approvalId)
      if (!pending) {
        return json(res, 409, { ok: false, accepted: false, error: "审批已结束或不存在" })
      }
      if (pending.sessionId !== sessionId) {
        return json(res, 409, { ok: false, accepted: false, error: "审批会话不匹配" })
      }
      if (!canDeviceHandle(pending, {
        deviceId: device.deviceId,
        authorized: true,
        subscribed: isDeviceSubscribedToSession(rt, pending.sessionId, device.deviceId),
        inGrace: rt.requests.inGrace(pending.sessionId),
      })) {
        return json(res, 403, { ok: false, accepted: false, error: "仅该会话的当前连接设备可处理审批" })
      }
      const result = await runMobileDeviceMutation(rt, state, device, () =>
        rt.requests.finishApproval(pending, outcome))
      if (mobileMutationWasRevoked(result)) return respondDeviceRevoked(res)
      return json(res, 200, {
        ok: true,
        accepted: true,
        outcome: result?.outcome ?? outcome,
        status: result?.status ?? mapApprovalUiStatus(outcome),
        handledBy: "plugin",
      })
    }

    const questionMatch = pathname.match(/^\/dsh-link\/mobile\/sessions\/([^/]+)\/question$/)
    if (req.method === "POST" && questionMatch) {
      const sessionId = decodeURIComponent(questionMatch[1])
      const body = await readAuthorizedJson(req, res, state, device)
      if (!body) return
      const rpcId = String(body.rpcId ?? "").trim()
      const answer = body.answer
      if (!rpcId || !answer || !Array.isArray(answer.answers)) {
        return json(res, 400, { error: "缺少 rpcId 或 answer.answers", code: "invalid-answers" })
      }
      const terminal = rt.requests.getTerminal(rpcId)
      if (terminal?.type === "question") {
        if (!requestBelongsToSession(terminal, sessionId)) {
          return json(res, 409, { ok: false, accepted: false, error: "澄清会话不匹配" })
        }
        return json(res, 200, {
          ok: true,
          accepted: true,
          alreadySettled: true,
          status: terminal.status,
          handledBy: "plugin",
        })
      }
      const pending = rt.requests.getQuestion(rpcId)
      if (!pending) {
        return json(res, 409, { ok: false, accepted: false, error: "澄清已结束或不存在" })
      }
      if (pending.sessionId !== sessionId) {
        return json(res, 409, { ok: false, accepted: false, error: "澄清会话不匹配" })
      }
      if (!canDeviceHandle(pending, {
        deviceId: device.deviceId,
        authorized: true,
        subscribed: isDeviceSubscribedToSession(rt, pending.sessionId, device.deviceId),
        inGrace: rt.requests.inGrace(pending.sessionId),
      })) {
        return json(res, 403, { ok: false, accepted: false, error: "仅该会话的当前连接设备可回答澄清" })
      }
      const normalized = normalizeQuestions(pending.questions ?? [])
      if (!normalized.ok) {
        return json(res, 400, { ok: false, accepted: false, error: "原问题定义无效", code: normalized.error })
      }
      const checked = validateAnswers(normalized.questions, answer)
      if (!checked.ok) {
        return json(res, 400, { ok: false, accepted: false, error: checked.error, code: checked.code })
      }
      const result = await runMobileDeviceMutation(rt, state, device, () =>
        rt.requests.finishQuestion(pending, checked.answer))
      if (mobileMutationWasRevoked(result)) return respondDeviceRevoked(res)
      return json(res, 200, { ok: true, accepted: true, handledBy: "plugin" })
    }

    const requestsMatch = pathname.match(/^\/dsh-link\/mobile\/sessions\/([^/]+)\/requests$/)
    if (req.method === "GET" && requestsMatch) {
      const sessionId = decodeURIComponent(requestsMatch[1])
      const subscribed = isDeviceSubscribedToSession(rt, sessionId, device.deviceId)
      const eligible = rt.requests.pendingForSession(sessionId).some((rec) => rec.eligibleDeviceIds.has(device.deviceId))
      if (!subscribed && !(rt.requests.inGrace(sessionId) && eligible)) {
        return json(res, 403, { error: "仅该会话的当前连接设备可查看待处理请求" })
      }
      return json(res, 200, { version: 1, ...rt.requests.snapshot(sessionId) })
    }

    const fileMatch = pathname.match(/^\/dsh-link\/mobile\/sessions\/([^/]+)\/file$/)
    if (req.method === "GET" && fileMatch) {
      const sessionId = decodeURIComponent(fileMatch[1])
      // 只有正在查看该会话（持有活跃 SSE 订阅）的设备才能下载其文件。
      if (!isDeviceSubscribedToSession(rt, sessionId, device.deviceId)) {
        return json(res, 403, { error: "仅正在查看该会话的设备可下载文件" })
      }
      const requested = String(new URL(req.url ?? "/", "http://x").searchParams.get("path") ?? "").trim()
      try {
        const list = await callLocalRpc(targetPort, "session.list", {})
        const item = (list.items ?? []).find((s) => s.sessionId === sessionId)
        if (!item) {
          const err = new Error("会话不存在")
          err.status = 404
          throw err
        }
        const cwd = optionalString(item?.cwd)
        const resolved = resolveWorkspaceFile(cwd, requested)
        const body = readFileSync(resolved.abs)
        const mime = mimeFromName(resolved.name)
        const headers = {
          "content-type": mime,
          "content-length": body.length,
          "cache-control": "private, max-age=60",
          "x-content-type-options": "nosniff",
          "x-dsh-link-filename": encodeURIComponent(resolved.name),
        }
        if (mime.startsWith("text/html") || mime === "image/svg+xml") {
          headers["content-disposition"] = `attachment; filename="${encodeURIComponent(resolved.name)}"`
        }
        res.writeHead(200, headers)
        res.end(body)
      } catch (err) {
        const status = Number.isInteger(err?.status) ? err.status : 500
        // Only the endpoint's own validation messages (4xx) are safe to echo; an
        // unexpected 5xx (e.g. an fs error) must not leak its internal text.
        const message = status >= 500 ? "读取文件失败" : (err?.message || "读取文件失败")
        return json(res, status, { error: message })
      }
      return
    }

    // 本轮改动文件：转发 Host workspaceChanges（摘要 = 路径 + 行数；对比 = 按需取的 hunk）。
    const changesMatch = pathname.match(/^\/dsh-link\/mobile\/sessions\/([^/]+)\/changes(\/diff)?$/)
    if (req.method === "GET" && changesMatch) {
      const sessionId = decodeURIComponent(changesMatch[1])
      const isDiff = Boolean(changesMatch[2])
      const service = workspaceChangesService(rt.workspaceChanges)
      if (!service) return json(res, 404, { error: "主机不支持改动文件", code: "changes_unsupported" })
      const coords = parseChangesCoordinates(new URL(req.url ?? "/", "http://x").searchParams, { needIndex: isDiff })
      if (!coords) return json(res, 400, { error: "改动坐标无效" })
      if (!isDiff) {
        const summary = projectChangesSummary(service.summary(sessionId, coords.seq))
        if (!summary) return json(res, 404, { error: "改动摘要已不可用", code: "changes_unavailable" })
        return json(res, 200, { ok: true, seq: coords.seq, ...summary })
      }
      // 对比送出文件全文（含工作区外文件），与文件下载同规则：只给正在查看该会话的设备。
      if (!isDeviceSubscribedToSession(rt, sessionId, device.deviceId)) {
        return json(res, 403, { error: "仅正在查看该会话的设备可查看改动" })
      }
      const controller = new AbortController()
      const onClose = () => controller.abort()
      res.once("close", onClose)
      try {
        const diff = projectFileDiff(await service.diff(sessionId, coords.seq, coords.index, controller.signal))
        if (!diff) return json(res, 404, { error: "改动对比已不可用", code: "changes_unavailable" })
        return json(res, 200, { ok: true, seq: coords.seq, index: coords.index, ...diff })
      } catch (err) {
        if (controller.signal.aborted) return
        logger?.warn?.(`dsh-links: changes diff: ${err?.message ?? err}`)
        return json(res, 500, { error: "读取改动对比失败" })
      } finally {
        res.off("close", onClose)
      }
    }

    const historyMatch = pathname.match(/^\/dsh-link\/mobile\/sessions\/([^/]+)\/history$/)
    if (req.method === "GET" && historyMatch) {
      const sessionId = decodeURIComponent(historyMatch[1])
      // 分页（session.history RPC：beforeSeq 从窗口尾部向前翻页，maxMessages 按消息边界计数）
      const search = new URL(req.url ?? "/", "http://x").searchParams
      const rpcPayload = { sessionId }
      const rawBeforeSeq = search.get("beforeSeq")
      const rawMaxMessages = search.get("maxMessages")
      const beforeSeq = Number(rawBeforeSeq ?? "")
      const maxMessages = clampHistoryMaxMessages(rawMaxMessages)
      const isTailPage = rawBeforeSeq === null && rawMaxMessages === null
      if (!isTailPage && Number.isInteger(beforeSeq) && beforeSeq > 0) rpcPayload.beforeSeq = beforeSeq
      if (maxMessages !== undefined) rpcPayload.maxMessages = maxMessages
      const value = await callLocalRpc(targetPort, "session.history", rpcPayload)
      const rawEvents = value.events ?? []
      // 会话统计（tokenUsage/sessionStats/contextPressure/contextBreakdown/todos projections）
      const projValues = value.projections?.values ?? {}
      const statsPayload = {
        tokenUsage: projValues.tokenUsage ?? null,
        sessionStats: projValues.sessionStats ?? null,
        contextPressure: projValues.contextPressure ?? null,
        contextBreakdown: projValues.contextBreakdown ?? null,
        todos: projValues.todos ?? null,
      }
      // 投影为稳定消息列表（WI-001）：reasoning 只合并到覆盖其 seq 窗口的页面，
      // 消息 id 以事件 seq 为键（跨页稳定），assistant/message 与同页 block-end 共享 id。
      const reasoningBySeq = pageNeedsReasoningFile(rawEvents)
        ? await readSessionReasoning(targetPort, sessionId, rt)
        : new Map()
      const changesService = workspaceChangesService(rt.workspaceChanges)
      const projected = projectHistoryPage({
        events: rawEvents,
        reasoningBySeq,
        hasMore: value.hasMore ?? false,
        changesSummary: changesService ? (seq) => changesService.summary(sessionId, seq) : null,
      })
      const messages = projected.messages
      // 会话被停止/失败/截断时，最后一条 turn/end reason 非 completed（如 interrupted/stopped/error/maxTokens）
      return json(res, 200, {
        ok: true,
        sessionId,
        messages,
        hasMore: projected.hasMore,
        // 翻页游标：本页最早事件的 seq（App 下一次请求 beforeSeq=该值）
        nextBeforeSeq: projected.nextBeforeSeq,
        // 本页最新事件的 seq（App 作为 SSE 去重基线，只随 tail 页有意义）
        maxSeq: projected.maxSeq,
        stoppedReason: projected.stoppedReason,
        stats: statsPayload,
      })
    }

    const promptMatch = pathname.match(/^\/dsh-link\/mobile\/sessions\/([^/]+)\/prompt$/)
    if (req.method === "POST" && promptMatch) {
      const sessionId = decodeURIComponent(promptMatch[1])
      const body = await readAuthorizedJson(req, res, state, device, PROMPT_BODY_LIMIT)
      if (!body) return
      const text = String(body.text ?? "").trim()
      // 图片附件（DSH prompt image 块：base64 data + mediaType）
      const images = Array.isArray(body.images) ? body.images : []
      const content = []
      for (const img of images.slice(0, 4)) {
        const mediaType = String(img.mediaType ?? "").trim().toLowerCase()
        const data = String(img.data ?? "").trim()
        if (!mediaType || !data) continue
        if (!PROMPT_IMAGE_MEDIA_TYPES.has(mediaType)) {
          return json(res, 400, { error: "图片类型不允许" })
        }
        if (data.length > PROMPT_IMAGE_DATA_LIMIT) {
          return json(res, 413, { error: "图片过大" })
        }
        content.push({ type: "image", mediaType, data })
      }
      if (!text && content.length === 0) return json(res, 400, { error: "消息内容不能为空" })
      if (text) content.push({ type: "text", text })
      const resVal = await runMobileDeviceMutation(rt, state, device, () => callLocalRpc(targetPort, "session.prompt", {
        sessionId,
        requestId: "mobile-" + randomBytes(12).toString("hex"),
        mode: body.mode || "queue",
        content,
      }))
      if (mobileMutationWasRevoked(resVal)) return respondDeviceRevoked(res)
      return json(res, 200, { ok: true, result: resVal })
    }

    const cancel = pathname.match(/^\/dsh-link\/mobile\/sessions\/([^/]+)\/cancel$/)
    if (req.method === "POST" && cancel) {
      const sessionId = decodeURIComponent(cancel[1])
      const result = await runMobileDeviceMutation(rt, state, device, () =>
        callLocalRpc(targetPort, "session.cancel", { sessionId }))
      if (mobileMutationWasRevoked(result)) return respondDeviceRevoked(res)
      return json(res, 200, { ok: true, sessionId })
    }

    const feedbackMatch = pathname.match(/^\/dsh-link\/mobile\/sessions\/([^/]+)\/feedback$/)
    if (feedbackMatch) {
      const sessionId = decodeURIComponent(feedbackMatch[1])
      // 反馈是会话日志级数据：设备已通过外层鉴权即可读写，不要求此刻有活跃 SSE 订阅
      // （App 打开会话时会与订阅建立并发请求，强校验会命中竞态 403）。
      if (req.method === "GET") {
        const value = await callLocalRpc(targetPort, "messageFeedback.list", { sessionId })
        return json(res, 200, value ?? { ok: true, value: { items: [] } })
      }
      if (req.method === "POST") {
        const body = await readAuthorizedJson(req, res, state, device, 64 * 1024)
        if (!body) return
        const messageId = String(body.messageId ?? "").trim()
        if (!messageId) return json(res, 400, { error: "缺少 messageId" })
        const action = String(body.action ?? "put")
        const operation = action === "delete"
          ? () => callLocalRpc(targetPort, "messageFeedback.delete", {
              sessionId,
              messageId,
              ifVersion: body.ifVersion ?? null,
            })
          : () => callLocalRpc(targetPort, "messageFeedback.put", {
              sessionId,
              messageId,
              rating: body.rating === "negative" ? "negative" : "positive",
              ...(typeof body.note === "string" && body.note.trim() ? { note: body.note.trim() } : {}),
              ...(typeof body.category === "string" && body.category.trim() ? { category: body.category.trim() } : {}),
              ifVersion: body.ifVersion ?? null,
            })
        const result = await runMobileDeviceMutation(rt, state, device, operation)
        if (mobileMutationWasRevoked(result)) return respondDeviceRevoked(res)
        return json(res, 200, result ?? { ok: true })
      }
    }

    return json(res, 404, { error: "mobile endpoint not found" })
  } catch (error) {
    console.error(`dsh-links: mobile API error: ${error?.message ?? error}`)
    // 主机运行时对被占用的会话拒绝 resume：给手机端可读的 409，而不是笼统的 502。
    if (/SessionAlreadyOwnedError|already owned by an active write handle/i.test(String(error?.message ?? error))) {
      return json(res, 409, {
        error: "这个会话正被网页端或其他设备使用，手机暂时无法继续。请换一个会话，或在网页端关掉该会话后重试。",
        code: "session_busy",
      })
    }
    return json(res, 502, { error: "mobile API unavailable" })
  }
}
