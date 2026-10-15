import { createHash, randomBytes } from 'node:crypto'
import { request as httpRequest } from 'node:http'
import { request as httpsRequest } from 'node:https'
import { networkInterfaces } from 'node:os'
import { createGzip } from 'node:zlib'
import { WebSocket, WebSocketServer } from 'ws'

export const UI_COOKIE = 'dsh-pocket-web'
export const UI_TICKET_MS = 60_000
export const UI_SESSION_MS = 15 * 60_000
const MAX_GRANTS = 128
const MAX_HTML_BYTES = 8 * 1024 * 1024
const MAX_ACCOUNT_SCRIPT_BYTES = 16 * 1024 * 1024
const MAX_ARTWORK_BYTES = 32 * 1024 * 1024
const SAFE_RESPONSE_HEADERS = ['content-type', 'content-disposition', 'content-range', 'accept-ranges']
const SAFE_REQUEST_HEADERS = ['accept', 'accept-language', 'content-type', 'content-length', 'range', 'last-event-id']
const RECOVERY_KEYS = ['backoffBaseMs', 'backoffFactor', 'backoffMaxMs', 'generationReadyWarnMs', 'generationReadyTimeoutMs']

/** Compress document/module bytes without buffering streams or changing byte ranges. */
function responseStream(req, res, response) {
  const type = String(response.headers['content-type'] ?? '').split(';')[0].trim().toLowerCase()
  if (req.method === 'HEAD' || response.statusCode !== 200 || response.headers['content-encoding']
    || !/^(?:text\/(?:html|css|javascript)|application\/(?:javascript|json|xhtml\+xml))$/u.test(type)) return res
  res.setHeader('vary', 'Accept-Encoding')
  const acceptsGzip = String(req.headers['accept-encoding'] ?? '').split(',').some((entry) => {
    const [name, ...parameters] = entry.trim().toLowerCase().split(';').map((part) => part.trim())
    const quality = parameters.find((part) => part.startsWith('q='))
    return name === 'gzip' && (quality === undefined || Number(quality.slice(2)) > 0)
  })
  if (!acceptsGzip) return res
  res.setHeader('content-encoding', 'gzip')
  const stream = createGzip()
  stream.on('error', () => res.destroy())
  res.once('close', () => stream.destroy())
  stream.pipe(res)
  return stream
}

function unavailable(message = 'Host UI unavailable') {
  return Object.assign(new Error(message), { code: 'ui/unavailable' })
}

/** The caller supplies services, never a URL from a device. TLS verification stays enabled. */
function hostOrigin(webServer) {
  const { protocol, port } = webServer
  if (!['http:', 'https:'].includes(protocol) || !Number.isInteger(port) || port < 1 || port > 65535) throw unavailable()
  let host = webServer.host
  if (host === '0.0.0.0') host = '127.0.0.1'
  if (host === '::' || host === '[::]') host = '::1'
  if (host?.startsWith('[') && host.endsWith(']')) host = host.slice(1, -1)
  const local = new Set(['localhost', '127.0.0.1', '::1'])
  for (const addresses of Object.values(networkInterfaces())) for (const address of addresses ?? []) local.add(address.address)
  if (!local.has(host)) throw unavailable('Host UI requires a local Host listener')
  return `${protocol}//${host.includes(':') ? `[${host}]` : host}:${port}`
}

