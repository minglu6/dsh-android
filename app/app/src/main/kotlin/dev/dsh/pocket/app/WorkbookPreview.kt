package dev.dsh.pocket.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color as AndroidColor
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import dev.dsh.pocket.protocol.PocketClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.Base64
import java.util.Locale
import java.util.UUID

internal const val WorkbookMaxBytes = 16 * 1024 * 1024
internal const val WorkbookChunkBytes = 64 * 1024
private const val WorkbookOrigin = "https://workbook-preview.invalid"
private const val WorkbookAssetPrefix = "$WorkbookOrigin/workbook-preview/"
private const val WorkbookUrl = "${WorkbookAssetPrefix}index.html"
private const val WorkbookInvalid = "invalid"
private class WorkbookReadFailure(val reason: String, cause: Throwable? = null) : IOException(reason, cause)

internal fun workbookPreviewErrorMessage(failure: Throwable): String {
    val english = Pocket.prefs.language == AppLanguage.En
    return when ((failure as? WorkbookReadFailure)?.reason) {
        "invalid" -> if (english) "This spreadsheet could not be opened. Check its format, contents, or password protection." else "无法打开此表格。请检查文件格式、内容或密码保护。"
        "tooLarge" -> if (english) "This workbook exceeds the preview size limit." else "此表格超过预览大小限制。"
        "source-changed" -> if (english) "The file changed while being read. Reopen the preview." else "文件在读取时已更改，请重新打开预览。"
        else -> failure.message ?: L.docErrorUnavailable(failure.toString())
    }
}

/** Bounded range requests use the existing Host file protocol, never Host web credentials. */
internal suspend fun readWorkbookBytes(client: PocketClient, sessionId: String, path: String): ByteArray =
    withContext(Dispatchers.IO) {
        readWorkbookChunks { offset, length ->
            client.call("files.readBytes", buildJsonObject {
                put("sessionId", sessionId)
                put("path", path)
                putJsonObject("options") { putJsonObject("range") { put("offset", offset); put("length", length) } }
            })
        }
    }

/** Testable wire boundary: reject mixed revisions, truncated reads and unbounded allocations. */
internal suspend fun readWorkbookChunks(read: suspend (Int, Int) -> JsonElement): ByteArray {
    var identity: Pair<String, String>? = null
    var result: ByteArray? = null
    var offset = 0
    var confirming = false
    while (true) {
        currentCoroutineContext().ensureActive()
        val length = if (confirming) 1 else WorkbookChunkBytes
        val page = read(offset, length) as? JsonObject ?: throw WorkbookReadFailure(WorkbookInvalid)
        currentCoroutineContext().ensureActive()
        fun field(name: String) = page[name] as? JsonPrimitive ?: throw WorkbookReadFailure(WorkbookInvalid)
        val absolute = field("absolutePath").takeIf { it.isString }?.contentOrNull ?: throw WorkbookReadFailure(WorkbookInvalid)
        val version = field("version").takeIf { it.isString }?.contentOrNull ?: throw WorkbookReadFailure(WorkbookInvalid)
        val total = field("bytes").takeUnless { it.isString }?.intOrNull ?: throw WorkbookReadFailure(WorkbookInvalid)
        val returnedOffset = field("offset").takeUnless { it.isString }?.intOrNull ?: throw WorkbookReadFailure(WorkbookInvalid)
        val eof = field("eof").takeUnless { it.isString }?.booleanOrNull ?: throw WorkbookReadFailure(WorkbookInvalid)
        if (total > WorkbookMaxBytes) throw WorkbookReadFailure("tooLarge")
        if (total < 1 || version.isEmpty() || !(absolute.startsWith("/") || absolute.startsWith("\\\\") || Regex("^[A-Za-z]:[/\\\\]").containsMatchIn(absolute))) throw WorkbookReadFailure(WorkbookInvalid)
        val nextIdentity = absolute to version
        if (identity != null && (identity != nextIdentity || result?.size != total)) throw WorkbookReadFailure("source-changed")
        if (returnedOffset != offset || field("encoding").contentOrNull != "base64") throw WorkbookReadFailure(WorkbookInvalid)
        val encoded = field("data").takeIf { it.isString }?.contentOrNull ?: throw WorkbookReadFailure(WorkbookInvalid)
        if (encoded.length > ((length + 2) / 3) * 4) throw WorkbookReadFailure(WorkbookInvalid)
        val chunk = try { Base64.getDecoder().decode(encoded) } catch (error: IllegalArgumentException) { throw WorkbookReadFailure(WorkbookInvalid, error) }
        if (chunk.size > length || offset + chunk.size > total || eof != (offset + chunk.size == total)) throw WorkbookReadFailure(WorkbookInvalid)
        if (confirming) {
            if (!eof || chunk.isNotEmpty()) throw WorkbookReadFailure("source-changed")
            return result ?: throw WorkbookReadFailure(WorkbookInvalid)
        }
        if (chunk.isEmpty()) throw WorkbookReadFailure(WorkbookInvalid)
        if (result == null) { result = ByteArray(total); identity = nextIdentity }
        chunk.copyInto(result, offset)
        offset += chunk.size
        confirming = eof
    }
}

/** Exact current document + generated assets; not a filesystem or URL-prefix allowlist. */
internal fun workbookPreviewResourceFor(url: String, mainFrame: Boolean, assets: Set<String>, documentUrl: String): String? {
    if (mainFrame) return "index.html".takeIf { url == WorkbookUrl }
    if (url == documentUrl) return "@document"
    if (!url.startsWith(WorkbookAssetPrefix)) return null
    return url.removePrefix(WorkbookAssetPrefix).takeIf { it in assets }
}

