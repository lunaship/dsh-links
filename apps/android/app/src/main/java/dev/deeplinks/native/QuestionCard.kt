package dev.deeplinks.native

import dev.deeplinks.core.DshType

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.deeplinks.core.Dsh
import dev.deeplinks.core.DshS
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * ask_user_question 澄清卡：逐题独立选择或自由文本；提交后等服务端 ack。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun QuestionCard(
    msg: MobileMessage,
    onAnswer: (rpcId: String, answer: JSONObject, onDone: (Boolean) -> Unit) -> Unit,
) {
    val strings = DshS
    val cardScope = rememberCoroutineScope()
    val questions = remember(msg.id, msg.questionPayloadJson) { parseClarifyingQuestions(msg.questionPayloadJson) }
    val drafts = remember(msg.id) { mutableStateMapOf<String, QuestionDraft>() }
    var sent by remember(msg.id, msg.requestStatus) { mutableStateOf(isTerminalRequestStatus(msg.requestStatus)) }
    var submitting by remember(msg.id) { mutableStateOf(false) }
    var submitError by remember(msg.id) { mutableStateOf<String?>(null) }
    val rpcId = msg.questionRpcId.orEmpty()
    val locked = sent || isTerminalRequestStatus(msg.requestStatus)

    if (locked) {
        Text(
            strings.questionSubmitted,
            color = Dsh.labelSecondary,
            style = DshType.t13,
            modifier = Modifier
                .clip(RoundedCornerShape(DshRadius.sm))
                .background(Dsh.bgSurface)
                .border(1.dp, Dsh.borderSubtle, RoundedCornerShape(DshRadius.sm))
                .padding(horizontal = 12.dp, vertical = 8.dp),
        )
        return
    }

    fun draftOf(id: String) = drafts[id] ?: QuestionDraft()
    fun updateDraft(id: String, transform: (QuestionDraft) -> QuestionDraft) {
        drafts[id] = transform(draftOf(id))
    }

    val displayQuestions = questions.ifEmpty {
        listOf(
            ClarifyingQuestion(
                id = "q0",
                prompt = msg.text,
                options = msg.questionOptions.map { QuestionOption(it, it) },
            ),
        )
    }

    Column(
        modifier = Modifier
            .widthIn(max = 340.dp)
            .fillMaxWidth()
            .heightIn(min = 120.dp)
            .shadow(6.dp, RoundedCornerShape(DshRadius.lg), ambientColor = Dsh.shadowCard, spotColor = Dsh.shadowCard)
            .clip(RoundedCornerShape(DshRadius.lg))
            .background(Dsh.bgSurface)
            .border(1.dp, Dsh.borderSubtle, RoundedCornerShape(DshRadius.lg))
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text(
            msg.questionHeader?.takeIf { it.isNotBlank() } ?: strings.questionClarify,
            color = Dsh.labelTertiary,
            style = DshType.t11M,
            fontWeight = FontWeight(500),
        )
        if (displayQuestions.any { it.unsupported }) {
            Spacer(Modifier.height(8.dp))
            Text(
                strings.questionUnsupportedOnPhone,
                color = Dsh.labelSecondary,
                style = DshType.titleSmall,
            )
            return@Column
        }
        displayQuestions.forEachIndexed { questionIndex, question ->
            if (questionIndex > 0) Spacer(Modifier.height(12.dp))
            else Spacer(Modifier.height(4.dp))
            if (displayQuestions.size > 1) {
                Text(
                    strings.questionIndex.format(questionIndex + 1),
                    color = Dsh.labelTertiary,
                    style = DshType.t11M,
                    fontWeight = FontWeight(500),
                )
                Spacer(Modifier.height(2.dp))
            }
            Text(
                question.prompt.ifBlank { msg.text },
                color = Dsh.labelPrimary,
                style = DshType.titleSmall,
                fontWeight = FontWeight(500),
                lineHeight = 18.sp,
            )
            val draft = draftOf(question.id)
            val bringIntoView = remember(question.id) { BringIntoViewRequester() }
            if (question.options.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                question.options.forEachIndexed { index, option ->
                    val isSelected = option.id in draft.selected || option.label in draft.selected
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(DshRadius.sm))
                            .selectable(
                                selected = isSelected,
                                onClick = {
                                    updateDraft(question.id) { current ->
                                        val nextSelected = if (question.multiple) {
                                            if (isSelected) current.selected - option.id else current.selected + option.id
                                        } else {
                                            listOf(option.id)
                                        }
                                        current.copy(selected = nextSelected, custom = if (question.multiple) current.custom else "")
                                    }
                                },
                                role = Role.RadioButton,
                            )
                            .semantics { contentDescription = option.label }
                            .heightIn(min = 48.dp)
                            .padding(vertical = 6.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "${index + 1}. ${option.label}",
                            color = if (isSelected) Dsh.labelPrimary else Dsh.labelSecondary,
                            style = DshType.t13,
                            fontWeight = if (isSelected) FontWeight(600) else FontWeight.Normal,
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            BasicTextField(
                value = draft.custom,
                onValueChange = { value ->
                    updateDraft(question.id) { current ->
                        current.copy(
                            custom = value.take(MAX_QUESTION_CUSTOM_CHARS),
                            selected = if (value.isNotBlank() && !question.multiple) emptyList() else current.selected,
                        )
                    }
                },
                maxLines = 5,
                textStyle = DshType.t13.copy(color = Dsh.labelPrimary),
                cursorBrush = SolidColor(Dsh.labelPrimary),
                decorationBox = { inner ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp, max = 140.dp)
                            .clip(RoundedCornerShape(DshRadius.sm))
                            .background(Dsh.bgCard)
                            .border(1.dp, Dsh.borderSubtle, RoundedCornerShape(DshRadius.sm))
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                    ) {
                        if (draft.custom.isEmpty()) {
                            Text(strings.questionAnswerHint, color = Dsh.labelTertiary, style = DshType.t13)
                        }
                        inner()
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp, max = 140.dp)
                    .bringIntoViewRequester(bringIntoView)
                    .onFocusChanged { focus ->
                        if (focus.isFocused) cardScope.launch { bringIntoView.bringIntoView() }
                    }
                    .semantics { contentDescription = strings.questionCustomAnswerDescription },
            )
        }
        Spacer(Modifier.height(10.dp))
        val shownError = submitError
        if (shownError != null) {
            Text(
                shownError,
                color = Dsh.error,
                style = DshType.caption,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = shownError },
            )
            Spacer(Modifier.height(8.dp))
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val canSend = !submitting && rpcId.isNotBlank() && questionDraftComplete(displayQuestions, drafts)
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clickable(enabled = canSend) {
                        val answer = buildQuestionAnswers(displayQuestions, drafts) ?: return@clickable
                        submitting = true
                        submitError = null
                        onAnswer(rpcId, answer) { ok ->
                            submitting = false
                            if (ok) sent = true
                            else submitError = strings.approvalNotAccepted
                        }
                    }
                    .semantics {
                        role = Role.Button
                        contentDescription = strings.questionSubmitAnswer
                    },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (canSend) Dsh.labelPrimary else Dsh.bgTrack),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Default.KeyboardArrowUp,
                        contentDescription = null,
                        tint = if (canSend) Dsh.bgSurface else Dsh.labelTertiary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}