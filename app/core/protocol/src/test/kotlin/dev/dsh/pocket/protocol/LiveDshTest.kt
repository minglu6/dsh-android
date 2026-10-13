package dev.dsh.pocket.protocol

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.*

/**
 * Runs against a real `dsh web` + dsh-plugin-pocket when DSH_POCKET_URL / DSH_POCKET_TOKEN are set
 * (CI job `app-protocol-live`). Skipped otherwise.
 */
class LiveDshTest {
    private val url = System.getenv("DSH_POCKET_URL").orEmpty()
    private val token = System.getenv("DSH_POCKET_TOKEN").orEmpty()

    @Test fun protocolClientAgainstRealDsh() = runBlocking {
        if (url.isEmpty()) { println("SKIP: DSH_POCKET_URL not set"); return@runBlocking }
        val c = PocketClient()
        val hello = c.connect(url, Auth.Token(token))
        assertTrue("prompt" in hello.ops)
        val id = c.createSession()
        assertTrue(c.listSessions().any { it.sessionId == id })
        val cat = c.modelCatalog()
        cat.groups.firstOrNull()?.models?.firstOrNull()?.let { m -> c.selectModel(id, m.provider, m.id, m.defaultEffort) }
        val perms = c.permissionCatalog()
        assertTrue(perms.options.isNotEmpty())
        assertTrue(perms.allowsDefault(perms.defaultPreset))
        val selectedPermission = perms.defaultOptions.first { it.value == perms.defaultPreset }
        assertEquals(selectedPermission.value, c.setPermission(id, selectedPermission.value))
        assertEquals(selectedPermission.value, c.permission(id))
        assertTrue(c.presets().contains("standard"))
        c.call("sessions.rename", jsonOf("sessionId" to id, "title" to "kotlin live"))
        val firstFollow = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(10_000) { c.events.first { it["ev"].str() == "follow" } } }
        val sub = c.follow(id)
        val frame = firstFollow.await()
        assertEquals(sub, frame["subId"].str())
        val r = TranscriptReducer()
        assertTrue(r.apply(frame["frame"]))
        assertEquals("kotlin live", r.title)
        // pairing: a second, owner-less connection with a bad code is refused
        val bad = PocketClient()
        val err = assertFailsWith<PocketError> { bad.connect(url, Auth.Pair("000000", "test")) }
        assertTrue(err.code.startsWith("pair/"), err.code)
        c.cancel(id)
        c.close()
    }
}
