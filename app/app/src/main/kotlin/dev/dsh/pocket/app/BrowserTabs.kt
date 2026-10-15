package dev.dsh.pocket.app

import android.content.Context
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

/** Checkpoints contain addresses only: recreation never restores a WebView or starts network traffic. */
internal class BrowserTabs(private val context: Context, sessionKey: String, private var policy: BrowserPolicy) {
    private val prefs = context.applicationContext.getSharedPreferences("browser_checkpoints", Context.MODE_PRIVATE)
    private val key = MessageDigest.getInstance("SHA-256").digest(sessionKey.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    private val mutablePages = mutableStateListOf<BrowserPageState>()
    val pages: List<BrowserPageState> get() = mutablePages
    var selected by mutableStateOf<BrowserPageState>(newPage())
        private set
    private var resumed = false
    private var disposed = false

    init {
        val saved = runCatching { JSONObject(prefs.getString("$key.tabs", "").orEmpty()) }.getOrNull()
        val entries = saved?.optJSONArray("pages")
        if (entries != null) {
            for (index in 0 until entries.length()) {
                val entry = entries.optJSONObject(index) ?: continue
                val id = entry.optString("id").takeIf { it.isNotBlank() && mutablePages.none { page -> page.id == it } } ?: continue
                val url = entry.optString("url").take(MAX_BROWSER_URL_LENGTH)
                mutablePages.add(newPage(id, url, entry.optString("title").take(512)))
            }
        } else {
            // Migrate the former single-page address checkpoint without loading it.
            mutablePages.add(newPage(url = prefs.getString("$key.url", "").orEmpty(), title = prefs.getString("$key.title", "").orEmpty()))
        }
        if (mutablePages.isEmpty()) mutablePages.add(selected)
        selected = mutablePages.firstOrNull { it.id == saved?.optString("selected") } ?: mutablePages.first()
    }

    private fun newPage(id: String = UUID.randomUUID().toString(), url: String = "", title: String = "") =
        BrowserPageState(id, url, title, policy, ::checkpoint)

    private fun checkpoint() {
        if (disposed) return
        val entries = JSONArray()
        pages.forEach { page -> entries.put(JSONObject().put("id", page.id).put("url", page.url).put("title", page.title.take(512))) }
        prefs.edit().putString("$key.tabs", JSONObject().put("selected", selected.id).put("pages", entries).toString())
            .remove("$key.url").remove("$key.title").apply()
    }

    fun updatePolicy(value: BrowserPolicy) {
        policy = value
        pages.forEach { it.policy = value }
    }

    fun add(): BrowserPageState {
        check(!disposed)
        val page = newPage()
        mutablePages.add(page)
        select(page)
        return page
    }

    fun select(page: BrowserPageState) {
        if (disposed || page !in pages) return
        selected.webView?.onPause()
        selected = page
        if (resumed) page.webView?.onResume()
        checkpoint()
    }

    fun setResumed(value: Boolean) {
        resumed = value
        pages.forEach { page ->
            if (value && page === selected) page.webView?.onResume() else page.webView?.onPause()
        }
    }

    /** A tab retains its native history when its viewport is detached. */
    fun viewFor(page: BrowserPageState): WebView {
        check(!disposed && page in pages)
        return page.webView ?: createBrowserWebView(context, page, this).also { view ->
            page.webView = view
            if (!resumed || page !== selected) view.onPause()
            if (page.started && page.url.isNotBlank()) {
                when (val target = policy.parse(page.url, allowShorthand = false)) {
                    is BrowserAddressResult.Target -> view.loadUrl(target.url)
                    is BrowserAddressResult.Rejected -> { page.loading = false; page.error = browserFailure(target.reason) }
                }
            }
        }
    }

    /** Only the WebChromeClient's user-gesture branch may call this; every child has the same policy. */
    fun openWindow(source: BrowserPageState): WebView? {
        if (disposed || source !in pages) return null
        val page = add()
        page.started = true
        return viewFor(page)
    }

    fun closeWindow(view: WebView) { pages.firstOrNull { it.webView === view }?.let(::close) }

    fun close(page: BrowserPageState) {
        if (disposed) return
        val index = mutablePages.indexOf(page)
        if (index < 0) return
        mutablePages.removeAt(index)
        release(page)
        if (mutablePages.isEmpty()) mutablePages.add(newPage())
        if (selected === page) select(mutablePages[index.coerceAtMost(mutablePages.lastIndex)])
        else checkpoint()
    }

    fun dispose() {
        if (disposed) return
        checkpoint()
        disposed = true
        pages.forEach(::release)
    }

    private fun release(page: BrowserPageState) {
        val view = page.webView ?: return
        page.webView = null
        view.stopLoading()
        view.onPause()
        (view.parent as? ViewGroup)?.removeView(view)
        view.webChromeClient = null
        view.webViewClient = WebViewClient()
        view.removeAllViews()
        view.destroy()
    }
}
