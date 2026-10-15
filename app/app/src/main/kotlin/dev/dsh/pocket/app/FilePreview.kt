package dev.dsh.pocket.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color as AndroidColor
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.dsh.pocket.protocol.bool
import dev.dsh.pocket.protocol.get
import dev.dsh.pocket.protocol.long
import dev.dsh.pocket.protocol.str
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File

/** Shared real workspace-file entry for chat, deliverables and the files panel. */
val LocalOpenFilePreview = staticCompositionLocalOf<((String) -> Unit)?> { null }
private fun previewText(zh: String, en: String) = if (Pocket.prefs.language == AppLanguage.En) en else zh

enum class PreviewKind { Text, Image, Pdf, Office, Workbook, Unsupported }

fun previewKindFor(path: String): PreviewKind = when (path.substringAfterLast('.', "").lowercase()) {
    "png", "jpg", "jpeg", "gif", "webp", "bmp", "ico", "avif", "heic", "heif" -> PreviewKind.Image
    "pdf" -> PreviewKind.Pdf
    "doc", "docx", "ppt", "pptx" -> PreviewKind.Office
    "xls", "xlsx" -> PreviewKind.Workbook
    "xlsm", "xlsb", "odt", "ods", "odp", "pages", "numbers", "key",
    "zip", "gz", "tar", "7z", "rar", "exe", "so", "dylib", "mp4", "mov", "mp3", "wav", "woff", "ttf" -> PreviewKind.Unsupported
    else -> PreviewKind.Text
}

private enum class TextPreviewMode { Preview, Code, Plain }

