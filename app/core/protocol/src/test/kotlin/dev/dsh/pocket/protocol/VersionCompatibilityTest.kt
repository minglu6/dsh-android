package dev.dsh.pocket.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class VersionCompatibilityTest {
    @Test fun acceptsTheCurrentAppAndPlugin() {
        assertNull(VersionCompatibility.evaluate("0.1.0-m3", handshake()))
    }

    @Test fun requestsPluginUpdateWhenPluginIsTooOld() {
        val issue = VersionCompatibility.evaluate(
            "0.1.0",
            handshake(pluginVersion = "0.0.9"),
        )
        assertEquals(CompatibilityIssueKind.PLUGIN_TOO_OLD, issue?.kind)
        assertEquals(VersionCompatibility.MINIMUM_PLUGIN_VERSION, issue?.requiredVersion)
    }

    @Test fun requestsAnAppUpdateOnlyWhenThePluginRequiresIt() {
        val issue = VersionCompatibility.evaluate(
            "0.0.9",
            handshake(minimumAppVersion = "0.1.0"),
        )
        assertEquals(CompatibilityIssueKind.APP_TOO_OLD, issue?.kind)
        assertEquals("0.1.0", issue?.requiredVersion)
    }

    @Test fun rejectsUnsupportedProtocol() {
        assertEquals(
            CompatibilityIssueKind.PROTOCOL_UNSUPPORTED,
            VersionCompatibility.evaluate("0.1.0", handshake(proto = 2))?.kind,
        )
    }

    @Test fun rejectsMalformedVersionMetadataWithoutRequestingTheWrongUpdate() {
        val malformed = HandshakeVersions(1, "development", "latest")
        assertEquals(
            CompatibilityIssueKind.PROTOCOL_UNSUPPORTED,
            VersionCompatibility.evaluate("0.1.0", malformed)?.kind,
        )
    }

    @Test fun comparesPrereleasesByTheirNumericRelease() {
        assertEquals(0, VersionCompatibility.compareVersions("0.1.0-m3", "0.1.0"))
        assertEquals(-1, VersionCompatibility.compareVersions("0.0.9", "0.1.0"))
        assertEquals(1, VersionCompatibility.compareVersions("0.1.1", "0.1.0"))
    }

    private fun handshake(
        pluginVersion: String = "0.1.0",
        minimumAppVersion: String = "0.1.0",
        proto: Int = 1,
    ) = HandshakeVersions(proto, pluginVersion, minimumAppVersion)
}
