package dev.deeplinks.native

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.core.content.FileProvider
import dev.deeplinks.core.BoundedIo
import dev.deeplinks.core.EXTRA_AUTH_NOTICE
import dev.deeplinks.core.Host
import dev.deeplinks.core.HostStore
import dev.deeplinks.core.L
import dev.deeplinks.core.pickStartupHost
import dev.deeplinks.devices.DevicesActivity
import dev.deeplinks.native.util.EXTRA_SHARE_IMAGE
import dev.deeplinks.native.util.EXTRA_SHARE_IMAGES
import dev.deeplinks.native.util.EXTRA_SHARE_NOTICE
import dev.deeplinks.native.util.EXTRA_SHARE_SEQ
import dev.deeplinks.native.util.EXTRA_SHARE_TEXT
import dev.deeplinks.native.util.forwardedShareImageUris
import dev.deeplinks.native.util.isContentUri
import dev.deeplinks.native.util.parseIncomingShare
import dev.deeplinks.native.util.prepareShareInbox
import dev.deeplinks.native.util.shareCatcherKind
import dev.deeplinks.native.util.ShareCatcherKind
import dev.deeplinks.native.util.shareImagePersistLost
import dev.deeplinks.native.util.shareInboxFile
import dev.deeplinks.native.util.shareInboxLock
import dev.deeplinks.native.util.shareInboxTempFile
import dev.deeplinks.native.util.SHARE_IMAGE_MAX_BYTES
import java.util.concurrent.Executors

/**
 * 导出入口：系统分享到已配对工作台。WorkspaceActivity 保持 exported=false。
 * 图片先拷进 cache/share，避免 Catcher finish 后系统临时 URI 授权失效。
 */
class ShareCatcherActivity : ComponentActivity() {
    private val copyExecutor = Executors.newSingleThreadExecutor()

