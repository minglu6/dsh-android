package dev.dsh.pocket.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.LocalTextStyle
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.dsh.pocket.protocol.jsonOf
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** OEM system font mappings differ from Robolectric's bundled fonts. */
@RunWith(AndroidJUnit4::class)
class MarkdownRenderingInstrumentedTest {
    @get:Rule val rule = createComposeRule()

    @Test fun strongEmphasisIsVisiblyHeavierThanBodyOnSystemFont() {
        Pocket.init(InstrumentationRegistry.getInstrumentation().targetContext)
        Pocket.prefs.hostSettings.bind({
            jsonOf("writable" to false, "namespaces" to listOf(
                jsonOf("ns" to "ui-theme", "revision" to 0, "value" to jsonOf("textFontFamily" to "")),
            ))
        }, { _, _, _ -> error("Rendering regression must not change Host settings") })
        rule.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxWidth().background(Color.White).padding(16.dp)) {
                    MarkdownView("**选型建议**\n\n选型建议", bodyColor = Color.Black, bodyFontSize = 14.sp)
                }
            }
        }
        val paragraphs = rule.onAllNodesWithText("选型建议")
        fun ink(index: Int): Double {
            val pixels = paragraphs[index].captureToImage().toPixelMap()
            var sum = 0.0
            for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
                val pixel = pixels[x, y]
                sum += (1.0 - (pixel.red + pixel.green + pixel.blue) / 3.0) * pixel.alpha
            }
            return sum
        }
        val strong = ink(0)
        val regular = ink(1)
        assertTrue("Strong emphasis must remain visible on the device font: strong=$strong, body=$regular", strong > regular * 1.05)
    }

    @Test fun inlineFileLinkIsNotClippedByItsPlaceholder() {
        Pocket.init(InstrumentationRegistry.getInstrumentation().targetContext)
        rule.setContent {
            PocketTheme {
                CompositionLocalProvider(LocalTextStyle provides MaterialTheme.typography.bodyLarge) {
                    Box(Modifier.fillMaxWidth().background(T.Bg).padding(16.dp)) {
                        MarkdownView(
                            "2. **错误分类修正**：信封级 401/403 现在归类为 `AUTH`（复用 [httpErrorCode](src/errors.ts)），不再误报 `UPSTREAM`，携带 `failure.status`",
                            bodyFontSize = 14.sp,
                        )
                    }
                }
            }
        }
        val image = rule.onRoot().captureToImage().asAndroidBitmap()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.openFileOutput("markdown-file-link-device.png", 0).use {
            image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        val label = rule.onNodeWithText("httpErrorCode", useUnmergedTree = true)
        val layouts = mutableListOf<TextLayoutResult>()
        label.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        val visibleHeight = label.fetchSemanticsNode().boundsInRoot.height
        assertTrue("link text clipped: lineBottom=${layout.getLineBottom(0)}, visibleHeight=$visibleHeight",
            layout.getLineBottom(0) <= visibleHeight + 1f)
    }
}
