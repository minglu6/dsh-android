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
 * Discover phone-reachable IPv4 endpoints on the interfaces actually bound by WS.
 * A concrete bind (including a resolved config hostname) must not advertise other NICs.
 *
 * @param {string | undefined | null} configured
 * @param {string} boundHost Actual server.address().address, or the configured bind.
 * @param {ReturnType<typeof networkInterfaces>} [interfaces]
 * @returns {Array<{type: 'lan'|'tailscale', host: string}>}
 */
export function listAdvertiseNetworks(configured, boundHost = pickBindHost(configured), interfaces = networkInterfaces()) {
  const pinned = typeof configured === 'string' ? configured.trim() : ''
  const wildcard = boundHost === '0.0.0.0' || boundHost === '::'
  const explicit = pinned && pinned !== '0.0.0.0' && pinned !== '::'
  const networks = []
  for (const list of Object.values(interfaces)) {
    for (const n of list ?? []) {
      if (n.family !== 'IPv4' && n.family !== 4) continue
      if (n.internal || !n.address || n.address.startsWith('127.') || n.address === '0.0.0.0') continue
      if (!wildcard && n.address !== boundHost) continue
      if (explicit && pinned !== n.address && boundHost === pinned) continue
      const host = explicit ? pinned : n.address
      const type = isTailscaleHost(host) || isTailscaleHost(n.address) ? 'tailscale' : 'lan'
      if (!networks.some((network) => network.type === type)) networks.push({ type, host })
    }
  }
  return networks.sort((a, b) => Number(a.type === 'tailscale') - Number(b.type === 'tailscale'))
}

/**
 * Omitted selection prefers LAN; explicit unavailable selections never fall back.
 * @param {Array<{type: 'lan'|'tailscale', host: string}>} networks
 * @param {string} [requested]
 */
export function selectNetwork(networks, requested) {
  if (requested !== undefined && requested !== 'lan' && requested !== 'tailscale') {
    throw Object.assign(new Error('network must be lan or tailscale'), { statusCode: 400 })
  }
  const selected = requested === undefined
    ? networks.find((network) => network.type === 'lan') ?? networks[0]
    : networks.find((network) => network.type === requested)
  if (requested !== undefined && !selected) {
    throw Object.assign(new Error(`${requested} network is unavailable on the bound interfaces`), { statusCode: 503 })
  }
  return selected
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
