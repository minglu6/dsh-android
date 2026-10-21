package dev.dsh.pocket.app

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

class TrajectoryJsonTest {
    private val colors = TrajectoryJsonColors(Color.Magenta, Color.Red, Color.Blue, Color.Black)

    @Test fun emptyContainersAndEscapedKeysRemainDistinctAndValid() {
        val source = """{"a/b":{"~key":["line\nquote\"\\",{},[]]},"a~1b":null}"""
        val root = trajectoryJsonDocument(source).root!!
        val rows = trajectoryJsonRows(root, setOf("", "/a~1b", "/a~1b/~0key"))
        assertNotNull(rows.find { it.path == "/a~01b" })
        assertEquals("line\nquote\"\\", rows.first { it.path == "/a~1b/~0key/0" }.value.jsonPrimitive.content)
        val empty = rows.filter { it.path.endsWith("/1") || it.path.endsWith("/2") }
        assertEquals(2, empty.size)
        assertTrue(empty.none { it.expandable })
        assertEquals(listOf("1: {}", "2: []"), empty.map { trajectoryJsonStyledRow(it, false, colors).text })
    }

    @Test fun invalidJsonRetainsOriginalErrorText() {
        listOf("HTTP 500: connection failed\nretry later", "Error", "01", "{broken", "[true,]").forEach { source ->
            val document = trajectoryJsonDocument(source)
            assertNull(source, document.root)
            assertEquals(source, document.source)
            assertFalse(document.depthLimited)
        }
    }

    @Test fun deeplyNestedInputUsesBoundedRawFallbackAndPreservesEveryByte() {
        val source = "[".repeat(20_000) + "0" + "]".repeat(20_000)
        val document = trajectoryJsonDocument(source)
        assertTrue(document.depthLimited)
        assertNull(document.root)
        assertEquals(source, document.source)
    }

    @Test fun maximumSupportedDepthExpandsIteratively() {
        val source = "[".repeat(TRAJECTORY_JSON_MAX_DEPTH) + "0" + "]".repeat(TRAJECTORY_JSON_MAX_DEPTH)
        val document = trajectoryJsonDocument(source)
        assertFalse(document.depthLimited)
        assertNotNull(document.root)
        val paths = (0..TRAJECTORY_JSON_MAX_DEPTH).map { "/0".repeat(it) }.toSet()
        val rows = trajectoryJsonRows(document.root!!, paths)
        assertEquals(TRAJECTORY_JSON_MAX_DEPTH * 2 + 1, rows.size)
        assertEquals("0: 0", trajectoryJsonStyledRow(
            rows.first { it.depth == TRAJECTORY_JSON_MAX_DEPTH }, false, colors).text)
    }

    @Test fun bracesAndEscapedQuotesInsideLongStringsAreNotNesting() {
        val value = "[".repeat(1000) + "\"\\\n" + "x".repeat(4000)
        val source = JsonPrimitive(value).toString()
        assertEquals(JsonPrimitive(value), trajectoryJsonDocument(source).root)
    }

    @Test fun collapsedPreviewsUseDesktopFieldElementAndDepthLimits() {
        val previews = listOf(
            """{"a":1,"b":2,"c":3,"d":4,"hidden":5}""" to "{a: 1, b: 2, c: 3, d: 4, …}",
            "[0,1,2,3,4,5]" to "[0, 1, 2, 3, 4, …]",
            """{"queries":[{"query":"not exposed until expanded"}],"limit":5}""" to "{queries: [{…}], limit: 5}",
            """{"text":"quote\"slash\\line\n"}""" to """{text: "quote\"slash\\line\n"}""",
        )
        for ((source, expected) in previews) {
            val row = TrajectoryJsonRow("", null, Json.parseToJsonElement(source), 0)
            assertEquals(expected, trajectoryJsonStyledRow(row, false, colors).text)
        }
    }

    @Test fun longParsedStringsKeepFullStyledTextAndAllCopyRepresentations() {
        val rawValues = listOf(
            "x".repeat(131_072),
            "中文🙂quote\"slash\\tab\tline\n\u0000\u2028".repeat(8_192),
        )
        for (raw in rawValues) {
            val source = JsonPrimitive(raw).toString()
            val value = trajectoryJsonDocument(source).root!!
            val row = TrajectoryJsonRow("/content", "content", value, 0, copyPath = "$.content")
            val styled = trajectoryJsonStyledRow(row, false, colors)
            assertEquals("content: $source", styled.text)
            assertEquals(
                listOf(Triple(0, 9, colors.property), Triple(9, styled.length, colors.string)),
                styled.spanStyles.map { Triple(it.start, it.end, it.item.color) },
            )
            assertEquals(raw, trajectoryJsonCopyText(value, row.copyPath, TrajectoryJsonCopyMode.Value))
            assertEquals(source, trajectoryJsonCopyText(value, row.copyPath, TrajectoryJsonCopyMode.Json))
            assertEquals("$.content", trajectoryJsonCopyText(value, row.copyPath, TrajectoryJsonCopyMode.Path))
        }
    }

