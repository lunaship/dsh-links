package dev.deeplinks.native

import org.json.JSONArray
import org.json.JSONObject

internal const val MAX_QUESTION_CUSTOM_CHARS = 8_000

internal data class QuestionOption(
    val id: String,
    val label: String,
)

internal data class ClarifyingQuestion(
    val id: String,
    val header: String = "",
    val prompt: String = "",
    val options: List<QuestionOption> = emptyList(),
    val multiple: Boolean = false,
    val optional: Boolean = false,
    val unsupported: Boolean = false,
)

internal data class QuestionDraft(
    val selected: List<String> = emptyList(),
    val custom: String = "",
)

internal fun parseClarifyingQuestions(payloadJson: String?): List<ClarifyingQuestion> {
    val array = try {
        JSONArray(payloadJson ?: "[]")
    } catch (_: Exception) {
        JSONArray()
    }
    return parseClarifyingQuestions(array)
}

internal fun parseClarifyingQuestions(array: JSONArray): List<ClarifyingQuestion> {
    return (0 until array.length()).mapNotNull { index ->
        val obj = array.optJSONObject(index) ?: return@mapNotNull null
        val type = obj.optString("type")
        val unsupported = type.isNotBlank() && type != "select" && type != "text" && type != "input"
        val optionsArr = obj.optJSONArray("options") ?: JSONArray()
        val options = (0 until optionsArr.length()).map { optionIndex ->
            val option = optionsArr.opt(optionIndex)
            when (option) {
                is JSONObject -> {
                    val id = option.optString("id").ifBlank { option.optString("value") }.ifBlank { option.optString("label") }.ifBlank { "opt$optionIndex" }
                    QuestionOption(id = id, label = option.optString("label").ifBlank { id })
                }
                else -> {
                    val label = option?.toString().orEmpty()
                    QuestionOption(id = label.ifBlank { "opt$optionIndex" }, label = label)
                }
            }
        }
        ClarifyingQuestion(
            id = obj.optString("id").ifBlank { "q$index" },
            header = obj.optString("header"),
            prompt = obj.optString("question").ifBlank { obj.optString("prompt") }.ifBlank { obj.optString("text") },
            options = options,
            multiple = obj.optBoolean("multiple") || obj.optBoolean("allowMultiple") || obj.optBoolean("allow_multiple"),
            optional = obj.optBoolean("optional") || obj.opt("required") == false,
            unsupported = unsupported,
        )
    }
}

internal fun questionSummaryText(questions: List<ClarifyingQuestion>, fallback: String): String {
    if (questions.isEmpty()) return fallback
    if (questions.size == 1) return questions[0].prompt.ifBlank { fallback }
    return questions.joinToString("\n") { question ->
        question.prompt.ifBlank { question.header }.ifBlank { question.id }
    }
}

internal fun buildQuestionAnswers(
    questions: List<ClarifyingQuestion>,
    drafts: Map<String, QuestionDraft>,
): JSONObject? {
    if (questions.isEmpty()) return null
    if (questions.any { it.unsupported }) return null
    val answers = JSONArray()
    for (question in questions) {
        val draft = drafts[question.id] ?: QuestionDraft()
        val selected = draft.selected.filter { value ->
            question.options.isEmpty() || question.options.any { it.id == value || it.label == value }
        }
        val custom = draft.custom.trim()
        if (selected.isEmpty() && custom.isEmpty() && !question.optional) return null
        if (custom.length > MAX_QUESTION_CUSTOM_CHARS) return null
        val item = JSONObject().put("id", question.id).put("selected", JSONArray(selected))
        if (custom.isNotEmpty()) item.put("custom", custom)
        answers.put(item)
    }
    return JSONObject().put("answers", answers)
}

internal fun questionDraftComplete(questions: List<ClarifyingQuestion>, drafts: Map<String, QuestionDraft>): Boolean {
    if (questions.isEmpty() || questions.any { it.unsupported }) return false
    return questions.all { question ->
        val draft = drafts[question.id] ?: QuestionDraft()
        val hasSelection = draft.selected.isNotEmpty()
        val hasCustom = draft.custom.trim().isNotEmpty()
        question.optional || hasSelection || hasCustom
    }
}
