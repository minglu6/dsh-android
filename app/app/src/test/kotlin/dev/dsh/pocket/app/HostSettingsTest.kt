package dev.dsh.pocket.app

import dev.dsh.pocket.protocol.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class HostSettingsTest {
    private fun snapshot(mode: String, revision: Int = 7, writable: Boolean = true) =
        jsonOf("writable" to writable, "namespaces" to listOf(jsonOf("ns" to "ui-chat", "revision" to revision,
            "value" to jsonOf("transcriptView" to mode))))
    private fun mirror() = HostSettings(CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))

    @Test fun savesWithObservedRevisionAndOnlyAdoptsHostReadBack() = runBlocking {
        val state = mirror()
        val commit = CompletableDeferred<JsonElement>()
        var host: JsonElement = snapshot("standard")
        var calls = 0
        state.bind({ host }, { ns, ops, revision ->
            calls++
            assertEquals("ui-chat", ns)
            assertEquals(7, revision)
            assertEquals("transcriptView", ops.single()["path"].arr()!!.single().str())
            assertEquals("compact", ops.single()["value"].str())
            commit.await()
        })
        state.set(SharedSetting.Transcript, JsonPrimitive("compact"))
        assertTrue(state.saving)
        assertEquals("standard", state.value(SharedSetting.Transcript).str())
        host = snapshot("compact", 8)
        commit.complete(JsonObject(emptyMap()))
        assertFalse(state.saving)
        assertNull(state.error)
        assertEquals("compact", state.value(SharedSetting.Transcript).str())
        assertEquals(1, calls)
    }

    @Test fun conflictRefreshesHostWithoutRetryingOrApplyingDraft() {
        val state = mirror()
        var host: JsonElement = snapshot("standard")
        var writes = 0
        state.bind({ host }, { _, _, _ -> writes++; host = snapshot("verbose", 8); error("settings/conflict") })
        state.set(SharedSetting.Transcript, JsonPrimitive("compact"))
        assertEquals(1, writes)
        assertEquals("verbose", state.value(SharedSetting.Transcript).str())
        assertEquals("settings/conflict", state.error)
        assertFalse(state.saving)
    }

    @Test fun oldHostReadAndWriteCannotLeakAcrossSwitch() = runBlocking {
        val state = mirror()
        val oldRead = CompletableDeferred<JsonElement>()
        var oldWrites = 0
        state.bind({ oldRead.await() }, { _, _, _ -> oldWrites++; JsonNull })
        state.clear()
        assertNull(state.snapshot)
        state.set(SharedSetting.Transcript, JsonPrimitive("compact"))
        assertEquals(0, oldWrites)
        assertNotNull(state.error)
        val oldWrite = CompletableDeferred<JsonElement>()
        state.bind({ snapshot("standard") }, { _, _, _ -> oldWrite.await() })
        var oldSaved = false
        state.set(SharedSetting.Transcript, JsonPrimitive("compact")) { oldSaved = true }
        var newWrites = 0
        state.bind({ snapshot("detailed", 1) }, { _, _, _ -> newWrites++; JsonNull })
        oldRead.complete(snapshot("verbose", 99))
        oldWrite.complete(JsonObject(emptyMap()))
        assertEquals("detailed", state.value(SharedSetting.Transcript).str())
        assertFalse(oldSaved)
        assertEquals(0, newWrites)
        assertNull(state.error)
    }

    @Test fun desktopEventRefreshAndReadOnlyUnavailableStates() {
        val state = mirror()
        var host: JsonElement = snapshot("standard")
        var writes = 0
        state.bind({ host }, { _, _, _ -> writes++; JsonNull })
        host = snapshot("verbose", 8)
        state.refresh()
        assertEquals("verbose", state.value(SharedSetting.Transcript).str())
        host = snapshot("verbose", 8, writable = false)
        state.refresh()
        state.set(SharedSetting.Transcript, JsonPrimitive("compact"))
        assertEquals(0, writes)
        assertNotNull(state.error)
        state.clear()
        assertNull(state.value(SharedSetting.Transcript))
        assertFalse(state.ready)
    }

    @Test fun overriddenReadBackIsFailureAndNeverPublishesDraft() {
        val state = mirror()
        state.bind({ snapshot("standard") }, { _, _, _ -> JsonObject(emptyMap()) })
        state.set(SharedSetting.Transcript, JsonPrimitive("compact"))
        assertNotNull(state.error)
        assertEquals("standard", state.value(SharedSetting.Transcript).str())
    }
}
