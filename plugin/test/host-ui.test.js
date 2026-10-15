import { test } from 'node:test'
import assert from 'node:assert/strict'
import { once } from 'node:events'
import { createServer, request } from 'node:http'
import { runInNewContext } from 'node:vm'
import { gunzipSync } from 'node:zlib'
import { WebSocket, WebSocketServer } from 'ws'
import { createPocketServer } from '../src/server.js'
import { UI_COOKIE, UI_TICKET_MS, UI_SESSION_MS } from '../src/host-ui.js'

// Entirely local fixtures: never connect to a running user Host or read its credentials.
const LAUNCH = 'fixture-process-launch-secret'
const HOST_COOKIE = 'fixture-host-cookie-secret'
const OWNER = 'fixture-pocket-owner-secret'
const ACCOUNT_PATH = '/plugins/??@deepseek-ai/dsh-client-ui-settings-account/client.js&rev=art'
const ARTWORK = Buffer.alloc(70_000, 42)
const BOOT = {
  rev: 'fixture',
  entries: [
    { id: 'bootstrap', url: '/plugins/bootstrap.js', rev: 'boot' },
    { id: 'custom-plugin', url: '/plugins/custom/client.js', rev: 'custom' },
    { id: 'account', url: ACCOUNT_PATH, rev: 'art' },
  ],
  batches: [
    { phase: 'bootstrap', url: '/plugins/bootstrap.js', rev: 'boot', entries: ['bootstrap'] },
    { phase: 'application', url: '/plugins/combined.js', rev: 'combined', entries: ['custom-plugin', 'account'] },
  ],
}

function http(origin, path, { method = 'GET', headers = {}, body, binary = false } = {}) {
  return new Promise((resolve, reject) => {
    const req = request(origin, { path, method, headers }, (res) => {
      const chunks = []
      res.on('data', (chunk) => chunks.push(chunk))
      res.on('error', reject)
      res.on('end', () => {
        const bytes = Buffer.concat(chunks)
        resolve({ status: res.statusCode, headers: res.headers, body: binary ? bytes : bytes.toString('utf8') })
      })
    })
    req.on('error', reject)
    req.end(body)
  })
}

async function peer(origin, token) {
  const ws = new WebSocket(origin.replace('http:', 'ws:'))
  const queue = []
  const waiters = []
  ws.on('message', (data) => {
    const message = JSON.parse(data.toString())
    const index = waiters.findIndex((entry) => entry.predicate(message))
    if (index >= 0) waiters.splice(index, 1)[0].resolve(message)
    else queue.push(message)
  })
  const next = (predicate) => {
    const index = queue.findIndex(predicate)
    if (index >= 0) return Promise.resolve(queue.splice(index, 1)[0])
    return new Promise((resolve) => waiters.push({ predicate, resolve }))
  }
  let sequence = 0
  const call = (op, args = {}) => {
    const id = ++sequence
    ws.send(JSON.stringify({ id, op, args }))
    return next((message) => message.id === id)
  }
  await once(ws, 'open')
  const hello = token === undefined ? undefined : await call('hello', { proto: 1, token })
  return { ws, call, next, hello }
}

function deniedSocket(origin, path, headers = {}) {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(`${origin.replace('http:', 'ws:')}${path}`, { headers })
    ws.on('error', () => {})
    ws.once('open', () => { ws.terminate(); reject(new Error('unauthorized WebSocket opened')) })
    ws.once('unexpected-response', (_req, res) => {
      res.resume()
      res.once('end', () => { ws.terminate(); resolve(res.statusCode) })
    })
  })
}

