package dev.dsh.pocket.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.github.takahirom.roborazzi.captureRoboImage
import dev.dsh.pocket.protocol.PocketJson
import dev.dsh.pocket.protocol.QueueAction
import dev.dsh.pocket.protocol.QueuedPrompt
import dev.dsh.pocket.protocol.TranscriptReducer
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
class QueuedPromptUiTest {
    @get:Rule val rule = createComposeRule()

    @Before fun state() {
        installHostSettingsFixture()
        Pocket.prefs.language = AppLanguage.Zh
        Pocket.prefs.appearance = Appearance.Light
        Pocket.profiles.value = listOf(Profile("p1", "desk", host = "100.106.128.87", port = 7690))
        Pocket.active.value = "p1"
        Pocket.status.value = mapOf("p1" to Dot.Online)
        Pocket.catalog.value = null
        Pocket.ops.value = setOf("queue.update")
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

    @Test fun busyQueueExpandsAndStaysAboveComposerUntilHostConsumesIt() {
        val reducer = TranscriptReducer()
        event(reducer, 1, "turn/start", """{"turn":1}""")
        reducer.echo("下一轮整理结果", "request-1", "queue")
        reducer.echo("下一轮整理结果", "request-2", "queue", images = 1, files = 2)
        publish(reducer)
        rule.setContent { PocketTheme { ChatRoot(onMenu = {}) } }
        rule.onNodeWithTag("promptQueueList").assertDoesNotExist()
        rule.onNodeWithTag("promptQueueToggle").performClick()
        rule.onAllNodes(hasText("下一轮整理结果", substring = true) and hasAnyAncestor(hasTestTag("promptQueue"))).assertCountEquals(2)
        rule.onAllNodes(hasText("下一轮整理结果") and !hasAnyAncestor(hasTestTag("promptQueue"))).assertCountEquals(0)
        rule.onNodeWithText("1 张图片 · 2 个文件", substring = true).assertIsDisplayed()
        rule.onNodeWithTag("composerInput").assertIsDisplayed()

        val message = """{"id":"host-1","role":"user","source":{"kind":"user","rpcId":"request-1"},"content":[{"type":"text","text":"下一轮整理结果"}]}"""
        rule.runOnIdle {
            event(reducer, 2, "agent/inbox/spliced", """{"target":"next-turn","start":0,"inserted":[$message]}""")
            publish(reducer)
        }
        rule.onNodeWithTag("queueEdit:host-1").assertIsEnabled()
        rule.onNodeWithTag("promptQueueToggle").performClick()
        rule.onNodeWithTag("promptQueueList").assertDoesNotExist()
        rule.onNodeWithTag("promptQueueToggle").performClick()
        rule.onRoot().captureRoboImage("build/screenshots/queue-composer-multiple-zh.png")

        rule.runOnIdle {
            event(reducer, 3, "turn/end", """{"turn":1,"reason":{"kind":"completed"}}""")
            event(reducer, 4, "agent/inbox/spliced", """{"target":"next-turn","start":0,"removedCount":1,"inserted":[]}""")
            event(reducer, 5, "user/message", message)
            publish(reducer)
        }
        rule.onNodeWithTag("promptQueueToggle").assertDoesNotExist()
        rule.onAllNodes(hasText("下一轮整理结果", substring = true) and hasAnyAncestor(hasTestTag("promptQueue"))).assertCountEquals(1)
        rule.onAllNodes(hasText("下一轮整理结果") and !hasAnyAncestor(hasTestTag("promptQueue"))).assertCountEquals(1)
        rule.runOnIdle { reducer.discardEcho("request-2"); publish(reducer) }
        rule.onNodeWithTag("promptQueue").assertDoesNotExist()
    }

    @Test fun singleConfirmedRowHasActionsWithoutHeaderOrWaitingStatus() {
        Pocket.chat.value = ChatState("queue-ui", loaded = true, running = true,
            queuedPrompts = listOf(QueuedPrompt("single", "看下今天的 AI 新闻")))
        rule.setContent { PocketTheme { ChatRoot(onMenu = {}) } }
        rule.onNodeWithTag("promptQueueCount").assertDoesNotExist()
        rule.onNodeWithTag("queueEdit:single").assertIsEnabled()
        rule.onNodeWithTag("queueRemove:single").assertIsEnabled()
        rule.onNodeWithTag("queueSteer:single").assertIsEnabled()
        rule.onNodeWithText("发送中…").assertDoesNotExist()
        rule.onNodeWithText("等待本轮结束", substring = true).assertDoesNotExist()
        rule.onNodeWithTag("composerInput").assertIsDisplayed()
        rule.onRoot().captureRoboImage("build/screenshots/queue-composer-single-zh.png")
        rule.runOnIdle { Pocket.ops.value = emptySet() }
        rule.onNodeWithTag("queueEdit:single").assertIsNotEnabled()
        rule.onNodeWithTag("queueRemove:single").assertIsNotEnabled()
        rule.onNodeWithTag("queueSteer:single").assertIsNotEnabled()
        rule.runOnIdle {
            Pocket.ops.value = setOf("queue.update")
            Pocket.status.value = mapOf("p1" to Dot.Offline)
        }
        rule.onNodeWithTag("queueRemove:single").assertIsNotEnabled()
    }

    @Test fun pendingAndAttachmentActionsRespectAcknowledgementAndRunning() {
        var rows by mutableStateOf(listOf(QueuedPrompt("row", "待发送", sending = true)))
        var running by mutableStateOf(true)
        val actions = mutableListOf<Pair<String, QueueAction>>()
        rule.setContent { PocketTheme {
            PromptQueue("session", rows, running, true) { id, action, complete ->
                actions += id to action
                complete(true)
            }
        } }
        rule.onNodeWithText("发送中…").assertIsDisplayed()
        rule.onNodeWithTag("queueEdit:row").assertIsNotEnabled()
        rule.onNodeWithTag("queueRemove:row").assertIsNotEnabled()
        rule.onNodeWithTag("queueSteer:row").assertIsNotEnabled()
        rule.runOnIdle { rows = listOf(QueuedPrompt("row", "附件消息", images = 1)); running = false }
        rule.onNodeWithTag("queueEdit:row").assertIsNotEnabled()
        rule.onNodeWithTag("queueRemove:row").assertIsEnabled()
        rule.onNodeWithTag("queueSteer:row").assertIsNotEnabled()
        rule.runOnIdle { rows = listOf(QueuedPrompt("row", "非文本内容", editable = false)) }
        rule.onNodeWithTag("queueEdit:row").assertIsNotEnabled()
        rule.runOnIdle { running = true }
        rule.onNodeWithTag("queueSteer:row").performClick()
        rule.runOnIdle { assertEquals(listOf("row" to QueueAction.Steer), actions) }
        // A successful mutation receipt alone must not locally consume the row.
        rule.onNodeWithTag("queuedPrompt:row").assertIsDisplayed()
        rule.onNodeWithTag("queueRemove:row").performClick()
        rule.runOnIdle { assertEquals("row" to QueueAction.Remove, actions.last()) }
        rule.onNodeWithTag("queuedPrompt:row").assertIsDisplayed()
        rule.runOnIdle { rows = emptyList() }
        rule.onNodeWithTag("promptQueue").assertDoesNotExist()
    }

    @Test fun editPreservesDraftOnFailureAndLocksExpandedQueueUntilCompletion() {
        var rows by mutableStateOf(listOf(QueuedPrompt("one", "相同内容"), QueuedPrompt("two", "相同内容")))
        var complete: ((Boolean) -> Unit)? = null
        val actions = mutableListOf<Pair<String, QueueAction>>()
        rule.setContent { PocketTheme {
            PromptQueue("session", rows, true, true) { id, action, done ->
                actions += id to action
                complete = done
            }
        } }
        rule.onNodeWithTag("promptQueueToggle").performClick()
        rule.onNodeWithTag("queueEdit:two").performClick()
        rule.onNodeWithTag("queueDraft:two").performTextReplacement("草稿内容")
        rule.onNodeWithTag("promptQueueToggle").assertIsNotEnabled()
        rule.onNodeWithTag("queueSave:two").performClick()
        rule.onNodeWithTag("queueDraft:two").assertIsNotEnabled()
        rule.onNodeWithTag("queueCancel:two").assertIsNotEnabled()
        rule.onNodeWithTag("queueRemove:one").assertIsNotEnabled()
        rule.runOnIdle {
            assertEquals(listOf("two" to QueueAction.Edit("草稿内容")), actions)
            complete!!(false)
        }
        rule.onNodeWithTag("queueDraft:two").assertTextEquals("草稿内容")
        rule.onNodeWithTag("queueSave:two").assertIsEnabled()
        rule.runOnIdle { rows = listOf(QueuedPrompt("two", "Host更新"), QueuedPrompt("one", "相同内容")) }
        rule.onNodeWithTag("queueDraft:two").assertTextEquals("草稿内容")
        rule.onNodeWithTag("queueSave:two").performClick()
        rule.runOnIdle { complete!!(true) }
        rule.onNodeWithTag("queueDraft:two").assertDoesNotExist()
        rule.onNodeWithTag("promptQueueToggle").assertIsEnabled()
        rule.onNodeWithTag("queueEdit:two").performClick()
        rule.onNodeWithTag("queueDraft:two").performTextReplacement("  ")
        rule.onNodeWithTag("queueSave:two").assertIsNotEnabled()
        rule.onNodeWithTag("queueCancel:two").performClick()
        rule.onNodeWithTag("queueDraft:two").assertDoesNotExist()
    }

    @Test fun disappearingEditedRowAndSessionSwitchNeverRetargetDraftByText() {
        var session by mutableStateOf("session-a")
        var rows by mutableStateOf(listOf(QueuedPrompt("one", "相同内容"), QueuedPrompt("two", "相同内容")))
        rule.setContent { PocketTheme { PromptQueue(session, rows, true, true) { _, _, _ -> } } }
        rule.onNodeWithTag("promptQueueToggle").performClick()
        rule.onNodeWithTag("queueEdit:one").performClick()
        rule.onNodeWithTag("queueDraft:one").performTextReplacement("不要转移")
        rule.runOnIdle { rows = listOf(QueuedPrompt("two", "相同内容")) }
        rule.onNodeWithTag("queueDraft:one").assertDoesNotExist()
        rule.onNodeWithTag("queueDraft:two").assertDoesNotExist()
        rule.onNodeWithTag("queueEdit:two").performClick()
        rule.onNodeWithTag("queueSave:two").performClick()
        rule.onNodeWithTag("queueCancel:two").assertIsNotEnabled()
        rule.runOnIdle {
            session = "session-b"
            rows = listOf(QueuedPrompt("two", "相同内容"), QueuedPrompt("three", "第三条"))
        }
        rule.onNodeWithTag("promptQueueList").assertDoesNotExist()
        rule.onNodeWithTag("promptQueueToggle").assertIsEnabled().performClick()
        rule.onNodeWithTag("queueDraft:two").assertDoesNotExist()
        rule.onNodeWithTag("queueEdit:two").assertIsEnabled().performClick()
        rule.onNodeWithTag("queueDraft:two").assertTextEquals("相同内容")
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
        rule.onNodeWithTag("promptQueueList").assertDoesNotExist()
        rule.onRoot().captureRoboImage("build/screenshots/queue-composer-collapsed-en-dark.png")
        rule.onNodeWithTag("promptQueueToggle").performClick()
        rule.onNodeWithText("2 images · 1 file").assertIsDisplayed()
        rule.onNodeWithTag("queueEdit:attachments").assertIsNotEnabled()
        rule.onNodeWithTag("composerInput").assertIsDisplayed()
        rule.onRoot().captureRoboImage("build/screenshots/queue-composer-multiple-en-dark.png")
        rule.onNodeWithText("Queued follow-up 8").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("composerInput").assertIsDisplayed()
    }
}
