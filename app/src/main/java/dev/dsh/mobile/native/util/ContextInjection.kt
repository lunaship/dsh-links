package dev.dsh.mobile.native.util

/**
 * 识别 DSH 注入到会话里的 system-reminder / skill catalog，
 * 对齐 Web「上下文注入 · skill-catalog」折叠行，避免首条消息把整段目录铺开。
 */
fun isContextInjectionText(text: String): Boolean {
    if (text.isBlank()) return false
    return text.contains("<system-reminder>", ignoreCase = true) ||
        text.contains("&lt;system-reminder&gt;", ignoreCase = true) ||
        text.contains("<available_skills>", ignoreCase = true) ||
        text.contains("&lt;available_skills&gt;", ignoreCase = true) ||
        text.contains("available skill catalog", ignoreCase = true) ||
        text.contains("available-skills", ignoreCase = true)
}

/** 注入来源标签，Web 端用「skill-catalog」这种短名。 */
fun contextInjectionLabels(text: String): List<String> {
    val labels = linkedSetOf<String>()
    if (
        text.contains("available_skills", ignoreCase = true) ||
        text.contains("skill catalog", ignoreCase = true) ||
        text.contains("available-skills", ignoreCase = true)
    ) {
        labels.add("skill-catalog")
    }
    Regex("""Instructions from:\s*(.+)""").findAll(text).forEach { match ->
        val name = match.groupValues[1].trim()
        if (name.isNotEmpty()) labels.add(name)
    }
    Regex("""(?:AGENTS\.md|CLAUDE\.md|\.zcode/[^\s,]+)""").findAll(text).forEach { match ->
        labels.add(match.value)
    }
    return labels.toList().ifEmpty { listOf("workspace") }
}