async function fixture(t, { install = true, image = ARTWORK } = {}) {
  const seen = []
  let time = Date.now()
  let exchanges = 0
  const upstream = createServer(async (req, res) => {
    const url = new URL(req.url, 'http://fixture.invalid')
    if (url.pathname === '/' && url.searchParams.get('token') === LAUNCH) {
      exchanges++
      res.writeHead(303, { 'set-cookie': `host-auth=${HOST_COOKIE}; HttpOnly; Path=/`, location: './' }).end()
      return
    }
    seen.push({ path: req.url, headers: req.headers })
    if (req.headers.cookie !== `host-auth=${HOST_COOKIE}`) { res.writeHead(401).end(); return }
    // These must never escape, even on a successful response.
    res.setHeader('set-cookie', `host-auth=${HOST_COOKIE}; HttpOnly`)
    res.setHeader('location', `http://127.0.0.1/?token=${LAUNCH}`)
    if (url.pathname === '/' || url.pathname === '/index.html') {
      res.writeHead(200, { 'content-type': 'text/html' }).end(`<!doctype html><html><head>
<script>globalThis["__DSH_CONNECTION_RECOVERY__"] = {"backoffBaseMs":500,"launchUrl":"http://127.0.0.1/?token=${LAUNCH}","cookie":"${HOST_COOKIE}"}</script>
<script>globalThis.__DSH_TRANSPORT__={streamBaseUrl:"http://127.0.0.1:1234",ownsHost:true}</script>
<link rel="preload" as="script" href="/plugins/combined.js">
<script>globalThis["__DSH_BOOT__"] = ${JSON.stringify(BOOT)}</script>
<script type="module" src="/assets/app.js"></script></head><body><div id="root">official shell</div></body></html>`)
    } else if (req.url === ACCOUNT_PATH) {
      res.writeHead(200, { 'content-type': 'text/javascript' })
        .end(`var onboarding_welcome_default = "data:image/png;base64,${image.toString('base64')}";globalThis.artwork = onboarding_welcome_default;`)
    } else if (url.pathname.startsWith('/assets/') || url.pathname.startsWith('/plugins/')) {
      res.writeHead(200, { 'content-type': 'text/javascript' }).end('globalThis.fixturePluginLoaded=true')
    } else if (url.pathname === '/api/echo' || url.pathname === '/api') {
      const chunks = []
      for await (const chunk of req) chunks.push(chunk)
      res.writeHead(200, { 'content-type': 'application/json' }).end(JSON.stringify({ body: Buffer.concat(chunks).toString(), method: req.method }))
    } else if (url.pathname === '/api/events') {
      res.writeHead(200, { 'content-type': 'text/event-stream' })
      res.write('data: ready\n\n')
      const timer = setInterval(() => res.write('data: tick\n\n'), 20)
      res.on('close', () => clearInterval(timer))
    } else if (url.pathname === '/api/redirect') {
      res.writeHead(302).end()
    } else if (url.pathname === '/api/unsafe-document') {
      res.writeHead(200, { 'content-type': 'text/html' }).end(`<body>${LAUNCH}</body>`)
    } else {
      res.writeHead(404).end()
    }
  })
  const upstreamWs = new WebSocketServer({ noServer: true })
  upstream.on('upgrade', (req, socket, head) => {
    if (req.url !== '/api/remote.mux' || req.headers.cookie !== `host-auth=${HOST_COOKIE}`
      || req.headers.origin !== `http://127.0.0.1:${upstream.address().port}`) { socket.destroy(); return }
    upstreamWs.handleUpgrade(req, socket, head, (ws) => {
      ws.on('message', (data, binary) => ws.send(data, { binary }))
    })
  })
  await new Promise((resolve) => upstream.listen(0, '127.0.0.1', resolve))
  const server = createPocketServer({ sessionController: {}, token: OWNER, host: '127.0.0.1', port: 0 })
  const issued = server.devices.add('fixture phone')
  const backend = {
    webServer: { protocol: 'http:', host: '127.0.0.1', port: upstream.address().port },
    connection: { authenticatedUrl: (base) => { const url = new URL(base); url.searchParams.set('token', LAUNCH); return url.href } },
    now: () => time,
  }
  let uninstall = install ? server.installHostUi(backend) : undefined
  const origin = `http://${await server.listen()}`
  const client = await peer(origin, issued.token)
  t.after(async () => {
    await server.close()
    for (const ws of upstreamWs.clients) ws.terminate()
    await new Promise((resolve) => upstreamWs.close(resolve))
    upstream.closeAllConnections()
    await new Promise((resolve) => upstream.close(resolve))
  })
  const open = async (section = 'models', target = client) => {
    const result = await target.call('ui.open', { section })
    assert.equal(result.ok, true)
    assert.equal(result.result.path, '/pocket-ui/open')
    assert.equal(typeof result.result.ticket, 'string')
    return result.result
  }
  const exchange = (grant, headers = {}) => http(origin, grant.path, {
    method: 'POST', headers: { 'content-type': 'application/x-www-form-urlencoded', ...headers },
    body: new URLSearchParams({ ticket: grant.ticket }).toString(),
  })
  const session = async (section) => {
    const grant = await open(section)
    const response = await exchange(grant)
    assert.equal(response.status, 303)
    return { grant, response, cookie: response.headers['set-cookie'][0].split(';')[0] }
  }
  return {
    server, origin, client, issued, open, exchange, session, seen,
    advance: (ms) => { time += ms }, exchanges: () => exchanges,
    install: () => { uninstall = server.installHostUi(backend) }, uninstall: () => uninstall?.(),
  }
}