/** Reject normalization tricks before URL parsing can erase their evidence. */
function requestPath(raw) {
  if (typeof raw !== 'string' || !raw.startsWith('/') || raw.startsWith('//') || /[\\#\p{Cc}\s]/u.test(raw)) return null
  const path = raw.split('?')[0]
  if (/%(?:2e|2f|5c|25|00)/iu.test(path) || path.includes('//') || path.split('/').some((part) => part === '.' || part === '..')) return null
  try {
    const decoded = decodeURIComponent(path)
    if (/[\\\p{Cc}\s]/u.test(decoded)) return null
    const url = new URL(raw, 'http://pocket.invalid')
    for (const name of url.searchParams.keys()) if (/token|authorization|cookie|ticket/iu.test(name)) return null
    return { path: decoded, search: url.search }
  } catch { return null }
}

function allowedPath(path) {
  return ['/', '/index.html', '/favicon.ico', '/favicon.svg', '/favicon-dark.svg', '/manifest.webmanifest'].includes(path)
    || ['/assets/', '/plugins/', '/api/'].some((prefix) => path.startsWith(prefix))
    || path === '/api'
}

/** Strict authority syntax: no userinfo, duplicate Host, or forwarded-host trust. */
export function uiRequestOrigin(req) {
  const authority = req.headers.host
  if (typeof authority !== 'string' || !authority || /[\s/@\\?#,]/u.test(authority)) return null
  try {
    const url = new URL(`${req.socket.encrypted ? 'https:' : 'http:'}//${authority}`)
    if (!/^(?:[a-z0-9.-]+|\[[a-f0-9:.]+\])$/iu.test(url.hostname)) return null
    return url.host.toLowerCase() === authority.toLowerCase() ? url.origin : null
  } catch { return null }
}

function sameSite(req, origin, requireOrigin = false) {
  const incoming = req.headers.origin
  if (incoming !== undefined && incoming !== origin) return false
  if (requireOrigin && incoming !== origin) return false
  const site = req.headers['sec-fetch-site']
  return site === undefined || site === 'none' || site === 'same-origin'
}

function headers(res) {
  res.setHeader('cache-control', 'no-store')
  res.setHeader('referrer-policy', 'no-referrer')
  res.setHeader('x-content-type-options', 'nosniff')
  res.setHeader('x-frame-options', 'DENY')
}
function reject(res, status = 403) {
  if (res.destroyed) return
  if (res.headersSent) { res.destroy(); return }
  headers(res)
  res.writeHead(status, { 'content-type': 'text/plain; charset=utf-8' }).end(status === 401 ? 'UI session required' : 'UI request denied')
}
function rejectUpgrade(socket, status = 403) {
  socket.end(`HTTP/1.1 ${status} Denied\r\nConnection: close\r\nCache-Control: no-store\r\nContent-Length: 0\r\n\r\n`)
}

/** Keep the official module graph; upstream credentials and transport endpoints stay server-side. */
function browserDocument(html, secrets) {
  const applicationPreloads = new Set()
  let sanitized = html.replace(/<script\b([^>]*)>([\s\S]*?)<\/script\s*>/giu, (tag, attributes, source) => {
    if (/\bsrc\s*=/iu.test(attributes)) return tag
    if (/__DSH_TRANSPORT__|__DSH_NATIVE__|__DSH_DESKTOP__|\b(?:dshDesktop|dshPlatform|dshNative)\b/u.test(source)) return ''
    const bootAssignment = /^\s*(?:globalThis|window)(?:\["__DSH_BOOT__"\]|\.__DSH_BOOT__)\s*=\s*(\{[\s\S]*\})\s*;?\s*$/u.exec(source)
    if (bootAssignment) {
      const graph = JSON.parse(bootAssignment[1])
      if (Array.isArray(graph.entries) && Array.isArray(graph.batches)) {
        const entries = new Map(graph.entries.map(entry => [entry.id, entry]))
        graph.batches = graph.batches.flatMap((batch) => {
          if (batch.phase !== 'application') return [batch]
          applicationPreloads.add(batch.url.replace(/^\.\//u, ''))
          return batch.entries.map((id) => {
            const entry = entries.get(id)
            if (!entry) throw unavailable('Host UI module graph is incomplete')
            return { ...batch, url: entry.url, rev: entry.rev, entries: [id] }
          })
        })
        return `<script>globalThis.__DSH_BOOT__=${JSON.stringify(graph).replace(/</gu, '\\u003c')}</script>`
      }
    }
    if (!source.includes('__DSH_CONNECTION_RECOVERY__')) return tag
    // Official renderIndex emits one JSON global per script. Keep only numeric timing fields.
    const assignment = /^(?:\s*)(?:globalThis|window)(?:\["__DSH_CONNECTION_RECOVERY__"\]|\.__DSH_CONNECTION_RECOVERY__)\s*=\s*(\{[\s\S]*\})\s*;?\s*$/u.exec(source)
    const safe = {}
    if (assignment) {
      try {
        const recovery = JSON.parse(assignment[1])
        for (const key of RECOVERY_KEYS) if (Number.isFinite(recovery[key]) && recovery[key] > 0) safe[key] = recovery[key]
      } catch { /* Unknown recovery format gets official browser defaults, not Host credentials. */ }
    }
    return `<script>globalThis.__DSH_CONNECTION_RECOVERY__=${JSON.stringify(safe)}</script>`
  })
  // Large combo preloads would still compete with the per-module boot requests.
  sanitized = sanitized.replace(/<link\b[^>]*>/giu, (tag) => {
    if (!/\brel=["']preload["']/iu.test(tag)) return tag
    const href = /\bhref=["']([^"']*)["']/iu.exec(tag)?.[1].replaceAll('&amp;', '&').replace(/^\.\//u, '')
    return applicationPreloads.has(href) ? '' : tag
  })
  // Fail closed if a future injection moves a process credential outside recovery.
  for (const secret of secrets) {
    if (secret && [secret, encodeURIComponent(secret), JSON.stringify(secret).slice(1, -1)].some((value) => sanitized.includes(value))) throw unavailable()
  }
  // Only authenticated, device-bound proxy responses reach this function. This
  // shell already grants Host settings RPCs; use the official desktop transport
  // capability instead of letting a LAN/Tailscale hostname disable those forms.
  // Never inherit upstream hooks, URLs, cookies, or recovery ownership.
  const head = /<head\b[^>]*>/iu
  if (!head.test(sanitized)) throw unavailable()
  return sanitized.replace(head, '$&<script>globalThis.__DSH_TRANSPORT__={ownsHost:true}</script>')
}

/** One live service scope. Grants belong to the authenticated client object, not its token. */
export function createHostUi({ webServer, connection, devices, isClientLive, now = Date.now, ticketMs = UI_TICKET_MS, sessionMs = UI_SESSION_MS }) {
  const origin = hostOrigin(webServer)
  const tickets = new Map()
  const sessions = new Map()
  const artwork = new Map()
  let artworkBytes = 0
  const mux = new WebSocketServer({ noServer: true, maxPayload: 32 * 1024 * 1024, perMessageDeflate: false })
  mux.on('headers', (outgoing) => {
    outgoing.push('Cache-Control: no-store', 'Referrer-Policy: no-referrer')
  })
  const backendAbort = new AbortController()
  let disposed = false
  let authentication
  let cookie = ''
  let secrets = []

  const live = (client) => !disposed && isClientLive(client) && client.device
    && (client.device.owner || devices.list().some((device) => device.id === client.device.id))
  function remove(map, key) {
    const grant = map.get(key)
    if (!grant) return
    map.delete(key)
    clearTimeout(grant.timer)
    for (const stop of grant.resources ?? []) stop()
    grant.resources?.clear()
  }
  function revoke(client, sourceTicket) {
    for (const [key, grant] of tickets) if (grant.client === client && (sourceTicket === undefined || key === sourceTicket)) remove(tickets, key)
    for (const [key, grant] of sessions) if (grant.client === client && (sourceTicket === undefined || grant.sourceTicket === sourceTicket)) remove(sessions, key)
  }
  function reap() {
    for (const map of [tickets, sessions]) for (const [key, grant] of map) {
      if (grant.expires <= now() || !live(grant.client)) remove(map, key)
    }
  }
  // Also catch store revocation by callers outside the bridge. Never authenticate() on asset requests.
  const sweep = setInterval(reap, 1_000)
  sweep.unref?.()

  async function authenticate() {
    if (disposed) throw unavailable()
    if (cookie) return cookie
    if (!authentication) authentication = (async () => {
      const url = new URL(connection.authenticatedUrl(`${origin}/`))
      if (url.origin !== origin || url.pathname !== '/' || url.username || url.password) throw unavailable()
      const launchSecrets = [...url.searchParams.values()]
      const response = await fetch(url, { redirect: 'manual', signal: AbortSignal.any([backendAbort.signal, AbortSignal.timeout(10_000)]) })
      const issued = response.headers.getSetCookie?.() ?? [response.headers.get('set-cookie')].filter(Boolean)
      await response.body?.cancel()
      if (disposed || response.status !== 303 || issued.length !== 1) throw unavailable()
      const value = issued[0].split(';')[0]
      if (!/^[^=\s;]+=[^;\r\n]+$/u.test(value)) throw unavailable()
      cookie = value
      secrets = [...launchSecrets, value, value.slice(value.indexOf('=') + 1)]
      return cookie
    })().finally(() => { authentication = undefined })
    return authentication
  }

  // These official PNG imports are used only as <img src> URLs. Keep their
  // original pixels, but do not make all eight onboarding images block boot.
  function accountArtwork(source) {
    const prefix = /(\b(?:var|let|const)\s+onboarding_(?:welcome|recharge)[\w$]*_default(?:\$\d+)?\s*=\s*)(["'])data:image\/png;base64,/gu
    const parts = []
    let cursor = 0
    for (const match of source.matchAll(prefix)) {
      const start = match.index + match[0].length
      const end = source.indexOf(match[2], start)
      if (end < start + 65_536) continue
      const encoded = source.slice(start, end)
      if (/[^A-Za-z\d+/=]/u.test(encoded)) continue
      const path = `/assets/pocket-artwork/${createHash('sha256').update(encoded).digest('hex')}.png`
      if (!artwork.has(path)) {
        const bytes = Buffer.from(encoded, 'base64')
        if (artworkBytes + bytes.length > MAX_ARTWORK_BYTES) continue
        artwork.set(path, bytes)
        artworkBytes += bytes.length
      }
      parts.push(source.slice(cursor, match.index), match[1], match[2], path, match[2])
      cursor = end + 1
    }
    if (cursor === 0) return source
    parts.push(source.slice(cursor))
    return parts.join('')
  }

  function authorized(req) {
    const requestOrigin = uiRequestOrigin(req)
    if (!requestOrigin || !sameSite(req, requestOrigin)) return null
    const values = new Set((req.headers.cookie ?? '').split(';').map((part) => part.trim())
      .filter((part) => part.startsWith(`${UI_COOKIE}=`)).map((part) => part.slice(UI_COOKIE.length + 1)))
    const matches = []
    for (const key of values) {
      const session = sessions.get(key)
      if (!session) continue
      if (session.expires <= now() || !live(session.client)) { remove(sessions, key); continue }
      if (session.origin === requestOrigin) matches.push(session)
    }
    // Ignore unrelated path-scoped cookie pollution, but never guess between live sessions.
    return matches.length === 1 ? matches[0] : null
  }

  async function exchange(req, res) {
    const requestOrigin = uiRequestOrigin(req)
    if (req.method !== 'POST' || !requestOrigin || !sameSite(req, requestOrigin)
      || req.headers['content-type']?.split(';')[0].trim().toLowerCase() !== 'application/x-www-form-urlencoded') { reject(res); return }
    let body = ''
    for await (const chunk of req) {
      body += chunk.toString('utf8')
      if (body.length > 256) { reject(res, 413); return }
    }
    const form = new URLSearchParams(body)
    if (form.size !== 1 || !form.has('ticket')) { reject(res); return }
    const key = form.get('ticket')
    const grant = tickets.get(key)
    if (!grant || grant.expires <= now() || !live(grant.client) || grant.origin !== requestOrigin) { reject(res, 401); return }
    remove(tickets, key)
    if (sessions.size >= MAX_GRANTS) { reject(res, 429); return }
    const id = randomBytes(32).toString('base64url')
    const session = { client: grant.client, sourceTicket: key, origin: requestOrigin, expires: now() + sessionMs, resources: new Set() }
    session.timer = setTimeout(() => remove(sessions, id), sessionMs)
    session.timer.unref?.()
    sessions.set(id, session)
    headers(res)
    res.setHeader('set-cookie', `${UI_COOKIE}=${id}; Path=/; HttpOnly; SameSite=Strict; Max-Age=${Math.floor(sessionMs / 1000)}${requestOrigin.startsWith('https:') ? '; Secure' : ''}`)
    res.writeHead(303, { location: grant.target }).end()
  }

  async function proxy(req, res, session) {
    const controller = new AbortController()
    const stop = () => { controller.abort(); res.destroy() }
    session.resources.add(stop)
    res.once('close', () => { controller.abort(); session.resources.delete(stop) })
    const hostCookie = await authenticate()
    if (controller.signal.aborted || !live(session.client)) return
    const outgoing = { cookie: hostCookie, 'accept-encoding': 'identity' }
    for (const name of SAFE_REQUEST_HEADERS) if (req.headers[name] !== undefined) outgoing[name] = req.headers[name]
    // A browser Origin is only translated after the exact external-origin check.
    if (req.headers.origin !== undefined) outgoing.origin = origin
    const target = new URL(req.url, origin)
    const request = (target.protocol === 'https:' ? httpsRequest : httpRequest)(target, {
      method: req.method, headers: outgoing, signal: controller.signal,
    }, (response) => {
      void (async () => {
        if (response.statusCode >= 300 && response.statusCode < 400) { response.destroy(); reject(res, 502); return }
        headers(res)
        for (const name of SAFE_RESPONSE_HEADERS) {
          const value = response.headers[name]
          if (value === undefined) continue
          if (secrets.some((secret) => secret && String(value).includes(secret))) throw unavailable()
          res.setHeader(name, value)
        }
        const html = /(?:text\/html|application\/xhtml\+xml)/iu.test(response.headers['content-type'] ?? '')
        if (html) {
          const websocketOrigin = session.origin.replace(/^http/u, 'ws')
          res.setHeader('content-security-policy', `connect-src 'self' ${websocketOrigin}; form-action 'self'; base-uri 'self'; object-src 'none'; frame-src 'self'; frame-ancestors 'none'`)
        }
        if (html && req.method !== 'HEAD') {
          if (response.headers['content-encoding'] && response.headers['content-encoding'] !== 'identity') throw unavailable()
          const chunks = []
          let size = 0
          for await (const chunk of response) {
            size += chunk.length
            if (size > MAX_HTML_BYTES) throw unavailable()
            chunks.push(chunk)
          }
          const body = browserDocument(Buffer.concat(chunks).toString('utf8'), secrets)
          const output = responseStream(req, res, response)
          res.writeHead(response.statusCode ?? 502)
          output.end(body)
        } else if (req.method !== 'HEAD' && response.statusCode === 200
          && req.url.includes('@deepseek-ai/dsh-client-ui-settings-account/client.js')
          && /(?:java|ecma)script/iu.test(response.headers['content-type'] ?? '')) {
          if (response.headers['content-encoding'] && response.headers['content-encoding'] !== 'identity') throw unavailable()
          const chunks = []
          let size = 0
          for await (const chunk of response) {
            size += chunk.length
            if (size > MAX_ACCOUNT_SCRIPT_BYTES) throw unavailable()
            chunks.push(chunk)
          }
          const body = accountArtwork(Buffer.concat(chunks).toString('utf8'))
          const output = responseStream(req, res, response)
          res.writeHead(200)
          output.end(body)
        } else {
          if (response.headers['content-encoding']) res.setHeader('content-encoding', response.headers['content-encoding'])
          const output = responseStream(req, res, response)
          res.writeHead(response.statusCode ?? 502)
          response.on('error', () => res.destroy())
          response.pipe(output)
        }
      })().catch(() => { response.destroy(); reject(res, 502) })
    })
    request.on('error', () => reject(res, 502))
    req.on('aborted', () => controller.abort())
    req.pipe(request)
  }

  return {
    open(args, client) {
      if (!live(client) || !client.meta.uiOrigin) throw unavailable()
      if (args.section !== undefined && args.section !== null && (typeof args.section !== 'string' || args.section.length > 256)) throw Object.assign(new Error('section must be a string'), { code: 'bad-request' })
      reap()
      revoke(client)
      if (tickets.size >= MAX_GRANTS || sessions.size >= MAX_GRANTS) throw unavailable('Too many UI sessions')
      const ticket = randomBytes(32).toString('base64url')
      const params = new URLSearchParams({ 'pocket-ui': '1' })
      if (args.section) params.set('section', args.section)
      const grant = { client, origin: client.meta.uiOrigin, target: `/?${params}`, expires: now() + ticketMs }
      grant.timer = setTimeout(() => remove(tickets, ticket), ticketMs)
      grant.timer.unref?.()
      tickets.set(ticket, grant)
      return { ticket, path: '/pocket-ui/open' }
    },
    revoke,
    async http(req, res) {
      try {
        const parsed = requestPath(req.url)
        if (!parsed) { reject(res); return }
        if (parsed.path === '/pocket-ui/open' && !parsed.search) { await exchange(req, res); return }
        if (!allowedPath(parsed.path)) { reject(res); return }
        const session = authorized(req)
        if (!session) { reject(res, 401); return }
        if (!['GET', 'HEAD'].includes(req.method) && !sameSite(req, session.origin, true)) { reject(res); return }
        if (parsed.path.startsWith('/assets/pocket-artwork/')) {
          const bytes = artwork.get(parsed.path)
          if (!bytes || !['GET', 'HEAD'].includes(req.method)) { reject(res, 404); return }
          const stop = () => res.destroy()
          session.resources.add(stop)
          res.once('close', () => session.resources.delete(stop))
          headers(res)
          res.writeHead(200, { 'content-type': 'image/png', 'content-length': bytes.length })
          res.end(req.method === 'HEAD' ? undefined : bytes)
          return
        }
        await proxy(req, res, session)
      } catch { reject(res, 502) }
    },
    async upgrade(req, socket, head) {
      const parsed = requestPath(req.url)
      const session = parsed?.path === '/api/remote.mux' ? authorized(req) : null
      if (!session || !sameSite(req, session.origin, true)) { rejectUpgrade(socket, 401); return }
      let upstream
      let downstream
      const stop = () => { upstream?.terminate(); downstream?.terminate(); socket.destroy() }
      session.resources.add(stop)
      socket.once('close', () => { upstream?.terminate(); session.resources.delete(stop) })
      socket.on('error', stop)
      try {
        const hostCookie = await authenticate()
        if (socket.destroyed || !live(session.client)) { stop(); return }
        const url = new URL(req.url, origin)
        url.protocol = url.protocol === 'https:' ? 'wss:' : 'ws:'
        const protocols = req.headers['sec-websocket-protocol']?.split(',').map((value) => value.trim()) ?? []
        upstream = new WebSocket(url, protocols, { headers: { cookie: hostCookie, origin }, followRedirects: false, handshakeTimeout: 10_000, maxPayload: 32 * 1024 * 1024, perMessageDeflate: false })
        upstream.on('error', stop)
        upstream.on('close', stop)
        upstream.once('open', () => {
          if (socket.destroyed || !live(session.client)) { stop(); return }
          // Only return a protocol actually selected by the Host.
          if (upstream.protocol) req.headers['sec-websocket-protocol'] = upstream.protocol
          else delete req.headers['sec-websocket-protocol']
          try {
            mux.handleUpgrade(req, socket, head, (ws) => {
              downstream = ws
              ws.on('error', stop)
              ws.on('close', stop)
              ws.on('message', (data, binary) => { if (upstream.readyState === WebSocket.OPEN) upstream.send(data, { binary }) })
              upstream.on('message', (data, binary) => { if (ws.readyState === WebSocket.OPEN) ws.send(data, { binary }) })
            })
          } catch { stop() }
        })
      } catch { stop() }
    },
    dispose() {
      if (disposed) return
      disposed = true
      backendAbort.abort()
      clearInterval(sweep)
      for (const map of [tickets, sessions]) for (const key of map.keys()) remove(map, key)
      cookie = ''
      secrets = []
      artwork.clear()
      artworkBytes = 0
      mux.close()
    },
  }
}
