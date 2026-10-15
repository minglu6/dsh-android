package dev.dsh.pocket.app

import dev.dsh.pocket.protocol.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Test
import kotlin.test.*

class QoderConnectionTest {
    private fun json(text: String) = Json.parseToJsonElement(text)
    private val writable = json("""{"configured":true,"writable":true}""")

    @Test fun profileOverridesRootAndOnlyChangedConnectionFieldsAreWritten() {
        val draft = qoderConnectionDraft(json("""{"apiKeyEnv":"ROOT","vpcInstance":"root","baseURL":"root-url","providers":{"qoder-cn":{"apiKeyEnv":"PROFILE","vpcInstance":"","openApiUrl":"profile-api","models":[{"id":"keep"}]}}}"""), 4)
        assertEquals("PROFILE", draft.ref)
        assertEquals(listOf("providers", "qoder-cn"), draft.path)
        assertEquals(QoderConnectionFields("", "root-url", "profile-api"), draft.fields)
        assertEquals("", draft.pat)
        val changed = draft.copy(fields = draft.fields.copy(baseURL = " https://gateway.test "))
        assertEquals(json("""[{"op":"set","path":["providers","qoder-cn","baseURL"],"value":"https://gateway.test"}]"""), qoderConnectionOperations(changed))
        assertEquals("QODERCN_PERSONAL_ACCESS_TOKEN", qoderConnectionDraft(json("{}"), 0).ref)
        assertEquals(emptyList(), qoderConnectionDraft(json("{}"), 0).path)
    }

    @Test fun patOnlySaveFencesRevisionAndConflictNeverWritesCredential() = runBlocking {
        val draft = qoderConnectionDraft(json("{}"), 7).copy(pat = "secret-fixture")
        val calls = mutableListOf<String>()
        val result = saveQoderConnection(draft, true,
            describe = { calls += "describe"; writable },
            mutate = { ops, revision ->
                calls += "mutate"
                assertEquals(JsonArray(emptyList()), ops)
                assertEquals(7L, revision)
                throw PocketError("settings/conflict", "secret-fixture must not surface")
            },
            store = { _, _ -> error("Credential must not be written after failed CAS") })
        assertEquals(listOf("describe", "mutate"), calls)
        assertEquals(QoderText.Conflict, result.error)
        assertEquals(draft, result.draft)
        assertFalse(result.settingsSaved)
    }

    @Test fun partialSaveRetainsCommittedRevisionReferenceAndRetriesWithoutOverwritingSettings() = runBlocking {
        val draft = qoderConnectionDraft(json("""{"vpcInstance":"old","apiKeyEnv":"OLD_REF"}"""), 3)
            .let { it.copy(fields = it.fields.copy(vpcInstance = "new"), pat = "  secret-fixture  ") }
        val written = json("""{"ns":"llm-qoder","revision":4,"value":{"vpcInstance":"new","apiKeyEnv":"NEW_REF"}}""")
        var stores = 0
        val first = saveQoderConnection(draft, true, { writable }, { ops, revision ->
            assertEquals(3L, revision)
            assertEquals(json("""[{"op":"set","path":["vpcInstance"],"value":"new"}]"""), ops)
            written
        }, { ref, value ->
            stores++
            assertEquals("NEW_REF", ref)
            assertEquals("secret-fixture", value)
            throw IllegalStateException(value)
        })
        assertEquals(QoderText.CredentialFailed, first.error)
        assertTrue(first.settingsSaved)
        assertEquals(4L, first.draft.revision)
        assertEquals("NEW_REF", first.draft.ref)
        assertEquals(draft.pat, first.draft.pat)
        val second = saveQoderConnection(first.draft, true, { ref -> assertEquals("NEW_REF", ref); writable }, { ops, revision ->
            assertEquals(4L, revision)
            assertTrue(ops.isEmpty())
            written
        }, { ref, _ -> stores++; jsonOf("ref" to ref, "configured" to true, "writable" to true) })
        assertEquals(2, stores)
        assertNull(second.error)
        assertEquals("", second.draft.pat)
        assertFalse(second.settingsSaved)
    }

    @Test fun readOnlyAndFailedMetadataNeverReachMutationOrTokenWrite() = runBlocking {
        val draft = qoderConnectionDraft(json("{}"), 1).copy(pat = "secret-fixture")
        val denied = saveQoderConnection(draft, false,
            { error("Must not describe") }, { _, _ -> error("Must not mutate") }, { _, _ -> error("Must not store") })
        assertEquals(QoderText.ReadOnly, denied.error)
        val unavailable = saveQoderConnection(draft, true,
            { throw IllegalStateException(draft.pat) }, { _, _ -> error("Must not mutate") }, { _, _ -> error("Must not store") })
        assertEquals(QoderText.LoadFailed, unavailable.error)
        assertEquals(draft, unavailable.draft)
        val readOnly = saveQoderConnection(draft, true,
            { json("""{"configured":true,"writable":false}""") }, { _, _ -> error("Must not mutate") }, { _, _ -> error("Must not store") })
        assertEquals(QoderText.CredentialReadOnly, readOnly.error)
    }
}
