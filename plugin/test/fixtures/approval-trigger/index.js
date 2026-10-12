// TEST ONLY. Fires the `approval/request` waterfall exactly like
// ApprovalService.decide() does: ctx.waterfall(scopeTarget(agent, agent), ...)
// so the real web-UI forwarder (api-remotes) and the pocket plugin both see it.
import { createRequire } from 'node:module'
import { realpathSync } from 'node:fs'
import { createServer } from 'node:http'
import { pathToFileURL } from 'node:url'

export const name = 'dsh-pocket-test-approval-trigger'
export const inject = ['agents']

async function loadScope() {
  const req = createRequire(realpathSync(process.argv[1]))
  const pkg = req.resolve('@deepseek-ai/dsh-scope/package.json')
  return import(pathToFileURL(pkg.replace(/package\.json$/, 'lib/index.js')).href)
}

export function apply(ctx, config = {}) {
  const pending = new Map()
  if (config.trace) {
    ctx.on('approval/request', async function (req, next) {
      const scope = await loadScope()
      console.log('[trigger] trace listener carrier=', scope.carrierKeyOf(this) === req.agent)
      const out = await next()
      console.log('[trigger] trace next ->', out)
      return out
    })
  }
  const server = createServer(async (req, res) => {
    let body = ''
    for await (const c of req) body += c
    const args = body ? JSON.parse(body) : {}
    const send = (s, v) => { res.writeHead(s, { 'content-type': 'application/json' }); res.end(JSON.stringify(v)) }
    try {
      if (req.url === '/trigger') {
        const agent = ctx.agents.get(args.sessionId)
        if (!agent) return send(404, { error: 'no live agent' })
        const { scopeTarget } = await loadScope()
        const ac = new AbortController()
        pending.set(args.key, ac)
        const request = { agent, toolName: args.toolName ?? 'bash', ...(args.callId ? { callId: args.callId } : {}), reason: 'pocket integration test', signal: ac.signal }
        const outcome = await ctx.waterfall(scopeTarget(agent, agent), 'approval/request', request, () => Promise.resolve('unavailable'))
        return send(200, { outcome })
      }
      if (req.url === '/diag') {
        const agent = ctx.agents.get(args.sessionId)
        const scope = await loadScope()
        return send(200, { hasAgent: !!agent, scopeOfAgentCtx: String(scope.scopeOf?.(agent.ctx)), exports: Object.keys(scope) })
      }
      if (req.url === '/abort') { pending.get(args.key)?.abort(); return send(200, {}) }
      send(404, {})
    } catch (error) { send(500, { error: String(error?.stack ?? error) }) }
  })
  ctx.effect(() => {
    server.listen(config.port ?? 7799, '127.0.0.1', () => console.log(`[trigger] listening ${config.port ?? 7799}`))
    return () => server.close()
  })
}
