package dev.dsh.pocket.app

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.webkit.WebResourceRequest
import android.webkit.WebView
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BrowserTabsTest {
    private lateinit var context: Context
    private val owners = mutableListOf<BrowserTabs>()
    private val policy = BrowserPolicy(listOf("https://host.test:443"))

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        Pocket.init(context)
        context.getSharedPreferences("browser_checkpoints", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After fun tearDown() { owners.forEach { it.dispose() } }

    private fun newTabs(key: String = "host:session") = BrowserTabs(context, key, policy).also { owners.add(it) }

    @Test fun switchingTabsRetainsEachNativeViewAndItsBackForwardHistory() {
        val tabs = newTabs()
        val first = tabs.selected
        first.navigate("https://one.test/start")
        val firstView = tabs.viewFor(first)
        shadowOf(firstView).pushEntryToHistory("https://one.test/start")
        shadowOf(firstView).pushEntryToHistory("https://one.test/next")
        first.observe(firstView, "https://one.test/next")
        val second = tabs.add()
        second.navigate("https://two.test/start")
        val secondView = tabs.viewFor(second)
        shadowOf(secondView).pushEntryToHistory("https://two.test/start")
        shadowOf(secondView).pushEntryToHistory("https://two.test/next")
        second.observe(secondView, "https://two.test/next")

        tabs.select(first)
        assertSame(firstView, tabs.viewFor(first))
        assertTrue(firstView.canGoBack())
        firstView.goBack()
        assertEquals("https://one.test/start", firstView.url)
        assertTrue(firstView.canGoForward())
        tabs.select(second)
        assertSame(secondView, tabs.viewFor(second))
        assertEquals("https://two.test/next", second.address)
        assertTrue(secondView.canGoBack())
        tabs.select(first)
        assertTrue(firstView.canGoForward())
        assertFalse(shadowOf(firstView).wasDestroyCalled())
    }

    @Test fun userGestureCreatesASeparateTransportAndUnsolicitedPopupDoesNot() {
        val tabs = newTabs()
        val first = tabs.selected
        val parent = tabs.viewFor(first)
        val chrome = shadowOf(parent).webChromeClient
        val denied = Message.obtain(Handler(Looper.getMainLooper())).apply { obj = parent.WebViewTransport() }
        assertFalse(chrome.onCreateWindow(parent, false, false, denied))
        assertEquals(1, tabs.pages.size)
        assertFalse(chrome.onCreateWindow(parent, false, true, null))
        assertEquals(1, tabs.pages.size)

        val transport = parent.WebViewTransport()
        val requested = Message.obtain(Handler(Looper.getMainLooper())).apply { obj = transport }
        assertTrue(chrome.onCreateWindow(parent, false, true, requested))
        assertEquals(2, tabs.pages.size)
        assertNotSame(first, tabs.selected)
        assertSame(tabs.selected.webView, transport.webView)
        assertNotSame(parent, transport.webView)
        val child = requireNotNull(transport.webView)
        assertTrue(child.settings.javaScriptEnabled)
        assertFalse(child.settings.javaScriptCanOpenWindowsAutomatically)
        assertFalse(child.settings.allowFileAccess)
        assertFalse(child.settings.allowContentAccess)
        assertFalse(shadowOf(child).webViewClient.shouldOverrideUrlLoading(child, request("https://two.test/page")))
        child.loadUrl("https://two.test/page")
        assertEquals("https://two.test/page", shadowOf(child).lastLoadedUrl)
        assertTrue(shadowOf(child).lastAdditionalHttpHeaders.isNullOrEmpty())
    }

    @Test fun popupChildrenStillBlockHostCredentialsAndNonHttpSchemes() {
        val tabs = newTabs()
        val child = requireNotNull(tabs.openWindow(tabs.selected))
        val client = shadowOf(child).webViewClient
        listOf("https://host.test/settings", "https://user:password@public.test/", "file:///private", "javascript:alert(1)", "intent://app")
            .forEach { url ->
                assertTrue(url, client.shouldOverrideUrlLoading(child, request(url)))
                assertEquals(403, client.shouldInterceptRequest(child, request(url))?.statusCode)
            }
        assertNull(client.shouldInterceptRequest(child, request("https://public.test/image.png")))
        tabs.updatePolicy(BrowserPolicy(listOf("https://new-host.test")))
        assertTrue(client.shouldOverrideUrlLoading(child, request("https://new-host.test/private")))
    }

    @Test fun closingSelectedAndLastTabsDestroysViewsAndLeavesAnEmptyUsableTab() {
        val tabs = newTabs()
        val first = tabs.selected
        val firstView = tabs.viewFor(first)
        val second = tabs.add()
        val secondView = tabs.viewFor(second)
        tabs.close(second)
        assertSame(first, tabs.selected)
        assertNull(second.webView)
        assertTrue(shadowOf(secondView).wasDestroyCalled())
        assertFalse(shadowOf(firstView).wasDestroyCalled())
        tabs.close(first)
        assertTrue(shadowOf(firstView).wasDestroyCalled())
        assertEquals(1, tabs.pages.size)
        assertEquals("", tabs.selected.url)
        assertFalse(tabs.selected.started)
        assertNull(tabs.selected.webView)
        tabs.selected.navigate("https://next.test/")
        assertEquals("https://next.test/", shadowOf(tabs.viewFor(tabs.selected)).lastLoadedUrl)
    }

    @Test fun scriptClosingAChildReleasesOnlyItsTab() {
        val tabs = newTabs()
        val original = tabs.selected
        val child = requireNotNull(tabs.openWindow(original))
        shadowOf(child).webChromeClient.onCloseWindow(child)
        assertSame(original, tabs.selected)
        assertEquals(1, tabs.pages.size)
        assertTrue(shadowOf(child).wasDestroyCalled())
    }

    @Test fun recreationRetainsAllAddressesAndSelectionWithoutStartingAnyPage() {
        val tabs = newTabs()
        val first = tabs.selected
        first.navigate("https://one.test/path")
        val firstView = tabs.viewFor(first)
        val second = tabs.add()
        second.navigate("https://two.test/path")
        val secondView = tabs.viewFor(second)
        tabs.select(first)
        tabs.dispose()
        assertTrue(shadowOf(firstView).wasDestroyCalled())
        assertTrue(shadowOf(secondView).wasDestroyCalled())
        val restored = newTabs()
        assertEquals(listOf("https://one.test/path", "https://two.test/path"), restored.pages.map { it.url })
        assertEquals(first.id, restored.selected.id)
        assertTrue(restored.pages.none { it.started || it.webView != null })
        assertEquals("", newTabs("other-host:session").selected.url)
        assertEquals("", newTabs("host:other-session").selected.url)
    }

    @Test fun disposalIsIdempotentAndRejectsLatePopupCallbacks() {
        val tabs = newTabs()
        val first = tabs.selected
        val view = tabs.viewFor(first)
        tabs.dispose()
        tabs.dispose()
        assertNull(tabs.openWindow(first))
        assertNull(first.webView)
        assertTrue(shadowOf(view).wasDestroyCalled())
    }

    @Test fun closingABackgroundTabDoesNotChangeSelectionOrDestroyCurrentView() {
        val tabs = newTabs()
        val first = tabs.selected
        val firstView = tabs.viewFor(first)
        val second = tabs.add()
        val secondView = tabs.viewFor(second)
        tabs.close(first)
        assertSame(second, tabs.selected)
        assertTrue(shadowOf(firstView).wasDestroyCalled())
        assertFalse(shadowOf(secondView).wasDestroyCalled())
    }

    @Test fun switchingPausesTheOldViewAndBackgroundingPausesTheCurrentView() {
        val tabs = newTabs()
        tabs.setResumed(true)
        val first = tabs.selected
        val firstView = tabs.viewFor(first)
        val second = tabs.add()
        val secondView = tabs.viewFor(second)
        assertTrue(shadowOf(firstView).wasOnPauseCalled())
        tabs.select(first)
        assertTrue(shadowOf(secondView).wasOnPauseCalled())
        assertTrue(shadowOf(firstView).wasOnResumeCalled())
        tabs.setResumed(false)
        assertTrue(shadowOf(firstView).wasOnPauseCalled())
        assertFalse(shadowOf(firstView).wasDestroyCalled())
    }

    private fun request(value: String) = object : WebResourceRequest {
        override fun getUrl(): Uri = Uri.parse(value)
        override fun isForMainFrame() = true
        override fun isRedirect() = false
        override fun hasGesture() = true
        override fun getMethod() = "GET"
        override fun getRequestHeaders(): MutableMap<String, String> = mutableMapOf()
    }
}
