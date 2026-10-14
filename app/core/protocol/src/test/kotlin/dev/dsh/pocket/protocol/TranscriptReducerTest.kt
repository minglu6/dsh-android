package dev.dsh.pocket.protocol

import kotlin.test.*

class TranscriptReducerTest {
    private fun j(s: String) = PocketJson.parseToJsonElement(s)
    private fun ev(seq: Int, type: String, data: String) = j("""{"type":"event","event":{"type":"$type","seq":$seq,"time":${1000 + seq},"data":$data}}""")

    private fun timedEvent(seq: Int, type: String, time: Long, data: String) =
        """{"type":"event","event":{"type":"$type","seq":$seq,"time":$time,"data":$data}}"""

    private fun start(revision: Int, id: String = "a", turn: Int = 1, step: Int = 1, afterSeq: Int = 1) =
        j("""{"type":"assistant-stream","frame":{"type":"start","revision":$revision,"attemptId":"$id","turn":$turn,"step":$step,"startedAfterSeq":$afterSeq}}""")

    private fun chunk(revision: Int, index: Int, text: String, id: String = "a") =
        j("""{"type":"assistant-stream","frame":{"type":"chunk","revision":$revision,"attemptId":"$id","index":$index,"time":100,"chunk":{"type":"text-delta","index":0,"text":"$text"}}}""")

    private fun end(revision: Int, index: Int, outcome: String, id: String = "a") =
        j("""{"type":"assistant-stream","frame":{"type":"end","revision":$revision,"attemptId":"$id","index":$index,"outcome":$outcome}}""")

    private fun opening(type: String, revision: Int = 10) = j("""{
        "type":"$type","cursor":1,"records":[${timedEvent(1, "turn/start", 1000, """{"turn":1}""")}],
        "assistantStream":{"revision":$revision,"activeAttempt":{"attemptId":"a","turn":1,"step":1,
        "startedAfterSeq":1,"nextIndex":2,
        "stream":[{"type":"text-chunks","index":0,"time0":1,"texts":["A","B"],"dt":[1]}]}}}""")

    @Test fun foldsSnapshotAndEvents() {
        val r = TranscriptReducer()
        r.apply(j("""{"type":"snapshot","cursor":2,"hasMore":false,"header":{},"projections":{"values":{"title":"问候","tokenUsage":{"uncachedInputTokens":40,"cacheReadTokens":960,"outputTokens":10}}},
            "records":[{"type":"event","event":{"type":"turn/start","seq":1,"time":1,"data":{}}},
                       {"type":"event","event":{"type":"user/message","seq":2,"time":2,"data":{"id":"m","role":"user","content":[{"type":"text","text":"hi"}]}}}]}"""))
        assertEquals("问候", r.title)
        assertEquals(96, r.usage.cacheHitPercent)
        assertTrue(r.running)
        r.apply(ev(3, "step/start", """{"turn":1,"step":1}"""))
        r.apply(ev(4, "assistant/message", """{"turn":1,"step":1,"stream":[{"type":"reasoning-chunks","time0":100,"index":0,"dt":[0,500,2500],"texts":["a","b","c"]}],
            "usage":{"inputTokens":100,"cacheReadTokens":50,"cacheWriteTokens":10,"outputTokens":20,"reasoningTokens":5},
            "message":{"source":{"provider":"deepseek","model":"deepseek-v4.1-flash"},
            "content":[{"type":"reasoning","text":"think"},{"type":"text","text":"hello"},{"type":"tool-call","id":"c1","name":"bash","arguments":"{\"cmd\":\"ls\"}"}]}}"""))
        r.apply(ev(5, "tool/call", """{"callId":"c1","name":"bash","arguments":"{\"cmd\":\"ls\"}"}"""))
        r.apply(ev(6, "approval/asked", """{"id":"a","toolName":"bash","callId":"c1"}"""))
        assertEquals(ToolStatus.AwaitingApproval, (r.items().last() as Item.Tool).status)
        r.apply(ev(7, "approval/decided", """{"id":"a","outcome":"allowed-once"}"""))
        r.apply(ev(8, "tool/result", """{"message":{"toolCallId":"c1","content":[{"type":"text","text":"a.txt"}]}}"""))
        r.apply(ev(9, "turn/end", "{}"))
        val items = r.items()
        assertEquals(listOf("User", "Reasoning", "Text", "Tool"), items.map { it::class.simpleName })
        assertEquals(3000L, (items[1] as Item.Reasoning).durationMs)
        val tool = items[3] as Item.Tool
        assertEquals(ToolStatus.Ok, tool.status); assertEquals("a.txt", tool.result)
        assertFalse(r.running)
        assertEquals(1, r.usage.turns); assertEquals(1, r.usage.steps)
        val step = r.stepRecords().single()
        assertEquals(1, step.turn); assertEquals(1, step.step)
        assertEquals(1003L, step.stepStartTime) // ev() times are 1000+seq
        assertEquals(1004L, step.messageTime)
        assertEquals("hello", step.replyText); assertEquals("think", step.reasoningText)
        assertEquals(100L, step.usage.inputTokens); assertEquals(50L, step.usage.cacheReadTokens)
        assertEquals(10L, step.usage.cacheWriteTokens); assertEquals(20L, step.usage.outputTokens)
        assertEquals(5L, step.usage.reasoningTokens)
        assertEquals("deepseek", step.provider); assertEquals("deepseek-v4.1-flash", step.model)
        assertEquals(listOf("c1"), step.toolCallIds)
        // duplicate/old seq ignored
        r.apply(ev(8, "user/message", """{"content":[{"type":"text","text":"dup"}]}"""))
        assertEquals(4, r.items().size)
    }

