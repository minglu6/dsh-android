package dev.dsh.pocket.app

import dev.dsh.pocket.protocol.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class GeneralSettingsPolicyTest {
    private fun snapshot(enabled: Boolean = false, revision: Int = 7, writable: Boolean = true): JsonElement =
        Json.parseToJsonElement("""{"writable":$writable,"namespaces":[{"ns":"session-log-deepseek","revision":$revision,"value":{"enabled":$enabled}}]}""")
    private fun mirror() = HostSettings(CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))

    @Test fun uploadNamespaceRequiresAnActualHostNamespace() {
        val state = mirror()
        state.bind({ jsonOf("writable" to true, "namespaces" to listOf(jsonOf("ns" to "session-log"))) }, { _, _, _ -> error("must not mutate") })
        assertNull(state.namespace(SharedSetting.SessionLog))
        assertFalse(state.writable(SharedSetting.SessionLog))
    }

    @Test fun uploadMutationUsesObservedRevisionAndReadsBackAcceptedValue() {
        val state = mirror()
        val calls = mutableListOf<String>()
        var host = snapshot()
        state.bind({ calls += "read"; host }, { ns, operations, revision ->
            calls += "mutate"
            assertEquals("session-log-deepseek", ns)
            assertEquals(7, revision)
            assertEquals(1, operations.size)
            assertEquals("set", operations[0]["op"].str())
            assertEquals(listOf("enabled"), operations[0]["path"].arr()!!.map { it.str() })
            assertEquals(true, operations[0]["value"].bool())
            host = snapshot(true, 8)
            JsonObject(emptyMap())
        })
        state.set(SharedSetting.SessionLog, JsonPrimitive(true))
        assertEquals(true, state.value(SharedSetting.SessionLog).bool())
        assertEquals(listOf("read", "mutate", "read"), calls)
        assertNull(state.error)
    }

    @Test fun readOnlyMissingAndUnversionedSettingsNeverMutate() {
        var mutations = 0
        for (initial in listOf(snapshot(writable = false), snapshot(revision = -1), JsonObject(emptyMap()))) {
            val state = mirror()
            state.bind({ initial }, { _, _, _ -> mutations++; JsonNull })
            state.set(SharedSetting.SessionLog, JsonPrimitive(true))
            assertNotNull(state.error)
        }
        assertEquals(0, mutations)
    }

    @Test fun failedApplicationAndOverriddenReadBackAreNotSuccess() {
        for (response in listOf(jsonOf("application" to "failed", "message" to "Host rejected"), JsonObject(emptyMap()))) {
            val state = mirror()
            state.bind({ snapshot() }, { _, _, _ -> response })
            state.set(SharedSetting.SessionLog, JsonPrimitive(true))
            assertNotNull(state.error)
            assertEquals(false, state.value(SharedSetting.SessionLog).bool())
        }
    }
}