@Composable
fun FilePreviewSheet(sessionId: String, path: String, onDismiss: () -> Unit, onBack: (() -> Unit)? = null) {
    val host by Pocket.active.collectAsState()
    val context = LocalContext.current
    val kind = remember(path) { previewKindFor(path) }
    val extension = path.substringAfterLast('.', "").lowercase()
    val richText = extension in setOf("md", "markdown", "html", "htm", "csv", "tsv")
    val language = remember(path) { previewCodeLanguage(path) }
    val modes = remember(richText, language) {
        buildList {
            if (richText) add(TextPreviewMode.Preview)
            if (language != null || richText) add(TextPreviewMode.Code)
            add(TextPreviewMode.Plain)
        }
    }
    var mode by remember(host, sessionId, path) { mutableStateOf(modes.first()) }
    var modeMenu by remember(host, sessionId, path) { mutableStateOf(false) }
    var generation by remember(host, sessionId, path) { mutableIntStateOf(0) }
    var loading by remember(host, sessionId, path) { mutableStateOf(true) }
    var error by remember(host, sessionId, path) { mutableStateOf<String?>(null) }
    var text by remember(host, sessionId, path) { mutableStateOf<String?>(null) }
    var bytes by remember(host, sessionId, path) { mutableStateOf<ByteArray?>(null) }
    var office by remember(host, sessionId, path) { mutableStateOf<OfficePreviewDownload?>(null) }
    var refreshed by remember(host, sessionId, path) { mutableStateOf<String?>(null) }
    var copied by remember(host, sessionId, path) { mutableStateOf(false) }
    var wrap by remember(host, sessionId, path) { mutableStateOf(Pocket.prefs.codeWordWrap) }
    var fullImage by remember(host, sessionId, path) { mutableStateOf(false) }
    val clip = LocalClipboardManager.current
    LaunchedEffect(copied, host, sessionId, path) {
        if (copied) {
            delay(1000)
            copied = false
        }
    }

    LaunchedEffect(host, sessionId, path, generation) {
        loading = true
        error = null
        copied = false
        try {
            when (kind) {
                PreviewKind.Image, PreviewKind.Pdf -> {
                    val result = Pocket.readFileBytes(sessionId, path) ?: error("Disconnected")
                    check(result["eof"].bool() != false) { previewText("文件未读取完整，无法预览", "Incomplete file response; cannot preview") }
                    val next = withContext(Dispatchers.Default) {
                        Base64.decode(result["data"].str() ?: error("Empty file response"), Base64.DEFAULT)
                    }
                    refreshed = bytes?.let { if (it.contentEquals(next)) previewText("文件未变更", "File unchanged") else previewText("已加载文件的新版本", "Loaded updated file") }
                    bytes = next
                }
                PreviewKind.Office -> {
                    val client = Pocket.clientOrNull() ?: error("Disconnected")
                    val next = loadOfficePreview(client, sessionId, path, context.cacheDir)
                    try {
                        refreshed = office?.let {
                            if (it.version == next.version && it.generation == next.generation) previewText("文件未变更", "File unchanged")
                            else previewText("已加载文件的新版本", "Loaded updated file")
                        }
                        office = next
                        loading = false
                        awaitCancellation()
                    } finally {
                        withContext(NonCancellable + Dispatchers.IO) { next.close() }
                    }
                }
                PreviewKind.Workbook -> {
                    val client = Pocket.clientOrNull() ?: error("Disconnected")
                    val next = readWorkbookBytes(client, sessionId, path)
                    val unchanged = bytes?.contentEquals(next) == true
                    refreshed = bytes?.let {
                        if (unchanged) previewText("文件未变更", "File unchanged")
                        else previewText("已加载文件的新版本", "Loaded updated file")
                    }
                    if (!unchanged) bytes = next
                }
                PreviewKind.Text -> {
                    val next = readAllText(sessionId, path)
                    refreshed = text?.let { if (it == next) previewText("文件未变更", "File unchanged") else previewText("已加载文件的新版本", "Loaded updated file") }
                    text = next
                }
                PreviewKind.Unsupported -> Unit
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (currentCoroutineContext().isActive) {
                error = when (kind) {
                    PreviewKind.Office -> officePreviewErrorMessage(failure)
                    PreviewKind.Workbook -> workbookPreviewErrorMessage(failure)
                    else -> failure.message ?: L.docErrorUnavailable(failure.toString())
                }
            }
        } finally {
            if (currentCoroutineContext().isActive) loading = false
        }
    }

    if (fullImage && bytes != null) FullScreenImagePreview(bytes!!) { fullImage = false }
    RightPanelFrame(
        title = path.substringAfterLast('/').substringAfterLast('\\').ifBlank { path },
        onDismiss = onDismiss,
        onBack = onBack,
        titleIcon = { FileTypeIcon(path, modifier = Modifier.padding(horizontal = 12.dp).size(18.dp)) },
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PreviewPathLabel(path, Modifier.weight(1f))
            if (kind == PreviewKind.Office && !loading && error == null) {
                office?.let { OfficeFontWarning(it.missingFonts) }
            }
            if (kind == PreviewKind.Text && modes.size > 1) {
                Box {
                    TextButton(
                        onClick = { modeMenu = true },
                        contentPadding = PaddingValues(horizontal = 8.dp),
                        colors = ButtonDefaults.textButtonColors(contentColor = T.Text2),
                        modifier = Modifier.semantics {
                            contentDescription = previewText("打开方式", "Open with")
                        },
                    ) {
                        Text(previewModeLabel(mode, richText), fontSize = 12.sp, maxLines = 1)
                    }
                    DropdownMenu(
                        expanded = modeMenu,
                        onDismissRequest = { modeMenu = false },
                        containerColor = T.Surface,
                    ) {
                        modes.forEach { candidate ->
                            DropdownMenuItem(
                                text = { Text(previewModeLabel(candidate, richText), fontSize = 13.sp) },
                                trailingIcon = {
                                    if (candidate == mode) Icon(D.check, null, Modifier.size(16.dp), tint = T.Text2)
                                },
                                modifier = Modifier.semantics { selected = candidate == mode },
                                colors = MenuDefaults.itemColors(textColor = T.Ink),
                                onClick = { mode = candidate; modeMenu = false },
                            )
                        }
                    }
                }
            }
            if (kind == PreviewKind.Text && mode != TextPreviewMode.Preview) {
                PreviewToolIcon(
                    icon = if (wrap) D.nowrap else D.wrap,
                    label = if (wrap) L.unwrapLines() else L.wrapLines(),
                    ariaLabel = L.wrapLines(),
                    checked = wrap,
                ) { wrap = !wrap }
            }
            PreviewToolIcon(
                icon = D.refresh,
                label = previewText("刷新文件", "Refresh file"),
                enabled = !loading,
            ) { generation++ }
        }
        HorizontalDivider(color = T.Border, thickness = 0.5.dp)
        refreshed?.let { Text(it, color = T.Text3, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp)) }
        when {
            loading && (if (kind == PreviewKind.Workbook) bytes == null else kind != PreviewKind.Text || text == null) -> Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = T.Accent, modifier = Modifier.size(28.dp))
            }
            error != null -> Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(16.dp)) {
                SelectableOutput { Text(error!!, color = T.Err) }
                TextButton(onClick = { generation++ }) { Text(L.docRetry()) }
            }
            kind == PreviewKind.Image && bytes != null -> Column(Modifier.weight(1f)) {
                TextButton(onClick = { fullImage = true }) { Text(L.viewOriginal()) }
                ImagePreviewBody(bytes!!, Modifier.fillMaxWidth().weight(1f))
            }
            kind == PreviewKind.Pdf && bytes != null -> PdfPreviewBody(bytes!!, Modifier.fillMaxWidth().weight(1f))
            kind == PreviewKind.Office && office != null -> PdfPreviewBody(office!!.file, Modifier.fillMaxWidth().weight(1f))
            kind == PreviewKind.Workbook && bytes != null -> WorkbookPreviewBody(path, bytes!!, Modifier.fillMaxWidth().weight(1f))
            text != null -> {
                val value = text!!
                when {
                    mode == TextPreviewMode.Preview && extension in setOf("csv", "tsv") ->
                        DelimitedPreview(value, if (extension == "tsv") '\t' else ',', Modifier.weight(1f))
                    mode == TextPreviewMode.Preview && extension in setOf("html", "htm") ->
                        StaticHtmlPreview(value, Modifier.fillMaxWidth().weight(1f))
                    mode == TextPreviewMode.Preview ->
                        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(16.dp)) {
                            MarkdownView(value)
                        }
                    else -> Column(Modifier.fillMaxWidth().weight(1f)) {
                        if (mode == TextPreviewMode.Code) {
                            Row(
                                Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    language ?: previewText("代码", "Code"),
                                    color = T.Text2,
                                    fontFamily = LocalFonts.code,
                                    fontSize = 12.sp,
                                    modifier = Modifier.weight(1f),
                                )
                                PreviewToolIcon(
                                    icon = if (copied) D.check else D.copy,
                                    label = if (copied) L.copied() else L.copy(),
                                ) {
                                    if (!copied) {
                                        clip.setText(AnnotatedString(value))
                                        copied = true
                                    }
                                }
                            }
                        }
                        CodePreviewBody(
                            path = path,
                            text = value,
                            wrap = wrap,
                            plain = mode == TextPreviewMode.Plain,
                            modifier = Modifier.fillMaxWidth().weight(1f),
                        )
                    }
                }
            }
            else -> Text(L.docUnsupported(), color = T.Text2, modifier = Modifier.padding(16.dp))
        }
    }
}

