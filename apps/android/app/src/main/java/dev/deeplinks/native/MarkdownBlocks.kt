package dev.deeplinks.native

import dev.deeplinks.core.MarkdownMedia

enum class MarkdownBlockType { PARAGRAPH, HEADING, LIST, CODE, QUOTE, TABLE, HR, IMAGE, MATH, EMPTY }

data class MarkdownBlock(
    val type: MarkdownBlockType,
    val content: String,
    val lang: String? = null,
    val level: Int = 0,
    val rows: List<List<String>> = emptyList(),
)

private val TABLE_SEP_CELL = Regex("^:?-{2,}:?$")
private const val MAX_RENDERED_TABLE_ROWS = 80
private const val MAX_RENDERED_TABLE_COLUMNS = 12
private const val MAX_RENDERED_TABLE_CELL_CHARS = 500

fun splitMarkdownBlocks(text: String): List<MarkdownBlock> {
    val blocks = mutableListOf<MarkdownBlock>()
    val lines = text.split("\n")
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        when {
            line.trimStart().startsWith("```") -> {
                val lang = line.trim().removePrefix("```").trim().ifBlank { null }
                val buf = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                    buf.appendLine(lines[i])
                    i++
                }
                i++
                blocks.add(MarkdownBlock(MarkdownBlockType.CODE, buf.toString(), lang))
            }
            line.trimStart().startsWith("> ") || line.trimStart() == ">" -> {
                val buf = StringBuilder(line.trimStart().removePrefix(">").trim())
                i++
                while (i < lines.size && (lines[i].trimStart().startsWith("> ") || lines[i].trimStart() == ">")) {
                    buf.append("\n").append(lines[i].trimStart().removePrefix(">").trim())
                    i++
                }
                blocks.add(MarkdownBlock(MarkdownBlockType.QUOTE, buf.toString()))
            }
            // 块级公式：$$...$$（同行闭合或跨行，DSH micromark math 扩展）
            line.trimStart().startsWith("$$") -> {
                val buf = StringBuilder()
                var i0 = i
                val open = line.trim()
                if (open.length > 2 && open.endsWith("$$")) {
                    // 同行闭合：$$x$$
                    buf.append(open.removePrefix("$$").removeSuffix("$$").trim())
                    i++
                } else {
                    buf.append(line.trimStart().removePrefix("$$"))
                    i++
                    var closed = false
                    while (i < lines.size) {
                        val l = lines[i]
                        val t = l.trim()
                        if (t == "$$") {
                            closed = true
                            i++
                            break
                        }
                        if (t.length > 2 && t.endsWith("$$")) {
                            buf.append("\n").append(t.removeSuffix("$$").trim())
                            closed = true
                            i++
                            break
                        }
                        buf.append("\n").append(l)
                        i++
                    }
                    if (!closed && i == i0 + 1) {
                        // 孤立 $$ 标记（非公式）：按原文段落回退
                        blocks.add(MarkdownBlock(MarkdownBlockType.PARAGRAPH, line))
                        continue
                    }
                }
                blocks.add(MarkdownBlock(MarkdownBlockType.MATH, buf.toString().trim()))
            }
            // 图片行：![alt](url)
            line.trimStart().startsWith("![") && line.contains("](") -> {
                val trimmed = line.trim()
                val close = trimmed.indexOf(']')
                val urlStart = trimmed.indexOf('(', close)
                val urlEnd = trimmed.indexOf(')', urlStart)
                if (urlStart > 0 && urlEnd > urlStart) {
                    val url = trimmed.substring(urlStart + 1, urlEnd).trim()
                    if (MarkdownMedia.isSafeImageUrl(url)) {
                        blocks.add(MarkdownBlock(MarkdownBlockType.IMAGE, url))
                    } else {
                        blocks.add(MarkdownBlock(MarkdownBlockType.PARAGRAPH, line))
                    }
                } else {
                    blocks.add(MarkdownBlock(MarkdownBlockType.PARAGRAPH, line))
                }
                i++
            }
            line.trimStart() == "---" || line.trimStart() == "***" || line.trimStart() == "___" -> {
                blocks.add(MarkdownBlock(MarkdownBlockType.HR, ""))
                i++
            }
            line.isBlank() -> {
                i++
            }
            // 表格：| a | b | 行 + 分隔行
            line.trimStart().startsWith("|") && line.contains("|") -> {
                val rows = mutableListOf<List<String>>()
                val raw = StringBuilder()
                var tooComplex = false
                while (i < lines.size && lines[i].trimStart().startsWith("|") && lines[i].contains("|")) {
                    if (raw.isNotEmpty()) raw.append('\n')
                    raw.append(lines[i])
                    val cells = lines[i].trim().trim('|').split("|").map { it.trim() }
                    if (cells.size > MAX_RENDERED_TABLE_COLUMNS || cells.any { it.length > MAX_RENDERED_TABLE_CELL_CHARS }) {
                        tooComplex = true
                    }
                    // 跳过分隔行（|---|）
                    if (!cells.all { it.matches(TABLE_SEP_CELL) }) {
                        rows.add(cells)
                        if (rows.size > MAX_RENDERED_TABLE_ROWS) tooComplex = true
                    }
                    i++
                }
                if (tooComplex) {
                    blocks.add(MarkdownBlock(MarkdownBlockType.PARAGRAPH, raw.toString()))
                } else if (rows.size >= 1) {
                    blocks.add(MarkdownBlock(MarkdownBlockType.TABLE, "", rows = rows))
                }
            }
            line.trimStart().startsWith("###") -> {
                blocks.add(MarkdownBlock(MarkdownBlockType.HEADING, line.trimStart().removePrefix("###").trim(), level = 3))
                i++
            }
            line.trimStart().startsWith("##") -> {
                blocks.add(MarkdownBlock(MarkdownBlockType.HEADING, line.trimStart().removePrefix("##").trim(), level = 2))
                i++
            }
            line.trimStart().startsWith("#") -> {
                blocks.add(MarkdownBlock(MarkdownBlockType.HEADING, line.trimStart().removePrefix("#").trim(), level = 1))
                i++
            }
            line.trimStart().startsWith("- ") || line.trimStart().startsWith("* ") -> {
                blocks.add(MarkdownBlock(MarkdownBlockType.LIST, line.trimStart().removePrefix("- ").removePrefix("* ")))
                i++
            }
            else -> {
                blocks.add(MarkdownBlock(MarkdownBlockType.PARAGRAPH, line))
                i++
            }
        }
    }
    return blocks
}
