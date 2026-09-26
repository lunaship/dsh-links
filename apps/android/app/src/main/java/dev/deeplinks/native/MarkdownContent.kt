package dev.deeplinks.native

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import dev.deeplinks.core.DshType
import dev.deeplinks.native.ui.DshHeaderAction

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import coil3.compose.AsyncImage
import dev.deeplinks.core.dshRipple
import dev.deeplinks.core.Dsh
import dev.deeplinks.core.L
import dev.deeplinks.core.MermaidFence
import dev.deeplinks.core.MarkdownMedia
import dev.deeplinks.native.util.copiedNeedsAppToast
import dev.deeplinks.native.util.tableToCsv
import dev.deeplinks.native.util.tableToTsv

@Composable
internal fun MarkdownContent(text: String, streaming: Boolean = false) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val blocks = remember(text) { splitMarkdownBlocks(text) }
    blocks.forEachIndexed { index, block ->
        val isLastBlock = index == blocks.lastIndex
        val streamTail = streaming && isLastBlock
        when (block.type) {
            MarkdownBlockType.CODE -> {
                val mermaidSource = if (MermaidFence.isMermaidLang(block.lang)) {
                    MermaidFence.sourceForRender(block.content)
                } else {
                    null
                }
                if (mermaidSource != null && (!streamTail || MermaidFence.looksRenderable(mermaidSource))) {
                    MermaidDiagramBlock(mermaidSource, ephemeral = streamTail)
                    if (streamTail) StreamCaret()
                } else {
                    MarkdownCodeBlock(block.lang, block.content)
                    if (streamTail) StreamCaret()
                }
            }
            MarkdownBlockType.HEADING -> {
                InlineMarkdownText(
                    block.content,
                    style = markdownHeadingStyle(block.level),
                    streaming = streamTail,
                    markdown = false,
                )
            }
            MarkdownBlockType.LIST -> {
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text("•  ", color = Dsh.labelTertiary, style = DshType.t16x28, lineHeight = 28.sp)
                    InlineMarkdownText(block.content, streaming = streamTail)
                }
            }
            MarkdownBlockType.QUOTE -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Dsh.bgCard)
                        .border(2.dp, Dsh.borderSubtle)
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    InlineMarkdownText(block.content, color = Dsh.labelSecondary, streaming = streamTail)
                }
            }
            MarkdownBlockType.TABLE -> {
                MarkdownTableBlock(block.rows)
                if (streamTail) StreamCaret()
            }
            MarkdownBlockType.HR -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(Dsh.borderSubtle)
                )
                if (streamTail) StreamCaret()
            }
            MarkdownBlockType.IMAGE -> {
                val imageUrl = MarkdownMedia.takeIfSafe(block.content)
                if (imageUrl != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(DshRadius.lg))
                            .background(Dsh.bgCard)
                            .clickable {
                                val uri = android.net.Uri.parse(imageUrl)
                                runCatching {
                                    context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, uri))
                                }
                            }
                    ) {
                        coil3.compose.AsyncImage(
                            model = imageUrl,
                            contentDescription = null,
                            contentScale = androidx.compose.ui.layout.ContentScale.FillWidth,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
                if (streamTail) StreamCaret()
            }
            MarkdownBlockType.MATH -> {
                LatexDisplayBlock(block.content)
                if (streamTail) StreamCaret()
            }
            MarkdownBlockType.EMPTY -> if (streamTail) StreamCaret()
            else -> InlineMarkdownText(block.content, streaming = streamTail)
        }
    }
}