test('ui ops follow the live service scope and require bridge authentication', { timeout: 10_000 }, async (t) => {
  const f = await fixture(t, { install: false })
  assert.equal(f.client.hello.result.ops.includes('ui.open'), false)
  f.install()
  const changed = await f.client.next((message) => message.ev === 'ops.changed')
  assert.ok(changed.ops.includes('ui.open'))
  const unauthenticated = await peer(f.origin)
  assert.equal((await unauthenticated.call('ui.open')).error.code, 'auth/denied')
  const { cookie } = await f.session()
  f.uninstall()
  assert.equal((await f.client.call('ui.open')).error.code, 'unknown-op')
  assert.notEqual((await http(f.origin, '/assets/app.js', { headers: { cookie } })).status, 200)
})

test('HTML, assets, plugins, API and mux require a device-bound session', { timeout: 10_000 }, async (t) => {
  const f = await fixture(t)
  for (const path of ['/', '/index.html', '/assets/app.js', '/plugins/custom/client.js', '/api/echo']) {
    assert.equal((await http(f.origin, path)).status, 401, path)
  }
  assert.equal(await deniedSocket(f.origin, '/api/remote.mux', { origin: f.origin }), 401)
  assert.equal(await deniedSocket(f.origin, '/plugins/unsafe'), 403)
  assert.equal(f.exchanges(), 0)
  assert.equal(f.seen.length, 0)
})

test('one-use tickets expire and never appear in browser navigation URLs', { timeout: 10_000 }, async (t) => {
  const f = await fixture(t)
  const grant = await f.open('models&redirect=https://evil.invalid/')
  const response = await f.exchange(grant)
  assert.equal(response.status, 303)
  const location = new URL(response.headers.location, f.origin)
  assert.equal(location.origin, f.origin)
  assert.equal(location.pathname, '/')
  assert.equal(location.searchParams.get('pocket-ui'), '1')
  assert.equal(location.searchParams.get('section'), 'models&redirect=https://evil.invalid/')
  assert.equal(location.searchParams.has('ticket'), false)
  assert.match(response.headers['set-cookie'][0], new RegExp(`^${UI_COOKIE}=`))
  assert.match(response.headers['set-cookie'][0], /HttpOnly; SameSite=Strict; Max-Age=900/u)
  assert.equal((await f.exchange(grant)).status, 401)
  const plain = await f.exchange(await f.open(null))
  assert.equal(plain.headers.location, '/?pocket-ui=1')
  const expired = await f.open()
  f.advance(UI_TICKET_MS)
  assert.equal((await f.exchange(expired)).status, 401)
  assert.equal((await f.client.call('ui.open', { section: { url: 'https://evil.invalid' } })).error.code, 'bad-request')
})

test('ticket exchange rejects cross-site and mismatched authorities without consuming a valid grant', { timeout: 10_000 }, async (t) => {
  const f = await fixture(t)
  const grant = await f.open()
  for (const headers of [{ origin: 'https://evil.invalid' }, { origin: 'null' }, { 'sec-fetch-site': 'cross-site' }]) {
    assert.equal((await f.exchange(grant, headers)).status, 403)
  }
  assert.equal((await f.exchange(grant, { host: `localhost:${f.server.address().port}` })).status, 401)
  assert.equal((await f.exchange(grant)).status, 303)
})

