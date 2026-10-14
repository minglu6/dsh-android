package dev.dsh.pocket.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h900dp-xxhdpi")
@OptIn(ExperimentalRoborazziApi::class)
class MarkdownLayoutTest {
    @get:Rule val rule = createComposeRule()

    @Test fun emphasisFlowsIntoParagraphInLightTheme() = checkParagraph(Appearance.Light)
    @Test fun emphasisFlowsIntoParagraphInDarkTheme() = checkParagraph(Appearance.Dark)
    @Test fun inlineCodeAndLinksKeepFollowingTextOnTheSameLine() {
        installHostSettingsFixture()
        val opened = mutableListOf<String>()
        rule.setContent {
            PocketTheme {
                CompositionLocalProvider(LocalUriHandler provides object : UriHandler {
                    override fun openUri(uri: String) { opened += uri }
                }) {
                    Box(Modifier.fillMaxWidth().background(T.Bg).padding(16.dp)) {
                        MarkdownView("> **提示**：运行 `x`，查看[官网](https://example.com)。")
                    }
                }
            }
        }
        val layouts = mutableListOf<TextLayoutResult>()
        val paragraph = rule.onNodeWithText("提示：运行 x，查看官网。")
        paragraph.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        assertEquals(1, layout.lineCount)
        rule.onRoot().captureRoboImage("build/screenshots/markdown-inline.png")
        rule.onNodeWithText("x", useUnmergedTree = true).assertIsDisplayed()
        val linkOffset = layout.layoutInput.text.text.indexOf("官网")
        paragraph.performTouchInput { click(layout.getBoundingBox(linkOffset).center) }
        rule.runOnIdle { assertEquals(listOf("https://example.com"), opened) }
    }


    private fun checkParagraph(appearance: Appearance) {
        installHostSettingsFixture()
        val previous = Pocket.prefs.appearance
        try {
            Pocket.prefs.appearance = appearance
            rule.setContent {
                PocketTheme {
                    Box(Modifier.fillMaxWidth().background(T.Bg).padding(16.dp)) {
                        MarkdownView(COMPARISON)
                    }
                }
            }
            val layouts = mutableListOf<TextLayoutResult>()
            rule.onNodeWithText(ADVICE).performSemanticsAction(SemanticsActions.GetTextLayoutResult) {
                it(layouts)
            }
            val layout = layouts.single()
            assertEquals("bold label and following punctuation share the first line", 0, layout.getLineForOffset(4))
            assertEquals(0, layout.getLineForOffset(6))
            assertTrue("paragraph wraps naturally on a phone", layout.lineCount > 1)
            val first = rule.onNodeWithText("维度").fetchSemanticsNode().boundsInRoot
            val second = rule.onNodeWithText("Claude 的相对优势").fetchSemanticsNode().boundsInRoot
            val longCell = rule.onNodeWithText("通常被认为 Agent 稳定性、多文件改造表现领先").fetchSemanticsNode().boundsInRoot
            assertTrue("short labels must not consume half the table", first.width < second.width)
            assertEquals("column positions remain aligned", second.left, longCell.left, 1f)
            rule.onRoot().captureRoboImage("build/screenshots/markdown-comparison-${appearance.name.lowercase()}.png")
        } finally {
            Pocket.prefs.appearance = previous
        }
    }

    companion object {
        private const val ADVICE = "选型建议：日常问答和写作选 Sonnet 档就够；复杂调试、大型重构、Agent 工作流上 Opus；批量分类、抽取、需要便宜快速的场景用 Haiku。"
        private val COMPARISON = """
            ### 和竞品相比的取舍

            | 维度 | Claude 的相对优势 |
            | --- | --- |
            | 代码与长任务 | 通常被认为 Agent 稳定性、多文件改造表现领先 |
            | 写作 | 文风自然、少"AI 味"，长文结构感强 |
            | 安全与合规 | 拒绝策略更保守、可解释性研究投入大，企业采购友好 |
            | 生态广度 | 插件/多模态应用生态不如 OpenAI、Gemini 铺得广 |

            **选型建议**：日常问答和写作选 Sonnet 档就够；复杂调试、大型重构、Agent 工作流上 Opus；批量分类、抽取、需要便宜快速的场景用 Haiku。

            需要我针对某一块展开吗？比如"Claude Code 怎么用"、"Claude 与 GPT 的具体差异"，或者帮你对比某个具体版本的价格与上下文限制。
        """.trimIndent()
    }
}
