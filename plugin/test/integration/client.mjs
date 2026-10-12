// Minimal smoke client: node test/integration/client.mjs ws://127.0.0.1:7691 <token>
import WebSocket from 'ws'
const [url, token] = process.argv.slice(2)
const ws = new WebSocket(url)
let id = 0; const pend = new Map(); const events = []
ws.on('message', (d) => { const m = JSON.parse(d); if (m.id && pend.has(m.id)) { pend.get(m.id)(m); pend.delete(m.id) } else events.push(m) })
const call = (op, args = {}) => new Promise((r) => { const i = ++id; pend.set(i, r); ws.send(JSON.stringify({ id: i, op, args })) })
const out = (k, v) => console.log(k, JSON.stringify(v).slice(0, 400))
ws.on('open', async () => {
  out('hello', await call('hello', { proto: 1, token }))
  out('list', await call('sessions.list'))
  const c = await call('sessions.create', { cwd: process.cwd() }); out('create', c)
  const sid = c.result?.sessionId
  out('rename', await call('sessions.rename', { sessionId: sid, title: 'pocket smoke' }))
  out('follow', await call('follow', { sessionId: sid }))
  await new Promise((r) => setTimeout(r, 1500))
  out('frames', events.filter((e) => e.ev === 'follow').map((e) => e.frame.type))
  out('models', await call('models.catalog'))
  out('cancel', await call('cancel', { sessionId: sid }))
  out('list2', await call('sessions.list'))
  out('session-events', events.filter((e) => e.ev === 'session'))
  ws.close()
})
