const LOOPBACK = new Set(['127.0.0.1', '::1', '::ffff:127.0.0.1'])

/** Only loopback peers, and no cross-site browser requests (Origin must be absent or same host). */
export function isLocalRequest(req) {
  if (!LOOPBACK.has(req.socket?.remoteAddress)) return false
  const origin = req.headers.origin
  if (origin === undefined) return true
  try { return new URL(origin).host === req.headers.host } catch { return false }
}

const json = (res, status, body) => {
  res.writeHead(status, { 'content-type': 'application/json; charset=utf-8', 'cache-control': 'no-store' })
  res.end(JSON.stringify(body))
}

/** POST /pocket/pair opens a window; GET /pocket/devices lists; POST /pocket/devices {id} revokes. */
export function registerPairRoute(ctx, { openPairing, devices, pairing }) {
  ctx.effect(() => ctx.webServer.register({
    kind: 'exact', path: '/pocket/pair',
    handler: (req, res) => {
      if (!isLocalRequest(req)) return json(res, 403, { error: 'loopback only' })
      if (req.method === 'POST') return json(res, 200, openPairing())
      return json(res, 200, pairing.status())
    },
  }))
  ctx.effect(() => ctx.webServer.register({
    kind: 'exact', path: '/pocket/devices',
    handler: async (req, res) => {
      if (!isLocalRequest(req)) return json(res, 403, { error: 'loopback only' })
      if (req.method === 'POST') {
        let body = ''
        for await (const c of req) { body += c; if (body.length > 4096) return json(res, 413, { error: 'too large' }) }
        let id
        try { id = JSON.parse(body).id } catch { /* fallthrough */ }
        if (typeof id !== 'string') return json(res, 400, { error: 'id required' })
        return json(res, 200, { removed: devices.revoke(id), devices: devices.list() })
      }
      return json(res, 200, { devices: devices.list() })
    },
  }))
}
