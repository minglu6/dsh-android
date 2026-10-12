import { isTrustedRequest } from './trust.js'

const json = (res, status, body) => {
  res.writeHead(status, { 'content-type': 'application/json; charset=utf-8', 'cache-control': 'no-store' })
  res.end(JSON.stringify(body))
}

const refuse = (res) => json(res, 403, { error: 'forbidden' })

async function readJson(req, res, limit = 4096) {
  let body = ''
  for await (const c of req) {
    body += c
    if (body.length > limit) {
      json(res, 413, { error: 'too large' })
      return undefined
    }
  }
  if (!body) return {}
  try { return JSON.parse(body) } catch {
    json(res, 400, { error: 'invalid json' })
    return undefined
  }
}

/**
 * Management routes on dsh's webServer (loopback Host/Origin fence).
 * @param {import('@deepseek-ai/cordis').Context} ctx
 * @param {{
 *   openPairing: () => object,
 *   closePairing?: () => object,
 *   pairingStatus: () => object,
 *   getStatus: () => object,
 *   listDevices: () => object[],
 *   revokeDevice: (id: string) => boolean,
 *   renameDevice: (id: string, name: string) => boolean,
 * }} api
 */
export function registerPairRoute(ctx, api) {
  const gate = (req, res) => {
    if (!isTrustedRequest(req)) { refuse(res); return false }
    return true
  }

  ctx.effect(() => ctx.webServer.register({
    kind: 'exact', path: '/pocket/status',
    handler: (req, res) => {
      if (!gate(req, res)) return
      if (req.method !== 'GET') return json(res, 405, { error: 'method not allowed' })
      return json(res, 200, api.getStatus())
    },
  }))

  ctx.effect(() => ctx.webServer.register({
    kind: 'exact', path: '/pocket/pair',
    handler: (req, res) => {
      if (!gate(req, res)) return
      if (req.method === 'POST') {
        try {
          return json(res, 200, api.openPairing())
        } catch (e) {
          return json(res, 503, { error: String(e?.message ?? e) })
        }
      }
      if (req.method === 'DELETE') return json(res, 200, (api.closePairing ?? (() => ({ open: false })))())
      if (req.method === 'GET') return json(res, 200, api.pairingStatus())
      return json(res, 405, { error: 'method not allowed' })
    },
  }))

  ctx.effect(() => ctx.webServer.register({
    kind: 'exact', path: '/pocket/devices',
    handler: async (req, res) => {
      if (!gate(req, res)) return
      if (req.method === 'GET') return json(res, 200, { devices: api.listDevices() })
      if (req.method === 'POST') {
        const body = await readJson(req, res)
        if (body === undefined) return
        const id = body.id
        if (typeof id !== 'string' || !id) return json(res, 400, { error: 'id required' })
        return json(res, 200, { removed: api.revokeDevice(id), devices: api.listDevices() })
      }
      if (req.method === 'PATCH') {
        const body = await readJson(req, res)
        if (body === undefined) return
        const id = body.id
        const name = body.name
        if (typeof id !== 'string' || !id) return json(res, 400, { error: 'id required' })
        if (typeof name !== 'string' || !name.trim()) return json(res, 400, { error: 'name required' })
        const renamed = api.renameDevice(id, name.trim())
        if (!renamed) return json(res, 404, { error: 'not found' })
        return json(res, 200, { renamed: true, devices: api.listDevices() })
      }
      return json(res, 405, { error: 'method not allowed' })
    },
  }))
}

/** @deprecated use isTrustedRequest — kept as alias for existing imports/tests */
export const isLocalRequest = isTrustedRequest
