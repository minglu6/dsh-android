import assert from 'node:assert/strict'
import { test } from 'node:test'
import {
  bindCompletionUnreadSource,
  createCompletionSync,
  installCompletionSync,
  overlayCompletionUnread,
  overlaySessionStatusCompletionUnread,
} from '../src/client/completion-sync.js'

const state = (revision, unreadIds = [], epoch = 'host-a') => ({ epoch, revision, unreadIds })
const tick = () => new Promise(resolve => setImmediate(resolve))

function bench() {
  let events
  let source
  let view = null
  let released = 0
  let closed = 0
  const requests = []
  const errors = []
  const sync = createCompletionSync({
    connect(callbacks) { events = callbacks; return { close() { closed++ } } },
    postView(clientId, sessionId, signal) {
      return new Promise((resolve, reject) => requests.push({ clientId, sessionId, signal, resolve, reject }))
    },
    installSource(value) { source = value; return () => { released++ } },
    visibleSession: () => view,
    reportError: error => errors.push(error),
  })
  return {
    sync, requests, errors,
    get source() { return source },
    get closed() { return closed },
    get released() { return released },
    message(value, clientId = 'desktop-a') { events.message({ clientId, ...value }) },
    disconnect() { events.disconnect() },
    view(value) { view = value; sync.updateView() },
  }
}

test('installs shared state before publishing a view and follows remote acknowledgements', async () => {
  const b = bench()
  b.view('one')
  assert.equal(b.requests.length, 0)
  assert.equal(b.source, undefined)
  b.message(state(1, ['one', 'two']))
  assert.deepEqual([...b.source.getSnapshot()], ['one', 'two'])
  assert.equal(b.requests[0].sessionId, 'one')
  let changes = 0
  const off = b.source.subscribe(() => changes++)
  b.message(state(3, []))
  b.requests[0].resolve(state(2, ['two']))
  await tick()
  assert.deepEqual([...b.source.getSnapshot()], [])
  b.message(state(1, ['one']))
  assert.deepEqual([...b.source.getSnapshot()], [])
  assert.equal(changes, 1)
  off()
  b.sync.dispose()
  b.sync.dispose()
  assert.equal(b.released, 1)
  assert.equal(b.closed, 1)
})

test('serializes view transitions including a hidden interval without rolling snapshots back', async () => {
  const b = bench()
  b.message(state(0))
  b.view('one')
  b.view(null)
  b.view('two')
  assert.deepEqual(b.requests.map(r => r.sessionId), [null])
  b.requests[0].resolve(state(0))
  await tick()
  assert.deepEqual(b.requests.map(r => r.sessionId), [null, 'one'])
  b.requests[1].resolve(state(1))
  await tick()
  assert.deepEqual(b.requests.map(r => r.sessionId), [null, 'one', null])
  b.requests[2].resolve(state(1))
  await tick()
  assert.deepEqual(b.requests.map(r => r.sessionId), [null, 'one', null, 'two'])
  b.sync.dispose()
})

test('reconnects with a fresh presence identity and ignores replies from the disconnected generation', async () => {
  const b = bench()
  b.view('one')
  b.message(state(4, ['one']))
  const stale = b.requests[0]
  b.disconnect()
  assert.equal(stale.signal.aborted, true)
  b.view('two')
  assert.equal(b.requests.length, 1)
  b.message(state(4, ['one']), 'desktop-b')
  assert.equal(b.requests[1].clientId, 'desktop-b')
  assert.equal(b.requests[1].sessionId, 'two')
  b.message(state(5, []), 'desktop-b')
  stale.resolve(state(99, ['one']))
  await tick()
  assert.deepEqual([...b.source.getSnapshot()], [])
  b.message(state(100, ['one']), 'desktop-a')
  assert.deepEqual([...b.source.getSnapshot()], [])
  b.disconnect()
  b.message(state(0, ['new'], 'host-b'), 'desktop-c')
  b.requests[1].resolve(state(100, ['old']))
  await tick()
  assert.deepEqual([...b.source.getSnapshot()], ['new'])
  b.sync.dispose()
})

