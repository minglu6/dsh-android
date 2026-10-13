import { test } from 'node:test'
import assert from 'node:assert/strict'
import { isTailscaleHost, listAdvertiseNetworks, pickBindHost, selectNetwork } from '../src/advertise-host.js'

const ipv4 = (address, internal = false) => ({ address, family: 'IPv4', internal })
const interfaces = {
  lo0: [ipv4('127.0.0.1', true)],
  utun: [ipv4('100.64.1.2')],
  en0: [ipv4('192.168.1.5')],
  en1: [ipv4('10.0.0.5')],
}

test('isTailscaleHost', () => {
  assert.equal(isTailscaleHost('100.64.1.2'), true)
  assert.equal(isTailscaleHost('100.127.255.255'), true)
  assert.equal(isTailscaleHost('mac.tail1.ts.net'), true)
  assert.equal(isTailscaleHost('192.168.1.10'), false)
  assert.equal(isTailscaleHost('100.63.1.2'), false)
  assert.equal(isTailscaleHost('127.0.0.1'), false)
})

test('wildcard binding offers LAN first and Tailscale without exposing loopback', () => {
  const networks = listAdvertiseNetworks(undefined, '0.0.0.0', interfaces)
  assert.deepEqual(networks, [
    { type: 'lan', host: '192.168.1.5' },
    { type: 'tailscale', host: '100.64.1.2' },
  ])
  assert.equal(selectNetwork(networks).host, '192.168.1.5')
  assert.equal(selectNetwork(networks, 'tailscale').host, '100.64.1.2')
})

test('LAN-only and Tailscale-only machines can pair without the other network', () => {
  for (const [type, host] of [['lan', '192.168.1.5'], ['tailscale', '100.64.1.2']]) {
    const networks = listAdvertiseNetworks(undefined, '0.0.0.0', { en0: [ipv4(host)] })
    assert.deepEqual(selectNetwork(networks), { type, host })
    const missing = type === 'lan' ? 'tailscale' : 'lan'
    assert.throws(() => selectNetwork(networks, missing), { statusCode: 503 })
  }
})

test('concrete binds only advertise their own available interface', () => {
  for (const [type, host] of [['lan', '192.168.1.5'], ['tailscale', '100.64.1.2']]) {
    assert.deepEqual(listAdvertiseNetworks(host, host, interfaces), [{ type, host }])
  }
  assert.deepEqual(listAdvertiseNetworks('10.0.0.99', '10.0.0.99', interfaces), [])
  assert.deepEqual(listAdvertiseNetworks(undefined, '192.168.1.5', interfaces), [{ type: 'lan', host: '192.168.1.5' }])
})

test('configured hostnames use the resolved bind and preserve the configured name', () => {
  assert.deepEqual(listAdvertiseNetworks('desktop.local', '192.168.1.5', interfaces), [
    { type: 'lan', host: 'desktop.local' },
  ])
  assert.deepEqual(listAdvertiseNetworks('desktop.tail.ts.net', '100.64.1.2', interfaces), [
    { type: 'tailscale', host: 'desktop.tail.ts.net' },
  ])
  assert.deepEqual(listAdvertiseNetworks('localhost', '127.0.0.1', interfaces), [])
})

test('loopback and absent interfaces never yield a phone endpoint', () => {
  assert.deepEqual(listAdvertiseNetworks('127.0.0.1', '127.0.0.1', interfaces), [])
  assert.deepEqual(listAdvertiseNetworks(undefined, '0.0.0.0', { lo: [ipv4('127.0.0.2')] }), [])
  assert.deepEqual(listAdvertiseNetworks(undefined, '0.0.0.0', {}), [])
  assert.equal(selectNetwork([]), undefined)
  assert.throws(() => selectNetwork([], 'lan'), { statusCode: 503 })
  assert.throws(() => selectNetwork([], 'unknown'), { statusCode: 400 })
  assert.throws(() => selectNetwork([], ''), { statusCode: 400 })
})

test('pickBindHost defaults to all interfaces when unset', () => {
  assert.equal(pickBindHost(undefined), '0.0.0.0')
  assert.equal(pickBindHost(''), '0.0.0.0')
  assert.equal(pickBindHost('100.64.1.2'), '100.64.1.2')
})

test('interface discovery honors address family and dual-stack wildcard binding', () => {
  assert.deepEqual(listAdvertiseNetworks('::', '::', {
    en0: [{ address: '192.168.1.5', family: 4, internal: false }],
    lo0: [{ address: '::1', family: 'IPv6', internal: true }],
  }), [{ type: 'lan', host: '192.168.1.5' }])
  assert.deepEqual(listAdvertiseNetworks('::1', '::1', interfaces), [])
})
