import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync, existsSync } from 'node:fs'
import { resolve, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'
import { createRequire } from 'node:module'

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const clientPath = resolve(root, 'lib/client.js')
const pkg = JSON.parse(readFileSync(resolve(root, 'package.json'), 'utf8'))

test('package declares official dsh.client half', () => {
  assert.equal(pkg.exports['./client'], './lib/client.js')
  assert.ok(pkg.dsh?.client?.platform === 'web')
  assert.ok(Array.isArray(pkg.dsh.client.inject))
  assert.ok(pkg.dsh.client.inject.includes('@deepseek-ai/dsh-client-ui-sidebar'))
  assert.ok(pkg.dsh.client.inject.includes('@deepseek-ai/dsh-client-locale'))
})

test('built client bundle is ModuleLoader lazy-CJS with static inject', () => {
  assert.ok(existsSync(clientPath), 'run npm run build:client first')
  const src = readFileSync(clientPath, 'utf8')
  assert.match(src, /window\.__ModuleLoader__\.load\(\{ id: "dsh-plugin-pocket"/)
  assert.match(src, /factory: \(require\) =>/)
  assert.match(src, /sidebar\.footer\.action/)
  assert.match(src, /shell\.overlay/)
  assert.match(src, /settings\.section/)
  assert.match(src, /手机连接/)
  assert.match(src, /Phone connection/)

  // Evaluate factory in a stub ModuleLoader to assert inject is a static array.
  const requires = {
    react: { useState: () => [], useEffect: () => {}, useCallback: () => {}, useSyncExternalStore: () => false },
    'react/jsx-runtime': { jsx: () => null, jsxs: () => null, Fragment: 'fragment' },
    '@deepseek-ai/cordis': {},
  }
  let exported
  globalThis.window = {
    __ModuleLoader__: {
      load({ id, factory }) {
        assert.equal(id, 'dsh-plugin-pocket')
        exported = factory((name) => {
          if (!Object.hasOwn(requires, name)) throw new Error(`unexpected require ${name}`)
          return requires[name]
        })
      },
    },
  }
  const req = createRequire(import.meta.url)
  // Execute as a classic script via Function — the bundle assigns to window.
  const code = readFileSync(clientPath, 'utf8')
  new Function(code)()
  assert.ok(exported)
  assert.ok(Array.isArray(exported.inject), 'inject must be a static array')
  assert.deepEqual([...exported.inject], ['slots', 'locale'])
  assert.equal(typeof exported.apply, 'function')
  delete globalThis.window
  void req
})
