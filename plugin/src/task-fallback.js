/**
 * Adapted from saya-ch/dsh-mobile (Apache-2.0) task-notify fallbacks.
 * Host-side completion inference when `session/event` turn/end is missing:
 * 90s quiet after activity, or busy→idle with 10s grace.
 */

export const TASK_NOTIFY_QUIET_MS = 90_000
export const TASK_NOTIFY_GRACE_MS = 10_000
export const TASK_NOTIFY_MIN_BUSY_MS = 20_000
export const TASK_NOTIFY_STALE_MS = 30 * 60_000

/**
 * Per-session fallback tracker driven by api-session status/activity.
 * Emits the same minimal {sessionId, turn, kind:'done'} shape as turn/end.
 */
export class TaskFallbackTracker {
  /**
   * @param {{
   *   quietMs?: number,
   *   graceMs?: number,
   *   minBusyMs?: number,
   *   now?: () => number,
   *   onTaskCompleted: (event: {sessionId: string, turn: number, kind: 'done'}) => void,
   *   isRootSession?: (sessionId: string) => boolean,
   *   hasPendingQuestion?: (sessionId: string) => boolean,
   * }} options
   */
  constructor(options) {
    this.quietMs = options.quietMs ?? TASK_NOTIFY_QUIET_MS
    this.graceMs = options.graceMs ?? TASK_NOTIFY_GRACE_MS
    this.minBusyMs = options.minBusyMs ?? TASK_NOTIFY_MIN_BUSY_MS
    this.now = options.now ?? Date.now
    this.onTaskCompleted = options.onTaskCompleted
    this.isRootSession = options.isRootSession ?? (() => true)
    this.hasPendingQuestion = options.hasPendingQuestion ?? (() => false)
    /** @type {Map<string, {
     *   lastActivityAt?: number,
     *   notifiedDoneAt?: number,
     *   wasBusy: boolean,
     *   busySince?: number,
     *   idleAnchoredAt?: number,
     *   timer?: ReturnType<typeof setTimeout>,
     * }>} */
    this.states = new Map()
  }

  state(sessionId) {
    let s = this.states.get(sessionId)
    if (!s) {
      s = { wasBusy: false }
      this.states.set(sessionId, s)
    }
    return s
  }

  markActivity(sessionId, at = this.now()) {
    if (!this.isRootSession(sessionId)) return
    const s = this.state(sessionId)
    s.lastActivityAt = at
    if (s.notifiedDoneAt !== undefined && s.notifiedDoneAt <= at) s.notifiedDoneAt = undefined
    this.schedule(sessionId)
  }

  /** Suppress fallback announce after an exact turn/end completion for the same session. */
  markAnnounced(sessionId, at = this.now()) {
    const s = this.state(sessionId)
    s.notifiedDoneAt = at
    s.idleAnchoredAt = undefined
    if (s.timer !== undefined) {
      clearTimeout(s.timer)
      s.timer = undefined
    }
  }

  onStatus(sessionId, running) {
    if (!this.isRootSession(sessionId)) return
    const s = this.state(sessionId)
    const now = this.now()
    if (!s.wasBusy && running) s.busySince = now
    if (s.wasBusy && !running) {
      const busyFor = s.busySince === undefined ? 0 : now - s.busySince
      s.busySince = undefined
      if (!this.hasPendingQuestion(sessionId) && busyFor >= this.minBusyMs) {
        s.idleAnchoredAt = undefined
        this.announce(sessionId, s, now)
        s.wasBusy = false
        return
      }
      s.idleAnchoredAt = now
    }
    s.wasBusy = running
    if (running) s.idleAnchoredAt = undefined
    this.schedule(sessionId)
  }

  /** Drop tracking when a session is removed. */
  forget(sessionId) {
    const s = this.states.get(sessionId)
    if (!s) return
    if (s.timer !== undefined) clearTimeout(s.timer)
    this.states.delete(sessionId)
  }

  dispose() {
    for (const s of this.states.values()) {
      if (s.timer !== undefined) clearTimeout(s.timer)
    }
    this.states.clear()
  }

  schedule(sessionId) {
    const s = this.state(sessionId)
    if (s.timer !== undefined) {
      clearTimeout(s.timer)
      s.timer = undefined
    }
    const delay = this.nextDelayMs(sessionId)
    if (delay === undefined) return
    s.timer = setTimeout(() => {
      s.timer = undefined
      this.evaluate(sessionId)
    }, delay)
  }

  nextDelayMs(sessionId) {
    const s = this.states.get(sessionId)
    if (!s || s.wasBusy || this.hasPendingQuestion(sessionId)) return undefined
    const now = this.now()
    const delays = []
    if (s.idleAnchoredAt !== undefined) {
      delays.push(Math.max(0, this.graceMs - (now - s.idleAnchoredAt)))
    }
    if (s.lastActivityAt !== undefined) {
      const since = now - s.lastActivityAt
      if (since > TASK_NOTIFY_STALE_MS) return undefined
      if (s.notifiedDoneAt !== undefined && s.notifiedDoneAt >= s.lastActivityAt) {
        /* already announced for this activity window */
      } else {
        delays.push(Math.max(0, this.quietMs - since))
      }
    }
    if (delays.length === 0) return undefined
    return Math.min(...delays)
  }

  evaluate(sessionId) {
    if (!this.isRootSession(sessionId)) return
    const s = this.state(sessionId)
    const now = this.now()
    if (s.wasBusy || this.hasPendingQuestion(sessionId)) return
    if (s.idleAnchoredAt !== undefined) {
      if (now - s.idleAnchoredAt < this.graceMs) {
        this.schedule(sessionId)
        return
      }
      s.idleAnchoredAt = undefined
      this.announce(sessionId, s, now)
      return
    }
    if (s.lastActivityAt === undefined) return
    if (now - s.lastActivityAt < this.quietMs) {
      this.schedule(sessionId)
      return
    }
    if (now - s.lastActivityAt > TASK_NOTIFY_STALE_MS) {
      s.lastActivityAt = undefined
      return
    }
    if (s.notifiedDoneAt !== undefined && s.notifiedDoneAt >= s.lastActivityAt) return
    this.announce(sessionId, s, now)
  }

  announce(sessionId, s, now) {
    s.notifiedDoneAt = now
    this.onTaskCompleted(Object.freeze({ sessionId, turn: 0, kind: 'done' }))
  }
}
