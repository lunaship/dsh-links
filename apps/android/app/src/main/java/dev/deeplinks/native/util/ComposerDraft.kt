package dev.deeplinks.native.util

/**
 * 输入条草稿跟会话走（对照 OpenClaw：附件/语音不得泄漏到切走后的会话）。
 * 未提交草稿不跨进程持久化；进程内按会话键暂存。
 */
data class ComposerDraft(
    val text: String = "",
    val images: List<Pair<String, String>> = emptyList(),
) {
    val isEmpty: Boolean get() = text.isBlank() && images.isEmpty()
}

const val COMPOSER_MAX_IMAGES = SHARE_IMAGE_LIMIT

fun composerDraftKey(sessionId: String?): String = sessionId.orEmpty()

fun stashComposerDraft(
    drafts: Map<String, ComposerDraft>,
    fromKey: String,
    toKey: String,
    current: ComposerDraft,
): Pair<Map<String, ComposerDraft>, ComposerDraft> {
    if (fromKey == toKey) return drafts to current
    val stored = if (current.isEmpty) drafts - fromKey else drafts + (fromKey to current)
    return stored to (stored[toKey] ?: ComposerDraft())
}

fun mergeComposerText(draft: ComposerDraft, addition: String): ComposerDraft {
    val t = addition.trim()
    if (t.isBlank()) return draft
    return draft.copy(text = if (draft.text.isBlank()) t else "${draft.text.trimEnd()} $t")
}

fun appendComposerImage(
    draft: ComposerDraft,
    image: Pair<String, String>,
    maxImages: Int = COMPOSER_MAX_IMAGES,
): ComposerDraft {
    if (draft.images.size >= maxImages) return draft
    return draft.copy(images = draft.images + image)
}

fun putComposerDraft(
    drafts: Map<String, ComposerDraft>,
    key: String,
    draft: ComposerDraft,
): Map<String, ComposerDraft> =
    if (draft.isEmpty) drafts - key else drafts + (key to draft)

fun putComposerDraftError(
    errors: Map<String, String>,
    key: String,
    message: String,
): Map<String, String> {
    val text = message.trim()
    return if (text.isEmpty()) errors - key else errors + (key to text)
}

/**
 * 切会话时把当前输入槽错误停在来源会话，并取出目标会话停着的错误。
 * 对照 Grok：状态跟操作走，不 toast 到正在看的另一条会话。
 */
fun switchComposerErrors(
    errors: Map<String, String>,
    fromKey: String,
    toKey: String,
    currentError: String?,
): Pair<Map<String, String>, String?> {
    if (fromKey == toKey) return errors to currentError
    var next = errors
    val parked = currentError?.trim().orEmpty()
    if (parked.isNotEmpty()) next = next + (fromKey to parked)
    val restored = next[toKey]
    return if (restored == null) next to null else (next - toKey) to restored
}

/**
 * 相册/拍照/语音失败绑在发起时的会话：当前正在看就写进输入槽，否则停着等切回去再显示。
 */
fun liveOrParkedComposerError(
    errors: Map<String, String>,
    ownerKey: String,
    liveKey: String,
    message: String,
): Pair<Map<String, String>, String?> {
    val text = message.trim()
    if (text.isEmpty()) return errors to null
    return if (ownerKey == liveKey) errors to text else putComposerDraftError(errors, ownerKey, text) to null
}