@Composable
private fun MarkdownTableBlock(rows: List<List<String>>) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var fullscreen by remember { mutableStateOf(false) }
    var tableError by remember { mutableStateOf<String?>(null) }
    val csvBytes = remember(rows) { tableToCsv(rows).toByteArray(Charsets.UTF_8) }
    val saveTable = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.openOutputStream(uri)?.use { it.write(csvBytes) }
                ?: error("open")
        }.onSuccess {
            tableError = null
        }.onFailure { e ->
            tableError = L.saveFailedWithMessage.format(e.message ?: L.unknownError)
        }
    }
    fun copyTable() {
        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("table", tableToTsv(rows)))
        if (copiedNeedsAppToast(android.os.Build.VERSION.SDK_INT)) {
            Toast.makeText(context, L.copied, Toast.LENGTH_SHORT).show()
        }
    }
    fun downloadTable() {
        saveTable.launch("dsh-table.csv")
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(DshRadius.md))
            .background(Dsh.bgCard)
            .border(1.dp, Dsh.borderSubtle, RoundedCornerShape(DshRadius.md))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Dsh.bgCodeBanner)
                .padding(horizontal = 12.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(L.tableLabel, color = Dsh.labelTertiary, style = DshType.microRelaxed, lineHeight = 16.sp, modifier = Modifier.weight(1f))
            DshHeaderAction(L.copy, onClick = { copyTable() })
            DshHeaderAction(L.tableDownload, onClick = { downloadTable() })
            DshHeaderAction(L.tableFullscreen) { fullscreen = true }
        }
        val shownTableError = tableError
        if (!shownTableError.isNullOrBlank()) {
            val error = shownTableError
            Text(
                error,
                color = Dsh.error,
                style = DshType.microRelaxed,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp)
                    .semantics { contentDescription = error },
            )
        }
        TableGrid(rows)
    }
    if (fullscreen) {
        Dialog(onDismissRequest = { fullscreen = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .clip(RoundedCornerShape(DshRadius.lg))
                    .background(Dsh.bgBase)
                    .padding(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(L.tableLabel, color = Dsh.labelPrimary, style = DshType.t14, modifier = Modifier.weight(1f))
                    DshHeaderAction(L.copy) { copyTable() }
                    DshHeaderAction(L.tableDownload) { downloadTable() }
                    DshHeaderAction(L.close) { fullscreen = false }
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .verticalScroll(rememberScrollState())
                        .padding(top = 8.dp),
                ) {
                    TableGrid(rows, compact = false)
                }
            }
        }
    }
}
@Composable
private fun TableGrid(rows: List<List<String>>, compact: Boolean = true) {
    Column(modifier = Modifier.fillMaxWidth()) {
        rows.forEachIndexed { rowIdx, cells ->
            Row(modifier = Modifier.fillMaxWidth()) {
                cells.forEach { cell ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .background(if (rowIdx == 0) Dsh.bgSubtle else Color.Transparent)
                            .border(0.5.dp, Dsh.borderSubtle)
                            .padding(horizontal = 10.dp, vertical = if (compact) 6.dp else 10.dp)
                    ) {
                        Text(
                            cell,
                            color = Dsh.labelPrimary,
                            style = DshType.bodyDense,
                            fontWeight = if (rowIdx == 0) FontWeight(500) else FontWeight(400),
                        )
                    }
                }
            }
        }
    }
}

// 代码块（DSH：语言标签行 + 复制按钮 + 等宽内容，radius 12）
@Composable
private fun MarkdownCodeBlock(lang: String?, content: String) {
    val context = androidx.compose.ui.platform.LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(DshRadius.lg))
            .background(Dsh.bgCode)
            .border(1.dp, Dsh.borderSubtle, RoundedCornerShape(DshRadius.lg))
    ) {
        // 语言标签 + 复制（DSH code-block-banner）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Dsh.bgCodeBanner)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                lang ?: "code",
                color = Dsh.labelTertiary,
                style = DshType.microRelaxed,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f)
            )
            val copyInteraction = remember { MutableInteractionSource() }
            val copyPressed by copyInteraction.collectIsPressedAsState()
            Row(
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(DshRadius.sm))
                    .background(if (copyPressed) Dsh.pressed else Color.Transparent)
                    .clickable(interactionSource = copyInteraction, indication = dshRipple()) {
                        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("code", content.trimEnd()))
                        if (copiedNeedsAppToast(android.os.Build.VERSION.SDK_INT)) {
                            Toast.makeText(context, L.copied, Toast.LENGTH_SHORT).show()
                        }
                    }
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    CopyOutline16,
                    contentDescription = L.copy,
                    tint = Dsh.labelTertiary,
                    modifier = Modifier.size(12.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text(L.copy, color = Dsh.labelTertiary, style = DshType.microRelaxed, lineHeight = 16.sp)
            }
        }
        val dark = Dsh.isDark
        val highlighted = remember(content, lang, dark) { highlightCode(content.trimEnd(), lang, dark) }
        val codeScroll = rememberScrollState()
        // 代码面几何统一走 DshCodeSurface，避免各处 code 字号/行高漂移
        val codeMetrics = remember { dev.deeplinks.core.DshCodeSurface.default }
        Text(
            highlighted,
            color = Dsh.labelPrimary,
            fontFamily = FontFamily.Monospace,
            fontSize = codeMetrics.fontSize,
            lineHeight = codeMetrics.lineHeight,
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(codeScroll)
                .padding(horizontal = 16.dp, vertical = 10.dp)
        )
    }
}


