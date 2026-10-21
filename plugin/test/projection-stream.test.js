import { test } from 'node:test'
import assert from 'node:assert/strict'
import { once } from 'node:events'
import WebSocket from 'ws'
import { createDeviceStore } from '../src/devices.js'
import { createPocketServer } from '../src/server.js'

const token = 'projection-stream-test-owner'

async function connect(url) {
  const ws = new WebSocket(url)
  const frames = []
  const requests = new Map()
  const waiters = []
  let id = 0
  ws.on('message', data => {
    const message = JSON.parse(data)
    if (requests.has(message.id)) {
      const { resolve, reject } = requests.get(message.id)
      requests.delete(message.id)
      if (message.ok) resolve(message.result)
      else reject(new Error(JSON.stringify(message.error)))
    } else if (message.ev === 'follow') {
      frames.push(message)
      for (const done of waiters.splice(0)) done()
    }
  })
  await once(ws, 'open')
  const call = (op, args = {}) => new Promise((resolve, reject) => {
    const requestId = ++id
    requests.set(requestId, { resolve, reject })
    ws.send(JSON.stringify({ id: requestId, op, args }))
  })
  const barrier = () => call('sessions.list')
  await call('hello', { token, proto: 1 })
  return {
    ws, frames, call, barrier,
    async frameCount(count) {
      while (frames.length < count) await new Promise(resolve => waiters.push(resolve))
    },
  }
}

async function start(t, beforeSnapshot = async () => {}, projections = { asOfSeq: 10, values: {} }) {
  const controller = {
    list: async () => ({ items: [] }),
    async *follow(request, signal) {
      await beforeSnapshot(request.address.sessionId)
      if (signal.aborted) return
      yield { type: 'snapshot', records: [], cursor: 10, projections }
      if (!signal.aborted) await new Promise(resolve => signal.addEventListener('abort', resolve, { once: true }))
    },
  }
  const server = createPocketServer({
    sessionController: controller, token, host: '127.0.0.1', port: 0,
    devices: createDeviceStore({ ownerToken: token, persist: false }),
  })
  t.after(() => server.close())
  return { server, url: `ws://${await server.listen()}` }
}

test('newest pending projection follows its baseline and never leaks to other sessions', { timeout: 10000 }, async t => {
  let release
  const gate = new Promise(resolve => { release = resolve })
  t.after(() => release())
  const { server, url } = await start(t, () => gate)
  const c = await connect(url)
  t.after(() => c.ws.terminate())
  const { subId } = await c.call('follow', { sessionId: 'selected' })
  server.broadcastProjection('selected', 'contextPressure', { pressureTokens: 11, contextWindow: 100 }, 11)
  server.broadcastProjection('selected', 'contextPressure', { pressureTokens: 12, contextWindow: 100 }, 12)
  server.broadcastProjection('selected', 'contextPressure', { pressureTokens: 9, contextWindow: 100 }, 9)
  server.broadcastProjection('other', 'contextPressure', { pressureTokens: 99, contextWindow: 100 }, 99)
  server.broadcastProjection('selected', 'unknown-private-projection', { secret: 'must not cross wire' }, 99)
  await c.barrier()
  assert.deepEqual(c.frames, [])
  release()
  await c.frameCount(2)
  await c.barrier()
  assert.equal(c.frames.length, 2)
  assert.equal(c.frames[0].frame.type, 'snapshot')
  assert.equal(c.frames[1].subId, subId)
  assert.equal(c.frames[1].frame.seq, 12)
  assert.equal(c.frames[1].frame.value.pressureTokens, 12)

  await c.call('unfollow', { subId })
  server.broadcastProjection('selected', 'contextPressure', { pressureTokens: 13, contextWindow: 100 }, 13)
  await c.barrier()
  assert.equal(c.frames.length, 2)
})

test('live projection withdrawal is scoped to the matching authenticated follow', { timeout: 10000 }, async t => {
  const { server, url } = await start(t)
  const a = await connect(url)
  const b = await connect(url)
  t.after(() => { a.ws.terminate(); b.ws.terminate() })
  await a.call('follow', { sessionId: 'a' })
  await b.call('follow', { sessionId: 'b' })
  await Promise.all([a.frameCount(1), b.frameCount(1)])
  server.broadcastProjection('a', 'permissions', { currentValue: 'auto' }, 11)
  server.broadcastProjection('a', 'contextPressure', undefined, 12)
  await Promise.all([a.barrier(), b.barrier()])
  assert.deepEqual(a.frames.slice(1).map(({ frame }) => [frame.key, frame.value]), [
    ['permissions', { currentValue: 'auto' }], ['contextPressure', null],
  ])
  assert.equal(b.frames.length, 1)
})

