import { test } from 'node:test'
import assert from 'node:assert/strict'
import { spawn } from 'node:child_process'
import { createInterface } from 'node:readline'
import { mkdtempSync, existsSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { createDeviceStore } from '../src/devices.js'
import { createPairing } from '../src/pairing.js'
import { createPocketServer } from '../src/server.js'
import { pairingString, pairingUri } from '../src/manage.js'
import { resolveHostBinary, startIrohSidecar } from '../src/iroh.js'

const OWNER = 'owner-token-iroh-pair'
const bin = resolveHostBinary()

function waitExit(child, ms = 2000) {
  if (child.exitCode !== null || child.signalCode !== null) return Promise.resolve()
  return new Promise((resolve) => {
    const t = setTimeout(() => { try { child.kill('SIGKILL') } catch { /* ignore */ }; resolve() }, ms)
    child.once('exit', () => { clearTimeout(t); resolve() })
  })
}

test('iroh host↔phone-sim pairs over ALPN without shared network / WS', {
  skip: !bin || !existsSync(bin),
  timeout: 90_000,
}, async () => {
  const hostDir = mkdtempSync(join(tmpdir(), 'pocket-host-pair-'))
  const phoneDir = mkdtempSync(join(tmpdir(), 'pocket-phone-pair-'))
  const devices = createDeviceStore({ ownerToken: OWNER, persist: false })
  const pairing = createPairing()
  const server = createPocketServer({
    sessionController: {
      list: async () => ({ items: [] }),
      follow: () => (async function* () {})(),
    },
    token: OWNER, port: 0, devices, pairing, pairFailDelayMs: 1,
  })
  // No server.listen() — pure iroh path (phones never need the loopback WS).

  const sidecar = await startIrohSidecar({
    server,
    dataDir: hostDir,
    hostBinary: bin,
    log: () => {},
  })
  assert.ok(sidecar)
  const endpointId = await Promise.race([
    sidecar.endpointId,
    new Promise((_, rej) => setTimeout(() => rej(new Error('ready timeout')), 20_000)),
  ])

  const window = pairing.open()
  const line = pairingString({ code: window.code, nodeId: endpointId })
  const uri = pairingUri({ code: window.code, nodeId: endpointId })
  assert.match(line, /#\d{6}$/)
  assert.equal(uri, `dshpocket://pair?n=${encodeURIComponent(endpointId)}&c=${window.code}`)

  const first = JSON.stringify({
    id: 1,
    op: 'pair',
    args: { proto: 1, code: window.code, name: 'phone-sim' },
  })
  const phone = spawn(bin, ['phone-sim', '--peer', endpointId, '--data-dir', phoneDir, '--first', first], {
    stdio: ['ignore', 'pipe', 'pipe'],
  })
  const rl = createInterface({ input: phone.stdout })
  try {
    const reply = await new Promise((resolve, reject) => {
      const t = setTimeout(() => reject(new Error('phone-sim pair timeout')), 45_000)
      rl.once('line', (l) => { clearTimeout(t); resolve(l) })
      phone.on('error', reject)
    })
    const msg = JSON.parse(reply)
    assert.equal(msg.id, 1)
    assert.equal(msg.ok, true)
    assert.ok(msg.result?.token)
    assert.ok(msg.result?.deviceId)
    assert.equal(devices.list().length, 1)
  } finally {
    rl.close()
    phone.stdout?.destroy()
    phone.stderr?.destroy()
    try { phone.kill('SIGKILL') } catch { /* ignore */ }
    await waitExit(phone)
    await sidecar.close()
    await server.close()
  }
})
