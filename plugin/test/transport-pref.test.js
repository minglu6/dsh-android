import { test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtempSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { createManageApi, pairingUri } from '../src/manage.js'
import { loadTransport, saveTransport, normalizeTransport } from '../src/transport-pref.js'

test('normalizeTransport accepts only tailscale', () => {
  assert.equal(normalizeTransport('tailscale'), 'tailscale')
  assert.equal(normalizeTransport('iroh'), null)
  assert.equal(normalizeTransport('ws'), null)
  assert.equal(normalizeTransport(undefined), null)
})

test('load/save transport preference defaults to tailscale', () => {
  const dir = mkdtempSync(join(tmpdir(), 'pocket-transport-'))
  try {
    assert.equal(loadTransport(dir), 'tailscale')
    assert.equal(saveTransport(dir, 'tailscale'), 'tailscale')
    assert.equal(loadTransport(dir), 'tailscale')
    writeFileSync(join(dir, 'transport'), 'iroh\n')
    // Legacy iroh file is ignored.
    assert.equal(loadTransport(dir), 'tailscale')
    assert.equal(loadTransport(dir, 'tailscale'), 'tailscale')
    assert.throws(() => saveTransport(dir, 'iroh'), /transport must be/)
  } finally {
    rmSync(dir, { recursive: true, force: true })
  }
})

test('manage API advertises host:port pairing only', () => {
  const pairing = {
    status: () => ({ open: true, code: '123456', expiresInSec: 600, attemptsLeft: 3 }),
    open: () => ({ code: '123456', expiresInSec: 600, attemptsLeft: 3 }),
    close: () => {},
  }
  const devices = { list: () => [] }
  const server = {
    onlineDeviceIds: () => new Set(),
    address: () => ({ address: '0.0.0.0', port: 7690 }),
    revokeDevice: () => false,
  }
  const api = createManageApi({
    host: '100.64.1.2',
    port: 7690,
    devices,
    pairing,
    server,
    openPairing: () => pairing.open(),
    getTransport: () => 'tailscale',
    setTransport: (m) => m,
  })

  const st = api.pairingStatus()
  assert.equal(st.transport, 'tailscale')
  assert.equal(
    st.uri,
    pairingUri({ code: '123456', host: '100.64.1.2', port: 7690, transport: 'tailscale' }),
  )
  assert.ok(st.uri.includes('h='))
  assert.ok(!st.uri.includes('n='))

  const status = api.getStatus()
  assert.equal(status.transport, 'tailscale')
  assert.equal(status.host, '100.64.1.2')
  assert.equal(status.running, true)
})

test('openPairing does not require iroh nodeId', () => {
  let window = null
  const pairing = {
    status: () => (window
      ? { open: true, code: window.code, expiresInSec: window.expiresInSec, attemptsLeft: window.attemptsLeft }
      : { open: false }),
    open: () => {
      window = { code: '654321', expiresInSec: 600, attemptsLeft: 3 }
      return window
    },
    close: () => { window = null },
  }
  const api = createManageApi({
    host: '100.64.1.2',
    port: 7690,
    devices: { list: () => [] },
    pairing,
    server: {
      onlineDeviceIds: () => new Set(),
      address: () => ({ address: '0.0.0.0', port: 7690 }),
      revokeDevice: () => false,
    },
    openPairing: () => pairing.open(),
    getTransport: () => 'tailscale',
  })

  const opened = api.openPairing()
  assert.equal(opened.transport, 'tailscale')
  assert.ok(opened.uri.includes('h='))
})
