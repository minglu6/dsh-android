package dev.dsh.pocket.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Reducer coverage for desktop trajectory kinds newly ported (compaction / PTC / interrupt). */
class TrajectoryKindsTest {
    private fun j(s: String) = PocketJson.parseToJsonElement(s)
    private fun ev(seq: Int, type: String, data: String, time: Long) =
        j("""{"type":"event","event":{"type":"$type","seq":$seq,"time":$time,"data":$data}}""")

    private fun snap(): TranscriptReducer {
        val r = TranscriptReducer()
        r.apply(j("""{"type":"snapshot","cursor":0,"records":[],"hasMore":false,"projections":{}}"""))
        return r
    }

    @Test fun compactionLifecycle() {
        val r = snap()
        r.apply(ev(1, "compaction/start", """{"compactionId":"c1","turn":1}""", 100))
        val running = r.items().filterIsInstance<Item.Compacted>().single()
        assertEquals(CompactionStatus.Running, running.status)
        r.apply(ev(2, "compaction/summary", """{"compactionId":"c1","summary":"Kept goals."}""", 150))
        r.apply(ev(3, "compaction/end", """{"compactionId":"c1"}""", 200))
        val done = r.items().filterIsInstance<Item.Compacted>().single()
        assertEquals(CompactionStatus.Ok, done.status)
        assertEquals("Kept goals.", done.summary)
        assertEquals(100L, done.startedAt)
        assertEquals(200L, done.endedAt)
    }

    @Test fun compactionInterrupted() {
        val r = snap()
        r.apply(ev(1, "compaction/start", """{"compactionId":"c1"}""", 100))
        r.apply(ev(2, "compaction/end", """{"compactionId":"c1","error":{"code":"interrupted","reason":"interrupted"}}""", 120))
        val done = r.items().filterIsInstance<Item.Compacted>().single()
        assertEquals(CompactionStatus.Error, done.status)
        assertTrue(done.interrupted)
    }

    @Test fun ptcDispatchCreatesSubtool() {
        val r = snap()
        r.apply(ev(1, "tool/call", """{"callId":"root","name":"run_code","arguments":"{\"code\":\"x\"}","turn":1,"step":1}""", 100))
        r.apply(ev(2, "tool/ptc-dispatch-start", """{"rootCallId":"root","parentCallId":"root","subCallId":"root:ptc:1","name":"read","arguments":{"path":"a"}}""", 110))
        r.apply(ev(3, "tool/ptc-dispatch", """{"rootCallId":"root","parentCallId":"root","subCallId":"root:ptc:1","name":"read","arguments":{"path":"a"},"isError":false,"content":[{"type":"text","text":"ok"}]}""", 120))
        val tools = r.items().filterIsInstance<Item.Tool>()
        assertEquals(2, tools.size)
        val sub = tools.single { it.callId == "root:ptc:1" }
        assertEquals("root", sub.parentCallId)
        assertEquals(ToolStatus.Ok, sub.status)
        assertEquals("ok", sub.result)
    }

    @Test fun interruptedAssistantHasNoNotice() {
        val r = snap()
        r.apply(ev(1, "user/message", """{"content":[{"type":"text","text":"hi"}],"source":{"kind":"user"},"role":"user"}""", 1))
        r.apply(ev(2, "step/start", """{"turn":1,"step":1}""", 2))
        r.apply(ev(3, "assistant/message", """{"turn":1,"step":1,"interrupted":true,"stream":[],"usage":{},"message":{"content":[{"type":"text","text":"p"}]}}""", 3))
        assertTrue(r.items().none { it is Item.Notice })
        assertTrue(r.stepRecords().single().interrupted)
    }

    @Test fun systemPromptKeepsPreviousAndToolsCatalog() {
        val r = snap()
        r.apply(ev(1, "request/header", """{"header":{"tools":[{"name":"read","description":"R","parameters":{"type":"object"}}]}}""", 10))
        r.apply(ev(2, "system/message", """{"message":{"content":[{"type":"text","text":"prompt v1"}]}}""", 20))
        r.apply(ev(3, "request/header", """{"header":{"tools":[{"name":"read","description":"R","parameters":{"type":"object"}},{"name":"bash","description":"B","parameters":{"type":"object"}}]}}""", 30))
        r.apply(ev(4, "system/message", """{"message":{"content":[{"type":"text","text":"prompt v2"}]}}""", 40))
        val systems = r.items().filterIsInstance<Item.System>()
        assertEquals(2, systems.size)
        assertEquals("初始系统提示词", systems[0].label)
        assertEquals("系统提示词已更新", systems[1].label)
        assertEquals("prompt v1", systems[1].previousText)
        assertNotNull(systems[1].toolsJson)
        assertNotNull(systems[1].previousToolsJson)
    }

    @Test fun contextCapturesMessageSource() {
        val r = snap()
        r.apply(ev(1, "developer/message", """{"message":{"content":[{"type":"text","text":"<system-reminder>x"}],"source":{"kind":"plugin","plugin":"memory"}}}""", 10))
        val ctx = r.items().filterIsInstance<Item.Context>().single()
        assertNotNull(ctx.messageSourceJson)
        assertTrue(ctx.messageSourceJson!!.contains("plugin"))
    }

    @Test fun firstTokenTimeFromStream() {
        assertNull(TranscriptReducer.assistantStreamFirstTokenTime(null))
        val t = TranscriptReducer.assistantStreamFirstTokenTime(
            j("""[{"type":"reasoning-chunks","time0":100,"texts":["a"]},{"type":"text-chunks","time0":250,"texts":["hi"]}]"""),
        )
        assertEquals(250L, t)
    }
}
