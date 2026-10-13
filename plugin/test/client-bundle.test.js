import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { createRequire } from 'node:module'
import { runInNewContext } from 'node:vm'

const require = createRequire(import.meta.url)

test('client bundle loads with the public browser dependencies and Cordis injection contract', () => {
  let exported
  runInNewContext(readFileSync(resolve(import.meta.dirname, '../lib/client.js'), 'utf8'), {
    window: {
      __ModuleLoader__: {
        load({ id, factory }) {
          assert.equal(id, 'dsh-plugin-pocket')
          exported = factory((name) => {
            assert.ok(['react', 'react-dom', 'react/jsx-runtime', '@deepseek-ai/cordis'].includes(name), `unsupported browser dependency: ${name}`)
            return require(name)
          })
        },
      },
    },
  })
  assert.deepEqual([...exported.inject], ['slots', 'locale'])
  assert.equal(typeof exported.apply, 'function')
})
