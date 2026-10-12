package dev.dsh.pocket.protocol

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.test.*

/** LOCAL ONLY (needs a model key in the dsh env): first message in a brand-new session, the app's exact op order. */
class RealPromptTest {
    @Test fun firstMessageInNewSession() = runBlocking {
        val url = System.getenv("DSH_POCKET_URL").orEmpty()
        if (url.isEmpty() || System.getenv("DSH_E2E_PROMPT") != "1") { println("SKIP: DSH_E2E_PROMPT"); return@runBlocking }
        val c = PocketClient()
        c.connect(url, Auth.Token(System.getenv("DSH_POCKET_TOKEN")!!))
        val frames = java.util.Collections.synchronizedList(mutableListOf<kotlinx.serialization.json.JsonObject>())
        val job = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) { c.events.collect { frames += it } }
        val id = c.createSession()
        val sub = c.follow(id)
        c.prompt(id, "只回复两个字：你好", "queue")
        withTimeout(120_000) { while (frames.none { it["ev"].str() == "follow" && it["frame"]["type"].str() == "event" && it["frame"]["event"]["type"].str() == "turn/end" }) delay(200) }
        val mine = frames.filter { it["ev"].str() == "follow" && it["subId"].str() == sub }
        System.getenv("E2E_FRAMES_OUT")?.takeIf { it.isNotEmpty() }?.let { java.io.File(it).writeText(kotlinx.serialization.json.JsonArray(mine.map { m -> m["frame"]!! }).toString()) }
        val r = TranscriptReducer(); mine.forEach { r.apply(it["frame"]) }
        println("items=" + r.items().map { it::class.simpleName + ":" + (it as? Item.User)?.text } + " usage=" + r.usage + " title=" + r.title)
        mine.filter { it["frame"]["event"]["type"].str() == "user/message" }.forEach { println("USERMSG " + it["frame"].toString().take(300)) }
        job.cancel(); c.close()
        assertEquals(1, r.items().count { it is Item.User })
    }
}