private fun Color.workbookCss(): String = String.format(Locale.ROOT, "#%08x", toArgb().let { (it shl 8) or (it ushr 24) })

/** A fresh WebView for every document identity: old Workers/callbacks cannot reach a new file. */
@Composable
internal fun WorkbookPreviewBody(path: String, bytes: ByteArray, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val renderer = remember(context, path, bytes) { WorkbookPreviewRenderer(context, path, bytes) }
    val colors = ThemeState.colors
    val tokens = JSONObject()
        .put("--dsw-alias-label-primary", T.Ink.workbookCss())
        .put("--dsw-alias-label-secondary", T.Text2.workbookCss())
        .put("--dsw-alias-label-tertiary", T.Text3.workbookCss())
        .put("--dsw-alias-bg-document-preview", T.DocumentPreviewBg.workbookCss())
        .put("--dsw-alias-state-warn-label", T.WarnText.workbookCss())
        .put("--dsw-alias-state-warn-tertiary", T.WarnTint.workbookCss())
        .put("--dsw-alias-interactive-bg-hover", T.HoverBg.workbookCss())
        .put("--dsw-alias-interactive-bg-active", colors.InteractiveBgActive.workbookCss())
        .put("--dsw-alias-tooltip-bg", colors.TooltipBg.workbookCss())
    val options = JSONObject().put("dark", ThemeState.dark)
        .put("locale", if (Pocket.prefs.language == AppLanguage.En) "en" else "zh").put("tokens", tokens).toString()
    DisposableEffect(renderer) { onDispose { renderer.destroy() } }
    key(renderer) {
        AndroidView(modifier = modifier, factory = { renderer.view }, update = { renderer.update(options) })
    }
}

@SuppressLint("SetJavaScriptEnabled")
private class WorkbookPreviewRenderer(context: Context, path: String, private val bytes: ByteArray) {
    private val documentUrl = "$WorkbookOrigin/document/${UUID.randomUUID()}"
    private val format = path.substringAfterLast('.').lowercase(Locale.ROOT)
    private val assets = context.assets.open("workbook-preview/manifest.json").bufferedReader().use {
        val names = JSONArray(it.readText())
        buildSet { for (index in 0 until names.length()) add(names.getString(index)) }
    }
    private var ready = false
    @Volatile private var destroyed = false
    private var sentDocument = false
    private var options: String? = null
    private var sentOptions: String? = null
    val view = WebView(context).apply {
        setBackgroundColor(AndroidColor.TRANSPARENT)
        settings.apply {
            javaScriptEnabled = true
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            allowFileAccess = false
            allowContentAccess = false
            @Suppress("DEPRECATION")
            allowFileAccessFromFileURLs = false
            @Suppress("DEPRECATION")
            allowUniversalAccessFromFileURLs = false
            blockNetworkLoads = true
            blockNetworkImage = true
            loadsImagesAutomatically = false
            domStorageEnabled = false
            databaseEnabled = false
            cacheMode = WebSettings.LOAD_NO_CACHE
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setGeolocationEnabled(false)
            setSupportZoom(false)
            textZoom = 100
        }
        webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) { request.deny() }
        }
        // No JavascriptInterface, file chooser, download route, cookies or Host API bridge.
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
            @Deprecated("Legacy WebView callback")
            override fun shouldOverrideUrlLoading(view: WebView, url: String) = true
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                if (destroyed || request.method != "GET") return denied()
                val resource = workbookPreviewResourceFor(request.url.toString(), request.isForMainFrame, assets, documentUrl) ?: return denied()
                if (resource == "@document") {
                    if (bytes.size > WorkbookMaxBytes || format !in setOf("xls", "xlsx")) return denied()
                    return WebResourceResponse("application/octet-stream", null, 200, "OK", mapOf(
                        "Cache-Control" to "no-store", "Content-Length" to bytes.size.toString(), "X-Content-Type-Options" to "nosniff",
                    ), ByteArrayInputStream(bytes))
                }
                val mime = when (resource.substringAfterLast('.')) { "html" -> "text/html"; "css" -> "text/css"; else -> "text/javascript" }
                return WebResourceResponse(mime, "UTF-8", 200, "OK", mapOf(
                    "Cache-Control" to "no-store", "X-Content-Type-Options" to "nosniff",
                ), context.assets.open("workbook-preview/$resource"))
            }
            override fun onPageFinished(view: WebView, url: String) {
                if (!destroyed && url == WorkbookUrl) { ready = true; flush() }
            }
        }
        loadUrl(WorkbookUrl)
    }

    fun update(nextOptions: String) { options = nextOptions; flush() }

    private fun flush() {
        if (!ready || destroyed) return
        val current = options ?: return
        if (sentOptions != current) { view.evaluateJavascript("window.workbookPreview.setOptions($current);", null); sentOptions = current }
        if (!sentDocument) {
            view.evaluateJavascript("window.workbookPreview.setDocument(${JSONObject.quote(documentUrl)},${JSONObject.quote(format)});", null)
            sentDocument = true
        }
    }

    fun destroy() {
        if (destroyed) return
        destroyed = true
        ready = false
        view.evaluateJavascript("window.workbookPreview?.dispose();", null)
        view.stopLoading()
        view.removeAllViews()
        view.destroy()
    }

    private fun denied() = WebResourceResponse("text/plain", "UTF-8", 403, "Forbidden", emptyMap(), ByteArrayInputStream(ByteArray(0)))
}