/** 行内渲染：`code` / **bold** / *italic* / ~~删除线~~ / [链接](url) / $公式$（KaTeX，异步渲染完成后重组换图） */
@Composable
private fun InlineMarkdownText(
    text: String,
    color: Color = Dsh.labelPrimary,
    style: TextStyle = DshType.bodyLarge,
    /** 流式尾块：补齐未闭合标记、尾部新字淡入、行内光标；版式与定稿完全相同。 */
    streaming: Boolean = false,
    /** 标题等纯文本块不解析行内标记。 */
    markdown: Boolean = true,
) {
    val pressed = Dsh.pressed
    val labelPrimaryColor = Dsh.labelPrimary
    val brand400Color = Dsh.brand400
    val context = androidx.compose.ui.platform.LocalContext.current
    val openUrl: (String) -> Unit = remember(context) {
        { url ->
            // 普通 Markdown 链接与图片使用同一 HTTPS/公网校验，避免打开明文、私网或元数据地址。
            if (MarkdownMedia.isSafeImageUrl(url)) {
                runCatching {
                    context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)))
                }
            }
        }
    }
    SideEffect { MathRenderer.attach(context.applicationContext) }
    val mathRevision = MathRenderer.revision
    val source = if (streaming && markdown) closeStreamingMarkdown(text) else text
    val built = remember(source, markdown, pressed, labelPrimaryColor, brand400Color, color, mathRevision) {
        if (markdown) {
            buildInlineMarkdown(source, pressed, labelPrimaryColor, brand400Color, color.toArgb(), openUrl)
        } else {
            AnnotatedString(source) to emptyMap()
        }
    }
    if (!streaming) {
        Text(built.first, inlineContent = built.second, color = color, style = style)
        return
    }
    val fade = rememberStreamFade(built.first.length, enabled = !isReduceMotionEnabled())
    val caret = rememberStreamCaretContent()
    Text(
        built.first.withStreamTail(fade, color, caret = true),
        inlineContent = built.second + (STREAM_CARET_ID to caret),
        color = color,
        style = style,
    )
}

/** 标题：与正文同族，只放大字号；流式与定稿共用，避免完成时跳版。 */
@Composable
private fun markdownHeadingStyle(level: Int): TextStyle = DshType.bodyLarge.copy(
    fontSize = when (level) {
        1 -> 22.sp
        2 -> 18.sp
        else -> 16.sp
    },
    fontWeight = FontWeight(500),
    lineHeight = 28.sp,
)

