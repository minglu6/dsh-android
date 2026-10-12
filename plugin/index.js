import { homedir } from 'node:os'
import { join } from 'node:path'
import { createApprovalHook } from './src/approval-hook.js'
import { createDeviceStore } from './src/devices.js'
import { createPairing } from './src/pairing.js'
import { createPocketServer } from './src/server.js'
import { loadOrCreateToken } from './src/token.js'
import { permissionOps, presetOps } from './src/extra-ops.js'
import { registerPairRoute } from './src/web-route.js'

export const name = 'dsh-plugin-pocket'
// webServer is optional: Cordis `inject` entries are all required, so the
// loopback pairing route is mounted through a nested ctx.inject(['webServer'])
// that only runs when (and while) a webServer service exists.
export const inject = ['sessionController']

const SESSION_EVENTS = ['added', 'removed', 'status', 'activity', 'error']

/**
 * @param {import('@deepseek-ai/cordis').Context} ctx
 * @param {{host?: string, port?: number, token?: string, dataDir?: string, pair?: boolean}} [config]
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
    onSessionEvent: (fn) => { listeners.add(fn); return () => listeners.delete(fn) },
  })
  for (const kind of SESSION_EVENTS) {
    ctx.on(`api-session/${kind}`, (...args) => { for (const fn of listeners) fn(kind, args) })
  }
  // prepend: run outside the web UI forwarder so we can race it.
  ctx.on('approval/request', createApprovalHook(server, { log }), { prepend: true })

  const openPairing = () => {
    const w = pairing.open()
    log(`pairing code ${w.code} (valid ${Math.round(w.expiresInSec / 60)} min) — dshpocket://pair?h=${host}:${port}&c=${w.code}`)
    return { ...w, host, port }
  }
  ctx.inject(['permissionPresets', 'agents'], (c) => { c.effect(() => server.registerOps(permissionOps(c.permissionPresets, c.agents))) })
  ctx.inject(['agentPresets'], (c) => { c.effect(() => server.registerOps(presetOps(c.agentPresets))) })
  ctx.inject(['webServer'], (webCtx) => registerPairRoute(webCtx, { openPairing, devices, pairing }))

  ctx.effect(() => {
    server.listen().then(
      (addr) => {
        log(`listening on ws://${addr}`)
        log(created ? `new owner token written to ${path}: ${token}` : `owner token: ${path}`)
        if (config.pair || devices.list().length === 0) openPairing()
      },
      (error) => console.error('[pocket] listen failed:', error),
    )
    return () => server.close()
  })
}