test('official HTML graph, assets, POST and mux work without publishing Host credentials', { timeout: 10_000 }, async (t) => {
  const f = await fixture(t)
  const { cookie } = await f.session()
  const page = await http(f.origin, '/?pocket-ui=1&section=models', { headers: { cookie } })
  assert.equal(page.status, 200)
  assert.match(page.body, /__DSH_BOOT__/u)
  assert.match(page.body, /custom-plugin/u)
  assert.match(page.body, /backoffBaseMs/u)
  assert.match(page.body, /official shell/u)
  assert.doesNotMatch(page.body, /streamBaseUrl|launchUrl/u)
  const browser = {}
  for (const [, attributes, source] of page.body.matchAll(/<script\b([^>]*)>([\s\S]*?)<\/script\s*>/giu)) {
    if (!/\bsrc\s*=/iu.test(attributes)) runInNewContext(source, browser)
  }
  assert.equal(browser.__DSH_TRANSPORT__.ownsHost, true)
  assert.deepEqual(Object.keys(browser.__DSH_TRANSPORT__), ['ownsHost'])
  for (const secret of [LAUNCH, HOST_COOKIE, OWNER]) assert.equal(JSON.stringify(page).includes(secret), false)
  assert.equal(page.headers['set-cookie'], undefined)
  assert.equal(page.headers.location, undefined)
  assert.equal(page.headers['referrer-policy'], 'no-referrer')
  assert.equal(page.headers['cache-control'], 'no-store')
  for (const path of ['/assets/app.js', '/plugins/custom/client.js']) {
    assert.equal((await http(f.origin, path, { headers: { cookie } })).body, 'globalThis.fixturePluginLoaded=true')
  }
  const api = await http(f.origin, '/api/echo', { method: 'POST', headers: { cookie, origin: f.origin, 'content-type': 'application/json' }, body: '{"custom":true}' })
  assert.equal(api.status, 200)
  assert.deepEqual(JSON.parse(api.body), { body: '{"custom":true}', method: 'POST' })
  const ws = new WebSocket(`${f.origin.replace('http:', 'ws:')}/api/remote.mux`, { headers: { cookie, origin: f.origin } })
  await once(ws, 'open')
  const message = once(ws, 'message')
  ws.send('custom-plugin-stream')
  assert.equal((await message)[0].toString(), 'custom-plugin-stream')
  ws.terminate()
  assert.equal(f.exchanges(), 1)
  assert.ok(f.seen.every((entry) => entry.headers.cookie === `host-auth=${HOST_COOKIE}`))
})

test('slow-link documents and modules negotiate gzip without changing authenticated content', async (t) => {
  const f = await fixture(t)
  const { cookie } = await f.session()
  for (const path of ['/?pocket-ui=1&section=models', '/assets/app.js']) {
    const plain = await http(f.origin, path, { headers: { cookie } })
    const compressed = await http(f.origin, path, { headers: { cookie, 'accept-encoding': 'br, gzip;q=0.8' }, binary: true })
    assert.equal(compressed.status, 200)
    assert.equal(compressed.headers['content-encoding'], 'gzip')
    assert.equal(gunzipSync(compressed.body).toString('utf8'), plain.body)
    const disabled = await http(f.origin, path, { headers: { cookie, 'accept-encoding': 'gzip;q=0, identity' } })
    assert.equal(disabled.headers['content-encoding'], undefined)
    assert.equal(disabled.body, plain.body)
  }
})

test('valid cookies cannot bypass origin, fetch metadata or authority checks', { timeout: 10_000 }, async (t) => {
  const f = await fixture(t)
  const { cookie } = await f.session()
  for (const headers of [{ origin: 'https://evil.invalid' }, { origin: 'null' }, { 'sec-fetch-site': 'same-site' }, { host: `localhost:${f.server.address().port}` }]) {
    assert.equal((await http(f.origin, '/api/echo', { headers: { cookie, ...headers } })).status, 401)
  }
  assert.equal((await http(f.origin, '/api/echo', { method: 'POST', headers: { cookie }, body: '{}' })).status, 403)
  assert.equal(await deniedSocket(f.origin, '/api/remote.mux', { cookie, origin: 'https://evil.invalid' }), 401)
  assert.equal(await deniedSocket(f.origin, '/api/remote.mux', { cookie }), 401)
  assert.equal(f.seen.length, 0)
})