private fun previewModeLabel(mode: TextPreviewMode, richText: Boolean): String = when (mode) {
    TextPreviewMode.Preview -> previewText("预览", "Preview")
    TextPreviewMode.Code -> if (richText) previewText("源码", "Source") else previewText("代码", "Code")
    TextPreviewMode.Plain -> previewText("纯文本", "Plain text")
}

/** Preserve the complete selectable path; start at its filename rather than truncating the tail. */
@Composable
private fun PreviewPathLabel(path: String, modifier: Modifier) {
    val split = maxOf(path.lastIndexOf('/'), path.lastIndexOf('\\')) + 1
    val label = buildAnnotatedString {
        withStyle(SpanStyle(color = T.Text3)) { append(path.substring(0, split)) }
        withStyle(SpanStyle(color = T.Ink)) { append(path.substring(split)) }
    }
    val scroll = remember(path) { ScrollState(Int.MAX_VALUE) }
    Box(modifier) {
        SelectableOutput {
            Text(
                label,
                style = T.Mono.copy(fontSize = 12.sp),
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.fillMaxWidth().horizontalScroll(scroll).padding(vertical = 12.dp),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PreviewToolIcon(
    icon: ImageVector,
    label: String,
    ariaLabel: String = label,
    enabled: Boolean = true,
    checked: Boolean? = null,
    onClick: () -> Unit,
) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(label, fontSize = 12.sp) } },
        state = rememberTooltipState(),
    ) {
        if (checked == null) {
            IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(40.dp)) {
                Icon(icon, ariaLabel, tint = if (enabled) T.Text2 else T.Text3, modifier = Modifier.size(16.dp))
            }
        } else {
            IconToggleButton(
                checked = checked,
                onCheckedChange = { onClick() },
                enabled = enabled,
                modifier = Modifier.size(40.dp).semantics {
                    stateDescription = if (checked) previewText("已换行", "Wrapped") else previewText("不换行", "Not wrapped")
                },
                colors = IconButtonDefaults.iconToggleButtonColors(contentColor = T.Text2, checkedContentColor = T.Ink),
            ) {
                Icon(icon, ariaLabel, modifier = Modifier.size(16.dp))
            }
        }
    }
}

