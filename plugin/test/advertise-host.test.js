import { test } from 'node:test'
import assert from 'node:assert/strict'
import { isTailscaleHost, pickAdvertiseHost, pickBindHost } from '../src/advertise-host.js'
import { connectionTypeForHost } from '../src/manage.js'

test('isTailscaleHost', () => {
  assert.equal(isTailscaleHost('100.64.1.2'), true)
  assert.equal(isTailscaleHost('100.127.255.255'), true)
  assert.equal(isTailscaleHost('mac.tail1.ts.net'), true)
  assert.equal(isTailscaleHost('192.168.1.10'), false)
  assert.equal(isTailscaleHost('100.63.1.2'), false)
  assert.equal(isTailscaleHost('127.0.0.1'), false)
})

test('pickAdvertiseHost prefers explicit non-loopback config', () => {
  assert.equal(pickAdvertiseHost('100.64.1.2'), '100.64.1.2')
  assert.equal(pickAdvertiseHost('192.168.1.5'), '192.168.1.5')
})

test('pickBindHost defaults to all interfaces when unset', () => {
  assert.equal(pickBindHost(undefined), '0.0.0.0')
  assert.equal(pickBindHost(''), '0.0.0.0')
  assert.equal(pickBindHost('100.64.1.2'), '100.64.1.2')
})

test('connectionTypeForHost is tailscale or lan', () => {
  assert.equal(connectionTypeForHost('100.64.1.2'), 'tailscale')
  assert.equal(connectionTypeForHost('mac.tail1.ts.net'), 'tailscale')
  assert.equal(connectionTypeForHost('192.168.1.10'), 'lan')
  assert.equal(connectionTypeForHost('127.0.0.1'), 'lan')
})
