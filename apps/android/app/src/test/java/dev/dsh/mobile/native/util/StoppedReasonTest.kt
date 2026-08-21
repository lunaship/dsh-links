package dev.dsh.mobile.native.util

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StoppedReasonTest {
    @Test
    fun optNullableString_jsonNullIsAbsent() {
        val obj = JSONObject().put("stoppedReason", JSONObject.NULL)
        assertNull(obj.optNullableString("stoppedReason"))
    }

    @Test
    fun optNullableString_missingIsAbsent() {
        assertNull(JSONObject().optNullableString("stoppedReason"))
    }

    @Test
    fun optNullableString_readsRealValue() {
        val obj = JSONObject().put("stoppedReason", "interrupted")
        assertEquals("interrupted", obj.optNullableString("stoppedReason"))
    }

    @Test
    fun parseStoppedReason_dropsJsonNullLiteralAndCompleted() {
        assertNull(parseStoppedReason(null))
        assertNull(parseStoppedReason(""))
        assertNull(parseStoppedReason("null"))
        assertNull(parseStoppedReason("NULL"))
        assertNull(parseStoppedReason("completed"))
        assertNull(parseStoppedReason("undefined"))
    }

    @Test
    fun parseStoppedReason_keepsRealStopKinds() {
        assertEquals("interrupted", parseStoppedReason("interrupted"))
        assertEquals("error", parseStoppedReason("error"))
        assertEquals("maxTokens", parseStoppedReason("maxTokens"))
    }
}
