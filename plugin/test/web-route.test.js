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
    token: OWNER, host: '0.0.0.0', port: 0, devices, pairing, pairFailDelayMs: 1,
  })
  const addr = await server.listen()
  t.after(() => server.close())
  let networks = [
    { type: 'lan', host: '192.168.1.5' },
    { type: 'tailscale', host: '100.64.1.2' },
  ]
  const port = Number(String(addr).split(':').pop())
  const openPairing = () => pairing.open()
  const manage = createManageApi({
    getNetworks: () => networks, port, devices, pairing, server, openPairing,
    getTransport: () => 'tailscale',
    instanceId: 'a'.repeat(64),
    getMdns: () => false,
    getDiagnostics: async () => ({
      version: 1,
      generatedAt: Date.now(),
      overall: 'ok',
      summary: '连接基础检查正常。',
      checks: [],
      report: 'DSH Pocket 诊断报告\n生成时间: test',
    }),
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
  assert.equal(st.connectionType, 'lan')
  assert.equal(st.host, '192.168.1.5')
  assert.deepEqual(st.networks, networks)
  // Legacy iroh path reports must not affect /pocket/status connectionType.
  server.noteIrohPath?.('peer-status', 'relay')
  const st2 = await (await fetch(`${base}/pocket/status`)).json()
  assert.equal(st2.connectionType, 'lan')


  const cross = await fetch(`${base}/pocket/status`, { headers: { origin: 'http://evil.example' } })
  assert.equal(cross.status, 403)

  const pr = await (await fetch(`${base}/pocket/pair`, { method: 'POST' })).json()
  assert.match(pr.code, /^\d{6}$/)
  assert.ok(pr.uri?.includes(pr.code))
  assert.ok(pr.uri?.includes('h='))
  assert.ok(!pr.uri?.includes('n='))
  assert.equal(pr.pairingString, pr.uri)
  const get = await (await fetch(`${base}/pocket/pair`)).json()
  assert.equal(get.open, true)
  assert.equal(get.code, pr.code)
  assert.equal(get.pairingString, pr.pairingString)

  // Selection changes only this response, not the pairing window or WS listener.
  const selected = await (await fetch(`${base}/pocket/status?network=tailscale`)).json()
  assert.equal(selected.host, '100.64.1.2')
  assert.equal(selected.connectionType, 'tailscale')
  assert.equal(selected.pairing.host, selected.host)
  assert.equal(selected.pairing.code, pr.code)
  assert.equal(new URL(selected.pairing.uri).searchParams.get('h'), `100.64.1.2:${port}`)
  const tsPair = await (await fetch(`${base}/pocket/pair?network=tailscale`)).json()
  assert.equal(tsPair.uri, selected.pairing.uri)
  const openedTs = await (await fetch(`${base}/pocket/pair?network=tailscale`, { method: 'POST' })).json()
  assert.equal(openedTs.host, '100.64.1.2')
  assert.equal(new URL(openedTs.uri).searchParams.get('h'), `100.64.1.2:${port}`)
  assert.equal(openedTs.pairingString, openedTs.uri)
  const defaultStatus = await (await fetch(`${base}/pocket/status`)).json()
  assert.equal(defaultStatus.connectionType, 'lan')
  assert.equal(defaultStatus.pairing.code, openedTs.code)
  assert.equal(defaultStatus.pairing.host, defaultStatus.host)
  assert.equal(new URL(defaultStatus.pairing.uri).searchParams.get('h'), `192.168.1.5:${port}`)
  assert.deepEqual(defaultStatus.listen, st.listen)

  networks = [networks[0]]
  for (const [path, method] of [['status', 'GET'], ['pair', 'GET'], ['pair', 'POST']]) {
    const unavailable = await fetch(`${base}/pocket/${path}?network=tailscale`, { method })
    assert.equal(unavailable.status, 503)
    assert.match((await unavailable.json()).error, /tailscale.*unavailable/)
    const invalid = await fetch(`${base}/pocket/${path}?network=unknown`, { method })
    assert.equal(invalid.status, 400)
    assert.match((await invalid.json()).error, /network must be/)
  }
  assert.equal(pairing.status().code, openedTs.code)
  const lanOnly = await (await fetch(`${base}/pocket/pair?network=lan`, { method: 'POST' })).json()
  assert.equal(lanOnly.host, '192.168.1.5')
  assert.ok(lanOnly.uri)

  networks = []
  const offline = await (await fetch(`${base}/pocket/status`)).json()
  assert.deepEqual(offline.networks, [])
  assert.equal(offline.host, '')
  assert.equal(offline.connectionType, null)
  assert.deepEqual(offline.pairing, { open: false })
  assert.equal((await fetch(`${base}/pocket/pair`, { method: 'POST' })).status, 503)
  assert.equal(pairing.status().code, lanOnly.code)

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

  const diag = await (await fetch(`${base}/pocket/diagnostics`)).json()
  assert.equal(diag.version, 1)
  assert.ok(typeof diag.report === 'string' && diag.report.includes('诊断'))
  assert.doesNotMatch(diag.report, /token|secret/i)

  // keep port referenced for lint
  assert.ok(port >= 0)
})
