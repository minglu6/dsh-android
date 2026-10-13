package dev.dsh.pocket.app

import dev.dsh.pocket.protocol.*
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Test

class SessionInspectorModelTest {
    @Test fun typeFilterMatchesDesktopContainmentNotWildcardExpressions() {
        assertTrue(inspectorTypeMatches("reasoning-delta", "DELTA"))
        assertTrue(inspectorTypeMatches("reasoning-delta", " ** Reasoning ** "))
        assertFalse(inspectorTypeMatches("reasoning-delta", "*reasoning*delta*"))
        assertFalse(inspectorTypeMatches("Item.Tool", "Item.+"))
        assertTrue(inspectorTypeMatches("anything", " *** "))
    }

    @Test fun filterKeepsRealAncestorsWithoutCountingThemAsMatches() {
        val tool = InspectorRow("tool", "Item.Tool", jsonOf())
        val step = InspectorRow("step", "ProcessStep", jsonOf(), children = listOf(tool))
        val group = InspectorRow("group", "ChatRow.Process", jsonOf(), children = listOf(step))
        val other = InspectorRow("text", "Item.Text", jsonOf())
        val visible = inspectorVisibleRows(listOf(group, other), "TOOL", emptySet())
        assertEquals(listOf("group", "step", "tool"), visible.map { it.row.key })
        assertEquals(listOf(0, 1, 2), visible.map { it.depth })
        assertEquals(1, visible.count { it.matches })
        assertEquals(listOf("group"), inspectorVisibleRows(listOf(group), "tool", setOf("group")).map { it.row.key })
    }

    @Test fun usesActualProcessStepsAndKeepsHiddenReducerItemsAtTheirRootAnchor() {
        val items = listOf(
            Item.User("u", "question"),
            Item.Context("context", "injected context"),
            Item.Reasoning("reasoning", "thinking", null, false, turn = 8, step = 2),
            Item.Tool("tool", "call", "read", "{}", ToolStatus.Ok, turn = 8, step = 2),
            Item.Text("answer", "reply", false, turn = 8, step = 2),
        )
        val state = ChatState("s", items = items, turns = listOf(TurnRecord(8, 10, 20, 1000, 2000)))
        val policy = presentationPolicyFor(TranscriptView.Detailed)
        val projected = foldChatItems(items, policy, false, state.turns).filterIsInstance<ChatRow.Process>()
        val actual = projected.single { !it.turnSummary }
        val control = projected.single { it.turnSummary }
        val rows = inspectorGroupRows(state, policy)
        assertEquals(listOf("item:u", "item:context", "group:${control.key}", "group:${actual.key}", "item:answer"),
            rows.map { it.key })
        val group = rows.single { it.key == "group:${actual.key}" }
        val controlRow = rows.single { it.key == "group:${control.key}" }
        assertEquals(8, controlRow.value["turn"].int())
        assertTrue(controlRow.children.isEmpty())
        assertEquals(control.controlledKeys.toList(),
            controlRow.value["controlledKeys"].arr().orEmpty().mapNotNull { it.str() })
        assertEquals(actual.steps.map { it.step }, group.children.map { it.value["step"].int() })
        assertEquals(actual.members.map { "item:${it.key}" }, group.children.flatMap { it.children }.map { it.key })
        assertEquals("Item.Context", rows[1].type)
        assertEquals(1, rows.count { it.key == "item:context" })
    }

    @Test fun partialWindowNeverInventsTurnOrStepForUnownedItems() {
        val orphan = Item.Tool("tool", "call", "read", "{}", ToolStatus.Running)
        val state = ChatState("s", items = listOf(orphan), hasMore = true)
        val group = inspectorGroupRows(state, presentationPolicyFor(TranscriptView.Compact)).single()
        assertEquals("ChatRow.Process", group.type)
        assertNull(group.value["turn"])
        assertNull(group.children.single().value["step"])
        assertNull(group.children.single().children.single().value["turn"])
    }

