export type PairingStatus = {
  open: boolean
  code?: string
  expiresInSec?: number
  attemptsLeft?: number
  transport?: 'iroh' | 'tailscale'
  host?: string
  port?: number
  nodeId?: string
  uri?: string
  pairingString?: string
  irohReady?: boolean
  error?: string
}

export type DeviceRow = {
  id: string
  name: string
  createdAt: number
  lastSeenAt: number | null
  online: boolean
}

export type PocketStatus = {
  running: boolean
  version: string
  plugin: string
  /** Selected remote path: `iroh` | `tailscale` (no auto-fallback). */
  transport?: 'iroh' | 'tailscale'
  host: string
  port: number
  nodeId?: string | null
  irohReady?: boolean
  listen: Array<{ host: string, port: number, address: string }>
  pairing: PairingStatus
  devices: DeviceRow[]
}

async function readBody(res: Response): Promise<unknown> {
  if (typeof res?.json === 'function') {
    try { return await res.json() } catch { /* fall through */ }
  }
  if (typeof res?.text === 'function') {
    try { return await res.text() } catch { /* fall through */ }
  }
  return undefined
}

async function parse<T>(res: Response): Promise<T> {
  if (!res || res.ok !== true) {
    const body = await readBody(res)
    const detail = body === undefined
      ? `HTTP ${res?.status ?? '?'}`
      : (typeof body === 'string' ? body : JSON.stringify(body))
    throw new Error(detail || `HTTP ${res?.status ?? '?'}`)
  }
  return (await readBody(res)) as T
}

/** Same-origin fetch helpers for the Host management routes. */
export function createPocketApi(base = '') {
  const url = (path: string) => `${base}${path}`
  const get = async <T>(path: string, init?: RequestInit) => parse<T>(await fetch(url(path), init))
  return {
    status: () => get<PocketStatus>('/pocket/status'),
    setTransport: (transport: 'iroh' | 'tailscale') => get<PocketStatus>(
      '/pocket/status', {
        method: 'PATCH',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify({ transport }),
      },
    ),
    pairStatus: () => get<PairingStatus>('/pocket/pair'),
    openPair: () => get<PairingStatus & { nodeId?: string, uri?: string, pairingString?: string }>(
      '/pocket/pair', { method: 'POST' },
    ),
    closePair: () => get<{ open: false }>('/pocket/pair', { method: 'DELETE' }),
    listDevices: () => get<{ devices: DeviceRow[] }>('/pocket/devices').then((r) => r.devices),
    revoke: (id: string) => get<{ removed: boolean, devices: DeviceRow[] }>(
      '/pocket/devices', {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify({ id }),
      },
    ),
    rename: (id: string, name: string) => get<{ renamed: boolean, devices: DeviceRow[] }>(
      '/pocket/devices', {
        method: 'PATCH',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify({ id, name }),
      },
    ),
  }
}

export type PocketApi = ReturnType<typeof createPocketApi>
