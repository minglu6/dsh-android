import { mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'

export const TRANSPORTS = Object.freeze(['iroh', 'tailscale'])

/**
 * @param {unknown} v
 * @returns {'iroh'|'tailscale'|null}
 */
export function normalizeTransport(v) {
  if (v === 'iroh' || v === 'tailscale') return v
  return null
}

/**
 * Resolve remote transport: explicit config > persisted last choice > default iroh.
 * @param {string} dataDir
 * @param {unknown} [configTransport]
 * @returns {'iroh'|'tailscale'}
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
  return 'iroh'
}

/**
 * Persist last UI / API choice (ignored when cordis config.transport is set).
 * @param {string} dataDir
 * @param {'iroh'|'tailscale'} mode
 */
export function saveTransport(dataDir, mode) {
  const m = normalizeTransport(mode)
  if (!m) throw new Error(`transport must be one of: ${TRANSPORTS.join(', ')}`)
  mkdirSync(dataDir, { recursive: true })
  writeFileSync(join(dataDir, 'transport'), `${m}\n`, { mode: 0o600 })
  return m
}
