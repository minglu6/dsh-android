/**
 * Adapted from saya-ch/dsh-mobile (Apache-2.0) task-notify fallbacks.
 */
import { test } from 'node:test'
import assert from 'node:assert/strict'
import {
  TaskFallbackTracker,
  TASK_NOTIFY_QUIET_MS,
  TASK_NOTIFY_GRACE_MS,
  TASK_NOTIFY_MIN_BUSY_MS,
} from '../src/task-fallback.js'

function trackerAt(startMs = 1_000_000, overrides = {}) {
  let now = startMs
  const announced = []
  const tracker = new TaskFallbackTracker({
    now: () => now,
    onTaskCompleted: (event) => { announced.push(event) },
    ...overrides,
  })
  return {
    tracker,
    announced,
    advance: (ms) => { now += ms },
    flush: async (ms = 5) => {
      await new Promise((r) => setTimeout(r, ms))
    },
  }
}

test('announces once after the quiet period following activity', async () => {
  const { tracker, announced, advance, flush } = trackerAt()
  tracker.markActivity('s1')
  assert.deepEqual(announced, [])
  advance(TASK_NOTIFY_QUIET_MS - 1)
  tracker.evaluate('s1')
  assert.deepEqual(announced, [])
  advance(1)
  await flush(20)
  assert.deepEqual(announced, [{ sessionId: 's1', turn: 0, kind: 'done' }])
  tracker.evaluate('s1')
  assert.equal(announced.length, 1)
})

test('busy-to-idle grace announces after graceMs', async () => {
  const { tracker, announced, advance, flush } = trackerAt()
  tracker.onStatus('s1', true)
  advance(1_000) // short busy — uses grace, not instant
  tracker.onStatus('s1', false)
  assert.deepEqual(announced, [])
  advance(TASK_NOTIFY_GRACE_MS - 1)
  tracker.evaluate('s1')
  assert.deepEqual(announced, [])
  advance(1)
  await flush(20)
  assert.deepEqual(announced, [{ sessionId: 's1', turn: 0, kind: 'done' }])
})

test('long busy announces immediately on idle', () => {
  const { tracker, announced, advance } = trackerAt()
  tracker.onStatus('s1', true)
  advance(TASK_NOTIFY_MIN_BUSY_MS)
  tracker.onStatus('s1', false)
  assert.deepEqual(announced, [{ sessionId: 's1', turn: 0, kind: 'done' }])
})

test('skips child sessions and pending questions', async () => {
  const pending = new Set()
  const { tracker, announced, advance, flush } = trackerAt(1_000_000, {
    isRootSession: (id) => id !== 'child',
    hasPendingQuestion: (id) => pending.has(id),
  })
  tracker.onStatus('child', true)
  advance(TASK_NOTIFY_MIN_BUSY_MS)
  tracker.onStatus('child', false)
  assert.deepEqual(announced, [])

  tracker.onStatus('s1', true)
  advance(TASK_NOTIFY_MIN_BUSY_MS)
  pending.add('s1')
  tracker.onStatus('s1', false)
  assert.deepEqual(announced, [])
  // Pending question suppressed the idle announce; clear it and use quiet fallback.
  pending.delete('s1')
  tracker.markActivity('s1')
  advance(TASK_NOTIFY_QUIET_MS)
  await flush(20)
  tracker.evaluate('s1')
  assert.deepEqual(announced, [{ sessionId: 's1', turn: 0, kind: 'done' }])
})

test('markAnnounced suppresses a subsequent quiet fallback', async () => {
  const { tracker, announced, advance, flush } = trackerAt()
  tracker.markActivity('s1')
  tracker.markAnnounced('s1')
  advance(TASK_NOTIFY_QUIET_MS)
  await flush(20)
  tracker.evaluate('s1')
  assert.deepEqual(announced, [])
})

test('dispose clears pending timers', async () => {
  const { tracker, announced, advance, flush } = trackerAt()
  tracker.markActivity('s1')
  tracker.dispose()
  advance(TASK_NOTIFY_QUIET_MS)
  await flush(20)
  assert.deepEqual(announced, [])
})