    @Test fun previewStylesParsedPropertiesAndValuesWithoutReinterpretingTheirContents() {
        val name = "true 42 \"quoted\\name🙂"
        val raw = "false null -12.5e+3 \"quote\" \\\\ 中文🙂\n".repeat(4_096)
        val primitive = JsonPrimitive(raw)
        val value = buildJsonObject {
            put(name, primitive)
            put("items", buildJsonArray { add(primitive); add(Json.parseToJsonElement("-12.5e+3")); add(true); add(JsonNull) })
        }
        val parsed = trajectoryJsonDocument(value.toString()).root!!
        val styled = trajectoryJsonStyledRow(TrajectoryJsonRow("", null, parsed, 0), false, colors)
        assertEquals("{$name: $primitive, items: [$primitive, -12.5e+3, true, null]}", styled.text)
        val firstValueStart = styled.text.indexOf(primitive.toString())
        assertFalse("Desktop preview property names must not be tokenized as values",
            styled.spanStyles.any { it.start < firstValueStart })
        assertEquals(
            listOf(primitive.toString(), primitive.toString()),
            styled.spanStyles.filter { it.item.color == colors.string }.map { styled.text.substring(it.start, it.end) },
        )
        assertEquals(
            listOf("-12.5e+3", "true", "null"),
            styled.spanStyles.filter { it.item.color == colors.keyword }.map { styled.text.substring(it.start, it.end) },
        )
    }

    @Test fun copyModesPreserveValuesAndProduceDesktopFormattedAndCompactJson() {
        val raw = "quote\"slash\\line\nzero\u200Bwidth"
        val primitive = JsonPrimitive(raw)
        assertEquals(raw, trajectoryJsonCopyText(primitive, "$.text", TrajectoryJsonCopyMode.Value))
        assertEquals(primitive, Json.parseToJsonElement(
            trajectoryJsonCopyText(primitive, "$.text", TrajectoryJsonCopyMode.Json)))
        val root = Json.parseToJsonElement("""{"text":"value","count":2}""")
        assertEquals("{\n  \"text\": \"value\",\n  \"count\": 2\n}",
            trajectoryJsonCopyText(root, "$", TrajectoryJsonCopyMode.PrettyJson))
        assertEquals("""{"text":"value","count":2}""",
            trajectoryJsonCopyText(root, "$", TrajectoryJsonCopyMode.CompactJson))
    }