    @Test fun verboseProjectionKeepsProcessIdentityButDoesNotGroupItsPresentation() {
        val state = ChatState("s", items = listOf(Item.Tool("t", "c", "read", "{}", ToolStatus.Ok, turn = 1, step = 1)))
        val rows = inspectorGroupRows(state, presentationPolicyFor(TranscriptView.Verbose))
        assertEquals(listOf("ChatRow.Process"), rows.map { it.type })
        assertEquals(false, rows.single().value["grouped"].bool())
        assertEquals("c", rows.single().children.single().children.single().value["callId"].str())
    }

    @Test fun utcClockKeepsMillisecondsWithoutDateOrSuffix() {
        assertEquals("00:00:00.000", inspectorTime(0))
        assertEquals("23:59:59.999", inspectorTime(-1))
        assertEquals("00:00:01.234", inspectorTime(86_401_234))
        assertEquals("", inspectorTime(null))
    }

    @Test fun rawRangesRetainEndEventsAndDoNotInventMissingStarts() {
        val records = listOf(
            event(1, "step/end", """{"turn":3,"step":1}"""),
            event(2, "turn/start", """{"turn":4}"""),
            event(3, "step/start", """{"turn":4,"step":1}"""),
            event(4, "custom/event", """{"turn":4,"step":1,"payload":{"secret":"verbatim"}}"""),
            event(5, "step/end", """{"turn":4,"step":1}"""),
            event(6, "turn/end", """{"turn":4}"""),
            event(7, "outside", "{}"),
        )
        val rows = inspectorRawRows(records)
        assertEquals(listOf("event:1", "event:2", "event:7"), rows.map { it.key })
        assertEquals(listOf("event:3", "event:6"), rows[1].children.map { it.key })
        val nested = rows[1].children.first().children
        assertEquals(listOf("event:4", "event:5"), nested.map { it.key })
        assertSame(records[3], nested.first().value)
        assertEquals("4", nested.first().identity)
        assertEquals("4/1", nested.first().location)
        assertFalse(rows[1].defaultCollapsed)
    }

    @Test fun compactStreamsExpandExactTimesAndKeepIndexedBlockAncestry() {
        val record = event(8, "assistant/message", """{"turn":2,"step":3,"stream":[
            {"type":"chunk","time":100,"chunk":{"type":"block-start","index":0,"blockType":"reasoning"}},
            {"type":"chunk","time":101,"chunk":{"type":"block-start","index":1,"blockType":"text"}},
            {"type":"reasoning-chunks","index":0,"time0":102,"texts":["first"," second"],"dt":[-1]},
            {"type":"text-chunks","index":1,"time0":104,"texts":["a","b"],"dt":[0]},
            {"type":"chunk","time":105,"chunk":{"type":"block-end","index":0}},
            {"type":"tool-call-chunks","index":2,"time0":106,"id":"call","args":["{","}"],"dt":[2]},
            {"type":"tool-call-chunks","index":3,"time0":109,"id":"named","name":"read","args":["{}"],"dt":[]}
        ]}""")
        val root = inspectorRawRows(listOf(record)).single()
        val reasoning = root.children.first()
        assertSame(record, root.value)
        assertEquals("block-start", reasoning.type)
        assertEquals(listOf("2", "3", "6"), reasoning.children.map { it.identity })
        assertEquals(listOf(102L, 101L, 105L), reasoning.children.map { it.time })
        assertEquals("blockType: reasoning · first second", reasoning.summary)
        assertTrue(reasoning.defaultCollapsed)
        assertEquals(listOf("a", "b"), root.children[1].children.map { it.value["text"].str() })
        val unnamed = root.children[2]
        assertEquals("tool-call-delta", unnamed.type)
        assertEquals("{", unnamed.value["argumentsDelta"].str())
        assertFalse(unnamed.value.containsKey("name"))
        assertEquals(108L, root.children[3].time)
        assertEquals("read", root.children[4].value["name"].str())
        assertTrue(root.children.all { it.location == "2/3" })
    }

