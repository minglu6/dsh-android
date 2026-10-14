package dev.dsh.pocket.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color as AndroidColor
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.Locale

private const val CodePreviewOrigin = "https://code-preview.invalid"
private const val CodePreviewAssetPrefix = "$CodePreviewOrigin/code-preview/"
private const val CodePreviewUrl = "$CodePreviewOrigin/code-preview/index.html"

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
    val context = LocalContext.current
    val renderer = remember(context) { CodePreviewRenderer(context) }
    val options = JSONObject()
        .put("wrap", wrap).put("plain", plain).put("dark", ThemeState.dark)
        .put("fontSize", Pocket.prefs.codeFontSize * LocalDensity.current.fontScale)
        .put("fontFamily", previewCodeFontFamily(Pocket.prefs.codeFontFamily))
        .put("foreground", T.Ink.previewCss()).put("muted", T.Text3.previewCss())
        .put("background", T.Surface.previewCss())
        .toString()
    DisposableEffect(renderer) { onDispose { renderer.destroy() } }
    AndroidView(
        modifier = modifier,
        factory = { renderer.view },
        update = { renderer.update(path, text, options) },
    )
}

@SuppressLint("SetJavaScriptEnabled")
private class CodePreviewRenderer(context: Context) {
    private val assets = context.assets.open("code-preview/manifest.json").bufferedReader().use { reader ->
        val names = JSONArray(reader.readText())
        buildSet { for (index in 0 until names.length()) add(names.getString(index)) }
    }
    private var ready = false
    private var destroyed = false
    private var path: String? = null
    private var text: String? = null
    private var options: String? = null
    private var sentPath: String? = null
    private var sentText: String? = null
    private var sentOptions: String? = null
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
        // There is deliberately no JavascriptInterface, navigation delegate or download handler.
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true
            @Deprecated("Legacy WebView callback")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean = true
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                if (request.method != "GET") return blockedResource()
                val asset = codePreviewAssetFor(request.url.toString(), request.isForMainFrame, assets)
                    ?: return blockedResource()
                val stream = if (asset == "@meslo") context.resources.openRawResource(R.font.meslolgl_dz_nerd_regular)
                    else context.assets.open("code-preview/$asset")
                val mime = when (asset.substringAfterLast('.')) {
                    "html" -> "text/html"
                    "js" -> "text/javascript"
                    "css" -> "text/css"
                    else -> "font/ttf"
                }
                return WebResourceResponse(mime, "UTF-8", 200, "OK", mapOf(
                    "Cache-Control" to "no-store",
                    "X-Content-Type-Options" to "nosniff",
                ), stream)
            }
            override fun onPageFinished(view: WebView, url: String) {
                if (!destroyed && url == CodePreviewUrl) {
                    ready = true
                    flush()
                }
            }
        }
        loadUrl(CodePreviewUrl)
    }

    fun update(nextPath: String, nextText: String, nextOptions: String) {
        path = nextPath
        text = nextText
        options = nextOptions
        flush()
    }

    private fun flush() {
        if (!ready || destroyed) return
        val currentPath = path ?: return
        val currentText = text ?: return
        val currentOptions = options ?: return
        // Theme/wrap/font changes only update CSS; do not retransmit/read/tokenize the source.
        if (sentOptions != currentOptions) {
            view.evaluateJavascript("window.codePreview.setOptions($currentOptions);", null)
            sentOptions = currentOptions
        }
        if (sentPath != currentPath || sentText != currentText) {
            view.evaluateJavascript(codePreviewDocumentScript(currentPath, currentText), null)
            sentPath = currentPath
            sentText = currentText
        }
    }

    fun destroy() {
        destroyed = true
        ready = false
        view.stopLoading()
        view.removeAllViews()
        view.destroy()
    }
}

private fun blockedResource() = WebResourceResponse(
    "text/plain", "UTF-8", 403, "Forbidden", emptyMap(), ByteArrayInputStream(ByteArray(0)),
)
