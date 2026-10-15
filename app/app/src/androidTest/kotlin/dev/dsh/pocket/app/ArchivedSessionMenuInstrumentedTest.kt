package dev.dsh.pocket.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.dsh.pocket.protocol.SessionSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ArchivedSessionMenuInstrumentedTest {
    @get:Rule val rule = createComposeRule()

    @Test fun archivedRenameCancellationKeepsOriginalTitleAndArchiveState() {
        Pocket.init(InstrumentationRegistry.getInstrumentation().targetContext)
        val profiles = Pocket.profiles.value
        val active = Pocket.active.value
        val status = Pocket.status.value
        val ops = Pocket.ops.value
        val sessions = Pocket.sessions.value
        val workspaces = Pocket.workspaces.value
        val archived = Pocket.archivedIds.value
        val pinned = Pocket.pinnedIds.value
        val chat = Pocket.chat.value
        val grouping = Pocket.prefs.sessionGroupBy
        val filter = Pocket.prefs.archivedFilter
        try {
            Pocket.profiles.value = listOf(Profile("rename-test", "Host", host = "127.0.0.1", port = 7690))
            Pocket.active.value = "rename-test"
            Pocket.status.value = mapOf("rename-test" to Dot.Online)
            Pocket.ops.value = setOf("sessions.rename", "sessions.fork", "workspace.pinSession")
            Pocket.sessions.value = listOf(SessionSummary("archived-test", "Original title", "/workspace", false, false, 1, null))
            Pocket.workspaces.value = emptyList()
            Pocket.archivedIds.value = setOf("archived-test")
            Pocket.pinnedIds.value = emptySet()
            Pocket.chat.value = null
            Pocket.prefs.sessionGroupBy = SessionGroupBy.Flat
            Pocket.prefs.archivedFilter = ArchivedFilter.Show
            rule.setContent { PocketTheme { SessionDrawer({}, {}, {}, {}) } }
            rule.onNodeWithText("Original title").performTouchInput { longClick() }
            rule.onNodeWithText(L.rename()).assertIsEnabled().performClick()
            rule.onNode(hasSetTextAction()).performTextReplacement("Unsaved title")
            rule.onNodeWithText(L.cancel()).performClick()
            rule.onNodeWithText("Original title").performTouchInput { longClick() }
            rule.onNodeWithText(L.rename()).performClick()
            rule.onNode(hasSetTextAction()).assertTextEquals("Original title")
            rule.onNodeWithText(L.cancel()).performClick()
            assertEquals(setOf("archived-test"), Pocket.archivedIds.value)
            assertTrue(Pocket.pinnedIds.value.isEmpty())
        } finally {
            Pocket.profiles.value = profiles
            Pocket.active.value = active
            Pocket.prefs.profiles = profiles
            Pocket.prefs.activeProfile = active
            Pocket.status.value = status
            Pocket.ops.value = ops
            Pocket.sessions.value = sessions
            Pocket.workspaces.value = workspaces
            Pocket.archivedIds.value = archived
            Pocket.pinnedIds.value = pinned
            Pocket.chat.value = chat
            Pocket.prefs.sessionGroupBy = grouping
            Pocket.prefs.archivedFilter = filter
        }
    }
}
