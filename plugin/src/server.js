import { randomUUID } from 'node:crypto'
import { createPairing } from './pairing.js'
import { createDeviceStore } from './devices.js'
import { createServer } from 'node:http'
import { WebSocketServer } from 'ws'

export const PROTO = 1
const PRE_AUTH_MAX = 64 * 1024
const MAX_PAYLOAD = 32 * 1024 * 1024
/** Hello / pair must complete within this window (slow links: ~120s). */
export const HELLO_TIMEOUT_MS = 120_000
/** Default application ping interval for slow links (ref dsh-mobile SLOW_CONNECTIONS). */
export const DEFAULT_HEARTBEAT_MS = 30_000
/** Drop idle authed clients after this silence (covers ~4 missed heartbeats). */
export const DEFAULT_IDLE_MS = 120_000
/**
 * Adapted from saya-ch/dsh-mobile (Apache-2.0) websocket-compression defaults.
 * Compress outbound frames ≥ 1 KiB at zlib level 3; no context takeover.
 */
export const WS_DEFLATE = Object.freeze({
  clientNoContextTakeover: true,
  serverNoContextTakeover: true,
  threshold: 1024,
  zlibDeflateOptions: { level: 3 },
})
/** Default follow snapshot window when the client omits maxMessages (phone path). */
export const DEFAULT_FOLLOW_MAX_MESSAGES = 50
/** Soft ceiling for one follow JSON frame (~900 KiB); leave headroom under iroh MAX_LINE. */
export const FRAME_SOFT_LIMIT = 900 * 1024
/** Hard ceiling matching pocket-iroh MAX_LINE — a frame must never exceed this. */
export const FRAME_HARD_LIMIT = 1024 * 1024
/** Inline tool-output truncation budget when shrinking an oversized snapshot. */
export const TOOL_TEXT_TRUNCATE = 4_096

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
const eventSeq = (rec) => rec?.event?.seq
const frameBytes = (frame) => Buffer.byteLength(JSON.stringify(frame), 'utf8')

class OpError extends Error {
  constructor(code, message) { super(message); this.code = code }
}
const need = (cond, msg) => { if (!cond) throw new OpError('bad-request', msg) }
const str = (v) => typeof v === 'string' && v.length > 0

/**
 * Keep only fields Android `SessionSummary.parse` reads.
 * Full dsh list items carry tokenUsage / turnOutline / contextBreakdown and can
 * push a multi-session `sessions.list` reply near the iroh MAX_LINE cap.
 */
export function slimSessionSummary(item) {
  if (!item || typeof item !== 'object') return item
  const title = item.projections?.values?.title
  const out = {
    sessionId: item.sessionId,
    updatedAt: item.updatedAt,
    running: item.running,
    blank: item.blank,
    cwd: item.cwd,
    parentSessionId: item.parentSessionId,
  }
  if (title !== undefined && title !== null) {
    out.projections = { values: { title } }
  }
  return out
}

export function slimSessionsList(result) {
  if (!result || typeof result !== 'object') return result
  if (!Array.isArray(result.items)) return result
  return { ...result, items: result.items.map(slimSessionSummary) }
}

/**
 * Truncate huge tool/result (and similar) text payloads inline so a follow
 * snapshot fits under the iroh line cap. Full text is available later via
 * history.page / tool expand.
 */
export function truncateHugeToolOutputs(frame, maxText = TOOL_TEXT_TRUNCATE) {
  if (!frame || typeof frame !== 'object') return frame
  const records = frame.records
  if (!Array.isArray(records)) return frame
  let changed = false
  const next = records.map((rec) => {
    const ev = rec?.event
    if (!ev || typeof ev !== 'object') return rec
    const data = ev.data
    if (!data || typeof data !== 'object') return rec
    const msg = data.message
    if (!msg || typeof msg !== 'object' || !Array.isArray(msg.content)) return rec
    let msgChanged = false
    const content = msg.content.map((block) => {
      if (!block || block.type !== 'text' || typeof block.text !== 'string') return block
      if (block.text.length <= maxText) return block
      msgChanged = true
      return {
        ...block,
        text: `${block.text.slice(0, maxText)}\n…[truncated ${block.text.length - maxText} bytes]`,
        truncated: true,
        originalLength: block.text.length,
      }
    })
    if (!msgChanged) return rec
    changed = true
    return { ...rec, event: { ...ev, data: { ...data, message: { ...msg, content } } } }
  })
  return changed ? { ...frame, records: next } : frame
}

