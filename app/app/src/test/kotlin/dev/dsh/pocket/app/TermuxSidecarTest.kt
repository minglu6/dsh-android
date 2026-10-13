package dev.dsh.pocket.app

import org.junit.Assert.*
import org.junit.Test

class TermuxSidecarTest {
    @Test fun parsePairingUriForcesLoopbackWhenRemoteHostAdvertised() {
        val c = TermuxSidecar.parseCredential("dshpocket://pair?h=192.168.1.20:7690&c=482913")
        assertTrue(c is TermuxSidecar.Credential.PairCode)
        c as TermuxSidecar.Credential.PairCode
        assertEquals("127.0.0.1", c.host)
        assertEquals(7690, c.port)
        assertEquals("482913", c.code)
    }

    @Test fun parseLoopbackUriKeepsHost() {
        val c = TermuxSidecar.parseCredential("dshpocket://pair?h=127.0.0.1:7700&c=111111")
            as TermuxSidecar.Credential.PairCode
        assertEquals("127.0.0.1", c.host)
        assertEquals(7700, c.port)
    }

    @Test fun parseTokenPaste() {
        val token = "abcdefghijklmnopqrstuvwxyz012345"
        val c = TermuxSidecar.parseCredential(token, portFallback = 7690)
        assertTrue(c is TermuxSidecar.Credential.Token)
        c as TermuxSidecar.Credential.Token
        assertEquals("127.0.0.1", c.host)
        assertEquals(7690, c.port)
        assertEquals(token, c.token)
    }

    @Test fun parseCodeOnlyUsesPortFallback() {
        val c = TermuxSidecar.parseCredential("482913", portFallback = 7691)
            as TermuxSidecar.Credential.PairCode
        assertEquals(7691, c.port)
        assertEquals("482913", c.code)
    }

    @Test fun looksLikeTokenRejectsShortAndPairUri() {
        assertFalse(TermuxSidecar.looksLikeToken("123456"))
        assertFalse(TermuxSidecar.looksLikeToken("dshpocket://pair?h=127.0.0.1:7690&c=123456"))
        assertTrue(TermuxSidecar.looksLikeToken("abcdefghijklmnopqrstuvwxyz012345"))
    }

    @Test fun bootstrapPointsAtCommunityInstallersNotVendored() {
        assertTrue(TermuxSidecar.BOOTSTRAP_COMMANDS.contains(TermuxSidecar.TERMUX_GITHUB_RELEASES))
        assertTrue(TermuxSidecar.BOOTSTRAP_COMMANDS.contains(TermuxSidecar.TERMUX_FDROID))
        assertTrue(TermuxSidecar.BOOTSTRAP_COMMANDS.contains("127.0.0.1"))
        assertTrue(TermuxSidecar.BOOTSTRAP_COMMANDS.contains("dsh web"))
        assertFalse(TermuxSidecar.BOOTSTRAP_COMMANDS.contains("Runtime.exec"))
        assertTrue(TermuxSidecar.PRIVILEGE_REMINDER_COMMANDS.contains("never runs su"))
    }

    @Test fun probeRejectsNonLoopback() {
        assertFalse(TermuxSidecar.probeLoopback("192.168.1.1", 7690))
        assertFalse(TermuxSidecar.probeLoopback("127.0.0.1", 0))
    }

    @Test fun isTermuxProfile() {
        assertTrue(TermuxSidecar.isTermuxProfile("127.0.0.1"))
        assertTrue(TermuxSidecar.isTermuxProfile("localhost"))
        assertFalse(TermuxSidecar.isTermuxProfile("100.64.1.2"))
    }
}
