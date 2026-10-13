package dev.dsh.pocket.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Adapted from dsh-mobile LanDiscoveryTest.kt
 * (https://github.com/saya-ch/dsh-mobile), Apache-2.0.
 */
class LanDiscoveryTest {
    @Test
    fun acceptsOnlyMetadataAnnouncementFromItsAdvertisedAddress() {
        val instanceId = "a".repeat(64)
        val result = NsdDiscovery.parseAnnouncement(
            """{"deviceName":"Studio PC","host":"192.168.1.20","port":7690,"protocol":1,"instanceId":"$instanceId"}""",
            "192.168.1.20",
        )
        assertEquals(DiscoveredHost("Studio PC", "192.168.1.20", 7690, instanceId), result)
    }

    @Test
    fun rejectsSecretBearingOrAddressMismatchedAnnouncements() {
        val valid = """{"deviceName":"Studio PC","host":"192.168.1.20","port":7690,"protocol":1,"instanceId":"${"a".repeat(64)}"}"""
        val withSecret = valid.dropLast(1) + ",\"deviceToken\":\"secret\"}"
        assertNull(NsdDiscovery.parseAnnouncement(withSecret, "192.168.1.20"))
        assertNull(NsdDiscovery.parseAnnouncement(valid, "192.168.1.21"))
    }

    @Test
    fun applyAddressUpdateOnlyWhenInstanceMatchesAndHostChanges() {
        val id = "b".repeat(64)
        val profile = Profile("p1", "Mac", host = "192.168.1.10", port = 7690, instanceId = id)
        assertNull(LanDiscovery.applyAddressUpdate(profile, DiscoveredHost("Mac", "192.168.1.10", 7690, id)))
        val updated = LanDiscovery.applyAddressUpdate(profile, DiscoveredHost("Mac", "192.168.1.55", 7690, id))
        assertEquals("192.168.1.55", updated!!.host)
        assertNull(LanDiscovery.applyAddressUpdate(profile, DiscoveredHost("Other", "192.168.1.55", 7690, "c".repeat(64))))
    }
}
