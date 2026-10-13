/**
 * Adapted from saya-ch/dsh-mobile (Apache-2.0)
 * Host-side task-completion fan-out for phone notifications.
 *
 * Watches the public `session/event` bus for completed root turns and hands
 * them to a sink (normally the pocket WS server). Phones render the text
 * locally — only opaque session and turn ids cross this boundary.
 */

export const TASK_EVENT_DEBOUNCE_MS = 1_000

function turnNumber(value) {
  return typeof value === 'number' && Number.isSafeInteger(value) && value >= 0 ? value : 0
}

function turnData(value) {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) {
    return { turn: 0, completed: false }
  }
  const reason = value.reason
  const completed = typeof reason === 'string'
    ? reason === 'completed'
    : reason !== null && typeof reason === 'object' && reason.kind === 'completed'
  return { turn: turnNumber(value.turn), completed }
}

/**
 * Subscribe to completed root turns. Subagent turns are skipped so one task
 * announces once, and rapid turn boundaries collapse into a single event.
 * @returns {() => void} disposer that also drops pending debounces
 */
export function watchTaskCompletions(ctx, options) {
  const debounceMs = options.debounceMs ?? TASK_EVENT_DEBOUNCE_MS
  const pending = new Map()
  const disposeListener = ctx.on('session/event', (session, event) => {
    if (event?.type !== 'turn/end') return
    const { turn, completed } = turnData(event.data)
    if (!completed) return
    if (session?.header?.parentSession !== undefined && session?.header?.parentSession !== null) return
    const sessionId = String(session.id)
    options.log?.('task-completed-observed', { sessionId, turn })
    const previous = pending.get(sessionId)
    if (previous !== undefined) clearTimeout(previous)
    pending.set(sessionId, setTimeout(() => {
      pending.delete(sessionId)
      options.log?.('task-completed-announced', { sessionId, turn })
      options.onTaskCompleted(Object.freeze({ sessionId, turn, kind: 'done' }))
    }, debounceMs))
  })
  return () => {
    disposeListener()
    for (const timer of pending.values()) clearTimeout(timer)
    pending.clear()
  }
}

/** One subscription feeding every sink; normally a single pocket server. */
export class TaskEventHub {
  constructor() {
    this.sinks = new Set()
  }

  add(sink) {
    this.sinks.add(sink)
    return () => { this.sinks.delete(sink) }
  }

  broadcast(event) {
    for (const sink of [...this.sinks]) {
      try { sink.broadcastTaskEvent(event) } catch { /* A failing sink must not break siblings. */ }
    }
  }

  get size() {
    return this.sinks.size
  }
}
