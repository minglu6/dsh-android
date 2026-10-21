package dev.dsh.pocket.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color as AndroidColor
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.Locale

private const val CodePreviewOrigin = "https://code-preview.invalid"
private const val CodePreviewAssetPrefix = "$CodePreviewOrigin/code-preview/"
private const val CodePreviewUrl = "$CodePreviewOrigin/code-preview/index.html"
private const val CodePreviewTimeoutMillis = 15_000L

/** Exact generated asset names only, not a URL prefix or arbitrary filesystem path. */
internal fun codePreviewAssetFor(url: String, mainFrame: Boolean, assets: Set<String>): String? {
    if (mainFrame) return "index.html".takeIf { url == CodePreviewUrl }
    if (url == "$CodePreviewOrigin/font/meslo.ttf") return "@meslo"
    if (!url.startsWith(CodePreviewAssetPrefix)) return null
    val name = url.removePrefix(CodePreviewAssetPrefix)
    return name.takeIf { it in assets }
}

/** JSON is an evaluateJavascript argument, never an HTML/template/script element. */
internal fun codePreviewDocumentScript(path: String, text: String): String =
    "window.codePreview.setDocument(" + JSONObject().put("path", path).put("text", text)
        .toString().replace("\u2028", "\\u2028").replace("\u2029", "\\u2029") + ");"

private fun Color.previewCss(): String = String.format(Locale.ROOT, "#%06x", toArgb() and 0xffffff)

/** Resolve through the same installed-font policy/cache as all Compose code surfaces. */
internal fun previewCodeFontFamily(value: String): String {
    val family = LocalFonts.resolve(value, true)
    return when (family) {
        MesloNerdFamily -> "\"DSH Meslo\", monospace"
        FontFamily.Monospace -> "monospace"
        FontFamily.SansSerif -> "sans-serif"
        FontFamily.Serif -> "serif"
        FontFamily.Cursive -> "cursive"
        else -> {
            val name = value.split(',').firstOrNull { LocalFonts.resolve(it, true) == family }
            normalizeFontFamily(name.orEmpty()) + ", \"DSH Meslo\", monospace"
        }
    }
}

/** Native chrome remains Compose; only the offline, inert source body is WebView. */
@Composable
internal fun CodePreviewBody(path: String, text: String, wrap: Boolean, plain: Boolean, modifier: Modifier) {
    val document = remember(path, text) { codePreviewDocumentScript(path, text) }
    SharedCodePreviewBody(document, wrap, plain, modifier)
}

@Composable
internal fun SharedCodePreviewBody(documentScript: String, wrap: Boolean, plain: Boolean, modifier: Modifier) {
    val context = LocalContext.current
    var retry by remember { mutableStateOf(0) }
    val renderer = remember(context, retry) { CodePreviewRenderer(context) }
    val options = JSONObject()
        .put("wrap", wrap).put("plain", plain).put("dark", ThemeState.dark)
        .put("language", if (Pocket.prefs.language == AppLanguage.En) "en" else "zh")
        .put("fontSize", Pocket.prefs.codeFontSize * LocalDensity.current.fontScale)
        .put("fontFamily", previewCodeFontFamily(Pocket.prefs.codeFontFamily))
        .put("foreground", T.Ink.previewCss()).put("muted", T.Text3.previewCss())
        .put("background", T.Surface.previewCss())
        .toString()
    DisposableEffect(renderer) { onDispose { renderer.destroy() } }
    Box(modifier) {
        key(renderer) {
            AndroidView(
                modifier = Modifier.fillMaxSize().testTag("codePreviewViewport"),
                factory = { renderer.view },
                update = { renderer.update(documentScript, options) },
            )
        }
        val message = renderer.error
        if (message != null) {
            Column(Modifier.fillMaxSize().background(T.Surface).padding(16.dp).testTag("codePreviewError")) {
                SelectableOutput { Text(message, color = T.Err) }
                TextButton(onClick = { retry++ }, modifier = Modifier.testTag("codePreviewRetry")) { Text(L.docRetry()) }
            }
        } else if (renderer.loading) {
            Box(Modifier.fillMaxSize().background(T.Surface), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = T.Accent)
            }
        }
    }
}

