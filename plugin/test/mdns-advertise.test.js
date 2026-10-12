import { test } from 'node:test'
import assert from 'node:assert/strict'
import { discoveryDeviceName, discoveryMdnsHost, MDNS_SERVICE_TYPE, DISCOVERY_PROTOCOL } from '../src/mdns-advertise.js'

test('discovery helpers', () => {
  assert.equal(MDNS_SERVICE_TYPE, 'dshpocket')
  assert.equal(DISCOVERY_PROTOCOL, 1)
  assert.ok(discoveryDeviceName().length <= 63)
  const host = discoveryMdnsHost('a'.repeat(64))
  assert.match(host, /\.local$/)
  assert.match(host, /aaaaaaaa/)
})
