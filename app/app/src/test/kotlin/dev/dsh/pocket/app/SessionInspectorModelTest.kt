package dev.dsh.pocket.app

import dev.dsh.pocket.protocol.*
import org.junit.Assert.*
import org.junit.Test

class SessionInspectorModelTest {
    @Test fun fragmentFilterIgnoresCaseAndTreatsOnlyStarAsWildcard() {
        assertTrue(inspectorTypeMatches("assistant-stream/reasoning-delta", "DELTA"))
        assertTrue(inspectorTypeMatches("assistant-stream/reasoning-delta", " *Reasoning*DELTA* "))
        assertFalse(inspectorTypeMatches("assistant-stream/reasoning-delta", "delta*reasoning"))
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
}
