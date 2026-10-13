package dev.dsh.pocket.app

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.io.ByteArrayInputStream
import java.security.MessageDigest

private fun bt(zh: String, en: String) = if (Pocket.prefs.language == AppLanguage.En) en else zh

private fun browserFailure(reason: BrowserAddressFailure) = when (reason) {
    BrowserAddressFailure.Empty -> bt("请输入地址。", "Enter an address.")
    BrowserAddressFailure.Invalid -> bt("这个地址无效或过长。", "That address is invalid or too long.")
    BrowserAddressFailure.Protocol -> bt("只支持 HTTP 和 HTTPS 地址；本地文件请使用文档预览。", "Only HTTP and HTTPS addresses are supported; use Document Preview for local files.")
    BrowserAddressFailure.Credentials -> bt("地址不能包含用户名或密码。", "Addresses cannot contain a username or password.")
    BrowserAddressFailure.ApplicationOrigin -> bt("不能在嵌入浏览器中打开 DSH 应用自身。", "The embedded browser cannot open the DSH application itself.")
}

/** Only a checkpoint, never a saved WebView, cookie, bridge credential or automatic navigation. */
private class BrowserCheckpoint(context: Context, sessionKey: String) {
    private val prefs = context.getSharedPreferences("browser_checkpoints", Context.MODE_PRIVATE)
    private val key = MessageDigest.getInstance("SHA-256").digest(sessionKey.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    val url: String get() = prefs.getString("$key.url", "").orEmpty()
    val title: String get() = prefs.getString("$key.title", "").orEmpty()
    fun save(url: String, title: String) {
        prefs.edit().putString("$key.url", url).putString("$key.title", title.take(512)).apply()
    }
}

private class BrowserPageState(private val checkpoint: BrowserCheckpoint, initialPolicy: BrowserPolicy) {
    @Volatile var policy = initialPolicy
    var address by mutableStateOf(checkpoint.url)
    var url by mutableStateOf(checkpoint.url)
    var title by mutableStateOf(checkpoint.title)
    var started by mutableStateOf(false)
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var canBack by mutableStateOf(false)
    var canForward by mutableStateOf(false)
    var webView: WebView? = null

    fun navigate(input: String) {
        when (val result = policy.parse(input)) {
            is BrowserAddressResult.Rejected -> error = browserFailure(result.reason)
            is BrowserAddressResult.Target -> {
                url = result.url
                address = result.url
                title = result.title
                error = null
                loading = true
                checkpoint.save(url, title)
                started = true
                webView?.loadUrl(url)
            }
        }
    }

    fun observe(view: WebView, observedUrl: String?) {
        val target = observedUrl?.let { policy.parse(it, allowShorthand = false) } as? BrowserAddressResult.Target ?: return
        url = target.url
        address = target.url
        canBack = view.canGoBack()
        canForward = view.canGoForward()
        checkpoint.save(url, title)
    }

    fun observeTitle(view: WebView, observedTitle: String?) {
        if (error != null || policy.parse(view.url.orEmpty(), allowShorthand = false) !is BrowserAddressResult.Target) return
        title = observedTitle?.take(512).orEmpty()
        checkpoint.save(url, title)
    }

