package dev.dsh.pocket.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Ported from desktop `@deepseek-ai/dsh-client-ui-trajectory`:
 * - `TrajectoryTable.tsx` `parseToolSchema` / `stateOf` / `statusLabel` / `formatStartedAt`
 * - `trajectory-record.ts` `formatElapsedSeconds` / `formatDurationMillis`
 * - `trajectory-snapshot-builder.ts` `captureSchemas` via `request/header` tools
 */
class TrajectorySchemaTest {
    private fun j(s: String) = PocketJson.parseToJsonElement(s)
    private fun ev(seq: Int, type: String, data: String, time: Long = 1000L + seq) =
        j("""{"type":"event","event":{"type":"$type","seq":$seq,"time":$time,"data":$data}}""")

    @Test fun parseToolSchemaRequiresNameDescriptionParametersObject() {
        val ok = assertNotNull(parseToolSchema("""{"name":"read","description":"Read a file","parameters":{"type":"object"}}"""))
        assertEquals("read", ok.name)
        assertEquals("Read a file", ok.description)
        assertNull(parseToolSchema("{"))
        assertNull(parseToolSchema("[]"))
        assertNull(parseToolSchema("null"))
        assertNull(parseToolSchema("""{"name":"a","description":"d","parameters":[]}"""))
        assertNull(parseToolSchema("""{"name":"a","description":"d"}"""))
    }

    @Test fun formatElapsedSecondsMatchesDesktopMilliseconds() {
        assertEquals("—", formatElapsedSeconds(null))
        assertEquals("0 毫秒", formatElapsedSeconds(0.0))
        assertEquals("29 毫秒", formatElapsedSeconds(0.029))
        assertEquals("500 毫秒", formatElapsedSeconds(0.5))
        assertEquals("1,500 毫秒", formatElapsedSeconds(1.5))
        assertEquals("11 毫秒", formatElapsedSeconds(0.011))
        assertEquals("235,000 毫秒", formatElapsedSeconds(235.0))
        assertEquals("—", formatDurationMillis(Double.NaN))
    }

    @Test fun toolStateAndStatusLabelsMatchDesktop() {
        assertEquals(TrajectoryRecordState.Error, trajectoryToolState(isError = true, hasOutput = true))
        assertEquals(TrajectoryRecordState.Running, trajectoryToolState(isError = false, hasOutput = false))
        assertEquals(TrajectoryRecordState.Complete, trajectoryToolState(isError = false, hasOutput = true))
        assertEquals("失败", trajectoryStatusLabel(TrajectoryRecordState.Error))
        assertEquals("等待中", trajectoryStatusLabel(TrajectoryRecordState.Running))
        assertEquals("已完成", trajectoryStatusLabel(TrajectoryRecordState.Complete))
    }

    @Test fun formatStartedAtUsesLocalWallClock() {
        assertEquals("不可用", formatStartedAt(null))
        val label = formatStartedAt(1_760_000_000_000L)
        assertTrue(label.matches(Regex("""\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3}""")), label)
    }

    @Test fun requestHeaderToolsAttachSchemaJsonToToolCalls() {
        val r = TranscriptReducer()
        r.apply(j("""{"type":"snapshot","cursor":0,"records":[],"projections":{}}"""))
        r.apply(ev(1, "turn/start", """{"turn":1}"""))
        r.apply(ev(2, "step/start", """{"turn":1,"step":1}"""))
        r.apply(
            ev(
                3,
                "request/header",
                """{"reason":"change","header":{"config":{"provider":"test","model":"exact"},
                "tools":[{"name":"read","description":"Read a UTF-8 text file and return line-numbered content.",
                "parameters":{"type":"object","properties":{"file_path":{"type":"string"}}}}]}}""",
            ),
        )
        r.apply(ev(4, "tool/call", """{"turn":1,"step":1,"callId":"c-read","name":"read","arguments":"{\"file_path\":\"a.yml\"}"}"""))
        r.apply(ev(5, "tool/result", """{"message":{"toolCallId":"c-read","content":[{"type":"text","text":"<path>a.yml</path>"}]}}"""))
        val tool = r.items().filterIsInstance<Item.Tool>().single()
        assertEquals("read", tool.name)
        assertEquals(1, tool.turn)
        assertEquals(1, tool.step)
        val schema = assertNotNull(tool.schemaJson)
        val parsed = assertNotNull(parseToolSchema(schema))
        assertEquals("read", parsed.name)
        assertEquals("Read a UTF-8 text file and return line-numbered content.", parsed.description)
        assertTrue(schema.contains("file_path"))
        assertEquals(ToolStatus.Ok, tool.status)
    }
}