/** Only embedded subresources may bypass interception; CSP further restricts their content types. */
internal fun allowStaticHtmlResource(isMainFrame: Boolean, scheme: String?): Boolean =
    !isMainFrame && scheme.equals("data", ignoreCase = true)

/** Same static-only policy as official basic-document.ts; no JavaScript bridge or navigable browser. */
@Composable
private fun StaticHtmlPreview(text: String, modifier: Modifier) {
    val html = remember(text) {
        """<meta http-equiv="Content-Security-Policy" content="default-src 'none'; script-src 'none'; style-src 'unsafe-inline'; img-src data:; font-src data:; media-src data:; connect-src 'none'; frame-src 'none'; form-action 'none'; base-uri 'none'">""" + text
    }
    Column(modifier) {
        Text(previewText("安全静态预览：不执行脚本、不加载外部资源。", "Safe static preview: scripts and external resources are disabled."),
            color = T.Text3, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        AndroidView(
            factory = { context ->
                WebView(context).apply {
                    settings.javaScriptEnabled = false
                    settings.domStorageEnabled = false
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.blockNetworkLoads = true
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    settings.setSupportMultipleWindows(false)
                    settings.javaScriptCanOpenWindowsAutomatically = false
                    settings.setSupportZoom(true)
                    settings.builtInZoomControls = true
                    settings.displayZoomControls = false
                    settings.useWideViewPort = true
                    settings.loadWithOverviewMode = true
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
                        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                            if (allowStaticHtmlResource(request.isForMainFrame, request.url.scheme)) null
                            else WebResourceResponse("text/plain", "UTF-8", java.io.ByteArrayInputStream(ByteArray(0)))
                    }
                }
            },
            update = { view ->
                if (view.tag != html) {
                    view.tag = html
                    view.loadDataWithBaseURL("https://dsh-preview.invalid/", html, "text/html", "UTF-8", null)
                }
            },
            onRelease = { view -> view.stopLoading(); view.destroy() },
            modifier = Modifier.fillMaxWidth().weight(1f),
        )
    }
}

