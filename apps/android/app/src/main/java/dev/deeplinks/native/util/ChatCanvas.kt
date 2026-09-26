package dev.deeplinks.native.util

/** 聊天画布空列表时的互斥态（注释约定 load / empty / error 互斥）。 */
enum class ChatCanvasKind { Loading, Error, Working, Empty, Content }

fun chatCanvasKind(
    hasMessages: Boolean,
    initialLoadInFlight: Boolean,
    hasHistoryError: Boolean,
    working: Boolean,
): ChatCanvasKind = when {
    hasMessages -> ChatCanvasKind.Content
    initialLoadInFlight -> ChatCanvasKind.Loading
    hasHistoryError -> ChatCanvasKind.Error
    working -> ChatCanvasKind.Working
    else -> ChatCanvasKind.Empty
}

enum class LoadOlderKind { Loading, Failed, Idle }

fun loadOlderKind(loading: Boolean, failed: Boolean): LoadOlderKind = when {
    loading -> LoadOlderKind.Loading
    failed -> LoadOlderKind.Failed
    else -> LoadOlderKind.Idle
}

enum class SessionListKind { Loading, Error, Empty, Content }

fun catalogKind(
    hasItems: Boolean,
    initialLoad: Boolean,
    hasError: Boolean,
): SessionListKind = when {
    hasItems -> SessionListKind.Content
    initialLoad -> SessionListKind.Loading
    hasError -> SessionListKind.Error
    else -> SessionListKind.Empty
}

fun sessionListKind(
    hasSessions: Boolean,
    initialLoad: Boolean,
    hasError: Boolean,
): SessionListKind = catalogKind(hasItems = hasSessions, initialLoad = initialLoad, hasError = hasError)

/** 已有会话时刷新失败：列表仍显示，顶上带重试，不再 toast。 */
fun sessionShowsRefreshBanner(hasSessions: Boolean, hasError: Boolean): Boolean =
    hasSessions && hasError

enum class RenameDialogKind { Idle, Saving, Failed }

/** 保存失败时弹窗保持打开，错误写在对话框内，不再先关再 toast。 */
fun renameDialogKind(saving: Boolean, error: String?): RenameDialogKind = when {
    saving -> RenameDialogKind.Saving
    !error.isNullOrBlank() -> RenameDialogKind.Failed
    else -> RenameDialogKind.Idle
}

/** 归档/删除只有服务端接受后才本机隐藏，避免列表和主机不一致。 */
fun localHideAfterRemote(accepted: Boolean): Boolean = accepted

/** 分叉响应必须带回新会话 id，空 id 视为失败。 */
fun forkAccepted(newSessionId: String?): Boolean = !newSessionId.isNullOrBlank()
