package dev.deeplinks.architecture

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 代码卫生门禁（G3 巨型文件的机器判据）。
 *
 * 两层：
 * 1. 文件级——每文件行数预算（code-hygiene-baseline.txt）；
 * 2. 函数级——顶层函数体行数预算（function-hygiene-baseline.txt，默认 400 行）。
 *
 * 不引入 detekt（其与 AGP 9 内置 Kotlin 的兼容性曾有已知问题）。
 * 预算只允许下调。
 */
class CodeHygieneTest {

    private companion object {
        const val DEFAULT_MAX_FILE_LINES = 1500
        const val DEFAULT_MAX_FUNCTION_LINES = 400
    }

    private fun mainSourceRoot(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null && !File(dir, "src/main/java").isDirectory) dir = dir.parentFile
        requireNotNull(dir) { "找不到 src/main/java" }
        return File(dir, "src/main/java")
    }

    private fun loadLimits(resource: String): Map<String, Int> {
        val stream = javaClass.getResourceAsStream(resource) ?: return emptyMap()
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
    fun kotlinFilesStayWithinLineBudget() {
        val root = mainSourceRoot()
        val limits = loadLimits("/code-hygiene-baseline.txt")
        val violations = mutableListOf<String>()

        for (file in root.walkTopDown().filter { it.isFile && it.extension == "kt" }) {
            val rel = relative(root, file)
            val lines = file.readLines().size
            val max = limits[rel] ?: DEFAULT_MAX_FILE_LINES
            if (lines > max) {
                violations += rel + ": " + lines + " 行，超过预算 " + max + "（请拆解或调小基线，不要上调）"
            }
        }

        assertTrue(
            "文件体积超预算：\n" + violations.joinToString("\n"),
            violations.isEmpty(),
        )
    }

    @Test
    fun topLevelFunctionsStayWithinBudget() {
        val root = mainSourceRoot()
        val limits = loadLimits("/function-hygiene-baseline.txt")
        val violations = mutableListOf<String>()

        for (file in root.walkTopDown().filter { it.isFile && it.extension == "kt" }) {
            val rel = relative(root, file)
            val lines = file.readLines()
            for (i in lines.indices) {
                val line = lines[i]
                if (!(line.startsWith("fun ") || line.startsWith("private fun ") || line.startsWith("internal fun "))) continue
                var brace = -1
                for (j in i until minOf(i + 40, lines.size)) {
                    if (lines[j].trimEnd().endsWith("{")) { brace = j; break }
                }
                if (brace < 0) continue
                var end = -1
                for (j in brace + 1 until lines.size) {
                    if (lines[j] == "}") { end = j; break }
                }
                if (end < 0) continue
                val length = end - i + 1
                val name = line.substringBefore("(").trim().split(" ").last()
                val key = rel + "::" + name
                val max = limits[key] ?: DEFAULT_MAX_FUNCTION_LINES
                if (length > max) {
                    violations += key + ": " + length + " 行，超过预算 " + max + "（请抽 UiState/子 composable）"
                }
            }
        }

        assertTrue(
            "函数体超预算：\n" + violations.joinToString("\n"),
            violations.isEmpty(),
        )
    }
}
