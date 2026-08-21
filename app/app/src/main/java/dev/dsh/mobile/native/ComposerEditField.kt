package dev.dsh.mobile.native

import android.graphics.Color as AndroidColor
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.TextView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.widget.TextViewCompat

/**
 * Chat composer backed by a real [EditText].
 *
 * Compose BasicTextField often restarts the IME InputConnection when controlled
 * state / KeyboardOptions change mid-session. Chinese IME voice dictation then
 * listens but never commits. Native EditText keeps a stable connection and
 * composing spans.
 */
@Composable
fun ComposerEditField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    hint: String = "",
    textColor: Color,
    hintColor: Color,
    cursorColor: Color,
    fontSize: TextUnit = 15.sp,
    lineHeight: TextUnit = 23.sp,
) {
    val density = LocalDensity.current
    val latestValue by rememberUpdatedState(value)
    val latestOnChange by rememberUpdatedState(onValueChange)
    val textArgb = textColor.toArgb()
    val hintArgb = hintColor.toArgb()
    val cursorArgb = cursorColor.toArgb()
    val textSizePx = with(density) { fontSize.toPx() }
    val lineHeightPx = with(density) { lineHeight.toPx() }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            val watcher = ComposerTextWatcher(
                currentValue = { latestValue },
                onChange = { latestOnChange(it) },
            )
            EditText(context).apply {
                tag = watcher
                setBackgroundColor(AndroidColor.TRANSPARENT)
                setTextColor(textArgb)
                setHintTextColor(hintArgb)
                highlightColor = (cursorArgb and 0x00FFFFFF) or 0x33000000
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, textSizePx)
                TextViewCompat.setLineHeight(this, lineHeightPx.toInt())
                gravity = Gravity.TOP or Gravity.START
                setPadding(0, 0, 0, 0)
                includeFontPadding = false
                isFocusable = true
                isFocusableInTouchMode = true
                isVerticalScrollBarEnabled = true
                overScrollMode = android.view.View.OVER_SCROLL_IF_CONTENT_SCROLLS
                // Stable flags — flipping these while focused breaks voice IME.
                inputType = InputType.TYPE_CLASS_TEXT or
                    InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                    InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
                    InputType.TYPE_TEXT_FLAG_AUTO_CORRECT
                imeOptions = EditorInfo.IME_FLAG_NO_ENTER_ACTION
                maxLines = 8
                minLines = 2
                importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO
                setText(value, TextView.BufferType.EDITABLE)
                setSelection(text?.length ?: 0)
                this.hint = hint
                addTextChangedListener(watcher)
            }
        },
        update = { edit ->
            edit.setTextColor(textArgb)
            edit.setHintTextColor(hintArgb)
            edit.hint = hint
            edit.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, textSizePx)
            TextViewCompat.setLineHeight(edit, lineHeightPx.toInt())

            val editable = edit.text
            val current = editable?.toString().orEmpty()
            if (current == latestValue) return@AndroidView

            val composing = editable != null &&
                BaseInputConnection.getComposingSpanStart(editable) >= 0
            if (composing) return@AndroidView

            val watcher = edit.tag as? ComposerTextWatcher
            val apply = {
                edit.setText(latestValue, TextView.BufferType.EDITABLE)
                val len = edit.text?.length ?: 0
                edit.setSelection(latestValue.length.coerceIn(0, len))
            }
            if (watcher != null) watcher.withSelfChange(apply) else apply()
        },
    )
}

private class ComposerTextWatcher(
    private val currentValue: () -> String,
    private val onChange: (String) -> Unit,
) : TextWatcher {
    private var selfChange = false

    fun withSelfChange(block: () -> Unit) {
        selfChange = true
        try {
            block()
        } finally {
            selfChange = false
        }
    }

    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
    override fun afterTextChanged(s: Editable?) {
        if (selfChange) return
        val next = s?.toString().orEmpty()
        if (next != currentValue()) onChange(next)
    }
}
