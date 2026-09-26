package dev.deeplinks.native

internal const val REQUEST_PENDING = "pending"
internal const val REQUEST_RESOLVED = "resolved"
internal const val REQUEST_CANCELLED = "cancelled"
internal const val REQUEST_EXPIRED = "expired"
internal const val REQUEST_UNKNOWN = "unknown"

internal fun approvalUiStatus(outcome: String?): String = when (outcome) {
    null, "" -> REQUEST_PENDING
    "allowed-once", "rejected" -> REQUEST_RESOLVED
    "cancelled" -> REQUEST_CANCELLED
    "unavailable" -> REQUEST_EXPIRED
    else -> REQUEST_UNKNOWN
}

internal fun isTerminalRequestStatus(status: String?): Boolean =
    status == REQUEST_RESOLVED || status == REQUEST_CANCELLED || status == REQUEST_EXPIRED

private fun requestStatusRank(status: String?): Int = when (status) {
    REQUEST_RESOLVED, REQUEST_CANCELLED, REQUEST_EXPIRED -> 4
    REQUEST_UNKNOWN -> 2
    REQUEST_PENDING -> 1
    else -> 0
}

internal fun mergeRequestStatus(current: String?, incoming: String?): String? {
    if (incoming.isNullOrBlank()) return current
    if (current.isNullOrBlank()) return incoming
    if (isTerminalRequestStatus(current) && incoming == REQUEST_PENDING) return current
    return if (requestStatusRank(incoming) >= requestStatusRank(current)) incoming else current
}

internal fun mergeRequestOutcome(current: String?, incoming: String?, status: String?): String? {
    if (isTerminalRequestStatus(status) && !incoming.isNullOrBlank()) return incoming
    if (isTerminalRequestStatus(status) && !current.isNullOrBlank()) return current
    return incoming?.takeIf { it.isNotBlank() } ?: current
}

internal fun coalesceRequestMessages(messages: List<MobileMessage>): List<MobileMessage> {
    val afterApprovals = coalesceKeyedRequestMessages(messages, "approval") { it.approvalId }
    return coalesceKeyedRequestMessages(afterApprovals, "question") { it.questionRpcId }
}

private fun coalesceKeyedRequestMessages(
    messages: List<MobileMessage>,
    role: String,
    keyOf: (MobileMessage) -> String?,
): List<MobileMessage> {
    if (messages.none { it.role == role && !keyOf(it).isNullOrBlank() }) return messages
    val winnerById = LinkedHashMap<String, Pair<Int, MobileMessage>>()
    messages.forEachIndexed { index, message ->
        val key = keyOf(message)
        if (message.role != role || key.isNullOrBlank()) return@forEachIndexed
        val existing = winnerById[key]
        if (existing == null) {
            winnerById[key] = index to message
            return@forEachIndexed
        }
        val mergedStatus = mergeRequestStatus(existing.second.requestStatus, message.requestStatus)
        val merged = existing.second.copy(
            requestStatus = mergedStatus,
            outcome = mergeRequestOutcome(existing.second.outcome, message.outcome, mergedStatus),
            text = existing.second.text.ifBlank { message.text },
            toolName = existing.second.toolName ?: message.toolName,
            callId = existing.second.callId ?: message.callId,
            approvalId = existing.second.approvalId ?: message.approvalId,
            questionRpcId = existing.second.questionRpcId ?: message.questionRpcId,
            questionPayloadJson = existing.second.questionPayloadJson ?: message.questionPayloadJson,
            questionOptions = existing.second.questionOptions.ifEmpty { message.questionOptions },
            questionHeader = existing.second.questionHeader ?: message.questionHeader,
        )
        winnerById[key] = existing.first to merged
    }
    if (winnerById.isEmpty()) return messages
    val used = HashSet<String>()
    return messages.mapIndexedNotNull { index, message ->
        val key = keyOf(message)
        if (message.role != role || key.isNullOrBlank()) return@mapIndexedNotNull message
        val winner = winnerById[key] ?: return@mapIndexedNotNull message
        if (winner.first != index) return@mapIndexedNotNull null
        if (!used.add(key)) return@mapIndexedNotNull null
        winner.second
    }
}

