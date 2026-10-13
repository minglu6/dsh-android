package dev.dsh.pocket.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

@RunWith(AndroidJUnit4::class)
class SmokeTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test fun launchesToConnectionsAndOpensPairSheet() {
        rule.waitUntil(10_000) {
            rule.onAllNodesWithTag("connectionsScreen").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithTag("connectionsScreen").assertIsDisplayed()
        rule.onNodeWithText(L.scanQr()).assertIsDisplayed()
        rule.onNodeWithText(L.enterAddress()).performClick()
        rule.waitUntil(10_000) {
            rule.onAllNodesWithText("192.168.x.x:7690").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNode(hasText(L.pairAction()) and hasClickAction()).assertExists()
    }
}
