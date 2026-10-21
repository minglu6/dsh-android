package dev.dsh.pocket.app

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.dsh.pocket.protocol.WorkspaceDiffHunk
import dev.dsh.pocket.protocol.WorkspaceFileDiff
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Exercises generated offline assets, actual Shiki tokens, CSS and Android WebView startup. */
@RunWith(AndroidJUnit4::class)
class CodePreviewInstrumentedTest {
    @get:Rule val rule = createComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var renderer: CodePreviewRenderer
    private val options get() = JSONObject().put("wrap", true).put("plain", false).put("dark", false)
        .put("fontSize", 11).put("fontFamily", "\"DSH Meslo\", monospace")
        .put("foreground", "#111111").put("muted", "#888888").put("background", "#ffffff")

    private fun mount(script: String) {
        instrumentation.runOnMainSync {
            renderer = CodePreviewRenderer(instrumentation.targetContext)
            renderer.update(script, options.toString())
        }
        rule.setContent { AndroidView(factory = { renderer.view }, modifier = Modifier.fillMaxSize()) }
    }

    private fun update(script: String, nextOptions: JSONObject = options) {
        instrumentation.runOnMainSync { renderer.update(script, nextOptions.toString()) }
    }

    private fun evaluate(script: String): JSONObject {
        val completed = CountDownLatch(1)
        var result = "null"
        instrumentation.runOnMainSync {
            renderer.view.evaluateJavascript(script) { result = it; completed.countDown() }
        }
        assertTrue("WebView JavaScript callback", completed.await(5, TimeUnit.SECONDS))
        return JSONObject(result)
    }

    private fun ready() {
        rule.waitUntil(20_000) {
            var nativeReady = false
            instrumentation.runOnMainSync {
                renderer.error?.let { throw AssertionError(it) }
                nativeReady = !renderer.loading
            }
            nativeReady && evaluate("""
                ({state:window.codePreviewBridge?.status().state,
                  selectedFontLoaded:document.fonts.check(getComputedStyle(document.body).font)})
            """.trimIndent()).let {
                if (it.optString("state") != "ready") false
                else {
                    assertTrue("Native readiness must include the selected code font", it.getBoolean("selectedFontLoaded"))
                    true
                }
            }
        }
        instrumentation.runOnMainSync { assertNull(renderer.error) }
    }

    private fun snapshot() = evaluate("""
        (() => {
          const rows = [...document.querySelectorAll('[data-diff-line]')];
          const spans = [...document.querySelectorAll('#code span[style]')];
          const numberToken = spans.find(span => span.textContent.trim() === '42');
          const lastToken = rows.length ? rows[rows.length - 1].querySelector('[style]') : null;
          const first = document.querySelector('#code .line, #code .comparison-line');
          const firstStyle = first ? getComputedStyle(first) : null;
          const marker = first?.querySelector('.comparison-text');
          const pixel = value => Math.round(value * devicePixelRatio);
          const lineRect = first?.getBoundingClientRect();
          const gutter = first ? getComputedStyle(first, '::before') : null;
          const textRange = document.createRange();
          if (first) textRange.selectNodeContents(marker ?? first);
          const textRect = first ? textRange.getClientRects()[0] : null;
          return {
            ...window.codePreviewBridge.status(), mode: document.body.dataset.mode,
            oneSided: document.body.dataset.oneSided, wrap: document.body.dataset.wrap,
            dark: document.body.hasAttribute('data-ds-dark-theme'),
            numberColor: numberToken ? getComputedStyle(numberToken).color : '',
            rows: rows.map(row => ({old: row.dataset.old, new: row.dataset.new, kind: row.dataset.diffLine,
              text: row.textContent, heightPixel: pixel(row.getBoundingClientRect().height)})),
            lastColor: lastToken ? getComputedStyle(lastToken).color : '',
            secondGutter: rows.length ? getComputedStyle(rows[0], '::after').content : '',
            lineTopPixel: lineRect ? pixel(lineRect.top) : -1,
            textLeftPixel: textRect ? pixel(textRect.left) : -1,
            lineHeightPixel: lineRect ? pixel(lineRect.height) : -1,
            textLineHeightPixel: firstStyle ? pixel(parseFloat(firstStyle.lineHeight)) : -1,
            gutterVisible: gutter?.content !== 'none' && parseFloat(gutter?.width) > 0,
            textClearsGutter: !!textRect && textRect.left > lineRect.left + parseFloat(gutter.insetInlineStart) + parseFloat(gutter.width),
            lineBackground: firstStyle?.backgroundColor,
            marker: marker ? getComputedStyle(marker, '::before').content : 'none',
            text: document.getElementById('code').textContent,
            truncated: !!document.querySelector('[data-diff-note="truncated"]'),
            unsafeElements: document.querySelectorAll('#code img, #code script, #code iframe').length,
          };
        })()
    """.trimIndent())