    @Test fun liveStreamThenCommit() {
        val r = TranscriptReducer()
        r.apply(j("""{"type":"snapshot","cursor":0,"records":[],"hasMore":false,"projections":{}}"""))
        r.apply(j("""{"type":"assistant-stream","frame":{"type":"start","attemptId":"x","revision":1,"startedAfterSeq":0,"turn":1,"step":1}}"""))
        r.apply(j("""{"type":"assistant-stream","frame":{"type":"chunk","attemptId":"x","revision":2,"index":0,"time":5,"chunk":{"type":"reasoning-delta","index":0,"text":"hm"}}}"""))
        r.apply(j("""{"type":"assistant-stream","frame":{"type":"chunk","attemptId":"x","revision":3,"index":1,"time":6,"chunk":{"type":"text-delta","index":1,"text":"Hel"}}}"""))
        r.apply(j("""{"type":"assistant-stream","frame":{"type":"chunk","attemptId":"x","revision":4,"index":2,"time":7,"chunk":{"type":"text-delta","index":1,"text":"lo"}}}"""))
        r.apply(j("""{"type":"assistant-stream","frame":{"type":"chunk","attemptId":"x","revision":5,"index":3,"time":8,"chunk":{"type":"tool-call-delta","index":2,"id":"c9","name":"read","argumentsDelta":"{\"p"}}}"""))
        val live = r.items()
        assertEquals("Hello", (live[1] as Item.Text).text); assertTrue((live[1] as Item.Text).live)
        assertEquals("read", (live[2] as Item.Tool).name)
        r.apply(ev(1, "assistant/message", """{"turn":1,"step":1,"stream":[],"message":{"content":[{"type":"text","text":"Hello"}]}}"""))
        assertEquals(listOf(false), r.items().map { (it as Item.Text).live })
    }

    @Test fun abandonedStreamLeavesTurnRunning() {
        val r = TranscriptReducer()
        r.apply(ev(1, "turn/start", """{"turn":1}"""))
        r.apply(j("""{"type":"assistant-stream","frame":{"type":"start","attemptId":"a","revision":1,"startedAfterSeq":1,"turn":1,"step":1}}"""))
        assertTrue(r.running)
        r.apply(j("""{"type":"assistant-stream","frame":{"type":"end","attemptId":"a","revision":2,"index":0,"outcome":{"kind":"abandoned"}}}"""))
        assertTrue(r.running)
        assertFalse(r.needsResync)
        assertTrue(r.items().none { it is Item.Text && it.live })
        r.apply(ev(2, "turn/end", """{"turn":1}"""))
        assertFalse(r.running)
    }

    @Test fun hostControlSetsRunningWithoutInventingTurnBoundaries() {
        val r = TranscriptReducer()
        r.setRunning(true)
        assertTrue(r.running)
        r.setRunning(false)
        assertFalse(r.running)
        assertTrue(r.turnRecords().isEmpty())
    }

    @Test fun completedTurnRejectsLateStartAndChunksButNextTurnRuns() {
        val r = TranscriptReducer()
        r.apply(ev(1, "turn/start", """{"turn":1}"""))
        r.apply(ev(2, "turn/end", """{"turn":1,"reason":{"kind":"completed"}}"""))
        assertFalse(r.apply(start(1)))
        assertFalse(r.apply(chunk(2, 0, "old")))
        assertFalse(r.running)
        assertTrue(r.items().isEmpty())
        r.apply(ev(3, "turn/start", """{"turn":2}"""))
        assertTrue(r.apply(start(3, id = "b", turn = 2, afterSeq = 3)))
        assertTrue(r.apply(chunk(4, 0, "new", id = "b")))
        assertTrue(r.running)
        assertEquals(listOf("new"), r.items().filterIsInstance<Item.Text>().map { it.text })
        assertFalse(r.needsResync)
    }

