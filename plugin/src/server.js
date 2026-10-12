import { randomUUID, timingSafeEqual, createHash } from 'node:crypto'
import { createServer } from 'node:http'
import { WebSocketServer } from 'ws'

export const PROTO = 1
const PRE_AUTH_MAX = 64 * 1024
const MAX_PAYLOAD = 32 * 1024 * 1024
const HELLO_TIMEOUT_MS = 10_000

const digest = (s) => createHash('sha256').update(String(s)).digest()
const tokenOk = (got, want) => typeof got === 'string' && timingSafeEqual(digest(got), digest(want))

class OpError extends Error {
  constructor(code, message) { super(message); this.code = code }
}
const need = (cond, msg) => { if (!cond) throw new OpError('bad-request', msg) }
const str = (v) => typeof v === 'string' && v.length > 0

/** Build the op table over an in-process sessionController. */
export function createOps(sc, { timeZone } = {}) {
  const tz = timeZone ?? Intl.DateTimeFormat().resolvedOptions().timeZone
  return {
    'sessions.list': (a, signal) => sc.list({ ...(str(a.cursor) ? { cursor: a.cursor } : {}) }, signal),
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
 *   onSessionEvent?: (fn: (kind: string, args: unknown[]) => void) => () => void,
 *   log?: (...a: unknown[]) => void, heartbeatMs?: number, idleMs?: number,
 *   info?: object}} opts
 */
export function createPocketServer(opts) {
  const { sessionController: sc, token } = opts

  const heartbeatMs = opts.heartbeatMs ?? 20_000
  const idleMs = opts.idleMs ?? 60_000
  const ops = createOps(sc)
  const http = createServer((req, res) => {
    res.writeHead(426, { 'content-type': 'text/plain' }).end('websocket required')
  })
  const wss = new WebSocketServer({ server: http, maxPayload: MAX_PAYLOAD })
  const clients = new Set()
  const unsub = opts.onSessionEvent?.((kind, args) => {
    const ev = sessionEventPayload(kind, args)
    for (const c of clients) if (c.authed) c.push({ ev: 'session', ...ev })
  })

  wss.on('connection', (ws) => {
    const client = {
      authed: false, subs: new Map(), lastSeen: Date.now(), nextSub: 1,
      push: (m) => { if (ws.readyState === ws.OPEN) ws.send(JSON.stringify(m)) },
    }
    clients.add(client)
    const helloTimer = setTimeout(() => { if (!client.authed) ws.close(4401, 'hello timeout') }, HELLO_TIMEOUT_MS)
    const beat = setInterval(() => {
      if (!client.authed) return
      if (Date.now() - client.lastSeen > idleMs) { ws.terminate(); return }
      client.push({ ev: 'ping', t: Date.now() })
    }, heartbeatMs)
    ws.on('close', () => {
      clearTimeout(helloTimer); clearInterval(beat); clients.delete(client)
      for (const ac of client.subs.values()) ac.abort()
    })
    ws.on('message', (raw, isBinary) => {
      client.lastSeen = Date.now()
      if (isBinary) { ws.close(1003, 'text only'); return }
      if (!client.authed && raw.length > PRE_AUTH_MAX) { ws.close(1009, 'too large'); return }
      let msg
      try { msg = JSON.parse(raw.toString('utf8')) } catch { ws.close(1007, 'bad json'); return }
      if (msg === null || typeof msg !== 'object') { ws.close(1007, 'bad frame'); return }
      if (msg.op === 'pong') return
      const reply = (body) => client.push({ id: msg.id, ...body })
      const fail = (code, message) => reply({ ok: false, error: { code, message } })
      if (!client.authed) {
        if (msg.op !== 'hello' || !tokenOk(msg.args?.token, token)) {
          fail('auth/denied', 'hello with a valid token required'); ws.close(4401, 'unauthorized'); return
        }
        if (msg.args?.proto !== PROTO) { fail('proto/unsupported', `proto ${PROTO} required`); ws.close(4400, 'proto'); return }
        client.authed = true; clearTimeout(helloTimer)
        reply({ ok: true, result: { proto: PROTO, ...(opts.info ?? {}), ops: [...Object.keys(ops), 'follow', 'unfollow'] } })
        return
      }
      void handle(client, msg, reply, fail)
    })
  })

  async function handle(client, msg, reply, fail) {
    const args = msg.args && typeof msg.args === 'object' ? msg.args : {}
    try {
      if (msg.op === 'follow') {
        need(str(args.sessionId), 'sessionId required')
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
      const op = Object.hasOwn(ops, msg.op) ? ops[msg.op] : undefined
      if (op === undefined) { fail('unknown-op', `unknown op ${JSON.stringify(msg.op)}`); return }
      const result = await op(args, AbortSignal.timeout(120_000))
      reply({ ok: true, result: result ?? null })
    } catch (error) {
      fail(error?.code ?? 'internal', String(error?.message ?? error))
    }
  }

  async function pump(client, subId, ac, args) {
    try {
      const it = sc.follow({
        address: { kind: 'session', sessionId: args.sessionId }, assistantStream: true,
        ...(Number.isSafeInteger(args.maxMessages) ? { maxMessages: args.maxMessages } : {}),
      }, ac.signal)
      for await (const frame of it) {
        if (ac.signal.aborted) break
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
      for (const ws of wss.clients) ws.terminate()
      wss.close(); http.close(() => resolve())
    }),
  }
}

function sessionEventPayload(kind, args) {
  switch (kind) {
    case 'added': return { kind, summary: args[0] }
    case 'removed': return { kind, sessionId: args[0] }
    case 'status': return { kind, sessionId: args[0], running: args[1] }
    case 'activity': return { kind, sessionId: args[0], updatedAt: args[1] }
    case 'error': return { kind, sessionId: args[0], message: args[1] }
    default: return { kind, args }
  }
}
