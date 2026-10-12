import { spawn } from 'node:child_process'
import { createInterface } from 'node:readline'
import { existsSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { createRequire } from 'node:module'

const here = dirname(fileURLToPath(import.meta.url))
const require = createRequire(import.meta.url)

/**
 * Resolve the pocket-host binary for this platform.
 * Search order: config.hostBinary → env → cargo target → optional npm package.
 */
export function resolveHostBinary(explicit) {
  if (explicit) return explicit
  if (process.env.DSH_POCKET_HOST) return process.env.DSH_POCKET_HOST
  const plat = `${process.platform}-${process.arch}`
  const cargo = join(here, '..', 'iroh', 'target', 'release', process.platform === 'win32' ? 'pocket-host.exe' : 'pocket-host')
  if (existsSync(cargo)) return cargo
  const debug = join(here, '..', 'iroh', 'target', 'debug', process.platform === 'win32' ? 'pocket-host.exe' : 'pocket-host')
  if (existsSync(debug)) return debug
  try {
    const pkg = `dsh-pocket-host-${plat}`
    const entry = require.resolve(`${pkg}/package.json`)
    const bin = join(dirname(entry), process.platform === 'win32' ? 'pocket-host.exe' : 'pocket-host')
    if (existsSync(bin)) return bin
  } catch { /* optional dep missing */ }
  return undefined
}

/**
 * Spawn pocket-host and bridge peer sessions into the pocket server as pipe clients.
 * @param {{ server: any, dataDir: string, hostBinary?: string, log?: (...a:any)=>void }} opts
 * @returns {{ endpointId: Promise<string>, close: () => void } | null}
 */
export function startIrohSidecar(opts) {
  const log = opts.log ?? ((...a) => console.log('[pocket]', ...a))
  const binary = resolveHostBinary(opts.hostBinary)
  if (!binary || !existsSync(binary)) {
    log('iroh sidecar binary not found — WS-only mode (build plugin/iroh or set DSH_POCKET_HOST)')
    return null
  }
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
