package dev.dsh.pocket.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.dsh.pocket.protocol.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
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
class WorkspaceChangesTest {
    @get:Rule val rule = createComposeRule()
    private val summary = WorkspaceChangesSummary(1, "/repo", listOf(
        WorkspaceChangedFile("README.md", "README.md", 1, 1),
        WorkspaceChangedFile("dist/index.js", "dist/index.js", 265, 184),
        WorkspaceChangedFile("src/adapter.ts", "src/adapter.ts", 277, 206),
        WorkspaceChangedFile("src/errors.ts", "src/errors.ts", 46, 0),
        WorkspaceChangedFile("src/auth.ts", "src/auth.ts", 150, 3),
        WorkspaceChangedFile("test/auth.test.ts", "test/auth.test.ts", 120, 2),
        WorkspaceChangedFile("package.json", "package.json", 9, 0),
    ), 7, 868, 396)

    @Before fun setup() {
        installHostSettingsFixture()
        Pocket.prefs.language = AppLanguage.Zh
        Pocket.prefs.appearance = Appearance.Light
        Pocket.ops.value = setOf("workspaceChanges.summary")
    }

    @Test fun collapsedCardKeepsCountsAndOpensTheOriginalFileIndexAfterExpansion() {
        var selected = -1
        rule.setContent { PocketTheme { Column(Modifier.fillMaxSize().background(T.Bg).padding(16.dp)) {
            MarkdownView("## 下一步\n\n请重启 DSH 桌面端应用。重启后会自动重换并重试，不再需要重新保存 PAT。")
            Spacer(Modifier.height(12.dp))
            ChangesSummaryCard(summary, "one") { selected = it }
        } } }
        rule.onNodeWithText("已编辑 7 个文件").assertIsDisplayed()
        rule.onNodeWithText("+868").assertIsDisplayed()
        rule.onNodeWithText("−396").assertIsDisplayed()
        rule.onNodeWithTag("changedFile:3").assertIsDisplayed()
        rule.onNodeWithTag("changedFile:4").assertDoesNotExist()
        rule.onRoot().captureRoboImage("build/screenshots/changes-card-light.png")
        rule.onNodeWithTag("changesExpand").performClick()
        rule.onNodeWithTag("changedFile:6").performClick()
        rule.runOnIdle { assertEquals(6, selected) }
        rule.onNodeWithTag("changesExpand").performClick()
        rule.onNodeWithTag("changedFile:4").assertDoesNotExist()
        rule.onNodeWithText("已编辑 7 个文件").performClick()
        rule.runOnIdle { assertEquals(0, selected) }
        rule.runOnIdle { Pocket.prefs.appearance = Appearance.Dark }
        rule.onRoot().captureRoboImage("build/screenshots/changes-card-dark.png")
    }

    @Test fun singleBinaryFileHasItsNameAndStatusWithoutFakeCountsOrList() {
        val file = WorkspaceChangedFile("assets/logo.png", "assets/logo.png", 0, 0, binary = true)
        rule.setContent { PocketTheme { ChangesSummaryCard(summary.copy(files = listOf(file), total = 1, added = 0, deleted = 0), "binary", null) } }
        rule.onNodeWithText("已编辑 logo.png").assertIsDisplayed()
        rule.onNodeWithText("二进制").assertIsDisplayed()
        rule.onNodeWithText("+0").assertDoesNotExist()
        rule.onNodeWithTag("changedFile:0").assertDoesNotExist()
    }

    @Test fun missingSummaryAndLateOldSessionResponseNeverDisplayAnOldCard() {
        var session by mutableStateOf("old")
        val old = CompletableDeferred<WorkspaceChangesSummary?>()
        rule.setContent { PocketTheme { ChangesCard(session, Item.Changes("ch4", turn = 1, seq = 4)) { id, _ ->
            if (id == "old") old.await() else null
        } } }
        rule.onNodeWithTag("changesSummary").assertDoesNotExist()
        rule.runOnIdle { session = "new" }
        rule.runOnIdle { old.complete(summary) }
        rule.onNodeWithTag("changesSummary").assertDoesNotExist()
    }

    @Test fun emptySummaryDoesNotProduceEditedZeroFiles() {
        rule.setContent { PocketTheme { ChangesCard("empty", Item.Changes("ch1", turn = 1, seq = 1)) { _, _ -> summary.copy(files = emptyList(), total = 0) } } }
        rule.onNodeWithTag("changesSummary").assertDoesNotExist()
    }