/**
 * Ensure a follow snapshot/resume frame fits under FRAME_SOFT_LIMIT (and never
 * over FRAME_HARD_LIMIT). Prefer a smaller history window; then truncate tool
 * outputs. Preserves hasMore/cursor from dsh when trimming.
 *
 * @param {object} frame
 * @param {{ sessionId: string, page?: Function, signal?: AbortSignal }} opts
 */
export async function fitFollowFrame(frame, opts = {}) {
  if (!frame || typeof frame !== 'object') return frame
  if (frame.type !== 'snapshot' && frame.type !== 'resume') return frame
  let out = frame
  if (frameBytes(out) <= FRAME_SOFT_LIMIT) return out

  // Re-fetch a smaller window from history when page is available.
  if (opts.page && Number.isSafeInteger(out.cursor) && str(opts.sessionId)) {
    for (const n of [25, 10, 5, 1]) {
      try {
        const page = await opts.page({
          address: { kind: 'session', sessionId: opts.sessionId },
          throughSeq: out.cursor,
          maxMessages: n,
        }, opts.signal)
        if (!page || !Array.isArray(page.records)) continue
        let candidate = {
          ...out,
          records: page.records,
          hasMore: page.hasMore !== false || (Array.isArray(out.records) && out.records.length > page.records.length),
        }
        if (frameBytes(candidate) <= FRAME_SOFT_LIMIT) return candidate
        candidate = truncateHugeToolOutputs(candidate)
        if (frameBytes(candidate) <= FRAME_SOFT_LIMIT) return candidate
        out = candidate
      } catch {
        /* try next window / fall through */
      }
    }
  }

  // Shrink the in-memory message window: keep the newest N records.
  const records = Array.isArray(out.records) ? out.records : []
  for (const n of [25, 10, 5, 1]) {
    if (records.length <= n) continue
    const trimmed = { ...out, records: records.slice(-n), hasMore: true }
    if (frameBytes(trimmed) <= FRAME_SOFT_LIMIT) return trimmed
    out = trimmed
  }

  out = truncateHugeToolOutputs(out)
  if (frameBytes(out) <= FRAME_SOFT_LIMIT) return out

  // Drop oldest records until under soft limit.
  let recs = Array.isArray(out.records) ? [...out.records] : []
  while (recs.length > 0 && frameBytes({ ...out, records: recs, hasMore: true }) > FRAME_SOFT_LIMIT) {
    recs = recs.slice(1)
  }
  out = { ...out, records: recs, hasMore: true }
  if (frameBytes(out) > FRAME_HARD_LIMIT) {
    out = { ...out, records: [], hasMore: true }
    if (frameBytes(out) > FRAME_HARD_LIMIT) {
      throw new OpError('frame/too-large', `follow frame exceeds ${FRAME_HARD_LIMIT} bytes`)
    }
  }
  return out
}

/** Resolve maxMessages for follow: client value, else default cap. */
export function followMaxMessages(args = {}) {
  if (Number.isSafeInteger(args.maxMessages) && args.maxMessages > 0) return args.maxMessages
  return DEFAULT_FOLLOW_MAX_MESSAGES
}

