package dev.dsh.pocket.protocol

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HostUiTest {
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).build()

    @Test fun ticketExchangeKeepsBearerOutOfUrlAndRetainsHttpOnlySameSiteCookie() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(303)
                .addHeader("Location", "/?pocket-ui=1&section=models")
                .addHeader("Set-Cookie", "dsh-pocket-web=short-session; Path=/; Max-Age=900; HttpOnly; SameSite=Strict"))
            val session = exchangeHostUiTicket(client, server.url("/"), "/pocket-ui/open", "one-use+ticket")
            val request = server.takeRequest()
            assertEquals("/pocket-ui/open", request.path)
            assertEquals("POST", request.method)
            assertEquals("ticket=one-use%2Bticket", request.body.readUtf8())
            assertEquals(null, request.getHeader("Authorization"))
            assertEquals(null, request.getHeader("Cookie"))
            assertEquals("models", session.startUrl.queryParameter("section"))
            assertTrue(session.cookie.httpOnly)
            assertTrue(session.cookieHeader.contains("SameSite=Strict"))
            assertFalse(session.startUrl.toString().contains("one-use"))
        }
    }

    @Test fun redirectCannotMoveSessionToDifferentOriginOrRevealTicket() {
        MockWebServer().use { server ->
            for (location in listOf("https://outside.invalid/?pocket-ui=1", "//outside.invalid/?pocket-ui=1",
                "/?pocket-ui=1&token=host-secret", "/?pocket-ui=1&ticket=grant", "/pocket/devices?pocket-ui=1", "/?pocket-ui=1#ticket")) {
                server.enqueue(MockResponse().setResponseCode(303).addHeader("Location", location)
                    .addHeader("Set-Cookie", "dsh-pocket-web=session; Path=/; HttpOnly; SameSite=Strict"))
                assertFails { exchangeHostUiTicket(client, server.url("/"), "/pocket-ui/open", "ticket") }
            }
        }
    }

    @Test fun sessionRejectsReadableCookiesAndNeverAcceptsUpstreamHostCookie() {
        MockWebServer().use { server ->
            for (header in listOf("dsh-auth-host=privileged; Path=/; HttpOnly", "dsh-pocket-web=session; Path=/",
                "dsh-pocket-web=session; Domain=localhost; Path=/; HttpOnly", "dsh-pocket-web=session; Path=/api; HttpOnly")) {
                server.enqueue(MockResponse().setResponseCode(303).addHeader("Location", "/?pocket-ui=1").addHeader("Set-Cookie", header))
                assertFails { exchangeHostUiTicket(client, server.url("/"), "/pocket-ui/open", "ticket") }
            }
        }
    }

    @Test fun authenticationFailureIsNotAnEmptyAuthenticatedPage() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(401).setBody("expired"))
            assertFails { exchangeHostUiTicket(client, server.url("/"), "/pocket-ui/open", "expired-ticket") }
        }
    }

    @Test fun webNavigationRejectsOtherHostsPortsSchemesCredentialsAndLocalFiles() {
        val policy = HostUiPolicy("http://192.0.2.1:7690/".toHttpUrl())
        assertTrue(policy.allows("http://192.0.2.1:7690/?pocket-ui=1&section=custom"))
        assertTrue(policy.allows("http://192.0.2.1:7690/plugins/vendor/client.js?rev=2"))
        for (url in listOf("http://192.0.2.1:7691/", "https://192.0.2.1:7690/", "http://outside.invalid/",
            "http://user@192.0.2.1:7690/", "http://192.0.2.1:7690\\@outside.invalid/", "file:///sdcard/private",
            "content://private", "javascript:alert(1)", "data:text/html,hello", "http://192.0.2.1:7690/\n")) {
            assertFalse(policy.allows(url), url)
        }
    }
}
