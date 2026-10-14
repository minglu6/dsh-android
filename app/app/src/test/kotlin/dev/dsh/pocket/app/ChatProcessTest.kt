package dev.dsh.pocket.app

import dev.dsh.pocket.protocol.Item
import dev.dsh.pocket.protocol.RetryAttempt
import dev.dsh.pocket.protocol.RetryState
import dev.dsh.pocket.protocol.ToolStatus
import dev.dsh.pocket.protocol.TurnRecord
import dev.dsh.pocket.protocol.TranscriptReducer
import dev.dsh.pocket.protocol.PocketJson
import org.junit.Assert.*
import org.junit.Test

class ChatProcessTest {
    private fun thought(key: String = "r", turn: Int? = 1, step: Int? = 1, live: Boolean = false) =
        Item.Reasoning(key, "**First** paragraph\n\nLatest thought", 900_000, live, turn, step)
    private fun tool(key: String = "t", turn: Int? = 1, step: Int? = 2, status: ToolStatus = ToolStatus.Ok) =
        Item.Tool(key, "call-$key", "read", """{"path":"a.kt"}""", status,
            startedAt = 2000, endedAt = if (status == ToolStatus.Ok) 3000 else null, turn = turn, step = step)
    private fun answer(key: String = "answer", turn: Int = 1, step: Int = 3) =
        Item.Text(key, key, false, turn = turn, step = step)
    private fun completed(turn: Int = 1, reason: String = "completed") =
        TurnRecord(turn, turn * 10L, turn * 10L + 9, 1000, 7500, reason)
    private fun List<ChatRow>.groups() = filterIsInstance<ChatRow.Process>().filterNot { it.turnSummary }
    private fun List<ChatRow>.controls() = filterIsInstance<ChatRow.Process>().filter { it.turnSummary }
    private fun List<ChatRow>.itemKeys(): List<String> = flatMap { row ->
        if (row is ChatRow.Process) row.members.map { it.key } else listOf(row.key)
    }

    private fun retry(state: RetryState = RetryState.Scheduled, seq: Long = 2L) = Item.Retry(
        "retry:1:1:request", "request", 1, 1,
        listOf(RetryAttempt(seq, 1, 5, "normal", 1500L, "upstream unavailable", "HTTP_503", state)),
    )

    @Test fun retryOnlyFailureAnchorsTurnHeaderBeforeBothPersistentRowsInEveryMode() {
        val retry = retry(RetryState.Started)
        val failure = Item.TurnFailure("failure", "upstream unavailable", "HTTP_503", 1)
        for (mode in TranscriptView.entries) {
            val rows = foldChatItems(listOf(retry, failure), presentationPolicyFor(mode), false,
                listOf(completed(reason = "error")))
            assertEquals(listOf("turn-1-process", retry.key, failure.key), rows.map { it.key })
            assertTrue(rows[1] is ChatRow.Retry)
            assertTrue(rows[2] is ChatRow.TurnFailure)
            assertTrue(rows.controls().single().controlledKeys.isEmpty())
            assertEquals(rows, visibleChatRows(rows, mapOf("turn-1-process" to false)))
        }
    }

    @Test fun retryAndFailureNeverBecomeMembersOfCollapsedProcessOrTurnControls() {
        val retry = retry()
        val failure = Item.TurnFailure("failure", "unavailable", "HTTP_503", 1)
        for (mode in TranscriptView.entries) {
            val rows = foldChatItems(listOf(thought(), retry, tool(), failure, answer()),
                presentationPolicyFor(mode), false, listOf(completed()))
            assertEquals(listOf("r", retry.key, "t", failure.key, "answer"), rows.itemKeys())
            assertEquals(listOf(listOf("r"), listOf("t")), rows.groups().map { it.members.map(Item::key) })
            val control = rows.controls().single()
            assertFalse(retry.key in control.controlledKeys)
            assertFalse(failure.key in control.controlledKeys)
            val visible = visibleChatRows(rows, mapOf(control.key to false))
            assertTrue(visible.any { it.key == retry.key })
            assertTrue(visible.any { it.key == failure.key })
        }
    }

