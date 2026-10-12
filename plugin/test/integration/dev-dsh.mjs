// Dev helper: boot a throwaway `dsh web` with this plugin (+ test approval trigger) and keep it running.
// Usage: node test/integration/dev-dsh.mjs [pocketPort] [triggerPort]
import { resolve } from 'node:path'
import { bootDsh, PLUGIN_DIR } from './helpers.js'

const port = Number(process.argv[2] ?? 7690)
const trigger = Number(process.argv[3] ?? 7799)
const token = 'dev-token-0123456789abcdef'
const cleanups = []
const { home, webUrl } = await bootDsh({ after: (f) => cleanups.push(f) }, {
  plugins: [PLUGIN_DIR, resolve(import.meta.dirname, '../fixtures/approval-trigger')],
  patch: `- id: dsh-pocket\n  config:\n    port: ${port}\n    host: 0.0.0.0\n    token: ${token}\n- id: pocket-approval-trigger\n  config:\n    port: ${trigger}\n`,
})
console.log(JSON.stringify({ ready: true, home, webUrl, url: `ws://127.0.0.1:${port}`, token, trigger }))
const stop = () => { for (const f of cleanups) f(); process.exit(0) }
process.on('SIGINT', stop); process.on('SIGTERM', stop)
setInterval(() => {}, 1 << 30)
