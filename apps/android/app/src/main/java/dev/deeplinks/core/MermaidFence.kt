package dev.deeplinks.core

/** Markdown ```mermaid 围栏识别与渲染上限（对照 DeepSeek 官方 App 1.2.6+）。 */
object MermaidFence {
    const val MAX_SOURCE_CHARS = 24_000

    fun isMermaidLang(lang: String?): Boolean {
        val t = lang?.trim()?.lowercase().orEmpty()
        return t == "mermaid" || t.startsWith("mermaid ")
    }

    /** 流式半图：像能 parse 再尝试渲染，避免每个 token 都进 WebView。 */
    fun looksRenderable(source: String): Boolean {
        val t = source.trim()
        if (t.length < 12) return false
        val head = t.lineSequence().firstOrNull { line ->
            val s = line.trim()
            s.isNotEmpty() && !s.startsWith("%%")
        } ?: return false
        return HEAD_RE.containsMatchIn(head)
    }

    private val HEAD_RE = Regex(
        "^(graph|flowchart|sequenceDiagram|classDiagram|stateDiagram|erDiagram|gantt|pie|gitGraph|mindmap|timeline|journey|quadrantChart|C4Context|requirementDiagram|sankey-beta|xychart-beta|block-beta|packet-beta|kanban)\\b",
        RegexOption.IGNORE_CASE,
    )

    /** 空图或超长源不进 WebView，调用方回退代码块。 */
    fun sourceForRender(source: String): String? {
        val trimmed = source.trim()
        if (trimmed.isEmpty() || trimmed.length > MAX_SOURCE_CHARS) return null
        return trimmed
    }
}