    @Test fun retryCountdownRoundsUpAndWaitsAtOneUntilHostAdvancesState() {
        assertEquals(1L, retrySeconds(-1))
        assertEquals(1L, retrySeconds(0))
        assertEquals(1L, retrySeconds(1000))
        assertEquals(2L, retrySeconds(1001))
        assertEquals(2L, retrySeconds(2000))
        assertEquals(Long.MAX_VALUE / 1000L + 1L, retrySeconds(Long.MAX_VALUE))
        assertEquals(2L, retryRemainingSeconds(1500, 0))
        assertEquals(1L, retryRemainingSeconds(1500, 500))
        assertEquals(1L, retryRemainingSeconds(1500, 9000))
        assertEquals(1L, retryRemainingSeconds(-1, Long.MAX_VALUE))
        assertEquals(2L, retryRemainingSeconds(1500, -1000))
    }

    @Test fun failureLocalizationPreservesUnknownAndRateLimitReasonsAndRetainsLegacyAliases() {
        for (en in listOf(false, true)) {
            val raw = "429: temporary request rate limit, retry later"
            for (code in listOf(null, "RATE_LIMIT", "HTTP_429", "UNRECOGNIZED")) {
                assertEquals(raw, chatFailureMessage(raw, code, en))
            }
            assertEquals(chatFailureMessage(raw, "AUTH", en), chatFailureMessage(raw, "AUTH_ERROR", en))
            assertEquals(chatFailureMessage(raw, "AUTH", en), chatFailureMessage(raw, "INVALID_API_KEY", en))
            assertEquals(chatFailureMessage(raw, "QUOTA", en), chatFailureMessage(raw, "ACCOUNT_QUOTA", en))
            assertEquals(chatFailureMessage(raw, "QUOTA", en), chatFailureMessage(raw, "QUOTA_EXCEEDED", en))
            assertNotEquals(chatFailureMessage(raw, "ACCOUNT_SIGNED_OUT", en),
                chatFailureMessage(raw, "ACCOUNT_SIGN_IN_REQUIRED", en))
        }
    }

    @Test fun invisibleAssistantWhitespaceDoesNotCreateResponseBoundaries() {
        val items = listOf(thought(), answer("empty").copy(text = " \n "),
            thought("empty-reasoning").copy(text = "\t"), tool())
        val rows = foldChatItems(items, presentationPolicyFor(), true)
        assertEquals(listOf("r", "t"), rows.groups().single().members.map { it.key })
        assertFalse(rows.groups().single().closed)
        assertTrue(rows.none { it is ChatRow.Answer })
    }
    private fun TranscriptReducer.event(seq: Int, type: String, time: Long, data: String) {
        apply(PocketJson.parseToJsonElement(
            """{"type":"event","event":{"seq":$seq,"type":"$type","time":$time,"data":$data}}""",
        ))
    }
    private fun TranscriptReducer.rows(mode: TranscriptView = TranscriptView.Standard) =
        foldChatItems(items(), presentationPolicyFor(mode), running, turnRecords())

    @Test fun standardSeparatesCompletedThinkingResponseAndCurrentToolsInOriginalOrder() {
        val items = listOf(thought(), answer("opening", step = 1), tool(status = ToolStatus.Running))
        val rows = foldChatItems(items, presentationPolicyFor(), true)
        assertEquals(listOf("r-group", "opening", "t-group"), rows.map { it.key })
        assertEquals(items.map { it.key }, rows.itemKeys())
        val (thinking, current) = rows.groups()
        assertTrue(thinking.closed)
        assertEquals("thinking", thinking.activityKind)
        assertFalse(current.closed)
        assertEquals("read", current.activityKind)
        assertEquals("a.kt", current.detail)
        assertTrue(rows.groups().all { it.grouped && !it.defaultOpen && it.durationMs == null })
        assertTrue(rows.controls().isEmpty())
    }

    @Test fun allModesRetainIdenticalGroupsAndOrderedMembersButDifferentPresentation() {
        val items = listOf(thought(live = true), tool(status = ToolStatus.Running))
        val keys = mutableListOf<List<String>>()
        for (mode in TranscriptView.entries) {
            val rows = foldChatItems(items, presentationPolicyFor(mode), true)
            keys += rows.map { it.key }
            assertEquals(items.map { it.key }, rows.itemKeys())
            val group = rows.groups().single()
            assertEquals(mode == TranscriptView.Compact || mode == TranscriptView.Standard, group.grouped)
            assertFalse(group.defaultOpen)
            assertEquals(if (mode == TranscriptView.Compact || mode == TranscriptView.Verbose) null else "a.kt", group.detail)
        }
        assertEquals(1, keys.distinct().size)
    }