    @Test fun hostIdleRejectsLatePreviewAndHostRunningAllowsNewAttempt() {
        val r = TranscriptReducer()
        r.setRunning(true)
        r.apply(start(1))
        r.apply(chunk(2, 0, "old"))
        r.setRunning(false)
        assertFalse(r.apply(start(3, id = "late")))
        assertFalse(r.apply(chunk(4, 0, "old", id = "late")))
        assertFalse(r.running)
        assertTrue(r.items().isEmpty())
        r.setRunning(true)
        r.apply(start(5, id = "new", turn = 2))
        r.apply(chunk(6, 0, "new", id = "new"))
        assertTrue(r.running)
        assertEquals("new", r.items().filterIsInstance<Item.Text>().single().text)
    }

    @Test fun replacementAgentRevisionResetRequiresBaselineRatherThanLosingItsReply() {
        val r = TranscriptReducer()
        r.apply(j("""{"type":"snapshot","cursor":10,"records":[${timedEvent(10, "turn/start", 1000, """{"turn":1}""")}],
            "assistantStream":{"revision":100}}"""))
        assertTrue(r.apply(start(1, id = "replacement", afterSeq = 10)))
        assertTrue(r.needsResync)
        assertFalse(r.apply(chunk(2, 0, "untrusted", id = "replacement")))
        r.apply(j("""{"type":"snapshot","cursor":10,"records":[${timedEvent(10, "turn/start", 1000, """{"turn":1}""")}],
            "assistantStream":{"revision":2,"activeAttempt":{"attemptId":"replacement","turn":1,"step":1,
            "startedAfterSeq":10,"nextIndex":1,"stream":[{"type":"text-chunks","index":0,"time0":1000,"texts":["new"],"dt":[]}]}}}"""))
        assertFalse(r.needsResync)
        assertTrue(r.apply(chunk(3, 1, " reply", id = "replacement")))
        assertEquals("new reply", r.items().filterIsInstance<Item.Text>().single().text)
        assertTrue(r.running)
    }

    @Test fun revisionGapLocksAllIncrementalFramesUntilSnapshot() {
        val r = TranscriptReducer()
        r.apply(ev(1, "turn/start", """{"turn":1}"""))
        r.apply(start(1))
        r.apply(chunk(2, 0, "A"))
        assertTrue(r.apply(chunk(4, 1, "wrong")))
        assertTrue(r.needsResync)
        assertTrue(r.items().isEmpty())
        assertFalse(r.apply(chunk(3, 1, "B")))
        assertFalse(r.apply(ev(2, "turn/end", """{"turn":1}""")))
        assertTrue(r.running)
        assertEquals(1L, r.cursor)
        r.apply(opening("snapshot"))
        assertFalse(r.needsResync)
        assertTrue(r.apply(chunk(11, 2, "C")))
        assertEquals("ABC", r.items().filterIsInstance<Item.Text>().single().text)
    }

    @Test fun indexGapRecoversFromResumeNextIndexWithoutDuplicatingPrefix() {
        val r = TranscriptReducer()
        r.apply(ev(1, "turn/start", """{"turn":1}"""))
        r.apply(start(1))
        r.apply(chunk(2, 0, "A"))
        assertTrue(r.apply(chunk(3, 2, "wrong")))
        assertTrue(r.needsResync)
        assertFalse(r.apply(chunk(4, 1, "also wrong")))
        r.apply(opening("resume"))
        assertFalse(r.needsResync)
        assertTrue(r.apply(chunk(11, 2, "C")))
        assertEquals("ABC", r.items().filterIsInstance<Item.Text>().single().text)
        assertTrue(r.running)
    }

    @Test fun duplicateRevisionAndLateIndexNeverDuplicateText() {
        val r = TranscriptReducer()
        r.apply(start(1))
        r.apply(chunk(2, 0, "A"))
        assertFalse(r.apply(chunk(2, 0, "A")))
        assertFalse(r.apply(start(1)))
        assertFalse(r.apply(chunk(3, 0, "A")))
        r.apply(chunk(4, 1, "B"))
        assertEquals("AB", r.items().filterIsInstance<Item.Text>().single().text)
        assertFalse(r.running) // Stream frames alone never imply an active turn.
        assertFalse(r.needsResync)
    }

    @Test fun durableSettlementBeforeStreamEndPublishesOnceAndAllowsNextAttempt() {
        val r = TranscriptReducer()
        r.apply(ev(1, "turn/start", """{"turn":1}"""))
        r.apply(start(1))
        r.apply(chunk(2, 0, "answer"))
        r.apply(ev(2, "assistant/message", """{"turn":1,"step":1,"message":{"content":[{"type":"text","text":"answer"}]}}"""))
        assertEquals(listOf(false), r.items().filterIsInstance<Item.Text>().map { it.live })
        assertFalse(r.apply(end(3, 1, """{"kind":"committed","seq":2,"eventType":"assistant/message"}""")))
        assertFalse(r.needsResync)
        assertTrue(r.running)
        r.apply(ev(3, "step/start", """{"turn":1,"step":2}"""))
        r.apply(start(4, id = "b", step = 2, afterSeq = 3))
        assertFalse(r.apply(chunk(5, 1, "late", id = "a")))
        assertFalse(r.apply(start(6)))
        r.apply(chunk(7, 0, "next", id = "b"))
        assertEquals(listOf("answer", "next"), r.items().filterIsInstance<Item.Text>().map { it.text })
        assertFalse(r.needsResync)
    }

