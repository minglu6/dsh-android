package dev.dsh.pocket.app

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.view.ViewGroup
import android.webkit.*
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.dsh.pocket.protocol.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import java.io.ByteArrayInputStream
import kotlin.coroutines.resume

/** Runs the Host's installed Web client in a separate surface, never in the ordinary browsing panel. */
@Composable
internal fun HostUiScreen(title: String, section: String? = null, onBack: () -> Unit) {
    val active by Pocket.active.collectAsState()
    val profiles by Pocket.profiles.collectAsState()
    val statuses by Pocket.status.collectAsState()
    val ops by Pocket.ops.collectAsState()
    val available = "ui.open" in ops
    val profile = profiles.firstOrNull { it.id == active }
    val online = profile != null && statuses[profile.id] == Dot.Online
    var attempt by remember(active) { mutableIntStateOf(0) }
    var session by remember(active) { mutableStateOf<HostUiSession?>(null) }
    var error by remember(active) { mutableStateOf<String?>(null) }
    var loading by remember(active) { mutableStateOf(true) }
    var view by remember(active) { mutableStateOf<WebView?>(null) }
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    BackHandler(onBack = onBack)
    LaunchedEffect(active, profile?.host, profile?.port, online, available, attempt, section) {
        session = null; error = null; loading = true
        val client = Pocket.clientOrNull()
        if (!online || profile == null || client == null) {
            error = st("主机未连接，请连接后重试。", "Host disconnected; reconnect and retry.")
            loading = false
            return@LaunchedEffect
        }
        if ("ui.open" !in ops) {
            error = st("当前主机未提供插件 Web 界面，请更新并重新加载桌面端手机连接插件。", "This Host does not expose plugin Web interfaces. Update and reload its phone-connection plugin.")
            loading = false
            return@LaunchedEffect
        }
        var ticket: String? = null
        var opened: HostUiSession? = null
        try {
            val grant = client.call("ui.open", jsonOf("section" to section))
            ticket = grant["ticket"].str() ?: error("Missing UI ticket")
            val origin = HttpUrl.Builder().scheme("http").host(profile.host.removeSurrounding("[", "]")).port(profile.port).build()
            val exchanged = exchangeHostUiTicket(origin, grant["path"].str().orEmpty(), ticket)
            opened = exchanged
            // Wait for Chromium's cookie store before loading the authenticated document.
            val accepted = suspendCancellableCoroutine { continuation ->
                CookieManager.getInstance().setCookie(origin.toString(), exchanged.cookieHeader) { stored ->
                    if (continuation.isActive) continuation.resume(stored)
                }
            }
            check(accepted) { "Web session cookie rejected" }
            session = opened
            awaitCancellation()
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) {
            // Authentication errors must not echo tickets, cookies or upstream URLs.
            error = st("插件界面加载失败，请确认主机在线并重试。", "Plugin interface could not load. Check the Host connection and retry.")
            loading = false
        } finally {
            val previous = opened
            if (previous != null) {
                val cookies = CookieManager.getInstance()
                val present = cookies.getCookie(previous.origin.toString()).orEmpty().split(';').map(String::trim)
                if ("${previous.cookie.name}=${previous.cookie.value}" in present) {
                    cookies.setCookie(previous.origin.toString(), "dsh-pocket-web=; Path=/; Max-Age=0; HttpOnly; SameSite=Strict")
                }
            }
            if (ticket != null) withContext(NonCancellable) {
                runCatching { client.call("ui.close", jsonOf("ticket" to ticket), timeoutMs = 5_000) }
            }
        }
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> view?.onResume()
                Lifecycle.Event.ON_PAUSE -> view?.onPause()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    Column(Modifier.fillMaxSize().background(T.Surface2).systemBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onBack) { Icon(D.chevronLeft, st("返回", "Back"), tint = T.Ink) }
            Text(title, Modifier.weight(1f), color = T.Ink, fontSize = 18.sp)
            IconButton({ attempt++ }, enabled = online) { Icon(D.refresh, st("重新加载", "Reload"), tint = T.Ink) }
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let {
            Text(it, color = T.Err, fontSize = 13.sp, modifier = Modifier.padding(18.dp))
            TextButton({ attempt++ }, enabled = online) { Text(L.retry()) }
        }
        val loaded = session
        if (loaded != null) key(loaded) {
            AndroidView(
                factory = {
                    createHostUiWebView(context, HostUiPolicy(loaded.origin),
                        onLoading = { loading = it },
                        onFailure = { error = it; loading = false },
                    ).also { created -> view = created; created.loadUrl(loaded.startUrl.toString()) }
                },
                modifier = Modifier.fillMaxWidth().weight(1f),
                onRelease = { released ->
                    if (view === released) view = null
                    released.stopLoading(); released.webChromeClient = null
                    released.loadUrl("about:blank"); released.clearHistory(); released.removeAllViews(); released.destroy()
                },
            )
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Suppress("DEPRECATION")
private fun createHostUiWebView(
    context: android.content.Context, policy: HostUiPolicy,
    onLoading: (Boolean) -> Unit, onFailure: (String) -> Unit,
): WebView = WebView(context).apply {
    // WRAP_CONTENT makes Chromium's viewport units resolve to zero in AndroidView.
    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    contentDescription = st("主机插件界面", "Host plugin interface")
    settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true
        allowFileAccess = false
        allowContentAccess = false
        allowFileAccessFromFileURLs = false
        allowUniversalAccessFromFileURLs = false
        javaScriptCanOpenWindowsAutomatically = false
        setSupportMultipleWindows(true)
        setGeolocationEnabled(false)
        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        mediaPlaybackRequiresUserGesture = true
        safeBrowsingEnabled = true
        cacheMode = WebSettings.LOAD_NO_CACHE
    }
    CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
    removeJavascriptInterface("searchBoxJavaBridge_")
    removeJavascriptInterface("accessibility")
    removeJavascriptInterface("accessibilityTraversal")
    setDownloadListener { _, _, _, _, _ -> onFailure(st("插件界面不能直接下载到设备。", "Plugin interfaces cannot download directly to this device.")) }
    webChromeClient = object : WebChromeClient() {
        override fun onPermissionRequest(request: PermissionRequest) = request.deny()
        override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback) { callback.invoke(origin, false, false) }
        override fun onShowFileChooser(webView: WebView?, callback: ValueCallback<Array<Uri>>?, params: FileChooserParams?): Boolean {
            callback?.onReceiveValue(null)
            onFailure(st("此插件界面尚不提供设备文件选择。", "Device file selection is unavailable in this plugin interface."))
            return true
        }
        override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message?): Boolean {
            onFailure(st("已阻止插件打开外部窗口。", "The plugin's external window was blocked."))
            return false
        }
    }
    webViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            if (policy.allows(request.url.toString())) return false
            onFailure(st("插件界面只能访问当前主机，已阻止外部跳转。", "Plugin interfaces can only access the current Host; external navigation was blocked."))
            return true
        }
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            if (policy.allows(request.url.toString())) return null
            return WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))
        }
        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            if (!policy.allows(url.orEmpty())) { view.stopLoading(); return }
            onLoading(true)
        }
        override fun onPageFinished(view: WebView, url: String?) { onLoading(false) }
        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (request.isForMainFrame) onFailure(st("主机插件界面连接失败，请重新加载。", "Host plugin interface connection failed; reload to retry."))
        }
        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
            if (request.isForMainFrame) onFailure(st("插件界面会话不可用，请重新加载。", "Plugin interface session unavailable; reload to retry."))
        }
        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
            handler.cancel()
            onFailure(st("主机证书验证失败，连接已阻止。", "Host certificate verification failed; connection blocked."))
        }
    }
}
