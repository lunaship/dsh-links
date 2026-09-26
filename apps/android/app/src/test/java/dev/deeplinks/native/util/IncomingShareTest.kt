package dev.deeplinks.native.util

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IncomingShareTest {
    @Test
    fun sendPlainText() {
        val payload = parseIncomingShare(
            action = "android.intent.action.SEND",
            mimeType = "text/plain",
            extraText = "  hello from share  ",
            extraStreamUri = null,
        )
        assertEquals("hello from share", payload?.text)
        assertNull(payload?.imageUri)
        assertEquals(emptyList<String>(), payload?.imageUris)
    }

    @Test
    fun sendImage() {
        val payload = parseIncomingShare(
            action = "android.intent.action.SEND",
            mimeType = "image/jpeg",
            extraText = null,
            extraStreamUri = "content://media/1",
        )
        assertEquals("content://media/1", payload?.imageUri)
        assertNull(payload?.text)
    }

    @Test
    fun sendImageWithCaption() {
        val payload = parseIncomingShare(
            action = "android.intent.action.SEND",
            mimeType = "image/png",
            extraText = "caption",
            extraStreamUri = "content://media/2",
        )
        assertEquals("caption", payload?.text)
        assertEquals("content://media/2", payload?.imageUri)
    }

    @Test
    fun ignoresViewAndEmpty() {
        assertNull(
            parseIncomingShare(
                action = "android.intent.action.VIEW",
                mimeType = "text/plain",
                extraText = "nope",
                extraStreamUri = null,
            ),
        )
        assertNull(
            parseIncomingShare(
                action = "android.intent.action.SEND",
                mimeType = "text/plain",
                extraText = "   ",
                extraStreamUri = null,
            ),
        )
    }

    @Test
    fun textPlainDoesNotTreatStreamAsImage() {
        val payload = parseIncomingShare(
            action = "android.intent.action.SEND",
            mimeType = "text/plain",
            extraText = "note",
            extraStreamUri = "content://media/file.pdf",
        )
        assertEquals("note", payload?.text)
        assertNull(payload?.imageUri)
    }

    @Test
    fun forwardedSharePrefersCopiedUri() {
        assertEquals("content://app/inbox", forwardedShareImageUri("content://app/inbox", "content://media/1"))
        assertNull(forwardedShareImageUri(null, "content://media/1"))
        assertNull(forwardedShareImageUri("  ", null))
    }

    @Test
    fun shareInboxLivesUnderCacheShare() {
        val cache = File(System.getProperty("java.io.tmpdir"), "dsh-share-test-${System.nanoTime()}")
        cache.mkdirs()
        try {
            val dest = shareInboxFile(cache, 42L)
            assertEquals("inbox-42-0.jpg", dest.name)
            assertEquals("share", dest.parentFile?.name)
            assertTrue(dest.parentFile!!.isDirectory)
        } finally {
            cache.deleteRecursively()
        }
    }

    @Test
    fun pruneShareInboxDeletesStaleFiles() {
        val cache = File(System.getProperty("java.io.tmpdir"), "dsh-share-prune-${System.nanoTime()}")
        val dir = File(cache, SHARE_INBOX_DIR).apply { mkdirs() }
        val stale = File(dir, "inbox-old.jpg").apply { writeText("x") }
        val fresh = File(dir, "inbox-new.jpg").apply { writeText("y") }
        val outgoing = File(dir, "dsh-share-1.png").apply { writeText("z") }
        val now = SHARE_INBOX_MAX_AGE_MS * 3
        stale.setLastModified(now - SHARE_INBOX_MAX_AGE_MS - 1)
        fresh.setLastModified(now)
        outgoing.setLastModified(now - SHARE_INBOX_MAX_AGE_MS - 1)
        pruneShareInbox(cache, now)
        assertFalse(stale.exists())
        assertTrue(fresh.exists())
        assertTrue(outgoing.exists())
        cache.deleteRecursively()
    }

    @Test
    fun pruneShareExportsDeletesOnlyStalePngExports() {
        val cache = File(System.getProperty("java.io.tmpdir"), "dsh-share-export-prune-${System.nanoTime()}")
        val dir = File(cache, SHARE_INBOX_DIR).apply { mkdirs() }
        val stale = File(dir, "${SHARE_EXPORT_PREFIX}old.png").apply { writeText("x") }
        val fresh = File(dir, "${SHARE_EXPORT_PREFIX}new.png").apply { writeText("y") }
        val other = File(dir, "${SHARE_EXPORT_PREFIX}old.jpg").apply { writeText("z") }
        val now = SHARE_INBOX_MAX_AGE_MS * 3
        stale.setLastModified(now - SHARE_INBOX_MAX_AGE_MS - 1)
        fresh.setLastModified(now)
        other.setLastModified(now - SHARE_INBOX_MAX_AGE_MS - 1)
        pruneShareExports(cache, now)
        assertFalse(stale.exists())
        assertTrue(fresh.exists())
        assertTrue(other.exists())
        cache.deleteRecursively()
    }

    @Test
    fun sendMultipleImagesKeepsCaptions() {
        val payload = parseIncomingShare(
            action = "android.intent.action.SEND_MULTIPLE",
            mimeType = "image/*",
            extraText = "cap1",
            extraStreamUri = null,
            extraStreamUris = listOf("content://media/1", "content://media/2", "content://media/1"),
            extraTextList = listOf("cap1", "cap2"),
        )
        assertEquals("cap1\ncap2", payload?.text)
        assertEquals(listOf("content://media/1", "content://media/2"), payload?.imageUris)
    }

    @Test
    fun sendMultipleCapsAtShareLimit() {
        val uris = (1..12).map { "content://media/$it" }
        val payload = parseIncomingShare(
            action = "android.intent.action.SEND_MULTIPLE",
            mimeType = "image/png",
            extraText = null,
            extraStreamUri = null,
            extraStreamUris = uris,
        )
        assertEquals(SHARE_IMAGE_LIMIT, payload?.imageUris?.size)
        assertEquals("content://media/1", payload?.imageUri)
    }

    @Test
    fun forwardedShareDropsUnpersistedOriginals() {
        assertEquals(
            listOf("content://app/a"),
            forwardedShareImageUris(
                copied = listOf("content://app/a", ""),
                original = listOf("content://media/1", "content://media/2"),
            ),
        )
        assertEquals(
            emptyList<String>(),
            forwardedShareImageUris(
                copied = listOf("", "  "),
                original = listOf("content://media/1", "content://media/2"),
            ),
        )
    }

    @Test
    fun shareImageExtrasPrefersList() {
        assertEquals(
            listOf("content://a", "content://b"),
            shareImageExtras(listOf("content://a", "content://b"), "content://a"),
        )
        assertEquals(listOf("content://a"), shareImageExtras(null, "content://a"))
        assertEquals(emptyList<String>(), shareImageExtras(emptyList(), "  "))
    }

    @Test
    fun shareImagePersistLost_whenAnyCopyFails() {
        assertEquals(false, shareImagePersistLost(originalCount = 0, resolvedCount = 0))
        assertEquals(false, shareImagePersistLost(originalCount = 2, resolvedCount = 2))
        assertEquals(true, shareImagePersistLost(originalCount = 2, resolvedCount = 1))
        assertEquals(true, shareImagePersistLost(originalCount = 2, resolvedCount = 0))
    }

    @Test
    fun unsupportedShareLandsOnDshScreen() {
        assertEquals(ShareCatcherKind.DevicesNotice, shareCatcherKind(hasHost = false))
        assertEquals(ShareCatcherKind.WorkspaceNotice, shareCatcherKind(hasHost = true))
    }

    @Test
    fun sendImageRejectsFileSchemeButKeepsCaption() {
        val payload = parseIncomingShare(
            action = "android.intent.action.SEND",
            mimeType = "image/jpeg",
            extraText = "caption",
            extraStreamUri = "file:///data/data/dev.deeplinks/files/secret.jpg",
        )
        assertEquals("caption", payload?.text)
        assertNull(payload?.imageUri)
        assertTrue(payload?.imageUris.isNullOrEmpty())
    }

    @Test
    fun sendImageRejectsHttpScheme() {
        assertNull(
            parseIncomingShare(
                action = "android.intent.action.SEND",
                mimeType = "image/png",
                extraText = null,
                extraStreamUri = "http://example.com/pic.png",
            ),
        )
    }

    @Test
    fun sendMultipleFiltersNonContentSchemes() {
        val payload = parseIncomingShare(
            action = "android.intent.action.SEND_MULTIPLE",
            mimeType = "image/*",
            extraText = null,
            extraStreamUri = null,
            extraStreamUris = listOf(
                "content://media/1",
                "file:///etc/passwd",
                "http://example.com/x.jpg",
                "content://media/2",
            ),
        )
        assertEquals(listOf("content://media/1", "content://media/2"), payload?.imageUris)
    }

    @Test
    fun isContentUriOnlyAcceptsContentScheme() {
        assertTrue(isContentUri("content://media/1"))
        assertTrue(isContentUri("  content://media/1  "))
        assertTrue(isContentUri("Content://Media/1"))
        assertFalse(isContentUri("file:///etc/passwd"))
        assertFalse(isContentUri("http://example.com/x.jpg"))
        assertFalse(isContentUri("https://example.com/x.jpg"))
        assertFalse(isContentUri("data:image/png;base64,AAAA"))
        assertFalse(isContentUri(""))
    }

    // ===== cache/share 累计配额（安全审查 P3-1） =====

    private fun inboxEntry(dir: File, name: String, bytes: Long, mtime: Long): ShareInboxEntry {
        val f = File(dir, name).apply { writeText("x") }
        f.setLastModified(mtime)
        return ShareInboxEntry(f, bytes, mtime)
    }

    @Test
    fun shareInboxEntries_onlyInboxFilesAndOldestFirst() {
        val cache = File(System.getProperty("java.io.tmpdir"), "dsh-inbox-entries-${System.nanoTime()}")
        val dir = File(cache, SHARE_INBOX_DIR).apply { mkdirs() }
        try {
            inboxEntry(dir, "inbox-300-0.jpg", 10, 300L)
            inboxEntry(dir, "inbox-100-0.jpg", 10, 100L)
            inboxEntry(dir, "inbox-200-0.jpg.part", 10, 200L)
            File(dir, "${SHARE_EXPORT_PREFIX}9.png").writeText("z")
            val names = shareInboxEntries(cache).map { it.file.name }
            assertEquals(
                listOf("inbox-100-0.jpg", "inbox-200-0.jpg.part", "inbox-300-0.jpg"),
                names,
            )
        } finally {
            cache.deleteRecursively()
        }
    }

    @Test
    fun plan_alwaysDeletesExpiredEntries() {
        val dir = File(System.getProperty("java.io.tmpdir"), "dsh-plan-expired-${System.nanoTime()}")
        dir.mkdirs()
        try {
            val now = SHARE_INBOX_MAX_AGE_MS * 3
            val stale = inboxEntry(dir, "inbox-1-0.jpg", 10, now - SHARE_INBOX_MAX_AGE_MS - 1)
            val fresh = inboxEntry(dir, "inbox-2-0.jpg", 10, now)
            val plan = planShareInboxReclaim(
                entries = listOf(stale, fresh),
                incomingBytes = 10,
                incomingFiles = 1,
                nowMs = now,
            )
            assertEquals(listOf(stale.file), plan.delete)
            assertTrue(plan.fits)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun plan_reclaimsOldestUntilWithinByteBudget() {
        val dir = File(System.getProperty("java.io.tmpdir"), "dsh-plan-bytes-${System.nanoTime()}")
        dir.mkdirs()
        try {
            val now = 1_000L
            val entries = listOf(
                inboxEntry(dir, "inbox-1-0.jpg", 40, now - 3),
                inboxEntry(dir, "inbox-2-0.jpg", 40, now - 2),
                inboxEntry(dir, "inbox-3-0.jpg", 40, now - 1),
            )
            val plan = planShareInboxReclaim(
                entries = entries,
                incomingBytes = 40,
                incomingFiles = 1,
                nowMs = now,
                maxBytes = 100,
                maxFiles = 24,
            )
            // 保留 120 + 40 = 160 > 100，需回收最旧的一张 -> 80+40 = 120 > 100 -> 再回收 -> 40+40 = 80 ✓
            assertEquals(2, plan.delete.size)
            assertEquals("inbox-1-0.jpg", plan.delete[0].name)
            assertEquals("inbox-2-0.jpg", plan.delete[1].name)
            assertTrue(plan.fits)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun plan_reclaimsOldestUntilWithinFileBudget() {
        val dir = File(System.getProperty("java.io.tmpdir"), "dsh-plan-files-${System.nanoTime()}")
        dir.mkdirs()
        try {
            val now = 1_000L
            val entries = (1..5).map { i -> inboxEntry(dir, "inbox-$i-0.jpg", 1, now - (10 - i).toLong()) }
            val plan = planShareInboxReclaim(
                entries = entries,
                incomingBytes = 8,
                incomingFiles = 8,
                nowMs = now,
                maxBytes = Long.MAX_VALUE,
                maxFiles = 10,
            )
            assertEquals(3, plan.delete.size)
            assertTrue(plan.fits)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun plan_rejectsPathologicalBatchBeyondHardCap() {
        val plan = planShareInboxReclaim(
            entries = emptyList(),
            incomingBytes = SHARE_INBOX_HARD_BYTES + 1,
            incomingFiles = 1,
            nowMs = 0L,
        )
        assertFalse(plan.fits)
    }

    @Test
    fun plan_allowsSingleMaxSizeBatchEvenAboveSteadyStateBudget() {
        // 8×16MiB 的合法单批不得超过稳态 64MiB 预算，但不能因此被拒绝。
        val batch = SHARE_IMAGE_MAX_BYTES * SHARE_IMAGE_LIMIT
        assertTrue(batch > SHARE_INBOX_MAX_BYTES)
        val plan = planShareInboxReclaim(
            entries = emptyList(),
            incomingBytes = batch,
            incomingFiles = SHARE_IMAGE_LIMIT,
            nowMs = 0L,
        )
        assertTrue(plan.fits)
    }

    @Test
    fun plan_neverDeletesTheBatchesOwnTempFiles() {
        val dir = File(System.getProperty("java.io.tmpdir"), "dsh-plan-exclude-${System.nanoTime()}")
        dir.mkdirs()
        try {
            val now = 1_000L
            val mine = inboxEntry(dir, "inbox-9-0.jpg.part", 999, now - 1)
            val plan = planShareInboxReclaim(
                entries = listOf(mine),
                incomingBytes = 999,
                incomingFiles = 1,
                nowMs = now,
                maxBytes = 10,
                excludeNames = setOf(mine.file.name),
            )
            // 本批自己的临时文件既不入账也不被回收
            assertEquals(emptyList<File>(), plan.delete)
            assertTrue(plan.fits)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun prepareShareInbox_actuallyDeletesAccordingToPlan() {
        val cache = File(System.getProperty("java.io.tmpdir"), "dsh-prepare-${System.nanoTime()}")
        val dir = File(cache, SHARE_INBOX_DIR).apply { mkdirs() }
        try {
            val now = SHARE_INBOX_MAX_AGE_MS * 3
            val stale = inboxEntry(dir, "inbox-1-0.jpg", 10, now - SHARE_INBOX_MAX_AGE_MS - 1)
            val fresh = inboxEntry(dir, "inbox-2-0.jpg", 10, now - 5)
            val fits = prepareShareInbox(cache, now, incomingBytes = 10, incomingFiles = 1)
            assertTrue(fits)
            assertFalse(stale.file.exists())
            assertTrue(fresh.file.exists())
        } finally {
            cache.deleteRecursively()
        }
    }

    @Test
    fun shareInboxTempFile_livesBesideFinalFile() {
        val cache = File(System.getProperty("java.io.tmpdir"), "dsh-temp-${System.nanoTime()}")
        cache.mkdirs()
        try {
            val temp = shareInboxTempFile(cache, 42L)
            assertEquals("inbox-42-0.jpg.part", temp.name)
            assertEquals("share", temp.parentFile?.name)
        } finally {
            cache.deleteRecursively()
        }
    }
}