    @Test fun settledAttemptTailCannotResurrectPreviewBeforeEnd() {
        val r = TranscriptReducer()
        r.apply(start(1))
        r.apply(chunk(2, 0, "draft"))
        r.apply(ev(2, "assistant/message", """{"turn":1,"step":1,"message":{"content":[{"type":"text","text":"final"}]}}"""))
        assertFalse(r.apply(chunk(3, 1, "late")))
        assertFalse(r.apply(end(4, 2, """{"kind":"committed","seq":2,"eventType":"assistant/message"}""")))
        assertEquals(listOf("final"), r.items().filterIsInstance<Item.Text>().map { it.text })
        assertFalse(r.needsResync)
    }

    @Test fun unrelatedDurableStepDoesNotClearOrBorrowCurrentPreview() {
        val r = TranscriptReducer()
        r.apply(start(1, turn = 2, step = 2, afterSeq = 10))
        r.apply(chunk(2, 0, "current"))
        r.apply(ev(11, "assistant/message", """{"turn":1,"step":2,"message":{"content":[{"type":"text","text":"other turn"}]}}"""))
        r.apply(ev(12, "assistant/message", """{"turn":2,"step":1,"message":{"content":[{"type":"text","text":"other step"}]}}"""))
        assertEquals("current", r.items().filterIsInstance<Item.Text>().single { it.live }.text)
        assertTrue(r.stepRecords().all { it.firstTokenTime == null })
        assertFalse(r.needsResync)
    }

    @Test fun settlementAtOrBeforeAttemptStartCannotClearPreview() {
        val r = TranscriptReducer()
        r.apply(start(1, afterSeq = 5))
        r.apply(chunk(2, 0, "current"))
        r.apply(ev(5, "assistant/message", """{"turn":1,"step":1,"message":{"content":[{"type":"text","text":"old"}]}}"""))
        assertEquals("current", r.items().filterIsInstance<Item.Text>().single { it.live }.text)
        assertNull(r.stepRecords().single().firstTokenTime)
    }

    @Test fun failedAttemptSettlementAllowsRetryWithoutEndingTurn() {
        val r = TranscriptReducer()
        r.apply(ev(1, "turn/start", """{"turn":1}"""))
        r.apply(start(1))
        r.apply(chunk(2, 0, "discard"))
        r.apply(ev(2, "assistant/attempt", """{"turn":1,"step":1}"""))
        assertTrue(r.items().isEmpty())
        r.apply(end(3, 1, """{"kind":"committed","seq":2,"eventType":"assistant/attempt"}"""))
        r.apply(start(4, id = "retry", afterSeq = 2))
        r.apply(chunk(5, 0, "retry", id = "retry"))
        assertEquals("retry", r.items().filterIsInstance<Item.Text>().single().text)
        assertTrue(r.running)
        assertFalse(r.needsResync)
    }

    @Test fun streamEndIndexGapAndMissingSettlementRequireResync() {
        for ((index, outcome) in listOf(
            1 to """{"kind":"abandoned"}""",
            0 to """{"kind":"committed","seq":2,"eventType":"assistant/message"}""",
        )) {
            val r = TranscriptReducer()
            r.apply(start(1))
            assertTrue(r.apply(end(2, index, outcome)))
            assertTrue(r.needsResync)
            assertTrue(r.items().isEmpty())
        }
    }

    @Test fun emptyResumeBaselineRetiresPreviousPreview() {
        val r = TranscriptReducer()
        r.apply(start(1))
        r.apply(chunk(2, 0, "old"))
        r.apply(j("""{"type":"resume","cursor":1,"records":[],"assistantStream":{"revision":2}}"""))
        assertTrue(r.items().isEmpty())
        assertFalse(r.apply(chunk(3, 1, "suffix")))
        assertFalse(r.needsResync)
    }

    @Test fun authoritativeBaselineRestoresPreviewAfterHostIdle() {
        val r = TranscriptReducer()
        r.apply(start(1))
        r.apply(chunk(2, 0, "old"))
        r.setRunning(false)
        r.apply(opening("snapshot"))
        assertEquals("AB", r.items().filterIsInstance<Item.Text>().single().text)
        r.apply(chunk(11, 2, "C"))
        assertEquals("ABC", r.items().filterIsInstance<Item.Text>().single().text)
        assertTrue(r.running) // The snapshot carries a real turn/start.
        assertFalse(r.needsResync)
    }

