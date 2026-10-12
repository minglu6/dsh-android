package dev.dsh.pocket.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SmokeTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test fun launchesToConnectionsAndOpensPairSheet() {
        rule.onNodeWithText("连接到桌面端 dsh").assertIsDisplayed()
        rule.onNodeWithText("粘贴配对串").performClick()
        rule.waitUntil(10_000) {
            rule.onAllNodesWithText("粘贴电脑上显示的整行").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNode(hasText("配对") and hasClickAction()).assertExists()
    }
}
