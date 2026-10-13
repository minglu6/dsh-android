package dev.dsh.pocket.app

import dev.dsh.pocket.protocol.PairingLink
import dev.dsh.pocket.protocol.isLoopbackHost
import dev.dsh.pocket.protocol.parseHostPort
import java.net.InetSocketAddress
import java.net.Socket

/**
 * On-device path: dsh + dsh-plugin-pocket run inside Termux; this APK is a controller
 * that dials loopback WebSocket only. The app never embeds Node/dsh and never runs su/tsu.
 */
object TermuxSidecar {
    const val DEFAULT_HOST = "127.0.0.1"
    const val DEFAULT_PORT = 7690

    /** Community Termux installers — do not vendor APKs in this repo. */
    const val TERMUX_GITHUB_RELEASES = "https://github.com/termux/termux-app/releases"
    const val TERMUX_FDROID = "https://f-droid.org/packages/com.termux/"

    /**
     * Copyable bootstrap for Termux. Points at community installers / npm; does not ship runtimes.
     * `host: 127.0.0.1` keeps the pocket listener on loopback for this app.
     */
    val BOOTSTRAP_COMMANDS: String =
        """
        |# 1) Install Termux from GitHub releases or F-Droid (not Play Store)
        |#    https://github.com/termux/termux-app/releases
        |#    https://f-droid.org/packages/com.termux/
        |
        |pkg update
        |pkg install -y nodejs-lts git
        |
        |# 2) Install dsh (see deepseek-harness docs), then add the pocket plugin
        |#    from https://github.com/minglu6/dsh-android (plugin/)
        |dsh plugin --profile web add /path/to/dsh-android/plugin
        |
        |# 3) Bind loopback so the phone app can reach pocket on 127.0.0.1
        |#    In ~/.dsh/profiles/web/cordis.patch.yml under dsh-pocket config:
        |#      host: 127.0.0.1
        |#      port: 7690
        |
        |dsh web
        |
        |# 4) Pair: Settings →「手机连接」→ copy dshpocket:// … into this app
        |#    Or paste the owner token:
        |#      cat ~/.dsh/pocket/token
        """.trimMargin()

    /** Reminder only — run elevated tools inside Termux yourself; the APK never invokes su. */
    val PRIVILEGE_REMINDER_COMMANDS: String =
        """
        |# Optional: start dsh under tsu/su inside Termux when tools need root.
        |# The Android app never runs su / tsu.
        |tsu -c 'dsh web'
        |# or: su -c 'dsh web'
        """.trimMargin()

    fun defaultEndpoint(): Pair<String, Int> = DEFAULT_HOST to DEFAULT_PORT

    fun isTermuxProfile(host: String): Boolean = isLoopbackHost(host)

    /** Soft TCP probe used before pairing / to decide whether to show install guidance. */
    fun probeLoopback(host: String = DEFAULT_HOST, port: Int = DEFAULT_PORT, timeoutMs: Int = 1_200): Boolean {
        if (!isLoopbackHost(host) || port !in 1..65535) return false
        return runCatching {
            Socket().use { it.connect(InetSocketAddress(host, port), timeoutMs) }
        }.isSuccess
    }

    /**
     * Credentials accepted by the Termux connect sheet:
     * - `dshpocket://pair?h=…&c=……` (host forced to loopback when missing / non-loopback)
     * - `host:port` + separate 6-digit code
     * - pasted owner / device token (hello auth)
     */
    sealed class Credential {
        data class PairCode(val host: String, val port: Int, val code: String) : Credential()
        data class Token(val host: String, val port: Int, val token: String) : Credential()
    }

    fun parseCredential(
        paste: String,
        portFallback: Int = DEFAULT_PORT,
        codeFallback: String = "",
        hostFallback: String = DEFAULT_HOST,
    ): Credential? {
        val raw = paste.trim()
        if (raw.isEmpty() && codeFallback.length != 6) return null

        PairingLink.parse(raw)?.let { link ->
            val host = if (isLoopbackHost(link.host)) link.host else hostFallback
            return Credential.PairCode(host, link.port, link.code)
        }

        parseHostPort(raw)?.let { (h, p) ->
            val code = codeFallback.filter(Char::isDigit).take(6)
            if (code.length == 6) {
                val host = if (isLoopbackHost(h)) h else hostFallback
                return Credential.PairCode(host, p, code)
            }
            return null
        }

        val codeOnly = raw.filter(Char::isDigit)
        if (codeOnly.length == 6 && raw.length <= 8) {
            val port = portFallback.takeIf { it in 1..65535 } ?: return null
            return Credential.PairCode(hostFallback, port, codeOnly)
        }

        val token = raw.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return null
        if (looksLikeToken(token)) {
            val port = portFallback.takeIf { it in 1..65535 } ?: return null
            return Credential.Token(hostFallback, port, token)
        }

        val code = codeFallback.filter(Char::isDigit).take(6)
        if (code.length == 6) {
            val port = portFallback.takeIf { it in 1..65535 } ?: return null
            return Credential.PairCode(hostFallback, port, code)
        }
        return null
    }

    /** Owner tokens are base64url (≥16); device tokens are base64url from randomBytes(32). */
    fun looksLikeToken(s: String): Boolean {
        if (s.length < 16 || s.length > 256) return false
        if (s.any { it.isWhitespace() }) return false
        if (s.startsWith("dshpocket://", ignoreCase = true)) return false
        if (s.all { it.isDigit() } && s.length == 6) return false
        return s.all { it.isLetterOrDigit() || it == '-' || it == '_' || it == '=' || it == '+' || it == '/' }
    }
}