test('does not change epochs from POST responses and releases presence on failed acknowledgement', async () => {
  const b = bench()
  b.message(state(1, ['one']))
  b.requests[0].resolve(state(20, ['wrong'], 'other-host'))
  await tick()
  assert.deepEqual([...b.source.getSnapshot()], ['one'])
  b.view('one')
  b.requests[1].reject(new Error('offline'))
  await tick()
  assert.equal(b.closed, 1)
  assert.equal(b.errors.length, 1)
  b.view('two')
  assert.equal(b.requests.length, 2)
  b.message(state(2, []), 'desktop-recovered')
  assert.equal(b.requests[2].clientId, 'desktop-recovered')
  assert.equal(b.requests[2].sessionId, 'two')
  assert.deepEqual([...b.source.getSnapshot()], [])
  b.sync.dispose()
})

test('rejects malformed completion snapshots before installing a shared source', () => {
  const b = bench()
  b.message({ epoch: 'host', revision: -1, unreadIds: ['one'] })
  assert.equal(b.source, undefined)
  assert.equal(b.errors.length, 1)
  assert.equal(b.closed, 1)
  b.sync.dispose()
})

test('page suspension releases presence but retains shared state until a new stream baseline', () => {
  const b = bench()
  b.view('one')
  b.message(state(1, ['two']))
  const source = b.source
  b.sync.suspend()
  b.sync.suspend()
  assert.equal(b.closed, 1)
  assert.equal(b.released, 0)
  assert.equal(b.requests[0].signal.aborted, true)
  b.view('two')
  assert.equal(b.requests.length, 1)
  b.sync.resume()
  b.sync.resume()
  b.message(state(2, ['two']), 'desktop-resumed')
  assert.equal(b.source, source)
  assert.equal(b.requests[1].clientId, 'desktop-resumed')
  assert.equal(b.requests[1].sessionId, 'two')
  b.sync.dispose()
  assert.equal(b.released, 1)
})

function observable(initial) {
  let value = initial
  const listeners = new Set()
  return {
    getSnapshot: () => value,
    subscribe(listener) { listeners.add(listener); return () => listeners.delete(listener) },
    set(next) { value = next; for (const listener of [...listeners]) listener() },
    get count() { return listeners.size },
  }
}

test('overlays Host unread onto official sessionStatus without a dedicated hook', () => {
  const official = new Map([
    ['one', { running: true, pendingInteraction: undefined, completionUnread: false }],
    ['two', { running: false, pendingInteraction: { kind: 'approval' }, completionUnread: true }],
  ])
  const overlaid = overlayCompletionUnread(official, new Set(['two', 'three']))
  assert.deepEqual(overlaid.get('one'), { running: true, pendingInteraction: undefined, completionUnread: false })
  assert.deepEqual(overlaid.get('two'), { running: false, pendingInteraction: { kind: 'approval' }, completionUnread: true })
  assert.deepEqual(overlaid.get('three'), { running: undefined, pendingInteraction: undefined, completionUnread: true })
  assert.equal(overlayCompletionUnread(official, new Set()).get('two').completionUnread, false)
})

test('sessionStatus overlay is Host source of truth and notifies already-subscribed listeners', () => {
  const official = new Map([
    ['one', { running: false, pendingInteraction: undefined, completionUnread: true }],
  ])
  const statusListeners = new Set()
  const sessionStatus = {
    getSnapshot: () => official,
    subscribe(listener) {
      statusListeners.add(listener)
      return () => statusListeners.delete(listener)
    },
  }
  let unread = new Set()
  const hostListeners = new Set()
  const source = {
    getSnapshot: () => unread,
    subscribe(listener) { hostListeners.add(listener); return () => hostListeners.delete(listener) },
  }
  let seen = 0
  sessionStatus.subscribe(() => { seen++ })
  const uninstall = overlaySessionStatusCompletionUnread(sessionStatus, source, statusListeners)
  assert.equal(sessionStatus.getSnapshot().get('one').completionUnread, false)
  assert.equal(seen, 1)
  unread = new Set(['one'])
  for (const listener of hostListeners) listener()
  assert.equal(sessionStatus.getSnapshot().get('one').completionUnread, true)
  assert.equal(sessionStatus.getSnapshot().get('one').running, false)
  assert.equal(seen, 2)
  uninstall()
  assert.equal(sessionStatus.getSnapshot().get('one').completionUnread, true)
  unread = new Set()
  for (const listener of hostListeners) listener()
  assert.equal(seen, 2)
})

