import { test } from 'node:test'
import assert from 'node:assert/strict'
import WebSocket from 'ws'
import { createDeviceStore } from '../src/devices.js'
import {
  createPocketServer,
  DEFAULT_FOLLOW_MAX_MESSAGES,
  FRAME_SOFT_LIMIT,
  FRAME_HARD_LIMIT,
  followMaxMessages,
  fitFollowFrame,
  truncateHugeToolOutputs,
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

test('followMaxMessages defaults to 50 when client omits it', () => {
  assert.equal(followMaxMessages({}), DEFAULT_FOLLOW_MAX_MESSAGES)
  assert.equal(followMaxMessages({ maxMessages: 12 }), 12)
  assert.equal(DEFAULT_FOLLOW_MAX_MESSAGES, 50)
})

test('follow without maxMessages caps snapshot at 50 and keeps hasMore/cursor', async () => {
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
  assert.equal(followCalls[0].maxMessages, 50)
  assert.equal(f.frame.type, 'snapshot')
  assert.equal(f.frame.records.length, 50)
  assert.equal(f.frame.cursor, 80)
  assert.equal(f.frame.hasMore, true)
  assert.equal(f.frame.records[0].event.seq, 31)
  assert.equal(f.frame.records.at(-1).event.seq, 80)
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

test('truncateHugeToolOutputs shrinks oversized tool text', () => {
  const huge = 'Z'.repeat(50_000)
  const frame = {
    type: 'snapshot',
    cursor: 1,
    hasMore: false,
    records: [toolResult(1, huge)],
  }
  const out = truncateHugeToolOutputs(frame, 100)
  const text = out.records[0].event.data.message.content[0].text
  assert.ok(text.length < 200)
  assert.match(text, /truncated/)
  assert.equal(out.records[0].event.data.message.content[0].truncated, true)
})

test('fitFollowFrame keeps frames under soft limit and never over hard limit', async () => {
  const fatRecs = Array.from({ length: 20 }, (_, i) => toolResult(i + 1, 'W'.repeat(80_000)))
  const frame = {
    type: 'snapshot',
    cursor: 20,
    hasMore: true,
    records: fatRecs,
    projections: {},
  }
  assert.ok(Buffer.byteLength(JSON.stringify(frame), 'utf8') > FRAME_SOFT_LIMIT)

  const fitted = await fitFollowFrame(frame, {})
  const size = Buffer.byteLength(JSON.stringify(fitted), 'utf8')
  assert.ok(size <= FRAME_SOFT_LIMIT, `fitted size ${size} > soft ${FRAME_SOFT_LIMIT}`)
  assert.ok(size <= FRAME_HARD_LIMIT)
  assert.equal(fitted.hasMore, true)
  assert.equal(fitted.cursor, 20)
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
