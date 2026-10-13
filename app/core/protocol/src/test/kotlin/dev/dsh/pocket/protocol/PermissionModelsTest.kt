package dev.dsh.pocket.protocol

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PermissionModelsTest {
    private val configured = """{"value":"restricted-team","name":"团队自定义","description":"只允许团队工作区内修改"}"""
    private val auto = """{"value":"auto","name":"Auto review","description":"Experimental review"}"""
    private fun parse(text: String) = PermissionCatalog.parse(PocketJson.parseToJsonElement(text))
    private fun catalog(options: String = configured, defaults: String = configured, default: String = "restricted-team") =
        """{"options":[$options],"defaultOptions":[$defaults],"defaultPreset":"$default"}"""

    @Test fun completeCatalogRetainsHostPresentationAndDefaultEligibility() {
        val result = parse(catalog("$configured,$auto"))
        assertEquals(listOf("restricted-team", "auto"), result.options.map { it.value })
        assertEquals("团队自定义", result.find("restricted-team")?.name)
        assertEquals("只允许团队工作区内修改", result.find("restricted-team")?.description)
        assertTrue(result.allowsDefault("restricted-team"))
        assertFalse(result.allowsDefault("auto"))
        assertEquals("restricted-team", result.defaultPreset)
    }

    @Test fun missingAutoIsNotManufacturedAndDescriptionMayBeAbsent() {
        val option = """{"value":"restricted-team","name":"团队自定义"}"""
        val result = parse(catalog(option, option))
        assertNull(result.find("auto"))
        assertNull(result.find("restricted-team")?.description)
    }

    @Test fun rejectsIncompleteCatalogInsteadOfSilentlyDroppingMetadata() {
        val valid = PocketJson.parseToJsonElement(catalog()) as JsonObject
        for (field in listOf("options", "defaultOptions", "defaultPreset")) {
            val error = assertFailsWith<PocketError> { PermissionCatalog.parse(JsonObject(valid - field)) }
            assertEquals("invalid-response", error.code)
        }
        assertFailsWith<PocketError> { parse("""{"options":[],"defaultOptions":{},"defaultPreset":"x"}""") }
        assertFailsWith<PocketError> { parse(catalog("""{"value":"restricted-team"}""")) }
    }

    @Test fun rejectsNonStringAndNullMetadata() {
        val invalidOptions = listOf(
            """{"value":123,"name":"Team"}""",
            """{"value":"restricted-team","name":true}""",
            """{"value":"restricted-team","name":"Team","description":null}""",
            """{"value":"restricted-team","name":"Team","description":123}""",
            """{"value":"","name":"Team"}""",
        )
        invalidOptions.forEach { option -> assertFailsWith<PocketError> { parse(catalog(option)) } }
    }

    @Test fun rejectsInvalidDefaultEligibilityAndDuplicateValues() {
        assertFailsWith<PocketError> { parse(catalog("$configured,$auto", "$configured,$auto")) }
        assertFailsWith<PocketError> { parse(catalog(default = "auto")) }
        assertFailsWith<PocketError> { parse(catalog(auto)) }
        assertFailsWith<PocketError> { parse(catalog("$configured,$configured")) }
        assertFailsWith<PocketError> { parse(catalog(defaults = "$configured,$configured")) }
        assertFailsWith<PocketError> { parse(catalog("""{"value":"custom","name":"Custom"}""")) }
    }

    @Test fun selectionPreservesWireContractAndRequiresNonemptyString() {
        assertEquals("auto", PermissionSelection.parse(PocketJson.parseToJsonElement("""{"current":"auto"}""")).currentValue)
        assertEquals("custom", PermissionSelection.parse(PocketJson.parseToJsonElement("""{"current":"custom"}""")).currentValue)
        assertFailsWith<PocketError> { PermissionSelection.parse(PocketJson.parseToJsonElement("""{"currentValue":"auto"}""")) }
        assertFailsWith<PocketError> { PermissionSelection.parse(PocketJson.parseToJsonElement("""{"current":""}""")) }
        assertFailsWith<PocketError> { PermissionSelection.parse(JsonObject(mapOf("current" to JsonPrimitive(7)))) }
    }
}
