package dev.dsh.pocket.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Adapted from saya-ch/dsh-mobile (Apache-2.0) ConnectionRestorePolicyTest.kt — network reconnect gate. */
class ConnectionRestorePolicyTest {
    @Test
    fun reconnectsOnlyWhenOfflineOrConnecting() {
        assertTrue(ConnectionRestorePolicy.shouldReconnect(Dot.Offline))
        assertTrue(ConnectionRestorePolicy.shouldReconnect(Dot.Connecting))
        assertFalse(ConnectionRestorePolicy.shouldReconnect(Dot.Online))
        assertFalse(ConnectionRestorePolicy.shouldReconnect(Dot.Unauthorized))
        assertFalse(ConnectionRestorePolicy.shouldReconnect(null))
    }
}