/** Build the op table over an in-process sessionController. */
export function createOps(sc, { timeZone } = {}) {
  const tz = timeZone ?? Intl.DateTimeFormat().resolvedOptions().timeZone
  return {
    'sessions.list': async (a, signal) => slimSessionsList(
      await sc.list({ ...(str(a.cursor) ? { cursor: a.cursor } : {}) }, signal),
    ),
    'sessions.create': (a) => sc.create({
      ...(str(a.cwd) ? { cwd: a.cwd } : {}),
      ...(str(a.workspaceId) ? { workspaceId: a.workspaceId } : {}),
      ...(str(a.agentPreset) ? { agentPreset: a.agentPreset } : {}),
    }),
    'sessions.rename': (a) => {
      need(str(a.sessionId) && str(a.title), 'sessionId and title required')
      return sc.rename({ sessionId: a.sessionId, title: a.title })
    },
    /** Fork at an exact event boundary (omit atSeq → latest completed-turn prefix). */
    'sessions.fork': (a) => {
      need(str(a.sessionId), 'sessionId required')
      const atSeq = a.atSeq
      need(
        atSeq === undefined || atSeq === null || Number.isSafeInteger(atSeq),
        'atSeq must be a safe integer when provided',
      )
      return sc.fork({
        sessionId: a.sessionId,
        ...(Number.isSafeInteger(atSeq) ? { atSeq } : {}),
        ...(a.allowMigration === false ? { allowMigration: false } : {}),
      })
    },
    prompt: (a, signal) => {
      need(str(a.sessionId), 'sessionId required')
      need(a.mode === 'queue' || a.mode === 'steer', 'mode must be queue or steer')
      const content = []
      if (str(a.text)) content.push({ type: 'text', text: a.text })
      for (const img of Array.isArray(a.images) ? a.images : []) {
        need(str(img?.mediaType) && str(img?.data), 'image needs mediaType and data')
        content.push({ type: 'image', mediaType: img.mediaType, data: img.data, ...(str(img.name) ? { name: img.name } : {}) })
      }
      // Staged non-image files from `attachments.upload` (`{type:'file', receiptId}`).
      for (const file of Array.isArray(a.files) ? a.files : []) {
        need(str(file?.receiptId), 'file needs receiptId')
        content.push({ type: 'file', receiptId: file.receiptId })
      }
      need(content.length > 0, 'text, images, or files required')
      return sc.prompt({
        requestId: str(a.requestId) ? a.requestId : randomUUID(),
        sessionId: a.sessionId, mode: a.mode, content, clientTimeZone: tz,
      }, signal)
    },
    'queue.update': (a) => {
      need(str(a.sessionId) && str(a.itemId) && a.action !== undefined, 'sessionId, itemId, action required')
      return sc.updateQueue({ sessionId: a.sessionId, itemId: a.itemId, action: a.action })
    },
    cancel: (a) => { need(str(a.sessionId), 'sessionId required'); return sc.cancel({ sessionId: a.sessionId }) },
    'history.page': (a, signal) => {
      need(str(a.sessionId) && Number.isSafeInteger(a.throughSeq), 'sessionId and throughSeq required')
      return sc.page({
        address: { kind: 'session', sessionId: a.sessionId }, throughSeq: a.throughSeq,
        ...(Number.isSafeInteger(a.beforeSeq) ? { beforeSeq: a.beforeSeq } : {}),
        ...(Number.isSafeInteger(a.maxMessages) ? { maxMessages: a.maxMessages } : {}),
      }, signal)
    },
    'models.catalog': () => sc.modelCatalog(),
    'models.select': (a) => {
      need(str(a.sessionId) && str(a.provider) && str(a.model), 'sessionId, provider, model required')
      return sc.selectModel({
        sessionId: a.sessionId, provider: a.provider, model: a.model,
        ...(str(a.reasoningEffort) ? { reasoningEffort: a.reasoningEffort } : {}),
      })
    },
  }
}

/**
 * @param {{sessionController: any, token: string, host?: string, port?: number,
 *   devices?: ReturnType<typeof createDeviceStore>, pairing?: ReturnType<typeof createPairing>,
 *   onSessionEvent?: (fn: (kind: string, args: unknown[]) => void) => () => void,
 *   heartbeatMs?: number, idleMs?: number, pairFailDelayMs?: number, info?: object}} opts
 */