test('path-scoped cookie pollution cannot hide one valid session or select between two valid sessions', { timeout: 10_000 }, async (t) => {
  const f = await fixture(t)
  const first = await f.session()
  const polluted = `${UI_COOKIE}=foreign-path-cookie; ${first.cookie}`
  assert.equal((await http(f.origin, '/api/echo', { headers: { cookie: polluted } })).status, 200)
  const sibling = await peer(f.origin, f.issued.token)
  const secondGrant = await f.open('models', sibling)
  const secondResponse = await f.exchange(secondGrant)
  const second = { cookie: secondResponse.headers['set-cookie'][0].split(';')[0] }
  assert.equal((await http(f.origin, '/api/echo', { headers: { cookie: `${first.cookie}; ${second.cookie}` } })).status, 401)
  await f.client.call('ui.close', { ticket: first.grant.ticket })
  assert.equal((await http(f.origin, '/api/echo', { headers: { cookie: `${first.cookie}; ${second.cookie}` } })).status, 200)
})

test('path normalization and launch-token bypasses are rejected before reaching Host', { timeout: 10_000 }, async (t) => {
  const f = await fixture(t)
  const { cookie } = await f.session()
  const paths = ['/pocket/status', '/open-in-app/test', '//evil.invalid/api', 'http://evil.invalid/api', '/assets/../pocket/status', '/assets/%2e%2e/pocket/status', '/assets/%252e%252e/pocket/status', '/assets/x%2f..%2fpocket/status', '/assets/x%5c..%5cpocket/status', '/api/echo?%74oken=x', '/?token=x', '/plugins/test?ticket=x']
  for (const path of paths) assert.equal((await http(f.origin, path, { headers: { cookie } })).status, 403, path)
  assert.equal(f.seen.length, 0)
})

test('upstream redirects and unexpected credential-bearing HTML fail closed', { timeout: 10_000 }, async (t) => {
  const f = await fixture(t)
  const { cookie } = await f.session()
  for (const path of ['/api/redirect', '/api/unsafe-document']) {
    const response = await http(f.origin, path, { headers: { cookie } })
    assert.equal(response.status, 502)
    assert.equal(response.headers.location, undefined)
    assert.equal(response.headers['set-cookie'], undefined)
    assert.equal(JSON.stringify(response).includes(LAUNCH), false)
  }
})

test('ui.close is ticket-scoped, cannot close a replacement session, and expires sessions', { timeout: 10_000 }, async (t) => {
  const f = await fixture(t)
  const old = await f.session()
  const current = await f.session()
  const sibling = await peer(f.origin, f.issued.token)
  await sibling.call('ui.close', { ticket: current.grant.ticket })
  assert.equal((await f.client.call('ui.close', { ticket: old.grant.ticket })).ok, true)
  assert.equal((await http(f.origin, '/assets/app.js', { headers: { cookie: current.cookie } })).status, 200)
  assert.equal((await http(f.origin, '/assets/app.js', { headers: { cookie: old.cookie } })).status, 401)
  f.advance(UI_SESSION_MS)
  assert.equal((await http(f.origin, '/assets/app.js', { headers: { cookie: current.cookie } })).status, 401)
  const next = await f.session()
  await f.client.call('ui.close', { ticket: next.grant.ticket })
  assert.equal((await http(f.origin, '/assets/app.js', { headers: { cookie: next.cookie } })).status, 401)
})

test('device disconnect destroys active SSE and mux and invalidates cookies', { timeout: 10_000 }, async (t) => {
  const f = await fixture(t)
  const { cookie } = await f.session()
  const stream = await new Promise((resolve, reject) => {
    const req = request(`${f.origin}/api/events`, { headers: { cookie } }, (res) => {
      res.once('data', () => resolve(res))
      res.on('error', () => {})
    })
    req.on('error', reject)
    req.end()
  })
  const streamClosed = new Promise((resolve) => stream.once('close', resolve))
  const ws = new WebSocket(`${f.origin.replace('http:', 'ws:')}/api/remote.mux`, { headers: { cookie, origin: f.origin } })
  await once(ws, 'open')
  const socketClosed = once(ws, 'close')
  const bridgeClosed = once(f.client.ws, 'close')
  f.client.ws.close()
  await Promise.all([bridgeClosed, streamClosed, socketClosed])
  assert.equal((await http(f.origin, '/assets/app.js', { headers: { cookie } })).status, 401)
})