/** One renderer and one asset/security policy for live source and turn snapshot comparisons. */
@SuppressLint("SetJavaScriptEnabled")
internal class CodePreviewRenderer(private val context: Context) {
    var error by mutableStateOf<String?>(null)
        private set
    var loading by mutableStateOf(true)
        private set
    private val handler = Handler(Looper.getMainLooper())
    private val assets = runCatching {
        context.assets.open("code-preview/manifest.json").bufferedReader().use { reader ->
            val names = JSONArray(reader.readText())
            buildSet { for (index in 0 until names.length()) add(names.getString(index)) }
        }
    }.getOrElse { error = "Code preview: ${it.message}"; emptySet() }
    private var ready = false
    private var pageFinished = false
    private var destroyed = false
    private var generation = 0
    private var deadline = 0L
    private var inFlight = false
    private var document: String? = null
    private var options: String? = null
    private var sentDocument: String? = null
    private var sentOptions: String? = null
    private val poll = Runnable { inspect() }
    val view = WebView(context).apply {
        setBackgroundColor(AndroidColor.TRANSPARENT)
        isVerticalScrollBarEnabled = true
        isHorizontalScrollBarEnabled = true
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
            builtInZoomControls = false
            displayZoomControls = false
            textZoom = 100
        }
        // Deliberately no JavascriptInterface, external navigation or download handler.
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true
            @Deprecated("Legacy WebView callback")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean = true
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                if (request.method != "GET") return blockedResource()
                val asset = codePreviewAssetFor(request.url.toString(), request.isForMainFrame, assets)
                    ?: return blockedResource()
                return runCatching {
                    val stream = if (asset == "@meslo") context.resources.openRawResource(R.font.meslolgl_dz_nerd_regular)
                        else context.assets.open("code-preview/$asset")
                    val mime = when (asset.substringAfterLast('.')) {
                        "html" -> "text/html"
                        "js" -> "text/javascript"
                        "css" -> "text/css"
                        else -> "font/ttf"
                    }
                    WebResourceResponse(mime, "UTF-8", 200, "OK", mapOf(
                        "Cache-Control" to "no-store", "X-Content-Type-Options" to "nosniff",
                    ), stream)
                }.getOrElse {
                    handler.post { fail("Code preview asset $asset: ${it.message}") }
                    blockedResource()
                }
            }
            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                pageFinished = false
            }
            override fun onPageFinished(view: WebView, url: String) {
                // Page completion is not module readiness: the classic bridge acknowledges imports.
                if (url == CodePreviewUrl) {
                    pageFinished = true
                    scheduleInspection(0)
                }
            }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                fail("Code preview: ${error.description}")
            }
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                fail("Code preview process terminated.")
                return true
            }
        }
    }

    init { if (error == null) reload() }

    fun update(nextDocument: String, nextOptions: String) {
        if (document != nextDocument && error == null) loading = true
        document = nextDocument
        options = nextOptions
        flush()
    }

    fun reload() {
        if (destroyed) return
        generation++
        ready = false
        pageFinished = false
        inFlight = false
        error = null
        loading = true
        sentDocument = null
        sentOptions = null
        deadline = SystemClock.uptimeMillis() + CodePreviewTimeoutMillis
        handler.removeCallbacks(poll)
        view.stopLoading()
        view.loadUrl(CodePreviewUrl)
        scheduleInspection(100)
    }

    private fun fail(message: String) {
        if (destroyed || error != null) return
        ready = false
        error = message
        loading = false
        handler.removeCallbacks(poll)
    }

    private fun scheduleInspection(delay: Long) {
        handler.removeCallbacks(poll)
        if (!destroyed && error == null) handler.postDelayed(poll, delay)
    }

    private fun inspect() {
        if (destroyed || error != null || inFlight) return
        // Never acknowledge a previous document while reload navigation is still in flight.
        if (!pageFinished) {
            if (SystemClock.uptimeMillis() >= deadline) fail("Code preview initialization timed out.")
            else scheduleInspection(100)
            return
        }
        val ownGeneration = generation
        view.evaluateJavascript("window.codePreviewBridge ? window.codePreviewBridge.status() : null") { result ->
            if (destroyed || generation != ownGeneration || error != null) return@evaluateJavascript
            val status = runCatching { JSONObject(result) }.getOrNull()
            if (status?.optString("state") == "error") {
                fail(status.optString("error", "Code preview JavaScript failed."))
                return@evaluateJavascript
            }
            ready = status?.optBoolean("ready") == true
            if (ready && (sentDocument != document || sentOptions != options)) {
                flush()
                return@evaluateJavascript
            }
            val finished = ready && status?.optString("state") == "ready"
            loading = !finished
            if (!finished && SystemClock.uptimeMillis() >= deadline) {
                fail(if (ready) "Code preview highlighting timed out." else "Code preview initialization timed out.")
            } else scheduleInspection(if (finished) 1000 else 100)
        }
    }

    private fun flush() {
        if (!ready || destroyed || error != null || inFlight) return
        val currentDocument = document ?: return
        val currentOptions = options ?: return
        if (sentDocument == currentDocument && sentOptions == currentOptions) return
        // CSS-only changes never retransmit or re-tokenize source. A source change is acknowledged
        // after actual module readiness, not optimistically at onPageFinished.
        val changedDocument = sentDocument != currentDocument
        val source = if (changedDocument) "() => { $currentDocument }" else "null"
        val script = "window.codePreviewBridge.apply($currentOptions, $source);"
        inFlight = true
        if (changedDocument) loading = true
        deadline = SystemClock.uptimeMillis() + CodePreviewTimeoutMillis
        val ownGeneration = generation
        view.evaluateJavascript(script) { result ->
            if (destroyed || generation != ownGeneration || error != null) return@evaluateJavascript
            inFlight = false
            val status = runCatching { JSONObject(result) }.getOrNull()
            if (status?.optBoolean("ready") != true || status.optString("state") == "error") {
                fail(status?.optString("error")?.takeIf { it.isNotEmpty() } ?: "Code preview did not acknowledge the source document.")
                return@evaluateJavascript
            }
            sentDocument = currentDocument
            sentOptions = currentOptions
            loading = status.optString("state") != "ready"
            flush()
            scheduleInspection(0)
        }
    }

    fun destroy() {
        destroyed = true
        generation++
        ready = false
        handler.removeCallbacks(poll)
        view.stopLoading()
        view.removeAllViews()
        view.destroy()
    }
}

private fun blockedResource() = WebResourceResponse(
    "text/plain", "UTF-8", 403, "Forbidden", emptyMap(), ByteArrayInputStream(ByteArray(0)),
)
