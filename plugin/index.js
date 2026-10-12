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
import { createManageApi, pairingUri } from './src/manage.js'
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

  /** @type {{ endpointId: Promise<string>, close: () => void } | null} */
  let iroh = null
  /** @type {string | null} */
  let nodeId = null
  if (config.iroh !== false) {
    iroh = startIrohSidecar({ server, dataDir: join(dataDir, 'iroh'), hostBinary: config.hostBinary, log })
    if (iroh) {
      void iroh.endpointId.then((id) => { nodeId = id })
    }
  }

  const openPairing = () => {
    const w = pairing.open()
    const uri = pairingUri({ host, port, code: w.code, nodeId })
    log(`pairing code ${w.code} (valid ${Math.round(w.expiresInSec / 60)} min) — ${uri}`)
    return { ...w, host, port, nodeId, uri }
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
      const addr = await server.listen()
      log(`listening on ws://${addr}`)
      log(created ? `new owner token written to ${path}: ${token}` : `owner token: ${path}`)
      if (iroh) {
        try {
          nodeId = await Promise.race([
            iroh.endpointId,
            new Promise((_, rej) => setTimeout(() => rej(new Error('iroh ready timeout')), 15_000)),
          ])
        } catch (e) {
          console.error('[pocket] iroh sidecar did not become ready:', e.message ?? e)
          iroh.close()
          iroh = null
          nodeId = null
        }
      }
      if (config.pair || devices.list().length === 0) openPairing()
    }
    start().catch((error) => console.error('[pocket] listen failed:', error))
    return () => {
      void iroh?.close()
      void server.close()
    }
  })
}
