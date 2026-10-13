import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { isTailscaleHost } from './advertise-host.js'
import { normalizeTransport } from './transport-pref.js'

const PKG = JSON.parse(readFileSync(join(dirname(fileURLToPath(import.meta.url)), '..', 'package.json'), 'utf8'))

/**
 * Phone QR / deep link — host:port only.
 *
 * `dshpocket://pair?h=<host:port>&c=<code>`
 *
 * @param {{ code?: string, host?: string, port?: number, transport?: string }} opts
 */
export function pairingUri({ code, host, port, transport }) {
  if (!code) return undefined
  const mode = normalizeTransport(transport) ?? (host ? 'tailscale' : null)
  if (mode !== 'tailscale') return undefined
  if (!host || !port) return undefined
  return `dshpocket://pair?h=${encodeURIComponent(`${host}:${port}`)}&c=${encodeURIComponent(code)}`
}

/**
 * Pasteable one-liner — same URI shape as the QR payload.
 *
 * @param {{ code?: string, host?: string, port?: number, transport?: string }} opts
 */
export function pairingString({ code, host, port, transport }) {
  return pairingUri({ code, host, port, transport })
}

/**
 * Report how phones reach this host:port — Tailscale CGNAT/MagicDNS vs LAN/other.
 * @param {string} host
 * @returns {'tailscale'|'lan'}
 */
export function connectionTypeForHost(host) {
  return isTailscaleHost(host) ? 'tailscale' : 'lan'
}

/**
 * Facade used by management HTTP routes and the client half.
 * @param {{
 *   host: string,
 *   port: number,
 *   devices: any,
 *   pairing: any,
 *   server: any,
 *   openPairing: Function,
 *   getTransport: () => 'tailscale',
 *   setTransport?: (mode: string) => 'tailscale',
 * }} opts
 */
export function createManageApi({
  host, port, devices, pairing, server, openPairing, getTransport, setTransport,
  instanceId, getDiagnostics, getMdns,
}) {
  const transport = () => getTransport()
  const withOnline = () => {
    const online = server.onlineDeviceIds()
    return devices.list().map((d) => ({ ...d, online: online.has(d.id) }))
  }
  const enrichPairing = (status) => {
    const code = status?.code
    if (!code) return status?.open ? status : { open: false }
    const mode = transport()
    return {
      open: true,
      ...status,
      code,
      transport: mode,
      host,
      port,
      uri: pairingUri({ code, host, port, transport: mode }),
      pairingString: pairingString({ code, host, port, transport: mode }),
    }
  }
  return {
    openPairing: () => {
      openPairing()
      return enrichPairing(pairing.status())
    },
    closePairing: () => { pairing.close(); return { open: false } },
    pairingStatus: () => enrichPairing(pairing.status()),
    listDevices: withOnline,
    revokeDevice: (id) => server.revokeDevice(id),
    renameDevice: (id, name) => devices.rename(id, name),
    setTransport: (mode) => {
      if (typeof setTransport !== 'function') throw new Error('setTransport unavailable')
      return { transport: setTransport(mode) }
    },
    getDiagnostics: () => {
      if (typeof getDiagnostics !== 'function') throw new Error('diagnostics unavailable')
      return getDiagnostics()
    },
    getStatus: () => {
      const addr = server.address()
      const listening = addr !== null
      const listenHost = listening && typeof addr === 'object' ? addr.address : host
      const listenPort = listening && typeof addr === 'object' ? addr.port : port
      const mode = transport()
      return {
        running: listening,
        version: PKG.version,
        plugin: PKG.name,
        transport: mode,
        host,
        port: listenPort,
        instanceId: typeof instanceId === 'string' ? instanceId : undefined,
        mdns: typeof getMdns === 'function' ? Boolean(getMdns()) : false,
        /** Phone path: `tailscale` | `lan` from the advertised pairing host. */
        connectionType: connectionTypeForHost(host),
        listen: listening
          ? [{ host: listenHost, port: listenPort, address: typeof addr === 'string' ? addr : `${listenHost}:${listenPort}` }]
          : [],
        pairing: enrichPairing(pairing.status()),
        devices: withOnline(),
      }
    },
  }
}
