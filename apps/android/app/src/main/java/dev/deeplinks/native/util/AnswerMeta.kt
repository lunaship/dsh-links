package dev.deeplinks.native.util

import java.util.Locale

data class AnswerMeta(
    val elapsedMs: Long? = null,
) {
    fun isEmpty(): Boolean = elapsedMs == null
}

fun compactDuration(ms: Long): String = when {
    ms >= 3_600_000 -> String.format(Locale.US, "%.1fh", ms / 3_600_000.0)
    ms >= 60_000 -> String.format(Locale.US, "%.0fm", ms / 60_000.0)
    ms >= 1000 -> String.format(Locale.US, "%.1fs", ms / 1000.0)
    else -> "${ms}ms"
}

fun compactTokens(n: Long): String = when {
    n >= 1_000_000 -> String.format(Locale.US, "%.1fM", n / 1_000_000.0)
    n >= 1000 -> String.format(Locale.US, "%.1fK", n / 1000.0)
    else -> "$n"
}

/**
 * 助手回复底栏只保留这条消息自己的耗时。
 * 会话累计 token、缓存命中、输入/输出和吞吐率统一展示在输入框下方。
 */
fun buildAnswerMeta(durationMs: Long?): AnswerMeta? {
    val elapsed = durationMs?.takeIf { it > 0 }
    val meta = AnswerMeta(elapsedMs = elapsed)
    return meta.takeUnless { it.isEmpty() }
}

fun answerMetaSummary(meta: AnswerMeta): String = buildList {
    meta.elapsedMs?.let { add(compactDuration(it)) }
}.joinToString(" · ")
