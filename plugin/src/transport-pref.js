import { mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'

/** Phone path is always host:port WebSocket (Tailscale or LAN). */
export const TRANSPORTS = Object.freeze(['tailscale'])

/**
 * @param {unknown} v
 * @returns {'tailscale'|null}
 */
export function normalizeTransport(v) {
  if (v === 'tailscale') return v
  return null
}

/**
 * Resolve remote transport: explicit config > persisted last choice > default tailscale.
 * Legacy `iroh` values in the transport file are ignored (phone path no longer supports them).
 * @param {string} dataDir
 * @param {unknown} [configTransport]
 * @returns {'tailscale'}
 */
export function loadTransport(dataDir, configTransport) {
  const fromConfig = normalizeTransport(configTransport)
  if (fromConfig) return fromConfig
  try {
    const raw = readFileSync(join(dataDir, 'transport'), 'utf8').trim()
    const fromFile = normalizeTransport(raw)
    if (fromFile) return fromFile
  } catch {
    // missing file is fine
  }
  return 'tailscale'
}

/**
 * Persist last choice (ignored when cordis config.transport is set).
 * @param {string} dataDir
 * @param {'tailscale'} mode
 */
export function saveTransport(dataDir, mode) {
  const m = normalizeTransport(mode)
  if (!m) throw new Error(`transport must be one of: ${TRANSPORTS.join(', ')}`)
  mkdirSync(dataDir, { recursive: true })
  writeFileSync(join(dataDir, 'transport'), `${m}\n`, { mode: 0o600 })
  return m
}
