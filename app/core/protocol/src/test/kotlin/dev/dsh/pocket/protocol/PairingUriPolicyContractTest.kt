package dev.dsh.pocket.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** Runs the language-neutral pairing-URI vectors against the Android/JVM implementation. */
class PairingUriPolicyContractTest {
    private val contract: JsonObject by lazy {
        val resource = javaClass.classLoader?.getResource("pairing-uri-cases.json")
        assertNotNull(resource, "shared pairing-uri contract is missing from test resources")
        Json.parseToJsonElement(resource!!.readText()).jsonObject
    }

    @Test fun parseMatchesSharedContract() {
        val cases = contract["parse"]!!.jsonArray
        for (element in cases) {
            val case = element.jsonObject
            val name = case["name"]!!.jsonPrimitive.content
            val input = case["input"]!!.jsonPrimitive.content
            val expectedEl = case["expected"]
            val actual = PairingUriPolicy.parse(input)
            if (expectedEl == null || expectedEl is JsonNull) {
                assertEquals(null, actual, name)
            } else {
                val expected = expectedEl.jsonObject
                assertNotNull(actual, name)
                assertEquals(expected["host"]!!.jsonPrimitive.content, actual!!.host, name)
                assertEquals(expected["port"]!!.jsonPrimitive.int, actual.port, name)
                assertEquals(expected["code"]!!.jsonPrimitive.content, actual.code, name)
            }
        }
    }
}
