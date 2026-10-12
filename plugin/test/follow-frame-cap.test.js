import { test } from 'node:test'
import assert from 'node:assert/strict'
import {
  DEFAULT_FOLLOW_MAX_MESSAGES,
  FRAME_HARD_LIMIT,
  FRAME_SOFT_LIMIT,
  fitFollowFrame,
  followPushBytes,
  truncateToolOutputsInFrame,
  createPocketServer,
} from '../src/server.js'
import { createDeviceStore } from '../src/devices.js'
import WebSocket from 'ws'

const OWNER = 'owner-token-follow-cap-test'

function fatToolResult(seq, chars) {
  return {
    type: 'event',
    event: {
      type: 'tool/result',
      seq,
      time: seq,
      data: {
        message: {
          toolCallId: `c${seq}`,
          content: [{ type: 'text', text: 'Z'.repeat(chars) }],
        },
      },
    },
  }
}

function fatSnapshot(n, charsPerTool) {
  return {
    type: 'snapshot',
    header: {},
    cursor: n,
    hasMore: true,
    projections: { values: { title: 'big' } },
    records: Array.from({ length: n }, (_, i) => fatToolResult(i + 1, charsPerTool)),
  }
}

test('DEFAULT_FOLLOW_MAX_MESSAGES is 50 and soft limit leaves headroom under MAX_LINE', () => {
  assert.equal(DEFAULT_FOLLOW_MAX_MESSAGES, 50)
  assert.ok(FRAME_SOFT_LIMIT < FRAME_HARD_LIMIT)
  assert.equal(FRAME_HARD_LIMIT, 1024 * 1024)
})

test('truncateToolOutputsInFrame shortens huge tool/result text', () => {
  const frame = fatSnapshot(2, 50_000)
  const out = truncateToolOutputsInFrame(frame, 1_000)
  const text = out.records[0].event.data.message.content[0].text
  assert.ok(text.length < 1_200)
  assert.ok(text.endsWith('…[truncated]'))
  assert.equal(out.hasMore, true)
  assert.equal(out.cursor, 2)
})

test('fitFollowFrame re-fetches a smaller window when the snapshot is oversized', async () => {
  const big = fatSnapshot(40, 40_000)
  assert.ok(followPushBytes(big) > FRAME_SOFT_LIMIT)

  const pages = [
    { records: fatSnapshot(10, 40_000).records, hasMore: true },
    { records: fatSnapshot(5, 1_000).records, hasMore: true },
  ]
  const sc = {
    page: async (req) => {
      assert.equal(req.maxMessages < 50, true)
      return pages.shift() ?? { records: [], hasMore: false }
    },
  }
  const fitted = await fitFollowFrame(sc, big, { sessionId: 's1', maxMessages: 50 }, undefined)
  assert.ok(followPushBytes(fitted) <= FRAME_SOFT_LIMIT)
  assert.equal(fitted.hasMore, true)
  assert.equal(fitted.cursor, big.cursor)
  assert.ok(fitted.records.length <= 10)
})

test('fitFollowFrame truncates tool outputs when a smaller window is still too big', async () => {
  const big = fatSnapshot(8, 200_000)
  assert.ok(followPushBytes(big) > FRAME_SOFT_LIMIT)
  const sc = {
    page: async () => ({ records: big.records, hasMore: true }),
  }
  const fitted = await fitFollowFrame(sc, big, { sessionId: 's1', maxMessages: 50 }, undefined)
  assert.ok(followPushBytes(fitted) <= FRAME_SOFT_LIMIT)
  assert.ok(followPushBytes(fitted) < FRAME_HARD_LIMIT)
  const sample = fitted.records[0].event.data.message.content[0].text
  assert.ok(sample.includes('[truncated]') || sample.length < 200_000)
})

