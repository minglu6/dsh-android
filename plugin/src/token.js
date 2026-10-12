import { randomBytes } from 'node:crypto'
import { chmodSync, existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'

/** Load the persistent bearer token, creating a 32-byte one on first run (0600). */
export function loadOrCreateToken(dataDir, fixed) {
  const path = join(dataDir, 'token')
  if (typeof fixed === 'string' && fixed.length > 0) return { token: fixed, created: false, path: '(config)' }
  if (existsSync(path)) {
    const token = readFileSync(path, 'utf8').trim()
    if (token.length >= 16) return { token, created: false, path }
  }
  mkdirSync(dataDir, { recursive: true, mode: 0o700 })
  const token = randomBytes(32).toString('base64url')
  writeFileSync(path, token + '\n', { mode: 0o600 })
  chmodSync(path, 0o600)
  return { token, created: true, path }
}