    @Test fun refreshReloadsTheSameHistoricalCoordinatesWhileSelectionKeepsHostOrder() {
        val requests = mutableListOf<Triple<String, Long, Int>>()
        rule.setContent { PocketTheme {
            ChangesReviewSheet("session", 47, summary, 6, {}) { session, seq, index ->
                requests += Triple(session, seq, index)
                WorkspaceFileDiff("binary", summary.files[index].path, summary.files[index].display)
            }
        } }
        rule.onNodeWithTag("previewPath").assertContentDescriptionEquals("package.json")
        rule.onNodeWithContentDescription("刷新文件").performClick()
        rule.runOnIdle { assertEquals(listOf(Triple("session", 47L, 6), Triple("session", 47L, 6)), requests) }
        rule.onNodeWithTag("changesFilePicker").performClick()
        rule.onNodeWithTag("changesFileOption:1").performClick()
        rule.onNodeWithTag("previewPath").assertContentDescriptionEquals("dist/index.js")
        rule.runOnIdle { assertEquals(Triple("session", 47L, 1), requests.last()) }
        rule.onNodeWithTag("changesFilePicker").performClick()
        rule.onNodeWithTag("changesFileOption:6").performClick()
        rule.runOnIdle { assertEquals(3, requests.size) }
    }

    @Test fun modeAndWrapAreUsableWithoutFetchingTheCurrentWorkspaceFile() {
        val requests = mutableListOf<Int>()
        rule.setContent { PocketTheme {
            ChangesReviewSheet("snapshot", 8, summary, 0, {}) { _, _, index ->
                requests += index
                WorkspaceFileDiff("text", summary.files[index].path, summary.files[index].display)
            }
        } }
        rule.onNodeWithTag("previewMode").performClick()
        rule.onNodeWithTag("previewMode:Plain").performClick()
        rule.onNodeWithTag("previewMode").performClick()
        rule.onNodeWithTag("previewMode:Plain").assertIsSelected()
        rule.onNodeWithTag("previewMode:Code").performClick()
        rule.onNodeWithContentDescription(L.wrapLines()).performClick()
        rule.runOnIdle { assertEquals(listOf(0), requests) }
    }

    @Test fun switchingFilesIsolatesLateResponsesAndDoesNotCacheAnAbandonedRead() {
        val old = CompletableDeferred<WorkspaceFileDiff?>()
        val requests = mutableListOf<Int>()
        var first = true
        rule.setContent { PocketTheme {
            ChangesReviewSheet("snapshot", 12, summary, 0, {}) { _, _, index ->
                requests += index
                if (index == 0 && first) {
                    first = false
                    withContext(NonCancellable) { old.await() }
                } else WorkspaceFileDiff("binary", summary.files[index].path, summary.files[index].display)
            }
        } }
        rule.onNodeWithTag("changesLoading").assertExists()
        rule.onNodeWithTag("changesFilePicker").performClick()
        rule.onNodeWithTag("changesFileOption:1").performClick()
        rule.onNodeWithTag("previewPath").assertContentDescriptionEquals("dist/index.js")
        rule.runOnIdle { old.complete(WorkspaceFileDiff("oversized", "README.md", "README.md")) }
        rule.onNodeWithTag("previewPath").assertContentDescriptionEquals("dist/index.js")
        rule.onNodeWithTag("changesLoading").assertDoesNotExist()
        rule.onNodeWithTag("changesFilePicker").performClick()
        rule.onNodeWithTag("changesFileOption:0").performClick()
        rule.runOnIdle { assertEquals(listOf(0, 1, 0), requests) }
    }

    @Test fun failedSnapshotReadRetriesItsCoordinatesWithoutShowingThePreviousFile() {
        val requests = mutableListOf<Int>()
        var fail = true
        rule.setContent { PocketTheme {
            ChangesReviewSheet("snapshot", 12, summary, 0, {}) { _, _, index ->
                requests += index
                if (index == 1 && fail) { fail = false; error("snapshot unavailable") }
                WorkspaceFileDiff("binary", summary.files[index].path, summary.files[index].display)
            }
        } }
        rule.onNodeWithTag("changesFilePicker").performClick()
        rule.onNodeWithTag("changesFileOption:1").performClick()
        rule.onNodeWithText("snapshot unavailable").assertIsDisplayed()
        rule.onNodeWithTag("changesNotice").assertDoesNotExist()
        rule.onNodeWithText(L.docRetry()).performClick()
        rule.onNodeWithTag("changesNotice").assertExists()
        rule.onNodeWithText("snapshot unavailable").assertDoesNotExist()
        rule.runOnIdle { assertEquals(listOf(0, 1, 1), requests) }
    }
}