test('follow without maxMessages passes default 50 to sessionController.follow', async () => {
  const followReqs = []
  const snap = {
    type: 'snapshot',
    header: {},
    cursor: 3,
    hasMore: true,
    projections: {},
    records: [
      { type: 'event', event: { type: 'user/message', seq: 1, time: 1, data: { content: [{ type: 'text', text: 'hi' }] } } },
      { type: 'event', event: { type: 'user/message', seq: 2, time: 2, data: { content: [{ type: 'text', text: 'yo' }] } } },
      { type: 'event', event: { type: 'user/message', seq: 3, time: 3, data: { content: [{ type: 'text', text: 'ok' }] } } },
    ],
  }
  const sc = {
    list: async () => ({ items: [] }),
    page: async () => ({ records: [], hasMore: false }),
    follow: (req, signal) => {
      followReqs.push(req)
      return (async function* () {
        yield snap
        await new Promise((r) => signal.addEventListener('abort', r))
      })()
    },
  }
  const devices = createDeviceStore({ ownerToken: OWNER, persist: false })
  const server = createPocketServer({ sessionController: sc, token: OWNER, port: 0, devices, pairFailDelayMs: 1 })
  const url = `ws://${await server.listen()}`
  const ws = new WebSocket(url)
  const q = []
  const waiters = []
  ws.on('message', (d) => {
    const m = JSON.parse(d)
    const i = waiters.findIndex((w) => w.p(m))
    if (i >= 0) waiters.splice(i, 1)[0].r(m)
    else q.push(m)
  })
  const next = (p = () => true) => {
    const i = q.findIndex(p)
    return i >= 0 ? Promise.resolve(q.splice(i, 1)[0]) : new Promise((r) => waiters.push({ p, r }))
  }
  let id = 0
  const call = (op, args = {}) => {
    const i = ++id
    ws.send(JSON.stringify({ id: i, op, args: { proto: 1, ...args } }))
    return next((m) => m.id === i)
  }
  await new Promise((r) => ws.on('open', r))
  await call('hello', { token: OWNER })
  await call('follow', { sessionId: 'sess-1' })
  const frame = await next((m) => m.ev === 'follow')
  assert.equal(followReqs.length, 1)
  assert.equal(followReqs[0].maxMessages, DEFAULT_FOLLOW_MAX_MESSAGES)
  assert.equal(frame.frame.type, 'snapshot')
  assert.equal(frame.frame.hasMore, true)
  assert.equal(frame.frame.cursor, 3)
  assert.equal(frame.frame.records.length, 3)
  ws.close()
  await server.close()
})

test('follow with explicit maxMessages is honoured', async () => {
  const followReqs = []
  const sc = {
    list: async () => ({ items: [] }),
    page: async () => ({ records: [], hasMore: false }),
    follow: (req, signal) => {
      followReqs.push(req)
      return (async function* () {
        yield { type: 'snapshot', cursor: 0, records: [], hasMore: false, projections: {} }
        await new Promise((r) => signal.addEventListener('abort', r))
      })()
    },
  }
  const devices = createDeviceStore({ ownerToken: OWNER, persist: false })
  const server = createPocketServer({ sessionController: sc, token: OWNER, port: 0, devices, pairFailDelayMs: 1 })
  const url = `ws://${await server.listen()}`
  const ws = new WebSocket(url)
  const q = []
  const waiters = []
  ws.on('message', (d) => {
    const m = JSON.parse(d)
    const i = waiters.findIndex((w) => w.p(m))
    if (i >= 0) waiters.splice(i, 1)[0].r(m)
    else q.push(m)
  })
  const next = (p = () => true) => {
    const i = q.findIndex(p)
    return i >= 0 ? Promise.resolve(q.splice(i, 1)[0]) : new Promise((r) => waiters.push({ p, r }))
  }
  let id = 0
  const call = (op, args = {}) => {
    const i = ++id
    ws.send(JSON.stringify({ id: i, op, args: { proto: 1, ...args } }))
    return next((m) => m.id === i)
  }
  await new Promise((r) => ws.on('open', r))
  await call('hello', { token: OWNER })
  await call('follow', { sessionId: 'sess-2', maxMessages: 12 })
  await next((m) => m.ev === 'follow')
  assert.equal(followReqs[0].maxMessages, 12)
  ws.close()
  await server.close()
})
