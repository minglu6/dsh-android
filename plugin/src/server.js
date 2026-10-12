import { randomUUID } from 'node:crypto'
import { createPairing } from './pairing.js'
import { createDeviceStore } from './devices.js'
import { createServer } from 'node:http'
import { WebSocketServer } from 'ws'

export const PROTO = 1
const PRE_AUTH_MAX = 64 * 1024
const MAX_PAYLOAD = 32 * 1024 * 1024
const HELLO_TIMEOUT_MS = 10_000
/** Default follow window when the client omits maxMessages (phone must stay under iroh MAX_LINE). */
export const DEFAULT_FOLLOW_MAX_MESSAGES = 50
/** Soft budget for an encoded follow push line; leave headroom under pocket-iroh MAX_LINE (1 MiB). */
export const FRAME_SOFT_LIMIT = 900 * 1024
/** Hard ceiling — must match plugin/iroh/pocket-iroh/src/lib.rs MAX_LINE. */
export const FRAME_HARD_LIMIT = 1024 * 1024

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
const eventSeq = (rec) => rec?.event?.seq

class OpError extends Error {
  constructor(code, message) { super(message); this.code = code }
}
const need = (cond, msg) => { if (!cond) throw new OpError('bad-request', msg) }
const str = (v) => typeof v === 'string' && v.length > 0

/** Byte length of a follow push envelope (what crosses the iroh line reader). */
export function followPushBytes(frame, subId = 's0') {
  return Buffer.byteLength(JSON.stringify({ ev: 'follow', subId, frame }), 'utf8')
}

function truncateText(text, maxChars) {
  if (typeof text !== 'string' || text.length <= maxChars) return text
  return `${text.slice(0, maxChars)}\n…[truncated]`
}

function truncateContentBlocks(blocks, maxChars) {
  if (!Array.isArray(blocks)) return blocks
  return blocks.map((b) => {
    if (!b || typeof b !== 'object') return b
    if (b.type === 'text' && typeof b.text === 'string' && b.text.length > maxChars) {
      return { ...b, text: truncateText(b.text, maxChars) }
    }
    if (b.type === 'reasoning' && typeof b.text === 'string' && b.text.length > maxChars) {
      return { ...b, text: truncateText(b.text, maxChars) }
    }
    return b
  })
}

/**
 * Shrink huge tool outputs / text blocks inside a snapshot|resume frame so the
 * encoded push stays under the iroh line cap. Full text is available later via
 * history.page when the client expands or pages.
 */
export function truncateToolOutputsInFrame(frame, maxChars = 8_000) {
  if (!frame || typeof frame !== 'object' || !Array.isArray(frame.records)) return frame
  const records = frame.records.map((rec) => {
    if (rec?.type !== 'event' || !rec.event || typeof rec.event !== 'object') return rec
    const ev = rec.event
    const data = ev.data
    if (!data || typeof data !== 'object') return rec
    if (ev.type === 'tool/result' && data.message && typeof data.message === 'object') {
      const content = truncateContentBlocks(data.message.content, maxChars)
      return { ...rec, event: { ...ev, data: { ...data, message: { ...data.message, content } } } }
    }
    if (ev.type === 'assistant/message' && data.message && typeof data.message === 'object') {
      const content = truncateContentBlocks(data.message.content, maxChars)
      return { ...rec, event: { ...ev, data: { ...data, message: { ...data.message, content } } } }
    }
    if (ev.type === 'user/message') {
      const msg = data.content != null ? data : data.message
      if (msg && typeof msg === 'object' && Array.isArray(msg.content)) {
        const content = truncateContentBlocks(msg.content, maxChars)
        if (data.content != null) return { ...rec, event: { ...ev, data: { ...data, content } } }
        return { ...rec, event: { ...ev, data: { ...data, message: { ...msg, content } } } }
      }
    }
    return rec
  })
  return { ...frame, records }
}

/**
 * Ensure a follow frame fits under FRAME_SOFT_LIMIT (and never over FRAME_HARD_LIMIT):
 * prefer a smaller history window, then truncate inline tool/text payloads.
 */
