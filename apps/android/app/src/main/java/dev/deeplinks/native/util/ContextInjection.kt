package dev.deeplinks.native.util

/**
 * 识别 DSH 注入到会话里的 system-reminder / runtime context / skill catalog，
 * 对齐 Web「上下文注入 · skill-catalog」折叠行，避免首条消息把整段目录铺开。
 */
fun isContextInjectionText(text: String): Boolean {
    if (text.isBlank()) return false
    return isGoalRoundText(text) ||
        text.contains("<system-reminder>", ignoreCase = true) ||
        text.contains("&lt;system-reminder&gt;", ignoreCase = true) ||
        text.contains("<available_skills>", ignoreCase = true) ||
        text.contains("&lt;available_skills&gt;", ignoreCase = true) ||
        text.contains("available skill catalog", ignoreCase = true) ||
        text.contains("available-skills", ignoreCase = true) ||
        text.contains("Current runtime context", ignoreCase = true) ||
        text.contains("Current DSH file policy", ignoreCase = true) ||
        text.contains("Approval prompts are disabled", ignoreCase = true) ||
        text.contains("Instructions from:", ignoreCase = true) ||
        Regex("""\bAGENTS\.md\b""").containsMatchIn(text) ||
        Regex("""\bCLAUDE\.md\b""").containsMatchIn(text)
}

/**
 * 识别 goal 模式每轮注入的续跑提示（`<goal_round>` 包装的系统指令）。
 * 这类消息不是用户亲手输入，应折叠为「目标轮次」行而不是铺满整屏的用户气泡。
 */
fun isGoalRoundText(text: String): Boolean =
    text.contains("<goal_round>", ignoreCase = true) ||
        text.contains("&lt;goal_round&gt;", ignoreCase = true)

/** 从 goal_round 提示里提取 Objective 原文（用户真正设定的目标）。 */
fun goalRoundObjective(text: String): String? {
    val match = Regex("""Objective:\s*"(.*)"""").find(text) ?: return null
    return match.groupValues[1].trim().takeIf { it.isNotEmpty() }
}

/** 从 goal_round 提示里提取轮次（如 "1/256"）。 */
fun goalRoundProgress(text: String): String? {
    val match = Regex("""Round:\s*([0-9]+\s*/\s*[0-9]+)""").find(text) ?: return null
    return match.groupValues[1].replace(" ", "")
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
    if (text.contains("Current runtime context", ignoreCase = true)) {
        labels.add("runtime")
    }
    if (text.contains("Current DSH file policy", ignoreCase = true)) {
        labels.add("file-policy")
    }
    if (text.contains("Approval prompts are disabled", ignoreCase = true)) {
        labels.add("approval-policy")
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
