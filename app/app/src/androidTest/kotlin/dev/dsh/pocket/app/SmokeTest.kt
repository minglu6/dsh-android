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
        rule.onNodeWithText("连接").assertIsDisplayed()
        rule.onNodeWithText("局域网").assertIsDisplayed()
        rule.onNodeWithText("Tailscale").assertIsDisplayed()
        rule.onNodeWithText("输入地址").performClick()
        rule.waitUntil(10_000) {
            rule.onAllNodesWithText("192.168.x.x:7690").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNode(hasText("配对") and hasClickAction()).assertExists()
    }
}
