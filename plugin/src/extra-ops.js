import { randomUUID } from 'node:crypto'

const str = (v) => typeof v === 'string' && v.length > 0
const bad = (m) => Object.assign(new Error(m), { code: 'bad-request' })

/** Session object of a live (or auto-resumed) Agent. */
function sessionOf(agents, sessionId) {
  if (!str(sessionId)) throw bad('sessionId required')
  const agent = agents.get(sessionId)
  if (!agent) throw Object.assign(new Error('session has no live agent'), { code: 'session/not-found' })
  return agent.session
}

/** Live Agent for terminal / command ops that need the Gateway Agent handle. */
function agentOf(agents, sessionId) {
  if (!str(sessionId)) throw bad('sessionId required')
  const agent = agents.get(sessionId)
  if (!agent) throw Object.assign(new Error('session has no live agent'), { code: 'session/not-found' })
  return agent
}

/** permissions.* over the in-process permissionPresets service. */
export function permissionOps(presets, agents) {
  return {
    'permissions.catalog': () => presets.catalog(),
    'permissions.get': (a) => ({ current: presets.current(sessionOf(agents, a.sessionId)) }),
    'permissions.set': (a) => {
      if (!str(a.name)) throw bad('name required')
      const session = sessionOf(agents, a.sessionId)
      presets.set(session, a.name)
      return { current: presets.current(session) }
    },
  }
}

/** presets.list over the agentPresets registry (same roster the web UI uses). */
export function presetOps(agentPresets, settingsController) {
  return {
    'presets.list': () => agentPresets.remoteExportList(),
    ...(typeof agentPresets.readDocument === 'function' ? {
      'presets.read': (a) => {
        if (!str(a.id)) throw bad('preset id required')
        return agentPresets.readDocument(a.id)
      },
    } : {}),
    ...(settingsController?.describe ? {
      'presets.settings': async () => {
        const described = await settingsController.describe()
        return {
          ...await agentPresets.remoteExportList(),
          writable: described.writable !== false && typeof settingsController?.mutate === 'function',
          namespace: described.namespaces.find((ns) => ns.ns === 'agent-preset-registry'),
        }
      },
    } : {}),
  }
}

/**
 * Slash commands (`/compact`, `/plan`, `/goal …`, `/feedback …`) via Host commands service.
 * Line may be with or without a leading `/`.
 */
export function commandOps(commands, agents) {
  return {
    'commands.list': (a) => ({ commands: commands.list(agentOf(agents, a.sessionId)) }),
    'commands.execute': async (a, signal) => {
      if (!str(a.sessionId) || !str(a.line)) throw bad('sessionId and line required')
      const agent = agents.get(a.sessionId)
      if (!agent) throw Object.assign(new Error('session has no live agent'), { code: 'session/not-found' })
      const line = a.line.trimStart().startsWith('/') ? a.line.trim() : `/${a.line.trim()}`
      const execution = await commands.execute(agent, line, Array.isArray(a.attachments) ? a.attachments : [], signal)
      if (execution === undefined) throw Object.assign(new Error(`unknown command: ${line}`), { code: 'command/unknown' })
      return { commandId: execution.commandId, result: execution.result }
    },
  }
}

/**
 * Read-only workspace file browse for the phone right panel.
 * Scope is built from the live (or persisted) session header cwd.
 */
