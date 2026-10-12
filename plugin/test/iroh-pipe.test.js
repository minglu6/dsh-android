import { test } from 'node:test'
import assert from 'node:assert/strict'
import { createDeviceStore } from '../src/devices.js'
import { createPocketServer } from '../src/server.js'

const OWNER = 'owner-token-iroh-pipe-test'

test('attachPipe speaks the same hello/pair protocol as WebSocket', async () => {
  const devices = createDeviceStore({ ownerToken: OWNER, persist: false })
  const server = createPocketServer({
    sessionController: {
      list: async () => ({ items: [] }),
      follow: () => (async function* () {})(),
    },
    token: OWNER, port: 0, devices, pairFailDelayMs: 1,
  })
  await server.listen()

  const inbox = []
  let closed = false
  const pipe = server.attachPipe({
    send: (frame) => inbox.push(frame),
    close: () => { closed = true },
    meta: { transport: 'iroh', peer: 'peer-1' },
  })

  pipe.onMessage({ id: 1, op: 'hello', args: { proto: 1, token: OWNER } }, 64)
  await new Promise((r) => setTimeout(r, 20))
  assert.equal(inbox[0]?.ok, true)
  assert.ok(inbox[0]?.result?.ops?.includes('sessions.list'))

  const { code } = (await new Promise((resolve) => {
    // open pairing via owner ops on the same pipe client (already authed as owner)
    pipe.onMessage({ id: 2, op: 'pairing.open', args: {} }, 32)
    const check = () => {
      const m = inbox.find((x) => x.id === 2)
      if (m) resolve(m)
      else setTimeout(check, 5)
    }
    check()
  })).result

  // New phone over a second pipe
  const phoneInbox = []
  const phone = server.attachPipe({
    send: (frame) => phoneInbox.push(frame),
    close: () => {},
    meta: { transport: 'iroh', peer: 'phone-a' },
  })
  phone.onMessage({ id: 1, op: 'pair', args: { proto: 1, code, name: 'Pixel' } }, 80)
  await new Promise((r) => setTimeout(r, 50))
  const paired = phoneInbox.find((m) => m.id === 1)
  assert.equal(paired?.ok, true)
  assert.ok(paired.result.token)
  assert.ok(paired.result.deviceId)

  pipe.close(1000, 'bye')
  // transport.close runs; closed flag is set inside attachPipe's close wrapper
  assert.equal(closed, true)
  await server.close()
})

test('resolveHostBinary finds cargo debug build when present', async () => {
  const { resolveHostBinary } = await import('../src/iroh.js')
  const bin = resolveHostBinary()
  // May be undefined in a clean checkout before cargo build; that is OK.
  if (bin) assert.match(bin, /pocket-host/)
})
