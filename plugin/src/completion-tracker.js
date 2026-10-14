import { randomUUID } from 'node:crypto'

/** Host-owned unread state. A follow subscription is deliberately not a view. */
export class CompletionTracker {
  constructor({ initializing = false } = {}) {
    this.epoch = randomUUID()
    this.revision = 0
    this.running = new Set()
    this.unread = new Set()
    this.views = new Map()
    this.listeners = new Set()
    this.pending = initializing ? [] : null
  }

  snapshot() {
    return { epoch: this.epoch, revision: this.revision, unreadIds: [...this.unread] }
  }

  subscribe(listener) {
    this.listeners.add(listener)
    return () => this.listeners.delete(listener)
  }

  publish() {
    this.revision++
    const snapshot = this.snapshot()
    for (const listener of this.listeners) listener(snapshot)
  }

  /** Seed once, then replay events received while the asynchronous list was read. */
  seed(items) {
    if (this.pending === null) return
    const pending = this.pending
    this.pending = null
    for (const item of items) {
      if (item?.running === true && typeof item.sessionId === 'string') this.running.add(item.sessionId)
    }
    const observed = new Set()
    for (const [method, args] of pending) {
      const sessionId = args[0]
      if (method === 'status' || method === 'added' || method === 'remove') {
        // The list may already reflect this stop. Before the first baseline,
        // a live stop is completion evidence unless an earlier event says idle
        // or removed; historical idle rows alone are never completion evidence.
        if (method === 'status' && args[1] === false && !observed.has(sessionId)) this.running.add(sessionId)
        observed.add(sessionId)
      }
      this[method](...args)
    }
  }

  status(sessionId, running) {
    if (typeof sessionId !== 'string' || !sessionId) return
    if (this.pending !== null) { this.pending.push(['status', [sessionId, running]]); return }
    const wasRunning = this.running.has(sessionId)
    if (running) {
      this.running.add(sessionId)
      if (this.unread.delete(sessionId)) this.publish()
    } else {
      this.running.delete(sessionId)
      if (wasRunning && ![...this.views.values()].includes(sessionId) && !this.unread.has(sessionId)) {
        this.unread.add(sessionId)
        this.publish()
      }
    }
  }

  /** An added summary establishes running state; it is not a stop event. */
  added(sessionId, running) {
    if (typeof sessionId !== 'string' || !sessionId) return
    if (this.pending !== null) { this.pending.push(['added', [sessionId, running]]); return }
    if (running) this.status(sessionId, true)
    else this.running.delete(sessionId)
  }

  remove(sessionId) {
    if (this.pending !== null) { this.pending.push(['remove', [sessionId]]); return }
    this.running.delete(sessionId)
    for (const [client, viewed] of this.views) if (viewed === sessionId) this.views.delete(client)
    if (this.unread.delete(sessionId)) this.publish()
  }

  view(client, sessionId) {
    if (this.pending !== null) { this.pending.push(['view', [client, sessionId]]); return this.snapshot() }
    if (sessionId === null) this.views.delete(client)
    else this.views.set(client, sessionId)
    if (this.unread.delete(sessionId)) this.publish()
    return this.snapshot()
  }

  disconnect(client) {
    if (this.pending !== null) { this.pending.push(['disconnect', [client]]); return }
    this.views.delete(client)
  }
}