    private fun assertBlue(color: String) {
        val channels = Regex("\\d+").findAll(color).take(3).map { it.value.toInt() }.toList()
        assertEquals("A real colored number token must be present", 3, channels.size)
        assertTrue("Number token should be blue, not the unhighlighted foreground",
            channels[2] > channels[0] && channels[2] > channels[1])
    }

    @After fun dispose() {
        if (::renderer.isInitialized) instrumentation.runOnMainSync { renderer.destroy() }
    }

    @Test fun coldSourceAndComparisonHaveRealIdenticalBlueTokensAndSurviveReloadThemeAndSourceSwitch() {
        val source = "{\"count\": 42, \"enabled\": true, \"label\": \"${"x".repeat(400)}\"}"
        val sourceScript = codePreviewDocumentScript("snapshot.json", source)
        mount(sourceScript)
        ready()
        val live = snapshot()
        assertEquals("source", live.getString("mode"))
        val lightBlue = live.getString("numberColor")
        assertBlue(lightBlue)
        assertTrue("Source line numbers must remain visible", live.getBoolean("gutterVisible"))
        assertTrue("Source text must not overlap its line number", live.getBoolean("textClearsGutter"))
        assertTrue("A long source line must visibly wrap",
            live.getInt("lineHeightPixel") > live.getInt("textLineHeightPixel"))
        update(codeComparisonDocumentScript(WorkspaceFileDiff("text", "snapshot.json", "snapshot.json", before = false,
            hunks = listOf(WorkspaceDiffHunk(0, 1, listOf("+$source"))))))
        ready()
        val fullFile = snapshot()
        assertEquals(lightBlue, fullFile.getString("numberColor"))
        assertEquals(live.getInt("lineTopPixel"), fullFile.getInt("lineTopPixel"))
        assertEquals("Complete snapshots must align their source text on screen",
            live.getInt("textLeftPixel"), fullFile.getInt("textLeftPixel"))
        assertEquals(live.getInt("lineHeightPixel"), fullFile.getInt("lineHeightPixel"))
        assertTrue(fullFile.getBoolean("gutterVisible"))
        assertTrue(fullFile.getBoolean("textClearsGutter"))
        assertEquals("1", fullFile.getJSONArray("rows").getJSONObject(0).getString("new"))
        assertEquals(live.getString("lineBackground"), fullFile.getString("lineBackground"))
        assertEquals("none", fullFile.getString("marker"))

        val diff = WorkspaceFileDiff("text", "snapshot.json", "snapshot.json", hunks = listOf(
            WorkspaceDiffHunk(4, 8, listOf("-{\"count\": 1}", "+$source")),
        ))
        val diffScript = codeComparisonDocumentScript(diff)
        update(diffScript)
        ready()
        val comparison = snapshot()
        assertEquals("comparison", comparison.getString("mode"))
        assertEquals("false", comparison.getString("oneSided"))
        assertEquals("4", comparison.getJSONArray("rows").getJSONObject(0).getString("old"))
        assertEquals("8", comparison.getJSONArray("rows").getJSONObject(1).getString("new"))
        assertEquals(source, comparison.getJSONArray("rows").getJSONObject(1).getString("text"))
        assertEquals(lightBlue, comparison.getString("numberColor"))
        assertTrue("A patch must reserve space for both coordinates and the change sign",
            comparison.getInt("textLeftPixel") > fullFile.getInt("textLeftPixel"))
        assertTrue(comparison.getBoolean("gutterVisible"))
        assertTrue(comparison.getBoolean("textClearsGutter"))
        assertEquals("\"-\"", comparison.getString("marker"))
        assertTrue("A long patch row must visibly wrap",
            comparison.getJSONArray("rows").getJSONObject(1).getInt("heightPixel") > comparison.getInt("textLineHeightPixel"))
        val generation = comparison.getInt("generation")
        update(diffScript, options.put("dark", true).put("wrap", false).put("fontSize", 13))
        ready()
        val dark = snapshot()
        assertTrue(dark.getBoolean("dark"))
        assertEquals("false", dark.getString("wrap"))
        assertEquals(generation, dark.getInt("generation"))
        assertBlue(dark.getString("numberColor"))
        assertNotEquals(lightBlue, dark.getString("numberColor"))
        assertEquals("Disabling wrap must render the long patch row at one text-line height",
            dark.getInt("textLineHeightPixel"), dark.getJSONArray("rows").getJSONObject(1).getInt("heightPixel"))

        instrumentation.runOnMainSync { renderer.reload() }
        ready()
        assertEquals("comparison", snapshot().getString("mode"))
        update(sourceScript)
        ready()
        assertEquals(source, snapshot().getString("text"))
        assertEquals(0, snapshot().getJSONArray("rows").length())
        update(diffScript)
        ready()
        assertEquals(2, snapshot().getJSONArray("rows").length())

        evaluate("window.dispatchEvent(new ErrorEvent('error', {error: new Error('fixture render failure')})); ({dispatched:true})")
        rule.waitUntil(5_000) {
            var failure: String? = null
            instrumentation.runOnMainSync { failure = renderer.error }
            failure?.contains("fixture render failure") == true
        }
        instrumentation.runOnMainSync { renderer.reload() }
        ready()
        assertEquals(lightBlue, snapshot().getString("numberColor"))
    }

