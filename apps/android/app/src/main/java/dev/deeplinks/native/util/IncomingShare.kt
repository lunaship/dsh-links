package dev.deeplinks.native.util

import java.io.File

const val EXTRA_SHARE_TEXT = "shareText"
const val EXTRA_SHARE_IMAGE = "shareImageUri"
const val EXTRA_SHARE_IMAGES = "shareImageUris"
const val EXTRA_SHARE_SEQ = "shareSeq"
const val EXTRA_SHARE_NOTICE = "shareNotice"
const val SHARE_INBOX_DIR = "share"
const val SHARE_INBOX_MAX_AGE_MS = 24L * 60 * 60 * 1000
const val SHARE_IMAGE_MAX_BYTES = 16L * 1024 * 1024
const val SHARE_IMAGE_LIMIT = 8
const val SHARE_EXPORT_PREFIX = "dsh-share-"

/**
 * `cache/share` 稳态预算：每次写入前先把历史回收到这个规模。
 *
 * 单次分享的 8×16 MiB 上限不能当成「安全总预算」：重复或并发分享会让缓存无限增长。
 */
const val SHARE_INBOX_MAX_BYTES = 64L * 1024 * 1024

/** 稳态文件数预算：配合每批最多 8 张，大约保留最近三次分享。 */
const val SHARE_INBOX_MAX_FILES = 24

/**
 * 单批硬上限：即使清空全部历史也不能超过它。
 *
 * 之所以不把稳态预算当硬上限：现行产品允许一次分享最多 8 张、每张最大 16 MiB，
 * 若在入口直接拒绝超预算的单批，会把合法的大图分享变成失败。这里允许单批
 * 临时超出稳态预算，并在下一次写入时回收。
 */
const val SHARE_INBOX_HARD_BYTES = SHARE_IMAGE_MAX_BYTES * SHARE_IMAGE_LIMIT * 2

/** 进程级互斥：两个并发的分享批次不能各自通过配额检查再一起写超。 */
internal val shareInboxLock = Any()

private const val ACTION_SEND = "android.intent.action.SEND"
private const val ACTION_SEND_MULTIPLE = "android.intent.action.SEND_MULTIPLE"

data class IncomingSharePayload(
    val text: String? = null,
    val imageUris: List<String> = emptyList(),
) {
    val imageUri: String? get() = imageUris.firstOrNull()
    val isEmpty: Boolean get() = text.isNullOrBlank() && imageUris.isEmpty()
}

fun isShareAction(action: String?): Boolean =
    action == ACTION_SEND || action == ACTION_SEND_MULTIPLE

fun acceptsShareImages(mimeType: String?): Boolean {
    val mime = mimeType.orEmpty().lowercase()
    return mime.startsWith("image/") || mime == "*/*" || mime.isEmpty()
}

/** 分享图片只接受 content:// EXTRA_STREAM；file:// / http(s):// 等一律拒绝。 */
fun isContentUri(uri: String): Boolean = uri.trim().startsWith("content://", ignoreCase = true)

/**
 * 系统 Sharesheet → 会话草稿（对照 OpenClaw Android share-into-chat）。
 * 认 SEND / SEND_MULTIPLE；不认 VIEW / 任意 URI。
 */
fun parseIncomingShare(
    action: String?,
    mimeType: String?,
    extraText: String?,
    extraStreamUri: String?,
    extraStreamUris: List<String>? = null,
    extraTextList: List<String>? = null,
): IncomingSharePayload? {
    if (!isShareAction(action)) return null
    val text = shareCaption(extraText, extraTextList)
    val images = shareImageUris(action, mimeType, extraStreamUri, extraStreamUris)
    return IncomingSharePayload(text = text, imageUris = images).takeUnless { it.isEmpty }
}

/** 不支持的分享也要落到 DSH 画面，不能 toast 后看起来像没打开。 */
enum class ShareCatcherKind { DevicesNotice, WorkspaceNotice }

