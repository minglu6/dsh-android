import { randomUUID } from 'node:crypto'
import { isTrustedRequest } from './trust.js'

const json = (res, status, body) => {
  res.writeHead(status, { 'content-type': 'application/json; charset=utf-8', 'cache-control': 'no-store' })
  res.end(JSON.stringify(body))
}

/** Same local-operator trust fence as the existing Pocket management routes. */
export function registerCompletionRoutes(ctx, tracker) {
  ctx.effect(() => {
    const clients = new Map()
    const gate = (req, res) => {
      if (isTrustedRequest(req)) return true
      json(res, 403, { error: 'forbidden' })
      return false
    }
    const unregisterEvents = ctx.webServer.register({
      kind: 'exact', path: '/pocket/completion/events',
      handler: (req, res) => {
        if (!gate(req, res)) return
        if (req.method !== 'GET') return json(res, 405, { error: 'method not allowed' })
        const clientId = randomUUID()
        let closed = false
        let unsubscribe = () => {}
        let heartbeat
        const close = () => {
          if (closed) return
          closed = true
          clearInterval(heartbeat)
          unsubscribe()
          clients.delete(clientId)
          tracker.disconnect(clientId)
          res.end()
        }
        const send = (snapshot) => {
          if (closed) return
          // Disconnect slow consumers instead of retaining unbounded snapshots.
          if (!res.write(`data: ${JSON.stringify({ clientId, ...snapshot })}\n\n`)) close()
        }
        clients.set(clientId, close)
        res.on('close', close)
        res.on('error', close)
        res.writeHead(200, {
          'content-type': 'text/event-stream; charset=utf-8',
          'cache-control': 'no-store',
          'x-accel-buffering': 'no',
        })
        unsubscribe = tracker.subscribe(send)
        send(tracker.snapshot())
        if (!closed) {
          heartbeat = setInterval(() => { if (!res.write(': keepalive\n\n')) close() }, 30_000)
          heartbeat.unref()
        }
      },
    })
    const unregisterView = ctx.webServer.register({
      kind: 'exact', path: '/pocket/completion/view',
      handler: async (req, res) => {
        if (!gate(req, res)) return
        if (req.method !== 'POST') return json(res, 405, { error: 'method not allowed' })
        let body = ''
        try {
          for await (const chunk of req) {
            body += chunk
            if (body.length > 4096) return json(res, 413, { error: 'too large' })
          }
          body = JSON.parse(body)
        } catch {
          return json(res, 400, { error: 'invalid json' })
        }
        if (!body || typeof body.clientId !== 'string'
          || !(body.sessionId === null || (typeof body.sessionId === 'string' && body.sessionId.length > 0))) {
          return json(res, 400, { error: 'clientId and sessionId (string or null) required' })
        }
        // Checked after reading the body: the SSE may have disconnected meanwhile.
        if (!clients.has(body.clientId)) return json(res, 409, { error: 'completion/client-expired' })
        return json(res, 200, tracker.view(body.clientId, body.sessionId))
      },
    })
    return () => {
      unregisterEvents()
      unregisterView()
      for (const close of clients.values()) close()
    }
  })
}