    @Test fun filterOpensDefaultClosedBlocksAndSuggestionsIncludeHiddenDescendants() {
        val delta = InspectorRow("delta", "reasoning-delta", jsonOf())
        val block = InspectorRow("block", "block-start", jsonOf(), children = listOf(delta), defaultCollapsed = true)
        val root = InspectorRow("root", "assistant/message", jsonOf(), children = listOf(block))
        val rows = listOf(root)
        assertEquals(listOf("root", "block"), inspectorVisibleRows(rows, "", emptySet()).map { it.row.key })
        assertEquals(3, inspectorVisibleRows(rows, "", emptySet(), setOf("block")).size)
        val filtered = inspectorVisibleRows(rows, "*delta*", emptySet())
        assertEquals(listOf("root", "block", "delta"), filtered.map { it.row.key })
        assertEquals(listOf(false, false, true), filtered.map { it.matches })
        assertEquals(listOf("reasoning-delta"), inspectorTypeSuggestions(rows, "delta"))
        assertEquals(listOf("assistant/message", "block-start", "reasoning-delta"), inspectorTypeSuggestions(rows, ""))
        assertEquals(2, inspectorVisibleRows(rows, "delta", setOf("block")).size)
    }

    @Test fun liveBaselineAndFramesShareOneAttemptWithoutLosingOriginalJson() {
        val baseline = obj("""{"revision":3,"activeAttempt":{"attemptId":"a","turn":9,"step":2,"stream":[
            {"type":"chunk","time":1,"chunk":{"type":"block-start","index":0,"blockType":"reasoning"}},
            {"type":"reasoning-chunks","index":0,"time0":2,"texts":["one"],"dt":[]}
        ]}}""")
        val frame = obj("""{"type":"assistant-stream","frame":{"type":"chunk","attemptId":"a","revision":3,"index":2,"time":3,
            "chunk":{"type":"reasoning-delta","index":0,"text":"two"}}}""")
        val root = inspectorRawRows(listOf(baseline, frame)).single()
        assertEquals("assistant/live", root.type)
        assertEquals("a", root.identity)
        assertEquals("9/2", root.location)
        assertNull(root.seq)
        assertEquals(3L, root.time)
        assertEquals(listOf(baseline, frame), root.value["records"].arr())
        assertEquals(listOf("1", "2"), root.children.single().children.map { it.identity })
        assertEquals("blockType: reasoning · onetwo", root.children.single().summary)
    }

    @Test fun missingBlockStartAndMalformedCompactRecordsRemainInspectable() {
        val record = event(1, "assistant/attempt", """{"stream":[
            {"type":"chunk","time":1,"chunk":{"type":"reasoning-delta","index":5,"text":"orphan"}},
            {"type":"text-chunks","index":2,"time0":3,"texts":["a","b"],"dt":[]},
            {"type":"future-record","payload":{"nested":true}}
        ]}""")
        val root = inspectorRawRows(listOf(record)).single()
        assertEquals(listOf("reasoning-delta", "text-chunks", "future-record"), root.children.map { it.type })
        assertTrue(root.children.all { it.children.isEmpty() })
        assertEquals(record["data"]["stream"].arr()!![1], root.children[1].value)
        assertSame(record, root.value)
    }

    @Test fun previewsOmitColumnMetadataButNeverAlterRawValues() {
        val delta = obj("""{"type":"tool-call-delta","index":2,"id":"c","argumentsDelta":"{ exact }","time":500}""")
        assertEquals("{ exact }", inspectorPreview(delta))
        assertEquals("index: 2 · text: answer", inspectorPreview(obj("""{"type":"text-delta","index":2,"text":"answer"}""")))
        val raw = obj("""{"type":"reasoning-delta","index":0,"text":"${"a".repeat(300)}"}""")
        assertEquals(180, inspectorPreview(raw).length)
        assertEquals(300, raw["text"].str()!!.length)
        assertEquals("body", inspectorPreview(obj("""{"key":"k","turn":1,"text":"body"}"""), setOf("key", "turn")))
    }

