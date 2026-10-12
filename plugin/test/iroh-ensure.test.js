import { test } from 'node:test'
import assert from 'node:assert/strict'
import { ensureHostBinary, resolveHostBinary, PREBUILT_ASSET } from '../src/iroh.js'

test('ensureHostBinary returns existing binary without download/cargo', async () => {
  const existing = resolveHostBinary()
  if (!existing) return // environment without a built host — skip soft
  const got = await ensureHostBinary(existing, { allowDownload: false, allowCargo: false, log: () => {} })
  assert.equal(got, existing)
})

test('ensureHostBinary returns undefined when nothing available', async () => {
  const got = await ensureHostBinary('/no/such/pocket-host-binary', {
    allowDownload: false,
    allowCargo: false,
    log: () => {},
  })
  assert.equal(got, undefined)
})

test('prebuilt asset map covers macOS arm64 and linux x64', () => {
  assert.equal(PREBUILT_ASSET['darwin-arm64'], 'pocket-host-darwin-arm64')
  assert.equal(PREBUILT_ASSET['linux-x64'], 'pocket-host-linux-x64')
})
