import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { normalizeTransport } from './transport-pref.js'

const PKG = JSON.parse(readFileSync(join(dirname(fileURLToPath(import.meta.url)), '..', 'package.json'), 'utf8'))

/**
 * Phone QR / deep link for the selected transport only (never both).
 *
 * - iroh: `dshpocket://pair?n=<nodeId>&c=<code>`
 * - Tailscale: `dshpocket://pair?h=<host:port>&c=<code>`
 *
 * @param {{ code?: string, nodeId?: string, host?: string, port?: number, transport?: string }} opts
 */
export function pairingUri({ code, nodeId, host, port, transport }) {
  if (!code) return undefined
  const mode = normalizeTransport(transport) ?? (nodeId ? 'iroh' : (host ? 'tailscale' : null))
  if (mode === 'iroh') {
    if (!nodeId) return undefined
    return `dshpocket://pair?n=${encodeURIComponent(nodeId)}&c=${encodeURIComponent(code)}`
  }
  if (mode === 'tailscale') {
    if (!host || !port) return undefined
    return `dshpocket://pair?h=${encodeURIComponent(`${host}:${port}`)}&c=${encodeURIComponent(code)}`
  }
  return undefined
}

/**
 * Pasteable one-liner for the selected transport.
 * - iroh: `<nodeId>#<code>`
 * - Tailscale: `ws://host:port` is not used; return the same URI (or host:port + code via fields).
 *
 * @param {{ code?: string, nodeId?: string, host?: string, port?: number, transport?: string }} opts
 */
export function pairingString({ code, nodeId, host, port, transport }) {
  if (!code) return undefined
  const mode = normalizeTransport(transport) ?? (nodeId ? 'iroh' : (host ? 'tailscale' : null))
  if (mode === 'iroh') {
    if (!nodeId) return undefined
    return `${nodeId}#${code}`
  }
  if (mode === 'tailscale') {
    if (!host || !port) return undefined
    // Same shape as QR payload so phone paste/scan share one parser path.
    return `dshpocket://pair?h=${encodeURIComponent(`${host}:${port}`)}&c=${encodeURIComponent(code)}`
  }
  return undefined
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
 *   getNodeId?: () => string|null|undefined,
 *   getTransport: () => 'iroh'|'tailscale',
 *   setTransport?: (mode: string) => 'iroh'|'tailscale',
 * }} opts
 */
export function createManageApi({
  host, port, devices, pairing, server, openPairing, getNodeId, getTransport, setTransport,
}) {
  const nodeId = () => (typeof getNodeId === 'function' ? getNodeId() : null) || null
  const transport = () => getTransport()
  const withOnline = () => {
    const online = server.onlineDeviceIds()
    return devices.list().map((d) => ({ ...d, online: online.has(d.id) }))
  }
  const enrichPairing = (status) => {
    const code = status?.code
    if (!code) return status?.open ? status : { open: false }
    const mode = transport()
    const n = nodeId()
    if (mode === 'iroh') {
      if (!n) {
        return {
          open: true,
          ...status,
          code,
          transport: mode,
          nodeId: undefined,
          uri: undefined,
          pairingString: undefined,
          irohReady: false,
          error: 'iroh-not-ready',
        }
      }
      return {
        open: true,
        ...status,
        code,
        transport: mode,
        nodeId: n,
        uri: pairingUri({ code, nodeId: n, transport: mode }),
        pairingString: pairingString({ code, nodeId: n, transport: mode }),
        irohReady: true,
      }
    }
    // Tailscale mode — advertise host:port only; iroh not required.
    return {
      open: true,
      ...status,
      code,
      transport: mode,
      host,
      port,
      nodeId: n || undefined,
      uri: pairingUri({ code, host, port, transport: mode }),
      pairingString: pairingString({ code, host, port, transport: mode }),
      irohReady: Boolean(n),
    }
  }
  return {
    openPairing: () => {
      const mode = transport()
      if (mode === 'iroh' && !nodeId()) {
        throw new Error('iroh sidecar is not ready — cannot open remote pairing')
      }
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
    getStatus: () => {
      const addr = server.address()
      const listening = addr !== null
      const listenHost = listening && typeof addr === 'object' ? addr.address : host
      const listenPort = listening && typeof addr === 'object' ? addr.port : port
      const n = nodeId()
      const mode = transport()
      const remoteReady = mode === 'iroh' ? Boolean(n) : listening
      return {
        running: remoteReady || listening,
        version: PKG.version,
        plugin: PKG.name,
        transport: mode,
        host: listenHost,
        port: listenPort,
        nodeId: n,
        irohReady: Boolean(n),
        /** `direct` | `relay` | null — from pocket-host PeerPath reports. */
        connectionType: typeof server.connectionType === 'function' ? server.connectionType() : null,
        irohConnections: typeof server.irohConnections === 'function' ? server.irohConnections() : [],
        listen: listening
          ? [{ host: listenHost, port: listenPort, address: typeof addr === 'string' ? addr : `${listenHost}:${listenPort}` }]
          : [],
        pairing: enrichPairing(pairing.status()),
        devices: withOnline(),
      }
    },
  }
}