export async function fitFollowFrame(sc, frame, args, signal, softLimit = FRAME_SOFT_LIMIT) {
  if (!frame || (frame.type !== 'snapshot' && frame.type !== 'resume')) return frame
  let fitted = frame
  let size = followPushBytes(fitted)
  if (size <= softLimit) return fitted

  let maxMsg = Number.isSafeInteger(args.maxMessages) ? args.maxMessages : DEFAULT_FOLLOW_MAX_MESSAGES
  const throughSeq = Number.isSafeInteger(frame.cursor) ? frame.cursor : Number.MAX_SAFE_INTEGER
  while (size > softLimit && maxMsg > 5 && typeof sc?.page === 'function') {
    maxMsg = Math.max(5, Math.floor(maxMsg / 2))
    const page = await sc.page({
      address: { kind: 'session', sessionId: args.sessionId },
      throughSeq,
      maxMessages: maxMsg,
    }, signal)
    const records = page?.records ?? []
    // Shrinking the window means older history exists even if the page flag is false.
    const hasMore = page?.hasMore === true || records.length < (frame.records?.length ?? 0) || frame.hasMore === true
    fitted = { ...fitted, type: fitted.type === 'resume' ? 'resume' : 'snapshot', records, hasMore }
    size = followPushBytes(fitted)
  }

  let textCap = 8_000
  while (size > softLimit && textCap >= 250) {
    fitted = truncateToolOutputsInFrame(fitted, textCap)
    size = followPushBytes(fitted)
    if (size <= softLimit) break
    textCap = Math.floor(textCap / 2)
  }

  if (size > FRAME_HARD_LIMIT) {
    throw new OpError('frame-too-large', `follow frame exceeds MAX_LINE (${size} bytes)`)
  }
  return fitted
}

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
    prompt: (a, signal) => {
      need(str(a.sessionId), 'sessionId required')
      need(a.mode === 'queue' || a.mode === 'steer', 'mode must be queue or steer')
      const content = []
      if (str(a.text)) content.push({ type: 'text', text: a.text })
      for (const img of Array.isArray(a.images) ? a.images : []) {
        need(str(img?.mediaType) && str(img?.data), 'image needs mediaType and data')
        content.push({ type: 'image', mediaType: img.mediaType, data: img.data, ...(str(img.name) ? { name: img.name } : {}) })
      }
      need(content.length > 0, 'text or images required')
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
  const heartbeatMs = opts.heartbeatMs ?? 20_000
  const idleMs = opts.idleMs ?? 60_000
  const pairFailDelayMs = opts.pairFailDelayMs ?? 200
  const ops = createOps(sc)
  const http = createServer((req, res) => {
    res.writeHead(426, { 'content-type': 'text/plain' }).end('websocket required')
  })
  const wss = new WebSocketServer({ server: http, maxPayload: MAX_PAYLOAD })
  const clients = new Set()
  /** approvalId -> { payload, settle(outcome) } */
  const approvals = new Map()
  const authedClients = () => [...clients].filter((c) => c.device)
  const broadcast = (m) => { for (const c of authedClients()) c.push(m) }
  const unsub = opts.onSessionEvent?.((kind, args) => broadcast({ ev: 'session', ...sessionEventPayload(kind, args) }))

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
    // Phone clients that omit maxMessages used to get the full dsh snapshot (often
    // multi-MB). Cap at 50 and keep dsh hasMore/cursor so the app can page older history.
    const maxMessages = Number.isSafeInteger(args.maxMessages) ? args.maxMessages : DEFAULT_FOLLOW_MAX_MESSAGES
    const followArgs = { ...args, maxMessages }
    try {
      const it = sc.follow({
        address: { kind: 'session', sessionId: args.sessionId }, assistantStream: true,
        maxMessages,
      }, ac.signal)
      let first = true
      for await (let frame of it) {
        if (ac.signal.aborted) break
        if (first && frame?.type === 'snapshot' && args.afterSeq !== undefined) frame = await resumeFrame(frame, followArgs, ac.signal)
        if (frame?.type === 'snapshot' || frame?.type === 'resume') {
          frame = await fitFollowFrame(sc, frame, followArgs, ac.signal)
        }
        first = false
        client.push({ ev: 'follow', subId, frame })
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
    irohPath: (peer) => irohPaths.get(peer),
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