    @Test fun previousTurnEndCannotStopNewTurnOrClearItsPreview() {
        val r = TranscriptReducer()
        r.apply(ev(1, "turn/start", """{"turn":2}"""))
        r.apply(start(1, turn = 2))
        r.apply(chunk(2, 0, "current"))
        r.apply(ev(2, "turn/end", """{"turn":1,"reason":{"kind":"completed"}}"""))
        assertTrue(r.running)
        assertEquals("current", r.items().filterIsInstance<Item.Text>().single().text)
        assertTrue(r.turnRecords().first { it.turn == 1 }.closed)
        assertFalse(r.turnRecords().first { it.turn == 2 }.closed)
    }

    @Test fun settlementCanBeFollowedByNewStartBeforeOldEnd() {
        val r = TranscriptReducer()
        r.apply(start(1))
        r.apply(chunk(2, 0, "first"))
        r.apply(ev(2, "assistant/message", """{"turn":1,"step":1,"message":{"content":[{"type":"text","text":"first"}]}}"""))
        r.apply(start(3, id = "b", step = 2, afterSeq = 2))
        r.apply(chunk(4, 0, "second", id = "b"))
        assertFalse(r.apply(end(5, 1, """{"kind":"committed","seq":2,"eventType":"assistant/message"}""")))
        assertEquals(listOf("first", "second"), r.items().filterIsInstance<Item.Text>().map { it.text })
        assertFalse(r.needsResync)
    }

    @Test fun baselineOnlyPublishesChunksBeforeItsNextIndex() {
        val r = TranscriptReducer()
        r.apply(j("""{"type":"snapshot","cursor":1,"records":[],"assistantStream":{"revision":5,
            "activeAttempt":{"attemptId":"a","turn":1,"step":1,"startedAfterSeq":1,"nextIndex":2,
            "stream":[{"type":"text-chunks","index":0,"time0":1,"texts":["A","B","C"],"dt":[1,1]},
            {"type":"chunk","time":4,"chunk":{"type":"text-delta","index":0,"text":"D"}}]}}}"""))
        assertEquals("AB", r.items().filterIsInstance<Item.Text>().single().text)
        r.apply(chunk(6, 2, "C"))
        assertEquals("ABC", r.items().filterIsInstance<Item.Text>().single().text)
        assertFalse(r.needsResync)
    }

    @Test fun resumeAppends() {
        val r = TranscriptReducer()
        r.apply(j("""{"type":"snapshot","cursor":1,"records":[{"type":"event","event":{"type":"user/message","seq":1,"time":1,"data":{"content":[{"type":"text","text":"a"}]}}}],"projections":{}}"""))
        r.apply(j("""{"type":"resume","afterSeq":1,"cursor":2,"records":[{"type":"event","event":{"type":"user/message","seq":2,"time":2,"data":{"content":[{"type":"text","text":"b"}]}}}],"projections":{}}"""))
        assertEquals(listOf("a", "b"), r.items().map { (it as Item.User).text })
    }

    @Test fun snapshotTracksHasMoreAndFirstSeq() {
        val r = TranscriptReducer()
        r.apply(j("""{"type":"snapshot","cursor":5,"hasMore":true,"records":[
            {"type":"event","event":{"type":"user/message","seq":3,"time":3,"data":{"content":[{"type":"text","text":"old"}],"source":{"kind":"user"}}}},
            {"type":"event","event":{"type":"user/message","seq":5,"time":5,"data":{"content":[{"type":"text","text":"new"}],"source":{"kind":"user"}}}}
        ],"projections":{}}"""))
        assertTrue(r.hasMore)
        assertEquals(3L, r.firstSeq)
        assertEquals(5L, r.cursor)
    }

    @Test fun prependOlderHistoryKeepsNewerMessages() {
        val r = TranscriptReducer()
        r.apply(j("""{"type":"snapshot","cursor":4,"hasMore":true,"records":[
            {"type":"event","event":{"type":"user/message","seq":3,"time":3,"data":{"content":[{"type":"text","text":"mid"}],"source":{"kind":"user"}}}},
            {"type":"event","event":{"type":"user/message","seq":4,"time":4,"data":{"content":[{"type":"text","text":"new"}],"source":{"kind":"user"}}}}
        ],"projections":{}}"""))
        assertEquals(listOf("mid", "new"), r.items().map { (it as Item.User).text })
        assertEquals(3L, r.firstSeq)

        val older = j("""[
            {"type":"event","event":{"type":"user/message","seq":1,"time":1,"data":{"content":[{"type":"text","text":"oldest"}],"source":{"kind":"user"}}}},
            {"type":"event","event":{"type":"user/message","seq":2,"time":2,"data":{"content":[{"type":"text","text":"older"}],"source":{"kind":"user"}}}}
        ]""")
        assertTrue(r.prepend(older, pageHasMore = false))
        assertEquals(listOf("oldest", "older", "mid", "new"), r.items().map { (it as Item.User).text })
        assertEquals(1L, r.firstSeq)
        assertFalse(r.hasMore)
        assertEquals(4L, r.cursor) // cursor stays at the live tip
    }

