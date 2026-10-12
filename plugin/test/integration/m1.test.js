// Real dsh: approvals race (phone vs web UI), pairing via webServer route, resume.
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { resolve } from 'node:path'
import { hasDsh, PLUGIN_DIR, bootDsh, pocketClient, webUiClient } from './helpers.js'

const TOKEN = 'integration-owner-token-0123456789'
const PORT = 18000 + Math.floor(Math.random() * 500)
const TRIGGER = PORT + 600

test('real dsh: approvals, pairing route, resume', { skip: !hasDsh, timeout: 300_000 }, async (t) => {
  const { webUrl } = await bootDsh(t, {
    plugins: [PLUGIN_DIR, resolve(import.meta.dirname, '../fixtures/approval-trigger')],
    patch: `- id: dsh-pocket\n  config:\n    port: ${PORT}\n    token: ${TOKEN}\n- id: pocket-approval-trigger\n  config:\n    port: ${TRIGGER}\n`,
  })
  const owner = await pocketClient(`ws://127.0.0.1:${PORT}`)
  await owner.ok('hello', { token: TOKEN })

  // --- pairing through dsh's own webServer route
  const origin = new URL(webUrl).origin
  const pr = await fetch(`${origin}/pocket/pair`, { method: 'POST' })
  assert.equal(pr.status, 200, await pr.clone().text())
  const { code } = await pr.json()
  const phone = await pocketClient(`ws://127.0.0.1:${PORT}`)
  const paired = await phone.ok('pair', { code, name: 'it-phone' })
  assert.ok(paired.token)
  const phone2 = await pocketClient(`ws://127.0.0.1:${PORT}`)
  assert.equal((await phone2.ok('hello', { token: paired.token })).device.name, 'it-phone')
  const crossSite = await fetch(`${origin}/pocket/pair`, { method: 'POST', headers: { origin: 'http://evil.example' } })
  assert.equal(crossSite.status, 403)
  const dl = await (await fetch(`${origin}/pocket/devices`)).json()
  assert.equal(dl.devices.length, 1)

  // --- a live session so an Agent exists
  const { sessionId } = await owner.ok('sessions.create', {})
  await owner.ok('sessions.rename', { sessionId, title: 'approval it' })
  const web = await webUiClient(webUrl)
  const trigger = (key) => fetch(`http://127.0.0.1:${TRIGGER}/trigger`, { method: 'POST', body: JSON.stringify({ sessionId, key, toolName: 'bash' }) }).then((r) => r.json())

  // A: phone answers first -> tool gets phone's outcome, web UI's pending event is withdrawn
  const a = trigger('a')
  const webEv = await web.next((v) => v.type === 'waterfall' && v.event === 'approval/request')
  const offer = await phone2.next((m) => m.ev === 'approval')
  assert.equal(offer.sessionId, sessionId)
  await phone2.ok('approval.answer', { approvalId: offer.approvalId, outcome: 'allowed-once' })
  assert.deepEqual(await a, { outcome: 'allowed-once' })
  const cancel = await web.next((v) => v.type === 'cancel' && v.eventId === webEv.eventId)
  assert.ok(cancel, 'web UI got cancel for its pending approval')
  const closedA = await phone.next((m) => m.ev === 'approval.closed' && m.approvalId === offer.approvalId)
  assert.match(closedA.by, /^device:/)
  // late web answer to the withdrawn event is harmless
  const late = await web.answer(webEv.eventId, 'rejected')
  assert.ok(late.type === 'server-response')

  // B: web UI answers first -> phones notified approval.closed by chain
  const b = trigger('b')
  const webEv2 = await web.next((v) => v.type === 'waterfall' && v.event === 'approval/request')
  const offer2 = await phone2.next((m) => m.ev === 'approval')
  const r = await web.answer(webEv2.eventId, 'rejected')
  assert.equal(r.result?.ok, true, JSON.stringify(r))
  assert.deepEqual(await b, { outcome: 'rejected' })
  const closedB = await phone2.next((m) => m.ev === 'approval.closed' && m.approvalId === offer2.approvalId)
  assert.equal(closedB.by, 'chain')
  const tooLate = await phone2.call('approval.answer', { approvalId: offer2.approvalId, outcome: 'allowed-once' })
  assert.equal(tooLate.error.code, 'approval/not-pending')

  // C: tool-side cancel -> cancelled, phones notified
  const c = trigger('c')
  const offer3 = await phone2.next((m) => m.ev === 'approval')
  await fetch(`http://127.0.0.1:${TRIGGER}/abort`, { method: 'POST', body: JSON.stringify({ key: 'c' }) })
  assert.deepEqual(await c, { outcome: 'cancelled' })
  assert.equal((await phone2.next((m) => m.ev === 'approval.closed' && m.approvalId === offer3.approvalId)).outcome, 'cancelled')

  // D: resume follow from a seq
  const sub = await owner.ok('follow', { sessionId })
  const full = await owner.next((m) => m.ev === 'follow' && m.subId === sub.subId)
  assert.equal(full.frame.type, 'snapshot')
  const seqs = full.frame.records.map((x) => x.event.seq)
  assert.ok(seqs.length >= 2, `records: ${seqs}`)
  const after = seqs[seqs.length - 2]
  const sub2 = await owner.ok('follow', { sessionId, afterSeq: after })
  const res = await owner.next((m) => m.ev === 'follow' && m.subId === sub2.subId)
  assert.equal(res.frame.type, 'resume')
  assert.deepEqual(res.frame.records.map((x) => x.event.seq), seqs.filter((s) => s > after))

  // E: revoke kicks the device
  await owner.ok('devices.revoke', { deviceId: paired.deviceId })
  await new Promise((r) => phone2.ws.on('close', r))
  web.close(); owner.close(); phone.close()
})