export function filesOps(workspaceFiles, sessions, sandboxPolicy) {
  const scopeOf = async (sessionId) => {
    if (!str(sessionId)) throw bad('sessionId required')
    const live = sessions.get?.(sessionId)?.header
    const workspaceRoot = live?.cwd ?? sandboxPolicy?.workspaceRoot
    if (!str(workspaceRoot)) throw Object.assign(new Error('session has no workspace root'), { code: 'session/not-found' })
    return { sessionId, workspaceRoot }
  }
  return {
    'files.list': async (a, signal) => {
      const scope = await scopeOf(a.sessionId)
      return workspaceFiles.list(scope, str(a.path) ? a.path : '.', signal)
    },
    'files.read': async (a, signal) => {
      if (!str(a.path)) throw bad('path required')
      const scope = await scopeOf(a.sessionId)
      return workspaceFiles.read(scope, a.path, a.range, signal)
    },
    /**
     * Binary preview for images / PDF. Host `readBytes` returns a Uint8Array;
     * JSON transport base64-encodes it as `{encoding:'base64', data}`.
     */
    'files.readBytes': async (a, signal) => {
      if (!str(a.path)) throw bad('path required')
      const scope = await scopeOf(a.sessionId)
      const options = a.options && typeof a.options === 'object' ? a.options : undefined
      const r = await workspaceFiles.readBytes(scope, a.path, options, signal)
      const raw = r?.data
      const buf = Buffer.isBuffer(raw) ? raw : Buffer.from(raw ?? [])
      return {
        absolutePath: r.absolutePath,
        version: r.version,
        bytes: r.bytes,
        offset: r.offset,
        eof: r.eof,
        encoding: 'base64',
        data: buf.toString('base64'),
      }
    },
  }
}

/**
 * Host shell on the desktop machine (`terminalController`). Remote PTY is useful
 * on the phone; only local-app/editor launchers stay hidden.
 *
 * `terminal.follow` starts a background pump and returns `{subId, attachmentId}`
 * immediately; frames arrive as `{ev:"terminal", subId, frame}`. Cancel with
 * the existing `unfollow {subId}` (shared abort map on the WS client).
 */
export function terminalOps(terminalController, agents) {
  return {
    'terminal.list': (a) => {
      if (!str(a.sessionId)) throw bad('sessionId required')
      return { terminals: terminalController.list(a.sessionId) ?? [], platform: process.platform }
    },
    'terminal.create': async (a, signal) => {
      const agent = agentOf(agents, a.sessionId)
      const id = str(a.id) ? a.id : randomUUID().replace(/-/g, '').slice(0, 16)
      const cols = Number.isSafeInteger(a.cols) ? a.cols : 80
      const rows = Number.isSafeInteger(a.rows) ? a.rows : 24
      const info = await terminalController.create(agent, {
        id, cols, rows,
        ...(str(a.shellPath) ? { shellPath: a.shellPath } : {}),
      }, signal)
      return { terminal: info, platform: process.platform }
    },
    'terminal.write': async (a) => {
      const agent = agentOf(agents, a.sessionId)
      if (!str(a.id) || !str(a.attachmentId) || typeof a.data !== 'string') {
        throw bad('sessionId, id, attachmentId, data required')
      }
      await terminalController.write(agent, a.id, a.attachmentId, a.data)
      return { accepted: true }
    },
    'terminal.resize': async (a) => {
      const agent = agentOf(agents, a.sessionId)
      if (!str(a.id) || !str(a.attachmentId)) throw bad('sessionId, id, attachmentId required')
      const cols = Number.isSafeInteger(a.cols) ? a.cols : 80
      const rows = Number.isSafeInteger(a.rows) ? a.rows : 24
      await terminalController.resize(agent, a.id, a.attachmentId, cols, rows)
      return { accepted: true }
    },
    'terminal.close': async (a) => {
      const agent = agentOf(agents, a.sessionId)
      if (!str(a.id)) throw bad('id required')
      await terminalController.close(agent, a.id)
      return { closed: true }
    },
    'terminal.follow': async (a, _signal, client) => {
      const agent = agentOf(agents, a.sessionId)
      if (!str(a.id)) throw bad('id required')
      if (!client?.subs || typeof client.push !== 'function') {
        throw Object.assign(new Error('terminal.follow requires a live pocket client'), { code: 'internal' })
      }
      const attachmentId = str(a.attachmentId) ? a.attachmentId : randomUUID().replace(/-/g, '').slice(0, 16)
      const subId = `t${client.nextSub++}`
      const ac = new AbortController()
      client.subs.set(subId, ac)
      void (async () => {
        try {
          for await (const frame of terminalController.follow(agent, a.id, attachmentId, ac.signal)) {
            if (ac.signal.aborted) break
            client.push({ ev: 'terminal', subId, frame })
          }
          if (!ac.signal.aborted) client.push({ ev: 'terminal.end', subId })
        } catch (error) {
          if (!ac.signal.aborted) {
            client.push({
              ev: 'terminal.error', subId,
              error: { code: error?.code ?? 'internal', message: String(error?.message ?? error) },
            })
          }
        } finally {
          client.subs.delete(subId)
        }
      })()
      return { subId, attachmentId }
    },
  }
}

