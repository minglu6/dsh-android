package dev.dsh.pocket.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserPolicyTest {
    private val policy = BrowserPolicy()

    private fun accepted(input: String, expected: String, using: BrowserPolicy = policy) {
        val actual = using.parse(input)
        assertTrue("Expected accepted address for $input, got $actual", actual is BrowserAddressResult.Target)
        assertEquals(expected, (actual as BrowserAddressResult.Target).url)
    }

    private fun refused(input: String, reason: BrowserAddressFailure? = null, using: BrowserPolicy = policy) {
        val actual = using.parse(input)
        assertTrue("Expected refused address for $input, got $actual", actual is BrowserAddressResult.Rejected)
        if (reason != null) assertEquals(reason, (actual as BrowserAddressResult.Rejected).reason)
    }

    @Test fun missingSchemeDefaultsToHttpsAndRetainsPathsQueriesAndFragments() {
        accepted(" example.com ", "https://example.com/")
        accepted("EXAMPLE.com/path?q=a%20b#section", "https://example.com/path?q=a%20b#section")
        accepted("HTTP://Example.COM/a", "http://example.com/a")
        accepted("https://example.com/中文", "https://example.com/%E4%B8%AD%E6%96%87")
        accepted("https://例子.com", "https://xn--fsqu00a.com/")
    }

    @Test fun localAddressesAreRealHttpTargetsNotAnImplicitHostProxy() {
        accepted("localhost:8080", "https://localhost:8080/")
        accepted("http://localhost:8080/demo", "http://localhost:8080/demo")
        accepted("127.0.0.1:3000?x=1", "https://127.0.0.1:3000/?x=1")
        accepted("[::1]:8080/path", "https://[::1]:8080/path")
        accepted("http://[::1]:8080/", "http://[::1]:8080/")
        accepted("computer:1234", "https://computer:1234/")
    }

    @Test fun nativeAndScriptSchemesCannotNavigateEvenWithNumericPayloads() {
        listOf("file:///sdcard/private", "content://dev.dsh.pocket.files/private", "data:text/html,<script>x</script>",
            "javascript:alert(1)", "blob:https://example.com/id", "intent://example.com", "about:blank",
            "ftp://example.com", "ws://example.com", "data:123", "javascript:123", "content:123", "file:123")
            .forEach { refused(it, BrowserAddressFailure.Protocol) }
    }

    @Test fun userinfoNeverBecomesAnAddressOrExternalIntent() {
        listOf("https://user:password@example.com/", "http://user@example.com", "https://@example.com/",
            "https://%75ser@example.com", "https://example.com@evil.test").forEach {
            refused(it, BrowserAddressFailure.Credentials)
        }
        refused("https://user%40example.com/")
        refused("https://example%2ecom/")
    }

    @Test fun malformedAuthoritiesAndAmbiguousSeparatorsAreRejected() {
        listOf("https:///example.com", "http:example.com", "https://", "//example.com", "https://example.com:",
            "https://example.com:0", "https://example.com:65536", "https://example.com:abc", "https://example.com:-1",
            "https://example.com\\@evil.test", "https://example.com/\nheader", "https://example.com/a b",
            "https://example.com/%zz", "https://[::1", "https://example..com").forEach { refused(it) }
    }

    @Test fun addressLengthAndEmptyValuesAreBoundedBeforeNavigation() {
        refused("   ", BrowserAddressFailure.Empty)
        refused("https://example.com/" + "a".repeat(MAX_BROWSER_URL_LENGTH), BrowserAddressFailure.Invalid)
        val prefix = "https://example.com/"
        val exactLimit = prefix + "a".repeat(MAX_BROWSER_URL_LENGTH - prefix.length)
        accepted(exactLimit, exactLimit)
    }

    @Test fun hostOriginsUseCanonicalHostAndEffectivePort() {
        val blocked = BrowserPolicy(listOf("https://HOST.example:443/manage", "http://localhost:3000", "https://[::1]:4443"))
        listOf("https://host.example", "https://HOST.EXAMPLE:443/x", "https://host.example./x", "https://host.example:443/?q=x#y")
            .forEach { refused(it, BrowserAddressFailure.ApplicationOrigin, blocked) }
        refused("http://LOCALHOST:3000/anything", BrowserAddressFailure.ApplicationOrigin, blocked)
        refused("https://[::1]:4443/", BrowserAddressFailure.ApplicationOrigin, blocked)
        accepted("https://host.example:444/", "https://host.example:444/", blocked)
        accepted("http://host.example/", "http://host.example/", blocked)
        accepted("https://host.example.evil.test/", "https://host.example.evil.test/", blocked)
    }

    @Test fun requestChecksDoNotInterpretRelativeOrSpecialUrlsAsTypedHostnames() {
        listOf("example.com", "localhost:8080", "/relative/path", "//example.com", "data:123", "javascript:123")
            .forEach { assertTrue(policy.parse(it, allowShorthand = false) is BrowserAddressResult.Rejected) }
        assertTrue(policy.parse("https://example.com/page", allowShorthand = false) is BrowserAddressResult.Target)
    }

    @Test fun invalidConfiguredOriginsDoNotGrantSpecialAccessOrBlockUnrelatedSites() {
        val blocked = BrowserPolicy(listOf("invalid", "null", "file:///tmp", "https://"))
        accepted("https://example.com", "https://example.com/", blocked)
        refused("javascript:alert(1)", BrowserAddressFailure.Protocol, blocked)
    }
}
