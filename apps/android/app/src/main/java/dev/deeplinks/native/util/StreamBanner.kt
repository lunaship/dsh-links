package dev.deeplinks.native.util

/**
 * SSE 断线横幅（对照 Grok：健康连上时不堆 chrome；失败才出现）。
 *
 * 首次进入会话的 Connecting 静默 [quietMs]，避免每次切会话闪一条「正在连接」。
 * 曾经连上后再掉线，立刻提示。鉴权/服务端失败立刻提示。
 */
enum class StreamBannerKind { Hidden, Connecting, Retrying, Failed }

fun streamBannerKind(
    hasSession: Boolean,
    connected: Boolean,
    failed: Boolean,
    connecting: Boolean,
    retrying: Boolean,
    everConnected: Boolean,
    quietElapsed: Boolean,
): StreamBannerKind {
    if (!hasSession) return StreamBannerKind.Hidden
    if (connected) return StreamBannerKind.Hidden
    if (failed) return StreamBannerKind.Failed
    if (!(everConnected || quietElapsed)) return StreamBannerKind.Hidden
    return when {
        connecting -> StreamBannerKind.Connecting
        retrying -> StreamBannerKind.Retrying
        else -> StreamBannerKind.Hidden
    }
}