export function createPocketServer(opts) {
  const sc = opts.sessionController
  const devices = opts.devices ?? createDeviceStore({ ownerToken: opts.token, persist: false })
  const pairing = opts.pairing ?? createPairing()
  const heartbeatMs = opts.heartbeatMs ?? DEFAULT_HEARTBEAT_MS
  const idleMs = opts.idleMs ?? DEFAULT_IDLE_MS
  const pairFailDelayMs = opts.pairFailDelayMs ?? 200
  const ops = createOps(sc)
  const http = createServer((req, res) => {
    res.writeHead(426, { 'content-type': 'text/plain' }).end('websocket required')
  })
  // Adapted from saya-ch/dsh-mobile (Apache-2.0): negotiate permessage-deflate with phones.
  // Frame-size caps (PRE_AUTH_MAX / MAX_PAYLOAD / FRAME_*_LIMIT) still apply to decoded JSON.
  const wss = new WebSocketServer({
    server: http,
    maxPayload: MAX_PAYLOAD,
    perMessageDeflate: opts.perMessageDeflate === false ? false : (opts.perMessageDeflate ?? WS_DEFLATE),
  })
  const clients = new Set()
  /** approvalId -> { payload, settle(outcome) } */
  const approvals = new Map()
  /** callId (or synthetic id) -> { payload, settle(answer), close } */
  const userQuestions = new Map()
  /** sessionId -> true when known to be a subagent (has parentSessionId). */
  const childSessions = new Set()
  const authedClients = () => [...clients].filter((c) => c.device)
  const broadcast = (m) => { for (const c of authedClients()) c.push(m) }
  const pushTaskEvent = (event) => {
    need(str(event?.sessionId), 'sessionId required')
    const kind = event.kind === 'question' ? 'question' : 'done'
    const turn = typeof event.turn === 'number' && Number.isSafeInteger(event.turn) && event.turn >= 0
      ? event.turn
      : 0
    const payload = { ev: 'task', kind, sessionId: event.sessionId, turn }
    if (kind === 'question' && str(event.questionId)) payload.questionId = event.questionId
    broadcast(payload)
  }
  const unsub = opts.onSessionEvent?.((kind, args) => {
    if (kind === 'added') {
      const summary = args[0]
      if (summary?.parentSessionId) childSessions.add(summary.sessionId)
      else if (summary?.sessionId) childSessions.delete(summary.sessionId)
    } else if (kind === 'removed') {
      childSessions.delete(args[0])
    }
    broadcast({ ev: 'session', ...sessionEventPayload(kind, args) })
    opts.onSessionLifecycle?.(kind, args)
  })

  const adminOps = {
    'pairing.open': () => pairing.open(),
    'pairing.close': () => { pairing.close(); return { open: false } },
    'pairing.status': () => pairing.status(),
  }
  const deviceOps = {
    'devices.list': () => ({ devices: devices.list() }),
    'devices.revoke': (a) => {
      need(str(a.deviceId), 'deviceId required')
      const removed = devices.revoke(a.deviceId)
      for (const c of clients) if (c.device?.id === a.deviceId) c.close(4403, 'revoked')
      return { removed }
    },
    'approval.answer': (a, _s, client) => {
      need(str(a.approvalId), 'approvalId required')
      need(a.outcome === 'allowed-once' || a.outcome === 'rejected', 'outcome must be allowed-once or rejected')
      const p = approvals.get(a.approvalId)
      if (!p) throw new OpError('approval/not-pending', 'approval already settled or unknown')
      p.settle(a.outcome, client.device.id)
      return { accepted: true }
    },
    'approval.pending': () => ({ approvals: [...approvals.values()].map((p) => p.payload) }),
  }
  /** Ops contributed by optional in-process services (see index.js). */
  const extraOps = {}
  const allOps = () => [...Object.keys(ops), ...Object.keys(deviceOps), ...Object.keys(extraOps), 'follow', 'unfollow']
  /** peer endpoint id → last reported iroh path (`direct`|`relay`) */
  const irohPaths = new Map()

  /**
   * Bind a transport-agnostic client (WebSocket or iroh pipe).
   * @param {{ push(m: object): void, close(code?: number, reason?: string): void, terminate?(): void, meta?: object }} transport
   */
  function bindClient(transport) {
    let disposed = false
    /** @type {ReturnType<typeof setTimeout> | undefined} */
    let helloTimer
    /** @type {ReturnType<typeof setInterval> | undefined} */
    let beat
    const dispose = () => {
      if (disposed) return
      disposed = true
      clearTimeout(helloTimer); clearInterval(beat); clients.delete(client)
      for (const ac of client.subs.values()) ac.abort()
    }
    const client = {
      device: undefined, subs: new Map(), lastSeen: Date.now(), nextSub: 1,
      meta: transport.meta ?? { transport: 'ws' },
      push: (m) => { if (!disposed) transport.push(m) },
      close: (code, reason) => { transport.close(code, reason); dispose() },
    }
    clients.add(client)
    helloTimer = setTimeout(() => { if (!client.device) client.close(4401, 'hello timeout') }, HELLO_TIMEOUT_MS)
    beat = setInterval(() => {
      if (!client.device) return
      if (Date.now() - client.lastSeen > idleMs) {
        if (typeof transport.terminate === 'function') transport.terminate()
        else client.close(1001, 'idle')
        return
      }
      client.push({ ev: 'ping', t: Date.now() })
    }, heartbeatMs)
    let busy = false
    const onMessage = async (msg, rawLen = 0) => {
      client.lastSeen = Date.now()
      if (!client.device && rawLen > PRE_AUTH_MAX) { client.close(1009, 'too large'); return }
      if (msg === null || typeof msg !== 'object') { client.close(1007, 'bad frame'); return }
      if (msg.op === 'pong') return
      const reply = (body) => client.push({ id: msg.id, ...body })
      const fail = (code, message) => reply({ ok: false, error: { code, message } })
      if (!client.device) {
        if (busy) return
        if (msg.op !== 'hello' && msg.op !== 'pair') { fail('auth/denied', 'hello with a valid token required'); client.close(4401, 'unauthorized'); return }
        if (msg.args?.proto !== PROTO) { fail('proto/unsupported', `proto ${PROTO} required`); client.close(4400, 'proto'); return }
        if (msg.op === 'pair') {
          busy = true
          const reason = pairing.attempt(msg.args?.code)
          if (reason !== null) {
            await sleep(pairFailDelayMs)
            fail(reason, 'pairing failed'); client.close(4401, 'pair failed'); return
          }
          const issued = devices.add(msg.args?.name)
          client.device = devices.authenticate(issued.token)
          busy = false; clearTimeout(helloTimer)
          reply({ ok: true, result: { proto: PROTO, ...issued, ...(opts.info ?? {}), ops: allOps() } })
          sendPending(client)
          return
        }
        const device = msg.op === 'hello' ? devices.authenticate(msg.args?.token) : undefined
        if (!device) { fail('auth/denied', 'hello with a valid token required'); client.close(4401, 'unauthorized'); return }
        client.device = device; clearTimeout(helloTimer)
        reply({ ok: true, result: { proto: PROTO, device: { id: device.id, name: device.name }, ...(opts.info ?? {}), ops: allOps() } })
        sendPending(client)
        return
      }
      void handle(client, msg, reply, fail)
    }
    return {
      client,
      onMessage,
      onRemoteClose: dispose,
      close: (code, reason) => { client.close(code, reason) },
    }
  }

  wss.on('connection', (ws) => {
    const bound = bindClient({
      push: (m) => { if (ws.readyState === ws.OPEN) ws.send(JSON.stringify(m)) },
      close: (code, reason) => { try { ws.close(code ?? 1000, reason) } catch { /* ignore */ } },
      terminate: () => ws.terminate(),
      meta: { transport: 'ws' },
    })
    ws.on('close', () => bound.onRemoteClose())
    ws.on('message', (raw, isBinary) => {
      if (isBinary) { bound.client.close(1003, 'text only'); return }
      let msg
      try { msg = JSON.parse(raw.toString('utf8')) } catch { bound.client.close(1007, 'bad json'); return }
      void bound.onMessage(msg, raw.length)
    })
  })

  function sendPending(client) {
    for (const p of approvals.values()) client.push({ ev: 'approval', ...p.payload })
    for (const p of userQuestions.values()) client.push({ ev: 'user-question', ...p.payload })
  }

  async function handle(client, msg, reply, fail) {
    const args = msg.args && typeof msg.args === 'object' ? msg.args : {}
    try {
      if (msg.op === 'follow') {
        need(str(args.sessionId), 'sessionId required')
        need(args.afterSeq === undefined || Number.isSafeInteger(args.afterSeq), 'afterSeq must be an integer')
        const subId = `s${client.nextSub++}`
        const ac = new AbortController()
        client.subs.set(subId, ac)
        reply({ ok: true, result: { subId } })
        void pump(client, subId, ac, args)
        return
      }
      if (msg.op === 'unfollow') {
        const ac = client.subs.get(args.subId)
        ac?.abort(); client.subs.delete(args.subId)
        reply({ ok: true, result: { stopped: ac !== undefined } })
        return
      }
      let op
      if (Object.hasOwn(ops, msg.op)) op = ops[msg.op]
      else if (Object.hasOwn(deviceOps, msg.op)) op = deviceOps[msg.op]
      else if (Object.hasOwn(extraOps, msg.op)) op = extraOps[msg.op]
      else if (Object.hasOwn(adminOps, msg.op)) {
        if (!client.device.owner) { fail('auth/forbidden', 'owner token required'); return }
        op = adminOps[msg.op]
      }
      if (op === undefined) { fail('unknown-op', `unknown op ${JSON.stringify(msg.op)}`); return }
      const result = await op(args, AbortSignal.timeout(120_000), client)
      reply({ ok: true, result: result ?? null })
    } catch (error) {
      fail(error?.code ?? 'internal', String(error?.message ?? error))
    }
  }

  async function resumeFrame(snapshot, args, signal) {
    const after = args.afterSeq
    const records = snapshot.records ?? []
    const firstSeq = eventSeq(records[0])
    let tail = records.filter((r) => eventSeq(r) > after)
    let complete = records.length === 0 ? snapshot.cursor <= after || !snapshot.hasMore : firstSeq <= after + 1
    // Fill a gap between afterSeq and the snapshot window with history pages (bounded).
    let before = firstSeq
    for (let i = 0; !complete && before !== undefined && i < 10; i++) {
      const page = await sc.page({ address: { kind: 'session', sessionId: args.sessionId }, throughSeq: snapshot.cursor, beforeSeq: before, maxMessages: 200 }, signal)
      const recs = page.records ?? []
      if (recs.length === 0) break
      tail = [...recs.filter((r) => eventSeq(r) > after), ...tail]
      before = eventSeq(recs[0])
      complete = before <= after + 1 || !page.hasMore
    }
    if (!complete) return snapshot
    const { records: _r, hasMore: _h, ...rest } = snapshot
    return { ...rest, type: 'resume', afterSeq: after, records: tail }
  }

  async function pump(client, subId, ac, args) {
    try {
      const maxMessages = followMaxMessages(args)
      const it = sc.follow({
        address: { kind: 'session', sessionId: args.sessionId }, assistantStream: true,
        maxMessages,
      }, ac.signal)
      let first = true
      for await (let frame of it) {
        if (ac.signal.aborted) break
        if (first && frame?.type === 'snapshot' && args.afterSeq !== undefined) {
          frame = await resumeFrame(frame, args, ac.signal)
        }
        if (first && (frame?.type === 'snapshot' || frame?.type === 'resume')) {
          frame = await fitFollowFrame(frame, {
            sessionId: args.sessionId,
            page: typeof sc.page === 'function' ? sc.page.bind(sc) : undefined,
            signal: ac.signal,
          })
        }
        first = false
        const wire = { ev: 'follow', subId, frame }
        if (Buffer.byteLength(JSON.stringify(wire), 'utf8') > FRAME_HARD_LIMIT) {
          throw new OpError('frame/too-large', `follow frame exceeds ${FRAME_HARD_LIMIT} bytes`)
        }
        client.push(wire)
      }
      if (!ac.signal.aborted) client.push({ ev: 'follow.end', subId })
    } catch (error) {
      if (!ac.signal.aborted) client.push({ ev: 'follow.error', subId, error: { code: error?.code ?? 'internal', message: String(error?.message ?? error) } })
    } finally {
      client.subs.delete(subId)
    }
  }

  return {
    devices, pairing,
    /** Register extra ops; returns a disposer. */
    registerOps(table) {
      Object.assign(extraOps, table)
      return () => { for (const k of Object.keys(table)) if (extraOps[k] === table[k]) delete extraOps[k] }
    },
    hasDevices: () => authedClients().length > 0,
    /** Device ids currently authenticated on a live connection. */
    onlineDeviceIds: () => new Set(authedClients().filter((c) => !c.device.owner).map((c) => c.device.id)),
    /** Revoke a paired device and kick any live connection. */
    revokeDevice(id) {
      const removed = devices.revoke(id)
      for (const c of clients) if (c.device?.id === id) c.close(4403, 'revoked')
      return removed
    },
    /**
     * Attach an iroh (or other) pipe that speaks the same JSON frames.
     * @param {{ send(frame: object): void, close(): void, meta?: object }} pipe
     */
    attachPipe(pipe) {
      let closed = false
      const bound = bindClient({
        push: (m) => { if (!closed) pipe.send(m) },
        close: () => {
          if (closed) return
          closed = true
          pipe.close()
        },
        meta: pipe.meta ?? { transport: 'iroh' },
      })
      return {
        onMessage: (msg, rawLen) => bound.onMessage(msg, rawLen),
        onRemoteClose: () => { closed = true; bound.onRemoteClose() },
        close: (code, reason) => { bound.close(code, reason) },
      }
    },
    noteIrohPath(peer, kind) { if (peer && kind) irohPaths.set(peer, kind) },
    clearIrohPath(peer) { if (peer) irohPaths.delete(peer) },
    irohPath: (peer) => irohPaths.get(peer),
    /** Active iroh peer paths: `{ peer, kind: 'direct'|'relay' }[]`. */
    irohConnections: () => [...irohPaths.entries()].map(([peer, kind]) => ({ peer, kind })),
    /**
     * Aggregate connection type for /pocket/status: prefer direct if any peer
     * is direct, else relay, else null when no path reported yet.
     */
    connectionType() {
      const kinds = [...irohPaths.values()]
      if (kinds.includes('direct')) return 'direct'
      if (kinds.includes('relay')) return 'relay'
      return null
    },
    /**
     * Offer one approval to connected devices.
     * @returns {{ answer: Promise<{outcome: string, by: string}>, close(outcome: string, by: string): void }}
     */
    offerApproval(payload) {
      const approvalId = randomUUID()
      const full = { approvalId, ...payload }
      let resolve
      const answer = new Promise((r) => { resolve = r })
      const close = (outcome, by) => {
        if (!approvals.has(approvalId)) return
        approvals.delete(approvalId)
        broadcast({ ev: 'approval.closed', approvalId, outcome, by })
      }
      approvals.set(approvalId, {
        payload: full,
        settle: (outcome, deviceId) => { resolve({ outcome, by: `device:${deviceId}` }) },
      })
      broadcast({ ev: 'approval', ...full })
      return { approvalId, answer, close }
    },
    /**
     * Offer one user-question batch to connected devices.
     * @returns {{ questionId: string, answer: Promise<{answer: object, by: string}>, close(status: string, by: string): void }}
     */
    offerUserQuestion(payload) {
      const questionId = str(payload?.callId) ? payload.callId : randomUUID()
      const full = { questionId, ...payload }
      let resolve
      const answer = new Promise((r) => { resolve = r })
      const close = (status, by) => {
        if (!userQuestions.has(questionId)) return
        userQuestions.delete(questionId)
        broadcast({ ev: 'user-question.closed', questionId, status, by })
      }
      userQuestions.set(questionId, {
        payload: full,
        settle: (ans, deviceId) => { resolve({ answer: ans, by: `device:${deviceId}` }) },
      })
      broadcast({ ev: 'user-question', ...full })
      // Minimal task event so phones can build a local "waiting for your answer" notification.
      if (str(full.sessionId) && !childSessions.has(full.sessionId)) {
        pushTaskEvent({ sessionId: full.sessionId, kind: 'question', questionId })
      }
      return { questionId, answer, close }
    },
    /**
     * Push a minimal task-completion / question event (sessionId + turn).
     * Phones render notification text locally from session title + kind.
     * @param {{ sessionId: string, turn?: number, kind?: 'done'|'question', questionId?: string }} event
     */
    broadcastTaskEvent(event) {
      pushTaskEvent(event)
    },
    isRootSession(sessionId) {
      return str(sessionId) && !childSessions.has(sessionId)
    },
    hasPendingUserQuestionForSession(sessionId) {
      if (!str(sessionId)) return false
      for (const p of userQuestions.values()) {
        if (p.payload?.sessionId === sessionId) return true
      }
      return false
    },
    /** Settle a pending waterfall offer from `userQuestions.answer` (returns false if none). */
    settleUserQuestion(callId, answer, deviceId = 'phone') {
      const p = userQuestions.get(callId)
      if (!p) return false
      p.settle(answer, deviceId)
      return true
    },
    hasPendingUserQuestion(callId) {
      return userQuestions.has(callId)
    },
    listen: () => new Promise((resolve, reject) => {
      http.once('error', reject)
      http.listen(opts.port ?? 7690, opts.host ?? '127.0.0.1', () => {
        http.off('error', reject)
        const a = http.address()
        resolve(typeof a === 'string' ? a : `${a.family === 'IPv6' ? `[${a.address}]` : a.address}:${a.port}`)
      })
    }),
    address: () => http.address(),
    close: () => new Promise((resolve) => {
      unsub?.()
      for (const c of [...clients]) c.close(1001, 'shutdown')
      for (const ws of wss.clients) ws.terminate()
      wss.close(() => {
        http.close(() => resolve())
      })
    }),
  }
}

function sessionEventPayload(kind, args) {
  switch (kind) {
    case 'added': return { kind, summary: slimSessionSummary(args[0]) }
    case 'removed': return { kind, sessionId: args[0] }
    case 'status': return { kind, sessionId: args[0], running: args[1] }
    case 'activity': return { kind, sessionId: args[0], updatedAt: args[1] }
    case 'error': return { kind, sessionId: args[0], message: args[1] }
    default: return { kind, args }
  }
}
