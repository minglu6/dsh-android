package dev.dsh.pocket.app

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import com.github.takahirom.roborazzi.captureRoboImage
import dev.dsh.pocket.protocol.PocketJson
import dev.dsh.pocket.protocol.QueuedPrompt
import dev.dsh.pocket.protocol.TranscriptReducer
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
class QueuedPromptUiTest {
    @get:Rule val rule = createComposeRule()

    @Before fun state() {
        installHostSettingsFixture()
        Pocket.profiles.value = listOf(Profile("p1", "desk", host = "100.106.128.87", port = 7690))
        Pocket.active.value = "p1"
        Pocket.status.value = mapOf("p1" to Dot.Online)
        Pocket.catalog.value = null
        Pocket.ops.value = emptySet()
        Pocket.sessions.value = emptyList()
        Pocket.approvals.value = emptyList()
        Pocket.userQuestions.value = emptyList()
        Pocket.chat.value = ChatState("queue-ui", loaded = true)
    }

    private fun event(reducer: TranscriptReducer, seq: Int, type: String, data: String) {
        reducer.apply(PocketJson.parseToJsonElement(
            """{"type":"event","event":{"seq":$seq,"time":${seq * 1000},"type":"$type","data":$data}}""",
        ))
    }

    private fun publish(reducer: TranscriptReducer) {
        Pocket.chat.value = ChatState("queue-ui", loaded = true, running = reducer.running,
            items = reducer.items(), steps = reducer.stepRecords(), turns = reducer.turnRecords(),
            queuedPrompts = reducer.queueMessages())
    }

    @Test fun busyQueueStaysAboveComposerUntilHostConsumesIt() {
        val reducer = TranscriptReducer()
        event(reducer, 1, "turn/start", """{"turn":1}""")
        reducer.echo("下一轮整理结果", "request-1", "queue")
        reducer.echo("下一轮整理结果", "request-2", "queue", images = 1, files = 2)
        publish(reducer)
        rule.setContent { PocketTheme { ChatRoot(onMenu = {}) } }
        rule.onNodeWithText("排队消息（2）").assertIsDisplayed()
        rule.onAllNodes(hasText("下一轮整理结果") and hasAnyAncestor(hasTestTag("promptQueue"))).assertCountEquals(2)
        rule.onAllNodes(hasText("下一轮整理结果") and !hasAnyAncestor(hasTestTag("promptQueue"))).assertCountEquals(0)
        rule.onNodeWithText("发送中… · 1 张图片 · 2 个文件").assertIsDisplayed()

        val message = """{"id":"host-1","role":"user","source":{"kind":"user","rpcId":"request-1"},"content":[{"type":"text","text":"下一轮整理结果"}]}"""
        rule.runOnIdle {
            event(reducer, 2, "agent/inbox/spliced", """{"target":"next-turn","start":0,"inserted":[$message]}""")
            publish(reducer)
        }
        rule.onNodeWithText("等待本轮结束").assertIsDisplayed()
        rule.onNodeWithText("排队消息（2）").assertIsDisplayed()
        rule.onRoot().captureRoboImage("build/screenshots/queue-composer-zh.png")

        rule.runOnIdle {
            event(reducer, 3, "turn/end", """{"turn":1,"reason":{"kind":"completed"}}""")
            event(reducer, 4, "agent/inbox/spliced", """{"target":"next-turn","start":0,"removedCount":1,"inserted":[]}""")
            event(reducer, 5, "user/message", message)
            publish(reducer)
        }
        rule.onNodeWithText("排队消息（1）").assertIsDisplayed()
        rule.onAllNodes(hasText("下一轮整理结果") and hasAnyAncestor(hasTestTag("promptQueue"))).assertCountEquals(1)
        rule.onAllNodes(hasText("下一轮整理结果") and !hasAnyAncestor(hasTestTag("promptQueue"))).assertCountEquals(1)
        rule.runOnIdle { reducer.discardEcho("request-2"); publish(reducer) }
        rule.onNodeWithTag("promptQueue").assertDoesNotExist()
    }

    @Test fun idleAndSteerMessagesNeverAppearInQueue() {
        val reducer = TranscriptReducer()
        reducer.echo("正常首条消息", "idle", "queue")
        event(reducer, 1, "turn/start", """{"turn":1}""")
        reducer.echo("当前轮次补充", "steer", "steer")
        publish(reducer)
        rule.setContent { PocketTheme { ChatRoot(onMenu = {}) } }
        rule.onNodeWithTag("promptQueue").assertDoesNotExist()
        rule.onNodeWithText("正常首条消息").assertIsDisplayed()
        rule.onNodeWithText("当前轮次补充").assertIsDisplayed()
    }

    @Test fun englishAttachmentQueueIsBoundedAndScrollableInDarkTheme() {
        Pocket.prefs.language = AppLanguage.En
        Pocket.prefs.appearance = Appearance.Dark
        Pocket.chat.value = ChatState("queue-ui", loaded = true, running = true,
            queuedPrompts = listOf(QueuedPrompt("attachments", "", images = 2, files = 1)) +
                (1..8).map { QueuedPrompt("long-$it", "Queued follow-up $it") })
        rule.setContent { PocketTheme { ChatRoot(onMenu = {}) } }
        rule.onNodeWithText("Queued messages (9)").assertIsDisplayed()
        rule.onNodeWithText("Attachments").assertIsDisplayed()
        rule.onNodeWithText("Waiting for this turn to finish · 2 images · 1 file").assertIsDisplayed()
        rule.onNodeWithTag("composerInput").assertIsDisplayed()
        rule.onRoot().captureRoboImage("build/screenshots/queue-composer-en-dark.png")
        rule.onNodeWithText("Queued follow-up 8").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("composerInput").assertIsDisplayed()
    }
}
