package dev.dsh.pocket.app

import java.net.IDN
import java.net.URI
import java.util.Locale

internal const val MAX_BROWSER_URL_LENGTH = 16 * 1024

internal enum class BrowserAddressFailure { Empty, Invalid, Protocol, Credentials, ApplicationOrigin }

internal sealed interface BrowserAddressResult {
    data class Target(val url: String, val title: String) : BrowserAddressResult
    data class Rejected(val reason: BrowserAddressFailure) : BrowserAddressResult
}

/** HTTP(S) origin matching is deliberately not a DNS / private-network firewall. */
internal data class BrowserOrigin(val scheme: String, val host: String, val port: Int)

internal class BrowserPolicy(applicationOrigins: Collection<String> = emptyList()) {
    private val blockedOrigins = applicationOrigins.mapNotNull { origin(it) }.toSet()

    fun parse(input: String, allowShorthand: Boolean = true): BrowserAddressResult {
        val text = input.trim()
        if (text.isEmpty()) return BrowserAddressResult.Rejected(BrowserAddressFailure.Empty)
        if (text.length > MAX_BROWSER_URL_LENGTH || text.any { it <= ' ' || it == '\\' || it == '\u007f' }) {
            return BrowserAddressResult.Rejected(BrowserAddressFailure.Invalid)
        }
        val prefix = text.substringBefore(':').lowercase(Locale.ROOT)
        val hostPort = HOST_PORT.containsMatchIn(text) && prefix !in RESERVED_SCHEMES
        val explicitScheme = SCHEME.containsMatchIn(text) && !hostPort
        if (!explicitScheme && !allowShorthand) return BrowserAddressResult.Rejected(BrowserAddressFailure.Protocol)
        if (explicitScheme && prefix != "http" && prefix != "https") {
            return BrowserAddressResult.Rejected(BrowserAddressFailure.Protocol)
        }
        val candidate = if (explicitScheme) text else "https://$text"
        val parsed = runCatching { URI(candidate) }.getOrNull()
            ?: return BrowserAddressResult.Rejected(BrowserAddressFailure.Invalid)
        val scheme = parsed.scheme?.lowercase(Locale.ROOT)
        if (scheme != "http" && scheme != "https") return BrowserAddressResult.Rejected(BrowserAddressFailure.Protocol)
        if (parsed.rawUserInfo != null || parsed.rawAuthority?.contains('@') == true) {
            return BrowserAddressResult.Rejected(BrowserAddressFailure.Credentials)
        }
        val authority = parsed.rawAuthority ?: return BrowserAddressResult.Rejected(BrowserAddressFailure.Invalid)
        // Percent escapes in authority can disagree between URI and Chromium (including encoded userinfo).
        if ('%' in authority || authority.endsWith(':')) return BrowserAddressResult.Rejected(BrowserAddressFailure.Invalid)
        val host = canonicalHost(parsed) ?: return BrowserAddressResult.Rejected(BrowserAddressFailure.Invalid)
        val port = parsed.port
        if (port != -1 && port !in 1..65535) return BrowserAddressResult.Rejected(BrowserAddressFailure.Invalid)
        // A non-numeric port makes java.net.URI.host null; never discard that suffix when accepting IDNs.
        val canonicalAuthority = host + if (port == -1) "" else ":$port"
        val path = parsed.rawPath.orEmpty().ifEmpty { "/" }
        val url = buildString {
            append(scheme).append("://").append(canonicalAuthority).append(path)
            parsed.rawQuery?.let { append('?').append(it) }
            parsed.rawFragment?.let { append('#').append(it) }
        }
        val asciiUrl = runCatching { URI(url).toASCIIString() }.getOrNull()
            ?: return BrowserAddressResult.Rejected(BrowserAddressFailure.Invalid)
        if (BrowserOrigin(scheme, host, if (port == -1) defaultPort(scheme) else port) in blockedOrigins) {
            return BrowserAddressResult.Rejected(BrowserAddressFailure.ApplicationOrigin)
        }
        return BrowserAddressResult.Target(asciiUrl, host.removePrefix("[").removeSuffix("]"))
    }

    companion object {
        private val SCHEME = Regex("^[A-Za-z][A-Za-z\\d+.-]*:")
        private val HOST_PORT = Regex("^[A-Za-z0-9.-]+:\\d+(?:[/?#]|$)")
        private val RESERVED_SCHEMES = setOf("http", "https", "file", "content", "data", "javascript", "blob", "about", "intent", "chrome", "ftp", "ws", "wss", "mailto", "tel")

        private fun defaultPort(scheme: String) = if (scheme == "https") 443 else 80

        private fun canonicalHost(uri: URI): String? {
            val authority = uri.rawAuthority ?: return null
            val raw = uri.host ?: run {
                // IDN hostnames are not recognized by java.net.URI.host. A port must still be numeric.
                val parts = authority.split(':')
                if (parts.size > 2 || (parts.size == 2 && parts[1].toIntOrNull() == null)) return null
                if (parts.size == 2) return null // URI cannot expose the port on this non-server authority.
                parts[0]
            }
            if (raw.startsWith('[')) return raw.lowercase(Locale.ROOT)
            return runCatching { IDN.toASCII(raw, IDN.USE_STD3_ASCII_RULES).trimEnd('.').lowercase(Locale.ROOT) }
                .getOrNull()?.takeIf { it.isNotEmpty() }
        }

        private fun origin(value: String): BrowserOrigin? {
            val uri = runCatching { URI(value) }.getOrNull() ?: return null
            val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
            if (scheme != "http" && scheme != "https") return null
            return BrowserOrigin(scheme, canonicalHost(uri) ?: return null, if (uri.port == -1) defaultPort(scheme) else uri.port)
        }
    }
}