/**
 * Background jobs roster for one session. Host `job.list` is a stream; we take
 * the first `rows` frame then abort (whole-set replacement).
 */
export function jobsOps(jobController) {
  return {
    'jobs.follow': (a, _signal, client) => {
      if (!str(a.sessionId) || !str(a.jobId)) throw bad('sessionId and jobId required')
      if (a.from !== undefined && (!Number.isSafeInteger(a.from) || a.from < 0)) throw bad('invalid output offset')
      const subId = `j${client.nextSub++}`
      const ac = new AbortController()
      client.subs.set(subId, ac)
      void (async () => {
        try {
          for await (const frame of jobController.follow(a, ac.signal)) {
            if (ac.signal.aborted) break
            client.push({ ev: 'job', subId, frame })
          }
          if (!ac.signal.aborted) client.push({ ev: 'job.end', subId })
        } catch (error) {
          if (!ac.signal.aborted) client.push({ ev: 'job.error', subId, error: { code: error?.code ?? 'internal', message: String(error?.message ?? error) } })
        } finally { client.subs.delete(subId) }
      })()
      return { subId }
    },
    'jobs.list': async (a, signal) => {
      if (!str(a.sessionId)) throw bad('sessionId required')
      const ac = new AbortController()
      const onAbort = () => ac.abort()
      signal?.addEventListener?.('abort', onAbort, { once: true })
      try {
        for await (const frame of jobController.list({ sessionId: a.sessionId }, ac.signal)) {
          if (frame?.type === 'rows') {
            ac.abort()
            return { jobs: frame.jobs ?? [] }
          }
        }
        return { jobs: [] }
      } catch (error) {
        if (ac.signal.aborted) return { jobs: [] }
        throw error
      } finally {
        signal?.removeEventListener?.('abort', onAbort)
      }
    },
    'jobs.kill': (a) => {
      if (!str(a.sessionId) || !str(a.jobId)) throw bad('sessionId and jobId required')
      return jobController.kill({ sessionId: a.sessionId, jobId: a.jobId })
    },
  }
}

/**
 * Session log export: walk `sessionController.page` into JSONL and ZIP it
 * (single entry). Matches desktop download intent without Host cookie auth.
 */
export function exportOps(sc, { zipSync, strToU8 } = {}) {
  if (typeof zipSync !== 'function' || typeof strToU8 !== 'function') {
    throw new Error('exportOps requires fflate zipSync and strToU8')
  }
  return {
    'session.export': async (a, signal) => {
      if (!str(a.sessionId)) throw bad('sessionId required')
      const sessionId = a.sessionId
      const includeDescendants = a.includeDescendants !== false
      const lines = []
      let throughSeq = Number.MAX_SAFE_INTEGER
      let guard = 0
      while (guard++ < 200) {
        signal?.throwIfAborted?.()
        const page = await sc.page({
          address: { kind: 'session', sessionId },
          throughSeq,
          maxMessages: 200,
        }, signal)
        const records = page?.records ?? []
        for (const rec of records) lines.push(JSON.stringify(rec))
        if (!page?.hasMore || records.length === 0) break
        const seqs = records.map((r) => r?.event?.seq).filter((n) => Number.isSafeInteger(n))
        const min = seqs.length ? Math.min(...seqs) : null
        if (min === null || min <= 0) break
        throughSeq = min - 1
      }
      const body = `${lines.join('\n')}${lines.length ? '\n' : ''}`
      const filename = `dsh-session-${sessionId.replace(/[^A-Za-z0-9._-]+/g, '_').slice(0, 80)}.zip`
      const zipped = zipSync({ [`${sessionId}.jsonl`]: strToU8(body) }, { level: 6 })
      return {
        filename,
        mediaType: 'application/zip',
        includeDescendants,
        data: Buffer.from(zipped).toString('base64'),
      }
    },
  }
}