test('bindCompletionUnreadSource uses the official hook when present and otherwise overlays sessionStatus', () => {
  const hooked = []
  const off = bindCompletionUnreadSource({
    setCompletionUnreadSource(source) { hooked.push(source); return () => hooked.pop() },
  }, { getSnapshot: () => new Set(['hook']) })
  assert.equal(hooked.length, 1)
  assert.deepEqual([...hooked[0].getSnapshot()], ['hook'])
  off()
  assert.equal(hooked.length, 0)

  const statusListeners = new Set()
  const sessionStatus = {
    getSnapshot: () => new Map(),
    subscribe(listener) { statusListeners.add(listener); return () => statusListeners.delete(listener) },
  }
  const restore = bindCompletionUnreadSource(
    { sessionStatus, statusListeners },
    { getSnapshot: () => new Set(['overlaid']), subscribe: () => () => {} },
  )
  assert.equal(sessionStatus.getSnapshot().get('overlaid').completionUnread, true)
  restore()
  assert.equal(sessionStatus.getSnapshot().has('overlaid'), false)
})

test('bindCompletionUnreadSource throws rather than pretending to sync', () => {
  assert.throws(
    () => bindCompletionUnreadSource({}, { getSnapshot: () => new Set() }),
    /sessionStatus is not an overlayable HostObservable/,
  )
  assert.throws(
    () => bindCompletionUnreadSource({
      sessionStatus: { getSnapshot: () => new Map(), subscribe: () => () => {} },
    }, { getSnapshot: () => new Set() }),
    /status listeners are not reachable/,
  )
})

test('installCompletionSync overlays official sessionStatus when the hook is absent', async () => {
  const win = new EventTarget()
  const doc = new EventTarget()
  doc.visibilityState = 'visible'
  doc.hasFocus = () => true
  const current = observable({ key: 'one' })
  const list = observable({ phase: 'ready' })
  const retain = observable({ retainedBy: { mainView: 1 } })
  const session = observable({ openState: 'open', removed: false })
  const binding = { sessionId: 'one', session }
  const panelInfo = observable({ activePanelId: null })
  const settings = observable({ open: false })
  const registry = observable()
  const official = new Map([
    ['one', { running: false, pendingInteraction: undefined, completionUnread: false }],
    ['two', { running: false, pendingInteraction: undefined, completionUnread: true }],
  ])
  const statusListeners = new Set()
  const sessionStatus = {
    getSnapshot: () => official,
    subscribe(listener) { statusListeners.add(listener); return () => statusListeners.delete(listener) },
  }
  let events
  class Events {
    constructor(url) { assert.equal(url, '/pocket/completion/events'); events = this }
    close() { this.closed = true }
  }
  const dispose = installCompletionSync({
    window: win, document: doc, EventSource: Events,
    uiSession: { adapter: { current }, sessionStatus, statusListeners },
    sessions: { list, binding: id => id === 'one' ? binding : undefined, retainInfo: () => retain },
    layout: { panelInfo },
    slots: {
      entries: () => [{ children: { 'settings.launcher': {} }, store: { create: () => settings } }],
      subscribe: (_name, listener) => registry.subscribe(listener),
    },
    async fetch() { return { ok: true, json: async () => state(1, []) } },
  })
  let redraws = 0
  sessionStatus.subscribe(() => { redraws++ })
  events.onmessage({ data: JSON.stringify({ clientId: 'desktop', ...state(1, ['two']) }) })
  await tick()
  assert.equal(sessionStatus.getSnapshot().get('two').completionUnread, true)
  assert.equal(sessionStatus.getSnapshot().get('one').completionUnread, false)
  assert.ok(redraws >= 1)
  events.onmessage({ data: JSON.stringify({ clientId: 'desktop', ...state(2, []) }) })
  await tick()
  assert.equal(sessionStatus.getSnapshot().get('two').completionUnread, false)
  dispose()
})

