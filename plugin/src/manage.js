import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const PKG = JSON.parse(readFileSync(join(dirname(fileURLToPath(import.meta.url)), '..', 'package.json'), 'utf8'))

/**
 * Phone QR / deep link — pure iroh (tether-style). No host/port.
 * `dshpocket://pair?n=<nodeId>&c=<code>`
 */
export function pairingUri({ code, nodeId }) {
  if (!nodeId || !code) return undefined
  return `dshpocket://pair?n=${encodeURIComponent(nodeId)}&c=${encodeURIComponent(code)}`
}

/** Pasteable one-liner, same shape as dsh-tether: `<nodeId>#<code>`. */
export function pairingString({ code, nodeId }) {
  if (!nodeId || !code) return undefined
  return `${nodeId}#${code}`
}

/**
 * Facade used by management HTTP routes and the client half.
 * @param {{ host: string, port: number, devices: any, pairing: any, server: any, openPairing: Function, getNodeId?: () => string|null|undefined }} opts
 */
export function createManageApi({ host, port, devices, pairing, server, openPairing, getNodeId }) {
  const nodeId = () => (typeof getNodeId === 'function' ? getNodeId() : null) || null
  const withOnline = () => {
    const online = server.onlineDeviceIds()
    return devices.list().map((d) => ({ ...d, online: online.has(d.id) }))
  }
  const enrichPairing = (status) => {
    // `pairing.open()` returns {code,expiresInSec} without `open`; status() includes open:true.
    const code = status?.code
    if (!code) return status?.open ? status : { open: false }
    const n = nodeId()
    if (!n) {
      return {
        open: true,
        ...status,
        code,
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
      nodeId: n,
      uri: pairingUri({ code, nodeId: n }),
      pairingString: pairingString({ code, nodeId: n }),
      irohReady: true,
    }
  }
  return {
    openPairing: () => {
      const n = nodeId()
      if (!n) {
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
    getStatus: () => {
      const addr = server.address()
      const listening = addr !== null
      const listenHost = listening && typeof addr === 'object' ? addr.address : host
      const listenPort = listening && typeof addr === 'object' ? addr.port : port
      const n = nodeId()
      return {
        running: Boolean(n) || listening,
        version: PKG.version,
        plugin: PKG.name,
        // Loopback WS is internal/dev only — still reported for diagnostics.
        host: listenHost,
        port: listenPort,
        nodeId: n,
        irohReady: Boolean(n),
        listen: listening
          ? [{ host: listenHost, port: listenPort, address: typeof addr === 'string' ? addr : `${listenHost}:${listenPort}` }]
          : [],
        pairing: enrichPairing(pairing.status()),
        devices: withOnline(),
      }
    },
  }
}
