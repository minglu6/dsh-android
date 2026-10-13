package dev.dsh.pocket.protocol

/**
 * Pure `dshpocket://` pairing URI policy shared with the plugin via
 * [contract/pairing-uri-cases.json](../../../../../contract/pairing-uri-cases.json).
 *
 * Happy path: `dshpocket://pair?h=<host:port>&c=<6-digit>`
 * Legacy iroh forms (`n=` / `nodeId#code`) are rejected.
 */
object PairingUriPolicy {
    private val CODE = Regex("^\\d{6}$")

    fun parse(text: String): PairingLink? {
        val s = text.trim()
        if (!s.startsWith("dshpocket://pair")) return null
        val q = s.substringAfter('?', "").split('&')
            .filter { it.isNotEmpty() }
            .associate {
                it.substringBefore('=') to
                    java.net.URLDecoder.decode(it.substringAfter('=', ""), "UTF-8")
            }
        val c = q["c"]?.takeIf { CODE.matches(it) } ?: return null
        // Explicitly ignore legacy iroh node-id advertisements.
        if (!q["n"].isNullOrBlank()) return null
        val h = q["h"]?.takeIf { it.isNotBlank() } ?: return null
        val hp = parseHostPort(h) ?: return null
        return PairingLink(hp.first, hp.second, c)
    }
}