    @Test fun independentInputsNoticesAndTurnChangesCloseGroupsWithoutStealingMembers() {
        val items = listOf(
            thought(), Item.User("steering", "adjust", seq = 14), tool(),
            Item.Notice("retry", "retry", turn = 1), thought("after-retry", step = 3),
            answer(), thought("auto", turn = 2), answer("auto-answer", turn = 2),
        )
        val rows = foldChatItems(items, presentationPolicyFor(), false, listOf(completed(), completed(2)))
        assertEquals(items.map { it.key }, rows.itemKeys())
        assertEquals(listOf(listOf("r"), listOf("t"), listOf("after-retry"), listOf("auto")),
            rows.groups().map { it.members.map { member -> member.key } })
        assertEquals(listOf(1, 1, 1, 2), rows.groups().map { it.turn })
        assertTrue(rows.groups().all { it.closed })
        assertFalse(rows.controls().first().canCollapse)
        assertTrue(rows.controls().first().defaultOpen)
        assertTrue(visibleChatRows(rows).any { it.key == "steering" })
        assertTrue(visibleChatRows(rows).any { it.key == "retry" })
    }

    @Test fun wholeTurnControlDoesNotOwnOrReorderItsProcessAndOnlyKeepsFinalReplyVisible() {
        val items = listOf(Item.User("u", "start"), thought(), answer("opening", step = 1), tool(), answer())
        val rows = foldChatItems(items, presentationPolicyFor(), false, listOf(completed()))
        val control = rows.controls().single()
        assertEquals(items.map { it.key }, rows.itemKeys())
        assertEquals(listOf("u", control.key, "r-group", "opening", "t-group", "answer"), rows.map { it.key })
        assertTrue(control.members.isEmpty())
        assertEquals(setOf("r-group", "opening", "t-group"), control.controlledKeys)
        assertEquals(listOf("u", control.key, "answer"), visibleChatRows(rows).map { it.key })
        assertEquals(rows, visibleChatRows(rows, mapOf(control.key to true)))
        assertTrue(rows.groups().all { !it.defaultOpen && it.grouped })
    }

    @Test fun completionAndNextInputDistinguishOuterDisclosureFromHistoricalInnerGroups() {
        val items = listOf(thought(), tool(), answer())
        for (mode in TranscriptView.entries) {
            val completion = foldChatItems(items, presentationPolicyFor(mode), false, listOf(completed()))
            val deferred = foldChatItems(items, presentationPolicyFor(mode, CollapseTiming.NextInput), false, listOf(completed()))
            assertEquals(mode == TranscriptView.Verbose, completion.controls().single().defaultOpen)
            assertTrue(deferred.controls().single().defaultOpen)
            assertEquals(mode != TranscriptView.Verbose, completion.groups().single().grouped)
            assertEquals(mode == TranscriptView.Compact || mode == TranscriptView.Standard, deferred.groups().single().grouped)
            val afterInput = foldChatItems(items + Item.User("next", "next", pending = true),
                presentationPolicyFor(mode, CollapseTiming.NextInput), false, listOf(completed()))
            assertEquals(completion.controls().single().defaultOpen, afterInput.controls().single().defaultOpen)
            assertEquals(completion.groups().single().grouped, afterInput.groups().single().grouped)
        }
    }

    @Test fun automaticNextTurnAndTextOnlyLatestTurnEndNextInputDeferral() {
        val items = listOf(thought(), answer(), answer("automatic", turn = 2))
        val rows = foldChatItems(items, presentationPolicyFor(TranscriptView.Detailed, CollapseTiming.NextInput),
            false, listOf(completed(), completed(2)))
        assertFalse(rows.controls().first().defaultOpen)
        assertTrue(rows.groups().single().grouped)
        assertFalse(rows.controls().last().canCollapse)
        assertEquals(items.map { it.key }, rows.itemKeys())
    }

