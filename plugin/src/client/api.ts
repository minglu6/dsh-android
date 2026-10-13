export type NetworkType = 'lan' | 'tailscale'

export type PairingStatus = {
  open: boolean
  code?: string
  expiresInSec?: number
  attemptsLeft?: number
  transport?: 'tailscale'
  host?: string
  port?: number
  uri?: string
  pairingString?: string
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
  /** Phone path is always host:port WebSocket. */
  transport?: 'tailscale'
  host: string
  port: number
  connectionType: NetworkType | null
  networks: Array<{ type: NetworkType, host: string }>
  listen: Array<{ host: string, port: number, address: string }>
  pairing: PairingStatus
  devices: DeviceRow[]
}

export type ConnectionDiagnostics = {
  version: number
  generatedAt: number
  overall: string
  summary: string
  report: string
  checks: Array<{ id: string, status: string, label: string, detail: string }>
}

export interface PocketApi {
  status(network?: NetworkType): Promise<PocketStatus>
  pairStatus(network?: NetworkType): Promise<PairingStatus>
  openPair(network?: NetworkType): Promise<PairingStatus>
  closePair(): Promise<{ open: false }>
  listDevices(): Promise<DeviceRow[]>
  revoke(id: string): Promise<{ removed: boolean, devices: DeviceRow[] }>
  rename(id: string, name: string): Promise<{ renamed: boolean, devices: DeviceRow[] }>
  diagnostics(): Promise<ConnectionDiagnostics>
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
export function createPocketApi(base = ''): PocketApi {
  const get = async <T>(path: string, init?: RequestInit) => parse<T>(await fetch(`${base}${path}`, init))
  const networkPath = (path: string, network?: NetworkType) => network ? `${path}?network=${network}` : path
  return {
    status: (network?: NetworkType) => get<PocketStatus>(networkPath('/pocket/status', network)),
    pairStatus: (network?: NetworkType) => get<PairingStatus>(networkPath('/pocket/pair', network)),
    openPair: (network?: NetworkType) => get<PairingStatus>(
      networkPath('/pocket/pair', network), { method: 'POST' },
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
    diagnostics: () => get<ConnectionDiagnostics>('/pocket/diagnostics'),
  }
}