private suspend fun readAllText(sessionId: String, path: String): String = withContext(Dispatchers.IO) {
    val out = StringBuilder()
    var offset = 1
    var version: String? = null
    while (true) {
        val result = Pocket.readFile(sessionId, path, offset = offset, limit = 200) ?: error("Disconnected")
        val pageVersion = result["version"].str()
        if (offset == 1) version = pageVersion
        else check(pageVersion == version) { previewText("文件在读取过程中发生变更，请刷新。", "The file changed while reading; refresh to reload.") }
        val chunk = result["text"].str().orEmpty()
        val lines = result["lines"].long()?.toInt() ?: 0
        if ('\u0000' in chunk) error(previewText("二进制文件不支持文本预览", "Binary files cannot be previewed as text"))
        if (out.length + chunk.length > 4_000_000) error(previewText("文件过大，无法安全预览全文", "File too large to safely preview in full"))
        if (lines > 0) {
            if (offset > 1) out.append('\n')
            out.append(chunk)
        }
        if (result["eof"].bool() != false) break
        check(lines > 0) { "File response did not advance" }
        offset += lines
    }
    out.toString()
}

/** RFC 4180 quoted cells, escaped quotes and embedded newlines; no lossy line splitting. */
internal fun parseDelimitedPreview(text: String, delimiter: Char): List<List<String>> {
    val rows = mutableListOf<List<String>>()
    var row = mutableListOf<String>()
    val cell = StringBuilder()
    var quoted = false
    var index = 0
    var cells = 0
    fun finishCell() {
        require(++cells <= 100_000 && row.size < 128) { "Table too large for grid preview; use Source / 表格过大，请切换源码查看" }
        row.add(cell.toString())
        cell.setLength(0)
    }
    while (index < text.length) {
        val ch = text[index]
        when {
            ch == '"' && quoted && text.getOrNull(index + 1) == '"' -> { cell.append('"'); index++ }
            ch == '"' && (quoted || cell.isEmpty()) -> quoted = !quoted
            ch == delimiter && !quoted -> finishCell()
            (ch == '\n' || ch == '\r') && !quoted -> {
                finishCell(); rows.add(row); row = mutableListOf()
                if (ch == '\r' && text.getOrNull(index + 1) == '\n') index++
            }
            else -> cell.append(ch)
        }
        index++
    }
    require(!quoted) { "Unclosed quoted table cell" }
    if (cell.isNotEmpty() || row.isNotEmpty() || (text.isNotEmpty() && text.last() != '\n' && text.last() != '\r')) {
        finishCell()
        rows.add(row)
    }
    return rows
}

@Composable
private fun DelimitedPreview(text: String, delimiter: Char, modifier: Modifier) {
    val result = remember(text, delimiter) { runCatching { parseDelimitedPreview(text, delimiter) } }
    val rows = result.getOrNull()
    if (rows == null) {
        Text(result.exceptionOrNull()?.message.orEmpty(), color = T.Err, modifier = modifier.padding(16.dp))
        return
    }
    val columns = remember(rows) { rows.maxOfOrNull { it.size } ?: 0 }
    Box(modifier.horizontalScroll(rememberScrollState())) {
        LazyColumn(Modifier.width((columns.coerceAtLeast(1) * 160).dp)) {
            itemsIndexed(rows) { index, row ->
                Row(Modifier.fillMaxWidth().background(if (index == 0) T.Fill2 else T.Surface)) {
                    repeat(columns) { column ->
                        SelectableOutput(Modifier.width(160.dp).padding(8.dp)) {
                            Text(row.getOrNull(column).orEmpty(), color = T.Ink, fontSize = Pocket.prefs.codeFontSize.sp)
                        }
                    }
                }
                HorizontalDivider(color = T.Border)
            }
        }
    }
}

private fun decodePreviewBitmap(bytes: ByteArray, maxPixels: Long): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sample = 1
    while (bounds.outWidth.toLong() / sample * (bounds.outHeight / sample) > maxPixels ||
        bounds.outWidth / sample > 8192 || bounds.outHeight / sample > 8192) sample *= 2
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
}