    @Test fun propertyPathsDistinguishArrayIndexesFromObjectKeysAndEscapeSpecialNames() {
        val root = Json.parseToJsonElement("""{"items":[{"0":{"a.b":{"quote\"key":1}}}]}""")
        val rows = trajectoryJsonRows(root, setOf("", "/items", "/items/0", "/items/0/0", "/items/0/0/a.b"))
        val leaf = rows.first { it.path == "/items/0/0/a.b/quote\"key" }
        assertEquals("""$.items[0]["0"]["a.b"]["quote\"key"]""",
            trajectoryJsonCopyText(leaf.value, leaf.copyPath, TrajectoryJsonCopyMode.Path))
        assertEquals("""$[""]""", trajectoryJsonPropertyPath("$", ""))
        assertEquals("""$["a/b"]""", trajectoryJsonPropertyPath("$", "a/b"))
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xxhdpi")
class TrajectoryJsonInteractionTest {
    @get:Rule val rule = createComposeRule()
    private val source = """{"queries":[{"query":"DSH \"JSON\" tree","recency":null}],"limit":5}"""

    @Before fun setUp() {
        Pocket.init(RuntimeEnvironment.getApplication())
        installHostSettingsFixture()
        Pocket.prefs.trajectoryJsonStringWrapping = false
    }

    private fun copiedText(): String {
        val clipboard = RuntimeEnvironment.getApplication().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        return clipboard.primaryClip!!.getItemAt(0).text.toString()
    }

    @Test fun queriesCanCollapseReexpandAndCopySemanticallyIdenticalPrettyJson() {
        rule.setContent { PocketTheme { TrajectoryJsonValue(source) } }
        rule.onNodeWithTag("trajectory-json-row:/queries/0").assertDoesNotExist()
        rule.onNodeWithTag("trajectory-json-toggle:/queries").performClick()
        rule.onNodeWithTag("trajectory-json-toggle:/queries/0").performClick()
        rule.onNodeWithTag("trajectory-json-row:/queries/0/query").assertIsDisplayed()
        rule.onNodeWithTag("trajectory-json-toggle:/queries").performClick()
        rule.onNodeWithTag("trajectory-json-row:/queries/0/query").assertDoesNotExist()
        rule.onNodeWithTag("trajectory-json-toggle:/queries").performClick()
        rule.onNodeWithTag("trajectory-json-row:/queries/0/query").assertIsDisplayed()
        rule.onNodeWithTag("trajectory-json-copy").performClick()
        rule.runOnIdle {
            assertTrue(copiedText().startsWith("{\n  \"queries\": ["))
            assertEquals(Json.parseToJsonElement(source), Json.parseToJsonElement(copiedText()))
        }
        rule.onNodeWithTag("trajectory-json-copy:/queries").performClick()
        rule.runOnIdle {
            assertEquals(Json.parseToJsonElement(source).jsonObject["queries"], Json.parseToJsonElement(copiedText()))
        }
    }

    @Test fun primitiveCopyDefaultsToRawValueAndLongPressOffersJsonAndPath() {
        rule.setContent { PocketTheme { TrajectoryJsonValue(source) } }
        rule.onNodeWithTag("trajectory-json-toggle:/queries").performClick()
        rule.onNodeWithTag("trajectory-json-toggle:/queries/0").performClick()
        val copy = rule.onNodeWithTag("trajectory-json-copy:/queries/0/query")
        copy.performClick()
        rule.runOnIdle { assertEquals("DSH \"JSON\" tree", copiedText()) }
        copy.performTouchInput { longClick() }
        rule.onNodeWithText("复制值").assertIsDisplayed()
        rule.onNodeWithText("复制格式化 JSON").assertDoesNotExist()
        rule.onNodeWithText("复制 JSON").performClick()
        rule.runOnIdle { assertEquals(JsonPrimitive("DSH \"JSON\" tree"), Json.parseToJsonElement(copiedText())) }
        copy.performTouchInput { longClick() }
        rule.onNodeWithText("复制属性路径").performClick()
        rule.runOnIdle { assertEquals("$.queries[0].query", copiedText()) }
    }

    @Test fun containerLongPressOffersPrettyCompactAndPathWithoutPrimitiveActions() {
        rule.setContent { PocketTheme { TrajectoryJsonValue(source) } }
        val copy = rule.onNodeWithTag("trajectory-json-copy:/queries")
        copy.performTouchInput { longClick() }
        rule.onNodeWithText("复制值").assertDoesNotExist()
        rule.onNodeWithText("复制 JSON").assertDoesNotExist()
        rule.onNodeWithText("复制格式化 JSON").assertIsDisplayed()
        rule.onNodeWithText("复制紧凑 JSON").performClick()
        rule.runOnIdle { assertEquals("""[{"query":"DSH \"JSON\" tree","recency":null}]""", copiedText()) }
        copy.performTouchInput { longClick() }
        rule.onNodeWithText("复制属性路径").performClick()
        rule.runOnIdle { assertEquals("$.queries", copiedText()) }
        copy.performTouchInput { longClick() }
        rule.onNodeWithText("复制格式化 JSON").performClick()
        rule.runOnIdle {
            assertTrue(copiedText().startsWith("[\n  {"))
            assertEquals(Json.parseToJsonElement(source).jsonObject["queries"], Json.parseToJsonElement(copiedText()))
        }
    }

    @Test fun switchingRecordsWithIdenticalArgumentsResetsExpansion() {
        val row = mutableStateOf(TrajRow("one", 0, TrajL.kindTool, "search", inputDetail = source))
        rule.setContent {
            PocketTheme { Box(Modifier.fillMaxSize()) { TrajectoryDetailSheet(row.value, 1, onDismiss = {}) } }
        }
        rule.onNodeWithTag("trajectory-json-toggle:/queries").performClick()
        rule.onNodeWithTag("trajectory-json-row:/queries/0").assertIsDisplayed()
        rule.runOnIdle { row.value = row.value.copy(key = "two") }
        rule.onNodeWithTag("trajectory-json-row:/queries/0").assertDoesNotExist()
        rule.onNodeWithTag("trajectory-json-toggle:/queries").assertIsDisplayed()
        rule.onNode(hasText(TrajL.tabPayload) and SemanticsMatcher.expectValue(
            androidx.compose.ui.semantics.SemanticsProperties.Role, androidx.compose.ui.semantics.Role.Tab,
        )).performClick()
        rule.onNodeWithTag("trajectory-json-toggle:/queries").performClick()
        rule.onNodeWithTag("trajectory-json-row:/queries/0").assertIsDisplayed()
        rule.onNodeWithTag("trajectory-json-copy").performClick()
        rule.runOnIdle { assertEquals(Json.parseToJsonElement(source), Json.parseToJsonElement(copiedText())) }
    }

    private fun layout(tag: String): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        rule.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        return results.single()
    }

    @Test fun longArgumentExpandsToRawTextWrapsAndCopiesWithoutEscapes() {
        val raw = "# 规则\n" + "one-long-line/".repeat(100) + "\nquote\"slash\\\t中文🙂\n末尾"
        val payload = buildJsonObject { put("content", raw); put("path", "writing-rules.md") }.toString()
        rule.setContent { PocketTheme { TrajectoryJsonValue(payload, collapsedStringLines = 12) } }
        val collapsed = layout("trajectory-json-row:/content")
        assertEquals(12, collapsed.lineCount)
        assertTrue(collapsed.hasVisualOverflow)
        rule.onNodeWithTag("trajectory-json-string-expand:/content").performClick()
        rule.onNodeWithTag("trajectory-json-string-raw:/content").assertTextEquals(raw)
        val unwrapped = layout("trajectory-json-string-raw:/content").lineCount
        assertEquals(4, unwrapped)
        rule.onNodeWithTag("trajectory-json-string-wrap:/content").performClick()
        assertTrue(layout("trajectory-json-string-raw:/content").lineCount > unwrapped)
        rule.onNodeWithTag("trajectory-json-copy:/content").performClick()
        rule.runOnIdle { assertEquals(raw, copiedText()) }
        rule.onNodeWithTag("trajectory-json-string-collapse:/content").performClick()
        rule.onNodeWithTag("trajectory-json-string-raw:/content").assertDoesNotExist()
        rule.onNodeWithTag("trajectory-json-row:/path").assertIsDisplayed()
        rule.onNodeWithTag("trajectory-json-string-expand:/content").performClick()
        assertTrue(layout("trajectory-json-string-raw:/content").lineCount > unwrapped)
        rule.runOnIdle { Pocket.prefs.trajectoryJsonStringWrapping = false }
    }

    @Test fun payloadDoesNotInventFileLinksFromContentOrChapterNumbers() {
        val raw = "参见 `writing-rules.md`、references/system-prompt.md，第 2.2、5.1 节。\n" + "正文".repeat(1000)
        val payload = buildJsonObject { put("content", raw); put("path", "docs/x.md") }.toString()
        val row = TrajRow("file", 0, TrajL.kindTool, "write_file", inputDetail = payload)
        rule.setContent {
            PocketTheme { Box(Modifier.fillMaxSize()) { TrajectoryDetailSheet(row, 1, onDismiss = {}) } }
        }
        assertEquals(3, layout("trajectory-json-row:/content").lineCount)
        listOf("writing-rules.md", "system-prompt.md", "2.2", "5.1", "x.md").forEach {
            rule.onNodeWithText(it, substring = false).assertDoesNotExist()
        }
        rule.onNode(hasText(TrajL.tabPayload) and SemanticsMatcher.expectValue(
            androidx.compose.ui.semantics.SemanticsProperties.Role, androidx.compose.ui.semantics.Role.Tab,
        )).performClick()
        assertEquals(12, layout("trajectory-json-row:/content").lineCount)
        rule.onNodeWithTag("trajectory-json-string-expand:/content").performClick()
        rule.onNodeWithTag("trajectory-json-string-raw:/content").assertTextEquals(raw)
        rule.onNodeWithTag("trajectory-json-copy:/path").performClick()
        rule.runOnIdle { assertEquals("docs/x.md", copiedText()) }
    }

    @Test fun nonJsonErrorIsReadableAndCopiesUnmodifiedText() {
        val error = "HTTP 500: connection failed\n" + "x".repeat(120)
        rule.setContent { PocketTheme { TrajectoryJsonValue(error, error = true) } }
        rule.onNodeWithText(error).assertIsDisplayed()
        rule.onNodeWithTag("trajectory-json-copy").performClick()
        rule.runOnIdle { assertEquals(error, copiedText()) }
    }
}
