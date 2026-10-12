import { homedir } from 'node:os'
import { dirname, join } from 'node:path'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { createApprovalHook } from './src/approval-hook.js'
import { createDeviceStore } from './src/devices.js'
import { createPairing } from './src/pairing.js'
import { createPocketServer } from './src/server.js'
import { loadOrCreateToken } from './src/token.js'
import { zipSync, strToU8 } from 'fflate'
import { permissionOps, presetOps, commandOps, filesOps, jobsOps, exportOps, terminalOps } from './src/extra-ops.js'
import { registerPairRoute } from './src/web-route.js'
import { createManageApi, pairingUri, pairingString } from './src/manage.js'
import { startIrohSidecar } from './src/iroh.js'

export const name = 'dsh-plugin-pocket'
// webServer is optional: Cordis `inject` entries are all required, so the
// loopback pairing route is mounted through a nested ctx.inject(['webServer'])
// that only runs when (and while) a webServer service exists.
export const inject = ['sessionController']

const SESSION_EVENTS = ['added', 'removed', 'status', 'activity', 'error']
const PKG = JSON.parse(readFileSync(join(dirname(fileURLToPath(import.meta.url)), 'package.json'), 'utf8'))

/**
 * @param {import('@deepseek-ai/cordis').Context} ctx
 * @param {{host?: string, port?: number, token?: string, dataDir?: string, pair?: boolean, iroh?: boolean, hostBinary?: string}} [config]
 */
export function apply(ctx, config = {}) {
  const log = (...a) => console.log('[pocket]', ...a)
  const dataDir = config.dataDir ?? join(process.env.DSH_HOME ?? join(homedir(), '.dsh'), 'pocket')
  const { token, created, path } = loadOrCreateToken(dataDir, config.token)
  const devices = createDeviceStore({ dataDir, ownerToken: token })
  const pairing = createPairing()
  const listeners = new Set()
  // Loopback WS is internal/dev only — never advertise a Tailscale/LAN bind by default.
  const host = config.host ?? '127.0.0.1'
  const port = config.port ?? 7690
  const server = createPocketServer({
    sessionController: ctx.sessionController, token, host, port, devices, pairing,
    info: { plugin: PKG.name, version: PKG.version },
    onSessionEvent: (fn) => { listeners.add(fn); return () => listeners.delete(fn) },
  })
  for (const kind of SESSION_EVENTS) {
    ctx.on(`api-session/${kind}`, (...args) => { for (const fn of listeners) fn(kind, args) })
  }
  // prepend: run outside the web UI forwarder so we can race it.
  ctx.on('approval/request', createApprovalHook(server, { log }), { prepend: true })

  /** @type {{ endpointId: Promise<string>, close: () => Promise<void> } | null} */
  let iroh = null
  /** @type {string | null} */
  let nodeId = null

  const openPairing = () => {
    if (!nodeId) throw new Error('iroh sidecar is not ready — cannot open remote pairing')
    const w = pairing.open()
    const uri = pairingUri({ code: w.code, nodeId })
    const line = pairingString({ code: w.code, nodeId })
    log(`pairing string (${Math.round(w.expiresInSec / 60)} min): ${line}`)
    return { ...w, nodeId, uri, pairingString: line }
  }
  const manage = createManageApi({
    host, port, devices, pairing, server, openPairing,
    getNodeId: () => nodeId,
  })
  ctx.inject(['permissionPresets', 'agents'], (c) => { c.effect(() => server.registerOps(permissionOps(c.permissionPresets, c.agents))) })
  ctx.inject(['agentPresets'], (c) => { c.effect(() => server.registerOps(presetOps(c.agentPresets))) })
  ctx.inject(['commands', 'agents'], (c) => { c.effect(() => server.registerOps(commandOps(c.commands, c.agents))) })
  ctx.inject(['workspaceFiles', 'sessions'], (c) => {
    c.effect(() => server.registerOps(filesOps(c.workspaceFiles, c.sessions, c.get?.('sandboxPolicy'))))
  })
  ctx.inject(['jobController'], (c) => { c.effect(() => server.registerOps(jobsOps(c.jobController))) })
  ctx.inject(['terminalController', 'agents'], (c) => {
    c.effect(() => server.registerOps(terminalOps(c.terminalController, c.agents)))
  })
  server.registerOps(exportOps(ctx.sessionController, { zipSync, strToU8 }))
  ctx.inject(['webServer'], (webCtx) => registerPairRoute(webCtx, manage))

  ctx.effect(() => {
    const start = async () => {
      // Start iroh first — remote phones connect only over P2P/relay.
      if (config.iroh !== false) {
        iroh = await startIrohSidecar({
          server,
          dataDir: join(dataDir, 'iroh'),
          hostBinary: config.hostBinary,
          log,
        })
        if (iroh) {
          try {
            nodeId = await Promise.race([
              iroh.endpointId,
              new Promise((_, rej) => setTimeout(() => rej(new Error('iroh ready timeout')), 60_000)),
            ])
          } catch (e) {
            console.error('[pocket] iroh sidecar did not become ready:', e.message ?? e)
            await iroh.close()
            iroh = null
            nodeId = null
          }
        }
      }

      // Loopback WS for local tooling / tests (not user-facing remote path).
      const addr = await server.listen()
      log(`local ws://${addr} (dev/loopback only)`)
      log(created ? `new owner token written to ${path}: ${token}` : `owner token: ${path}`)
      if (!nodeId) {
        log('iroh not ready — remote phone pairing unavailable until pocket-host starts')
      }

      if (nodeId && (config.pair || devices.list().length === 0)) {
        try { openPairing() } catch (e) { console.error('[pocket]', e.message ?? e) }
      }
    }
    start().catch((error) => console.error('[pocket] start failed:', error))
    return () => {
      void iroh?.close()
      void server.close()
    }
  })
}
