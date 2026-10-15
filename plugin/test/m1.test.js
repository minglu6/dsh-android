import { test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtempSync, statSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import WebSocket from 'ws'
import { createPairing, PAIR_BAD_CODE, PAIR_TOO_MANY, PAIR_EXPIRED, PAIR_NO_WINDOW } from '../src/pairing.js'
import { createDeviceStore } from '../src/devices.js'
import { createPocketServer } from '../src/server.js'
import { createApprovalHook } from '../src/approval-hook.js'

const OWNER = 'owner-token-0123456789abcdef'

test('pairing window: code, 3 attempts, expiry, single use', () => {
  let t = 0
  const p = createPairing({ now: () => t })
  assert.equal(p.attempt('000000'), PAIR_NO_WINDOW)
  const { code, expiresInSec } = p.open()
  assert.match(code, /^\d{6}$/); assert.equal(expiresInSec, 600)
  const wrong = code === '999999' ? '000000' : '999999'
  assert.equal(p.attempt(wrong), PAIR_BAD_CODE)
  assert.equal(p.attempt(wrong), PAIR_BAD_CODE)
  assert.equal(p.attempt(wrong), PAIR_TOO_MANY)
  assert.equal(p.attempt(code), PAIR_NO_WINDOW)
  const w2 = p.open()
  assert.equal(p.attempt(w2.code), null)
  assert.equal(p.attempt(w2.code), PAIR_NO_WINDOW)
  const w3 = p.open(); t += 600_000
  assert.equal(p.attempt(w3.code), PAIR_EXPIRED)
})

test('device store: hashed tokens, persist 0600, revoke', () => {
  const dir = mkdtempSync(join(tmpdir(), 'pocket-'))
  const s = createDeviceStore({ dataDir: dir, ownerToken: OWNER })
  assert.equal(s.authenticate(OWNER).owner, true)
  const { deviceId, token } = s.add('Pixel')
  assert.equal(statSync(join(dir, 'devices.json')).mode & 0o777, 0o600)
  const again = createDeviceStore({ dataDir: dir, ownerToken: OWNER })
  assert.equal(again.authenticate(token).id, deviceId)
  assert.equal(again.authenticate('nope'), undefined)
  assert.ok(!JSON.stringify(again.list()).includes(token))
  assert.equal(again.revoke(deviceId), true)
  assert.equal(again.authenticate(token), undefined)
})

function mockSc(snapshot, pages = []) {
  return {
    list: async () => ({ items: [] }),
    page: async () => pages.shift() ?? { records: [], hasMore: false },
    follow: (_r, signal) => (async function* () {
      yield snapshot
      await new Promise((r) => signal.addEventListener('abort', r))
    })(),
  }
}
const ev = (seq) => ({ type: 'event', event: { type: 'x', seq, time: 0, data: {} } })

async function setup(sc = mockSc({ type: 'snapshot', cursor: 0, records: [], hasMore: false }), info) {
  const devices = createDeviceStore({ ownerToken: OWNER, persist: false })
  const server = createPocketServer({
    sessionController: sc, token: OWNER, port: 0, devices, pairFailDelayMs: 1,
    info: info ?? { plugin: 'dsh-plugin-pocket', version: '0.1.0', minimumAppVersion: '0.1.0' },
  })
  const url = `ws://${await server.listen()}`
  return { server, url, devices }
}
function client(url) {
  const ws = new WebSocket(url); const q = []; const w = []
  ws.on('message', (d) => { const m = JSON.parse(d); const i = w.findIndex((x) => x.p(m)); if (i >= 0) w.splice(i, 1)[0].r(m); else q.push(m) })
  const next = (p = () => true) => { const i = q.findIndex(p); return i >= 0 ? Promise.resolve(q.splice(i, 1)[0]) : new Promise((r) => w.push({ p, r })) }
  let id = 0
  const call = (op, args = {}) => { const i = ++id; ws.send(JSON.stringify({ id: i, op, args: { proto: 1, ...args } })); return next((m) => m.id === i) }
  return { ws, next, call, open: new Promise((r) => ws.on('open', r)), closed: new Promise((r) => ws.on('close', (c) => r(c))) }
}

test('pair over ws issues device token; owner-only pairing.open; revoke kicks device', async () => {
  const { server, url } = await setup()
  const phone0 = client(url); await phone0.open
  assert.equal((await phone0.call('pair', { code: '123' })).error.code, 'pair/no-window')
  const owner = client(url); await owner.open
  await owner.call('hello', { token: OWNER })
  const { code } = (await owner.call('pairing.open')).result
  const phone = client(url); await phone.open
  const paired = await phone.call('pair', { code, name: 'Pixel 9', app: 'dsh-android', appVersion: '0.1.0' })
  assert.equal(paired.ok, true)
  assert.ok(paired.result.token && paired.result.deviceId)
  assert.equal(paired.result.version, '0.1.0')
  assert.equal(paired.result.minimumAppVersion, '0.1.0')
  assert.equal((await phone.call('pairing.open')).error.code, 'auth/forbidden')
  const phone2 = client(url); await phone2.open
  const hello = await phone2.call('hello', { token: paired.result.token, app: 'dsh-android', appVersion: '0.1.0' })
  assert.equal(hello.result.device.name, 'Pixel 9')
  assert.equal(hello.result.version, '0.1.0')
  assert.equal(hello.result.minimumAppVersion, '0.1.0')
  const list = (await owner.call('devices.list')).result.devices
  assert.equal(list.length, 1)
  await owner.call('devices.revoke', { deviceId: paired.result.deviceId })
  assert.equal(await phone2.closed, 4403)
  const phone3 = client(url); await phone3.open
  assert.equal((await phone3.call('hello', { token: paired.result.token })).error.code, 'auth/denied')
  owner.ws.close(); phone.ws.close(); await server.close()
})

test('approval hook: phone wins, web chain withdrawn via signal, others notified', async () => {
  const { server, url } = await setup()
  const a = client(url); await a.open; await a.call('hello', { token: OWNER })
  const b = client(url); await b.open; await b.call('hello', { token: OWNER })
  const hook = createApprovalHook(server)
  const req = { agent: { session: { id: 'sess1' } }, toolName: 'bash', callId: 'c1', reason: 'net' }
  let chainSignal
  const next = () => { chainSignal = req.signal; return new Promise((_ok, fail) => req.signal.addEventListener('abort', () => fail(new Error('cancelled')))) }
  const p = hook(req, next)
  const offer = await a.next((m) => m.ev === 'approval')
  assert.equal(offer.sessionId, 'sess1'); assert.equal(offer.toolName, 'bash')
  await b.next((m) => m.ev === 'approval')
  assert.equal((await a.call('approval.answer', { approvalId: offer.approvalId, outcome: 'allowed-once' })).ok, true)
  assert.equal(await p, 'allowed-once')
  assert.equal(chainSignal.aborted, true)
  const closed = await b.next((m) => m.ev === 'approval.closed')
  assert.equal(closed.outcome, 'allowed-once'); assert.match(closed.by, /^device:/)
  assert.equal((await b.call('approval.answer', { approvalId: offer.approvalId, outcome: 'rejected' })).error.code, 'approval/not-pending')
  a.ws.close(); b.ws.close(); await server.close()
})

test('approval hook: web answers first; unavailable never wins; no devices passes through', async () => {
  const { server, url } = await setup()
  const hook = createApprovalHook(server)
  assert.equal(await hook({ toolName: 't' }, async () => 'rejected'), 'rejected')
  const a = client(url); await a.open; await a.call('hello', { token: OWNER })
  assert.equal(await hook({ toolName: 't' }, async () => 'rejected'), 'rejected')
  const closed = await a.next((m) => m.ev === 'approval.closed')
  assert.equal(closed.by, 'chain')
  const p = hook({ toolName: 't' }, async () => 'unavailable')
  const offer = await a.next((m) => m.ev === 'approval' && m.approvalId !== closed.approvalId)
  await a.call('approval.answer', { approvalId: offer.approvalId, outcome: 'rejected' })
  assert.equal(await p, 'rejected')
  const ac = new AbortController()
  const p2 = hook({ toolName: 't', signal: ac.signal }, () => new Promise(() => {}))
  await a.next((m) => m.ev === 'approval')
  ac.abort()
  assert.equal(await p2, 'cancelled')
  a.ws.close(); await server.close()
})

test('pending approvals replay on hello', async () => {
  const { server, url } = await setup()
  const a = client(url); await a.open; await a.call('hello', { token: OWNER })
  const hook = createApprovalHook(server)
  const p = hook({ toolName: 'bash' }, () => new Promise(() => {}))
  await a.next((m) => m.ev === 'approval')
  const b = client(url); await b.open; await b.call('hello', { token: OWNER })
  const replay = await b.next((m) => m.ev === 'approval')
  await b.call('approval.answer', { approvalId: replay.approvalId, outcome: 'rejected' })
  assert.equal(await p, 'rejected')
  a.ws.close(); b.ws.close(); await server.close()
})

test('follow afterSeq: resume within snapshot window', async () => {
  const sc = mockSc({ type: 'snapshot', header: {}, cursor: 12, records: [ev(8), ev(9), ev(10), ev(11), ev(12)], hasMore: true, projections: {} })
  const { server, url } = await setup(sc)
  const c = client(url); await c.open; await c.call('hello', { token: OWNER })
  await c.call('follow', { sessionId: 's', afterSeq: 10 })
  const f = await c.next((m) => m.ev === 'follow')
  assert.equal(f.frame.type, 'resume')
  assert.deepEqual(f.frame.records.map((r) => r.event.seq), [11, 12])
  assert.equal(f.frame.cursor, 12)
  c.ws.close(); await server.close()
})

test('follow afterSeq: gap filled from history pages, else full snapshot', async () => {
  const snap = { type: 'snapshot', header: {}, cursor: 12, records: [ev(10), ev(11), ev(12)], hasMore: true, projections: {} }
  const sc = mockSc(snap, [{ records: [ev(6), ev(7), ev(8), ev(9)], hasMore: true }])
  const { server, url } = await setup(sc)
  const c = client(url); await c.open; await c.call('hello', { token: OWNER })
  await c.call('follow', { sessionId: 's', afterSeq: 5 })
  const f = await c.next((m) => m.ev === 'follow')
  assert.equal(f.frame.type, 'resume')
  assert.deepEqual(f.frame.records.map((r) => r.event.seq), [6, 7, 8, 9, 10, 11, 12])
  c.ws.close(); await server.close()
  const sc2 = mockSc({ ...snap }, [{ records: [ev(9)], hasMore: true }])
  const s2 = await setup(sc2)
  const c2 = client(s2.url); await c2.open; await c2.call('hello', { token: OWNER })
  await c2.call('follow', { sessionId: 's', afterSeq: 1 })
  const f2 = await c2.next((m) => m.ev === 'follow')
  assert.equal(f2.frame.type, 'snapshot')
  c2.ws.close(); await s2.server.close()
})