test('only acknowledges loaded, focused, visible main content and tears down every observer', async () => {
  const win = new EventTarget()
  const doc = new EventTarget()
  let focused = true
  doc.visibilityState = 'visible'
  doc.hasFocus = () => focused
  const current = observable({ key: 'one' })
  const list = observable({ phase: 'ready' })
  const retain = observable({ retainedBy: { mainView: 1 } })
  const session = observable({ openState: 'loading', removed: false })
  const binding = { sessionId: 'one', session }
  let events
  let installed = false
  const requests = []
  const panelInfo = observable({ activePanelId: null })
  const settings = observable({ open: false })
  const registry = observable()
  class Events {
    constructor(url) { assert.equal(url, '/pocket/completion/events'); events = this }
    close() { this.closed = true }
  }
  const dispose = installCompletionSync({
    window: win, document: doc, EventSource: Events,
    uiSession: {
      adapter: { current },
      setCompletionUnreadSource() { installed = true; return () => { installed = false } },
    },
    sessions: { list, binding: id => id === 'one' ? binding : undefined, retainInfo: () => retain },
    layout: { panelInfo },
    slots: {
      entries: () => [{ children: { 'settings.launcher': {} }, store: { create: () => settings } }],
      subscribe: (_name, listener) => registry.subscribe(listener),
    },
    async fetch(url, options) {
      assert.equal(url, '/pocket/completion/view')
      assert.equal(installed, true)
      requests.push(JSON.parse(options.body))
      return { ok: true, json: async () => state(1) }
    },
  })
  events.onmessage({ data: JSON.stringify({ clientId: 'desktop', ...state(1) }) })
  await tick()
  assert.equal(requests.at(-1).sessionId, null)
  session.set({ openState: 'open', removed: false })
  await tick()
  assert.equal(requests.at(-1).sessionId, 'one')
  panelInfo.set({ activePanelId: 'schedule' })
  await tick()
  assert.equal(requests.at(-1).sessionId, null)
  panelInfo.set({ activePanelId: null })
  await tick()
  assert.equal(requests.at(-1).sessionId, 'one')
  settings.set({ open: true })
  await tick()
  assert.equal(requests.at(-1).sessionId, null)
  settings.set({ open: false })
  await tick()
  assert.equal(requests.at(-1).sessionId, 'one')
  focused = false
  win.dispatchEvent(new Event('blur'))
  await tick()
  assert.equal(requests.at(-1).sessionId, null)
  focused = true
  doc.visibilityState = 'hidden'
  win.dispatchEvent(new Event('focus'))
  await tick()
  assert.equal(requests.at(-1).sessionId, null)
  doc.visibilityState = 'visible'
  doc.dispatchEvent(new Event('visibilitychange'))
  await tick()
  assert.equal(requests.at(-1).sessionId, 'one')
  retain.set({ retainedBy: { follow: 1 } })
  await tick()
  assert.equal(requests.at(-1).sessionId, null)
  retain.set({ retainedBy: { mainView: 1 } })
  await tick()
  session.set({ openState: 'error', removed: false })
  await tick()
  assert.equal(requests.at(-1).sessionId, null)
  session.set({ openState: 'open', removed: false })
  await tick()
  list.set({ phase: 'pending' })
  await tick()
  assert.equal(requests.at(-1).sessionId, null)
  list.set({ phase: 'ready' })
  await tick()
  current.set({ key: undefined })
  await tick()
  assert.equal(requests.at(-1).sessionId, null)
  assert.equal(session.count, 0)
  assert.equal(retain.count, 0)
  dispose()
  assert.equal(events.closed, true)
  assert.equal(installed, false)
  assert.equal(current.count, 0)
  assert.equal(list.count, 0)
  assert.equal(settings.count, 0)
  assert.equal(registry.count, 0)
  assert.equal(panelInfo.count, 0)
  assert.equal(requests.some(r => r.clientId !== 'desktop'), false)
})
