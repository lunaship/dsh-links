package dev.dsh.mobile.native
import dev.dsh.mobile.core.verifyPatchApplied
import dev.dsh.mobile.core.withNamespace

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AppSettings 契约（WI-004）：服务端命名空间 → 配置模型映射；
 * 保存必须校验读回值一致，不一致视为保存失败（消灭假成功）。
 */
class AppSettingsTest {

    private fun ns(name: String, value: JSONObject) = MobileSettingsNamespace(ns = name, value = value, revision = 3)

    @Test
    fun `服务端命名空间映射到 AppSettings 默认值`() {
        val namespaces = listOf(
            ns("agent-presets", JSONObject().put("default", "code")),
            ns("permission", JSONObject().put("defaultPreset", "read-only")),
            ns("locale", JSONObject().put("preference", "en")),
            ns("ui-theme", JSONObject().put("preference", "dark")),
            ns("ui-conversation", JSONObject().put("busyEnter", "steer")),
            ns("agent-default-model", JSONObject().put("provider", "deepseek-official").put("model", "deepseek-v4-flash").put("reasoningEffort", "high")),
        )
        val s = AppSettings.fromServer(namespaces)
        assertEquals("code", s.agentPreset)
        assertEquals("read-only", s.permissionPreset)
        assertEquals("en", s.language)
        assertEquals("dark", s.theme)
        assertEquals("steer", s.busyEnter)
        assertEquals("deepseek-official", s.defaultModelProvider)
        assertEquals("deepseek-v4-flash", s.defaultModel)
        assertEquals("high", s.defaultReasoningEffort)
    }

    @Test
    fun `缺少命名空间时回退默认值（不崩溃）`() {
        val s = AppSettings.fromServer(emptyList())
        assertEquals("standard", s.agentPreset)
        assertEquals("workspace-write", s.permissionPreset)
        assertTrue(s.defaultModelProvider == null)
    }

    @Test
    fun `服务端读回合并进本地配置模型`() {
        val base = AppSettings(agentPreset = "standard", permissionPreset = "workspace-write")
        val merged = base.withNamespace("agent-presets", JSONObject().put("default", "minimal"))
        assertEquals("minimal", merged.agentPreset)
        assertEquals("workspace-write", merged.permissionPreset) // 未提交的字段保持不变
        val merged2 = base.withNamespace("permission", JSONObject().put("defaultPreset", "danger-full-access"))
        assertEquals("danger-full-access", merged2.permissionPreset)
    }

    @Test
    fun `读回值与提交值一致时校验通过`() {
        val patch = JSONObject().put("default", "code")
        val readBack = JSONObject().put("default", "code")
        verifyPatchApplied(readBack, patch, "agent-presets") // 不抛异常
    }

    @Test
    fun `读回值与提交值不一致时视为保存失败`() {
        val patch = JSONObject().put("default", "code")
        val readBack = JSONObject().put("default", "standard")
        val ex = assertThrows(IllegalStateException::class.java) {
            verifyPatchApplied(readBack, patch, "agent-presets")
        }
        assertTrue(ex.message!!.contains("保存校验失败"))
    }

    @Test
    fun `withNamespace 可清空默认模型`() {
        val base = AppSettings(
            defaultModelProvider = "deepseek-official",
            defaultModel = "deepseek-v4-flash",
            defaultReasoningEffort = "high",
        )
        val merged = base.withNamespace(
            "agent-default-model",
            JSONObject()
                .put("provider", "deepseek-official")
                .put("model", JSONObject.NULL)
                .put("reasoningEffort", JSONObject.NULL),
        )
        assertEquals("deepseek-official", merged.defaultModelProvider)
        assertNull(merged.defaultModel)
        assertNull(merged.defaultReasoningEffort)
    }

    @Test
    fun `读回 null 与提交 NULL 视为一致`() {
        val patch = JSONObject().put("model", JSONObject.NULL)
        val readBack = JSONObject().put("provider", "x").put("model", JSONObject.NULL)
        verifyPatchApplied(readBack, patch, "agent-default-model")
    }
}
