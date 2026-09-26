package dev.deeplinks.native

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuestionAnswersTest {
    @Test
    fun `逐题构造答案且第二题不复用第一题`() {
        val questions = parseClarifyingQuestions(
            JSONArray()
                .put(JSONObject().put("id", "q1").put("question", "一").put("options", JSONArray().put(JSONObject().put("id", "a").put("label", "A"))))
                .put(JSONObject().put("id", "q2").put("question", "二")),
        )
        val payload = buildQuestionAnswers(
            questions,
            mapOf(
                "q1" to QuestionDraft(selected = listOf("a")),
                "q2" to QuestionDraft(custom = "独立答案"),
            ),
        )
        assertNotNull(payload)
        val answers = payload!!.getJSONArray("answers")
        assertEquals("q1", answers.getJSONObject(0).getString("id"))
        assertEquals("a", answers.getJSONObject(0).getJSONArray("selected").getString(0))
        assertFalse(answers.getJSONObject(0).has("custom"))
        assertEquals("独立答案", answers.getJSONObject(1).getString("custom"))
    }

    @Test
    fun `未答完必填题不能提交`() {
        val questions = parseClarifyingQuestions(JSONArray().put(JSONObject().put("id", "q1").put("question", "必填")))
        assertNull(buildQuestionAnswers(questions, mapOf("q1" to QuestionDraft())))
        assertFalse(questionDraftComplete(questions, emptyMap()))
    }

    @Test
    fun `不支持题型不能构造答案`() {
        val questions = parseClarifyingQuestions(JSONArray().put(JSONObject().put("id", "q1").put("type", "rank").put("question", "排序")))
        assertTrue(questions[0].unsupported)
        assertNull(buildQuestionAnswers(questions, mapOf("q1" to QuestionDraft(custom = "猜"))))
    }
}

class RequestStateTest {
    @Test
    fun `终态优先且 pending 不能回滚`() {
        assertEquals(REQUEST_RESOLVED, mergeRequestStatus(REQUEST_RESOLVED, REQUEST_PENDING))
        assertEquals("allowed-once", mergeRequestOutcome("allowed-once", null, REQUEST_RESOLVED))
        val asked = MobileMessage(id = "approval-ap-1", role = "approval", text = "run", approvalId = "ap-1", requestStatus = REQUEST_PENDING)
        val decided = asked.copy(id = "approval-20", requestStatus = REQUEST_RESOLVED, outcome = "rejected")
        val merged = coalesceRequestMessages(listOf(asked, decided))
        assertEquals(1, merged.size)
        assertEquals(REQUEST_RESOLVED, merged[0].requestStatus)
        assertEquals("rejected", merged[0].outcome)
    }

    @Test
    fun `同 rpcId 的澄清卡归并为一张且 pending 不能回滚终态`() {
        val asked = MobileMessage(
            id = "question-q-1",
            role = "question",
            text = "选一个",
            questionRpcId = "q-1",
            questionPayloadJson = """[{"id":"q1","question":"选一个"}]""",
            requestStatus = REQUEST_PENDING,
        )
        val decided = asked.copy(id = "question-later", requestStatus = REQUEST_RESOLVED, outcome = "answered")
        val merged = coalesceRequestMessages(listOf(asked, decided))
        assertEquals(1, merged.size)
        assertEquals(REQUEST_RESOLVED, merged[0].requestStatus)
        assertEquals("answered", merged[0].outcome)
        assertEquals("选一个", merged[0].text)
    }

    @Test
    fun `快照终态覆盖 pending 审批卡`() {
        val pending = MobileMessage(id = "approval-ap-3", role = "approval", text = "run", approvalId = "ap-3", requestStatus = REQUEST_PENDING)
        val snapshot = SessionRequestSnapshot(
            approvals = listOf(SessionRequestState(id = "ap-3", kind = "approval", status = REQUEST_RESOLVED, outcome = "rejected")),
        )
        val applied = applyRequestSnapshotToMessages(listOf(pending), snapshot)
        assertEquals(REQUEST_RESOLVED, applied[0].requestStatus)
        assertEquals("rejected", applied[0].outcome)
    }

    @Test
    fun `快照补回 history 没有的 pending 澄清与审批卡`() {
        val history = listOf(MobileMessage(id = "msg-1", role = "user", text = "hi"))
        val snapshot = SessionRequestSnapshot(
            approvals = listOf(
                SessionRequestState(id = "ap-9", kind = "approval", status = REQUEST_PENDING, toolName = "bash", callId = "c-9"),
                SessionRequestState(id = "", kind = "approval", status = REQUEST_PENDING, toolName = "drop"),
                SessionRequestState(id = "ap-old", kind = "approval", status = REQUEST_RESOLVED, outcome = "rejected"),
            ),
            questions = listOf(
                SessionRequestState(
                    id = "q-9",
                    kind = "question",
                    status = REQUEST_PENDING,
                    questionsJson = """[{"id":"q1","question":"选一个","options":[{"id":"a","label":"A"}]}]""",
                ),
            ),
        )
        val merged = mergeMessagesWithRequestSnapshot(history, snapshot)
        assertEquals(listOf("msg-1", "approval-ap-9", "question-q-9"), merged.map { it.id })
        assertEquals("bash", merged[1].toolName)
        assertEquals("c-9", merged[1].callId)
        assertEquals("q-9", merged[2].questionRpcId)
        assertTrue(merged[2].questionPayloadJson!!.contains("选一个"))
        assertEquals("选一个", merged[2].text)
    }

    @Test
    fun `快照给空壳澄清卡补回题目内容`() {
        val empty = MobileMessage(
            id = "question-q-2",
            role = "question",
            text = "q-2",
            type = "question",
            questionRpcId = "q-2",
            requestStatus = REQUEST_PENDING,
        )
        val snapshot = SessionRequestSnapshot(
            questions = listOf(
                SessionRequestState(
                    id = "q-2",
                    kind = "question",
                    status = REQUEST_PENDING,
                    questionsJson = """[{"id":"q1","question":"还在吗"}]""",
                ),
            ),
        )
        val applied = applyRequestSnapshotToMessages(listOf(empty), snapshot)
        assertEquals("还在吗", applied[0].text)
        assertTrue(applied[0].questionPayloadJson!!.contains("还在吗"))
    }

    @Test
    fun `解析请求快照时丢掉空白 id 并保留 questions 数组`() {
        val root = JSONObject()
            .put(
                "approvals",
                JSONArray()
                    .put(JSONObject().put("approvalId", "").put("status", "pending"))
                    .put(JSONObject().put("approvalId", "ap-1").put("toolName", "bash").put("status", "pending")),
            )
            .put(
                "questions",
                JSONArray().put(
                    JSONObject()
                        .put("rpcId", "q-1")
                        .put("status", "pending")
                        .put("questions", JSONArray().put(JSONObject().put("id", "q1").put("question", "选一个"))),
                ),
            )
        val snapshot = parseSessionRequestSnapshot(root)
        assertEquals(1, snapshot.approvals.size)
        assertEquals("ap-1", snapshot.approvals[0].id)
        assertEquals("bash", snapshot.approvals[0].toolName)
        assertEquals("q-1", snapshot.questions[0].id)
        assertTrue(snapshot.questions[0].questionsJson!!.contains("选一个"))
    }
}
