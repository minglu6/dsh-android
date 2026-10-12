package dev.dsh.pocket.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SmokeTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test fun launchesToConnectionsAndOpensPairSheet() {
        rule.onNodeWithText("连接到桌面端 DeepSeek Harness").assertIsDisplayed()
        rule.onNodeWithText("输入配对码").performClick()
        rule.waitUntil(10_000) { rule.onAllNodesWithText("100.x.y.z:7690").fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodesWithText("配对").fetchSemanticsNodes().let { assert(it.isNotEmpty()) }
        // primary button carries its label
        rule.onNode(hasText("配对") and hasClickAction()).assertExists()
    }
}
