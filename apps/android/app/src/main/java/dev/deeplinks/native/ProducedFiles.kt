package dev.deeplinks.native

import dev.deeplinks.core.DshType

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import dev.deeplinks.core.dshRipple
import dev.deeplinks.core.Dsh
import dev.deeplinks.core.L
import dev.deeplinks.native.util.ProducedFileKind
import dev.deeplinks.native.util.decodeProducedText
import dev.deeplinks.native.util.isProducedTextMime
import dev.deeplinks.native.util.producedFileKind
import dev.deeplinks.native.util.producedFileName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val PREVIEW_MAX_EDGE = 2048

internal fun decodeSampledBitmap(bytes: ByteArray, maxEdge: Int = PREVIEW_MAX_EDGE): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    val width = bounds.outWidth
    val height = bounds.outHeight
    if (width <= 0 || height <= 0) return null
    var sample = 1
    while (width / sample > maxEdge || height / sample > maxEdge) sample *= 2
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
}

private sealed interface ProducedPreview {
    data class Image(val bitmap: Bitmap) : ProducedPreview
    data class Text(val path: String, val body: String) : ProducedPreview
}

/**
 * 本轮产出：对标 DSH 0.1.5 侧栏打开文件。
 * 图片/文本点击预览，失败可重试；其它文件点按或长按复制路径。
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
internal fun ProducedFilesRow(
    files: List<String>,
    onFetchFile: ((String) -> Pair<String, ByteArray>)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var preview by remember { mutableStateOf<ProducedPreview?>(null) }
    var loadingPath by remember { mutableStateOf<String?>(null) }
    var errorPath by remember { mutableStateOf<String?>(null) }
    var copiedPath by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(copiedPath) {
        if (copiedPath == null) return@LaunchedEffect
        delay(1_400)
        copiedPath = null
    }

    fun copyPath(path: String) {
        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
            as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("workspace path", path))
        copiedPath = path
        errorPath = null
    }

    fun openPath(path: String) {
        val fetch = onFetchFile
        val kind = producedFileKind(path)
        if (fetch == null || kind == ProducedFileKind.OTHER) {
            copyPath(path)
            return
        }
        loadingPath = path
        errorPath = null
        scope.launch(Dispatchers.IO) {
            val result = runCatching { fetch(path) }
            withContext(Dispatchers.Main) {
                loadingPath = null
                result.fold(
                    onSuccess = { (mime, bytes) ->
                        when {
                            kind == ProducedFileKind.IMAGE || mime.startsWith("image/") -> {
                                val bmp = decodeSampledBitmap(bytes)
                                if (bmp != null) preview = ProducedPreview.Image(bmp)
                                else errorPath = path
                            }
                            kind == ProducedFileKind.TEXT || isProducedTextMime(mime) -> {
                                preview = ProducedPreview.Text(path, decodeProducedText(bytes))
                            }
                            else -> copyPath(path)
                        }
                    },
                    onFailure = { errorPath = path },
                )
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(DshRadius.lg))
            .background(Dsh.bgInput)
            .border(1.dp, Dsh.borderSubtle, RoundedCornerShape(DshRadius.lg))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.Description,
                contentDescription = null,
                tint = Dsh.labelTertiary,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                L.producedFiles,
                color = Dsh.labelPrimary,
                style = DshType.t13x24M,
                fontWeight = FontWeight(500),
                lineHeight = 24.sp,
            )
        }
        if (files.isEmpty()) return@Column
        Spacer(Modifier.height(8.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            files.forEach { path ->
                val name = producedFileName(path)
                val loading = loadingPath == path
                val failed = errorPath == path
                val copied = copiedPath == path
                val label = when {
                    loading -> L.loadingFile
                    failed -> L.retry
                    copied -> L.copied
                    else -> name
                }
                Box(
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .clip(RoundedCornerShape(DshRadius.full))
                        .background(Dsh.bgTrack)
                        .border(1.dp, Dsh.borderSubtle, RoundedCornerShape(DshRadius.full))
                        .semantics {
                            role = Role.Button
                            contentDescription = when {
                                failed -> L.openFileFailed
                                else -> "${L.producedFiles}: $name"
                            }
                        }
                        .combinedClickable(
                            onClick = { if (!loading) openPath(path) },
                            onLongClick = { copyPath(path) },
                        )
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (loading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 1.5.dp,
                                color = Dsh.brand400,
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(
                            label,
                            color = if (failed) Dsh.error else Dsh.labelPrimary,
                            style = DshType.t13,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }

    when (val current = preview) {
        is ProducedPreview.Image -> Dialog(onDismissRequest = { preview = null }) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .semantics {
                        role = Role.Button
                        contentDescription = L.close
                    }
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = dshRipple(),
                    ) { preview = null }
                    .padding(16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    bitmap = current.bitmap.asImageBitmap(),
                    contentDescription = L.producedFiles,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(DshRadius.md)),
                )
            }
        }
        is ProducedPreview.Text -> Dialog(onDismissRequest = { preview = null }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .clip(RoundedCornerShape(DshRadius.lg))
                    .background(Dsh.bgCard)
                    .padding(16.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        producedFileName(current.path),
                        color = Dsh.labelPrimary,
                        style = DshType.t14SB,
                        fontWeight = FontWeight(600),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Box(
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .clip(RoundedCornerShape(DshRadius.sm))
                            .semantics {
                                role = Role.Button
                                contentDescription = L.close
                            }
                            .clickable { preview = null }
                            .padding(horizontal = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(L.close, color = Dsh.labelTertiary, style = DshType.microRelaxed, lineHeight = 16.sp)
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    current.body,
                    fontFamily = FontFamily.Monospace,
                    style = DshType.captionRelaxed,
                    color = Dsh.labelSecondary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                )
            }
        }
        null -> Unit
    }
}