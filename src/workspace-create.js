import { mkdir, stat } from "node:fs/promises"
import { dirname, isAbsolute, join } from "node:path"

const MAX_WORKSPACE_NAME_BYTES = 255

export class MobileWorkspaceCreateError extends Error {
  constructor(code, message, status = 400, details = {}) {
    super(message)
    this.name = "MobileWorkspaceCreateError"
    this.code = code
    this.status = status
    this.details = details
  }
}

function validateWorkspaceName(name) {
  if (name === "." || name === ".." || name.includes("/") || name.includes("\\")) {
    throw new MobileWorkspaceCreateError(
      "workspace-invalid-name",
      "工作区名称只能是一层目录名，不能包含路径分隔符",
      400,
      { input: name },
    )
  }
  if (/[\u0000-\u001f\u007f]/u.test(name) || Buffer.byteLength(name, "utf8") > MAX_WORKSPACE_NAME_BYTES) {
    throw new MobileWorkspaceCreateError(
      "workspace-invalid-name",
      "工作区名称包含无效字符或过长",
      400,
      { input: name },
    )
  }
}

/**
 * 将手机输入解析成 DSH workspace.create 接受的绝对路径。
 * - 绝对路径：沿用“注册电脑上已有目录”的语义，不创建目录；
 * - 单层名称：以当前工作区为锚点，在其同级目录创建，禁止手机任意指定父目录。
 */
export function planMobileWorkspaceCreate({ input, parentWorkspaceId, workspaces }) {
  const normalizedInput = String(input ?? "").trim()
  if (!normalizedInput) {
    throw new MobileWorkspaceCreateError("workspace-input-required", "请输入工作区名称或绝对路径")
  }
  if (isAbsolute(normalizedInput)) {
    return {
      path: normalizedInput,
      inputKind: "absolute-path",
      shouldCreateDirectory: false,
      parentWorkspaceId: null,
    }
  }

  validateWorkspaceName(normalizedInput)
  const items = Array.isArray(workspaces) ? workspaces : []
  const requestedParentId = String(parentWorkspaceId ?? "").trim()
  const anchor = requestedParentId
    ? items.find((item) => String(item?.workspaceId ?? "") === requestedParentId)
    : (items.length === 1 ? items[0] : null)
  if (!anchor) {
    throw new MobileWorkspaceCreateError(
      requestedParentId ? "workspace-parent-not-found" : "workspace-parent-required",
      requestedParentId ? "当前工作区已不存在，请刷新后重试" : "请先选择一个工作区，再用名称创建同级工作区",
      requestedParentId ? 404 : 400,
      requestedParentId ? { parentWorkspaceId: requestedParentId } : {},
    )
  }
  const anchorPath = String(anchor.path ?? "").trim()
  if (!isAbsolute(anchorPath)) {
    throw new MobileWorkspaceCreateError(
      "workspace-parent-invalid",
      "当前工作区路径无效，请改用绝对路径添加",
      400,
      { parentWorkspaceId: String(anchor.workspaceId ?? "") },
    )
  }
  return {
    path: join(dirname(anchorPath), normalizedInput),
    inputKind: "name",
    shouldCreateDirectory: true,
    parentWorkspaceId: String(anchor.workspaceId ?? ""),
  }
}

/** 创建名称模式的目录；已存在的目录视为可注册，而同名文件返回冲突。 */
export async function ensureMobileWorkspaceDirectory(plan) {
  if (!plan.shouldCreateDirectory) return { directoryCreated: false }
  try {
    await mkdir(plan.path, { recursive: false })
    return { directoryCreated: true }
  } catch (error) {
    if (error?.code === "EEXIST") {
      try {
        const existing = await stat(plan.path)
        if (existing.isDirectory()) return { directoryCreated: false }
      } catch {
        throw new MobileWorkspaceCreateError(
          "workspace-directory-check-failed",
          "无法检查同名目录",
          400,
          { path: plan.path },
        )
      }
      throw new MobileWorkspaceCreateError(
        "workspace-name-conflict",
        "同名路径已存在，但不是文件夹",
        409,
        { path: plan.path },
      )
    }
    throw new MobileWorkspaceCreateError(
      "workspace-directory-create-failed",
      "无法创建工作区目录",
      400,
      { path: plan.path },
    )
  }
}