private fun buildInlineMarkdown(
    text: String,
    pressed: Color,
    labelPrimaryColor: Color,
    brand400Color: Color,
    mathColorArgb: Int,
    openUrl: (String) -> Unit,
): Pair<AnnotatedString, Map<String, InlineTextContent>> {
    val inlineContent = mutableMapOf<String, InlineTextContent>()
    val annotated = buildAnnotatedString {
        var i = 0
        var mathIndex = 0
        while (i < text.length) {
            // 找下一个标记位置
            val tokens = listOf(
                text.indexOf('`', i) to '`',
                text.indexOf("**", i) to '*',
                text.indexOf("~~", i) to '~',
                text.indexOf("$", i) to '$',
                text.indexOf("[", i) to '[',
            ).filter { it.first != -1 }
            if (tokens.isEmpty()) {
                append(text.substring(i))
                break
            }
            val (nextIdx, kind) = tokens.minBy { it.first }
            append(text.substring(i, nextIdx))
            when (kind) {
                '`' -> {
                    val end = text.indexOf('`', nextIdx + 1)
                    if (end == -1) { append(text.substring(nextIdx)); break }
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = pressed, color = labelPrimaryColor)) {
                        append(text.substring(nextIdx + 1, end))
                    }
                    i = end + 1
                }
                '$' -> {
                    // 行内公式 $...$：内容首尾不能是空白（KaTeX 约定），不跨行、不嵌套
                    val end = text.indexOf('$', nextIdx + 1)
                    if (end == -1) { append(text.substring(nextIdx)); break }
                    val inner = text.substring(nextIdx + 1, end)
                    val valid = inner.isNotBlank() &&
                        !inner.startsWith(" ") && !inner.endsWith(" ") &&
                        !inner.contains('\n') && !inner.contains('$')
                    if (!valid) {
                        append("$")
                        i = nextIdx + 1
                        continue
                    }
                    val rendered = MathRenderer.peek(
                        inner,
                        MathRenderer.INLINE_FONT_CSS_PX,
                        mathColorArgb,
                        displayMode = false,
                    )
                    if (rendered == null) {
                        // 未命中缓存（渲染中）或非法 LaTeX：原文回退；渲染完成后 revision 变化触发重建
                        append("$inner$")
                        i = end + 1
                        continue
                    }
                    val key = "math-${mathIndex++}"
                    val start = length
                    append(" ")
                    addStringAnnotation("inlineContent", key, start, start + 1)
                    inlineContent[key] = InlineTextContent(
                        placeholder = Placeholder(
                            width = rendered.cssWidth.sp,
                            height = rendered.cssHeight.sp,
                            placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter,
                        )
                    ) {
                        LatexMathCanvas(rendered, Modifier.fillMaxSize())
                    }
                    i = end + 1
                }
                '*' -> {
                    // **bold** 或 *italic*
                    val after = if (nextIdx + 2 < text.length) text[nextIdx + 2] else '\u0000'
                    if (after == '*') {
                        val end = text.indexOf("**", nextIdx + 2)
                        if (end == -1) { append(text.substring(nextIdx)); break }
                        withStyle(SpanStyle(fontWeight = FontWeight(600))) {
                            append(text.substring(nextIdx + 2, end))
                        }
                        i = end + 2
                    } else {
                        val end = text.indexOf('*', nextIdx + 1)
                        if (end == -1) { append(text.substring(nextIdx)); break }
                        withStyle(SpanStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)) {
                            append(text.substring(nextIdx + 1, end))
                        }
                        i = end + 1
                    }
                }
                '~' -> {
                    val end = text.indexOf("~~", nextIdx + 2)
                    if (end == -1) { append(text.substring(nextIdx)); break }
                    withStyle(SpanStyle(textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough)) {
                        append(text.substring(nextIdx + 2, end))
                    }
                    i = end + 2
                }
                '[' -> {
                    // [text](url)
                    val close = text.indexOf(']', nextIdx + 1)
                    if (close == -1 || close + 1 >= text.length || text[close + 1] != '(') {
                        append("[")
                        i = nextIdx + 1
                        continue
                    }
                    val urlEnd = text.indexOf(')', close + 2)
                    if (urlEnd == -1) { append(text.substring(nextIdx)); break }
                    val label = text.substring(nextIdx + 1, close)
                    val url = text.substring(close + 2, urlEnd)
                    withLink(
                        LinkAnnotation.Url(
                            url = url,
                            styles = TextLinkStyles(
                                style = SpanStyle(
                                    color = brand400Color,
                                    textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline,
                                ),
                            ),
                            linkInteractionListener = LinkInteractionListener { annotation ->
                                (annotation as? LinkAnnotation.Url)?.url?.let(openUrl)
                            },
                        ),
                    ) {
                        append(label)
                    }
                    i = urlEnd + 1
                }
            }
        }
    }
    return annotated to inlineContent
}