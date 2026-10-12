#!/usr/bin/env node
// CLI: node bin/pocket.js pair|devices|revoke <id>  [--url ws://127.0.0.1:7690] [--token-file ~/.dsh/pocket/token]
import { readFileSync } from 'node:fs'
import { homedir } from 'node:os'
import { join } from 'node:path'
import WebSocket from 'ws'

const argv = process.argv.slice(2)
const flag = (n, d) => { const i = argv.indexOf(n); return i >= 0 ? argv.splice(i, 2)[1] : d }
const url = flag('--url', 'ws://127.0.0.1:7690')
const tokenFile = flag('--token-file', join(process.env.DSH_HOME ?? join(homedir(), '.dsh'), 'pocket', 'token'))
const [cmd, arg] = argv
const token = readFileSync(tokenFile, 'utf8').trim()
const ws = new WebSocket(url)
let id = 0; const pend = new Map()
ws.on('message', (d) => { const m = JSON.parse(d); pend.get(m.id)?.(m) })
const call = (op, args = {}) => new Promise((r) => { const i = ++id; pend.set(i, r); ws.send(JSON.stringify({ id: i, op, args: { proto: 1, ...args } })) })
ws.on('error', (e) => { console.error(e.message); process.exit(1) })
ws.on('open', async () => {
  const h = await call('hello', { token })
  if (!h.ok) { console.error(h.error); process.exit(1) }
  let r
  if (cmd === 'pair') r = await call('pairing.open')
  else if (cmd === 'devices') r = await call('devices.list')
  else if (cmd === 'revoke') r = await call('devices.revoke', { deviceId: arg })
  else { console.error('usage: pocket pair | devices | revoke <deviceId>'); process.exit(2) }
  if (!r.ok) { console.error(r.error); process.exit(1) }
  if (cmd === 'pair') console.log(`配对码 / pairing code: ${r.result.code}  (${Math.round(r.result.expiresInSec / 60)} min)`)
  else console.log(JSON.stringify(r.result, null, 2))
  ws.close()
})
