import { test } from 'node:test'
import assert from 'node:assert/strict'
import WebSocket from 'ws'
import { createDeviceStore } from '../src/devices.js'
import {
  createPocketServer,
  followMaxMessages,
} from '../src/server.js'

const OWNER = 'owner-token-follow-cap-test'

const ev = (seq, text = 'x') => ({
  type: 'event',
  event: {
    type: 'user/message',
    seq,
    time: seq,
    data: { content: [{ type: 'text', text }], source: { kind: 'user' } },
  },
})

const toolResult = (seq, text) => ({
  type: 'event',
  event: {
    type: 'tool/result',
    seq,
    time: seq,
    data: {
      message: {
        toolCallId: `c${seq}`,
        content: [{ type: 'text', text }],
      },
    },
  },
})

function mockSc(snapshot, { pages = [], followCalls = [] } = {}) {
  return {
    list: async () => ({ items: [] }),
    page: async (req) => {
      pages.push(req)
      return { records: (snapshot.records ?? []).slice(-Math.min(req.maxMessages ?? 50, 5)), hasMore: true }
    },
    follow: (req, signal) => {
      followCalls.push(req)
      const max = req.maxMessages
      let frame = snapshot
      if (Number.isSafeInteger(max) && Array.isArray(snapshot.records) && snapshot.records.length > max) {
        frame = {
          ...snapshot,
          records: snapshot.records.slice(-max),
          hasMore: true,
        }
      }
      return (async function* () {
        yield frame
        await new Promise((r) => signal.addEventListener('abort', r))
      })()
    },
  }
}

async function setup(sc) {
  const devices = createDeviceStore({ ownerToken: OWNER, persist: false })
  const server = createPocketServer({ sessionController: sc, token: OWNER, port: 0, devices, pairFailDelayMs: 1 })
  const url = `ws://${await server.listen()}`
  return { server, url }
}

function client(url) {
  const ws = new WebSocket(url)
  const q = []
  const w = []
  ws.on('message', (d) => {
    const m = JSON.parse(d)
    const i = w.findIndex((x) => x.p(m))
    if (i >= 0) w.splice(i, 1)[0].r(m)
    else q.push(m)
  })
  const next = (p = () => true) => {
    const i = q.findIndex(p)
    return i >= 0
      ? Promise.resolve(q.splice(i, 1)[0])
      : new Promise((r) => w.push({ p, r }))
  }
  let id = 0
  const call = (op, args = {}) => {
    const i = ++id
    ws.send(JSON.stringify({ id: i, op, args: { proto: 1, ...args } }))
    return next((m) => m.id === i)
  }
  return {
    ws,
    next,
    call,
    open: new Promise((r) => ws.on('open', r)),
  }
}

test('followMaxMessages omits a window unless the client asks', () => {
  assert.equal(followMaxMessages({}), undefined)
  assert.equal(followMaxMessages({ maxMessages: 12 }), 12)
  assert.throws(() => followMaxMessages({ maxMessages: 0 }), /positive integer/)
  assert.throws(() => followMaxMessages({ maxMessages: -1 }), /positive integer/)
})

test('follow without maxMessages forwards the Host snapshot unwindowed', async () => {
  const records = Array.from({ length: 80 }, (_, i) => ev(i + 1, `m${i}`))
  const snap = {
    type: 'snapshot',
    header: {},
    cursor: 80,
    records,
    hasMore: false,
    projections: {},
  }
  const followCalls = []
  const sc = mockSc(snap, { followCalls })
  const { server, url } = await setup(sc)
  const c = client(url)
  await c.open
  await c.call('hello', { token: OWNER })
  await c.call('follow', { sessionId: 's-fat' })
  const f = await c.next((m) => m.ev === 'follow')
  assert.equal(followCalls[0].maxMessages, undefined)
  assert.equal(f.frame.type, 'snapshot')
  assert.equal(f.frame.records.length, 80)
  assert.equal(f.frame.cursor, 80)
  assert.equal(f.frame.hasMore, false)
  assert.equal(f.frame.records[0].event.seq, 1)
  assert.equal(f.frame.records.at(-1).event.seq, 80)
  c.ws.close()
  await server.close()
})

test('follow rejects a non-positive maxMessages before opening a subscription', async () => {
  const { server, url } = await setup(mockSc({ type: 'snapshot', cursor: 0, records: [], hasMore: false }))
  const c = client(url)
  await c.open
  await c.call('hello', { token: OWNER })
  const denied = await c.call('follow', { sessionId: 's', maxMessages: 0 })
  assert.equal(denied.ok, false)
  assert.equal(denied.error.code, 'bad-request')
  c.ws.close()
  await server.close()
})

test('follow with explicit maxMessages is respected', async () => {
  const records = Array.from({ length: 40 }, (_, i) => ev(i + 1))
  const followCalls = []
  const sc = mockSc(
    { type: 'snapshot', cursor: 40, records, hasMore: false, projections: {} },
    { followCalls },
  )
  const { server, url } = await setup(sc)
  const c = client(url)
  await c.open
  await c.call('hello', { token: OWNER })
  await c.call('follow', { sessionId: 's', maxMessages: 7 })
  const f = await c.next((m) => m.ev === 'follow')
  assert.equal(followCalls[0].maxMessages, 7)
  assert.equal(f.frame.records.length, 7)
  assert.equal(f.frame.hasMore, true)
  c.ws.close()
  await server.close()
})

test('follow does not shrink oversized snapshots to the old iroh line cap', async () => {
  const fatRecs = Array.from({ length: 20 }, (_, i) => toolResult(i + 1, 'W'.repeat(80_000)))
  const snap = {
    type: 'snapshot',
    cursor: 20,
    hasMore: false,
    records: fatRecs,
    projections: {},
  }
  const size = Buffer.byteLength(JSON.stringify(snap), 'utf8')
  assert.ok(size > 900 * 1024)
  const followCalls = []
  const { server, url } = await setup(mockSc(snap, { followCalls }))
  const c = client(url)
  await c.open
  await c.call('hello', { token: OWNER })
  await c.call('follow', { sessionId: 's-tool' })
  const f = await c.next((m) => m.ev === 'follow')
  assert.equal(followCalls[0].maxMessages, undefined)
  assert.equal(f.frame.records.length, 20)
  assert.equal(f.frame.records[0].event.data.message.content[0].text.length, 80_000)
  assert.equal(f.frame.hasMore, false)
  c.ws.close()
  await server.close()
})

test('server connectionType reflects noteIrohPath', async () => {
  const sc = mockSc({ type: 'snapshot', cursor: 0, records: [], hasMore: false })
  const { server } = await setup(sc)
  assert.equal(server.connectionType(), null)
  server.noteIrohPath('peer-a', 'relay')
  assert.equal(server.connectionType(), 'relay')
  server.noteIrohPath('peer-b', 'direct')
  assert.equal(server.connectionType(), 'direct')
  assert.deepEqual(
    server.irohConnections().map((c) => c.kind).sort(),
    ['direct', 'relay'],
  )
  server.clearIrohPath('peer-b')
  assert.equal(server.connectionType(), 'relay')
  await server.close()
})
