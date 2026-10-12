package dev.dsh.pocket.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Adapted from dsh-mobile NearbyDiscoveryPermissionPolicy, Apache-2.0. */
class NearbyDiscoveryPermissionPolicyTest {
    @Test fun requestsOnlyOnApi33PlusWhenNotGrantedAndNotDeclined() {
        assertTrue(NearbyDiscoveryPermissionPolicy.shouldRequest(33, granted = false, previouslyDeclined = false))
        assertFalse(NearbyDiscoveryPermissionPolicy.shouldRequest(32, granted = false, previouslyDeclined = false))
        assertFalse(NearbyDiscoveryPermissionPolicy.shouldRequest(33, granted = true, previouslyDeclined = false))
        assertFalse(NearbyDiscoveryPermissionPolicy.shouldRequest(33, granted = false, previouslyDeclined = true))
    }
}
