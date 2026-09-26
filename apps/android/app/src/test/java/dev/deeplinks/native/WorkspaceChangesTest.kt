package dev.deeplinks.native

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceChangesTest {

    private val summaryJson = """
        {"turn":3,"total":5,"added":13,"deleted":3,"files":[
          {"path":"../up.txt","display":"../up.txt","added":1,"deleted":0},
          {"path":"src/a.ts","display":"src/a.ts","added":12,"deleted":3},
          {"path":"img.png","display":"img.png","added":0,"deleted":0,"binary":true},
          {"path":"","display":"skip"},
          {"path":"dist/big.map","display":"dist/big.map","oversized":true}
        ]}
    """.trimIndent()

    private fun card(seq: Long, turn: Int, files: Int = 1) = workspaceChangesMessage(
        WorkspaceChangesSummary(
            seq = seq,
            turn = turn,
            total = files,
            added = 1,
            deleted = 0,
            files = (0 until files).map { ChangedFile("f$it", "f$it", added = 1) },
        ),
        time = seq,
    )

    private fun text(id: String, seq: Long, role: String = "assistant") =
        MobileMessage(id = id, role = role, text = id, seq = seq)

    @Test
    fun `summary parse keeps order and flags, drops blank paths, total never below files`() {
        val s = parseWorkspaceChanges(JSONObject(summaryJson), seq = 42)!!
        assertEquals(42L, s.seq)
        assertEquals(3, s.turn)
        assertEquals(listOf("../up.txt", "src/a.ts", "img.png", "dist/big.map"), s.files.map { it.display })
        assertTrue(s.files[2].binary)
        assertTrue(s.files[3].oversized)
        assertEquals(5, s.total)
        assertFalse(s.complete)
        assertEquals("a.ts", s.files[1].name)
        assertEquals("src", s.files[1].directory)
        assertEquals("", s.files[2].directory)

        val low = parseWorkspaceChanges(JSONObject("""{"total":0,"files":[{"path":"a"}]}"""), 1)!!
        assertEquals(1, low.total)
        assertTrue(low.complete)
    }

    @Test
    fun `summary without files yields no card`() {
        assertNull(parseWorkspaceChanges(JSONObject("""{"turn":1,"files":[]}"""), 1))
        assertNull(parseWorkspaceChanges(JSONObject("""{"turn":1}"""), 1))
    }

    @Test
    fun `diff parse covers text, truncation and degraded kinds`() {
        val text = parseWorkspaceFileDiff(
            JSONObject(
                """{"kind":"text","path":"a","display":"a","before":false,"after":true,"coarse":true,
                   "hunks":[{"oldStart":1,"oldLines":0,"newStart":1,"newLines":2,"lines":["+x","+y"]}],
                   "truncated":{"shownLines":2,"totalLines":9}}""",
            ),
        ) as WorkspaceFileDiff.Text
        assertTrue(text.truncated)
        assertEquals(listOf(DiffNote.CREATED, DiffNote.COARSE, DiffNote.TRUNCATED), diffNotes(text))
        assertTrue(parseWorkspaceFileDiff(JSONObject("""{"kind":"binary","path":"b"}""")) is WorkspaceFileDiff.Binary)
        assertTrue(parseWorkspaceFileDiff(JSONObject("""{"kind":"oversized","path":"c"}""")) is WorkspaceFileDiff.Oversized)
        assertNull(parseWorkspaceFileDiff(JSONObject("""{"kind":"weird"}""")))

        val same = WorkspaceFileDiff.Text("a", "a", before = true, after = false, coarse = false, hunks = emptyList())
        assertEquals(listOf(DiffNote.DELETED, DiffNote.UNCHANGED), diffNotes(same))
    }

    @Test
    fun `diff rows number both sides from hunk starts`() {
        val rows = diffRows(
            listOf(DiffHunk(oldStart = 40, oldLines = 3, newStart = 41, newLines = 3, lines = listOf(" a", "-b", "+c", " d", ""))),
        )
        assertEquals(DiffRow.Kind.HUNK, rows[0].kind)
        assertEquals("@@ -40,3 +41,3 @@", rows[0].text)
        assertEquals(DiffRow(DiffRow.Kind.CONTEXT, 40, 41, "a"), rows[1])
        assertEquals(DiffRow(DiffRow.Kind.DELETE, 41, null, "b"), rows[2])
        assertEquals(DiffRow(DiffRow.Kind.ADD, null, 42, "c"), rows[3])
        assertEquals(DiffRow(DiffRow.Kind.CONTEXT, 42, 43, "d"), rows[4])
        // 空行（缺前缀）当作上下文，不崩
        assertEquals(DiffRow(DiffRow.Kind.CONTEXT, 43, 44, ""), rows[5])
    }

    @Test
    fun `later announcement for the same turn replaces the earlier card`() {
        val list = listOf(text("u", 1, "user"), card(5, turn = 3), text("m", 6), card(9, turn = 3, files = 2), card(12, turn = 4))
        val out = coalesceWorkspaceChanges(list)
        assertEquals(listOf("u", "m", "changes-9", "changes-12"), out.map { it.id })
        assertEquals(listOf(12L, 9L), sessionChangeSummaries(list).map { it.seq })
        // 没有改动卡的列表原样返回（同一实例，不做多余拷贝）
        val plain = listOf(text("a", 1))
        assertTrue(coalesceWorkspaceChanges(plain) === plain)
    }

    @Test
    fun `history pages merge coalesces cards across the page boundary`() {
        val older = listOf(text("u1", 1, "user"), card(5, turn = 1))
        val tail = listOf(card(8, turn = 1), text("u2", 10, "user"))
        assertEquals(listOf("u1", "changes-8", "u2"), mergeHistoryPages(older, tail).map { it.id })
    }

    @Test
    fun `live card survives a refresh that has not reached its announcement`() {
        val live = listOf(text("u", 1, "user"), card(7, turn = 1))
        val fresh = listOf(text("u", 1, "user"), text("m", 5))
        assertEquals(listOf("u", "m", "changes-7"), mergeHistoryWithLive(fresh, live).map { it.id })
    }

    @Test
    fun `live card is dropped once history covers it without a card`() {
        val live = listOf(text("u", 1, "user"), card(7, turn = 1))
        // history 已推进到 seq 9 仍没有这张卡：同轮被取代为空或 Host 已取不到摘要
        val fresh = listOf(text("u", 1, "user"), text("m", 9))
        assertEquals(listOf("u", "m"), mergeHistoryWithLive(fresh, live).map { it.id })
    }

    @Test
    fun `history card with same id wins over the live copy`() {
        val live = listOf(card(7, turn = 1, files = 1))
        val fresh = listOf(card(7, turn = 1, files = 3))
        val merged = mergeHistoryWithLive(fresh, live)
        assertEquals(1, merged.size)
        assertEquals(3, merged[0].changes?.files?.size)
    }

    @Test
    fun `raw fallback never swallows the changes card`() {
        assertFalse(isRawFallback(card(1, turn = 1)))
    }

    @Test
    fun `panel is full screen on phones and keeps 400dp for the main column on tablets`() {
        assertEquals(412f, changesPanelWidthDp(412f))
        assertEquals(599f, changesPanelWidthDp(599f))
        // 840dp：45% = 378，主区剩 462 ≥ 400
        assertEquals(378f, changesPanelWidthDp(840f), 0.01f)
        // 1280dp：45% = 576
        assertEquals(576f, changesPanelWidthDp(1280f), 0.01f)
        // 700dp：45% = 315 → 至少 360；主区只剩 340 < 400 时仍保 360 的可用面板
        assertEquals(360f, changesPanelWidthDp(700f), 0.01f)
    }

    @Test
    fun `release settles by fling first, then by distance`() {
        assertTrue(settleChangesPanelOpen(0.1f, 2_000f, 1_000f))
        assertFalse(settleChangesPanelOpen(0.9f, -2_000f, 1_000f))
        assertTrue(settleChangesPanelOpen(0.5f, 0f, 1_000f))
        assertFalse(settleChangesPanelOpen(0.2f, 300f, 1_000f))
    }
}