    @Test fun navigationUsesCallIdSequenceAndReasoningBlockAndRejectsUnknownItems() {
        val tool = Item.Tool("tool", "call", "read", "{}", ToolStatus.Ok, turn = 2, step = 3)
        val reasoning = Item.Reasoning("reason", "think", null, false, turn = 2, step = 3, seq = 5)
        val answer = Item.Text("answer", "reply", false, seq = 5, turn = 2, step = 3)
        val state = ChatState("s", items = listOf(tool, reasoning, answer))
        val rows = inspectorRawRows(listOf(
            event(4, "tool/call", """{"callId":"call","turn":2,"step":3}"""),
            event(5, "assistant/message", """{"turn":2,"step":3,"stream":[
                {"type":"chunk","time":1,"chunk":{"type":"block-start","index":0,"blockType":"reasoning"}}
            ]}"""),
        ))
        assertEquals("event:4", inspectorRowForItem(rows, tool)?.key)
        assertEquals("event:5", inspectorRowForItem(rows, answer)?.key)
        val block = inspectorRowForItem(rows, reasoning)!!
        assertEquals("block-start", block.type)
        assertEquals("reason", inspectorItemForRow(state, block))
        assertEquals("tool", inspectorItemForRow(state, rows.first()))
        assertNull(inspectorRowForItem(rows, Item.Notice("unknown", "no location")))
        assertNull(inspectorItemForRow(state, InspectorRow("missing", "unknown", jsonOf())))
        val projected = inspectorGroupRows(state, presentationPolicyFor(TranscriptView.Detailed))
        assertEquals("item:tool", inspectorRowForItem(projected, tool)?.key)
    }

    @Test fun unsafeOrNonNumericCompactTimingIsNotInvented() {
        val record = event(1, "assistant/message", """{"stream":[
            {"type":"text-chunks","index":0,"time0":9007199254740991,"texts":["a","b"],"dt":[1]},
            {"type":"text-chunks","index":0,"time0":1,"texts":["a","b"],"dt":["2"]},
            {"type":"text-chunks","index":-1,"time0":1,"texts":["a"],"dt":[]}
        ]}""")
        val root = inspectorRawRows(listOf(record)).single()
        assertEquals(List(3) { "text-chunks" }, root.children.map { it.type })
        assertEquals(record["data"]["stream"].arr(), root.children.map { it.value })
    }

    @Test fun projectedColumnsUseOnlyActualItemIdentityLocationAndTime() {
        val state = ChatState("s", items = listOf(
            Item.Text("answer", "reply", false, at = 1234, seq = 20, turn = 3, step = 2),
            Item.Notice("notice", "message", turn = 3),
        ))
        val rows = inspectorGroupRows(state, presentationPolicyFor(TranscriptView.Verbose))
        val answer = rows.first { it.key == "item:answer" }
        assertEquals("answer", answer.identity)
        assertEquals("3/2", answer.location)
        assertEquals(20L, answer.seq)
        assertEquals(1234L, answer.time)
        assertEquals("00:00:01.234", inspectorTime(answer.time))
        val notice = rows.first { it.key == "item:notice" }
        assertEquals(3, notice.value["turn"].int())
        assertEquals("3", notice.location)
        assertNull(notice.time)
    }

    private fun obj(json: String): JsonObject = PocketJson.parseToJsonElement(json) as JsonObject
    private fun event(seq: Long, type: String, data: String): JsonObject =
        obj("""{"seq":$seq,"type":"$type","time":1234,"data":$data}""")
}
