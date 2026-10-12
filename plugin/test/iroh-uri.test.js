import { test } from 'node:test'
import assert from 'node:assert/strict'
import { pairingUri, pairingString } from '../src/manage.js'

test('pairingUri rejects legacy iroh mode', () => {
  assert.equal(pairingUri({ code: '123456', nodeId: 'nodeXYZABCDEF0123', transport: 'iroh' }), undefined)
  assert.equal(pairingUri({ code: '123456', transport: 'iroh' }), undefined)
  assert.equal(pairingString({ code: '000001', nodeId: 'abcdefghijklmnopqrst', transport: 'iroh' }), undefined)
})

test('pairingUri host:port only (no n=)', () => {
  assert.equal(
    pairingUri({ code: '482913', host: '100.106.128.87', port: 7690, transport: 'tailscale' }),
    'dshpocket://pair?h=100.106.128.87%3A7690&c=482913',
  )
  assert.equal(
    pairingUri({ code: '482913', host: '100.1.2.3', port: 7690, transport: 'tailscale' }),
    'dshpocket://pair?h=100.1.2.3%3A7690&c=482913',
  )
  assert.ok(!pairingUri({
    code: '482913', host: '100.1.2.3', port: 7690, transport: 'tailscale',
  })?.includes('n='))
})

test('pairingString is the same URI shape', () => {
  assert.equal(
    pairingString({ code: '111111', host: 'mac.tail1.ts.net', port: 7690, transport: 'tailscale' }),
    pairingUri({ code: '111111', host: 'mac.tail1.ts.net', port: 7690, transport: 'tailscale' }),
  )
})