test('revocation invalidates outstanding tickets and browser sessions immediately', { timeout: 10_000 }, async (t) => {
  const f = await fixture(t)
  const { cookie } = await f.session()
  const another = await peer(f.origin, f.issued.token)
  const grant = await f.open('models', another)
  const ws = new WebSocket(`${f.origin.replace('http:', 'ws:')}/api/remote.mux`, { headers: { cookie, origin: f.origin } })
  await once(ws, 'open')
  const closed = once(ws, 'close')
  assert.equal(f.server.revokeDevice(f.issued.deviceId), true)
  await closed
  assert.equal((await f.exchange(grant)).status, 401)
  assert.equal((await http(f.origin, '/assets/app.js', { headers: { cookie } })).status, 401)
})

test('device store liveness is checked without rewriting authentication on every asset', { timeout: 10_000 }, async (t) => {
  const f = await fixture(t)
  const { cookie } = await f.session()
  f.server.devices.authenticate = () => { throw new Error('static requests must not touch persistent device authentication') }
  for (const path of ['/assets/app.js', '/plugins/custom/client.js']) {
    assert.equal((await http(f.origin, path, { headers: { cookie } })).status, 200)
  }
  f.server.devices.revoke(f.issued.deviceId)
  assert.equal((await http(f.origin, '/assets/app.js', { headers: { cookie } })).status, 401)
})

test('phone boot preserves every module without waiting for combined application downloads', { timeout: 10_000 }, async (t) => {
  const f = await fixture(t)
  const { cookie } = await f.session()
  const page = await http(f.origin, '/?pocket-ui=1&section=models', { headers: { cookie } })
  const context = {}
  for (const [, attributes, source] of page.body.matchAll(/<script\b([^>]*)>([\s\S]*?)<\/script>/gu)) {
    if (!attributes.includes('src=')) runInNewContext(source, context)
  }
  const graph = JSON.parse(JSON.stringify(context.__DSH_BOOT__))
  assert.deepEqual(graph.entries, BOOT.entries)
  assert.deepEqual(graph.batches.flatMap(batch => batch.entries), BOOT.entries.map(entry => entry.id))
  assert.deepEqual(graph.batches[0], BOOT.batches[0])
  for (const batch of graph.batches.filter(batch => batch.phase === 'application')) {
    assert.equal(batch.entries.length, 1)
    assert.equal((await http(f.origin, batch.url, { headers: { cookie } })).status, 200)
  }
})

test('onboarding artwork loads on demand with unchanged bytes and device-bound authorization', { timeout: 10_000 }, async (t) => {
  const f = await fixture(t)
  const { grant, cookie } = await f.session()
  const script = await http(f.origin, ACCOUNT_PATH, { headers: { cookie } })
  assert.equal(script.status, 200)
  const context = {}
  runInNewContext(script.body, context)
  assert.ok(context.artwork.startsWith('/assets/pocket-artwork/'))
  const image = await http(f.origin, context.artwork, { headers: { cookie }, binary: true })
  assert.equal(image.status, 200)
  assert.equal(image.headers['content-type'], 'image/png')
  assert.deepEqual(image.body, ARTWORK)
  assert.equal((await http(f.origin, context.artwork)).status, 401)
  await f.client.call('ui.close', { ticket: grant.ticket })
  assert.equal((await http(f.origin, context.artwork, { headers: { cookie } })).status, 401)
})

test('revoking a UI session aborts a backpressured artwork response', { timeout: 15_000 }, async (t) => {
  const image = Buffer.alloc(10 * 1024 * 1024, 23)
  const f = await fixture(t, { image })
  const { grant, cookie } = await f.session()
  const script = await http(f.origin, ACCOUNT_PATH, { headers: { cookie } })
  assert.equal(script.status, 200)
  const context = {}
  runInNewContext(script.body, context)
  const outgoing = request(`${f.origin}${context.artwork}`, { headers: { cookie } })
  const received = once(outgoing, 'response')
  outgoing.end()
  const [response] = await received
  response.pause()
  response.on('error', () => {})
  const aborted = once(response, 'aborted')
  try {
    await f.client.call('ui.close', { ticket: grant.ticket })
    response.resume()
    await aborted
    assert.equal(response.complete, false)
  } finally {
    response.destroy()
    outgoing.destroy()
  }
})
