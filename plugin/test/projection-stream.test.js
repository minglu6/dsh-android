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

async function start(t, beforeSnapshot = async () => {}) {
  const controller = {
    list: async () => ({ items: [] }),
    async *follow(request, signal) {
      await beforeSnapshot(request.address.sessionId)
      if (signal.aborted) return
      yield { type: 'snapshot', records: [], cursor: 10, projections: { asOfSeq: 10, values: {} } }
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
