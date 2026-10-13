/**
 * Stable host instance id for LAN mDNS advertisements and phone host:port updates.
 *
 * Adapted from dsh-mobile instance identity patterns
 * (https://github.com/saya-ch/dsh-mobile), Apache-2.0.
 */
import { createHash, randomBytes } from 'node:crypto'
import { existsSync, mkdirSync, readFileSync, renameSync, writeFileSync, chmodSync } from 'node:fs'
import { join } from 'node:path'

const INSTANCE_ID = /^[a-f0-9]{64}$/

/**
 * Load or create a 64-hex instance id under dataDir.
 * @param {string} dataDir
 * @returns {string}
 */
export function loadOrCreateInstanceId(dataDir) {
  const path = join(dataDir, 'instance-id')
  if (existsSync(path)) {
    const raw = readFileSync(path, 'utf8').trim().toLowerCase()
    if (INSTANCE_ID.test(raw)) return raw
  }
  const id = createHash('sha256').update(randomBytes(32)).digest('hex')
  mkdirSync(dataDir, { recursive: true, mode: 0o700 })
  const tmp = `${path}.tmp`
  writeFileSync(tmp, `${id}\n`, { mode: 0o600 })
  chmodSync(tmp, 0o600)
  renameSync(tmp, path)
  return id
}

export { INSTANCE_ID as INSTANCE_ID_RE }
