package dev.deeplinks.architecture

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 设计 token 强制门禁：业务代码不得再写裸字号（N.sp）或裸色值（Color(0x...)）。
 *
 * 存量用 design-token-baseline.txt 记录为「每文件上限」：
 * - 任何非白名单文件出现裸色值 -> 失败；
 * - 任何文件的裸字号数量超过基线 -> 失败；
 * - 迁移使数量下降后应把基线调小（允许收敛，禁止回涨）。
 *
 * 白名单只放 token 定义文件（DshTheme / DshTypography / DshSyntaxPalette）。
 */
class DesignTokenUsageTest {

    private val fontRegex = Regex("""\b\d+(\.\d+)?\.sp\b""")
    private val colorRegex = Regex("""Color\(0x""")

    private val allowlist = setOf(
        "dev/deeplinks/core/DshTheme.kt",
        "dev/deeplinks/core/DshTypography.kt",
        "dev/deeplinks/core/DshSyntaxPalette.kt",
    )

    private fun mainSourceRoot(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null && !File(dir, "src/main/java").isDirectory) dir = dir.parentFile
        requireNotNull(dir) { "找不到 src/main/java，user.dir=" + System.getProperty("user.dir") }
        return File(dir, "src/main/java")
    }

    private fun baselineLimits(): Map<String, Int> {
        val stream = javaClass.getResourceAsStream("/design-token-baseline.txt")
            ?: return emptyMap()
        return stream.bufferedReader().readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .associate { line ->
                val parts = line.split(Regex("\\s+"))
                parts[0] to parts[1].toInt()
            }
    }

    private fun relative(root: File, file: File): String =
        file.relativeTo(root).path.replace(File.separatorChar, '/')

    @Test
    fun noRawFontSizesOrColorsOutsideTokenLayer() {
        val root = mainSourceRoot()
        val limits = baselineLimits()
        val violations = mutableListOf<String>()

        for (file in root.walkTopDown().filter { it.isFile && it.extension == "kt" }) {
            val rel = relative(root, file)
            if (rel in allowlist) continue
            var fontLines = 0
            var colorLines = 0
            file.forEachLine { line ->
                if (fontRegex.containsMatchIn(line)) fontLines++
                if (colorRegex.containsMatchIn(line)) colorLines++
            }
            if (colorLines > 0) {
                violations += rel + ": " + colorLines + " 处裸色值 Color(0x...)，请改用 Dsh 颜色角色"
            }
            val limit = limits[rel] ?: 0
            if (fontLines > limit) {
                violations += rel + ": 裸字号 " + fontLines + " 处，超过基线 " + limit + "，请改用 DshType"
            }
        }

        assertTrue(
            "设计 token 违规：\n" + violations.joinToString("\n") +
                "\n\n修复：改用 Dsh.* 颜色角色 / DshType.* 排版；" +
                "若迁移减少了存量，请同步调小 app/src/test/resources/design-token-baseline.txt",
            violations.isEmpty()
        )
    }
}
