import { test, beforeEach, afterEach } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtempSync, statSync, readFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import WebSocket from 'ws'
import { createPocketServer } from '../src/server.js'
import { loadOrCreateToken } from '../src/token.js'
import { feedbackOps } from '../src/parity-ops.js'

const TOKEN = 'test-token-0123456789abcdef'

function mockSc() {
  const calls = []
  const rec = (name, value) => (...args) => { calls.push([name, ...args]); return typeof value === 'function' ? value(...args) : value }
  let release
  const gate = new Promise((r) => { release = r })
  return {
    calls, release: () => release(),
    list: rec('list', { items: [{ sessionId: 's1', running: false }] }),
    create: rec('create', { sessionId: 'new1' }),
    rename: rec('rename', { title: 'T', seq: 3 }),
    fork: rec('fork', (r) => ({ sessionId: `fork-of-${r.sessionId}`, atSeq: r.atSeq ?? null })),
    prompt: rec('prompt', { accepted: true }),
    cancel: rec('cancel', { accepted: true }),
    updateQueue: rec('updateQueue', { accepted: true }),
    page: rec('page', { records: [], hasMore: false }),
    modelCatalog: rec('modelCatalog', { groups: [] }),
    selectModel: rec('selectModel', (r) => ({ selected: { provider: r.provider, model: r.model } })),
    follow: rec('follow', (_req, signal) => (async function* () {
      yield { type: 'snapshot', cursor: 0, records: [], hasMore: false }
      yield { type: 'event', event: { type: 'turn/start', seq: 1, time: 0, data: {} } }
      await Promise.race([gate, new Promise((r) => signal.addEventListener('abort', r))])
    })()),
  }
}

let sc, server, url, emit
beforeEach(async () => {
  sc = mockSc()
  let listener
  server = createPocketServer({
    sessionController: sc, token: TOKEN, host: '127.0.0.1', port: 0, heartbeatMs: 50, idleMs: 10_000,
    onSessionEvent: (fn) => { listener = fn; return () => {} },
  })
  const addr = await server.listen()
  url = `ws://${addr}`
  emit = (k, a) => listener(k, a)
})
afterEach(async () => { sc.release(); await server.close() })

function connect() {
  const ws = new WebSocket(url)
  const queue = []; const waiters = []
  ws.on('message', (d) => {
    const m = JSON.parse(d.toString())
    const i = waiters.findIndex((w) => w.pred(m))
    if (i >= 0) waiters.splice(i, 1)[0].resolve(m); else queue.push(m)
  })
  const next = (pred = () => true) => {
    const i = queue.findIndex(pred)
    if (i >= 0) return Promise.resolve(queue.splice(i, 1)[0])
    return new Promise((resolve) => waiters.push({ pred, resolve }))
  }
  let id = 0
  const call = async (op, args = {}) => { const myId = ++id; ws.send(JSON.stringify({ id: myId, op, args })); return next((m) => m.id === myId) }
  const closed = new Promise((r) => ws.on('close', (code) => r(code)))
  return { ws, next, call, closed, open: new Promise((r) => ws.on('open', r)) }
}
async function authed() {
  const c = connect(); await c.open
  const r = await c.call('hello', { proto: 1, token: TOKEN })
  assert.equal(r.ok, true)
  return c
}

