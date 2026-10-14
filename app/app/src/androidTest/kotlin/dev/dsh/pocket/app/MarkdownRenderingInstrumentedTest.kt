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
import org.junit.Assert.assertEquals
import kotlin.math.abs
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

    @Test fun longCodeAndListMarkersInLightTheme() = checkCodeAndMarkers(Appearance.Light, 14)
    @Test fun longCodeAndListMarkersInDarkTheme() = checkCodeAndMarkers(Appearance.Dark, 14)
    @Test fun longCodeAndListMarkersWithLargeText() = checkCodeAndMarkers(Appearance.Light, 22)

    private fun checkCodeAndMarkers(appearance: Appearance, fontSize: Int) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        Pocket.init(context)
        Pocket.prefs.hostSettings.bind({
            jsonOf("writable" to false, "namespaces" to listOf(
                jsonOf("ns" to "ui-theme", "revision" to 0,
                    "value" to jsonOf("preference" to appearance.name.lowercase())),
            ))
        }, { _, _, _ -> error("Rendering regression must not change Host settings") })
        try {
            val code = """403 {"code":"105","message":"Login expired"}"""
            val item = "只重试一次（attempt 0 → 1），之后按原样抛错"
            rule.setContent {
                PocketTheme {
                    CompositionLocalProvider(LocalTextStyle provides MaterialTheme.typography.bodyLarge) {
                        Box(Modifier.fillMaxWidth().background(T.Bg).padding(16.dp)) {
                            MarkdownView(
                                "### 修复内容总结\n\n针对 `$code` 的根因（缓存的 job token 被网关拒绝后没有失效/重换机制，且实际有效时长远低于宣传的 24h），按批准的方案改了 4 处：\n\n" +
                                    "- 聊天请求：HTTP 401/403 或流内 `code 105` 信封都会触发失效重试；仅在**尚未输出任何 chunk**时重试（已 yield 的内容无法收回）\n" +
                                    "- 模型列表：HTTP 401/403 同样失效重试一次\n- $item",
                                bodyFontSize = fontSize.sp,
                            )
                        }
                    }
                }
            }
            val image = rule.onRoot().captureToImage()
            context.openFileOutput("markdown-code-markers-${appearance.name.lowercase()}-$fontSize.png", 0).use {
                image.asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            val codeNode = rule.onNodeWithText(code, useUnmergedTree = true)
            val codeLayouts = mutableListOf<TextLayoutResult>()
            codeNode.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(codeLayouts) }
            val codeLayout = codeLayouts.single()
            assertTrue("long inline code must wrap on the phone", codeLayout.lineCount > 1)
            assertTrue("all code lines must fit their measured slot",
                codeLayout.getLineBottom(codeLayout.lineCount - 1) <= codeNode.fetchSemanticsNode().boundsInRoot.height + 1f)
            assertTrue("code must not overflow horizontally", !codeLayout.didOverflowWidth)
            val parentLayouts = mutableListOf<TextLayoutResult>()
            val paragraph = rule.onNodeWithText("针对 $code", substring = true)
            paragraph.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(parentLayouts) }
            val parentLayout = parentLayouts.single()
            val slot = parentLayout.placeholderRects.single()!!
            val paragraphTop = paragraph.fetchSemanticsNode().boundsInRoot.top
            assertTrue("code must not paint over preceding prose: slot=$slot, previousBottom=${parentLayout.getLineBottom(0)}",
                paragraphTop + slot.top >= paragraphTop + parentLayout.getLineBottom(0) - 1f)
            assertTrue("following prose must clear the complete code slot",
                parentLayout.getLineTop(parentLayout.getLineForOffset(parentLayout.layoutInput.text.text.indexOf("的根因"))) >= slot.bottom - 1f)

            val listNode = rule.onNodeWithText(item)
            val listLayouts = mutableListOf<TextLayoutResult>()
            listNode.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(listLayouts) }
            val listLayout = listLayouts.single()
            val pixels = image.toPixelMap()
            val expectedBackground = if (appearance == Appearance.Dark) DswColors.Dark.BgBase else DswColors.Light.BgBase
            assertEquals("capture must exercise the requested theme", expectedBackground, pixels[0, 0])
            val rootBounds = rule.onRoot().fetchSemanticsNode().boundsInRoot
            val bounds = listNode.fetchSemanticsNode().boundsInRoot
            val density = rule.density.density
            val fontPx = with(rule.density) { fontSize.sp.toPx() }
            val color = ThemeState.colors.LabelSecondary
            val markerPixels = mutableListOf<Pair<Int, Int>>()
            for (y in (bounds.top - rootBounds.top).toInt() until
                (bounds.top - rootBounds.top + listLayout.getLineBottom(0)).toInt()) {
                for (x in (bounds.left - rootBounds.left - 18 * density).toInt() until
                    (bounds.left - rootBounds.left - 3 * density).toInt()) {
                    val p = pixels[x, y]
                    if (abs(p.red - color.red) < 0.08f && abs(p.green - color.green) < 0.08f &&
                        abs(p.blue - color.blue) < 0.08f) markerPixels += x to y
                }
            }
            assertTrue("list marker must be visible", markerPixels.isNotEmpty())
            val diameter = markerPixels.maxOf { it.first } - markerPixels.minOf { it.first } + 1
            assertTrue("disc must scale with body text, not a tiny OEM bullet: $diameter / $fontPx",
                diameter / fontPx in 0.24f..0.38f)
            val centerY = (markerPixels.minOf { it.second } + markerPixels.maxOf { it.second }) / 2f
            val baseline = bounds.top - rootBounds.top + listLayout.firstBaseline
            assertEquals("marker must align with the first line, not the whole wrapped item",
                baseline - fontPx * 0.35f, centerY, 2f)
        } finally {
            Pocket.prefs.hostSettings.clear()
        }
    }
}
