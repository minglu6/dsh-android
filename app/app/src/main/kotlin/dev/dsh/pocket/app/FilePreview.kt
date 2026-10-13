package dev.dsh.pocket.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dsh.pocket.protocol.bool
import dev.dsh.pocket.protocol.get
import dev.dsh.pocket.protocol.long
import dev.dsh.pocket.protocol.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Open a workspace file path in the document preview sheet (chat / trajectory / files). */
val LocalOpenFilePreview = staticCompositionLocalOf<((String) -> Unit)?> { null }

enum class PreviewKind { Text, Image, Pdf, Unsupported }

fun previewKindFor(path: String): PreviewKind {
    val ext = path.substringAfterLast('.', missingDelimiterValue = "").lowercase()
    return when (ext) {
        "png", "jpg", "jpeg", "gif", "webp", "bmp", "ico" -> PreviewKind.Image
        "pdf" -> PreviewKind.Pdf
        "svg" -> PreviewKind.Text // SVG source is readable; bitmap path stays Image for rasters
        else -> PreviewKind.Text
    }
}

/**
 * Document preview sheet: `files.read` (text pages) or `files.readBytes` (image / PDF).
 * Copy action uses common locale `copy` / `copied`.
 */
@Composable
fun FilePreviewSheet(sessionId: String, path: String, onDismiss: () -> Unit, onBack: (() -> Unit)? = null) {
    val kind = remember(path) { previewKindFor(path) }
    var loading by remember(path) { mutableStateOf(true) }
    var error by remember(path) { mutableStateOf<String?>(null) }
    var text by remember(path) { mutableStateOf<String?>(null) }
    var bytes by remember(path) { mutableStateOf<ByteArray?>(null) }
    var copied by remember { mutableStateOf(false) }
    val clip = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    fun reload() {
        loading = true
        error = null
        text = null
        bytes = null
        scope.launch {
            runCatching {
                when (kind) {
                    PreviewKind.Image, PreviewKind.Pdf -> {
                        val r = Pocket.readFileBytes(sessionId, path)
                            ?: error("no client")
                        val b64 = r["data"].str() ?: error("empty")
                        bytes = Base64.decode(b64, Base64.DEFAULT)
                    }
                    PreviewKind.Text, PreviewKind.Unsupported -> {
                        text = readAllText(sessionId, path)
                    }
                }
            }.onFailure {
                error = it.message ?: L.docErrorUnavailable(it.toString())
            }
            loading = false
        }
    }

    LaunchedEffect(sessionId, path) { reload() }

    RightPanelFrame(
        title = path.substringAfterLast('/').substringAfterLast('\\').ifBlank { path },
        onDismiss = onDismiss,
        onBack = onBack,
    ) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 12.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 22.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(path, style = T.Mono.copy(color = T.Text3, fontSize = 12.sp), maxLines = 2,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (text != null) {
                Text(
                    if (copied) L.copied() else L.copy(),
                    fontSize = 14.sp,
                    color = T.Accent,
                    modifier = Modifier
                        .clip(T.R8)
                        .clickable {
                            clip.setText(AnnotatedString(text!!))
                            copied = true
                        }
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        when {
            loading -> Row(
                Modifier.fillMaxWidth().padding(32.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator(Modifier.size(28.dp), color = T.Accent, strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
                Text(L.docLoading(), fontSize = 14.sp, color = T.Text2)
            }
            error != null -> Column(Modifier.padding(horizontal = 22.dp, vertical = 12.dp)) {
                Text(error!!, fontSize = 14.sp, color = T.Err, lineHeight = 20.sp)
                Spacer(Modifier.height(12.dp))
                Text(
                    L.docRetry(),
                    fontSize = 14.sp,
                    color = T.Accent,
                    modifier = Modifier
                        .clip(T.R8)
                        .border(0.5.dp, T.Border, T.R8)
                        .clickable { reload() }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
            kind == PreviewKind.Image && bytes != null -> ImagePreviewBody(bytes!!)
            kind == PreviewKind.Pdf && bytes != null -> PdfPreviewBody(bytes!!)
            text != null -> {
                Box(
                    Modifier
                        .padding(horizontal = 16.dp)
                        .fillMaxWidth()
                        .clip(T.R12)
                        .background(T.CodeBlock)
                        .border(0.5.dp, T.Border, T.R12)
                        .horizontalScroll(rememberScrollState())
                        .padding(12.dp),
                ) {
                    Text(text!!, style = T.Mono.copy(lineHeight = 20.sp), color = T.Ink)
                }
            }
            else -> Text(
                L.docUnsupported(),
                fontSize = 14.sp,
                color = T.Text2,
                modifier = Modifier.padding(22.dp),
            )
        }
        }
    }
}

private suspend fun readAllText(sessionId: String, path: String): String = withContext(Dispatchers.IO) {
    val out = StringBuilder()
    var offset = 1
    var guard = 0
    while (guard++ < 500) {
        val r = Pocket.readFile(sessionId, path, offset = offset, limit = 200)
            ?: error("no client")
        val chunk = r["text"].str().orEmpty()
        val lines = r["lines"].long()?.toInt() ?: 0
        val eof = r["eof"].bool() ?: true
        if (chunk.isNotEmpty()) {
            if (out.isNotEmpty()) out.append('\n')
            out.append(chunk)
        }
        if (eof || lines == 0) break
        offset += lines
    }
    out.toString()
}

@Composable
private fun ImagePreviewBody(bytes: ByteArray) {
    val bitmap = remember(bytes) {
        runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()
    }
    Box(
        Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .clip(T.R12)
            .background(T.DocumentPreviewBg)
            .padding(12.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap == null) {
            Text(L.imagePreviewFailed(), fontSize = 14.sp, color = T.Text2)
        } else {
            Image(
                bitmap.asImageBitmap(),
                contentDescription = L.image(),
                modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp),
                contentScale = ContentScale.Fit,
            )
        }
    }
}

@Composable
private fun PdfPreviewBody(bytes: ByteArray) {
    val ctx = LocalContext.current
    var pageCount by remember { mutableIntStateOf(0) }
    var pages by remember { mutableStateOf<List<Bitmap>>(emptyList()) }
    var err by remember { mutableStateOf<String?>(null) }
    var cacheFile by remember { mutableStateOf<File?>(null) }

    DisposableEffect(bytes) {
        val f = File(ctx.cacheDir, "preview-${System.nanoTime()}.pdf")
        cacheFile = f
        onDispose {
            pages.forEach { it.recycle() }
            pages = emptyList()
            f.delete()
        }
    }

    LaunchedEffect(bytes) {
        err = null
        pages = emptyList()
        pageCount = 0
        runCatching {
            withContext(Dispatchers.IO) {
                val f = cacheFile ?: error("no cache")
                f.writeBytes(bytes)
                ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                    PdfRenderer(pfd).use { renderer ->
                        pageCount = renderer.pageCount
                        val rendered = ArrayList<Bitmap>(minOf(renderer.pageCount, 8))
                        val limit = minOf(renderer.pageCount, 8)
                        for (i in 0 until limit) {
                            renderer.openPage(i).use { page ->
                                val scale = 2
                                val bmp = Bitmap.createBitmap(
                                    page.width * scale,
                                    page.height * scale,
                                    Bitmap.Config.ARGB_8888,
                                )
                                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                rendered += bmp
                            }
                        }
                        pages = rendered
                    }
                }
            }
        }.onFailure {
            err = L.pdfFailed(it.message ?: it.toString())
        }
    }

    Column(
        Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .clip(T.R12)
            .background(T.DocumentPreviewBg)
            .padding(12.dp),
    ) {
        when {
            err != null -> Text(err!!, fontSize = 14.sp, color = T.Err)
            pages.isEmpty() -> Text(L.pdfRendering(), fontSize = 13.sp, color = T.Text2)
            else -> {
                pages.forEachIndexed { i, bmp ->
                    Text(L.pdfPage(i + 1), fontSize = 12.sp, color = T.Text3, modifier = Modifier.padding(bottom = 6.dp))
                    Image(
                        bmp.asImageBitmap(),
                        contentDescription = L.pdfPage(i + 1),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                        contentScale = ContentScale.FillWidth,
                    )
                }
                if (pageCount > pages.size) {
                    Text(
                        L.docLoadMore(),
                        fontSize = 12.sp,
                        color = T.Caption,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}
