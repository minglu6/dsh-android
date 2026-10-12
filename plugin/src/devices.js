import { createHash, randomBytes, randomUUID, timingSafeEqual } from 'node:crypto'
import { chmodSync, existsSync, mkdirSync, readFileSync, renameSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'

const hash = (s) => createHash('sha256').update(String(s)).digest('hex')
const eq = (a, b) => timingSafeEqual(Buffer.from(a, 'hex'), Buffer.from(b, 'hex'))

/**
 * Paired-device store. Tokens are stored as sha256 only.
 * The owner token (M0 `token` file / config) authenticates as device "owner"
 * and is the only identity allowed to open pairing windows.
 */
export function createDeviceStore({ dataDir, ownerToken, persist = true, now = Date.now }) {
  const path = dataDir ? join(dataDir, 'devices.json') : undefined
  let devices = []
  if (persist && path && existsSync(path)) {
    try { devices = JSON.parse(readFileSync(path, 'utf8')).devices ?? [] } catch { devices = [] }
  }
  const save = () => {
    if (!persist || !path) return
    mkdirSync(dataDir, { recursive: true, mode: 0o700 })
    const tmp = `${path}.tmp`
    writeFileSync(tmp, JSON.stringify({ version: 1, devices }, null, 2), { mode: 0o600 })
    chmodSync(tmp, 0o600)
    renameSync(tmp, path)
  }
  const ownerHash = hash(ownerToken)
  return {
    /** @returns {{id: string, name: string, owner: boolean} | undefined} */
    authenticate(token) {
      if (typeof token !== 'string' || token.length === 0) return undefined
      const h = hash(token)
      if (eq(h, ownerHash)) return { id: 'owner', name: 'owner', owner: true }
      const d = devices.find((x) => eq(x.tokenHash, h))
      if (!d) return undefined
      d.lastSeenAt = now(); save()
      return { id: d.id, name: d.name, owner: false }
    },
    add(name) {
      const token = randomBytes(32).toString('base64url')
      const d = { id: randomUUID(), name: String(name || 'device').slice(0, 64), tokenHash: hash(token), createdAt: now(), lastSeenAt: null }
      devices.push(d); save()
      return { deviceId: d.id, token }
    },
    list: () => devices.map(({ id, name, createdAt, lastSeenAt }) => ({ id, name, createdAt, lastSeenAt })),
    revoke(id) {
      const before = devices.length
      devices = devices.filter((d) => d.id !== id)
      if (devices.length !== before) save()
      return devices.length !== before
    },
  }
}
