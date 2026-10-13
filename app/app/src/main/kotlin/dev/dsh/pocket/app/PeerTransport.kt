package dev.dsh.pocket.app

import android.content.Context
import android.content.pm.PackageManager
import dev.dsh.pocket.protocol.Route
import dev.dsh.pocket.protocol.classifyWsRoute

/**
 * First-class peer transport modes on the connection screen
 * (equal tabs — LAN / Tailscale / 本机 Termux).
 *
 * Pattern mirrors Syncthing device discovery + Tailscale peer list + LocalSend nearby:
 * each transport is visible and selectable; LAN is never buried behind Tailscale copy.
 * 本机 always appears (Termux sidecar path from #47); APK never embeds Node/dsh.
 */
enum class PeerTransport {
    Lan,
    Tailscale,
    Local,
    ;

    fun label(en: Boolean): String = when (this) {
        Lan -> if (en) "LAN" else "局域网"
        Tailscale -> "Tailscale"
        Local -> if (en) "This device" else "本机"
    }
}

/** Address field hint for [PeerTransport] — neutral, not Tailscale-first. */
fun peerAddressHint(tab: PeerTransport, en: Boolean = false): String = when (tab) {
    PeerTransport.Lan -> "192.168.x.x:7690"
    PeerTransport.Tailscale -> if (en) "100.x.y.z:7690 or name.ts.net:7690" else "100.x.y.z:7690 或 name.ts.net:7690"
    PeerTransport.Local -> "${TermuxSidecar.DEFAULT_HOST}:${TermuxSidecar.DEFAULT_PORT}"
}

/** Default host:port seed when opening the address sheet from a transport tab. */
fun peerAddressSeed(tab: PeerTransport): String = when (tab) {
    PeerTransport.Lan -> ""
    PeerTransport.Tailscale -> ""
    PeerTransport.Local -> "${TermuxSidecar.DEFAULT_HOST}:${TermuxSidecar.DEFAULT_PORT}"
}

fun PeerTransport.matchesProfile(profile: Profile): Boolean =
    when (classifyWsRoute(profile.host)) {
        Route.WsLan -> this == PeerTransport.Lan
        Route.WsTailscale -> this == PeerTransport.Tailscale
        Route.WsLocal -> this == PeerTransport.Local
    }

/**
 * Tabs shown on the connection screen.
 * [includeLocal] defaults true so 本机 Termux stays first-class (#47).
 */
fun peerTransportTabs(includeLocal: Boolean = true): List<PeerTransport> =
    buildList {
        add(PeerTransport.Lan)
        add(PeerTransport.Tailscale)
        if (includeLocal) add(PeerTransport.Local)
    }

/** Optional package probe — UI does not gate the 本机 tab on this. */
object TermuxPresence {
    const val PACKAGE = "com.termux"

    fun isInstalled(context: Context): Boolean = isInstalled(context.packageManager)

    fun isInstalled(pm: PackageManager): Boolean =
        runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                pm.getPackageInfo(PACKAGE, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(PACKAGE, 0)
            }
            true
        }.getOrDefault(false)
}

/**
 * Resolve "连接附近设备": prefer a single nearby host; otherwise stay on LAN with the list.
 */
sealed class NearbyConnectAction {
    data class OpenPair(val host: DiscoveredHost) : NearbyConnectAction()
    data object ShowLanTab : NearbyConnectAction()
}

fun resolveNearbyConnect(nearby: List<DiscoveredHost>): NearbyConnectAction =
    when {
        nearby.size == 1 -> NearbyConnectAction.OpenPair(nearby.first())
        else -> NearbyConnectAction.ShowLanTab
    }