test('feedback wire replies contain values on success and complete business failures on rejection', async () => {
  const item = {
    messageId: 'm1', rating: 'positive', note: 'another client', category: 'task-result',
    version: 'v2', createdAt: 1, updatedAt: 2,
  }
  const cases = [
    { method: 'list', value: { items: [item] } },
    { method: 'put', value: item },
    { method: 'delete', value: { absent: true } },
    { method: 'list', error: { code: 'session-not-found', sessionId: 's1' } },
    { method: 'put', error: { code: 'version-conflict', current: item } },
    { method: 'put', error: { code: 'version-conflict', current: null } },
    { method: 'delete', error: { code: 'version-conflict', current: item } },
    { method: 'put', error: { code: 'note-too-large', maxBytes: 100, actualBytes: 101 } },
  ]
  const c = await authed()
  try {
    for (const { method, value, error } of cases) {
      const unregister = server.registerOps(feedbackOps({
        [method]: async () => error ? { ok: false, error } : { ok: true, value },
      }))
      try {
        const frame = await c.call(method === 'list' ? 'feedback.get' : 'feedback.set', {
          sessionId: 's1', messageId: 'm1', rating: method === 'delete' ? null : 'negative', ifVersion: 'v1',
        })
        if (error) {
          assert.equal(frame.ok, false)
          assert.equal(Object.hasOwn(frame, 'result'), false)
          assert.deepEqual(frame.error, { code: error.code, message: error.code, details: error })
          if (Object.hasOwn(error, 'current')) {
            assert.equal(Object.hasOwn(frame.error.details, 'current'), true)
            assert.deepEqual(frame.error.details.current, error.current)
          }
        } else {
          assert.equal(frame.ok, true)
          assert.equal(Object.hasOwn(frame, 'error'), false)
          assert.deepEqual(frame.result, value)
        }
      } finally {
        unregister()
      }
    }
  } finally {
    c.ws.close()
  }
})

test('permission catalog invalidation reaches only authenticated clients', async () => {
  const authenticated = await authed()
  const pending = connect()
  await pending.open
  const pendingEvents = []
  pending.ws.on('message', (data) => {
    const frame = JSON.parse(data.toString())
    if (frame.ev) pendingEvents.push(frame)
  })
  server.broadcastPermissionCatalogChanged()
  assert.deepEqual(await authenticated.next((frame) => frame.ev === 'permission-presets/catalog-changed'), {
    ev: 'permission-presets/catalog-changed',
  })
  // The hello response is a wire-order barrier after the earlier broadcast.
  assert.equal((await pending.call('hello', { proto: 1, token: TOKEN })).ok, true)
  assert.deepEqual(pendingEvents, [])
  server.broadcastPermissionCatalogChanged()
  assert.deepEqual(await pending.next((frame) => frame.ev === 'permission-presets/catalog-changed'), {
    ev: 'permission-presets/catalog-changed',
  })
  authenticated.ws.close()
  pending.ws.close()
})

test('rejects bad token and closes 4401', async () => {
  const c = connect(); await c.open
  const r = await c.call('hello', { proto: 1, token: 'nope' })
  assert.equal(r.error.code, 'auth/denied')
  assert.equal(await c.closed, 4401)
})

test('rejects ops before hello', async () => {
  const c = connect(); await c.open
  const r = await c.call('sessions.list')
  assert.equal(r.ok, false)
  assert.equal(await c.closed, 4401)
})

test('hello lists ops; sessions.list/create/rename/fork pass through', async () => {
  const c = connect(); await c.open
  const h = await c.call('hello', { proto: 1, token: TOKEN })
  assert.ok(h.result.ops.includes('prompt'))
  assert.ok(h.result.ops.includes('sessions.fork'))
  assert.deepEqual((await c.call('sessions.list')).result.items[0].sessionId, 's1')
  assert.equal((await c.call('sessions.create', { cwd: '/tmp' })).result.sessionId, 'new1')
  assert.deepEqual(sc.calls.find((x) => x[0] === 'create')[1], { cwd: '/tmp' })
  assert.equal((await c.call('sessions.rename', { sessionId: 's1', title: 'T' })).ok, true)
  const forked = await c.call('sessions.fork', { sessionId: 's1', atSeq: 42 })
  assert.equal(forked.result.sessionId, 'fork-of-s1')
  assert.deepEqual(sc.calls.find((x) => x[0] === 'fork')[1], { sessionId: 's1', atSeq: 42 })
  const forkedLatest = await c.call('sessions.fork', { sessionId: 's1' })
  assert.equal(forkedLatest.result.sessionId, 'fork-of-s1')
  assert.deepEqual(sc.calls.filter((x) => x[0] === 'fork').at(-1)[1], { sessionId: 's1' })
  c.ws.close()
})