test('question continuation and queued reply projections reach only the owning session', { timeout: 10000 }, async t => {
  const { server, url } = await start(t)
  const a = await connect(url)
  const b = await connect(url)
  t.after(() => { a.ws.terminate(); b.ws.terminate() })
  await a.call('follow', { sessionId: 'a' })
  await b.call('follow', { sessionId: 'b' })
  await Promise.all([a.frameCount(1), b.frameCount(1)])
  const active = { active: [{ callId: 'choice', state: 'continued', questions: [{ id: 'q', question: 'Choose' }] }], settled: [] }
  const queued = { 'next-step': [{ id: 'reply', source: { kind: 'user-question-reply', callId: 'choice' } }], 'next-turn': [] }
  server.broadcastProjection('a', 'userQuestions', active, 11)
  server.broadcastProjection('a', 'inbox', queued, 12)
  server.broadcastProjection('a', 'userQuestions', { active: [], settled: [{ callId: 'choice', answers: [{ id: 'q', selected: ['A'] }] }] }, 13)
  await Promise.all([a.barrier(), b.barrier()])
  assert.deepEqual(a.frames.slice(1).map(({ frame }) => [frame.key, frame.seq]), [
    ['userQuestions', 11], ['inbox', 12], ['userQuestions', 13],
  ])
  assert.deepEqual(a.frames[1].frame.value, active)
  assert.deepEqual(a.frames[2].frame.value, queued)
  assert.deepEqual(a.frames[3].frame.value.active, [])
  assert.equal(b.frames.length, 1)
})

test('team and child catalog changes follow the initial sequence cut, including withdrawal', { timeout: 10000 }, async t => {
  let release
  const gate = new Promise(resolve => { release = resolve })
  t.after(() => release())
  const { server, url } = await start(t, () => gate)
  const c = await connect(url)
  t.after(() => c.ws.terminate())
  await c.call('follow', { sessionId: 'parent' })
  const catalog = [{ id: 'child', mode: 'continuable', label: 'Research', createdAt: 123 }]
  const team = { members: [{ id: 'parent', name: 'lead', role: 'lead', phase: 'active' }], tasks: [] }
  // Covered by the opening snapshot: must not restore stale header state.
  server.broadcastProjection('parent', 'agentTeam', null, 9)
  server.broadcastProjection('parent', 'subagentCatalog', catalog, 11)
  server.broadcastProjection('other-parent', 'subagentCatalog', [], 12)
  await c.barrier()
  assert.equal(c.frames.length, 0)
  release()
  await c.frameCount(2)
  await c.barrier()
  assert.equal(c.frames.length, 2)
  assert.equal(c.frames[0].frame.projections.asOfSeq, 10)
  assert.deepEqual(c.frames[1].frame, {
    type: 'projection', sessionId: 'parent', key: 'subagentCatalog', value: catalog, seq: 11,
  })
  server.broadcastProjection('parent', 'agentTeam', team, 12)
  server.broadcastProjection('parent', 'subagentCatalog', [], 13)
  server.broadcastProjection('parent', 'subagentCatalog', undefined, 14)
  server.broadcastProjection('parent', 'agentTeam', undefined, 15)
  await c.barrier()
  assert.deepEqual(c.frames.slice(2).map(({ frame }) => [frame.key, frame.value, frame.seq]), [
    ['agentTeam', team, 12], ['subagentCatalog', [], 13], ['subagentCatalog', null, 14], ['agentTeam', null, 15],
  ])
})

test('a todo withdrawal queued before the opening cut cannot restore another session or an old plan', { timeout: 10000 }, async t => {
  let release
  const gate = new Promise(resolve => { release = resolve })
  t.after(() => release())
  const { server, url } = await start(t, () => gate)
  const a = await connect(url)
  const b = await connect(url)
  t.after(() => { a.ws.terminate(); b.ws.terminate() })
  await a.call('follow', { sessionId: 'a' })
  await b.call('follow', { sessionId: 'b' })
  const completed = [{ content: 'Old plan', status: 'completed' }]
  server.broadcastProjection('a', 'todos', completed, 9)
  server.broadcastProjection('a', 'todos', null, 11)
  server.broadcastProjection('a', 'todos', completed, 10)
  await Promise.all([a.barrier(), b.barrier()])
  assert.equal(a.frames.length, 0)
  release()
  await a.frameCount(2)
  await b.frameCount(1)
  await Promise.all([a.barrier(), b.barrier()])
  assert.equal(a.frames.length, 2)
  assert.equal(a.frames[0].frame.type, 'snapshot')
  assert.deepEqual(a.frames[1].frame, {
    type: 'projection', sessionId: 'a', key: 'todos', value: null, seq: 11,
  })
  assert.equal(b.frames.length, 1)
})

