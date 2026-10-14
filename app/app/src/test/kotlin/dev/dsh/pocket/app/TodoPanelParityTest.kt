package dev.dsh.pocket.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import dev.dsh.pocket.protocol.PocketJson
import dev.dsh.pocket.protocol.TranscriptReducer
import dev.dsh.pocket.protocol.TodoItem
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-xxhdpi")
@OptIn(com.github.takahirom.roborazzi.ExperimentalRoborazziApi::class)
class TodoPanelParityTest {
    @get:Rule val rule = createComposeRule()
    private var language = AppLanguage.Zh
    private var appearance = Appearance.Light

    @Before fun setup() {
        Pocket.init(RuntimeEnvironment.getApplication())
        installHostSettingsFixture()
        language = Pocket.prefs.language
        appearance = Pocket.prefs.appearance
        Pocket.prefs.language = AppLanguage.Zh
        Pocket.prefs.appearance = Appearance.Light
    }
    @After fun restore() {
        Pocket.prefs.language = language
        Pocket.prefs.appearance = appearance
    }

    @Test fun hostTodoUpdatesChangeTheDockAndDetailsWithoutZeroCountSegments() {
        val reducer = TranscriptReducer()
        reducer.apply(PocketJson.parseToJsonElement("""{"type":"snapshot","cursor":0,"records":[],"projections":{"values":{"todos":[{"content":"核对要求","status":"in_progress"},{"content":"任务二","status":"pending"},{"content":"任务三","status":"pending"},{"content":"任务四","status":"pending"},{"content":"任务五","status":"pending"},{"content":"任务六","status":"pending"},{"content":"任务七","status":"pending"}]}}}"""))
        var todos by mutableStateOf(reducer.todos())
        rule.setContent {
            PocketTheme {
                Box(Modifier.fillMaxWidth().background(T.Bg)) {
                    TodoPanel("host:session", todos)
                }
            }
        }
        val initial = "1 进行中\u2002·\u20026 待处理"
        rule.onNodeWithText(initial, useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText("0/7").assertDoesNotExist()
        rule.onNodeWithText("核对要求").assertDoesNotExist()
        val title = rule.onNodeWithText("任务", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val summary = rule.onNodeWithText(initial, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue("Summary belongs immediately after the title", summary.left > title.right && summary.left - title.right < 40f)
        for (mode in listOf(Appearance.Light, Appearance.Dark)) {
            rule.runOnIdle { Pocket.prefs.appearance = mode }
            rule.onRoot().captureRoboImage("build/screenshots/todo-bar-${mode.name}.png",
                roborazziOptions = RoborazziOptions(taskType = RoborazziTaskType.Record))
        }
        rule.runOnIdle {
            reducer.apply(PocketJson.parseToJsonElement("""{"type":"event","event":{"seq":1,"type":"todo/write","time":1,"data":{"todos":[{"content":"核对要求","status":"completed"},{"content":"修正展示","status":"in_progress"},{"content":"回归验证","status":"pending"}]}}}"""))
            todos = reducer.todos()
            Pocket.prefs.appearance = Appearance.Light
        }
        val mixed = "1 已完成\u2002·\u20021 进行中\u2002·\u20021 待处理"
        rule.onNodeWithText(mixed, useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithContentDescription("展开任务").performClick()
        rule.onNodeWithText("核对要求").assertIsDisplayed()
        rule.onNodeWithText("修正展示").assertIsDisplayed()
        rule.onNodeWithText("回归验证").assertIsDisplayed()
        rule.onNodeWithContentDescription("收起任务").assertIsDisplayed()
        rule.onAllNodesWithText(mixed, useUnmergedTree = true).assertCountEquals(1)
        val header = rule.onNodeWithTag("todoHeader").fetchSemanticsNode().boundsInRoot
        val details = rule.onNodeWithTag("todoItems").fetchSemanticsNode().boundsInRoot
        val panel = rule.onNodeWithTag("todoPanel").fetchSemanticsNode().boundsInRoot
        assertTrue("Details expand inside the same card", details.top >= header.bottom && details.bottom <= panel.bottom)
        for (mode in listOf(Appearance.Light, Appearance.Dark)) {
            rule.runOnIdle { Pocket.prefs.appearance = mode }
            rule.onRoot().captureRoboImage("build/screenshots/todo-expanded-${mode.name}.png",
                roborazziOptions = RoborazziOptions(taskType = RoborazziTaskType.Record))
        }
        rule.runOnIdle { todos = todos.map { it.copy(status = "completed") } }
        rule.onNodeWithText("3 已完成", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText("核对要求").assertIsDisplayed()
        rule.onNodeWithContentDescription("收起任务").performClick()
        rule.onNodeWithText("核对要求").assertDoesNotExist()
        rule.onNodeWithContentDescription("展开任务").assertIsDisplayed()
        rule.runOnIdle { todos = emptyList() }
        rule.onNodeWithTag("todoPanel").assertDoesNotExist()
    }

    @Test @Config(qualifiers = "w320dp-h800dp-xxhdpi")
    fun narrowEnglishSummaryWrapsWithoutLosingStatusCounts() {
        Pocket.prefs.language = AppLanguage.En
        rule.setContent {
            PocketTheme {
                Box(Modifier.fillMaxWidth().background(T.Bg)) {
                    TodoPanel("english", listOf(TodoItem("a", "completed"), TodoItem("b", "in_progress"), TodoItem("c", "pending")))
                }
            }
        }
        val summary = rule.onNodeWithText("1 completed\u2002·\u20021 in progress\u2002·\u20021 pending", useUnmergedTree = true)
        summary.assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        summary.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertFalse("All counts must remain readable on a narrow phone", layouts.single().hasVisualOverflow)
        rule.onRoot().captureRoboImage("build/screenshots/todo-bar-narrow-en.png",
            roborazziOptions = RoborazziOptions(taskType = RoborazziTaskType.Record))
    }
    @Test fun longListScrollsInsideTheCardAndSessionSwitchResetsExpansion() {
        var session by mutableStateOf("host:one")
        val tasks = List(30) { TodoItem("Task $it", "pending") }
        rule.setContent { PocketTheme { TodoPanel(session, tasks) } }
        rule.onNodeWithContentDescription("展开任务").performClick()
        val height = rule.onNodeWithTag("todoItems").fetchSemanticsNode().boundsInRoot.height
        assertTrue("Tasks must not consume the conversation viewport", height <= with(rule.density) { 180.dp.toPx() })
        rule.onNodeWithText("Task 29").performScrollTo().assertIsDisplayed()
        rule.onNodeWithContentDescription("收起任务").assertIsDisplayed()
        rule.runOnIdle { session = "host:two" }
        rule.onNodeWithTag("todoItems").assertDoesNotExist()
        rule.onNodeWithContentDescription("展开任务").assertIsDisplayed()
    }
}
