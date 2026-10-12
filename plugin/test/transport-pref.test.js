import { test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtempSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { createManageApi, pairingUri } from '../src/manage.js'
import { loadTransport, saveTransport, normalizeTransport } from '../src/transport-pref.js'

test('normalizeTransport', () => {
  assert.equal(normalizeTransport('iroh'), 'iroh')
  assert.equal(normalizeTransport('tailscale'), 'tailscale')
  assert.equal(normalizeTransport('ws'), null)
  assert.equal(normalizeTransport(undefined), null)
})

test('load/save transport preference', () => {
  const dir = mkdtempSync(join(tmpdir(), 'pocket-transport-'))
  try {
    assert.equal(loadTransport(dir), 'iroh')
    assert.equal(saveTransport(dir, 'tailscale'), 'tailscale')
    assert.equal(loadTransport(dir), 'tailscale')
    // Explicit config wins over file.
    assert.equal(loadTransport(dir, 'iroh'), 'iroh')
  } finally {
    rmSync(dir, { recursive: true, force: true })
  }
})

test('manage API routes pairing by selected transport only', () => {
  let mode = 'iroh'
  const NODE = 'abcdefghijklmnopqrstuvwxyz012345'
  const pairing = {
    status: () => ({ open: true, code: '123456', expiresInSec: 600, attemptsLeft: 3 }),
    open: () => ({ code: '123456', expiresInSec: 600, attemptsLeft: 3 }),
    close: () => {},
  }
  const devices = { list: () => [] }
  const server = {
    onlineDeviceIds: () => new Set(),
    address: () => ({ address: '100.64.1.2', port: 7690 }),
    revokeDevice: () => false,
  }
  const api = createManageApi({
    host: '100.64.1.2',
    port: 7690,
    devices,
    pairing,
    server,
    openPairing: () => pairing.open(),
    getNodeId: () => NODE,
    getTransport: () => mode,
    setTransport: (m) => { mode = m; return m },
  })

  const iroh = api.pairingStatus()
  assert.equal(iroh.transport, 'iroh')
  assert.equal(iroh.pairingString, `${NODE}#123456`)
  assert.ok(iroh.uri.includes('n='))
  assert.ok(!iroh.uri.includes('h='))

  api.setTransport('tailscale')
  const ts = api.pairingStatus()
  assert.equal(ts.transport, 'tailscale')
  assert.equal(
    ts.uri,
    pairingUri({ code: '123456', host: '100.64.1.2', port: 7690, transport: 'tailscale' }),
  )
  assert.ok(ts.uri.includes('h='))
  assert.ok(!ts.uri.includes('n='))

  const st = api.getStatus()
  assert.equal(st.transport, 'tailscale')
})

test('iroh openPairing refuses without nodeId; Tailscale does not need it', () => {
  let mode = 'iroh'
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
      address: () => ({ address: '100.64.1.2', port: 7690 }),
      revokeDevice: () => false,
    },
    openPairing: () => pairing.open(),
    getNodeId: () => null,
    getTransport: () => mode,
    setTransport: (m) => { mode = m; return m },
  })

  assert.throws(() => api.openPairing(), /iroh sidecar is not ready/)
  mode = 'tailscale'
  const opened = api.openPairing()
  assert.equal(opened.transport, 'tailscale')
  assert.ok(opened.uri.includes('h='))
})
