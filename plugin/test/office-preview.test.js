import { test } from 'node:test'
import assert from 'node:assert/strict'
import { setImmediate } from 'node:timers/promises'
import { createOfficePreviewOps, OFFICE_CHUNK_BYTES } from '../src/office-preview.js'

const signal = () => new AbortController().signal
const client = () => ({ subs: new Map() })
function fixture(options = {}, overrides = {}) {
  const data = new Uint8Array([37, 80, 68, 70, 45, 49])
  const stat = { absolutePath: '/project/report.docx', version: 'v1' }
  const rendered = { ...stat, data, bytes: data.byteLength, offset: 0, eof: true, missingFonts: ['Aptos'], generation: 'g1' }
  const calls = []
  const ctx = {
    sessions: { get: id => id === 's1' ? { header: { cwd: '/project' } } : undefined },
    sandboxPolicy: { workspaceRoot: '/fallback' },
    get: () => undefined,
    workspaceFiles: { stat: async () => ({ ...stat }) },
    officeToPdf: { render: async (...args) => { calls.push(args); return rendered } },
    ...overrides,
  }
  const service = createOfficePreviewOps(ctx, options)
  const peer = client()
  const render = (extra = {}, owner = peer) => service.ops['office.render']({ sessionId: 's1', path: 'report.docx', subId: 'o-test', ...extra }, signal(), owner)
  const read = (meta, extra = {}, owner = peer) => service.ops['office.read']({
    sessionId: 's1', handle: meta.handle, version: meta.version, generation: meta.generation,
    offset: 0, length: OFFICE_CHUNK_BYTES, ...extra,
  }, signal(), owner)
  return { service, peer, ctx, data, stat, rendered, calls, render, read }
}

test('Office calls the real service contract with a header-derived WorkspaceFileScope', async t => {
  const f = fixture()
  t.after(() => f.service.dispose())
  for (const extension of ['doc', 'docx', 'ppt', 'pptx']) {
    const meta = await f.render({ path: `report.${extension}`, subId: `o-${extension}` })
    assert.equal(meta.data, undefined)
    assert.deepEqual(f.calls.at(-1).slice(0, 3), [{ sessionId: 's1', workspaceRoot: '/project' }, `report.${extension}`, 'foreground'])
    const page = await f.read(meta)
    assert.deepEqual(Buffer.from(page.data, 'base64'), Buffer.from(f.data))
    assert.equal(page.eof, true)
    assert.deepEqual(meta.missingFonts, ['Aptos'])
  }
})

test('scope lookup uses persisted headers and never fabricates a missing session from global cwd', async t => {
  const f = fixture({}, { sessions: { get: () => undefined }, get: key => key === 'sessionPersistence' ? {
    stat: async id => id === 'stored' ? { header: { cwd: '/stored-workspace' } } : id === 'default' ? { header: {} } : undefined,
  } : undefined })
  t.after(() => f.service.dispose())
  await assert.rejects(f.render({ sessionId: 'missing' }), { code: 'session/not-found' })
  assert.equal(f.calls.length, 0)
  await f.render({ sessionId: 'stored' })
  assert.deepEqual(f.calls[0][0], { sessionId: 'stored', workspaceRoot: '/stored-workspace' })
  await f.render({ sessionId: 'default', subId: 'o-default' })
  assert.deepEqual(f.calls[1][0], { sessionId: 'default', workspaceRoot: '/fallback' })
})

test('connection and session ownership protect reads, release, and handle collision', async t => {
  const f = fixture()
  t.after(() => f.service.dispose())
  const meta = await f.render()
  const other = client()
  await assert.rejects(f.read(meta, {}, other), { code: 'office/handle-unavailable' })
  await assert.rejects(f.read(meta, { sessionId: 's2' }), { code: 'office/handle-unavailable' })
  assert.throws(() => f.service.ops['office.release']({ handle: meta.handle, sessionId: 's2' }, signal(), f.peer), { code: 'office/handle-unavailable' })
  assert.throws(() => f.service.ops['office.release']({ handle: meta.handle, sessionId: 's1' }, signal(), other), { code: 'office/handle-unavailable' })
  await assert.rejects(f.render({}, other), { code: 'gateway/bad-request' })
  assert.equal((await f.read(meta)).eof, true)
})

test('changed source version or session root invalidates the entire lease', async t => {
  for (const change of ['version', 'root', 'generation']) {
    const f = fixture()
    t.after(() => f.service.dispose())
    const meta = await f.render()
    if (change === 'version') f.stat.version = 'v2'
    if (change === 'root') f.ctx.sessions.get = () => ({ header: { cwd: '/other' } })
    await assert.rejects(f.read(meta, change === 'generation' ? { generation: 'g2' } : {}), { code: 'document-render/failed', details: { reason: 'source-changed' } })
    assert.equal(f.peer.subs.size, 0)
    await assert.rejects(f.read(meta), { code: 'office/handle-unavailable' })
  }
})

test('PDF larger than WS cap is paged losslessly with a bounded wire payload', async t => {
  const f = fixture()
  t.after(() => f.service.dispose())
  const data = Buffer.alloc(33 * 1024 * 1024 + 7, 0xa7)
  f.rendered.data = data
  f.rendered.bytes = data.length
  const meta = await f.render()
  assert.ok(JSON.stringify(meta).length < 1024)
  let offset = 0
  while (offset < data.length) {
    const page = await f.read(meta, { offset })
    assert.ok(JSON.stringify(page).length < 180 * 1024)
    const decoded = Buffer.from(page.data, 'base64')
    assert.deepEqual(decoded, data.subarray(offset, offset + decoded.length))
    assert.equal(page.offset, offset)
    offset += decoded.length
    assert.equal(page.eof, offset === data.length)
  }
  assert.equal(offset, data.length)
  await assert.rejects(f.read(meta, { length: OFFICE_CHUNK_BYTES + 1 }), { code: 'gateway/bad-request' })
  await assert.rejects(f.read(meta, { offset: data.length }), { code: 'gateway/bad-request' })
})

