import { test } from 'node:test'
import assert from 'node:assert/strict'
import { CompletionTracker } from '../src/completion-tracker.js'

test('only true-to-false transitions create unread, with idempotent revisions and fresh epochs', () => {
  const tracker = new CompletionTracker()
  assert.notEqual(tracker.epoch, new CompletionTracker().epoch)
  const updates = []
  tracker.subscribe((snapshot) => updates.push(snapshot))
  tracker.status('history', false)
  assert.equal(tracker.snapshot().revision, 0)
  tracker.status('s', true)
  tracker.status('s', false)
  tracker.status('s', false)
  assert.deepEqual(tracker.snapshot().unreadIds, ['s'])
  assert.equal(tracker.snapshot().revision, 1)
  tracker.status('s', true)
  assert.deepEqual(tracker.snapshot().unreadIds, [])
  tracker.status('s', false)
  tracker.remove('s')
  tracker.remove('s')
  assert.equal(tracker.snapshot().revision, 4)
  assert.equal(updates.length, 4)
})

test('views clear globally; any live view suppresses completion and disconnect never marks read', () => {
  const tracker = new CompletionTracker()
  const complete = () => { tracker.status('s', true); tracker.status('s', false) }
  complete()
  tracker.view('desktop', 's')
  assert.deepEqual(tracker.snapshot().unreadIds, [])
  tracker.view('phone', 's')
  tracker.disconnect('desktop')
  complete()
  assert.deepEqual(tracker.snapshot().unreadIds, [])
  tracker.view('phone', null)
  complete()
  tracker.disconnect('phone')
  assert.deepEqual(tracker.snapshot().unreadIds, ['s'])
})

test('initial list seeds running only, replaying all events and view changes in arrival order', () => {
  const tracker = new CompletionTracker({ initializing: true })
  tracker.status('seeded-running', false)
  tracker.status('new', true)
  tracker.status('new', false)
  tracker.view('viewer', 'new')
  tracker.disconnect('viewer')
  tracker.status('new', true)
  tracker.status('new', false)
  tracker.remove('removed')
  tracker.seed([
    { sessionId: 'seeded-running', running: true },
    { sessionId: 'history', running: false },
    { sessionId: 'removed', running: true },
  ])
  assert.deepEqual(tracker.snapshot().unreadIds, ['seeded-running', 'new'])
  tracker.seed([{ sessionId: 'removed', running: true }])
  tracker.status('removed', false)
  assert.deepEqual(tracker.snapshot().unreadIds, ['seeded-running', 'new'])
})

test('a first live stop before baseline survives a list that already reports idle', () => {
  const tracker = new CompletionTracker({ initializing: true })
  tracker.status('finished-during-list', false)
  tracker.status('finished-during-list', false)
  tracker.remove('removed-before-stop')
  tracker.status('removed-before-stop', false)
  tracker.added('known-idle', false)
  tracker.status('known-idle', false)
  tracker.view('desktop', 'viewed')
  tracker.status('viewed', false)
  tracker.seed([
    { sessionId: 'finished-during-list', running: false },
    { sessionId: 'historical-idle', running: false },
    { sessionId: 'removed-before-stop', running: true },
    { sessionId: 'known-idle', running: true },
    { sessionId: 'viewed', running: false },
  ])
  assert.deepEqual(tracker.snapshot().unreadIds, ['finished-during-list'])
  assert.equal(tracker.snapshot().revision, 1)
  tracker.status('unknown-after-baseline', false)
  tracker.status('finished-during-list', false)
  assert.equal(tracker.snapshot().revision, 1)
})
