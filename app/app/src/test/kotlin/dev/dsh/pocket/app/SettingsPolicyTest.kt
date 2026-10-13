package dev.dsh.pocket.app

import dev.dsh.pocket.protocol.arr
import dev.dsh.pocket.protocol.get
import dev.dsh.pocket.protocol.str
import kotlinx.serialization.json.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SettingsPolicyTest {
    @Test fun providerEditsNeverReplaceUnobservedFieldsOrSecrets() {
        val before = Json.parseToJsonElement("""{"baseURL":"https://old.example","apiKeyEnv":"HOST_KEY","headers":{"Authorization":"redacted"}}""")
        val after = Json.parseToJsonElement("""{"baseURL":"https://new.example"}""") as JsonObject
        val operations = providerFieldOps(listOf("providers", "gateway"), before, after)
        assertEquals(1, operations.size)
        assertEquals(listOf("providers", "gateway", "baseURL"), operations[0]["path"].arr()!!.map { it.str() })
        assertEquals("set", operations[0]["op"].str())
    }

    @Test fun blankOverrideRestoresInheritanceWithoutCreatingNullValues() {
        val before = Json.parseToJsonElement("""{"baseURL":"https://old.example"}""")
        val operations = providerFieldOps(emptyList(), before, buildJsonObject { put("baseURL", JsonNull); put("displayName", JsonNull) })
        assertEquals(1, operations.size)
        assertEquals("unset", operations[0]["op"].str())
        assertNull(operations[0]["value"])
    }

    @Test fun protocolChoicesFollowHostReferenceGraphAndDictionary() {
        val schema = Json.parseToJsonElement("""{"uid":0,"refs":{"0":{"type":"object","dict":{"providers":1}},"1":{"type":"dict","inner":2},"2":{"type":"object","dict":{"api":3}},"3":{"type":"union","list":[4,5]},"4":{"type":"const","value":"host-api-a"},"5":{"type":"const","value":"host-api-b"}}}""")
        assertEquals(listOf("host-api-a", "host-api-b"), providerProtocolChoices(schema))
        assertTrue(providerProtocolChoices(JsonNull).isEmpty())
    }

    @Test fun modelsRejectDuplicateIdsAndInvalidCapacities() {
        assertNotNull(validateProviderModels(Json.parseToJsonElement("""[{"id":"a"},{"id":"a"}]""") as JsonArray))
        assertNotNull(validateProviderModels(Json.parseToJsonElement("""[{"id":"a","contextWindow":0}]""") as JsonArray))
        assertNotNull(validateProviderModels(Json.parseToJsonElement("""[{"id":"a","maxTokens":"200"}]""") as JsonArray))
        assertNull(validateProviderModels(Json.parseToJsonElement("""[{"id":"a","contextWindow":131072,"input":["text","image"],"reasoningEfforts":{"high":"enabled"}}]""") as JsonArray))
    }

    @Test fun failedProviderCasNeverTouchesCredentials() = runBlocking {
        var progress = ProviderRemovalProgress()
        var credentialPresent = true
        val calls = mutableListOf<String>()
        val failure = runCatching {
            removeProviderSafely(progress, { progress = it },
                removeConfiguration = { calls += "cas"; error("settings/conflict") },
                confirmRemoval = { calls += "read" },
                clearManagedCredential = { calls += "credential"; credentialPresent = false },
            )
        }.exceptionOrNull()
        assertEquals("settings/conflict", failure?.message)
        assertEquals(listOf("cas"), calls)
        assertTrue(credentialPresent)
        assertEquals(ProviderRemovalProgress(), progress)
    }

    @Test fun credentialCleanupFailureRetriesWithoutRepeatingConfigurationCas() = runBlocking {
        var progress = ProviderRemovalProgress()
        var configurationPresent = true
        var credentialPresent = true
        var failCredential = true
        val calls = mutableListOf<String>()
        suspend fun remove() = removeProviderSafely(progress, { progress = it },
            removeConfiguration = {
                calls += "cas"
                check(configurationPresent) { "stale revision" }
                configurationPresent = false
            },
            confirmRemoval = { calls += "read"; check(!configurationPresent) },
            clearManagedCredential = {
                calls += "credential"
                if (failCredential) error("credential store unavailable")
                credentialPresent = false
            },
        )
        assertEquals("credential store unavailable", runCatching { remove() }.exceptionOrNull()?.message)
        assertFalse(configurationPresent)
        assertTrue(credentialPresent)
        assertEquals(ProviderRemovalProgress(configurationRemoved = true), progress)
        failCredential = false
        remove()
        assertEquals(listOf("cas", "read", "credential", "read", "credential"), calls)
        assertFalse(credentialPresent)
        assertEquals(ProviderRemovalProgress(true, true), progress)
    }

    @Test fun unconfirmedRemovalKeepsCredentialAndCanResumeReadBack() = runBlocking {
        var progress = ProviderRemovalProgress()
        var failRead = true
        val calls = mutableListOf<String>()
        suspend fun remove() = removeProviderSafely(progress, { progress = it },
            removeConfiguration = { calls += "cas" },
            confirmRemoval = { calls += "read"; if (failRead) error("disconnected") },
            clearManagedCredential = { calls += "credential" },
        )
        assertNotNull(runCatching { remove() }.exceptionOrNull())
        assertEquals(listOf("cas", "read"), calls)
        failRead = false
        remove()
        assertEquals(listOf("cas", "read", "read", "credential"), calls)
    }

    @Test fun creationRefreshAdoptsLatestRevisionAndDetectsRoutesMissingFromDirectory() {
        val listed = Json.parseToJsonElement("""{
            "rows":[{"provider":"catalog-route"}],
            "namespaces":[{"ns":"llm-pi-ai","revision":42,
                "user":{"providers":{"concurrent-create":{"baseURL":"https://other.example"}}},
                "value":{"providers":{"deployment-route":{}}}}]
        }""")
        val refreshed = providerCreationBaseline(listed, "llm-pi-ai")
        assertEquals(42L, refreshed.revision)
        assertEquals(setOf("catalog-route", "concurrent-create", "deployment-route"), refreshed.taken)
        assertFalse("renamed-draft" in refreshed.taken)
        assertEquals("https://other.example", refreshed.namespace["user"]["providers"]["concurrent-create"]["baseURL"].str())
    }

    @Test fun creationRefreshRefusesMissingRevisionInsteadOfBlindOverwrite() {
        val listed = Json.parseToJsonElement("""{"namespaces":[{"ns":"llm-pi-ai","user":{},"value":{}}]}""")
        assertNotNull(runCatching { providerCreationBaseline(listed, "llm-pi-ai") }.exceptionOrNull())
    }
}
