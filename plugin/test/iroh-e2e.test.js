import { test } from 'node:test'
import assert from 'node:assert/strict'
import { spawn } from 'node:child_process'
import { once } from 'node:events'
import { createInterface } from 'node:readline'
import { mkdtempSync, existsSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { createDeviceStore } from '../src/devices.js'
import { createPairing } from '../src/pairing.js'
import { createPocketServer } from '../src/server.js'
import { resolveHostBinary, startIrohSidecar } from '../src/iroh.js'

const OWNER = 'owner-token-iroh-e2e'
const bin = resolveHostBinary()
const options = { skip: !bin || !existsSync(bin), timeout: 30_000 }

async function stopPhone(phone) {
  if (!phone) return
  phone.stdout?.destroy()
  phone.stderr?.destroy()
  if (phone.exitCode !== null || phone.signalCode !== null) return
  const exited = once(phone, 'exit')
  phone.kill('SIGKILL')
  await exited
}

/** Real QUIC/TLS + the Node pipe bridge, isolated from public DNS and relay availability. */
async function exchange(t, first, pairing) {
  const hostDir = mkdtempSync(join(tmpdir(), 'pocket-host-'))
  const phoneDir = mkdtempSync(join(tmpdir(), 'pocket-phone-'))
  const devices = createDeviceStore({ ownerToken: OWNER, persist: false })
  const server = createPocketServer({
    sessionController: {
      list: async () => ({ items: [{ sessionId: 's1' }] }),
      follow: () => (async function* () {})(),
    },
    token: OWNER, port: 0, devices, pairing, pairFailDelayMs: 1,
  })
  let sidecar, phone, rl
  t.after(async () => {
    rl?.close()
    await stopPhone(phone)
    await sidecar?.close()
    await server.close()
    rmSync(hostDir, { recursive: true, force: true })
    rmSync(phoneDir, { recursive: true, force: true })
  })
  // No server.listen(): neither case can accidentally use WebSocket instead of iroh.
  sidecar = await startIrohSidecar({ server, dataDir: hostDir, hostBinary: bin, localOnly: true, log: () => {} })
  assert.ok(sidecar)
  const endpoint = await sidecar.endpoint
  assert.match(endpoint.directAddress, /^127\.0\.0\.1:\d+$/)
  phone = spawn(bin, ['phone-sim', '--local-only', '--peer', endpoint.id, '--peer-addr', endpoint.directAddress,
    '--data-dir', phoneDir, '--first', JSON.stringify(first)], { stdio: ['ignore', 'pipe', 'pipe'] })
  let stderr = ''
  phone.stderr.on('data', chunk => { stderr = (stderr + chunk).slice(-4096) })
  rl = createInterface({ input: phone.stdout })
  const abort = new AbortController()
  const timeout = setTimeout(() => abort.abort(new Error(`iroh reply timeout: ${stderr}`)), 15_000)
  try {
    const line = await Promise.race([
      once(rl, 'line', { signal: abort.signal }).then(([line]) => line),
      once(phone, 'exit', { signal: abort.signal }).then(([code, signal]) => {
        throw new Error(`phone-sim exited (${code ?? signal}): ${stderr}`)
      }),
    ])
    return { reply: JSON.parse(line), devices }
  } finally {
    clearTimeout(timeout)
    abort.abort()
  }
}

test('iroh hello authenticates over loopback QUIC and ALPN dsh-pocket/1 without WS', options, async t => {
  const { reply } = await exchange(t, { id: 1, op: 'hello', args: { proto: 1, token: OWNER, app: 'phone-sim' } })
  assert.equal(reply.id, 1)
  assert.equal(reply.ok, true)
  assert.ok(reply.result.ops.includes('sessions.list'))
})

test('iroh pairing registers a real device over loopback QUIC without WS', options, async t => {
  const pairing = createPairing()
  const { code } = pairing.open()
  const { reply, devices } = await exchange(t, { id: 1, op: 'pair', args: { proto: 1, code, name: 'phone-sim' } }, pairing)
  assert.equal(reply.id, 1)
  assert.equal(reply.ok, true)
  assert.equal(typeof reply.result.token, 'string')
  assert.notEqual(reply.result.token, OWNER)
  assert.deepEqual(devices.list().map(({ id, name }) => ({ id, name })), [{ id: reply.result.deviceId, name: 'phone-sim' }])
})
