// Apply the unmodified upstream FortuneSheet fixes to the exact locked releases.
import { spawnSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'
import { resolve } from 'node:path'
const root = fileURLToPath(new URL('.', import.meta.url))
// node_modules lives inside this repository. Prevent git apply from silently
// skipping package-relative paths as outside the repository's current prefix.
const env = { ...process.env, GIT_CEILING_DIRECTORIES: resolve(root, 'node_modules') }
delete env.GIT_DIR
delete env.GIT_WORK_TREE
for (const name of ['core', 'react']) {
  const cwd = resolve(root, `node_modules/@fortune-sheet/${name}`)
  const patch = resolve(root, `patches/@fortune-sheet__${name}@1.0.4.patch`)
  const run = args => spawnSync('git', ['apply', ...args, patch], { cwd, env, encoding: 'utf8' })
  if (run(['--reverse', '--check']).status === 0) continue
  const result = run([])
  if (result.status !== 0) throw new Error(`Cannot apply FortuneSheet ${name} patch: ${result.stderr}`)
}