    @Test fun hostFailureAndAbortPreventOuterFoldingWithoutReplacingProcessGroups() {
        for (reason in listOf("error", "aborted")) {
            val items = listOf(thought(), tool(status = ToolStatus.Error), answer())
            val rows = foldChatItems(items, presentationPolicyFor(), false, listOf(completed(reason = reason)))
            val control = rows.controls().single()
            assertTrue(control.defaultOpen)
            assertFalse(control.canCollapse)
            assertNull(control.durationMs)
            assertEquals(rows, visibleChatRows(rows, mapOf(control.key to false)))
            assertEquals(items.map { it.key }, rows.itemKeys())
            assertTrue(rows.groups().single().grouped)
        }
    }

    @Test fun durationsUseHostBoundariesOnlyAndBelongExclusivelyToTurnControl() {
        val items = listOf(thought(), tool(), answer())
        val rows = foldChatItems(items, presentationPolicyFor(), false, listOf(completed()))
        assertEquals(6500L, rows.controls().single().durationMs)
        assertTrue(rows.groups().all { it.durationMs == null })
        for (fact in listOf(completed().copy(startSeq = null, startedAt = null), completed().copy(endedAt = null))) {
            assertNull(foldChatItems(items, presentationPolicyFor(), false, listOf(fact)).controls().single().durationMs)
        }
        val openFact = completed().copy(endSeq = null, endedAt = null, endReason = null)
        assertTrue(foldChatItems(items, presentationPolicyFor(), false, listOf(openFact)).controls().isEmpty())
    }

    @Test fun emptyAndTextOnlyCompletedTurnsHaveNonInteractiveControlsAtTheirAnchors() {
        val rows = foldChatItems(listOf(answer(turn = 2)), presentationPolicyFor(), false,
            listOf(completed(), completed(2)))
        assertEquals(listOf("turn-1-process", "turn-2-process", "answer"), rows.map { it.key })
        assertTrue(rows.controls().all { !it.canCollapse && it.controlledKeys.isEmpty() })
    }

    @Test fun toolBearingLastStepIsNotMisclassifiedAsFinalAnswer() {
        val items = listOf(answer("comment", step = 1), tool(step = 1))
        val rows = foldChatItems(items, presentationPolicyFor(), false, listOf(completed()))
        assertEquals(setOf("comment", "t-group"), rows.controls().single().controlledKeys)
        assertEquals(listOf("turn-1-process"), visibleChatRows(rows).map { it.key })
    }

    @Test fun multipleFinalTextBlocksRemainVisibleButInlineReasoningIsControlled() {
        val items = listOf(tool(step = 1), thought(step = 2), answer("part-1", step = 2), answer("part-2", step = 2))
        val rows = foldChatItems(items, presentationPolicyFor(), false, listOf(completed()))
        assertEquals(listOf("turn-1-process", "part-1", "part-2"), visibleChatRows(rows).map { it.key })
        assertEquals(items.map { it.key }, rows.itemKeys())
        assertEquals(listOf(1, 2), rows.groups().single().steps.map { it.step })
    }

    @Test fun unownedAndInterleavedTurnRecordsRemainOrderedWithoutInventedIdentity() {
        val items = listOf(tool("a", turn = 1), tool("orphan", turn = null, step = null),
            tool("b", turn = 2), thought("back", turn = 1), answer())
        val rows = foldChatItems(items, presentationPolicyFor(), false, listOf(completed(), completed(2)))
        assertEquals(items.map { it.key }, rows.itemKeys())
        assertEquals(listOf(1, null, 2, 1), rows.groups().map { it.turn })
        assertNull(rows.groups()[1].steps.single().step)
        assertEquals(rows.map { it.key }.size, rows.map { it.key }.toSet().size)
    }

    @Test fun inspectorTargetsRealMembersRatherThanEmptyTurnControl() {
        val rows = foldChatItems(listOf(thought(), tool(), answer()), presentationPolicyFor(), false, listOf(completed()))
        val group = rows.groups().single()
        assertEquals(rows.indexOf(group), inspectorRowIndex(rows, "r"))
        assertEquals(rows.indexOf(group), inspectorRowIndex(rows, "t"))
        assertEquals("r", inspectorPickKey(group))
        assertEquals(-1, inspectorRowIndex(rows, "absent"))
    }

