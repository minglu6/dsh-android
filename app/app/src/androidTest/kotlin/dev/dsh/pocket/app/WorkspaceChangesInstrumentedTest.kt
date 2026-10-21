package dev.dsh.pocket.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.dsh.pocket.protocol.*
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real Android dialog windows, file switching and retry without mutating a paired Host. */
@RunWith(AndroidJUnit4::class)
class WorkspaceChangesInstrumentedTest {
    @get:Rule val rule = createComposeRule()

    @Test fun cardOpensSelectedSnapshotAndRetryDoesNotReuseThePreviousFile() {
        Pocket.init(InstrumentationRegistry.getInstrumentation().targetContext)
        val summary = WorkspaceChangesSummary(2, "/repo", (0..6).map {
            WorkspaceChangedFile("src/file$it.kt", "src/file$it.kt", it + 1, it)
        }, 7, 28, 21)
        var selected by mutableStateOf<Int?>(null)
        val requests = mutableListOf<Triple<String, Long, Int>>()
        var failed = false
        rule.setContent { PocketTheme { Column(Modifier.fillMaxSize().background(T.Bg).padding(16.dp)) {
            ChangesSummaryCard(summary, "phone") { selected = it }
            selected?.let { index -> ChangesReviewSheet("session-owner", 42, summary, index, { selected = null }) { session, seq, file ->
                requests += Triple(session, seq, file)
                if (file == 0 && !failed) { failed = true; error("snapshot read failed") }
                WorkspaceFileDiff("text", summary.files[file].path, summary.files[file].display,
                    hunks = listOf(WorkspaceDiffHunk(1, 1, listOf("-old-$file", "+new-$file"))))
            } }
        } } }
        rule.onNodeWithTag("changedFile:4").assertDoesNotExist()
        rule.onNodeWithTag("changesExpand").performClick()
        rule.onNodeWithTag("changedFile:6").performClick()
        rule.onNodeWithTag("previewPath").assertContentDescriptionEquals("src/file6.kt")
        rule.onNodeWithTag("changesDiff").assertExists()
        assertEquals(Triple("session-owner", 42L, 6), requests.single())
        rule.onNodeWithTag("changesFilePicker").performClick()
        rule.onNodeWithTag("changesFileOption:0").performClick()
        rule.onNodeWithText("snapshot read failed").assertIsDisplayed()
        rule.onNodeWithTag("changesDiff").assertDoesNotExist()
        rule.onNodeWithText(L.docRetry()).performClick()
        rule.onNodeWithTag("previewPath").assertContentDescriptionEquals("src/file0.kt")
        rule.onNodeWithTag("changesDiff").assertExists()
        assertEquals(listOf(6, 0, 0), requests.map { it.third })
        rule.onNodeWithTag("changesFilePicker").performClick()
        rule.onNodeWithTag("changesFileOption:6").performClick()
        rule.onNodeWithTag("previewPath").assertContentDescriptionEquals("src/file6.kt")
        rule.onNodeWithTag("changesDiff").assertExists()
        assertEquals(listOf(6, 0, 0), requests.map { it.third })
        rule.onNodeWithTag("previewMode").performClick()
        rule.onNodeWithTag("previewMode:Plain").performClick()
        rule.onNodeWithTag("changesDiff").assertExists()
        rule.onNodeWithContentDescription(if (Pocket.prefs.language == AppLanguage.En) "Refresh file" else "刷新文件").performClick()
        rule.runOnIdle { assertEquals(listOf(6, 0, 0, 6), requests.map { it.third }) }
    }
}
