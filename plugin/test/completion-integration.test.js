import { test } from 'node:test'
import assert from 'node:assert/strict'
import { createServer, get } from 'node:http'
import { once } from 'node:events'
import WebSocket from 'ws'
import { createPocketServer } from '../src/server.js'
import { registerCompletionRoutes } from '../src/completion-route.js'

const TOKEN = 'completion-test-owner'

function inbox() {
  const queue = []
  const waiters = []
  return {
    push(value) {
      const i = waiters.findIndex((w) => w.predicate(value))
      if (i < 0) queue.push(value)
      else waiters.splice(i, 1)[0].resolve(value)
    },
    next(predicate = () => true) {
      const i = queue.findIndex(predicate)
      if (i >= 0) return Promise.resolve(queue.splice(i, 1)[0])
      return new Promise((resolve) => waiters.push({ predicate, resolve }))
    },
  }
}

async function fixture(t, list = async () => ({ items: [{ sessionId: 'history', running: false }] })) {
  let emit
  const server = createPocketServer({
    sessionController: {
      list,
      follow: (_args, signal) => (async function* () {
        yield { type: 'snapshot', records: [], cursor: 0, hasMore: false }
        if (!signal.aborted) await new Promise((resolve) => signal.addEventListener('abort', resolve, { once: true }))
      })(),
    },
    token: TOKEN, host: '127.0.0.1', port: 0,
    onSessionEvent: (fn) => { emit = fn; return () => {} },
  })
  const address = await server.listen()
  const routes = new Map()
  const disposers = []
  const closedStreams = inbox()
  const http = createServer((req, res) => {
    const handler = routes.get(req.url)
    if (!handler) { res.writeHead(404).end(); return }
    if (req.url === '/pocket/completion/events') res.once('close', () => closedStreams.push(true))
    void handler(req, res)
  })
  registerCompletionRoutes({
    effect: (fn) => { disposers.push(fn()) },
    webServer: { register: ({ path, handler }) => { routes.set(path, handler); return () => routes.delete(path) } },
  }, server.completion)
  http.listen(0, '127.0.0.1')
  await once(http, 'listening')
  const base = `http://127.0.0.1:${http.address().port}`
  t.after(async () => {
    for (const dispose of disposers) dispose()
    await server.close()
    await new Promise((resolve) => http.close(resolve))
  })
  return {
    server, emit, base, closedStreams,
    async phone(authenticate = true) {
      const ws = new WebSocket(`ws://${address}`)
      const messages = inbox()
      ws.on('message', (data) => messages.push(JSON.parse(data.toString())))
      await once(ws, 'open')
      let id = 0
      const call = (op, args = {}) => {
        const request = ++id
        ws.send(JSON.stringify({ id: request, op, args }))
        return messages.next((frame) => frame.id === request)
      }
      if (authenticate) assert.equal((await call('hello', { proto: 1, token: TOKEN })).ok, true)
      return { ws, call, next: messages.next }
    },
    async desktop() {
      const messages = inbox()
      let buffer = ''
      const request = get(`${base}/pocket/completion/events`)
      const [response] = await once(request, 'response')
      assert.equal(response.statusCode, 200)
      response.setEncoding('utf8')
      response.on('data', (chunk) => {
        buffer += chunk
        let end
        while ((end = buffer.indexOf('\n\n')) >= 0) {
          const line = buffer.slice(0, end)
          buffer = buffer.slice(end + 2)
          if (line.startsWith('data: ')) messages.push(JSON.parse(line.slice(6)))
        }
      })
      const initial = await messages.next()
      return { initial, next: messages.next, close: () => response.destroy() }
    },
    post: (body, headers = {}) => fetch(`${base}/pocket/completion/view`, {
      method: 'POST', headers: { 'content-type': 'application/json', ...headers }, body: JSON.stringify(body),
    }),
  }
}

