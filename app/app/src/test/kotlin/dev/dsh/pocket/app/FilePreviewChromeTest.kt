package dev.dsh.pocket.app

import android.widget.TextView
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.matcher.ViewMatchers.withText
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xxhdpi")
class FilePreviewChromeTest {
    @get:Rule val rule = createComposeRule()

    @Before fun setup() {
        installHostSettingsFixture()
        Pocket.prefs.language = AppLanguage.Zh
    }

    @Test fun narrowPathElidesItsStartAndKeepsTheFilenameAndFullCopyValue() {
        val filename = "settings.json"
        val path = "/workspace/very-long-project-name/configuration/production/$filename"
        lateinit var clipboard: ClipboardManager
        rule.setContent { PocketTheme {
            clipboard = LocalClipboardManager.current
            PreviewPathLabel(path, Modifier.width(160.dp).testTag("path"))
        } }
        rule.onNodeWithTag("path").assertContentDescriptionEquals(path)
        onView(withText(path)).check { view, failure ->
            if (failure != null) throw failure
            val textView = view as TextView
            val layout = textView.layout
            assertEquals(0, layout.getEllipsisStart(0))
            assertTrue(layout.getEllipsisCount(0) > 0)
            assertTrue(layout.getEllipsisCount(0) <= path.length - filename.length)
            assertEquals(path, textView.text.toString())
        }
        val copy = rule.onNodeWithTag("path").fetchSemanticsNode().config[SemanticsActions.CustomActions].single()
        rule.runOnIdle {
            assertTrue(copy.action())
            assertEquals(path, clipboard.getText()?.text)
        }
    }

    @Test fun richPreviewKeepsItsModesAndCodeControlsCopyTheLatestValueAtClickTime() {
        var mode by mutableStateOf(TextPreviewMode.Preview)
        var wrap by mutableStateOf(false)
        var value = "initial source"
        var copiedReads = 0
        var refreshes = 0
        lateinit var clipboard: ClipboardManager
        rule.setContent { PocketTheme {
            clipboard = LocalClipboardManager.current
            FilePreviewChrome(
                path = "docs/notes.md", sourceKey = "current-file", onDismiss = {},
                mode = mode, modes = TextPreviewMode.entries, richText = true,
                onModeChange = { mode = it }, wrap = wrap, onWrapChange = { wrap = !wrap },
                loading = false, onRefresh = { refreshes++ }, language = "markdown",
                copyText = { copiedReads++; value },
            ) { Text(mode.name, Modifier.testTag("renderedMode")) }
        } }
        rule.onNodeWithTag("previewCodeBar").assertDoesNotExist()
        rule.onNodeWithTag("previewMode").performClick()
        rule.onNodeWithTag("previewMode:Code").performClick()
        rule.onNodeWithTag("renderedMode").assertTextEquals("Code")
        rule.onNodeWithTag("previewCodeBar").assertExists()
        rule.onNodeWithContentDescription(L.wrapLines()).performClick()
        rule.runOnIdle {
            assertTrue(wrap)
            assertEquals(0, copiedReads)
            value = "updated source"
        }
        rule.onNodeWithContentDescription(L.copy()).performClick()
        rule.runOnIdle {
            assertEquals(1, copiedReads)
            assertEquals("updated source", clipboard.getText()?.text)
        }
        rule.onNodeWithTag("previewMode").performClick()
        rule.onNodeWithTag("previewMode:Plain").performClick()
        rule.onNodeWithTag("renderedMode").assertTextEquals("Plain")
        rule.onNodeWithTag("previewCodeBar").assertExists()
        rule.onNodeWithContentDescription("刷新文件").performClick()
        rule.runOnIdle { assertEquals(1, refreshes) }
    }
}
