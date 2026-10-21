package dev.dsh.pocket.app

import dev.dsh.pocket.protocol.Item
import dev.dsh.pocket.protocol.ToolStatus
import dev.dsh.pocket.protocol.TurnOutlineEntry
import dev.dsh.pocket.protocol.TurnRecord
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TurnNavigatorModelTest {
    @Test fun mergesFullOutlineAndLoadedWindowUsingRealTurnNumbers() {
        val result = transcriptTurnMarks(
            listOf(ChatRow.User(Item.User("user-12", "Loaded prompt", seq = 121)),
                ChatRow.Answer(Item.Text("answer-12", "Loaded answer", false, turn = 12)),
                ChatRow.Answer(Item.Text("answer-21", "Newest answer", false, turn = 21))),
            listOf(TurnRecord(12, startSeq = 120)),
            listOf(TurnOutlineEntry(21, 210), TurnOutlineEntry(3, 30, "Older prompt", "Older answer"),
                TurnOutlineEntry(12, 120, "Outline prompt", "Outline answer")),
        )
        assertEquals(listOf(3, 12, 21), result.map { it.turn })
        assertEquals(TranscriptTurnMark(3, "Older prompt", "Older answer", seq = 30), result[0])
        assertEquals(TranscriptTurnMark(12, "Loaded prompt", "Loaded answer", "user-12", 120), result[1])
        assertEquals("answer-21", result[2].anchorKey)
    }

    @Test fun midTurnWindowKeepsOutlinePromptAndAnchorsItsFirstVisibleRow() {
        val rows = listOf(ChatRow.Tool(Item.Tool("tool-9", "call", "read", "{}", ToolStatus.Ok, turn = 9)),
            ChatRow.Answer(Item.Text("answer-9", "Window answer", false, turn = 9)))
        val mark = transcriptTurnMarks(rows, emptyList(), listOf(TurnOutlineEntry(9, 900, "Missing user", "Outline answer"))).single()
        assertEquals("tool-9", mark.anchorKey)
        assertEquals("Missing user", mark.prompt)
        assertEquals("Window answer", mark.response)
        assertEquals(900L, mark.seq)
    }

    @Test fun steeringMessagesDoNotInventTurnsOrReplaceOriginalPrompt() {
        val marks = transcriptTurnMarks(listOf(
            ChatRow.User(Item.User("initial", "Original request", seq = 102)),
            ChatRow.Answer(Item.Text("partial", "First response", false, turn = 8)),
            ChatRow.User(Item.User("steer", "Please continue", seq = 150)),
            ChatRow.Answer(Item.Text("final", "Final response", false, turn = 8)),
            ChatRow.User(Item.User("next", "Next request", seq = 202)),
        ), listOf(TurnRecord(8, startSeq = 100, endSeq = 190), TurnRecord(15, startSeq = 200)), emptyList())
        assertEquals(listOf(8, 15), marks.map { it.turn })
        assertEquals("Original request", marks[0].prompt)
        assertEquals("initial", marks[0].anchorKey)
        assertEquals("Final response", marks[0].response)
        assertEquals("next", marks[1].anchorKey)
    }

    @Test fun pendingEchoAndUnassignedUsersNeverBecomeExecutedTurns() {
        val rows = listOf(
            ChatRow.User(Item.User("before-window", "Unknown prior turn", seq = 20)),
            ChatRow.User(Item.User("pending", "Local echo", pending = true, seq = 110)),
            ChatRow.User(Item.User("no-seq", "No durable identity")),
            ChatRow.User(Item.User("between-turns", "Idle input", seq = 150)),
        )
        val marks = transcriptTurnMarks(rows, listOf(TurnRecord(7, startSeq = 100, endSeq = 130)), emptyList())
        assertEquals(listOf(TranscriptTurnMark(7, "", "", seq = 100)), marks)
        assertEquals(emptyList(), transcriptTurnMarks(rows, emptyList(), emptyList()))
    }

    @Test fun foldedProcessIsAnchorRatherThanItsInvisibleMembers() {
        val hiddenAnswer = ChatRow.Answer(Item.Text("hidden-answer", "Hidden interim answer", false, turn = 30))
        val process = ChatRow.Process("turn-30", emptyList(), emptyList(), "Analysis completed", true, false,
            turnSummary = true, turn = 30, controlledKeys = setOf(hiddenAnswer.key))
        val rows = visibleChatRows(listOf(process, hiddenAnswer))
        assertEquals(listOf("turn-30"), rows.map { it.key })
        val mark = transcriptTurnMarks(rows, listOf(TurnRecord(30, startSeq = 300)),
            listOf(TurnOutlineEntry(30, 300, "Missing prompt", "Outline answer"))).single()
        assertEquals("turn-30", mark.anchorKey)
        assertEquals("Outline answer", mark.response)
    }

    @Test fun missingPagesRemainNavigableAndDoNotAffectLoadedIdentities() {
        val marks = transcriptTurnMarks(listOf(
            ChatRow.User(Item.User("user-40", "Fortieth", seq = 402)),
            ChatRow.Reasoning(Item.Reasoning("thinking-40", "Thinking", null, false, turn = 40)),
        ), emptyList(), listOf(TurnOutlineEntry(40, 400), TurnOutlineEntry(2, 20), TurnOutlineEntry(19, 190)))
        assertEquals(listOf(2, 19, 40), marks.map { it.turn })
        assertNull(marks[0].anchorKey)
        assertNull(marks[1].anchorKey)
        assertEquals("user-40", marks[2].anchorKey)
        assertEquals("Fortieth", marks[2].prompt)
    }

    @Test fun everyVisibleNonUserRowCanAnchorItsExplicitTurn() {
        val rows = listOf(
            ChatRow.Notice(Item.Notice("notice", "Notice", turn = 11)),
            ChatRow.Retry(Item.Retry("retry", "r", 12, 1, emptyList())),
            ChatRow.TurnFailure(Item.TurnFailure("failure", "Failure", null, 13)),
            ChatRow.Deliverable(Item.Deliverable("files", emptyList(), turn = 14)),
            ChatRow.Changes(Item.Changes("changes", 15)),
        )
        val marks = transcriptTurnMarks(rows, emptyList(), emptyList())
        assertEquals(listOf(11, 12, 13, 14, 15), marks.map { it.turn })
        assertEquals(listOf("notice", "retry", "failure", "files", "changes"), marks.map { it.anchorKey })
    }

    @Test fun metadataAndRowOrderingNeverRenumberTurns() {
        val rows = listOf(ChatRow.Answer(Item.Text("later", "Later", false, turn = 99)),
            ChatRow.Answer(Item.Text("earlier", "Earlier", false, turn = 4)))
        val marks = transcriptTurnMarks(rows, listOf(TurnRecord(77, startSeq = 770), TurnRecord(4, startSeq = 40)), emptyList())
        assertEquals(listOf(4, 77, 99), marks.map { it.turn })
        assertEquals("earlier", marks[0].anchorKey)
        assertEquals(770L, marks[1].seq)
        assertNull(marks[1].anchorKey)
        assertEquals("later", marks[2].anchorKey)
    }

    @Test fun firstHumanPromptWinsOverEarlierContextRowsAndLatestAnswerWinsPreview() {
        val mark = transcriptTurnMarks(listOf(
            ChatRow.Notice(Item.Notice("context", "Notification", turn = 7)),
            ChatRow.User(Item.User("prompt", "First\nhuman\tquestion", seq = 72)),
            ChatRow.Answer(Item.Text("draft", "Earlier reasoning response", false, turn = 7)),
            ChatRow.User(Item.User("steer", "Follow-up within the same turn", seq = 75)),
            ChatRow.Answer(Item.Text("final", "Final\nanswer", false, turn = 7)),
        ), listOf(TurnRecord(7, startSeq = 70)), emptyList()).single()
        assertEquals("prompt", mark.anchorKey)
        assertEquals("First human question", mark.prompt)
        assertEquals("Final answer", mark.response)
    }
}
