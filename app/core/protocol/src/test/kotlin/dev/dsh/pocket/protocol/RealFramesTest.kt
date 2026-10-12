package dev.dsh.pocket.protocol

import java.io.File
import kotlin.test.*

/** Replays follow frames captured from a real model run (plugin/test/integration/real-model.mjs). Skipped without E2E_FRAMES. */
class RealFramesTest {
    @Test fun reducerFoldsRealRun() {
        val path = System.getenv("E2E_FRAMES").orEmpty()
        if (path.isEmpty()) { println("SKIP: E2E_FRAMES not set"); return }
        val frames = PocketJson.parseToJsonElement(File(path).readText()).arr()!!
        val r = TranscriptReducer()
        var sawLiveText = false
        frames.forEach { f -> r.apply(f); if (r.items().any { it is Item.Text && it.live && it.text.isNotEmpty() }) sawLiveText = true }
        val items = r.items()
        println("items=" + items.map { it::class.simpleName } + " usage=" + r.usage)
        assertTrue(sawLiveText, "live text from deltas")
        assertEquals(3, items.count { it is Item.User })
        val tools = items.filterIsInstance<Item.Tool>()
        assertTrue(tools.any { it.name == "bash" && it.status == ToolStatus.Ok }, "approved bash ok")
        assertTrue(tools.any { it.name == "bash" && it.status == ToolStatus.Error }, "rejected bash error")
        assertTrue(items.none { it is Item.Text && it.live })
        assertFalse(r.running)
        assertEquals(3, r.usage.turns)
    }
}
