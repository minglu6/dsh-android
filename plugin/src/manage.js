import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { selectNetwork } from './advertise-host.js'
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
 * Facade used by management HTTP routes and the client half.
 * @param {{
 *   getNetworks: () => Array<{type: 'lan'|'tailscale', host: string}>,
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
  getNetworks, port, devices, pairing, server, openPairing, getTransport, setTransport,
  instanceId, getDiagnostics, getMdns,
}) {
  const transport = () => getTransport()
  const withOnline = () => {
    const online = server.onlineDeviceIds()
    return devices.list().map((d) => ({ ...d, online: online.has(d.id) }))
  }
  const listenPort = () => {
    const address = server.address()
    return address && typeof address === 'object' ? address.port : port
  }
  const enrichPairing = (status, selected, selectedPort) => {
    if (!selected) return { open: false }
    const { host } = selected
    const code = status?.code
    if (!code) return status?.open ? status : { open: false }
    const mode = transport()
    return {
      open: true,
      ...status,
      code,
      transport: mode,
      host,
      port: selectedPort,
      uri: pairingUri({ code, host, port: selectedPort, transport: mode }),
      pairingString: pairingString({ code, host, port: selectedPort, transport: mode }),
    }
  }
  return {
    openPairing: (network) => {
      const selected = selectNetwork(getNetworks(), network)
      if (!selected || server.address() === null) {
        throw Object.assign(new Error('No reachable network is available for pairing'), { statusCode: 503 })
      }
      const port = listenPort()
      openPairing({ ...selected, port })
      return enrichPairing(pairing.status(), selected, port)
    },
    closePairing: () => { pairing.close(); return { open: false } },
    pairingStatus: (network) => enrichPairing(pairing.status(), selectNetwork(getNetworks(), network), listenPort()),
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
    getStatus: (network) => {
      const networks = getNetworks()
      const selected = selectNetwork(networks, network)
      const addr = server.address()
      const listening = addr !== null
      const listenHost = listening && typeof addr === 'object' ? addr.address : ''
      const port = listenPort()
      const mode = transport()
      return {
        running: listening,
        version: PKG.version,
        plugin: PKG.name,
        transport: mode,
        networks,
        host: selected?.host ?? '',
        port,
        instanceId: typeof instanceId === 'string' ? instanceId : undefined,
        mdns: typeof getMdns === 'function' ? Boolean(getMdns()) : false,
        connectionType: selected?.type ?? null,
        listen: listening
          ? [{ host: listenHost, port, address: typeof addr === 'string' ? addr : `${listenHost}:${port}` }]
          : [],
        pairing: enrichPairing(pairing.status(), selected, port),
        devices: withOnline(),
      }
    },
  }
}