fun shareCatcherKind(hasHost: Boolean): ShareCatcherKind =
    if (hasHost) ShareCatcherKind.WorkspaceNotice else ShareCatcherKind.DevicesNotice

fun shareCaption(extraText: String?, extraTextList: List<String>?): String? {
    val fromList = extraTextList.orEmpty().map { it.trim() }.filter { it.isNotEmpty() }
    val unique = LinkedHashSet<String>()
    extraText?.trim()?.takeIf { it.isNotEmpty() }?.let { unique.add(it) }
    for (item in fromList) {
        if (unique.any { it == item || it.contains(item) }) continue
        unique.add(item)
    }
    return unique.joinToString("\n").takeIf { it.isNotEmpty() }
}

fun shareImageUris(
    action: String?,
    mimeType: String?,
    extraStreamUri: String?,
    extraStreamUris: List<String>?,
): List<String> {
    if (!acceptsShareImages(mimeType)) return emptyList()
    val raw = when (action) {
        ACTION_SEND_MULTIPLE -> extraStreamUris.orEmpty()
        ACTION_SEND -> listOfNotNull(extraStreamUri)
        else -> emptyList()
    }
    return raw.map { it.trim() }
        .filter { it.isNotBlank() && isContentUri(it) }
        .distinct()
        .take(SHARE_IMAGE_LIMIT)
}

/** Catcher 结束后系统对 content URI 的临时授权会失效；先拷进 cache/share。 */
fun shareInboxFile(cacheDir: File, nowMs: Long, index: Int = 0): File {
    val dir = File(cacheDir, SHARE_INBOX_DIR).apply { mkdirs() }
    return File(dir, "inbox-$nowMs-$index.jpg")
}

/** 复制中的临时文件：写完整、且在配额内，才原子 rename 成 [shareInboxFile]。 */
fun shareInboxTempFile(cacheDir: File, nowMs: Long, index: Int = 0): File =
    File(shareInboxFile(cacheDir, nowMs, index).path + ".part")

/** cache/share 里的一个 inbox 条目。 */
data class ShareInboxEntry(val file: File, val sizeBytes: Long, val lastModifiedMs: Long)

/** 配额规划结果：`delete` 需要立即删除，`fits` 为 false 时调用方必须放弃本批。 */
data class ShareInboxPlan(val delete: List<File>, val fits: Boolean)

/** 列出 inbox 文件（按最后修改时间升序：最旧在前）；导出件（dsh-share-*.png）不参与回收。 */
fun shareInboxEntries(cacheDir: File): List<ShareInboxEntry> =
    File(cacheDir, SHARE_INBOX_DIR).listFiles()
        .orEmpty()
        .filter { it.isFile && it.name.startsWith("inbox-") }
        .map { ShareInboxEntry(it, it.length(), it.lastModified()) }
        .sortedWith(compareBy({ it.lastModifiedMs }, { it.file.name }))

/**
 * 纯规划函数：算清本次写入前要删哪些文件（JVM 单测覆盖）。
 *
 * 规则：
 * 1. 超过 [maxAgeMs] 的条目无条件删除（与 [pruneShareInbox] 同一口径）；
 * 2. 剩余条目按最旧优先回收，直到「保留量 + 本批」落到 [maxBytes]/[maxFiles] 以内；
 * 3. 本批自身超过 [hardBytes] 时 [ShareInboxPlan.fits] = false（调用方不得写入）；
 * 4. [excludeNames] 里的文件属于本批正在写的临时文件：既不入账，也不允许被回收。
 */