    @Test fun prependIgnoresOverlappingSeqs() {
        val r = TranscriptReducer()
        r.apply(j("""{"type":"snapshot","cursor":2,"hasMore":true,"records":[
            {"type":"event","event":{"type":"user/message","seq":2,"time":2,"data":{"content":[{"type":"text","text":"b"}],"source":{"kind":"user"}}}}
        ],"projections":{}}"""))
        val page = j("""[
            {"type":"event","event":{"type":"user/message","seq":1,"time":1,"data":{"content":[{"type":"text","text":"a"}],"source":{"kind":"user"}}}},
            {"type":"event","event":{"type":"user/message","seq":2,"time":2,"data":{"content":[{"type":"text","text":"dup"}],"source":{"kind":"user"}}}}
        ]""")
        assertTrue(r.prepend(page, pageHasMore = true))
        assertEquals(listOf("a", "b"), r.items().map { (it as Item.User).text })
        assertTrue(r.hasMore)
    }

    @Test fun prependMergesOlderStepRecords() {
        val r = TranscriptReducer()
        r.apply(j("""{"type":"snapshot","cursor":4,"hasMore":true,"records":[
            {"type":"event","event":{"type":"assistant/message","seq":4,"time":4,"data":{"turn":2,"step":1,"stream":[],"message":{"content":[{"type":"text","text":"newer"}],"source":{"provider":"p","model":"m"}},"usage":{"inputTokens":1,"outputTokens":2}}}}
        ],"projections":{}}"""))
        assertEquals(listOf("newer"), r.stepRecords().map { it.replyText })
        val older = j("""[
            {"type":"event","event":{"type":"assistant/message","seq":2,"time":2,"data":{"turn":1,"step":1,"stream":[],"message":{"content":[{"type":"text","text":"older"}],"source":{"provider":"p","model":"m"}},"usage":{"inputTokens":3,"outputTokens":4}}}}
        ]""")
        assertTrue(r.prepend(older, pageHasMore = false))
        assertEquals(listOf("older", "newer"), r.stepRecords().map { it.replyText })
        assertEquals(2L, r.firstSeq)
    }

    @Test fun keepsInjectedContextForTrajectoryAndMarksToolErrors() {
        val r = TranscriptReducer()
        r.apply(j("""{"type":"snapshot","cursor":0,"records":[],"projections":{}}"""))
        r.apply(ev(1, "user/message", """{"role":"user","source":{"kind":"user"},"content":[{"type":"text","text":"hi"}]}"""))
        r.apply(ev(2, "user/message", """{"role":"user","source":{"kind":"runtime-context"},"content":[{"type":"text","text":"cwd"}]}"""))
        r.apply(ev(3, "tool/call", """{"callId":"c","name":"bash","arguments":"{}"}"""))
        r.apply(ev(4, "tool/result", """{"message":{"toolCallId":"c","isError":true,"content":[{"type":"text","text":"Error: rejected"}]}}"""))
        r.apply(ev(5, "assistant/message", """{"usage":{"inputTokens":100,"cacheReadTokens":900,"outputTokens":5},"message":{"content":[]}}"""))
        assertEquals(1, r.items().count { it is Item.User })
        assertEquals(1, r.items().count { it is Item.Context && (it as Item.Context).sourceKind == "runtime-context" })
        assertEquals(ToolStatus.Error, (r.items().last() as Item.Tool).status)
        assertEquals(90, r.usage.cacheHitPercent)
    }

    /** Real event order from DeepSeek Harness desktop (Mac mini session-576f1e04): prompt sits in the inbox ~24s before user/message. */
    @Test fun firstMessageInNewSessionShowsImmediately() {
        val r = TranscriptReducer()
        r.apply(j("""{"type":"snapshot","cursor":2,"records":[],"projections":{}}"""))
        r.echo("你好", requestId = "R1")                 // optimistic, before any server event
        assertEquals(listOf(true), r.items().map { (it as Item.User).pending })
        r.apply(ev(3, "agent/inbox/spliced", """{"target":"next-turn","start":0,"inserted":[{"content":[{"type":"text","text":"你好"}],"source":{"kind":"user","rpcId":"R1"},"role":"user"}]}"""))
        r.apply(ev(4, "turn/start", """{"turn":1}"""))
        r.apply(ev(5, "agent/inbox/spliced", """{"target":"next-turn","start":0,"removedCount":1,"inserted":[]}"""))
        assertEquals(1, r.items().size)
        r.apply(ev(8, "user/message", """{"content":[{"type":"text","text":"你好"}],"source":{"kind":"user","rpcId":"R1"},"role":"user","id":"x"}"""))
        r.apply(ev(9, "user/message", """{"content":[{"type":"text","text":"Current runtime context."}],"source":{"kind":"runtime-context"},"role":"user"}"""))
        r.apply(ev(11, "user/message", """{"id":"h","role":"user","content":[{"type":"text","text":"<hindsight_knowledge>\nmemory"}]}"""))
        val users = r.items().filterIsInstance<Item.User>()
        assertEquals(listOf("你好"), users.map { it.text })
        assertFalse(users.single().pending)
        val contexts = r.items().filterIsInstance<Item.Context>()
        assertEquals(2, contexts.size)
        assertEquals("runtime-context", contexts[0].sourceKind)
        assertTrue(contexts[1].text.startsWith("<hindsight_knowledge>"))
    }

