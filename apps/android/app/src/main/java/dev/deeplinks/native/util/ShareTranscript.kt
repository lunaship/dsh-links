package dev.deeplinks.native.util

import dev.deeplinks.core.DshStrings
import dev.deeplinks.core.L
import dev.deeplinks.native.MobileMessage

data class ShareTurn(val role: String, val text: String)

const val SHARE_MAX_TURNS = 8
const val SHARE_MAX_CHARS_PER_TURN = 420

private val CLOSED_FENCE = Regex("```[^\\n]*\\n?[\\s\\S]*?```")

/** 分享卡片用：去掉未闭合围栏、折叠代码/图为占位，再截断。 */
fun flattenShareText(
    text: String,
    maxChars: Int = SHARE_MAX_CHARS_PER_TURN,
    strings: DshStrings = L,
): String {
    val closed = stripIncompleteFence(text)
    val placeholders = CLOSED_FENCE.replace(closed) { match ->
        val raw = match.value
        val header = raw.lineSequence().firstOrNull().orEmpty().removePrefix("```").trim().lowercase()
        if (header.startsWith("mermaid")) strings.shareFenceDiagram else strings.shareFenceCode
    }
    val collapsed = placeholders
        .replace(Regex("[ \\t]+"), " ")
        .replace(Regex("\\n{3,}"), "\n\n")
        .trim()
    if (collapsed.length <= maxChars) return collapsed
    return collapsed.take(maxChars - 1).trimEnd() + "…"
}

fun stripIncompleteFence(text: String): String {
    val count = Regex("```").findAll(text).count()
    if (count % 2 == 0) return text
    val last = text.lastIndexOf("```")
    return if (last >= 0) text.substring(0, last).trimEnd() else text
}

fun selectShareTurns(
    messages: List<MobileMessage>,
    maxTurns: Int = SHARE_MAX_TURNS,
): List<ShareTurn> =
    messages.asSequence()
        .filter { it.role == "user" || it.role == "assistant" }
        .filter { it.running != true }
        .map { ShareTurn(it.role, flattenShareText(it.text)) }
        .filter { it.text.isNotBlank() }
        .toList()
        .takeLast(maxTurns)