test('whole-log turn outline stays an array in the opening snapshot and live replacements', { timeout: 10000 }, async t => {
  const initial = [
    { turn: 0, seq: 0, prompt: 'unloaded first turn', response: 'settled answer' },
    { turn: 2, seq: 8, prompt: 'loaded tail', response: '' },
  ]
  const { server, url } = await start(t, undefined, { asOfSeq: 10, values: { turnOutline: initial } })
  const a = await connect(url)
  const b = await connect(url)
  t.after(() => { a.ws.terminate(); b.ws.terminate() })
  await a.call('follow', { sessionId: 'a' })
  await b.call('follow', { sessionId: 'b' })
  await Promise.all([a.frameCount(1), b.frameCount(1)])
  assert.deepEqual(a.frames[0].frame.projections.values.turnOutline, initial)
  const settled = initial.map(entry => entry.turn === 2 ? { ...entry, response: 'final response' } : entry)
  const next = [...settled, { turn: 3, seq: 12, prompt: '', response: '' }]
  server.broadcastProjection('a', 'turnOutline', settled, 11)
  server.broadcastProjection('a', 'turnOutline', next, 12)
  await Promise.all([a.barrier(), b.barrier()])
  assert.deepEqual(a.frames.slice(1).map(({ frame }) => [frame.key, frame.value, frame.seq]), [
    ['turnOutline', settled, 11], ['turnOutline', next, 12],
  ])
  assert.equal(b.frames.length, 1)
  server.broadcastProjection('a', 'turnOutline', undefined, 13)
  await a.barrier()
  assert.equal(a.frames[3].frame.value, null)
})

test('newest buffered outline follows the snapshot cut without flattening its full array', { timeout: 10000 }, async t => {
  let release
  const gate = new Promise(resolve => { release = resolve })
  t.after(() => release())
  const initial = [{ turn: 0, seq: 0, prompt: 'first', response: '' }]
  const newest = [...initial, { turn: 1, seq: 11, prompt: 'second', response: 'done' }]
  const { server, url } = await start(t, () => gate, { asOfSeq: 10, values: { turnOutline: initial } })
  const c = await connect(url)
  t.after(() => c.ws.terminate())
  await c.call('follow', { sessionId: 'selected' })
  server.broadcastProjection('selected', 'turnOutline', [], 9)
  server.broadcastProjection('selected', 'turnOutline', [...initial, { turn: 1, seq: 11, prompt: '', response: '' }], 11)
  server.broadcastProjection('selected', 'turnOutline', newest, 12)
  server.broadcastProjection('selected', 'turnOutline', null, 11)
  server.broadcastProjection('other', 'turnOutline', [], 20)
  await c.barrier()
  assert.equal(c.frames.length, 0)
  release()
  await c.frameCount(2)
  await c.barrier()
  assert.equal(c.frames.length, 2)
  assert.deepEqual(c.frames[0].frame.projections.values.turnOutline, initial)
  assert.deepEqual(c.frames[1].frame, {
    type: 'projection', sessionId: 'selected', key: 'turnOutline', value: newest, seq: 12,
  })
})

test('resume conversion preserves the whole-log outline and drops buffered updates covered by its cut', { timeout: 10000 }, async t => {
  let release
  const gate = new Promise(resolve => { release = resolve })
  t.after(() => release())
  const outline = [
    { turn: 0, seq: 0, prompt: 'outside resumed event range', response: 'complete' },
    { turn: 1, seq: 8, prompt: 'tail', response: '' },
  ]
  const { server, url } = await start(t, () => gate, { asOfSeq: 10, values: { turnOutline: outline } })
  const c = await connect(url)
  t.after(() => c.ws.terminate())
  await c.call('follow', { sessionId: 'selected', afterSeq: 10 })
  server.broadcastProjection('selected', 'turnOutline', null, 9)
  server.broadcastProjection('selected', 'turnOutline', [], 10)
  release()
  await c.frameCount(1)
  await c.barrier()
  assert.equal(c.frames.length, 1)
  assert.equal(c.frames[0].frame.type, 'resume')
  assert.deepEqual(c.frames[0].frame.records, [])
  assert.deepEqual(c.frames[0].frame.projections, { asOfSeq: 10, values: { turnOutline: outline } })
  server.broadcastProjection('selected', 'turnOutline', [...outline, { turn: 2, seq: 11 }], 11)
  await c.barrier()
  assert.equal(c.frames[1].frame.key, 'turnOutline')
  assert.equal(c.frames[1].frame.value.length, 3)
})