    @Test fun systemMessageBecomesInitialThenUpdatedLabels() {
        val r = TranscriptReducer()
        r.apply(j("""{"type":"snapshot","cursor":0,"records":[],"projections":{}}"""))
        r.apply(ev(1, "system/message", """{"message":{"content":[{"type":"text","text":"You are helpful."}],"role":"system"}}"""))
        r.apply(ev(2, "system/message", """{"message":{"content":[{"type":"text","text":"Updated prompt."}],"role":"system"}}"""))
        val systems = r.items().filterIsInstance<Item.System>()
        assertEquals(listOf("初始系统提示词", "系统提示词已更新"), systems.map { it.label })
        assertEquals("You are helpful.", systems[0].text)
    }

    @Test fun echoSurvivesLateSnapshot() {
        val r = TranscriptReducer()
        r.echo("hi", requestId = "R1")
        r.apply(j("""{"type":"snapshot","cursor":0,"records":[],"projections":{}}"""))
        assertEquals(listOf("hi"), r.items().map { (it as Item.User).text })
        r.apply(j("""{"type":"snapshot","cursor":1,"records":[{"type":"event","event":{"type":"user/message","seq":1,"time":1,"data":{"content":[{"type":"text","text":"hi"}],"source":{"kind":"user","rpcId":"R1"}}}}],"projections":{}}"""))
        assertEquals(1, r.items().size)
    }

    @Test fun turnBoundariesUseHostIdentityAcrossResumeAndMultipleSteps() {
        val r = TranscriptReducer()
        val start = timedEvent(20, "turn/start", 1000, """{"turn":9}""")
        r.apply(j("""{"type":"snapshot","cursor":20,"records":[$start],"projections":{}}"""))
        r.apply(ev(21, "step/start", """{"turn":9,"step":1}"""))
        r.apply(ev(22, "assistant/message", """{"turn":9,"step":1,"message":{"content":[
            {"type":"reasoning","text":"first"},{"type":"tool-call","id":"a","name":"read","arguments":"{}"},
            {"type":"reasoning","text":"after call"}]}}"""))
        r.apply(ev(23, "step/start", """{"turn":9,"step":2}"""))
        r.apply(ev(24, "assistant/message", """{"turn":9,"step":2,"message":{"content":[{"type":"text","text":"done"}]}}"""))
        val end = timedEvent(25, "turn/end", 7500, """{"turn":9,"reason":{"kind":"completed"}}""")
        r.apply(j("""{"type":"resume","cursor":25,"records":[$start,$end],"projections":{}}"""))
        val turn = r.turnRecords().single()
        assertEquals(9, turn.turn)
        assertEquals(6500L, turn.durationMs)
        assertEquals(listOf(9, 9), r.stepRecords().map { it.turn })
        assertEquals(listOf(9, 9), r.items().filterIsInstance<Item.Reasoning>().map { it.turn })
        assertEquals(listOf(1, 1), r.items().filterIsInstance<Item.Reasoning>().map { it.step })
        assertEquals(9, r.items().filterIsInstance<Item.Text>().single().turn)
        assertEquals(2, r.items().filterIsInstance<Item.Text>().single().step)
        assertEquals(listOf("Reasoning", "Tool", "Reasoning", "Text"), r.items().map { it::class.simpleName })
    }

