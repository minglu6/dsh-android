/**
 * Adapted from saya-ch/dsh-mobile (Apache-2.0) websocket-compression tests.
 * Confirms the pocket WS server negotiates permessage-deflate (threshold ~1KB, level 3)
 * and keeps frame-size caps while relaxing heartbeat/idle defaults for slow links.
 */
import { test, beforeEach, afterEach } from 'node:test'
import assert from 'node:assert/strict'
import WebSocket from 'ws'
import {
  createPocketServer,
  DEFAULT_HEARTBEAT_MS,
  DEFAULT_IDLE_MS,
  HELLO_TIMEOUT_MS,
  WS_DEFLATE,
  FRAME_HARD_LIMIT,
  FRAME_SOFT_LIMIT,
} from '../src/server.js'

const TOKEN = 'test-token-compression-01234567'

function mockSc() {
  return {
    list: async () => ({ items: [] }),
    create: async () => ({ sessionId: 'new' }),
    rename: async () => ({}),
    fork: async () => ({ sessionId: 'fork' }),
    prompt: async () => ({ accepted: true }),
    cancel: async () => ({ accepted: true }),
    updateQueue: async () => ({ accepted: true }),
    page: async () => ({ records: [], hasMore: false }),
    modelCatalog: async () => ({ groups: [] }),
    selectModel: async () => ({}),
    follow: async function* () { /* empty */ },
  }
}

let server, url
beforeEach(async () => {
  server = createPocketServer({
    sessionController: mockSc(), token: TOKEN, host: '127.0.0.1', port: 0,
    heartbeatMs: 50, idleMs: 10_000,
  })
  const addr = await server.listen()
  url = `ws://${addr}`
})
afterEach(async () => { await server.close() })

test('exports slow-link defaults matching dsh-mobile guidance', () => {
  assert.equal(DEFAULT_HEARTBEAT_MS, 30_000)
  assert.equal(DEFAULT_IDLE_MS, 120_000)
  assert.equal(HELLO_TIMEOUT_MS, 120_000)
  assert.equal(WS_DEFLATE.threshold, 1024)
  assert.equal(WS_DEFLATE.zlibDeflateOptions.level, 3)
  assert.equal(WS_DEFLATE.clientNoContextTakeover, true)
  assert.equal(WS_DEFLATE.serverNoContextTakeover, true)
  // Frame caps remain in force for decoded payloads.
  assert.equal(FRAME_HARD_LIMIT, 1024 * 1024)
  assert.ok(FRAME_SOFT_LIMIT < FRAME_HARD_LIMIT)
})

test('negotiates permessage-deflate with a compressing client (OkHttp-compatible)', async () => {
  const ws = new WebSocket(url, {
    perMessageDeflate: {
      threshold: 1024,
      zlibDeflateOptions: { level: 3 },
    },
  })
  await new Promise((resolve, reject) => {
    ws.once('open', resolve)
    ws.once('error', reject)
  })
  assert.match(String(ws.extensions), /permessage-deflate/)
  const hello = await new Promise((resolve) => {
    ws.once('message', (d) => resolve(JSON.parse(d.toString())))
    ws.send(JSON.stringify({ id: 1, op: 'hello', args: { proto: 1, token: TOKEN } }))
  })
  assert.equal(hello.ok, true)
  ws.close()
})

test('still accepts clients that omit compression', async () => {
  const ws = new WebSocket(url, { perMessageDeflate: false })
  await new Promise((resolve, reject) => {
    ws.once('open', resolve)
    ws.once('error', reject)
  })
  assert.equal(ws.extensions, '')
  const hello = await new Promise((resolve) => {
    ws.once('message', (d) => resolve(JSON.parse(d.toString())))
    ws.send(JSON.stringify({ id: 1, op: 'hello', args: { proto: 1, token: TOKEN } }))
  })
  assert.equal(hello.ok, true)
  ws.close()
})

test('broadcastTaskEvent pushes minimal done/question payloads', async () => {
  const ws = new WebSocket(url, { perMessageDeflate: true })
  const queue = []
  const waiters = []
  ws.on('message', (d) => {
    const m = JSON.parse(d.toString())
    const i = waiters.findIndex((w) => w.pred(m))
    if (i >= 0) waiters.splice(i, 1)[0].resolve(m)
    else queue.push(m)
  })
  const next = (pred = () => true) => {
    const i = queue.findIndex(pred)
    if (i >= 0) return Promise.resolve(queue.splice(i, 1)[0])
    return new Promise((resolve) => waiters.push({ pred, resolve }))
  }
  await new Promise((r) => ws.once('open', r))
  ws.send(JSON.stringify({ id: 1, op: 'hello', args: { proto: 1, token: TOKEN } }))
  assert.equal((await next((m) => m.id === 1)).ok, true)

  server.broadcastTaskEvent({ sessionId: 's1', turn: 4, kind: 'done' })
  assert.deepEqual(await next((m) => m.ev === 'task'), {
    ev: 'task', kind: 'done', sessionId: 's1', turn: 4,
  })

  server.offerUserQuestion({ sessionId: 's1', callId: 'q1', questions: [] })
  const uq = await next((m) => m.ev === 'user-question')
  assert.equal(uq.questionId, 'q1')
  assert.deepEqual(await next((m) => m.ev === 'task' && m.kind === 'question'), {
    ev: 'task', kind: 'question', sessionId: 's1', turn: 0, questionId: 'q1',
  })
  ws.close()
})
