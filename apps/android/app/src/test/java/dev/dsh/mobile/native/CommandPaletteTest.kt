package dev.dsh.mobile.native

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM unit tests for the typed CommandPalette model.
 * No Android runtime required — verifies filtering/grouping semantics.
 */
class CommandPaletteTest {

    private val insertPlan = PaletteEntry(
        PaletteCommand.Insertable("/plan", "生成执行计划"),
        PaletteGroup.COMMANDS,
    )
    private val newSession = PaletteEntry(
        PaletteCommand.Local("/new-session", "新建会话", LocalKind.NEW_SESSION),
        PaletteGroup.ACTIONS,
    )
    private val completeCmd = PaletteEntry(
        PaletteCommand.Completable("/clear", "清除上下文"),
        PaletteGroup.COMMANDS,
    )
    private val subagent = PaletteEntry(
        PaletteCommand.Insertable("/subagent", "派生子智能体"),
        PaletteGroup.SUBAGENTS,
    )

    private val sample: List<PaletteEntry> = listOf(insertPlan, newSession, completeCmd, subagent)

    @Test
    fun `只有根 slash 返回全部条目，按组分组并保留组顺序`() {
        val out = filterPalette(sample, "/")
        val groups = out.map { it.first }
        assertEquals(listOf(PaletteGroup.COMMANDS, PaletteGroup.SUBAGENTS, PaletteGroup.ACTIONS), groups)
        // COMMANDS 内保持源顺序：plan 先于 clear
        assertEquals(listOf(insertPlan, completeCmd), out.first().second)
        // 空组（SKILLS）被丢弃
        assertTrue(out.none { it.first == PaletteGroup.SKILLS })
    }

    @Test
    fun `按 trigger 子串大小写不敏感过滤`() {
        val out = filterPalette(sample, "/PLA") // 触发 /plan
        assertEquals(1, out.size)
        assertEquals(PaletteGroup.COMMANDS, out.first().first)
        assertEquals(listOf(insertPlan), out.first().second)
    }

    @Test
    fun `match 命中跨组时各自返回`() {
        val out = filterPalette(sample, "/NEW") // 触发 /new-session (ACTIONS)
        assertEquals(listOf(PaletteGroup.ACTIONS), out.map { it.first })
        assertEquals(listOf(newSession), out.first().second)
    }

    @Test
    fun `无匹配时返回空列表`() {
        assertEquals(emptyList<Pair<PaletteGroup, List<PaletteEntry>>>(), filterPalette(sample, "/zzz"))
    }

    @Test
    fun `本地动作不会作为服务端命令误投递`() {
        // /new-session 是 LocalKind.NEW_SESSION；不应作为 Completable 出现
        assertTrue(sample.none { (it.command as? PaletteCommand.Completable)?.trigger == "/new-session" })
        assertTrue(sample.any { it.command is PaletteCommand.Local && (it.command as PaletteCommand.Local).kind == LocalKind.NEW_SESSION })
    }

    @Test
    fun `空 source 与空 query 都安全返回空结果`() {
        assertEquals(emptyList<Pair<PaletteGroup, List<PaletteEntry>>>(), filterPalette(emptyList(), ""))
        assertEquals(emptyList<Pair<PaletteGroup, List<PaletteEntry>>>(), filterPalette(emptyList(), "/"))
    }

    @Test
    fun `可插入命令与完整命令按类型可区分`() {
        assertTrue(insertPlan.command is PaletteCommand.Insertable)
        assertTrue(completeCmd.command is PaletteCommand.Completable)
        // Insertable.id 必须以前缀 insert: 开头
        assertEquals("insert:/plan", insertPlan.command.id)
        assertEquals("complete:/clear", completeCmd.command.id)
    }
}
