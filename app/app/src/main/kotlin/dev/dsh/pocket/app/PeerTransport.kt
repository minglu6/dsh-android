package dev.dsh.pocket.app

import dev.dsh.pocket.protocol.Route
import dev.dsh.pocket.protocol.classifyWsRoute

/**
 * Peer transport tabs on the connection screen: LAN and Tailscale only.
 * (Equal weight — LAN is never hidden behind Tailscale copy.)
 */
enum class PeerTransport {
    Lan,
    Tailscale,
    ;

    fun label(en: Boolean): String = when (this) {
        Lan -> if (en) "LAN" else "局域网"
        Tailscale -> "Tailscale"
    }
}

fun peerAddressHint(tab: PeerTransport, en: Boolean = false): String = when (tab) {
    PeerTransport.Lan -> "192.168.x.x:7690"
    PeerTransport.Tailscale -> if (en) "100.x.y.z:7690 or *.ts.net:7690" else "100.x.y.z:7690 或 *.ts.net:7690"
}

fun peerTransportTabs(): List<PeerTransport> = listOf(PeerTransport.Lan, PeerTransport.Tailscale)

fun PeerTransport.matchesProfile(profile: Profile): Boolean =
    when (classifyWsRoute(profile.host)) {
        Route.WsLan -> this == PeerTransport.Lan
        Route.WsTailscale -> this == PeerTransport.Tailscale
        Route.WsLocal -> false
    }
