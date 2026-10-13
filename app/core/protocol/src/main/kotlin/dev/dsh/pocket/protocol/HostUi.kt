package dev.dsh.pocket.protocol

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** No phone bearer or Host process credential is ever handed to the WebView. */
class HostUiSession(val origin: HttpUrl, val startUrl: HttpUrl, val cookie: Cookie) {
    val cookieHeader: String get() = cookie.toString() + "; SameSite=Strict"
}

class HostUiPolicy(val origin: HttpUrl) {
    fun allows(url: String): Boolean {
        if (url.any { it == '\\' || it <= ' ' || it == '\u007f' }) return false
        val target = url.toHttpUrlOrNull() ?: return false
        return target.username.isEmpty() && target.password.isEmpty() &&
            target.scheme == origin.scheme && target.host == origin.host && target.port == origin.port
    }
}

private val hostUiHttp by lazy {
    PocketClient.defaultHttp().newBuilder().followRedirects(false).followSslRedirects(false)
        .callTimeout(20, TimeUnit.SECONDS).build()
}

/** Exchanges one short-lived grant in native networking, without following a credential-bearing redirect. */
suspend fun exchangeHostUiTicket(origin: HttpUrl, path: String, ticket: String): HostUiSession = withContext(Dispatchers.IO) {
    require(origin.username.isEmpty() && origin.password.isEmpty() && origin.encodedPath == "/" && origin.query == null)
    require(path == "/pocket-ui/open" && ticket.isNotBlank())
    exchangeHostUiTicket(hostUiHttp, origin, path, ticket)
}

internal fun exchangeHostUiTicket(client: OkHttpClient, origin: HttpUrl, path: String, ticket: String): HostUiSession {
    val request = Request.Builder().url(origin.resolve(path)!!)
        .post(FormBody.Builder().add("ticket", ticket).build()).build()
    return client.newCall(request).execute().use { response ->
        check(response.code == 303) { "Host UI authentication failed (${response.code})" }
        val location = response.header("Location") ?: error("Host UI redirect missing")
        val target = origin.resolve(location) ?: error("Invalid Host UI redirect")
        check(HostUiPolicy(origin).allows(target.toString()) && target.encodedPath == "/" &&
            target.queryParameter("pocket-ui") == "1" && target.queryParameter("token") == null &&
            target.queryParameter("ticket") == null && target.fragment == null) { "Unsafe Host UI redirect" }
        val cookies = response.headers("Set-Cookie").mapNotNull { Cookie.parse(origin, it) }
        val cookie = cookies.singleOrNull { it.name == "dsh-pocket-web" } ?: error("Host UI session cookie missing")
        check(cookie.httpOnly && cookie.hostOnly && cookie.domain == origin.host && cookie.path == "/" &&
            (origin.scheme != "https" || cookie.secure)) { "Unsafe Host UI cookie" }
        HostUiSession(origin, target, cookie)
    }
}