@Composable
private fun ImagePreviewBody(bytes: ByteArray, modifier: Modifier = Modifier, original: Boolean = false) {
    var loading by remember(bytes, original) { mutableStateOf(true) }
    val bitmap by produceState<Bitmap?>(null, bytes, original) {
        try { value = withContext(Dispatchers.Default) { decodePreviewBitmap(bytes, if (original) 16_000_000L else 4_000_000L) } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { value = null }
        finally { loading = false }
    }
    Box(modifier.background(T.DocumentPreviewBg), contentAlignment = Alignment.Center) {
        when {
            loading -> CircularProgressIndicator(color = T.Accent, modifier = Modifier.size(24.dp))
            bitmap != null -> Image(bitmap!!.asImageBitmap(), L.image(), Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            else -> Text(L.imagePreviewFailed(), color = T.Text2)
        }
    }
}

internal fun clampImageTranslation(translation: Offset, scale: Float, viewport: IntSize): Offset {
    if (scale <= 1f || viewport.width <= 0 || viewport.height <= 0) return Offset.Zero
    val overflow = (scale - 1f).coerceAtLeast(0f) / 2f
    val maxX = viewport.width * overflow
    val maxY = viewport.height * overflow
    return Offset(translation.x.coerceIn(-maxX, maxX), translation.y.coerceIn(-maxY, maxY))
}

@Composable
fun FullScreenImagePreview(bytes: ByteArray, onDismiss: () -> Unit) {
    var scale by remember(bytes) { mutableFloatStateOf(1f) }
    var translation by remember(bytes) { mutableStateOf(Offset.Zero) }
    var viewport by remember(bytes) { mutableStateOf(IntSize.Zero) }
    fun zoomTo(nextScale: Float) {
        scale = nextScale.coerceIn(1f, 8f)
        translation = clampImageTranslation(translation, scale, viewport)
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = T.Surface) {
            Column(Modifier.systemBarsPadding()) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { scale = 1f; translation = Offset.Zero }) { Text(previewText("重置", "Reset")) }
                    TextButton(onClick = { zoomTo(scale / 1.5f) },
                        modifier = Modifier.semantics { contentDescription = previewText("缩小图片", "Zoom out") }) { Text("−", fontSize = 24.sp) }
                    TextButton(onClick = { zoomTo(scale * 1.5f) },
                        modifier = Modifier.semantics { contentDescription = previewText("放大图片", "Zoom in") }) { Text("+", fontSize = 24.sp) }
                    TextButton(onClick = onDismiss) { Text(previewText("关闭", "Close")) }
                }
                Box(Modifier.fillMaxWidth().weight(1f).clipToBounds().onSizeChanged {
                    viewport = it
                    translation = clampImageTranslation(translation, scale, it)
                }.pointerInput(bytes) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 8f)
                        translation = clampImageTranslation(translation + pan, scale, size)
                    }
                }) {
                    ImagePreviewBody(bytes, Modifier.fillMaxSize().graphicsLayer {
                        scaleX = scale; scaleY = scale; translationX = translation.x; translationY = translation.y
                    }, original = true)
                }
            }
        }
    }
}

