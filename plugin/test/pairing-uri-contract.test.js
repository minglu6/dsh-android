import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'
import { parsePairingUri } from '../src/pairing-uri.js'

const root = join(dirname(fileURLToPath(import.meta.url)), '../..')
const contract = JSON.parse(readFileSync(join(root, 'contract/pairing-uri-cases.json'), 'utf8'))

test('pairing URI parse matches shared contract', () => {
  assert.equal(contract.schemaVersion, 1)
  for (const c of contract.parse) {
    const actual = parsePairingUri(c.input)
    if (c.expected === null) {
      assert.equal(actual, null, c.name)
    } else {
      assert.deepEqual(actual, c.expected, c.name)
    }
  }
})
