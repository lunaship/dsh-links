package dev.deeplinks.native

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MobileWorkspaceParsingTest {
    @Test
    fun parsesCommittedWorkspaceCreateEcho() {
        val parsed = parseMobileWorkspace(
            JSONObject()
                .put("workspaceId", "workspace-1")
                .put("path", "/Volumes/Space/Dev/demo/")
                .put("title", "Demo")
                .put("sessionIds", JSONArray().put("s1").put("s2")),
        )

        assertEquals(
            MobileWorkspace(
                workspaceId = "workspace-1",
                path = "/Volumes/Space/Dev/demo",
                title = "Demo",
                sessionIds = listOf("s1", "s2"),
            ),
            parsed,
        )
    }

    @Test
    fun rejectsWorkspaceWithoutUsablePath() {
        assertNull(parseMobileWorkspace(JSONObject()))
    }

    @Test
    fun parseMobileSession_jsonNullAgentPresetIsAbsent() {
        val parsed = parseMobileSession(
            JSONObject()
                .put("sessionId", "s1")
                .put("title", "Reply with exactly PONG015")
                .put("agentPreset", JSONObject.NULL)
                .put("cwd", JSONObject.NULL),
        )
        assertNull(parsed.agentPreset)
        assertNull(parsed.cwd)
        assertEquals("Reply with exactly PONG015", parsed.title)
    }

    @Test
    fun parseMobileSession_literalNullAgentPresetIsAbsent() {
        val parsed = parseMobileSession(
            JSONObject()
                .put("sessionId", "s1")
                .put("agentPreset", "null"),
        )
        assertNull(parsed.agentPreset)
        assertEquals("未命名会话", parsed.title)
    }

    @Test
    fun resolveHarnessLabel_fallsBackWhenPresetIsJsonNullLiteral() {
        assertEquals(
            "标准模式",
            resolveHarnessLabel(
                presets = emptyList(),
                activeId = "null",
                settingsId = "",
                fallback = "标准模式",
            ),
        )
        assertEquals(
            "标准",
            resolveHarnessLabel(
                presets = listOf(MobileAgentPreset("standard", "标准")),
                activeId = null,
                settingsId = "standard",
                fallback = "标准模式",
            ),
        )
    }

    @Test
    fun parseHistoryFiles_reads_string_array() {
        val files = parseHistoryFiles(
            JSONObject().put("files", JSONArray().put("notes/hi.md").put("shot.png")),
        )
        assertEquals(listOf("notes/hi.md", "shot.png"), files)
    }

    @Test
    fun parseHistoryFiles_skips_blank() {
        assertEquals(emptyList<String>(), parseHistoryFiles(JSONObject()))
    }
}
