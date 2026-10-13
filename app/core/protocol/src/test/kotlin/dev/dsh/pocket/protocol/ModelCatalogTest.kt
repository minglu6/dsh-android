package dev.dsh.pocket.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ModelCatalogTest {
    @Test fun parsesProviderFailuresAndGlobalDefaultEffort() {
        val catalog = ModelCatalog.parse(PocketJson.parseToJsonElement("""
            {"groups":[{"id":"p","name":"Provider","models":[{"id":"m","name":"Model"}]}],
             "default":{"provider":"p","model":"m","reasoningEffort":"high"},
             "failures":[{"id":"offline","name":"Offline provider","message":"credentials expired"}]}
        """))
        assertEquals("p", catalog.defaultProvider)
        assertEquals("m", catalog.defaultModel)
        assertEquals("high", catalog.defaultEffort)
        assertEquals(listOf(ModelCatalogFailure("offline", "Offline provider", "credentials expired")), catalog.failures)
        assertEquals("Model", catalog.find("p", "m")?.name)
    }

    @Test fun distinguishesAbsentReasoningFromEmptyReasoning() {
        val catalog = ModelCatalog.parse(PocketJson.parseToJsonElement("""
            {"groups":[{"id":"p","models":[
              {"id":"absent"}, {"id":"null","reasoning":null},
              {"id":"empty","reasoning":{}},
              {"id":"levels","reasoning":{"efforts":[{"id":"high","name":"High"}],"defaultEffort":"high"}}
            ]}]}
        """))
        assertFalse(catalog.find("p", "absent")!!.hasReasoning)
        assertFalse(catalog.find("p", "null")!!.hasReasoning)
        assertTrue(catalog.find("p", "empty")!!.hasReasoning)
        assertTrue(catalog.find("p", "levels")!!.hasReasoning)
        assertEquals("high", catalog.find("p", "levels")!!.defaultEffort)
        assertEquals(emptyList(), catalog.failures)
    }

}