    @Test fun liveActivityUsesLatestRunningToolThenCurrentReasoningAndNeverCompletedTitle() {
        val first = tool("first", status = ToolStatus.Running).copy(startedAt = 9000,
            name = "bash", arguments = """{"command":"  echo   hello\nworld "}""")
        val last = tool("last", status = ToolStatus.Running).copy(startedAt = 1000)
        val group = foldChatItems(listOf(first, last), presentationPolicyFor(), true).groups().single()
        assertEquals("commands", group.activityKind)
        assertEquals("echo hello world", group.detail)
        val thinking = foldChatItems(listOf(thought(live = true)), presentationPolicyFor(), true).groups().single()
        assertEquals("Latest thought", thinking.detail)
        val idle = foldChatItems(listOf(tool()), presentationPolicyFor(), true).groups().single()
        assertFalse(idle.closed)
        assertEquals("thinking", idle.activityKind)
        assertEquals(liveProcessTitle(emptyList(), emptyList()), idle.title)
    }

    @Test fun liveCommitClosesOnlyThinkingSegmentAndWaitsForHostTurnEndForControl() {
        val reducer = TranscriptReducer()
        reducer.event(1, "turn/start", 1000, """{"turn":7}""")
        reducer.event(2, "step/start", 1200, """{"turn":7,"step":1}""")
        reducer.apply(PocketJson.parseToJsonElement(
            """{"type":"assistant-stream","frame":{"type":"start","attemptId":"a","turn":7,"step":1,"startedAfterSeq":2,"revision":1}}""",
        ))
        reducer.apply(PocketJson.parseToJsonElement(
            """{"type":"assistant-stream","frame":{"type":"chunk","attemptId":"a","revision":2,"index":0,"time":2000,"chunk":{"type":"reasoning-delta","index":0,"text":"thinking"}}}""",
        ))
        assertFalse(reducer.rows().groups().single().closed)
        assertFalse(reducer.rows(TranscriptView.Detailed).groups().single().grouped)
        reducer.event(3, "assistant/message", 7000, """{"turn":7,"step":1,
            "stream":[{"type":"reasoning-chunks","time0":2000,"dt":[0,1000],"texts":["thinking"]}],
            "message":{"content":[{"type":"reasoning","text":"thinking"},{"type":"text","text":"answer"}]}}""")
        assertTrue(reducer.rows().groups().single().closed)
        assertTrue(reducer.rows().controls().isEmpty())
        reducer.event(4, "turn/end", 7500, """{"turn":7,"reason":{"kind":"completed"}}""")
        assertEquals(6500L, reducer.rows().controls().single().durationMs)
        assertEquals(listOf("thinking"), reducer.rows().groups().single().reasoning.map { it.text })
        assertEquals(listOf("answer"), visibleChatRows(reducer.rows()).filterIsInstance<ChatRow.Answer>().map { it.item.text })
    }

    @Test fun historyPageUpgradesUnknownDurationWithoutChangingTurnControlIdentity() {
        val reducer = TranscriptReducer()
        reducer.apply(PocketJson.parseToJsonElement("""{"type":"snapshot","cursor":4,"hasMore":true,"projections":{},
            "records":[
                {"type":"event","event":{"seq":3,"time":7000,"type":"assistant/message","data":{"turn":7,"step":1,
                    "message":{"content":[{"type":"text","text":"answer"}]}}}},
                {"type":"event","event":{"seq":4,"time":7500,"type":"turn/end","data":{"turn":7,"reason":{"kind":"completed"}}}}
            ]}"""))
        val before = reducer.rows().controls().single()
        assertNull(before.durationMs)
        reducer.prepend(PocketJson.parseToJsonElement("""[
            {"type":"event","event":{"seq":1,"time":1000,"type":"turn/start","data":{"turn":7}}}
        ]"""), false)
        val after = reducer.rows().controls().single()
        assertEquals(before.key, after.key)
        assertEquals(6500L, after.durationMs)
        assertEquals(1, reducer.rows().filterIsInstance<ChatRow.Answer>().size)
    }
}
