/**
 * Adapted from saya-ch/dsh-mobile (Apache-2.0) tests/task-events.test.ts
 */
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { TaskEventHub, watchTaskCompletions } from '../src/task-events.js'

function context(debounceMs = 5) {
  const handlers = []
  const announced = []
  const events = []
  const dispose = watchTaskCompletions(
    { on: (_event, handler) => { handlers.push(handler); return () => {} } },
    {
      debounceMs,
      onTaskCompleted: (event) => { announced.push(event) },
      log: (event, fields) => { events.push([event, fields]) },
    },
  )
  return { handlers, announced, events, dispose }
}

function turnEnd(reason, turn = 3) {
  return { type: 'turn/end', data: { turn, reason } }
}

const root = { id: 'session-1', header: {} }
const child = { id: 'session-2', header: { parentSession: 'session-1' } }

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

test('announces a completed root turn after debouncing', async () => {
  const { handlers, announced } = context()
  for (const handler of handlers) handler(root, turnEnd({ kind: 'completed' }))
  assert.deepEqual(announced, [])
  await sleep(20)
  assert.deepEqual(announced, [{ sessionId: 'session-1', turn: 3, kind: 'done' }])
})

test('ignores non-completed reasons and unrelated events', async () => {
  const { handlers, announced } = context()
  for (const handler of handlers) {
    handler(root, turnEnd({ kind: 'aborted' }))
    handler(root, turnEnd({ kind: 'error' }))
    handler(root, { type: 'turn/start', data: { turn: 3 } })
    handler(root, { type: 'assistant/message', data: { turn: 3 } })
  }
  await sleep(30)
  assert.deepEqual(announced, [])
})

test('skips subagent turns so one task announces once', async () => {
  const { handlers, announced } = context()
  for (const handler of handlers) handler(child, turnEnd({ kind: 'completed' }))
  await sleep(30)
  assert.deepEqual(announced, [])
})

test('collapses rapid turn boundaries into a single announcement', async () => {
  const { handlers, announced } = context()
  for (const handler of handlers) {
    handler(root, turnEnd({ kind: 'completed' }, 3))
    handler(root, turnEnd({ kind: 'completed' }, 4))
  }
  await sleep(20)
  assert.deepEqual(announced, [{ sessionId: 'session-1', turn: 4, kind: 'done' }])
  await sleep(30)
  assert.equal(announced.length, 1)
})

test('drops pending debounces on dispose', async () => {
  const { handlers, announced, dispose } = context()
  for (const handler of handlers) handler(root, turnEnd({ kind: 'completed' }))
  dispose()
  await sleep(30)
  assert.deepEqual(announced, [])
})

test('accepts string reason completed', async () => {
  const { handlers, announced } = context()
  for (const handler of handlers) handler(root, turnEnd('completed', 2))
  await sleep(20)
  assert.deepEqual(announced, [{ sessionId: 'session-1', turn: 2, kind: 'done' }])
})

test('task event hub fans out and isolates failures', () => {
  const hub = new TaskEventHub()
  const first = []
  const disposeFirst = hub.add({ broadcastTaskEvent: (event) => { first.push(event) } })
  hub.add({
    broadcastTaskEvent: () => { throw new Error('gateway gone') },
  })
  assert.equal(hub.size, 2)
  assert.doesNotThrow(() => { hub.broadcast({ sessionId: 's', turn: 1, kind: 'done' }) })
  assert.deepEqual(first, [{ sessionId: 's', turn: 1, kind: 'done' }])
  disposeFirst()
  assert.equal(hub.size, 1)
})
