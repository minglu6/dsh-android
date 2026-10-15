import { extname } from 'node:path'

export const OFFICE_CHUNK_BYTES = 128 * 1024
const OUTPUT_LIMIT = 100 * 1024 * 1024
const failure = (reason) => Object.assign(new Error('Office conversion failed.'), {
  code: 'document-render/failed', details: { reason },
})
const bad = () => Object.assign(new Error('Invalid Office preview request.'), { code: 'gateway/bad-request' })
const missing = () => Object.assign(new Error('Office preview handle is unavailable.'), { code: 'office/handle-unavailable' })

/** Connection-owned leases over the official converter's caller-owned output; no second conversion cache. */
export function createOfficePreviewOps(ctx, {
  leaseMs = 60_000, maxHandles = 8, maxRetainedBytes = 200 * 1024 * 1024,
  maxOutputBytes = OUTPUT_LIMIT, renderTimeoutMs = 600_000,
} = {}) {
  const handles = new Map()
  let retainedBytes = 0
  let disposed = false
  const scopeOf = async (sessionId) => {
    const live = ctx.sessions.get(sessionId)?.header
    const stored = live === undefined ? await ctx.get('sessionPersistence')?.stat(sessionId) : undefined
    const header = live ?? stored?.header
    if (header === undefined) throw Object.assign(new Error('Session not found.'), { code: 'session/not-found' })
    const workspaceRoot = header.cwd ?? ctx.sandboxPolicy.workspaceRoot
    return { sessionId, workspaceRoot }
  }
  const release = (entry) => {
    if (handles.get(entry.id) !== entry) return
    handles.delete(entry.id)
    clearTimeout(entry.timer)
    retainedBytes -= entry.reserved
    entry.data = undefined
    if (entry.client.subs.get(entry.id) === entry.ac) entry.client.subs.delete(entry.id)
    entry.ac.abort()
  }
  const renew = (entry, ms = leaseMs, rendering = false) => {
    clearTimeout(entry.timer)
    entry.timer = setTimeout(() => {
      entry.timedOut = rendering
      release(entry)
    }, ms)
    entry.timer.unref?.()
  }
  const owned = (a, client) => {
    const entry = handles.get(a.handle)
    if (!entry || entry.client !== client || entry.scope?.sessionId !== a.sessionId) throw missing()
    return entry
  }
  const unchanged = async (entry, signal) => {
    const scope = await scopeOf(entry.scope.sessionId)
    if (scope.workspaceRoot !== entry.scope.workspaceRoot) throw failure('source-changed')
    const current = await ctx.workspaceFiles.stat(scope, entry.path, signal)
    if (current.absolutePath !== entry.meta.absolutePath || current.version !== entry.meta.version) throw failure('source-changed')
    signal.throwIfAborted()
  }
  const ops = {
    'office.render': async (a, signal, client) => {
      if (typeof a.sessionId !== 'string' || !a.sessionId || typeof a.path !== 'string' || !a.path ||
          !/^o-[a-zA-Z0-9-]{1,80}$/.test(a.subId ?? '') || !client?.subs || client.subs.has(a.subId) || handles.has(a.subId)) throw bad()
      if (!['.doc', '.docx', '.ppt', '.pptx'].includes(extname(a.path).toLowerCase())) throw failure('unsupported-format')
      if (disposed) throw failure('unavailable')
      if (handles.size >= maxHandles || retainedBytes + maxOutputBytes > maxRetainedBytes) throw failure('busy')
      const ac = new AbortController()
      const entry = { id: a.subId, sessionId: a.sessionId, client, ac, path: a.path, reserved: maxOutputBytes }
      handles.set(entry.id, entry)
      retainedBytes += entry.reserved
      client.subs.set(entry.id, ac)
      ac.signal.addEventListener('abort', () => release(entry), { once: true })
      renew(entry, renderTimeoutMs, true)
      const upstream = AbortSignal.any([ac.signal, signal, AbortSignal.timeout(renderTimeoutMs)])
      try {
        entry.scope = await scopeOf(a.sessionId)
        upstream.throwIfAborted()
        const result = await ctx.officeToPdf.render(entry.scope, a.path, 'foreground', upstream)
        upstream.throwIfAborted()
        if (!(result.data instanceof Uint8Array) || result.eof !== true || result.offset !== 0 ||
            result.bytes !== result.data.byteLength || result.bytes <= 0 ||
            typeof result.version !== 'string' || typeof result.generation !== 'string' ||
            typeof result.absolutePath !== 'string' || !Array.isArray(result.missingFonts) ||
            !result.missingFonts.every(font => typeof font === 'string')) throw failure('invalid-output')
        if (result.bytes > maxOutputBytes) throw failure('output-too-large')
        entry.meta = {
          handle: entry.id, absolutePath: result.absolutePath, version: result.version,
          generation: result.generation, bytes: result.bytes, missingFonts: result.missingFonts,
          chunkBytes: OFFICE_CHUNK_BYTES,
        }
        await unchanged(entry, upstream)
        retainedBytes += result.bytes - entry.reserved
        entry.reserved = result.bytes
        entry.data = result.data
        renew(entry)
        return entry.meta
      } catch (error) {
        const cancelled = upstream.aborted
        const timedOut = entry.timedOut || upstream.reason?.name === 'TimeoutError'
        release(entry)
        if (timedOut) throw failure('timeout')
        if (cancelled) throw Object.assign(new Error('The document preview was cancelled.'), { code: 'gateway/cancelled' })
        throw error
      }
    },
    'office.read': async (a, signal, client) => {
      const entry = owned(a, client)
      if (!entry.data) throw missing()
      if (!Number.isSafeInteger(a.offset) || a.offset < 0 || a.offset >= entry.data.byteLength ||
          !Number.isSafeInteger(a.length) || a.length < 1 || a.length > OFFICE_CHUNK_BYTES) throw bad()
      try {
        if (a.version !== entry.meta.version || a.generation !== entry.meta.generation) throw failure('source-changed')
        const upstream = AbortSignal.any([signal, entry.ac.signal])
        await unchanged(entry, upstream)
        const end = Math.min(entry.data.byteLength, a.offset + a.length)
        const data = Buffer.from(entry.data.buffer, entry.data.byteOffset + a.offset, end - a.offset).toString('base64')
        renew(entry)
        return { absolutePath: entry.meta.absolutePath, version: entry.meta.version,
          generation: entry.meta.generation, bytes: entry.meta.bytes, offset: a.offset,
          eof: end === entry.data.byteLength, encoding: 'base64', data }
      } catch (error) { release(entry); throw error }
    },
    'office.release': (a, _signal, client) => {
      const entry = handles.get(a.handle)
      if (!entry) return { released: false }
      // Pending renders also have an owner and can be cancelled before scope lookup settles.
      if (entry.client !== client || entry.sessionId !== a.sessionId) throw missing()
      release(entry)
      return { released: true }
    },
  }
  return { ops, dispose() { disposed = true; for (const entry of handles.values()) release(entry) } }
}
