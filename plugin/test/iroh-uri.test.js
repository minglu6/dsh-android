import { test } from 'node:test'
import assert from 'node:assert/strict'
import { pairingUri, pairingString } from '../src/manage.js'

test('pairingUri is pure iroh (no host/port)', () => {
  assert.equal(
    pairingUri({ code: '123456', nodeId: 'nodeXYZABCDEF0123' }),
    'dshpocket://pair?n=nodeXYZABCDEF0123&c=123456',
  )
  assert.equal(pairingUri({ code: '123456' }), undefined)
  assert.equal(pairingUri({ nodeId: 'n' }), undefined)
})

test('pairingString matches tether shape nodeId#code', () => {
  assert.equal(
    pairingString({ code: '000001', nodeId: 'abcdefghijklmnopqrst' }),
    'abcdefghijklmnopqrst#000001',
  )
})
