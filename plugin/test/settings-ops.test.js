import { test } from 'node:test'
import assert from 'node:assert/strict'
import {
  deriveKeyRef, getPath, joinProviderDirectory,
  modelsSettingsOps, pluginsOps, pluginSettingsOps, credentialsOps,
} from '../src/settings-ops.js'

test('deriveKeyRef matches desktop ui-settings-models', () => {
  assert.equal(deriveKeyRef('anthropic'), 'ANTHROPIC_API_KEY')
  assert.equal(deriveKeyRef('minimax-cn'), 'MINIMAX_CN_API_KEY')
  assert.equal(deriveKeyRef('openai'), 'OPENAI_API_KEY')
})

test('joinProviderDirectory prefers account/official then declared order', () => {
  const rows = joinProviderDirectory(
    [{ id: 'openai', name: 'OpenAI' }, { id: 'custom', name: 'Custom' }],
    [
      { provider: 'deepseek-official', displayName: 'DeepSeek', settingsNs: 'llm-deepseek', settingsPath: ['providers', 'deepseek-official'] },
      { provider: 'openai', displayName: 'OpenAI', settingsNs: 'llm-pi-ai', settingsPath: ['providers', 'openai'] },
    ],
  )
  assert.equal(rows[0].provider, 'deepseek-official')
  assert.equal(rows[1].provider, 'openai')
  assert.equal(rows[1].active, true)
  assert.equal(rows.some((r) => r.provider === 'custom'), true)
})

test('getPath walks nested objects', () => {
  assert.equal(getPath({ a: { b: 1 } }, ['a', 'b']), 1)
  assert.equal(getPath({ a: 1 }, ['a', 'b']), undefined)
})

test('models.providers joins directory, settings, and credential describe', async () => {
  const ops = modelsSettingsOps({
    llm: {
      listProviders: () => [{ id: 'openai', name: 'OpenAI' }],
      listConfigurableProviders: () => [{
        provider: 'openai', displayName: 'OpenAI', settingsNs: 'llm-pi-ai',
        settingsPath: ['providers', 'openai'],
      }],
    },
    settings: {
      describe: () => [{
        ns: 'llm-pi-ai', revision: 1,
        value: { providers: { openai: { apiKeyEnv: 'OPENAI_API_KEY', models: [{ id: 'gpt-4o' }] } } },
        user: { providers: { openai: { apiKeyEnv: 'OPENAI_API_KEY' } } },
        base: {},
        schema: {},
      }],
      writable: true,
    },
    credentials: {
      describe: async (ref) => ({ configured: ref === 'OPENAI_API_KEY', source: 'file', writable: true }),
    },
  })
  const listed = await ops['models.providers']()
  assert.equal(listed.writable, true)
  assert.equal(listed.rows[0].provider, 'openai')
  assert.equal(listed.rows[0].configured, true)
  assert.equal(listed.rows[0].apiKeyEnv, 'OPENAI_API_KEY')
  assert.equal(listed.rows[0].credential.configured, true)
  assert.deepEqual(listed.rows[0].profile.models, [{ id: 'gpt-4o' }])
})


test('credentials.list describes refs without values; set/unset round-trip', async () => {
  const store = new Map([['OPENAI_API_KEY', 'sk-secret']])
  const ops = credentialsOps({
    credentials: {
      describe: async (ref) => ({
        configured: store.has(ref),
        ...(store.has(ref) ? { source: 'file' } : {}),
        writable: true,
      }),
      set: async (ref, value) => { store.set(ref, value) },
      unset: async (ref) => { store.delete(ref) },
      listRecords: async () => [{ key: 'llm/openai', kind: 'api-key' }],
    },
    llm: {
      listProviders: () => [{ id: 'openai', name: 'OpenAI' }],
      listConfigurableProviders: () => [{
        provider: 'openai', displayName: 'OpenAI', settingsNs: 'llm-pi-ai',
        settingsPath: ['providers', 'openai'],
      }],
    },
    settings: {
      describe: () => [{
        ns: 'llm-pi-ai', revision: 1,
        value: { providers: { openai: { apiKeyEnv: 'OPENAI_API_KEY' } } },
        user: {}, base: {}, schema: {},
      }],
    },
  })
  const listed = await ops['credentials.list']({})
  assert.equal(listed.items[0].ref, 'OPENAI_API_KEY')
  assert.equal(listed.items[0].configured, true)
  assert.equal(listed.items[0].value, undefined)
  assert.equal(listed.records[0].key, 'llm/openai')
  const json = JSON.stringify(listed)
  assert.equal(json.includes('sk-secret'), false)

  await ops['credentials.set']({ ref: 'ANTHROPIC_API_KEY', value: 'sk-ant' })
  assert.equal(store.get('ANTHROPIC_API_KEY'), 'sk-ant')
  const described = await ops['credentials.describe']({ refs: ['ANTHROPIC_API_KEY'] })
  assert.equal(described.ANTHROPIC_API_KEY.configured, true)
  await ops['credentials.unset']({ ref: 'ANTHROPIC_API_KEY' })
  assert.equal(store.has('ANTHROPIC_API_KEY'), false)
})

test('credentials.set rejects empty value', async () => {
  const ops = credentialsOps({
    credentials: {
      describe: async () => ({ configured: false, writable: true }),
      set: async () => {},
      unset: async () => {},
    },
  })
  await assert.rejects(() => ops['credentials.set']({ ref: 'X', value: '' }), /non-empty/)
})

test('plugin controls reject ambiguous targets without changing either plugin', async () => {
  let changes = 0
  const ops = pluginsOps({
    setBundleEnabled: () => { changes++ },
    setPluginEnabled: () => { changes++ },
  })
  await assert.rejects(ops['plugins.setEnabled']({ bundle: 'bundle', id: 'row', enabled: false }), /not both/)
  await assert.rejects(ops['plugins.setEnabled']({ id: 'row1', entryId: 'row2', enabled: false }), /conflicting/)
  await assert.rejects(ops['plugins.setEnabled']({ id: 'row', enabled: 'false' }), /boolean/)
  assert.equal(changes, 0)
})

test('unavailable package management operations are not advertised', () => {
  const ops = pluginsOps({})
  for (const op of ['install', 'inspect', 'uninstall', 'cancelInstall', 'waitForInstall', 'registries']) {
    assert.equal(`plugins.${op}` in ops, false)
  }
})

test('blank install and uninstall requests cannot reach the package manager', async () => {
  let changes = 0
  const ops = pluginsOps({
    installBundle: () => { changes++ },
    removeBundle: () => { changes++ },
  })
  await assert.rejects(ops['plugins.install']({ spec: ' \n ' }), /spec required/)
  await assert.rejects(ops['plugins.uninstall']({ bundle: '' }), /bundle required/)
  assert.equal(changes, 0)
})

test('plugin configuration requires a valid observed revision before mutation', async () => {
  let changes = 0
  const ops = pluginSettingsOps({ mutate: () => { changes++ } })
  for (const revision of [undefined, null, -1, 0.5, '1', Number.MAX_SAFE_INTEGER + 1]) {
    await assert.rejects(
      ops['plugins.settingsMutate']({ ns: 'example', ops: [], expectedRevision: revision }),
      /expectedRevision required/,
    )
  }
  assert.equal(changes, 0)
})
