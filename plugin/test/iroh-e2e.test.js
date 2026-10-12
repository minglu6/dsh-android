import { test } from 'node:test'
import assert from 'node:assert/strict'
import { spawn } from 'node:child_process'
import { createInterface } from 'node:readline'
import { mkdtempSync, existsSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { createDeviceStore } from '../src/devices.js'
import { createPocketServer } from '../src/server.js'
import { resolveHostBinary, startIrohSidecar } from '../src/iroh.js'

const OWNER = 'owner-token-iroh-e2e'
const bin = resolveHostBinary()

function waitExit(child, ms = 2000) {
  if (child.exitCode !== null || child.signalCode !== null) return Promise.resolve()
  return new Promise((resolve) => {
    const t = setTimeout(() => { try { child.kill('SIGKILL') } catch { /* ignore */ }; resolve() }, ms)
    child.once('exit', () => { clearTimeout(t); resolve() })
  })
}

test('iroh host↔phone-sim carries pocket hello over ALPN dsh-pocket/1', { skip: !bin || !existsSync(bin), timeout: 90_000 }, async () => {
  const hostDir = mkdtempSync(join(tmpdir(), 'pocket-host-'))
  const phoneDir = mkdtempSync(join(tmpdir(), 'pocket-phone-'))
  const devices = createDeviceStore({ ownerToken: OWNER, persist: false })
  const server = createPocketServer({
    sessionController: {
      list: async () => ({ items: [{ sessionId: 's1' }] }),
      follow: () => (async function* () {})(),
    },
    token: OWNER, port: 0, devices, pairFailDelayMs: 1,
  })
  await server.listen()

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
  assert.ok(typeof endpointId === 'string' && endpointId.length > 8)

  const first = JSON.stringify({ id: 1, op: 'hello', args: { proto: 1, token: OWNER, app: 'phone-sim' } })
  const phone = spawn(bin, ['phone-sim', '--peer', endpointId, '--data-dir', phoneDir, '--first', first], {
    stdio: ['ignore', 'pipe', 'pipe'],
  })
  const rl = createInterface({ input: phone.stdout })
  try {
    const line = await new Promise((resolve, reject) => {
      const t = setTimeout(() => reject(new Error('phone-sim timeout')), 30_000)
      rl.once('line', (l) => { clearTimeout(t); resolve(l) })
      phone.on('error', reject)
    })
    const msg = JSON.parse(line)
    assert.equal(msg.id, 1)
    assert.equal(msg.ok, true)
    assert.ok(Array.isArray(msg.result?.ops))
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