    @Test fun boundaryOnlyPrependCompletesDurationWithoutReplacingLiveOwnership() {
        val r = TranscriptReducer()
        val oldMessage = timedEvent(30, "assistant/message", 7000,
            """{"turn":9,"step":2,"message":{"content":[{"type":"text","text":"old"}]}}""")
        val oldEnd = timedEvent(31, "turn/end", 7500, """{"turn":9,"reason":{"kind":"completed"}}""")
        val newStart = timedEvent(32, "turn/start", 8000, """{"turn":10}""")
        r.apply(j("""{"type":"snapshot","cursor":32,"hasMore":true,
            "records":[$oldMessage,$oldEnd,$newStart],"projections":{},
            "assistantStream":{"revision":2,"activeAttempt":{"attemptId":"live","turn":10,"step":1,"startedAfterSeq":32,"nextIndex":1,
            "stream":[{"type":"reasoning-chunks","index":0,"time0":9000,"texts":["live"],"dt":[0]}]}}}"""))
        assertNull(r.turnRecords().first { it.turn == 9 }.durationMs)
        val page = j("""[${timedEvent(29, "turn/start", 1000, """{"turn":9}""")}]""")
        assertTrue(r.prepend(page, false))
        assertEquals(6500L, r.turnRecords().first { it.turn == 9 }.durationMs)
        assertEquals(10, r.items().filterIsInstance<Item.Reasoning>().single().turn)
        assertTrue(r.running)
        assertEquals(32L, r.cursor)
        assertEquals(29L, r.firstSeq)
        assertFalse(r.prepend(page, false))
        r.apply(j(timedEvent(33, "assistant/message", 14_000,
            """{"turn":10,"step":1,"message":{"content":[{"type":"text","text":"new"}]}}""")))
        r.apply(j(timedEvent(34, "turn/end", 15_000, """{"turn":10,"reason":{"kind":"completed"}}""")))
        assertEquals(listOf(6500L, 7000L), r.turnRecords().map { it.durationMs })
        assertEquals(listOf(9, 10), r.items().filterIsInstance<Item.Text>().map { it.turn })
    }

    @Test fun tailSnapshotPreservesKnownBoundaryOnlyForPresentTurns() {
        val r = TranscriptReducer()
        r.apply(j(timedEvent(1, "turn/start", 1000, """{"turn":1}""")))
        val end = timedEvent(3, "turn/end", 7500, """{"turn":1,"reason":{"kind":"completed"}}""")
        r.apply(j("""{"type":"snapshot","cursor":3,"records":[$end],"projections":{}}"""))
        assertEquals(6500L, r.turnRecords().single().durationMs)
        val other = timedEvent(4, "turn/start", 10_000, """{"turn":2}""")
        r.apply(j("""{"type":"snapshot","cursor":4,"records":[$other],"projections":{}}"""))
        assertEquals(listOf(2), r.turnRecords().map { it.turn })
        assertNull(r.turnRecords().single().durationMs)
    }

    @Test fun missingEndAndIdleNeverCreateACompletionClock() {
        val r = TranscriptReducer()
        r.apply(j(timedEvent(1, "turn/start", 1000, """{"turn":3}""")))
        r.setRunning(false)
        assertFalse(r.turnRecords().single().closed)
        assertNull(r.turnRecords().single().durationMs)
        // A later unrelated end cannot borrow turn 3's start.
        r.apply(j(timedEvent(2, "turn/end", 7500, """{"turn":4,"reason":{"kind":"completed"}}""")))
        assertTrue(r.turnRecords().last().closed)
        assertNull(r.turnRecords().last().durationMs)
    }

    @Test fun streamBaselineAndCommitRetainHostStepCoordinates() {
        val r = TranscriptReducer()
        r.apply(j("""{"type":"snapshot","cursor":5,"records":[],"projections":{},
            "assistantStream":{"revision":2,"activeAttempt":{"attemptId":"a","turn":12,"step":3,"startedAfterSeq":5,"nextIndex":1,
            "stream":[{"type":"text-chunks","index":0,"time0":9000,"texts":["live"],"dt":[0]}]}}}"""))
        assertFalse(r.running) // A model preview is not a Host turn-status signal.
        assertEquals(12, r.items().filterIsInstance<Item.Text>().single().turn)
        assertEquals(3, r.items().filterIsInstance<Item.Text>().single().step)
        r.apply(ev(6, "assistant/message", """{"turn":12,"step":3,"message":{"content":[{"type":"text","text":"settled"}]}}"""))
        assertEquals(12, r.items().filterIsInstance<Item.Text>().single().turn)
        assertEquals(3, r.items().filterIsInstance<Item.Text>().single().step)
        assertNull(r.turnRecords().single().startedAt)
    }

    @Test fun replacementSnapshotCannotImportAnEndBeyondItsCursor() {
        val r = TranscriptReducer()
        val start = timedEvent(1, "turn/start", 1000, """{"turn":1}""")
        r.apply(j(start))
        r.apply(j(timedEvent(3, "turn/end", 7500, """{"turn":1,"reason":{"kind":"completed"}}""")))
        r.apply(j("""{"type":"snapshot","cursor":1,"records":[$start],"projections":{}}"""))
        assertTrue(r.running)
        assertFalse(r.turnRecords().single().closed)
        assertNull(r.turnRecords().single().durationMs)
    }

    @Test fun pairingLink() {
        assertEquals(PairingLink("100.1.2.3", 7690, "111111"), PairingLink.parse("dshpocket://pair?h=100.1.2.3:7690&c=111111"))
        assertNull(PairingLink.parse("dshpocket://pair?n=abcdefghijklmnopqrst&c=482913"))
        assertNull(PairingLink.parse("abcdefghijklmnopqrst#000001"))
        assertNull(PairingLink.parse("dshpocket://pair?h=x:1&c=12"))
    }
}
