/** Host completion snapshots and view presence, independent of rendering and transport mocks. */
export function createCompletionSync({ connect, postView, installSource, visibleSession, reportError = console.error }) {
  let snapshot
  let unread = new Set()
  const listeners = new Set()
  const source = {
    getSnapshot: () => unread,
    subscribe: listener => { listeners.add(listener); return () => listeners.delete(listener) },
  }
  let uninstall
  let clientId = null
  let generation = 0
  let disposed = false
  let suspended = false
  let stream
  let carrier = 0
  let desired
  let queue = []
  let pending
  const accept = (value, allowEpoch) => {
    if (!value || typeof value.epoch !== 'string' || !value.epoch
      || !Number.isSafeInteger(value.revision) || value.revision < 0
      || !Array.isArray(value.unreadIds) || value.unreadIds.some(id => typeof id !== 'string' || !id)) {
      throw new Error('Invalid completion snapshot')
    }
    if (snapshot?.epoch !== value.epoch && snapshot && !allowEpoch) return false
    if (snapshot?.epoch === value.epoch && snapshot.revision > value.revision) return false
    if (snapshot?.epoch !== value.epoch || snapshot?.revision !== value.revision) {
      snapshot = value
      unread = new Set(value.unreadIds)
      for (const listener of listeners) listener()
    }
    return true
  }
  const disconnect = () => {
    generation++
    clientId = null
    desired = undefined
    queue = []
    pending?.controller.abort()
    pending = undefined
  }
  const drain = async () => {
    if (disposed || !clientId || pending || !queue.length) return
    const request = { generation, controller: new AbortController() }
    pending = request
    const id = clientId
    const sessionId = queue.shift()
    let received = false
    try {
      const value = await postView(id, sessionId, request.controller.signal)
      received = true
      if (!disposed && generation === request.generation && clientId === id) accept(value, false)
    } catch (error) {
      if (!disposed && generation === request.generation) {
        // An acknowledgement may have committed before its response was lost.
        // Release its presence and obtain a new stream baseline before retrying.
        disconnect()
        carrier++
        stream.close()
        reportError(error)
        if (!received) openStream()
      }
    } finally {
      if (pending === request) {
        pending = undefined
        void drain()
      }
    }
  }
  const updateView = () => {
    if (disposed || !clientId) return
    const next = visibleSession()
    if (next === desired) return
    desired = next
    queue.push(next)
    void drain()
  }
  const openStream = () => {
    const token = ++carrier
    stream = connect({
      message(value) {
        if (disposed || suspended || token !== carrier) return
        try {
          if (typeof value?.clientId !== 'string' || !value.clientId) throw new Error('Invalid completion client identity')
          if (clientId !== null && clientId !== value.clientId) return
          if (!accept(value, clientId === null)) return
          clientId = value.clientId
          if (!uninstall) uninstall = installSource(source)
          updateView()
        } catch (error) {
          disconnect()
          carrier++
          stream.close()
          reportError(error)
        }
      },
      disconnect() {
        if (!disposed && !suspended && token === carrier) disconnect()
      },
    })
  }
  openStream()
  return {
    updateView,
    suspend() {
      if (disposed || suspended) return
      suspended = true
      disconnect()
      carrier++
      stream.close()
    },
    resume() {
      if (disposed || !suspended) return
      suspended = false
      openStream()
    },
    dispose() {
      if (disposed) return
      disposed = true
      disconnect()
      stream.close()
      uninstall?.()
      listeners.clear()
    },
  }
}

/** Observe only already-retained, loaded main-view content; never open or select a Session. */
export function installCompletionSync({ uiSession, sessions, layout, slots, window: win = window, document: doc = document, fetch: request = fetch, EventSource: Events = EventSource, reportError = console.error }) {
  let binding
  let settings
  let offSession = () => {}
  let offRetain = () => {}
  let offSettings = () => {}
  const visibleSession = () => {
    if (doc.visibilityState !== 'visible' || !doc.hasFocus() || sessions.list.getSnapshot().phase !== 'ready') return null
    if (layout.panelInfo.getSnapshot().activePanelId !== null || settings?.getSnapshot().open) return null
    if (!binding || (sessions.retainInfo(binding.sessionId).getSnapshot().retainedBy.mainView ?? 0) <= 0) return null
    const state = binding.session.getSnapshot()
    return state.openState === 'open' && !state.removed ? binding.sessionId : null
  }
  const sync = createCompletionSync({
    visibleSession,
    reportError,
    installSource: source => uiSession.setCompletionUnreadSource(source),
    connect({ message, disconnect }) {
      const events = new Events('/pocket/completion/events')
      events.onmessage = event => {
        try { message(JSON.parse(event.data)) } catch (error) {
          disconnect()
          events.close()
          reportError(error)
        }
      }
      events.onerror = disconnect
      return { close: () => events.close() }
    },
    async postView(clientId, sessionId, signal) {
      const response = await request('/pocket/completion/view', {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify({ clientId, sessionId }),
        signal,
      })
      if (!response.ok) throw new Error(`Completion view failed: HTTP ${response.status}`)
      return response.json()
    },
  })
  const watchMain = () => {
    const id = uiSession.adapter.current.getSnapshot().key
    const next = id === undefined ? undefined : sessions.binding(id)
    if (next !== binding) {
      offSession()
      offRetain()
      binding = next
      offSession = binding ? binding.session.subscribe(sync.updateView) : () => {}
      offRetain = binding ? sessions.retainInfo(binding.sessionId).subscribe(sync.updateView) : () => {}
    }
    sync.updateView()
  }
  const watchSettings = () => {
    const owner = slots.entries('sidebar.settings').find(entry => entry.children?.['settings.launcher'])
    // SettingsRoot publishes this root-scoped instance for its launcher and shortcuts.
    const next = owner?.store?.create()
    if (next !== settings) {
      offSettings()
      settings = next
      offSettings = settings ? settings.subscribe(sync.updateView) : () => {}
    }
    sync.updateView()
  }
  const offMain = uiSession.adapter.current.subscribe(watchMain)
  const offList = sessions.list.subscribe(watchMain)
  const offSettingsOwner = slots.subscribe('sidebar.settings', watchSettings)
  const offPanel = layout.panelInfo.subscribe(sync.updateView)
  doc.addEventListener('visibilitychange', sync.updateView)
  win.addEventListener('focus', sync.updateView)
  win.addEventListener('blur', sync.updateView)
  win.addEventListener('pagehide', sync.suspend)
  win.addEventListener('pageshow', sync.resume)
  watchSettings()
  watchMain()
  return () => {
    offMain()
    offList()
    offSettingsOwner()
    offSettings()
    offPanel()
    offSession()
    offRetain()
    doc.removeEventListener('visibilitychange', sync.updateView)
    win.removeEventListener('focus', sync.updateView)
    win.removeEventListener('blur', sync.updateView)
    win.removeEventListener('pagehide', sync.suspend)
    win.removeEventListener('pageshow', sync.resume)
    sync.dispose()
  }
}