    fun reload() {
        if (!started) navigate(url)
        else {
            val target = policy.parse(url, allowShorthand = false)
            if (target is BrowserAddressResult.Rejected) error = browserFailure(target.reason)
            else {
                error = null
                loading = true
                webView?.reload()
            }
        }
    }
}

@Composable
fun BrowserSheet(sessionKey: String, onDismiss: () -> Unit, onBack: (() -> Unit)? = null) {
    // A Host/session switch must dispose the old WebView, including its navigation stack and callbacks.
    key(sessionKey) { BrowserPanelContent(sessionKey, onDismiss, onBack) }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun BrowserPanelContent(sessionKey: String, onDismiss: () -> Unit, onBack: (() -> Unit)?) {
    val context = LocalContext.current
    val profiles by Pocket.profiles.collectAsState()
    val policy = remember(profiles) {
        BrowserPolicy(profiles.flatMap { profile ->
            val host = profile.host.let { if (':' in it && !it.startsWith('[')) "[$it]" else it }
            listOf("http://$host:${profile.port}", "https://$host:${profile.port}")
        })
    }
    val state = remember { BrowserPageState(BrowserCheckpoint(context.applicationContext, sessionKey), policy) }
    SideEffect { state.policy = policy }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, state) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> state.webView?.onResume()
                Lifecycle.Event.ON_PAUSE -> state.webView?.onPause()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    fun external() {
        when (val result = state.policy.parse(state.url, allowShorthand = false)) {
            is BrowserAddressResult.Rejected -> state.error = browserFailure(result.reason)
            is BrowserAddressResult.Target -> try {
                // No headers, extras, referrer or app authentication is forwarded to the browser.
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(result.url)).addCategory(Intent.CATEGORY_BROWSABLE))
            } catch (_: ActivityNotFoundException) {
                state.error = bt("未找到系统浏览器。", "No system browser is available.")
            } catch (_: SecurityException) {
                state.error = bt("系统不允许打开浏览器。", "The system did not allow opening a browser.")
            }
        }
    }
    RightPanelFrame(state.title.ifBlank { bt("浏览器", "Browser") }, onDismiss, onBack) {
        val keyboard = LocalSoftwareKeyboardController.current
        val focus = LocalFocusManager.current
        fun go() { focus.clearFocus(); keyboard?.hide(); state.navigate(state.address) }
        Column(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }.testTag("browserPanel")) {
            Row(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                IconButton(onClick = { state.error = null; state.webView?.goBack() }, enabled = state.canBack,
                    modifier = Modifier.size(32.dp).testTag("browserBack")) {
                    Icon(D.chevronLeft, bt("后退", "Back"), tint = if (state.canBack) T.Text2 else T.Text3.copy(alpha = 0.4f), modifier = Modifier.size(16.dp))
                }
                IconButton(onClick = { state.error = null; state.webView?.goForward() }, enabled = state.canForward,
                    modifier = Modifier.size(32.dp).testTag("browserForward")) {
                    Icon(D.chevronRight, bt("前进", "Forward"), tint = if (state.canForward) T.Text2 else T.Text3.copy(alpha = 0.4f), modifier = Modifier.size(16.dp))
                }
                IconButton(onClick = state::reload, enabled = state.url.isNotBlank(), modifier = Modifier.size(32.dp).testTag("browserReload")) {
                    Icon(D.refresh, bt("刷新", "Reload"), tint = T.Text2, modifier = Modifier.size(16.dp))
                }
                Row(Modifier.weight(1f).height(32.dp).background(T.Bg, T.R8).border(0.5.dp, T.Border, T.R8),
                    verticalAlignment = Alignment.CenterVertically) {
                    BasicTextField(
                        value = state.address,
                        onValueChange = { if (it.length <= MAX_BROWSER_URL_LENGTH) state.address = it },
                        modifier = Modifier.weight(1f).padding(start = 9.dp).testTag("browserAddress")
                            .semantics { contentDescription = bt("输入 HTTP(S) 地址", "Enter an HTTP(S) address") },
                        singleLine = true,
                        textStyle = LocalTextStyle.current.copy(color = T.Ink, fontSize = 12.sp),
                        cursorBrush = SolidColor(T.Accent),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { go() }),
                        decorationBox = { inner ->
                            Box {
                                if (state.address.isEmpty()) Text(bt("输入 HTTP(S) 地址", "Enter an HTTP(S) address"),
                                    color = T.Text3, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                inner()
                            }
                        },
                    )
                    IconButton(onClick = ::go, modifier = Modifier.size(32.dp).testTag("browserGo")) {
                        Icon(D.link, bt("前往", "Go"), tint = T.Text2, modifier = Modifier.size(16.dp))
                    }
                }
                IconButton(onClick = ::external, enabled = state.url.isNotBlank(), modifier = Modifier.size(32.dp).testTag("browserExternal")) {
                    Icon(D.rightUp, bt("在系统浏览器中打开", "Open in system browser"), tint = T.Text2, modifier = Modifier.size(14.dp))
                }
            }
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("browserLoading"), color = T.Accent)
            else HorizontalDivider(color = T.Border, thickness = 0.5.dp)
            state.error?.let { error ->
                Text(error, color = T.Err, fontSize = 12.sp, modifier = Modifier.fillMaxWidth().padding(12.dp).testTag("browserError"))
            }
            if (!state.started) {
                Column(Modifier.fillMaxWidth().weight(1f).padding(20.dp),
                    verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    if (state.url.isBlank()) {
                        Text(bt("输入 HTTP(S) 地址开始浏览", "Enter an HTTP(S) address to start browsing"), color = T.Text3, fontSize = 13.sp)
                    } else {
                        Text(bt("上次打开", "Previously opened"), color = T.Text3, fontSize = 12.sp)
                        Text(state.title, color = T.Ink, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp).testTag("browserTitle"))
                        Text(state.url, color = T.Text3, fontSize = 12.sp, modifier = Modifier.padding(vertical = 8.dp))
                        Button(onClick = { state.navigate(state.url) }, colors = PrimaryColors(), modifier = Modifier.testTag("browserRestore")) {
                            Text(bt("恢复页面", "Restore page"))
                        }
                    }
                }
            } else {
                AndroidView(
                    factory = { createBrowserWebView(it, state).also { view ->
                        state.webView = view
                        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) view.onPause()
                        val target = state.policy.parse(state.url, allowShorthand = false)
                        if (target is BrowserAddressResult.Target) view.loadUrl(target.url)
                        else if (target is BrowserAddressResult.Rejected) { state.loading = false; state.error = browserFailure(target.reason) }
                    } },
                    modifier = Modifier.fillMaxWidth().weight(1f).testTag("browserWebView"),
                    onReset = null,
                    onRelease = { view ->
                        state.webView = null
                        view.stopLoading()
                        view.onPause()
                        view.webChromeClient = null
                        view.webViewClient = WebViewClient()
                        view.removeAllViews()
                        view.destroy()
                    },
                )
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Suppress("DEPRECATION")
private fun createBrowserWebView(context: Context, state: BrowserPageState): WebView {
    WebView.setWebContentsDebuggingEnabled(false)
    return WebView(context).apply {
        contentDescription = bt("网页内容", "Web page content")
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
            builtInZoomControls = true
            displayZoomControls = false
        }
        // These legacy platform names are removed as defense in depth. No app JS interface is registered.
        removeJavascriptInterface("searchBoxJavaBridge_")
        removeJavascriptInterface("accessibility")
        removeJavascriptInterface("accessibilityTraversal")
        setDownloadListener { _, _, _, _, _ ->
            state.loading = false
            state.error = bt("不支持下载，请在系统浏览器中打开。", "Downloads are unavailable; open this page in the system browser.")
        }
        webChromeClient = object : WebChromeClient() {
            override fun onReceivedTitle(view: WebView, title: String?) { state.observeTitle(view, title) }
            override fun onPermissionRequest(request: PermissionRequest) { request.deny() }
            override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback) {
                callback.invoke(origin, false, false)
            }
            override fun onShowFileChooser(view: WebView?, callback: ValueCallback<Array<Uri>>?, params: FileChooserParams?): Boolean {
                callback?.onReceiveValue(null)
                state.error = bt("此浏览器不提供文件选择。", "File selection is unavailable in this browser.")
                return true
            }
            override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message?): Boolean {
                state.error = bt("已阻止新窗口，请在系统浏览器中打开。", "New windows are blocked; use the system browser.")
                return false
            }
        }
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val target = state.policy.parse(request.url.toString(), allowShorthand = false)
                if (target is BrowserAddressResult.Rejected) {
                    if (request.isForMainFrame) { state.loading = false; state.error = browserFailure(target.reason) }
                    return true
                }
                return false
            }
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                // Runs off the UI thread. Do not read/write Compose state here. Android only exposes
                // the first URL of subresource redirects; this is not a general network firewall.
                if (state.policy.parse(request.url.toString(), allowShorthand = false) is BrowserAddressResult.Target) return null
                return WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))
            }
            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                val target = state.policy.parse(url.orEmpty(), allowShorthand = false)
                if (target is BrowserAddressResult.Rejected) {
                    view.stopLoading()
                    state.loading = false
                    state.error = browserFailure(target.reason)
                    return
                }
                state.loading = true
                state.error = null
                state.title = (target as BrowserAddressResult.Target).title
                state.observe(view, url)
            }
            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) { state.observe(view, url) }
            override fun onPageFinished(view: WebView, url: String?) {
                state.loading = false
                state.observe(view, url)
                state.observeTitle(view, view.title)
            }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) {
                    state.loading = false
                    state.error = bt("网页加载失败：", "Page load failed: ") + error.description
                }
            }
            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                if (request.isForMainFrame) {
                    state.loading = false
                    state.error = bt("网页返回 HTTP ", "Page returned HTTP ") + response.statusCode
                }
            }
            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                handler.cancel()
                state.loading = false
                state.error = bt("TLS 证书验证失败，已阻止连接。", "TLS certificate validation failed; the connection was blocked.")
            }
        }
    }
}
