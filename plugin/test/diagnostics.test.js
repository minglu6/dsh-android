import { test } from 'node:test'
import assert from 'node:assert/strict'
import { collectConnectionDiagnostics, listInterfacesRedacted } from '../src/diagnostics.js'

// Re-export probe via collect with injectable probe.
test('collectConnectionDiagnostics redacts hosts and reports checks', async () => {
  const report = await collectConnectionDiagnostics({
    version: '0.1.0',
    plugin: 'dsh-plugin-pocket',
    transport: 'tailscale',
    host: '192.168.1.20',
    port: 7690,
    instanceId: 'a'.repeat(64),
    connectionType: 'lan',
    running: true,
    mdns: true,
    deviceName: 'studio',
    probes: {
      reachability: async () => ({ ok: true, latencyMs: 3 }),
    },
  })
  assert.equal(report.version, 1)
  assert.equal(report.overall, 'ok')
  assert.match(report.report, /192\.168\.1\.x/)
  assert.doesNotMatch(report.report, /192\.168\.1\.20/)
  assert.ok(report.checks.some((c) => c.id === 'port' && c.status === 'ok'))
  assert.ok(report.checks.some((c) => c.id === 'mdns' && c.status === 'ok'))
  assert.equal(report.facts.instanceIdPrefix, 'aaaaaaaa')
  assert.equal(listInterfacesRedacted().every((i) => typeof i.address === 'string'), true)
})

test('failed reachability marks overall error', async () => {
  const report = await collectConnectionDiagnostics({
    version: '0.1.0',
    plugin: 'dsh-plugin-pocket',
    transport: 'tailscale',
    host: '127.0.0.1',
    port: 9,
    instanceId: 'b'.repeat(64),
    connectionType: 'lan',
    running: true,
    mdns: false,
    probes: {
      reachability: async () => ({ ok: false, error: 'ECONNREFUSED' }),
    },
  })
  assert.equal(report.overall, 'error')
  assert.ok(report.checks.some((c) => c.id === 'port' && c.status === 'error'))
})