    @Test fun oneSidedSnapshotRetainsBlankRowsAndCapsVisibleLinesWithExplicitNotice() {
        val lines = List(5001) { "+const value$it = $it;" }
        val diff = WorkspaceFileDiff("text", "created.ts", "created.ts", before = false,
            hunks = listOf(WorkspaceDiffHunk(0, 1, lines)))
        mount(codeComparisonDocumentScript(diff))
        ready()
        val capped = snapshot()
        assertEquals(5000, capped.getJSONArray("rows").length())
        assertEquals("5000", capped.getJSONArray("rows").getJSONObject(4999).getString("new"))
        assertTrue(capped.getBoolean("truncated"))
        assertEquals("true", capped.getString("oneSided"))
        assertEquals("none", capped.getString("secondGutter"))
        assertNotEquals("rgb(17, 17, 17)", capped.getString("lastColor"))
        val removed = diff.copy(path = "removed.ts", before = true, after = false, hunks = listOf(
            WorkspaceDiffHunk(10, 0, listOf("-/* open", "-still comment */", "-")),
            WorkspaceDiffHunk(20, 0, listOf("-const restored = 42;", "-")),
        ))
        update(codeComparisonDocumentScript(removed))
        ready()
        val deleted = snapshot()
        assertEquals(5, deleted.getJSONArray("rows").length())
        assertEquals("20", deleted.getJSONArray("rows").getJSONObject(3).getString("old"))
        assertEquals("", deleted.getJSONArray("rows").getJSONObject(4).getString("text"))
        assertFalse(deleted.getBoolean("truncated"))
        assertBlue(deleted.getString("numberColor"))
        update(codePreviewDocumentScript("attack.js", "</script><img src=x onerror=alert(1)>\nconst safe = 42;"))
        ready()
        assertEquals(0, snapshot().getInt("unsafeElements"))
    }
}
