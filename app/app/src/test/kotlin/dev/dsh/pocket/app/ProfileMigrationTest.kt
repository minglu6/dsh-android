package dev.dsh.pocket.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileMigrationTest {
    @Test fun migrateLegacyProfilesSeedsLastUsedAndClearsIrohNoise() {
        val now = 1_700_000_000_000L
        val legacy = listOf(
            Profile("a", "Old iroh", nodeId = "abcdefghijklmnopqrstuvwxyz01", transport = "iroh"),
            Profile("b", "LAN box", host = "192.168.1.2", port = 7690, transport = "tailscale", nodeId = "deadbeef"),
            Profile("c", "Tail", host = "100.64.1.2", port = 7690),
        )
        val out = migrateLegacyProfiles(legacy, now)
        assertEquals(3, out.size)
        assertTrue(out[0].needsRepair)
        assertEquals("", out[1].transport)
        assertEquals("", out[1].nodeId)
        assertTrue(out[0].lastUsedAt < out[1].lastUsedAt)
        assertTrue(out[1].lastUsedAt < out[2].lastUsedAt)
        assertEquals(now - 1000L, out[2].lastUsedAt)
    }

    @Test fun profilesSortedByLastUsedDescending() {
        val rows = listOf(
            Profile("a", "A", host = "1.1.1.1", port = 1, lastUsedAt = 10),
            Profile("b", "B", host = "1.1.1.1", port = 1, lastUsedAt = 30),
            Profile("c", "C", host = "1.1.1.1", port = 1, lastUsedAt = 20),
        )
        val sorted = rows.sortedWith(compareByDescending<Profile> { it.lastUsedAt }.thenBy { it.name })
        assertEquals(listOf("b", "c", "a"), sorted.map { it.id })
    }
}
