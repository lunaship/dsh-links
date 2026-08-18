package dev.dsh.mobile.native.util
import dev.dsh.mobile.native.MobileMessage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 斜杠命令过滤（WI-005 / WI-006 抽出）单元测试。
 *
 * 验证：
 * - 至少 2 字符才过滤；
 * - 大小写不敏感；
 * - 子串匹配而非仅前缀；
 * - 空查询（不含 `/`）返回空。
 */
class ToolSearchTest {

    private val groups = listOf(
        SlashCommandGroup(
            title = "Built-in",
            items = listOf(
                SlashCommand("/help", "显示命令帮助"),
                SlashCommand("/history", "历史会话"),
                SlashCommand("/clear", "清屏"),
            )
        ),
        SlashCommandGroup(
            title = "Permissions",
            items = listOf(
                SlashCommand("/approve", "批准当前请求"),
                SlashCommand("/reject", "拒绝当前请求"),
            )
        ),
    )

    @Test
    fun `无前导斜杠返回空列表`() {
        val out = filterSlashCommands(groups, "help")
        assertEquals(emptyList<SlashCommandGroup>(), out)
        assertTrue(filterSlashCommands(groups, "").isEmpty())
        assertTrue(filterSlashCommands(groups, "   ").isEmpty())
    }

    @Test
    fun `单字符不过滤，返回全部分组`() {
        val out = filterSlashCommands(groups, "/h")
        assertEquals(2, out.size)
        assertEquals(3, out[0].items.size)
        assertEquals(2, out[1].items.size)
    }

    @Test
    fun `两个及以上字符按子串匹配，大小写不敏感`() {
        val out = filterSlashCommands(groups, "/HE")
        val flat = out.flatMap { it.items }.map { it.token }
        // /help 含 "he"
        assertEquals(listOf("/help"), flat)
    }

    @Test
    fun `不命中时返回空列表`() {
        val out = filterSlashCommands(groups, "/xyz123")
        assertTrue(out.isEmpty())
    }

    @Test
    fun `命中只覆盖有命中的分组，空分组被剔除`() {
        // "ap" 只命中 Permissions 分组
        val out = filterSlashCommands(groups, "/ap")
        assertEquals(1, out.size)
        assertEquals("Permissions", out[0].title)
        assertEquals(1, out[0].items.size)
        assertEquals("/approve", out[0].items[0].token)
    }

    @Test
    fun `保留分组输入顺序`() {
        val out = filterSlashCommands(groups, "/re")
        // /reject 命中 Permissions
        assertEquals(1, out.size)
        assertEquals("Permissions", out[0].title)
        assertEquals(listOf("/reject"), out[0].items.map { it.token })
    }

    // ===== matchesTool：当前会话内工具调用查找 =====

    private fun toolCall(name: String? = "bash", args: String? = null, text: String = "") =
        MobileMessage(id = "t1", role = "tool_call", text = text, toolName = name, toolArgs = args)

    private fun toolResult(text: String) =
        MobileMessage(id = "r1", role = "tool_result", text = text)

    private fun assistant(text: String) =
        MobileMessage(id = "a1", role = "assistant", text = text)

    @Test
    fun `空查询命中所有工具消息`() {
        assertTrue(matchesTool(toolCall(), ""))
        assertTrue(matchesTool(toolResult("ok"), "  "))
    }

    @Test
    fun `非工具消息一律不命中`() {
        assertFalse(matchesTool(assistant("bash 命令是..."), "bash"))
        assertFalse(matchesTool(assistant("写入文件成功"), "write"))
    }

    @Test
    fun `按工具名匹配且大小写不敏感`() {
        assertTrue(matchesTool(toolCall(name = "Write"), "write"))
        assertTrue(matchesTool(toolCall(name = "Bash"), "BASH"))
        assertFalse(matchesTool(toolCall(name = "Write"), "read"))
    }

    @Test
    fun `按参数匹配`() {
        val call = toolCall(name = "Write", args = """{"path":"/tmp/demo.py","content":"print(1)"}""")
        assertTrue(matchesTool(call, "demo.py"))
        assertTrue(matchesTool(call, "print"))
        assertFalse(matchesTool(call, "notexist"))
    }

    @Test
    fun `按结果文本匹配`() {
        assertTrue(matchesTool(toolResult("exit code 0, 128 lines written"), "128 lines"))
        assertFalse(matchesTool(toolResult("exit code 1"), "成功"))
    }

    @Test
    fun `toolName 为空时仍可按参数和文本匹配`() {
        val call = toolCall(name = null, args = "ls -la /tmp")
        assertTrue(matchesTool(call, "/tmp"))
        assertFalse(matchesTool(toolResult("done"), "ls"))
    }
}