/** Renderer owns only the PDF descriptor. Visible lazy rows own their bounded-size bitmaps. */
internal class PreviewPdf(val file: File, private val deleteOnClose: Boolean = true) : AutoCloseable {
    private val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = try { PdfRenderer(descriptor) } catch (failure: Throwable) { descriptor.close(); throw failure }
    private var closed = false
    val pageCount = renderer.pageCount
    @Synchronized fun render(index: Int): Bitmap {
        check(!closed)
        return renderer.openPage(index).use { page ->
            val scale = minOf(1440f / page.width, 2048f / page.height, 2f)
            Bitmap.createBitmap((page.width * scale).toInt().coerceAtLeast(1), (page.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888).also {
                it.eraseColor(AndroidColor.WHITE)
                page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            }
        }
    }
    @Synchronized override fun close() {
        if (!closed) {
            closed = true
            try { renderer.close() } finally { if (deleteOnClose) file.delete() }
        }
    }
}

/** Own the descriptor until cancellation and finish cleanup off the UI thread even after disposal. */
internal suspend fun holdPreviewPdf(bytes: ByteArray, cacheDir: File, onOpened: (PreviewPdf) -> Unit) {
    var opened: PreviewPdf? = null
    try {
        withContext(Dispatchers.IO) {
            val file = File.createTempFile("preview-", ".pdf", cacheDir)
            try { file.writeBytes(bytes); opened = PreviewPdf(file) }
            catch (failure: Throwable) { file.delete(); throw failure }
        }
        onOpened(checkNotNull(opened))
        awaitCancellation()
    } finally {
        withContext(NonCancellable + Dispatchers.IO) { opened?.close() }
    }
}

/** A downloaded Office preview owns its file; the renderer only borrows its descriptor. */
internal suspend fun holdPreviewPdf(file: File, onOpened: (PreviewPdf) -> Unit) {
    var opened: PreviewPdf? = null
    try {
        withContext(Dispatchers.IO) { opened = PreviewPdf(file, deleteOnClose = false) }
        onOpened(checkNotNull(opened))
        awaitCancellation()
    } finally {
        withContext(NonCancellable + Dispatchers.IO) { opened?.close() }
    }
}

@Composable
private fun PdfPreviewBody(bytes: ByteArray, modifier: Modifier) {
    val context = LocalContext.current
    var document by remember(bytes) { mutableStateOf<PreviewPdf?>(null) }
    var error by remember(bytes) { mutableStateOf<String?>(null) }
    LaunchedEffect(bytes) {
        try { holdPreviewPdf(bytes, context.cacheDir) { document = it } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = L.pdfFailed(failure.message.orEmpty()) }
    }
    PdfPreviewPages(document, error, modifier)
}

@Composable
private fun PdfPreviewBody(file: File, modifier: Modifier) {
    var document by remember(file) { mutableStateOf<PreviewPdf?>(null) }
    var error by remember(file) { mutableStateOf<String?>(null) }
    LaunchedEffect(file) {
        try { holdPreviewPdf(file) { document = it } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = L.pdfFailed(failure.message.orEmpty()) }
    }
    PdfPreviewPages(document, error, modifier)
}

@Composable
private fun PdfPreviewPages(document: PreviewPdf?, error: String?, modifier: Modifier) {
    val pdf = document
    when {
        error != null -> Text(error!!, color = T.Err, modifier = modifier.padding(16.dp))
        pdf == null -> Box(modifier, contentAlignment = Alignment.Center) { Text(L.pdfRendering(), color = T.Text2) }
        else -> LazyColumn(modifier.background(T.DocumentPreviewBg), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(pdf.pageCount, key = { it }) { index -> PdfPage(pdf, index) }
        }
    }
}

@Composable
private fun PdfPage(document: PreviewPdf, index: Int) {
    var bitmap by remember(document, index) { mutableStateOf<Bitmap?>(null) }
    var error by remember(document, index) { mutableStateOf<String?>(null) }
    LaunchedEffect(document, index) {
        try { bitmap = withContext(Dispatchers.IO) { document.render(index) } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = L.pdfFailed(failure.message.orEmpty()) }
    }
    Column(Modifier.fillMaxWidth()) {
        Text("${L.pdfPage(index + 1)} / ${document.pageCount}", color = T.Text3, fontSize = 12.sp)
        val page = bitmap
        when {
            page != null -> Image(page.asImageBitmap(), L.pdfPage(index + 1), Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth)
            error != null -> Text(error!!, color = T.Err)
            else -> Box(Modifier.fillMaxWidth().height(320.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = T.Accent) }
        }
    }
}
