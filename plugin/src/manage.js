import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const PKG = JSON.parse(readFileSync(join(dirname(fileURLToPath(import.meta.url)), '..', 'package.json'), 'utf8'))

/** Build a phone-side deep link for the active pairing window. */
export function pairingUri({ host, port, code }) {
  return `dshpocket://pair?h=${encodeURIComponent(`${host}:${port}`)}&c=${encodeURIComponent(code)}`
}

/**
 * Facade used by management HTTP routes and the client half.
 * @param {{ host: string, port: number, devices: any, pairing: any, server: any, openPairing: Function }} opts
 */
export function createManageApi({ host, port, devices, pairing, server, openPairing }) {
  const withOnline = () => {
    const online = server.onlineDeviceIds()
    return devices.list().map((d) => ({ ...d, online: online.has(d.id) }))
  }
  const enrichPairing = (status) => {
    if (!status.open || !status.code) return status
    return { ...status, host, port, uri: pairingUri({ host, port, code: status.code }) }
  }
  return {
    openPairing,
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
      return {
        running: listening,
        version: PKG.version,
        plugin: PKG.name,
        host: listenHost,
        port: listenPort,
        listen: listening
          ? [{ host: listenHost, port: listenPort, address: typeof addr === 'string' ? addr : `${listenHost}:${listenPort}` }]
          : [],
        pairing: enrichPairing(pairing.status()),
        devices: withOnline(),
      }
    },
  }
}
