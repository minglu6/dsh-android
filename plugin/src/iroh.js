import { spawn, spawnSync } from 'node:child_process'
import { createInterface } from 'node:readline'
import { chmodSync, existsSync, mkdirSync, renameSync, unlinkSync, writeFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { createRequire } from 'node:module'
import { homedir } from 'node:os'
import { pipeline } from 'node:stream/promises'
import { createWriteStream } from 'node:fs'
import { Readable } from 'node:stream'

const here = dirname(fileURLToPath(import.meta.url))
const require = createRequire(import.meta.url)
const irohRoot = join(here, '..', 'iroh')
const hostBinName = process.platform === 'win32' ? 'pocket-host.exe' : 'pocket-host'

/** Platform → GitHub release asset name (rolling tag dsh-android-latest). */
export const PREBUILT_ASSET = {
  'darwin-arm64': 'pocket-host-darwin-arm64',
  'darwin-x64': 'pocket-host-darwin-x64',
  'linux-x64': 'pocket-host-linux-x64',
  'linux-arm64': 'pocket-host-linux-arm64',
}

const PREBUILT_BASE =
  process.env.DSH_POCKET_HOST_URL_BASE
  ?? 'https://github.com/minglu6/dsh-android/releases/download/dsh-android-latest'

function platKey() {
  return `${process.platform}-${process.arch}`
}

function cacheBinPath() {
  const dir = join(process.env.DSH_HOME ?? join(homedir(), '.dsh'), 'pocket', 'bin')
  mkdirSync(dir, { recursive: true })
  return join(dir, hostBinName)
}

/**
 * Resolve the pocket-host binary for this platform (no download / build).
 * Search order: config → env → cargo target → cache → optional npm package.
 */
export function resolveHostBinary(explicit) {
  if (explicit) return explicit
  if (process.env.DSH_POCKET_HOST) return process.env.DSH_POCKET_HOST
  const release = join(irohRoot, 'target', 'release', hostBinName)
  if (existsSync(release)) return release
  const debug = join(irohRoot, 'target', 'debug', hostBinName)
  if (existsSync(debug)) return debug
  const cached = cacheBinPath()
  if (existsSync(cached)) return cached
  const prebuilt = join(irohRoot, 'prebuilt', platKey(), hostBinName)
  if (existsSync(prebuilt)) return prebuilt
  try {
    const pkg = `dsh-pocket-host-${platKey()}`
    const entry = require.resolve(`${pkg}/package.json`)
    const bin = join(dirname(entry), hostBinName)
    if (existsSync(bin)) return bin
  } catch { /* optional dep missing */ }
  return undefined
}

function cargoAvailable() {
  const r = spawnSync('cargo', ['--version'], { encoding: 'utf8' })
  return r.status === 0
}

/** Build pocket-host via cargo in plugin/iroh (synchronous). */
export function buildHostWithCargo({ log = console.log } = {}) {
  log('[pocket] building iroh sidecar with cargo (one-time; needs Rust toolchain)…')
  const r = spawnSync(
    'cargo',
    ['build', '--release', '-p', 'pocket-host', '--manifest-path', join(irohRoot, 'Cargo.toml')],
    { stdio: 'inherit', env: process.env },
  )
  if (r.status !== 0) {
    throw new Error(`cargo build pocket-host failed (exit ${r.status})`)
  }
  const bin = join(irohRoot, 'target', 'release', hostBinName)
  if (!existsSync(bin)) throw new Error(`cargo build succeeded but ${bin} missing`)
  return bin
}

/** Download a CI-published pocket-host binary into ~/.dsh/pocket/bin. */
export async function downloadPrebuiltHost({ log = console.log } = {}) {
  const asset = PREBUILT_ASSET[platKey()]
  if (!asset) {
    throw new Error(`no prebuilt pocket-host for ${platKey()}`)
  }
  const url = `${PREBUILT_BASE.replace(/\/$/, '')}/${asset}`
  log(`[pocket] downloading iroh sidecar from ${url}`)
  const res = await fetch(url, { redirect: 'follow' })
  if (!res.ok) {
    throw new Error(`download failed: HTTP ${res.status} for ${url}`)
  }
  const dest = cacheBinPath()
  const tmp = `${dest}.tmp-${process.pid}`
  try {
    await pipeline(Readable.fromWeb(res.body), createWriteStream(tmp))
    chmodSync(tmp, 0o755)
    renameSync(tmp, dest)
  } catch (e) {
    try { unlinkSync(tmp) } catch { /* ignore */ }
    throw e
  }
  // Write a tiny stamp so we can see which asset we got.
  try {
    writeFileSync(`${dest}.url`, `${url}\n`, { mode: 0o644 })
  } catch { /* ignore */ }
  return dest
}

/**
 * Ensure a pocket-host binary exists: resolve → download prebuilt → cargo build.
 * Returns the path, or undefined when iroh cannot be enabled (caller keeps WS-only).
 */
export async function ensureHostBinary(explicit, { log = (...a) => console.log('[pocket]', ...a), allowDownload = true, allowCargo = true } = {}) {
  // Explicit path: never fall back to download/cargo (tests / config.hostBinary).
  if (explicit) return existsSync(explicit) ? explicit : undefined

  const existing = resolveHostBinary()
  if (existing && existsSync(existing)) return existing

  if (allowDownload && PREBUILT_ASSET[platKey()]) {
    try {
      return await downloadPrebuiltHost({ log: (m) => log(m.replace(/^\[pocket\]\s*/, '')) })
    } catch (e) {
      log(`prebuilt sidecar download skipped: ${e.message ?? e}`)
    }
  }

  if (allowCargo && cargoAvailable()) {
    try {
      return buildHostWithCargo({ log: (m) => log(m.replace(/^\[pocket\]\s*/, '')) })
    } catch (e) {
      log(`cargo sidecar build failed: ${e.message ?? e}`)
    }
  }

  return undefined
}

function missingSidecarHelp() {
  const plat = platKey()
  const asset = PREBUILT_ASSET[plat]
  return [
    `iroh sidecar not found for ${plat} — remote phone pairing is unavailable.`,
    'Phones connect only over iroh (direct hole-punch, else relay); no Tailscale/VPN.',
    'One-line install (from repo):  cd plugin && npm run build:iroh',
    asset
      ? `Or wait for / download the CI prebuilt: ${PREBUILT_BASE}/${asset}`
      : 'Or install Rust (https://rustup.rs) and re-run; the plugin auto-builds on next start.',
    'Override path with env DSH_POCKET_HOST=/path/to/pocket-host',
  ].join('\n[pocket] ')
}

/**
 * Spawn pocket-host and bridge peer sessions into the pocket server as pipe clients.
 * @param {{ server: any, dataDir: string, hostBinary?: string, log?: (...a:any)=>void }} opts
 * @returns {Promise<{ endpointId: Promise<string>, close: () => Promise<void> } | null>}
 */
export async function startIrohSidecar(opts) {
  const log = opts.log ?? ((...a) => console.log('[pocket]', ...a))
  const binary = await ensureHostBinary(opts.hostBinary, { log })
  if (!binary || !existsSync(binary)) {
    log(missingSidecarHelp())
    return null
  }
  log(`iroh sidecar: ${binary}`)
  const args = ['host', '--data-dir', opts.dataDir]
  const child = spawn(binary, args, { stdio: ['pipe', 'pipe', 'inherit'], windowsHide: true })
  let gone
  child.on('exit', (code) => {
    gone = `sidecar exited (code=${code})`
    console.error(`[pocket] iroh ${gone}`)
  })
  child.on('error', (error) => {
    gone = `sidecar failed to start: ${error.message}`
    console.error(`[pocket] iroh ${gone} (${binary})`)
  })

  const send = (msg) => {
    if (gone || !child.stdin.writable) return
    child.stdin.write(`${JSON.stringify(msg)}\n`)
  }

  let resolveReady
  const endpointId = new Promise((r) => { resolveReady = r })
  /** @type {Map<string, { push(line: string): void, close(): void }>} */
  const sessions = new Map()

  const rl = createInterface({ input: child.stdout })
  rl.on('line', (line) => {
    let msg
    try { msg = JSON.parse(line) } catch {
      console.error(`[pocket] bad sidecar line: ${line}`)
      return
    }
    switch (msg.type) {
      case 'ready':
        resolveReady(msg.endpoint_id)
        log(`iroh ready, node id: ${msg.endpoint_id}`)
        break
      case 'peer-session': {
        const id = msg.id
        const peer = msg.peer
        const client = opts.server.attachPipe({
          send: (frame) => send({ type: 'session-send', id, line: JSON.stringify(frame) }),
          close: () => send({ type: 'session-close', id }),
          meta: { transport: 'iroh', peer },
        })
        sessions.set(id, client)
        log(`iroh peer session ${id} from ${String(peer).slice(0, 16)}…`)
        break
      }
      case 'peer-data': {
        const client = sessions.get(msg.id)
        if (!client) return
        try {
          const frame = JSON.parse(msg.line)
          client.onMessage(frame, msg.line.length)
        } catch {
          client.close(1007, 'bad json')
        }
        break
      }
      case 'peer-path':
        log(`iroh path ${msg.kind} ${msg.remote}`)
        opts.server.noteIrohPath?.(msg.peer, msg.kind)
        break
      case 'peer-session-end': {
        const client = sessions.get(msg.id)
        sessions.delete(msg.id)
        if (msg.peer) opts.server.clearIrohPath?.(msg.peer)
        client?.onRemoteClose()
        break
      }
      default:
        console.error(`[pocket] unknown sidecar message: ${line}`)
    }
  })

  return {
    endpointId,
    /** @returns {Promise<void>} */
    close: () => new Promise((resolve) => {
      const done = () => resolve()
      try { rl.close() } catch { /* ignore */ }
      try { child.stdin?.destroy() } catch { /* ignore */ }
      try { child.stdout?.destroy() } catch { /* ignore */ }
      if (child.exitCode !== null || child.signalCode !== null) { done(); return }
      const t = setTimeout(() => { try { child.kill('SIGKILL') } catch { /* ignore */ }; done() }, 1500)
      child.once('exit', () => { clearTimeout(t); done() })
      try { child.kill('SIGKILL') } catch { clearTimeout(t); done() }
    }),
  }
}