test('prompt builds content, validates mode', async () => {
  const c = await authed()
  const bad = await c.call('prompt', { sessionId: 's1', mode: 'x', text: 'hi' })
  assert.equal(bad.error.code, 'bad-request')
  const ok = await c.call('prompt', { sessionId: 's1', mode: 'steer', text: 'hi', images: [{ mediaType: 'image/png', data: 'AAA=' }] })
  assert.equal(ok.result.accepted, true)
  const req = sc.calls.find((x) => x[0] === 'prompt')[1]
  assert.equal(req.mode, 'steer')
  assert.equal(typeof req.requestId, 'string')
  assert.deepEqual(req.content, [{ type: 'text', text: 'hi' }, { type: 'image', mediaType: 'image/png', data: 'AAA=' }])
  const withFile = await c.call('prompt', { sessionId: 's1', mode: 'queue', text: 'doc', files: [{ receiptId: 'r1' }] })
  assert.equal(withFile.result.accepted, true)
  const fileReq = sc.calls.filter((x) => x[0] === 'prompt').at(-1)[1]
  assert.deepEqual(fileReq.content, [{ type: 'text', text: 'doc' }, { type: 'file', receiptId: 'r1' }])
  c.ws.close()
})

test('cancel, page, models', async () => {
  const c = await authed()
  assert.equal((await c.call('cancel', { sessionId: 's1' })).result.accepted, true)
  await c.call('history.page', { sessionId: 's1', throughSeq: 10, maxMessages: 5 })
  assert.deepEqual(sc.calls.find((x) => x[0] === 'page')[1], { address: { kind: 'session', sessionId: 's1' }, throughSeq: 10, maxMessages: 5 })
  assert.deepEqual((await c.call('models.catalog')).result, { groups: [] })
  const sel = await c.call('models.select', { sessionId: 's1', provider: 'p', model: 'm', reasoningEffort: 'high' })
  assert.deepEqual(sel.result.selected, { provider: 'p', model: 'm' })
  assert.equal((await c.call('nope')).error.code, 'unknown-op')
  c.ws.close()
})

test('follow streams frames and unfollow aborts', async () => {
  const c = await authed()
  const r = await c.call('follow', { sessionId: 's1' })
  const subId = r.result.subId
  const f1 = await c.next((m) => m.ev === 'follow')
  assert.equal(f1.frame.type, 'snapshot')
  const f2 = await c.next((m) => m.ev === 'follow')
  assert.equal(f2.frame.event.type, 'turn/start')
  assert.deepEqual(sc.calls.find((x) => x[0] === 'follow')[1].address, { kind: 'session', sessionId: 's1' })
  const u = await c.call('unfollow', { subId })
  assert.equal(u.result.stopped, true)
  c.ws.close()
})

test('follow end is reported', async () => {
  const c = await authed()
  await c.call('follow', { sessionId: 's1' })
  sc.release()
  const end = await c.next((m) => m.ev === 'follow.end')
  assert.ok(end.subId)
  c.ws.close()
})

test('session events and heartbeat reach authed clients', async () => {
  const c = await authed()
  emit('status', ['s1', true])
  const ev = await c.next((m) => m.ev === 'session')
  assert.deepEqual(ev, { ev: 'session', kind: 'status', sessionId: 's1', running: true })
  const ping = await c.next((m) => m.ev === 'ping')
  assert.ok(ping.t)
  c.ws.close()
})

test('token created once with 0600', () => {
  const dir = join(mkdtempSync(join(tmpdir(), 'pocket-')), 'pocket')
  const a = loadOrCreateToken(dir)
  assert.equal(a.created, true)
  assert.equal(statSync(a.path).mode & 0o777, 0o600)
  const b = loadOrCreateToken(dir)
  assert.equal(b.created, false)
  assert.equal(b.token, a.token)
  assert.equal(readFileSync(a.path, 'utf8').trim(), a.token)
  assert.equal(loadOrCreateToken(dir, 'fixed-token-value').token, 'fixed-token-value')
})
