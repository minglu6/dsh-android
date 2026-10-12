package dev.dsh.pocket.protocol

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

class PocketError(val code: String, message: String) : Exception("$code: $message")

data class SessionSummary(
    val sessionId: String,
    val title: String?,
    val cwd: String?,
    val running: Boolean,
    val blank: Boolean,
    val updatedAt: Long,
    val parentSessionId: String?,
) {
    companion object {
        fun parse(e: JsonElement?): SessionSummary? {
            val id = e["sessionId"].str() ?: return null
            return SessionSummary(
                sessionId = id,
                title = e["projections"]["values"]["title"].str(),
                cwd = e["cwd"].str(),
                running = e["running"].bool() ?: false,
                blank = e["blank"].bool() ?: false,
                updatedAt = e["updatedAt"].long() ?: 0,
                parentSessionId = e["parentSessionId"].str(),
            )
        }
    }
}

data class ReasoningLevel(val id: String, val name: String, val description: String?)
data class ModelInfo(val provider: String, val id: String, val name: String, val description: String?, val efforts: List<ReasoningLevel>, val defaultEffort: String?)
data class ModelGroup(val id: String, val name: String, val models: List<ModelInfo>)
data class ModelCatalog(val groups: List<ModelGroup>, val defaultProvider: String?, val defaultModel: String?) {
    fun find(provider: String?, model: String?) = groups.flatMap { it.models }.firstOrNull { it.provider == provider && it.id == model }
    companion object {
        fun parse(e: JsonElement?): ModelCatalog = ModelCatalog(
            groups = e["groups"].arr().orEmpty().map { g ->
                val gid = g["id"].str() ?: ""
                ModelGroup(gid, g["name"].str() ?: gid, g["models"].arr().orEmpty().map { m ->
                    ModelInfo(
                        provider = gid, id = m["id"].str() ?: "", name = m["name"].str() ?: m["id"].str() ?: "",
                        description = m["description"].str(),
                        efforts = m["reasoning"]["efforts"].arr().orEmpty().map { r -> ReasoningLevel(r["id"].str() ?: "", r["name"].str() ?: r["id"].str() ?: "", r["description"].str()) },
                        defaultEffort = m["reasoning"]["defaultEffort"].str(),
                    )
                })
            },
            defaultProvider = e["default"]["provider"].str(),
            defaultModel = e["default"]["model"].str(),
        )
    }
}

data class Approval(val approvalId: String, val sessionId: String?, val toolName: String, val callId: String?, val reason: String?) {
    companion object {
        fun parse(e: JsonObject) = Approval(
            e["approvalId"].str() ?: "", e["sessionId"].str(), e["toolName"].str() ?: "", e["callId"].str(),
            e["displayReason"]["zh"].str() ?: e["reason"].str() ?: e["displayReason"]["en"].str(),
        )
    }
}

/** One image part for `prompt` (base64 payload, no data: URL prefix). */
data class PromptImage(val mediaType: String, val data: String, val name: String? = null)

/** User-selected remote path. Never auto-switched against the other. */
enum class RemoteTransport {
    Iroh,
    Tailscale,
}

/**
 * Pairing payload for one transport only — never mixes Tailscale host with iroh dial.
 *
 * - Iroh QR / deep link: `dshpocket://pair?n=<irohNodeId>&c=<6-digit>`
 * - Iroh paste: `<irohNodeId>#<6-digit>`
 * - Tailscale QR / deep link: `dshpocket://pair?h=<host:port>&c=<6-digit>`
 *
 * If both `n` and `h` appear, `n` wins (iroh advertisement). Modes are exclusive at dial time.
 */
sealed class PairingLink {
    abstract val code: String

    data class Iroh(val nodeId: String, override val code: String) : PairingLink()
    data class Tailscale(val host: String, val port: Int, override val code: String) : PairingLink()

    companion object {
        private val CODE = Regex("^\\d{6}$")
        private val TETHER_LINE = Regex("^([A-Za-z0-9_-]{16,})#(\\d{6})$")

        fun parse(text: String): PairingLink? {
            val s = text.trim()
            TETHER_LINE.matchEntire(s)?.let { m ->
                return Iroh(m.groupValues[1], m.groupValues[2])
            }
            if (!s.startsWith("dshpocket://pair")) return null
            val q = s.substringAfter('?', "").split('&')
                .filter { it.isNotEmpty() }
                .associate { it.substringBefore('=') to java.net.URLDecoder.decode(it.substringAfter('=', ""), "UTF-8") }
            val c = q["c"]?.takeIf { CODE.matches(it) } ?: return null
            q["n"]?.takeIf { it.isNotBlank() }?.let { return Iroh(it, c) }
            val h = q["h"]?.takeIf { it.isNotBlank() } ?: return null
            val hp = parseHostPort(h) ?: return null
            return Tailscale(hp.first, hp.second, c)
        }
    }
}

/** Parse `host:port` or `[ipv6]:port` (optional `ws://` / `wss://` prefix). */
fun parseHostPort(s: String): Pair<String, Int>? {
    val t = s.trim().removePrefix("ws://").removePrefix("wss://")
    val match = Regex("^\\[?([^\\[\\]]+?)]?:(\\d{1,5})$").find(t) ?: return null
    val port = match.groupValues[2].toInt().takeIf { it in 1..65535 } ?: return null
    val host = match.groupValues[1].takeIf { it.isNotBlank() } ?: return null
    return host to port
}
