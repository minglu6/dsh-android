/**
 * Pure `dshpocket://` pairing URI parser — mirrors Android PairingUriPolicy.
 * Shared vectors: ../../contract/pairing-uri-cases.json
 *
 * Happy path: `dshpocket://pair?h=<host:port>&c=<6-digit>`
 * Legacy iroh forms (`n=` / `nodeId#code`) are rejected.
 */

const CODE = /^\d{6}$/

/**
 * @param {string} raw
 * @returns {{ host: string, port: number } | null}
 */
export function parseHostPort(raw) {
  const t = String(raw || '').trim().replace(/^wss?:\/\//, '')
  const match = /^\[?([^[\]]+?)]?:(\d{1,5})$/.exec(t)
  if (!match) return null
  const port = Number(match[2])
  if (!Number.isInteger(port) || port < 1 || port > 65535) return null
  const host = match[1]
  if (!host) return null
  return { host, port }
}

/**
 * @param {string} text
 * @returns {{ host: string, port: number, code: string } | null}
 */
export function parsePairingUri(text) {
  const s = String(text ?? '').trim()
  if (!s.startsWith('dshpocket://pair')) return null
  const q = new URLSearchParams(s.includes('?') ? s.slice(s.indexOf('?') + 1) : '')
  const c = q.get('c')
  if (!c || !CODE.test(c)) return null
  if (q.get('n')) return null
  const h = q.get('h')
  if (!h) return null
  const hp = parseHostPort(h)
  if (!hp) return null
  return { host: hp.host, port: hp.port, code: c }
}
