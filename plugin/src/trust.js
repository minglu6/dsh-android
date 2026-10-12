/** Loopback hostnames: localhost, IPv6 loopback, and the entire 127/8 range. */
export function isLoopbackHostname(hostname) {
  if (hostname === 'localhost' || hostname === '[::1]' || hostname === '::1') return true
  const parts = hostname.split('.')
  return parts.length === 4 && parts[0] === '127'
    && parts.every((part) => /^\d{1,3}$/.test(part) && Number(part) <= 255)
}

/**
 * Browser-trust fence matching dsh's own `/api` Host/Origin rules (and tether):
 * Host must be a loopback authority; refuse `sec-fetch-site: cross-site`;
 * when Origin is present it must match Host. Does not accept trustedHosts —
 * management routes are local-operator only.
 */
export function isTrustedRequest(req) {
  const host = req.headers.host
  if (typeof host !== 'string') return false
  let hostUrl
  try { hostUrl = new URL(`http://${host}`) } catch { return false }
  if (!isLoopbackHostname(hostUrl.hostname)) return false
  if (req.headers['sec-fetch-site'] === 'cross-site') return false
  const origin = req.headers.origin
  if (typeof origin !== 'string') return true
  try { return new URL(origin).host === hostUrl.host } catch { return false }
}
