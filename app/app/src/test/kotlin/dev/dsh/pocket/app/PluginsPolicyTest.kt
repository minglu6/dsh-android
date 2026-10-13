package dev.dsh.pocket.app

import dev.dsh.pocket.protocol.bool
import dev.dsh.pocket.protocol.get
import kotlinx.serialization.json.Json
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginsPolicyTest {
    @Test
    fun declarationDoesNotImplyEnabledRuntime() {
        val row = Json.parseToJsonElement("""{"rowId":"tool","moduleName":"tool","entryId":"live-1"}""")
        val live = Json.parseToJsonElement("""{"entryId":"live-1","enabled":false,"fiberPhase":null}""")
        assertSame(live, pluginLiveRow(row, listOf(live)))
        assertFalse(pluginLiveRow(row, listOf(live))["enabled"].bool() == true)
        assertNull(pluginLiveRow(row, emptyList()))
    }

    @Test
    fun runtimeJoinUsesEntryIdentityNotModuleOrRowName() {
        val row = Json.parseToJsonElement("""{"rowId":"same","moduleName":"same","entryId":"a"}""")
        val wrong = Json.parseToJsonElement("""{"entryId":"b","id":"same","moduleName":"same","enabled":true}""")
        assertNull(pluginLiveRow(row, listOf(wrong)))
        assertNull(pluginLiveRow(Json.parseToJsonElement("""{"rowId":"same"}"""), listOf(wrong)))
    }

    @Test
    fun parentWritesCannotEraseRedactedSecrets() {
        val secrets = listOf(listOf("provider", "apiKey"))
        assertTrue(pluginSettingsReplacesSecret(emptyList(), secrets))
        assertTrue(pluginSettingsReplacesSecret(listOf("provider"), secrets))
        assertFalse(pluginSettingsReplacesSecret(listOf("provider", "apiKey"), secrets))
        assertFalse(pluginSettingsReplacesSecret(listOf("provider", "model"), secrets))
        assertFalse(pluginSettingsReplacesSecret(emptyList(), emptyList()))
    }

    @Test
    fun dereferencesSchemasteryGraphForNativeNumberFields() {
        val schema = Json.parseToJsonElement("""{"uid":6358,"refs":{"6358":{"type":"object","dict":{"maxParallelToolCalls":6357}},"6357":{"type":"number","meta":{"step":1,"min":1,"default":10}}}}""")
        val fields = pluginSchemaFields(schema)
        org.junit.Assert.assertEquals(1, fields.size)
        org.junit.Assert.assertEquals(listOf("maxParallelToolCalls"), fields.single().path)
        org.junit.Assert.assertEquals("number", fields.single().type)
        org.junit.Assert.assertEquals("10", fields.single().schema["meta"]["default"].toString())
    }

    @Test
    fun supportsNestedNativeBooleanAndEnumWithoutFollowingCycles() {
        val schema = Json.parseToJsonElement("""{"uid":1,"refs":{"1":{"type":"object","dict":{"options":2,"mode":4,"cycle":1}},"2":{"type":"object","dict":{"enabled":3}},"3":{"type":"boolean"},"4":{"type":"union","list":[5,6]},"5":{"type":"const","value":"safe"},"6":{"type":"const","value":"fast"}}}""")
        val fields = pluginSchemaFields(schema)
        org.junit.Assert.assertEquals(2, fields.size)
        org.junit.Assert.assertEquals(listOf("options", "enabled"), fields.first().path)
        org.junit.Assert.assertEquals("boolean", fields.first().type)
        org.junit.Assert.assertEquals(listOf(Json.parseToJsonElement("\"safe\""), Json.parseToJsonElement("\"fast\"")), fields.last().choices)
    }
}