internal fun applyRequestState(message: MobileMessage, status: String?, outcome: String?): MobileMessage {
    val mergedStatus = mergeRequestStatus(message.requestStatus, status)
    return message.copy(
        requestStatus = mergedStatus,
        outcome = mergeRequestOutcome(message.outcome, outcome, mergedStatus),
    )
}

internal fun applyRequestSnapshotToMessages(
    messages: List<MobileMessage>,
    snapshot: SessionRequestSnapshot,
): List<MobileMessage> {
    if (snapshot.approvals.isEmpty() && snapshot.questions.isEmpty()) return messages
    return messages.map { msg ->
        when {
            msg.role == "approval" && !msg.approvalId.isNullOrBlank() -> {
                val rec = snapshot.approvals.firstOrNull { it.id == msg.approvalId } ?: return@map msg
                val withState = applyRequestState(msg, rec.status, rec.outcome)
                withState.copy(
                    toolName = withState.toolName ?: rec.toolName,
                    callId = withState.callId ?: rec.callId,
                    text = withState.text.ifBlank { rec.toolName.orEmpty() },
                )
            }
            msg.role == "question" && !msg.questionRpcId.isNullOrBlank() -> {
                val rec = snapshot.questions.firstOrNull { it.id == msg.questionRpcId } ?: return@map msg
                val withState = applyRequestState(msg, rec.status, rec.outcome)
                if (!withState.questionPayloadJson.isNullOrBlank() || rec.questionsJson.isNullOrBlank()) withState
                else {
                    val parsed = parseClarifyingQuestions(rec.questionsJson)
                    withState.copy(
                        questionPayloadJson = rec.questionsJson,
                        questionOptions = parsed.firstOrNull()?.options?.map { it.label }.orEmpty(),
                        questionHeader = parsed.firstOrNull()?.header?.takeIf { it.isNotBlank() },
                        text = questionSummaryText(parsed, withState.text.ifBlank { rec.id }),
                    )
                }
            }
            else -> msg
        }
    }
}

/** 历史没有澄清/审批投影时，用请求快照把仍 pending 的卡片补回尾页。 */
internal fun mergeMessagesWithRequestSnapshot(
    messages: List<MobileMessage>,
    snapshot: SessionRequestSnapshot,
): List<MobileMessage> {
    val updated = applyRequestSnapshotToMessages(messages, snapshot)
    val approvalIds = updated.mapNotNull { it.approvalId }.toSet()
    val questionIds = updated.mapNotNull { it.questionRpcId }.toSet()
    val extras = buildList {
        for (rec in snapshot.approvals) {
            if (rec.id.isBlank() || rec.status != REQUEST_PENDING || rec.id in approvalIds) continue
            add(
                MobileMessage(
                    id = "approval-${rec.id}",
                    role = "approval",
                    text = rec.toolName.orEmpty(),
                    toolName = rec.toolName,
                    approvalId = rec.id,
                    callId = rec.callId,
                    type = "approval",
                    requestStatus = rec.status,
                    outcome = rec.outcome,
                ),
            )
        }
        for (rec in snapshot.questions) {
            if (rec.id.isBlank() || rec.status != REQUEST_PENDING || rec.id in questionIds) continue
            val parsed = parseClarifyingQuestions(rec.questionsJson)
            add(
                MobileMessage(
                    id = "question-${rec.id}",
                    role = "question",
                    text = questionSummaryText(parsed, rec.id),
                    type = "question",
                    questionRpcId = rec.id,
                    questionOptions = parsed.firstOrNull()?.options?.map { it.label }.orEmpty(),
                    questionHeader = parsed.firstOrNull()?.header?.takeIf { it.isNotBlank() },
                    questionPayloadJson = rec.questionsJson,
                    requestStatus = rec.status,
                ),
            )
        }
    }
    return if (extras.isEmpty()) updated else coalesceRequestMessages(updated + extras)
}
