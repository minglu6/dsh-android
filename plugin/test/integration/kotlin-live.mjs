// Boots a real `dsh web` with this plugin, then runs the Kotlin protocol tests against it.
// Usage: node test/integration/kotlin-live.mjs   (needs dsh + pnpm on PATH, JDK 17)
import { spawnSync } from 'node:child_process'
import { resolve } from 'node:path'
import { bootDsh, PLUGIN_DIR } from './helpers.js'

const token = 'kotlin-live-token-0123456789'
const port = 19500 + Math.floor(Math.random() * 400)
const cleanups = []
const t = { after: (f) => cleanups.push(f) }
let code = 1
try {
  await bootDsh(t, { plugins: [PLUGIN_DIR], patch: `- id: dsh-pocket\n  config:\n    port: ${port}\n    token: ${token}\n` })
  const appDir = resolve(PLUGIN_DIR, '../app')
  const r = spawnSync('./gradlew', ['--no-daemon', ':core:protocol:test', '--rerun-tasks', '-i'], {
    cwd: appDir, stdio: ['ignore', 'pipe', 'inherit'], encoding: 'utf8',
    env: { ...process.env, DSH_POCKET_URL: `ws://127.0.0.1:${port}`, DSH_POCKET_TOKEN: token },
  })
  const out = r.stdout ?? ''
  process.stdout.write(out.split('\n').filter((l) => /Test|SKIP|FAIL|BUILD/.test(l)).join('\n') + '\n')
  if (out.includes('SKIP: DSH_POCKET_URL')) throw new Error('live test was skipped')
  code = r.status ?? 1
} finally {
  for (const f of cleanups) f()
}
process.exit(code)
