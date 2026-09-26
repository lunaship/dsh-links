package dev.deeplinks.native

import androidx.compose.ui.unit.dp

/** 输入条 / 消息列宽度。手机原生铺满，左右 12dp 让悬浮卡更贴边。 */
internal val COMPOSER_SIDE_CLEARANCE = 12.dp
internal val COMPOSER_COMPACT_WIDTH = 360.dp
internal val COMPOSER_MODEL_MAX_WIDTH = 132.dp

/** 访问模式座文字上限（DSH PermissionSelect 的 max-width 220px；手机上够装「Workspace Write」）。 */
internal val COMPOSER_ACCESS_MAX_WIDTH = 124.dp

fun composerIsCompact(widthDp: Float): Boolean = widthDp < COMPOSER_COMPACT_WIDTH.value

/**
 * 输入卡实际可用宽度（容器宽 - 左右留白）是否进入紧凑档。
 * 分屏/自由窗口下容器宽会变小，所以按容器宽推导，不看 screenWidthDp。
 */
fun composerSeatsCompact(containerWidthDp: Float): Boolean =
    composerIsCompact(containerWidthDp - 2 * COMPOSER_SIDE_CLEARANCE.value)

/**
 * 模型座内容（DSH `conversation.input.model`）：名称与推理等级拆成两段，
 * 等级是次级文本、空间不足时先被挤掉；名称缺省时由座位显示「选择模型」。
 */
internal data class ComposerModelSeat(val name: String?, val effort: String?)

internal fun composerModelSeat(
    catalog: MobileModelCatalog?,
    pending: Triple<String, String, String?>? = null,
): ComposerModelSeat {
    val current = catalog
    val requested = pending?.second ?: current?.currentModel
    val option = requested?.let { id ->
        current?.groups?.asSequence()
            ?.flatMap { it.models.asSequence() }
            ?.firstOrNull { it.id == id }
    }
    return ComposerModelSeat(
        // 目录里有展示名就用展示名（pending 只有 id）
        name = option?.name ?: requested,
        effort = pending?.third ?: current?.currentReasoningEffort ?: option?.defaultEffort,
    )
}

/**
 * 手机端只认 DSH 三个权限预设；表外值（含旧值、自定义）按默认的
 * `workspace-write` 显示，避免座位显示成空白或服务端拒绝的名字。
 */
internal fun canonicalComposerPermission(preset: String?): String = when (preset) {
    "read-only" -> "read-only"
    "danger-full-access" -> "danger-full-access"
    else -> "workspace-write"
}

/** Full access 在座位上要显风险色（DSH 的 Auto review/Full access 也是显式风险档）。 */
internal fun composerPermissionIsDanger(preset: String?): Boolean =
    canonicalComposerPermission(preset) == "danger-full-access"

/** 已开聊时工作区/Harness 在侧栏，输入条上不再堆只读标签。 */
fun composerShowsSetupRow(
    workspaceEditable: Boolean,
    showHarness: Boolean,
    harnessLabel: String = "",
): Boolean = workspaceEditable || (showHarness && harnessLabel.isNotBlank())

/** 发送/停止失败写在输入槽内；发送中不保留上一次错误。 */
fun composerShowsActionError(error: String?, sending: Boolean): Boolean =
    !sending && !error.isNullOrBlank()

/** 已开聊且刚改过本会话权限时，输入条 chip 显示这次选择，而不是全局默认。 */
fun composerPermissionPreset(
    sessionId: String?,
    sessionOverrides: Map<String, String>,
    defaultPreset: String,
): String = sessionId?.let { sessionOverrides[it] } ?: defaultPreset

/** 无会话时 slash 完整命令不得清空输入；有会话才提交并清空。 */
fun completableCanSubmit(hasSession: Boolean): Boolean = hasSession
