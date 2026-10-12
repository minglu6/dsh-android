import { test } from 'node:test'
import assert from 'node:assert/strict'
import { pairingUri } from '../src/manage.js'

test('pairingUri stays backward compatible without node id', () => {
  assert.equal(
    pairingUri({ host: '100.1.2.3', port: 7690, code: '123456' }),
    'dshpocket://pair?h=100.1.2.3%3A7690&c=123456',
  )
})

test('pairingUri appends n= when iroh node id is present', () => {
  const uri = pairingUri({ host: '127.0.0.1', port: 7690, code: '000001', nodeId: 'nodeXYZ' })
  assert.match(uri, /^dshpocket:\/\/pair\?/)
  assert.match(uri, /c=000001/)
  assert.match(uri, /n=nodeXYZ/)
  assert.match(uri, /h=127\.0\.0\.1%3A7690/)
})
