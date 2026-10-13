/**
 * Advertise the pocket WebSocket listener via mDNS/Bonjour (_dshpocket._tcp).
 *
 * Adapted from dsh-mobile gateway mDNS publish
 * (https://github.com/saya-ch/dsh-mobile src/gateway.ts), Apache-2.0.
 */
import { hostname } from 'node:os'
import Bonjour from 'bonjour-service'

export const MDNS_SERVICE_TYPE = 'dshpocket'
export const DISCOVERY_PROTOCOL = 1

/** @returns {string} */
export function discoveryDeviceName() {
  const value = [...hostname().trim()].filter((ch) => {
    const code = ch.codePointAt(0) ?? 0
    return code >= 0x20 && code !== 0x7f
  }).join('')
  return (value === '' ? 'dsh' : value).slice(0, 63)
}

/** @param {string} instanceId */
export function discoveryMdnsHost(instanceId) {
  const label = hostname().toLowerCase().replaceAll(/[^a-z0-9-]/gu, '-').replaceAll(/^-+|-+$/gu, '').slice(0, 40)
  return `${label === '' ? 'dsh' : label}-${instanceId.slice(0, 8)}.local`
}

/**
 * @param {{
 *   host: string,
 *   port: number,
 *   instanceId: string,
 *   deviceName?: string,
 *   onError?: (err: Error) => void,
 *   log?: (...a: unknown[]) => void,
 * }} opts
 */
export function startMdnsAdvertise(opts) {
  const deviceName = opts.deviceName ?? discoveryDeviceName()
  const onError = (error) => {
    try { opts.onError?.(error instanceof Error ? error : new Error(String(error))) } catch { /* ignore */ }
  }
  const bonjour = new Bonjour({ disableIPv6: true }, onError)
  // bonjour-service 1.4.4 does not forward multicast-dns EventEmitter errors
  // through errorCallback. Keep a listener on its underlying emitter for its lifetime.
  const mdns = /** @type {{ server: { mdns: { on(event: 'error', listener: (error: Error) => void): void } } }} */ (bonjour).server.mdns
  mdns.on('error', onError)
  const service = bonjour.publish({
    name: `${deviceName} (${opts.instanceId.slice(0, 8)})`,
    type: MDNS_SERVICE_TYPE,
    protocol: 'tcp',
    port: opts.port,
    host: discoveryMdnsHost(opts.instanceId),
    disableIPv6: true,
    txt: {
      deviceName,
      host: opts.host,
      port: String(opts.port),
      instanceId: opts.instanceId,
      protocol: String(DISCOVERY_PROTOCOL),
    },
  })
  opts.log?.(`mdns advertising _${MDNS_SERVICE_TYPE}._tcp (${deviceName}, ${opts.host}:${opts.port})`)
  return {
    deviceName,
    service,
    stop: () => new Promise((resolve) => {
      try {
        bonjour.unpublishAll(() => { bonjour.destroy(() => resolve()) })
      } catch {
        resolve()
      }
    }),
  }
}
