package dev.dsh.pocket.protocol

import kotlin.test.*

class TranscriptReducerTest {
    private fun j(s: String) = PocketJson.parseToJsonElement(s)
    private fun ev(seq: Int, type: String, data: String) = j("""{"type":"event","event":{"type":"$type","seq":$seq,"time":${1000 + seq},"data":$data}}""")

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
        r.apply(j("""{"type":"assistant-stream","frame":{"type":"chunk","attemptId":"x","revision":1,"index":0,"time":5,"chunk":{"type":"reasoning-delta","index":0,"text":"hm"}}}"""))
        r.apply(j("""{"type":"assistant-stream","frame":{"type":"chunk","attemptId":"x","revision":1,"index":1,"time":6,"chunk":{"type":"text-delta","index":1,"text":"Hel"}}}"""))
        r.apply(j("""{"type":"assistant-stream","frame":{"type":"chunk","attemptId":"x","revision":1,"index":2,"time":7,"chunk":{"type":"text-delta","index":1,"text":"lo"}}}"""))
        r.apply(j("""{"type":"assistant-stream","frame":{"type":"chunk","attemptId":"x","revision":1,"index":3,"time":8,"chunk":{"type":"tool-call-delta","index":2,"id":"c9","name":"read","argumentsDelta":"{\"p"}}}"""))
        val live = r.items()
        assertEquals("Hello", (live[1] as Item.Text).text); assertTrue((live[1] as Item.Text).live)
        assertEquals("read", (live[2] as Item.Tool).name)
        r.apply(ev(1, "assistant/message", """{"stream":[],"message":{"content":[{"type":"text","text":"Hello"}]}}"""))
        assertEquals(listOf(false), r.items().map { (it as Item.Text).live })
    }

    @Test fun abandonedStreamClearsRunning() {
        val r = TranscriptReducer()
        r.apply(j("""{"type":"snapshot","cursor":0,"records":[],"projections":{}}"""))
        r.apply(j("""{"type":"assistant-stream","frame":{"type":"start","attemptId":"a","revision":1,"startedAfterSeq":0,"turn":1,"step":1}}"""))
        assertTrue(r.running)
        r.apply(j("""{"type":"assistant-stream","frame":{"type":"end","attemptId":"a","revision":1,"outcome":{"kind":"abandoned"}}}"""))
        assertFalse(r.running)
        assertTrue(r.items().none { it is Item.Text && it.live })
    }

    @Test fun markIdleClearsRunning() {
        val r = TranscriptReducer()
        r.apply(ev(1, "turn/start", "{}"))
        assertTrue(r.running)
        r.markIdle()
        assertFalse(r.running)
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

    @Test fun hidesInjectedContextAndMarksToolErrors() {
        val r = TranscriptReducer()
        r.apply(j("""{"type":"snapshot","cursor":0,"records":[],"projections":{}}"""))
        r.apply(ev(1, "user/message", """{"role":"user","source":{"kind":"user"},"content":[{"type":"text","text":"hi"}]}"""))
        r.apply(ev(2, "user/message", """{"role":"user","source":{"kind":"runtime-context"},"content":[{"type":"text","text":"cwd"}]}"""))
        r.apply(ev(3, "tool/call", """{"callId":"c","name":"bash","arguments":"{}"}"""))
        r.apply(ev(4, "tool/result", """{"message":{"toolCallId":"c","isError":true,"content":[{"type":"text","text":"Error: rejected"}]}}"""))
        r.apply(ev(5, "assistant/message", """{"usage":{"inputTokens":100,"cacheReadTokens":900,"outputTokens":5},"message":{"content":[]}}"""))
        assertEquals(1, r.items().count { it is Item.User })
        assertEquals(ToolStatus.Error, (r.items().last() as Item.Tool).status)
        assertEquals(90, r.usage.cacheHitPercent)
    }

    /** Real event order from DeepSeek Harness desktop (Mac mini session-576f1e04): prompt sits in the inbox ~24s before user/message. */
    @Test fun firstMessageInNewSessionShowsImmediately() {
        val r = TranscriptReducer()
        r.apply(j("""{"type":"snapshot","cursor":2,"records":[],"projections":{}}"""))
        r.echo("你好")                                   // optimistic, before any server event
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
    }

    @Test fun echoSurvivesLateSnapshot() {
        val r = TranscriptReducer()
        r.echo("hi")
        r.apply(j("""{"type":"snapshot","cursor":0,"records":[],"projections":{}}"""))
        assertEquals(listOf("hi"), r.items().map { (it as Item.User).text })
        r.apply(j("""{"type":"snapshot","cursor":1,"records":[{"type":"event","event":{"type":"user/message","seq":1,"time":1,"data":{"content":[{"type":"text","text":"hi"}],"source":{"kind":"user"}}}}],"projections":{}}"""))
        assertEquals(1, r.items().size)
    }

    @Test fun pairingLink() {
        assertEquals(PairingLink("100.1.2.3", 7690, "111111"), PairingLink.parse("dshpocket://pair?h=100.1.2.3:7690&c=111111"))
        assertNull(PairingLink.parse("dshpocket://pair?n=abcdefghijklmnopqrst&c=482913"))
        assertNull(PairingLink.parse("abcdefghijklmnopqrst#000001"))
        assertNull(PairingLink.parse("dshpocket://pair?h=x:1&c=12"))
    }
}