    /** 分享落到「最近使用」的设备，而不是列表首项（多设备用户不会突然跑到另一台）。 */
    private fun defaultHost(): Host? {
        val hosts = HostStore.load(this)
        return pickStartupHost(hosts, intentHost = null, lastIdentity = HostStore.lastHostIdentity(this))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val streams = streamUris(intent).map { it.toString() }
        val payload = parseIncomingShare(
            action = intent.action,
            mimeType = intent.type,
            extraText = intent.getStringExtra(Intent.EXTRA_TEXT),
            extraStreamUri = streams.firstOrNull(),
            extraStreamUris = streams,
            extraTextList = intent.getStringArrayListExtra(Intent.EXTRA_TEXT),
        )
        if (payload == null) {
            val host = defaultHost()
            if (shareCatcherKind(host != null) == ShareCatcherKind.DevicesNotice || host == null) {
                startActivity(
                    Intent(this, DevicesActivity::class.java)
                        .putExtra(EXTRA_AUTH_NOTICE, L.shareUnsupported),
                )
            } else {
                launchWorkspace(host, text = null, imageUris = emptyList(), notice = L.shareUnsupported)
            }
            finish()
            return
        }
        val host = defaultHost()
        if (host == null) {
            startActivity(
                Intent(this, DevicesActivity::class.java)
                    .putExtra(EXTRA_AUTH_NOTICE, L.shareNeedsPairing),
            )
            finish()
            return
        }
        val originalImages = payload.imageUris.map { Uri.parse(it) }
        if (originalImages.isEmpty()) {
            launchWorkspace(host, payload.text, imageUris = emptyList())
            return
        }
        copyExecutor.execute {
            // 全批次共用同一把进程锁：两个并发 ACTION_SEND_MULTIPLE 不能各自通过
            // 配额检查再一起写超；只依赖单个 Activity 的 copyExecutor 不够。
            val outcome = persistShareBatch(originalImages)
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                if (outcome.rejected) {
                    // 达到配额：不启动带不完整图片的工作台，用现有提示告知失败。
                    launchWorkspace(
                        host,
                        payload.text,
                        imageUris = emptyList(),
                        notice = L.shareImagesPersistFailed,
                    )
                    return@runOnUiThread
                }
                val resolved = forwardedShareImageUris(outcome.copied, originalImages.map { it.toString() })
                val notice = if (shareImagePersistLost(originalImages.size, resolved.size)) {
                    L.shareImagesPersistFailed
                } else null
                launchWorkspace(host, payload.text, imageUris = resolved.map { Uri.parse(it) }, notice = notice)
            }
        }
    }

    private class ShareBatchOutcome(val copied: List<String>, val rejected: Boolean)

    /**
     * 写临时文件 → 配额内回收历史 → 原子 rename。
     * 任何异常、超限、拒绝都在这里删掉本批临时文件，不留下半个批次。
     */
    private fun persistShareBatch(originalImages: List<Uri>): ShareBatchOutcome =
        synchronized(shareInboxLock) {
            val now = System.currentTimeMillis()
            val temps = mutableListOf<java.io.File>()
            var totalBytes = 0L
            for ((index, uri) in originalImages.withIndex()) {
                val temp = shareInboxTempFile(cacheDir, now, index)
                val written = runCatching {
                    // 只读 content:// provider；file:// / http(s):// 等不打开，避免变成任意 URI 读取器。
                    if (!isContentUri(uri.toString())) return@runCatching false
                    contentResolver.openInputStream(uri)?.use { input ->
                        temp.outputStream().buffered().use { output ->
                            BoundedIo.copy(input, output, SHARE_IMAGE_MAX_BYTES)
                        }
                    } ?: return@runCatching false
                    temp.exists() && temp.length() > 0L
                }.getOrDefault(false)
                if (!written) {
                    temp.delete()
                } else {
                    temps += temp
                    totalBytes += temp.length()
                }
            }
            // 本批自身上限（8×16 MiB）已由上游保证；这里拒绝的是病态输入。
            val fits = prepareShareInbox(
                cacheDir = cacheDir,
                nowMs = now,
                incomingBytes = totalBytes,
                incomingFiles = temps.size,
                excludeNames = temps.map { it.name }.toSet(),
            )
            if (!fits) {
                temps.forEach { runCatching { it.delete() } }
                return@synchronized ShareBatchOutcome(emptyList(), rejected = true)
            }
            val finalUris = temps.mapIndexedNotNull { index, temp ->
                val dest = shareInboxFile(cacheDir, now, index)
                runCatching {
                    if (dest.exists()) dest.delete()
                    if (!temp.renameTo(dest)) {
                        temp.delete()
                        null
                    } else {
                        FileProvider.getUriForFile(this, "${packageName}.fileprovider", dest).toString()
                    }
                }.getOrNull()
            }
            ShareBatchOutcome(finalUris, rejected = false)
        }

    override fun onDestroy() {
        copyExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun launchWorkspace(host: Host, text: String?, imageUris: List<Uri>, notice: String? = null) {
        val next = host.putInto(Intent(this, WorkspaceActivity::class.java)).apply {
            addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            putExtra(EXTRA_SHARE_SEQ, System.currentTimeMillis())
            text?.let { putExtra(EXTRA_SHARE_TEXT, it) }
            if (!notice.isNullOrBlank()) putExtra(EXTRA_SHARE_NOTICE, notice)
            if (imageUris.isNotEmpty()) {
                data = imageUris.first()
                val clip = ClipData.newUri(contentResolver, "shared-image", imageUris.first())
                imageUris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
                clipData = clip
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                putExtra(EXTRA_SHARE_IMAGE, imageUris.first().toString())
                putStringArrayListExtra(EXTRA_SHARE_IMAGES, ArrayList(imageUris.map { it.toString() }))
            }
        }
        startActivity(next)
        finish()
    }

    private fun streamUris(intent: Intent): List<Uri> {
        if (intent.action == Intent.ACTION_SEND_MULTIPLE) {
            val list = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
            }
            return list.orEmpty().filterNotNull()
        }
        return listOfNotNull(streamUri(intent))
    }

    private fun streamUri(intent: Intent): Uri? =
        if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }
}