test('WS and desktop share unread; view clears all; follow does not view; reconnect preserves state', { timeout: 10_000 }, async (t) => {
  const f = await fixture(t)
  const a = await f.phone()
  const b = await f.phone()
  const desktop = await f.desktop()
  assert.deepEqual(desktop.initial.unreadIds, [])
  assert.deepEqual((await a.call('completion.state')).result.unreadIds, [])
  await a.call('follow', { sessionId: 's' })
  f.emit('status', ['s', true])
  f.emit('status', ['s', false])
  const first = await a.next((m) => m.ev === 'completion')
  assert.deepEqual(first.unreadIds, ['s'])
  assert.deepEqual(await b.next((m) => m.ev === 'completion'), first)
  assert.deepEqual(await desktop.next(), {
    clientId: desktop.initial.clientId, epoch: first.epoch, revision: first.revision, unreadIds: first.unreadIds,
  })
  const cleared = (await b.call('completion.view', { sessionId: 's' })).result
  assert.deepEqual(cleared.unreadIds, [])
  assert.equal(cleared.revision, first.revision + 1)
  assert.deepEqual((await a.next((m) => m.ev === 'completion')).unreadIds, [])
  assert.deepEqual((await desktop.next()).unreadIds, [])
  f.emit('status', ['s', true])
  f.emit('status', ['s', false])
  assert.equal((await a.call('completion.state')).result.revision, cleared.revision)
  const disconnected = Promise.withResolvers()
  const disconnect = f.server.completion.disconnect.bind(f.server.completion)
  t.mock.method(f.server.completion, 'disconnect', (client) => {
    disconnect(client)
    disconnected.resolve()
  })
  const bClosed = once(b.ws, 'close')
  b.ws.close()
  await bClosed
  await disconnected.promise
  f.emit('status', ['s', true])
  f.emit('status', ['s', false])
  const unread = (await a.call('completion.state')).result
  assert.deepEqual(unread.unreadIds, ['s'])
  const reconnected = await f.phone()
  assert.deepEqual((await reconnected.call('completion.state')).result, unread)
  // Neither list reads nor duplicate stop events change the unread truth.
  await reconnected.call('sessions.list')
  f.emit('status', ['s', false])
  assert.deepEqual((await reconnected.call('completion.state')).result, unread)
  const response = await f.post({ clientId: desktop.initial.clientId, sessionId: 's' })
  assert.equal(response.status, 200)
  assert.deepEqual((await response.json()).unreadIds, [])
  assert.deepEqual((await reconnected.next((m) => m.ev === 'completion')).unreadIds, [])
})

test('desktop SSE presence releases on close, stale IDs and untrusted requests cannot clear unread', { timeout: 10_000 }, async (t) => {
  const f = await fixture(t)
  const desktop = await f.desktop()
  const view = { clientId: desktop.initial.clientId, sessionId: 's' }
  assert.equal((await f.post(view)).status, 200)
  f.emit('status', ['s', true])
  f.emit('status', ['s', false])
  assert.deepEqual(f.server.completion.snapshot().unreadIds, [])
  desktop.close()
  await f.closedStreams.next()
  f.emit('status', ['s', true])
  f.emit('status', ['s', false])
  assert.deepEqual(f.server.completion.snapshot().unreadIds, ['s'])
  assert.equal((await f.post(view)).status, 409)
  const replacement = await f.desktop()
  assert.notEqual(replacement.initial.clientId, view.clientId)
  assert.deepEqual(replacement.initial.unreadIds, ['s'])
  assert.equal((await f.post({ ...view, clientId: replacement.initial.clientId }, { origin: 'http://evil.example' })).status, 403)
  assert.equal((await fetch(`${f.base}/pocket/completion/events`, { headers: { 'sec-fetch-site': 'cross-site' } })).status, 403)
  assert.equal((await f.post({ clientId: replacement.initial.clientId })).status, 400)
  assert.deepEqual(f.server.completion.snapshot().unreadIds, ['s'])
  assert.equal((await f.post({ clientId: replacement.initial.clientId, sessionId: 's' })).status, 200)
  f.emit('status', ['s', true])
  f.emit('status', ['s', false])
  assert.deepEqual(f.server.completion.snapshot().unreadIds, [])
  assert.equal((await f.post({ clientId: replacement.initial.clientId, sessionId: null })).status, 200)
  f.emit('status', ['s', true])
  f.emit('status', ['s', false])
  assert.deepEqual((await replacement.next((m) => m.unreadIds.includes('s'))).unreadIds, ['s'])
})

test('initial running snapshot cannot overwrite live transitions and completion ops require authentication', { timeout: 10_000 }, async (t) => {
  let release
  const f = await fixture(t, () => new Promise((resolve) => { release = resolve }))
  const phone = await f.phone()
  f.emit('status', ['initial', false])
  f.emit('status', ['live', true])
  f.emit('status', ['live', false])
  f.emit('removed', ['deleted'])
  release({ items: [
    { sessionId: 'initial', running: false },
    { sessionId: 'deleted', running: true },
    { sessionId: 'history', running: false },
  ] })
  await phone.next((m) => m.ev === 'completion' && m.unreadIds.includes('live'))
  assert.deepEqual((await phone.call('completion.state')).result.unreadIds, ['initial', 'live'])
  f.emit('status', ['deleted', false])
  f.emit('removed', ['initial'])
  assert.deepEqual((await phone.call('completion.state')).result.unreadIds, ['live'])
  assert.equal((await phone.call('completion.view', {})).error.code, 'bad-request')
  const unauthenticated = await f.phone(false)
  assert.equal((await unauthenticated.call('completion.view', { sessionId: 'live' })).error.code, 'auth/denied')
  assert.deepEqual((await phone.call('completion.state')).result.unreadIds, ['live'])
})
