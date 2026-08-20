package dev.dsh.mobile.native

import org.junit.Assert.assertEquals
import org.junit.Test

class SelectModelErrorTest {
    @Test
    fun parsesJsonErrorBody() {
        assertEquals("调用 API 失败", parseMobileApiError(502, """{"error":"调用 API 失败"}"""))
    }

    @Test
    fun mapsMissingAdapter() {
        assertEquals(
            "找不到该模型供应商，请确认电脑端已启用对应插件",
            friendlySelectModelError("""no adapter registered for provider "DeepSeek""""),
        )
    }

    @Test
    fun mapsChineseApiFailure() {
        assertEquals(
            "电脑端调用模型接口失败，请检查 DeepSeek 密钥后重载插件",
            friendlySelectModelError("调用 API 失败"),
        )
    }
}
