import { homedir } from 'node:os'
import { join } from 'node:path'
import { createPocketServer } from './src/server.js'
import { loadOrCreateToken } from './src/token.js'

export const name = 'dsh-plugin-pocket'
export const inject = ['sessionController']

const SESSION_EVENTS = ['added', 'removed', 'status', 'activity', 'error']

/**
 * @param {import('@deepseek-ai/cordis').Context} ctx
 * @param {{host?: string, port?: number, token?: string, dataDir?: string}} [config]
 */
export function apply(ctx, config = {}) {
  const dataDir = config.dataDir ?? join(process.env.DSH_HOME ?? join(homedir(), '.dsh'), 'pocket')
  const { token, created, path } = loadOrCreateToken(dataDir, config.token)
  const listeners = new Set()
  const server = createPocketServer({
    sessionController: ctx.sessionController,
    token,
    host: config.host ?? '127.0.0.1',
    port: config.port ?? 7690,
    onSessionEvent: (fn) => { listeners.add(fn); return () => listeners.delete(fn) },
    log: (...a) => console.log('[pocket]', ...a),
  })
  for (const kind of SESSION_EVENTS) {
    ctx.on(`api-session/${kind}`, (...args) => {
      for (const fn of listeners) fn(kind, args)
    })
  }
  ctx.effect(() => {
    server.listen().then(
      (addr) => {
        console.log(`[pocket] listening on ws://${addr}`)
        console.log(created ? `[pocket] new token written to ${path}: ${token}` : `[pocket] token: ${path}`)
      },
      (error) => console.error('[pocket] listen failed:', error),
    )
    return () => server.close()
  })
}
