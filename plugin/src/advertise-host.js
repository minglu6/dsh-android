import { networkInterfaces } from 'node:os'

/**
 * Tailscale CGNAT (100.64.0.0/10) or MagicDNS.
 * @param {string} host
 */
export function isTailscaleHost(host) {
  if (!host || typeof host !== 'string') return false
  if (host.endsWith('.ts.net')) return true
  const p = host.split('.').map((x) => Number(x))
  return p.length === 4 && p.every((n) => Number.isInteger(n) && n >= 0 && n <= 255)
    && p[0] === 100 && p[1] >= 64 && p[1] <= 127
}

/**
 * Pick the address advertised in pairing URIs.
 * Prefer an explicit non-loopback config host; else Tailscale IP; else first LAN IPv4.
 *
 * @param {string | undefined | null} configured
 * @returns {string}
 */
export function pickAdvertiseHost(configured) {
  const pinned = typeof configured === 'string' ? configured.trim() : ''
  if (pinned && pinned !== '127.0.0.1' && pinned !== '0.0.0.0' && pinned !== '::' && pinned !== 'localhost') {
    return pinned
  }
  const addrs = []
  for (const list of Object.values(networkInterfaces())) {
    for (const n of list ?? []) {
      const family = n.family
      if (family !== 'IPv4' && family !== 4) continue
      if (n.internal) continue
      if (!n.address) continue
      addrs.push(n.address)
    }
  }
  return addrs.find((a) => isTailscaleHost(a)) ?? addrs[0] ?? (pinned || '127.0.0.1')
}

/**
 * WS bind host: explicit config wins; otherwise all interfaces so Tailscale + LAN work.
 * @param {string | undefined | null} configured
 */
export function pickBindHost(configured) {
  const pinned = typeof configured === 'string' ? configured.trim() : ''
  if (pinned) return pinned
  return '0.0.0.0'
}