test('unfollow and disconnect abort pending render and discard late output', async t => {
  const f = fixture()
  t.after(() => f.service.dispose())
  let finish
  let upstream
  f.ctx.officeToPdf.render = (_scope, _path, _priority, signal) => {
    upstream = signal
    return new Promise(resolve => { finish = resolve })
  }
  const pending = f.render()
  await setImmediate()
  f.peer.subs.get('o-test').abort()
  assert.equal(upstream.aborted, true)
  finish(f.rendered)
  await assert.rejects(pending, { code: 'gateway/cancelled' })
  assert.equal(f.peer.subs.size, 0)
  await assert.rejects(f.read({ handle: 'o-test', version: 'v1', generation: 'g1' }), { code: 'office/handle-unavailable' })
})

test('release during persisted-header lookup prevents conversion', async t => {
  let finish
  const f = fixture({}, { sessions: { get: () => undefined }, get: () => ({ stat: () => new Promise(resolve => { finish = resolve }) }) })
  t.after(() => f.service.dispose())
  const pending = f.render()
  assert.throws(() => f.service.ops['office.release']({ handle: 'o-test', sessionId: 's2' }, signal(), f.peer), { code: 'office/handle-unavailable' })
  f.service.ops['office.release']({ handle: 'o-test', sessionId: 's1' }, signal(), f.peer)
  finish({ header: { cwd: '/stored' } })
  await assert.rejects(pending, { code: 'gateway/cancelled' })
  assert.equal(f.calls.length, 0)
})

test('idle leases expire, reads renew them, and service disposal releases every subscription', async t => {
  t.mock.timers.enable({ apis: ['setTimeout'] })
  const f = fixture({ leaseMs: 1000 })
  t.after(() => f.service.dispose())
  const meta = await f.render()
  t.mock.timers.tick(900)
  await f.read(meta)
  t.mock.timers.tick(900)
  assert.equal(f.peer.subs.size, 1)
  t.mock.timers.tick(101)
  assert.equal(f.peer.subs.size, 0)
  await assert.rejects(f.read(meta), { code: 'office/handle-unavailable' })
  await f.render()
  f.service.dispose()
  assert.equal(f.peer.subs.size, 0)
  await assert.rejects(f.render({ subId: 'o-after' }), { details: { reason: 'unavailable' } })
})

test('pending output reservations and completed handles are bounded, then reusable after release', async t => {
  const f = fixture({ maxOutputBytes: 8, maxRetainedBytes: 8 })
  t.after(() => f.service.dispose())
  let finish
  f.ctx.officeToPdf.render = () => new Promise(resolve => { finish = resolve })
  const pending = f.render()
  await setImmediate()
  await assert.rejects(f.render({ subId: 'o-overflow' }), { details: { reason: 'busy' } })
  finish(f.rendered)
  const meta = await pending
  await assert.rejects(f.render({ subId: 'o-overflow' }), { details: { reason: 'busy' } })
  f.service.ops['office.release']({ handle: meta.handle, sessionId: 's1' }, signal(), f.peer)
  f.ctx.officeToPdf.render = async () => f.rendered
  await f.render()
})

test('invalid and oversized output, authorization failures, and source changes never leave a handle', async t => {
  for (const kind of ['incomplete', 'oversized', 'denied', 'changed']) {
    const f = fixture({ maxOutputBytes: 8 })
    t.after(() => f.service.dispose())
    if (kind === 'incomplete') f.rendered.eof = false
    if (kind === 'oversized') { f.rendered.data = new Uint8Array(9); f.rendered.bytes = 9 }
    if (kind === 'denied') f.ctx.officeToPdf.render = async () => { throw Object.assign(new Error('denied'), { code: 'workspace-file/not-readable' }) }
    if (kind === 'changed') f.stat.version = 'v2'
    await assert.rejects(f.render())
    assert.equal(f.peer.subs.size, 0)
  }
})

test('render deadline cancels upstream and reports the official timeout reason', async t => {
  t.mock.timers.enable({ apis: ['setTimeout'] })
  const f = fixture({ renderTimeoutMs: 1000 })
  t.after(() => f.service.dispose())
  let upstream
  f.ctx.officeToPdf.render = (_scope, _path, _priority, signal) => {
    upstream = signal
    return new Promise((_resolve, reject) => signal.addEventListener('abort', () => reject(signal.reason), { once: true }))
  }
  const pending = f.render()
  await setImmediate()
  t.mock.timers.tick(1001)
  await assert.rejects(pending, { code: 'document-render/failed', details: { reason: 'timeout' } })
  assert.equal(upstream.aborted, true)
  assert.equal(f.peer.subs.size, 0)
})

test('handle-count admission remains bounded even for tiny PDFs', async t => {
  const f = fixture({ maxHandles: 1 })
  t.after(() => f.service.dispose())
  const first = await f.render()
  await assert.rejects(f.render({ subId: 'o-second' }), { details: { reason: 'busy' } })
  f.service.ops['office.release']({ handle: first.handle, sessionId: 's1' }, signal(), f.peer)
  await f.render({ subId: 'o-second' })
})
