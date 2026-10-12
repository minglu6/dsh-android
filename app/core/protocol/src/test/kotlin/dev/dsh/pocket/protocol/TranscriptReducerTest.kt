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
        r.apply(ev(3, "assistant/message", """{"turn":1,"step":1,"stream":[{"type":"reasoning-chunks","time0":100,"index":0,"dt":[0,500,2500],"texts":["a","b","c"]}],
            "message":{"content":[{"type":"reasoning","text":"think"},{"type":"text","text":"hello"},{"type":"tool-call","id":"c1","name":"bash","arguments":"{\"cmd\":\"ls\"}"}]}}"""))
        r.apply(ev(4, "tool/call", """{"callId":"c1","name":"bash","arguments":"{\"cmd\":\"ls\"}"}"""))
        r.apply(ev(5, "approval/asked", """{"id":"a","toolName":"bash","callId":"c1"}"""))
        assertEquals(ToolStatus.AwaitingApproval, (r.items().last() as Item.Tool).status)
        r.apply(ev(6, "approval/decided", """{"id":"a","outcome":"allowed-once"}"""))
        r.apply(ev(7, "tool/result", """{"message":{"toolCallId":"c1","content":[{"type":"text","text":"a.txt"}]}}"""))
        r.apply(ev(8, "turn/end", "{}"))
        val items = r.items()
        assertEquals(listOf("User", "Reasoning", "Text", "Tool"), items.map { it::class.simpleName })
        assertEquals(3000L, (items[1] as Item.Reasoning).durationMs)
        val tool = items[3] as Item.Tool
        assertEquals(ToolStatus.Ok, tool.status); assertEquals("a.txt", tool.result)
        assertFalse(r.running)
        assertEquals(1, r.usage.turns); assertEquals(1, r.usage.steps)
        // duplicate/old seq ignored
        r.apply(ev(7, "user/message", """{"content":[{"type":"text","text":"dup"}]}"""))
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
        assertEquals(PairingLink.Iroh("abcdefghijklmnopqrst", "482913"), PairingLink.parse("dshpocket://pair?n=abcdefghijklmnopqrst&c=482913"))
        assertEquals(PairingLink.Iroh("abcdefghijklmnopqrst", "000001"), PairingLink.parse("abcdefghijklmnopqrst#000001"))
        assertEquals(PairingLink.Tailscale("100.1.2.3", 7690, "111111"), PairingLink.parse("dshpocket://pair?h=100.1.2.3:7690&c=111111"))
        assertNull(PairingLink.parse("dshpocket://pair?h=x:1&c=12"))
    }
}
