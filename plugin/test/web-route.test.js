import { test } from 'node:test'
import assert from 'node:assert/strict'
import { createServer } from 'node:http'
import { isTrustedRequest } from '../src/trust.js'
import { createDeviceStore } from '../src/devices.js'
import { createPairing } from '../src/pairing.js'
import { createPocketServer } from '../src/server.js'
import { createManageApi } from '../src/manage.js'
import { registerPairRoute } from '../src/web-route.js'

test('isTrustedRequest: loopback Host + Origin fence', () => {
  const req = (headers) => ({ headers })
  assert.equal(isTrustedRequest(req({ host: '127.0.0.1:7788' })), true)
  assert.equal(isTrustedRequest(req({ host: 'localhost:7788' })), true)
  assert.equal(isTrustedRequest(req({ host: '127.0.0.42:9' })), true)
  assert.equal(isTrustedRequest(req({ host: 'evil.example' })), false)
  assert.equal(isTrustedRequest(req({ host: '127.0.0.1:1', 'sec-fetch-site': 'cross-site' })), false)
  assert.equal(isTrustedRequest(req({ host: '127.0.0.1:1', origin: 'http://127.0.0.1:1' })), true)
  assert.equal(isTrustedRequest(req({ host: '127.0.0.1:1', origin: 'http://evil.example' })), false)
})

test('device rename persists', () => {
  const s = createDeviceStore({ ownerToken: 'tok', persist: false })
  const { deviceId } = s.add('Pixel')
  assert.equal(s.rename(deviceId, 'Pixel 9'), true)
  assert.equal(s.list()[0].name, 'Pixel 9')
  assert.equal(s.rename('missing', 'x'), false)
})

test('pairing status exposes code while open', () => {
  const p = createPairing()
  assert.equal(p.status().open, false)
  const { code } = p.open()
  assert.equal(p.status().code, code)
  p.close()
  assert.equal(p.status().open, false)
})

test('management routes: status, pair, rename, revoke, Origin fence', async (t) => {
  const OWNER = 'owner-token-for-web-route-test'
  const devices = createDeviceStore({ ownerToken: OWNER, persist: false })
  const pairing = createPairing()
  const server = createPocketServer({
    sessionController: {
      list: async () => ({ items: [] }),
      follow: () => (async function* () {})(),
    },
    token: OWNER, port: 0, devices, pairing, pairFailDelayMs: 1,
  })
  const addr = await server.listen()
  t.after(() => server.close())
  const host = '127.0.0.1'
  const port = Number(String(addr).split(':').pop())
  const NODE = 'abcdefghijklmnopqrstuvwxyz012345'
  const openPairing = () => {
    const w = pairing.open()
    return { ...w, nodeId: NODE }
  }
  const manage = createManageApi({
    host, port, devices, pairing, server, openPairing,
    getNodeId: () => NODE,
  })

  // Minimal fake webServer.register that mounts onto a real http server
  const routes = new Map()
  const http = createServer(async (req, res) => {
    const path = new URL(req.url, 'http://127.0.0.1').pathname
    const h = routes.get(path)
    if (!h) { res.writeHead(404); res.end(); return }
    await h(req, res)
  })
  await new Promise((r) => http.listen(0, '127.0.0.1', r))
  t.after(() => new Promise((r) => http.close(r)))
  const base = `http://127.0.0.1:${http.address().port}`

  const effects = []
  const ctx = {
    effect: (fn) => { effects.push(fn); return fn() },
    webServer: {
      register: ({ path, handler }) => {
        routes.set(path, handler)
        return () => routes.delete(path)
      },
    },
  }
  registerPairRoute(ctx, manage)

  const st = await (await fetch(`${base}/pocket/status`)).json()
  assert.equal(st.running, true)
  assert.equal(st.version, '0.1.0')
  assert.ok(Array.isArray(st.listen))

  const cross = await fetch(`${base}/pocket/status`, { headers: { origin: 'http://evil.example' } })
  assert.equal(cross.status, 403)

  const pr = await (await fetch(`${base}/pocket/pair`, { method: 'POST' })).json()
  assert.match(pr.code, /^\d{6}$/)
  assert.ok(pr.uri?.includes(pr.code))
  assert.ok(pr.uri?.includes('n='))
  assert.equal(pr.pairingString, `${NODE}#${pr.code}`)
  const get = await (await fetch(`${base}/pocket/pair`)).json()
  assert.equal(get.open, true)
  assert.equal(get.code, pr.code)
  assert.equal(get.pairingString, pr.pairingString)

  const { deviceId } = devices.add('Phone A')
  const renamed = await (await fetch(`${base}/pocket/devices`, {
    method: 'PATCH',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ id: deviceId, name: 'Phone B' }),
  })).json()
  assert.equal(renamed.renamed, true)
  assert.equal(renamed.devices[0].name, 'Phone B')

  const revoked = await (await fetch(`${base}/pocket/devices`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ id: deviceId }),
  })).json()
  assert.equal(revoked.removed, true)
  assert.equal(revoked.devices.length, 0)

  // keep port referenced for lint
  assert.ok(port >= 0)
})
