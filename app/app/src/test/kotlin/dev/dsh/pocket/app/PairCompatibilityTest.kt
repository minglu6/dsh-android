package dev.dsh.pocket.app

import dev.dsh.pocket.protocol.CompatibilityIssueKind
import dev.dsh.pocket.protocol.HelloResult
import dev.dsh.pocket.protocol.VersionCompatibility
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PairCompatibilityTest {
    @Test fun compatibleHelloPasses() {
        val hello = HelloResult(
            JsonObject(
                mapOf(
                    "proto" to JsonPrimitive(VersionCompatibility.PROTO),
                    "version" to JsonPrimitive("0.2.0"),
                    "minimumAppVersion" to JsonPrimitive("0.1.0"),
                    "token" to JsonPrimitive("tok"),
                ),
            ),
        )
        assertNull(Pocket.pairCompatibilityIssue(hello))
    }

    @Test fun pluginTooOldFailsPair() {
        val hello = HelloResult(
            JsonObject(
                mapOf(
                    "proto" to JsonPrimitive(VersionCompatibility.PROTO),
                    "version" to JsonPrimitive("0.0.1"),
                    "minimumAppVersion" to JsonPrimitive("0.1.0"),
                    "token" to JsonPrimitive("tok"),
                ),
            ),
        )
        val issue = Pocket.pairCompatibilityIssue(hello)
        assertNotNull(issue)
        assertEquals(CompatibilityIssueKind.PLUGIN_TOO_OLD, issue.kind)
        assertEquals(L.pluginTooOld(issue.requiredVersion), Pocket.compatibilityMessage(issue))
    }

    @Test fun missingHandshakeIsUnsupported() {
        val hello = HelloResult(JsonObject(mapOf("token" to JsonPrimitive("tok"))))
        val issue = Pocket.pairCompatibilityIssue(hello)
        assertNotNull(issue)
        assertEquals(CompatibilityIssueKind.PROTOCOL_UNSUPPORTED, issue.kind)
        assertEquals(L.protocolUnsupported(), Pocket.compatibilityMessage(issue))
    }
}
