export type PairingStatus = {
  open: boolean
  code?: string
  expiresInSec?: number
  attemptsLeft?: number
  host?: string
  port?: number
  uri?: string
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
  host: string
  port: number
  listen: Array<{ host: string, port: number, address: string }>
  pairing: PairingStatus
  devices: DeviceRow[]
}

async function parse<T>(res: Response): Promise<T> {
  if (!res.ok) {
    const text = await res.text().catch(() => '')
    throw new Error(text || `HTTP ${res.status}`)
  }
  return res.json() as Promise<T>
}

/** Same-origin fetch helpers for the Host management routes. */
export function createPocketApi(base = '') {
  const url = (path: string) => `${base}${path}`
  return {
    status: () => parse<PocketStatus>(fetch(url('/pocket/status'))),
    pairStatus: () => parse<PairingStatus>(fetch(url('/pocket/pair'))),
    openPair: () => parse<PairingStatus & { host: string, port: number, uri: string }>(
      fetch(url('/pocket/pair'), { method: 'POST' }),
    ),
    closePair: () => parse<{ open: false }>(fetch(url('/pocket/pair'), { method: 'DELETE' })),
    listDevices: () => parse<{ devices: DeviceRow[] }>(fetch(url('/pocket/devices'))).then((r) => r.devices),
    revoke: (id: string) => parse<{ removed: boolean, devices: DeviceRow[] }>(
      fetch(url('/pocket/devices'), {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify({ id }),
      }),
    ),
    rename: (id: string, name: string) => parse<{ renamed: boolean, devices: DeviceRow[] }>(
      fetch(url('/pocket/devices'), {
        method: 'PATCH',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify({ id, name }),
      }),
    ),
  }
}

export type PocketApi = ReturnType<typeof createPocketApi>
