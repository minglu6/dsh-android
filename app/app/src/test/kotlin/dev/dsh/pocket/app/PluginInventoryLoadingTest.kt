package dev.dsh.pocket.app

import androidx.compose.runtime.*
import androidx.compose.ui.test.junit4.createComposeRule
import dev.dsh.pocket.protocol.PocketClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Exercises the actual Compose load effect, including cancellation before an RPC returns. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PluginInventoryLoadingTest {
    @get:Rule val rule = createComposeRule()

    private var host by mutableStateOf<String?>("host-a")
    private var online by mutableStateOf(false)
    private var ops by mutableStateOf(emptySet<String>())
    private var connection by mutableStateOf<PocketClient?>(null)
    private var revision by mutableLongStateOf(0L)
    private var refresh by mutableIntStateOf(0)
    private lateinit var state: PluginInventoryLoadState
    private val requests = mutableListOf<Request>()
    private var delayedCancellation = false
    private val inventory = Json.parseToJsonElement(
        """{"entries":[{"entryId":"global-a","moduleName":"global-plugin"}],"agentPresets":[{"id":"standard","isDefault":true,"rows":[{"moduleName":"session-plugin","enabled":true}]}]}""",
    )
    private val updatedInventory = Json.parseToJsonElement(
        """{"entries":[{"entryId":"global-b","moduleName":"updated-plugin"}],"agentPresets":[]}""",
    )

    private class Request(val connection: PocketClient) {
        val response = CompletableDeferred<JsonElement>()
    }

    private fun mount() {
        rule.setContent {
            state = rememberPluginInventory(host, online, "plugins.inventory" in ops, connection, revision, refresh) { client ->
                val request = Request(client)
                requests += request
                if (delayedCancellation) withContext(NonCancellable) { request.response.await() }
                else request.response.await()
            }
        }
        rule.waitForIdle()
    }

    private fun connect() {
        rule.runOnIdle {
            connection = PocketClient()
            online = true
            ops = setOf("plugins.inventory")
        }
        rule.waitForIdle()
    }

    private fun complete(index: Int, value: JsonElement = inventory) {
        rule.runOnIdle { requests[index].response.complete(value) }
        rule.waitForIdle()
    }

    @Test fun connectionAndIncrementalCapabilityPublicationStartTheInitialLoad() {
        mount()
        rule.runOnIdle {
            assertTrue(requests.isEmpty())
            assertFalse(state.loading)
            connection = PocketClient()
            online = true
            ops = setOf("sessions.list")
        }
        rule.waitForIdle()
        rule.runOnIdle {
            assertTrue(requests.isEmpty())
            ops = ops + "plugins.inventory"
        }
        rule.waitForIdle()
        rule.runOnIdle {
            assertEquals(1, requests.size)
            assertTrue(state.loading)
            assertSame(connection, requests[0].connection)
        }
        complete(0)
        rule.runOnIdle {
            assertSame(inventory, state.snapshot)
            assertFalse(state.loading)
            assertNull(state.error)
        }
    }

    @Test fun unrelatedCapabilitiesDoNotCancelLoadsOrDiscardTheDirectory() {
        mount()
        connect()
        rule.runOnIdle { ops = ops + "models.providers" }
        rule.waitForIdle()
        rule.runOnIdle { assertEquals(1, requests.size) }
        complete(0)
        rule.runOnIdle { ops = ops - "models.providers" }
        rule.waitForIdle()
        rule.runOnIdle {
            assertEquals(1, requests.size)
            assertSame(inventory, state.snapshot)
        }
    }

    @Test fun initialFailureIsVisibleWithoutAnExistingDirectory() {
        online = true
        ops = setOf("plugins.inventory")
        connection = PocketClient()
        mount()
        val failure = IllegalStateException("inventory unavailable")
        rule.runOnIdle {
            assertEquals(1, requests.size)
            requests[0].response.completeExceptionally(failure)
        }
        rule.waitForIdle()
        rule.runOnIdle {
            assertSame(failure, state.error)
            assertNull(state.snapshot)
            assertFalse(state.loading)
        }
    }

    @Test fun connectionReplacementWhileOnlineRejectsLateFailure() {
        delayedCancellation = true
        mount()
        connect()
        rule.runOnIdle { connection = PocketClient() }
        rule.waitForIdle()
        rule.runOnIdle {
            assertEquals(2, requests.size)
            requests[0].response.completeExceptionally(IllegalStateException("old connection failed"))
        }
        rule.waitForIdle()
        rule.runOnIdle {
            assertNull(state.error)
            assertTrue(state.loading)
        }
        complete(1, updatedInventory)
        rule.runOnIdle {
            assertSame(updatedInventory, state.snapshot)
            assertFalse(state.loading)
        }
    }

    @Test fun catalogChangeDuringManualRefreshReplacesRatherThanSkipsTheLoad() {
        mount()
        connect()
        complete(0)
        rule.runOnIdle {
            delayedCancellation = true
            refresh++
        }
        rule.waitForIdle()
        rule.runOnIdle {
            assertEquals(2, requests.size)
            revision++
        }
        rule.waitForIdle()
        rule.runOnIdle {
            assertEquals(3, requests.size)
            assertTrue(state.loading)
            assertSame(inventory, state.snapshot)
        }
        // The old load's finally must not clear the replacement's loading flag.
        complete(1)
        rule.runOnIdle { assertTrue(state.loading) }
        complete(2, updatedInventory)
        rule.runOnIdle {
            assertSame(updatedInventory, state.snapshot)
            assertFalse(state.loading)
        }
    }

    @Test fun failurePreservesDirectoryAndManualRefreshClearsTheError() {
        mount()
        connect()
        complete(0)
        rule.runOnIdle { refresh++ }
        rule.waitForIdle()
        val failure = IllegalStateException("inventory unavailable")
        rule.runOnIdle { requests[1].response.completeExceptionally(failure) }
        rule.waitForIdle()
        rule.runOnIdle {
            assertSame(failure, state.error)
            assertSame(inventory, state.snapshot)
            assertFalse(state.loading)
            refresh++
        }
        rule.waitForIdle()
        rule.runOnIdle {
            assertNull(state.error)
            assertTrue(state.loading)
        }
        complete(2, updatedInventory)
        rule.runOnIdle { assertSame(updatedInventory, state.snapshot) }
    }

    @Test fun capabilityRemovalAndDisconnectRetainDirectoryAndReconnectLoadsNewConnection() {
        mount()
        connect()
        complete(0)
        rule.runOnIdle { ops = emptySet() }
        rule.waitForIdle()
        rule.runOnIdle {
            assertSame(inventory, state.snapshot)
            assertFalse(state.loading)
            ops = setOf("plugins.inventory")
        }
        rule.waitForIdle()
        rule.runOnIdle {
            assertEquals(2, requests.size)
            online = false
            connection = null
            ops = emptySet()
        }
        rule.waitForIdle()
        rule.runOnIdle {
            assertSame(inventory, state.snapshot)
            assertFalse(state.loading)
        }
        connect()
        rule.runOnIdle {
            assertEquals(3, requests.size)
            assertNotSame(requests[0].connection, requests[2].connection)
        }
        complete(1, updatedInventory)
        rule.runOnIdle { assertSame(inventory, state.snapshot) }
        complete(2, updatedInventory)
        rule.runOnIdle { assertSame(updatedInventory, state.snapshot) }
    }

    @Test fun hostSwitchAndLateResponsesCannotMixDirectories() {
        delayedCancellation = true
        mount()
        connect()
        complete(0)
        rule.runOnIdle { refresh++ }
        rule.waitForIdle()
        rule.runOnIdle {
            host = "host-b"
            connection = PocketClient()
        }
        rule.waitForIdle()
        rule.runOnIdle {
            assertEquals(3, requests.size)
            assertNull(state.snapshot)
            assertTrue(state.loading)
        }
        complete(2, updatedInventory)
        complete(1)
        rule.runOnIdle {
            assertSame(updatedInventory, state.snapshot)
            assertFalse(state.loading)
            assertNull(state.error)
        }
    }
}
