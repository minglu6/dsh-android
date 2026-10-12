import { test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtempSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { loadOrCreateInstanceId, INSTANCE_ID_RE } from '../src/instance-id.js'

test('loadOrCreateInstanceId persists a 64-hex id', () => {
  const dir = mkdtempSync(join(tmpdir(), 'pocket-iid-'))
  try {
    const a = loadOrCreateInstanceId(dir)
    const b = loadOrCreateInstanceId(dir)
    assert.match(a, INSTANCE_ID_RE)
    assert.equal(a, b)
  } finally {
    rmSync(dir, { recursive: true, force: true })
  }
})