fun planShareInboxReclaim(
    entries: List<ShareInboxEntry>,
    incomingBytes: Long,
    incomingFiles: Int,
    nowMs: Long,
    maxBytes: Long = SHARE_INBOX_MAX_BYTES,
    maxFiles: Int = SHARE_INBOX_MAX_FILES,
    maxAgeMs: Long = SHARE_INBOX_MAX_AGE_MS,
    hardBytes: Long = SHARE_INBOX_HARD_BYTES,
    excludeNames: Set<String> = emptySet(),
): ShareInboxPlan {
    // excludeNames = 本批正在写、尚未 rename 的临时文件：它们既不入账也不允许被回收。
    val candidates = entries.filterNot { it.file.name in excludeNames }
    val expired = candidates.filter { nowMs - it.lastModifiedMs > maxAgeMs }
    val expiredFiles = expired.map { it.file }.toSet()
    if (incomingBytes > hardBytes || incomingFiles > maxFiles * 4) {
        return ShareInboxPlan(delete = expiredFiles.toList(), fits = false)
    }
    val kept = candidates.filterNot { it.file in expiredFiles }.toMutableList()
    var keptBytes = kept.sumOf { it.sizeBytes.coerceAtLeast(0L) }
    val delete = expiredFiles.toMutableList()
    while (kept.isNotEmpty() && (keptBytes + incomingBytes > maxBytes || kept.size + incomingFiles > maxFiles)) {
        val oldest = kept.removeAt(0)
        delete += oldest.file
        keptBytes -= oldest.sizeBytes.coerceAtLeast(0L)
    }
    return ShareInboxPlan(delete = delete, fits = true)
}

/**
 * 在进程锁内执行配额回收。返回 false 表示本批不允许写入（调用方应删除临时文件并提示失败）。
 */
fun prepareShareInbox(
    cacheDir: File,
    nowMs: Long,
    incomingBytes: Long,
    incomingFiles: Int,
    excludeNames: Set<String> = emptySet(),
): Boolean = synchronized(shareInboxLock) {
    val plan = planShareInboxReclaim(
        entries = shareInboxEntries(cacheDir),
        incomingBytes = incomingBytes,
        incomingFiles = incomingFiles,
        nowMs = nowMs,
        excludeNames = excludeNames,
    )
    plan.delete.forEach { runCatching { it.delete() } }
    plan.fits
}

/** Catcher 结束后系统临时授权会失效；只转发已拷进 cache/share 的副本。 */
fun forwardedShareImageUris(
    copied: List<String>,
    @Suppress("UNUSED_PARAMETER") original: List<String>,
): List<String> = copied.map { it.trim() }.filter { it.isNotBlank() }

fun forwardedShareImageUri(copiedUri: String?, originalUri: String?): String? =
    forwardedShareImageUris(
        copied = listOfNotNull(copiedUri),
        original = listOfNotNull(originalUri),
    ).firstOrNull()

fun shareImageExtras(listed: List<String>?, first: String?): List<String> {
    val fromList = listed.orEmpty().map { it.trim() }.filter { it.isNotBlank() }
    if (fromList.isNotEmpty()) return fromList
    return listOfNotNull(first?.trim()?.takeIf { it.isNotBlank() })
}

/** 系统图没拷进 cache 时，不能静默当成分享成功。 */
fun shareImagePersistLost(originalCount: Int, resolvedCount: Int): Boolean =
    originalCount > 0 && resolvedCount < originalCount

fun pruneShareInbox(cacheDir: File, nowMs: Long, maxAgeMs: Long = SHARE_INBOX_MAX_AGE_MS) {
    val dir = File(cacheDir, SHARE_INBOX_DIR)
    val files = dir.listFiles() ?: return
    for (file in files) {
        if (!file.name.startsWith("inbox-")) continue
        if (nowMs - file.lastModified() > maxAgeMs) file.delete()
    }
}

fun pruneShareExports(cacheDir: File, nowMs: Long, maxAgeMs: Long = SHARE_INBOX_MAX_AGE_MS) {
    val dir = File(cacheDir, SHARE_INBOX_DIR)
    val files = dir.listFiles() ?: return
    for (file in files) {
        if (!file.name.startsWith(SHARE_EXPORT_PREFIX) || !file.name.endsWith(".png")) continue
        if (nowMs - file.lastModified() > maxAgeMs) file.delete()
    }
}
