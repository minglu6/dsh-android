import { test } from 'node:test'
import assert from 'node:assert/strict'
import { setImmediate } from 'node:timers/promises'
import { filesOps } from '../src/extra-ops.js'

const sessions = { get: () => ({ header: { cwd: '/workspace' } }) }
const client = () => ({ subs: new Map(), pushes: [], push(frame) { this.pushes.push(frame) } })

test('files.follow uses official directory watch and releases it on unfollow', async () => {
  const peer = client()
  let released = false
  let observed
  let change
  const ops = filesOps({
    list: async (scope, path) => {
      assert.deepEqual(scope, { sessionId: 's1', workspaceRoot: '/workspace' })
      assert.equal(path, 'alias')
      return { path: 'canonical', entries: [] }
    },
    changes: async function* (scope, path, signal) {
      observed = { scope, path }
      try {
        yield { kind: 'ready' }
        await new Promise(resolve => { change = resolve })
        yield { kind: 'change', change: { absolutePath: '/workspace/canonical', version: '2' } }
        await new Promise(resolve => signal.addEventListener('abort', resolve, { once: true }))
      } finally { released = true }
    },
  }, sessions)
  assert.deepEqual(ops['files.follow']({ sessionId: 's1', path: 'alias', subId: 'f-test' }, undefined, peer), { subId: 'f-test' })
  await setImmediate()
  assert.equal(observed.path, 'canonical')
  assert.equal(peer.pushes[0].frame.kind, 'ready')
  assert.equal(peer.pushes[0].frame.path, observed.path)
  change()
  await setImmediate()
  assert.equal(peer.pushes[1].frame.kind, 'change')
  assert.equal(peer.pushes[1].frame.path, observed.path)
  peer.subs.get('f-test').abort()
  await setImmediate()
  assert.equal(released, true)
  assert.equal(peer.subs.size, 0)
  assert.deepEqual(peer.pushes.map(frame => frame.ev), ['files', 'files'])
})

test('files.follow cancelled during opening never starts a watcher or pushes late frames', async () => {
  const peer = client()
  let finishList
  let watched = false
  const ops = filesOps({
    list: () => new Promise(resolve => { finishList = resolve }),
    changes: async function* () { watched = true; yield { kind: 'ready' } },
  }, sessions)
  ops['files.follow']({ sessionId: 's1', path: '.', subId: 'f-opening' }, undefined, peer)
  await setImmediate()
  peer.subs.get('f-opening').abort()
  finishList({ path: '', entries: [] })
  await setImmediate()
  assert.equal(watched, false)
  assert.equal(peer.subs.size, 0)
  assert.deepEqual(peer.pushes, [])
})

test('files.follow rejects external paths and preserves Host directory containment failures', async () => {
  const peer = client()
  let watched = false
  const ops = filesOps({
    list: async () => { throw Object.assign(new Error('external symlink'), { code: 'workspace-file/outside-workspace' }) },
    changes: async function* () { watched = true; yield { kind: 'ready' } },
  }, sessions)
  for (const path of ['/etc', '../outside', 'a/../outside', 'C:/outside', 'a\\outside']) {
    assert.throws(() => ops['files.follow']({ sessionId: 's1', path, subId: 'f-invalid' }, undefined, peer),
      { code: 'workspace-file/outside-workspace' })
  }
  assert.equal(peer.subs.size, 0)
  ops['files.follow']({ sessionId: 's1', path: 'link', subId: 'f-link' }, undefined, peer)
  await setImmediate()
  assert.equal(watched, false)
  assert.equal(peer.subs.size, 0)
  assert.equal(peer.pushes[0].ev, 'files.error')
  assert.equal(peer.pushes[0].error.code, 'workspace-file/outside-workspace')
})

test('files.follow capability is absent when the official watch is unavailable', () => {
  assert.equal('files.follow' in filesOps({ list() {} }, sessions), false)
})
