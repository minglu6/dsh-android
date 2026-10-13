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

test('joinProviderDirectory keeps only configurable routes in desktop priority order', () => {
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
  assert.equal(rows.some((r) => r.provider === 'custom'), false)
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
  assert.equal(listed.writable, false)
  assert.equal(listed.rows[0].provider, 'openai')
  assert.equal(listed.rows[0].configured, true)
  assert.equal(listed.rows[0].apiKeyEnv, 'OPENAI_API_KEY')
  assert.equal(listed.rows[0].credential.configured, true)
  assert.deepEqual(listed.rows[0].profile.models, [{ id: 'gpt-4o' }])
})

test('model settings reject unfenced writes and never fall back to raw settings mutation', async () => {
  let writes = 0
  const raw = { mutate: async () => { writes++ } }
  const unsafe = modelsSettingsOps({ settings: raw })
  await assert.rejects(unsafe['models.settingsMutate']({ ns: 'provider', ops: [], expectedRevision: 0 }), /safe settings controller unavailable/)
  const safe = modelsSettingsOps({ settingsController: { mutate: async () => { writes++ } } })
  for (const expectedRevision of [undefined, null, -1, 1.5, '1']) {
    await assert.rejects(safe['models.settingsMutate']({ ns: 'provider', ops: [], expectedRevision }), /expectedRevision required/)
  }
  assert.equal(writes, 0)
})


test('model API-key writes expose only metadata and never enumerate or delete credential records', async () => {
  const store = new Map([['OPENAI_API_KEY', 'sk-secret']])
  const ops = credentialsOps({
    credentials: {
      describe: async (ref) => ({
        configured: store.has(ref), source: 'file', writable: true,
        value: store.get(ref),
      }),
      set: async (ref, value) => { store.set(ref, value) },
      unset: async (ref) => { store.delete(ref) },
      listRecords: async () => { throw new Error('must not enumerate records') },
      deleteRecord: async () => { throw new Error('must not delete records') },
    },
  })
  assert.deepEqual(Object.keys(ops).sort(), ['credentials.set', 'credentials.unset'])
  const stored = await ops['credentials.set']({ ref: 'ANTHROPIC_API_KEY', value: 'sk-ant' })
  assert.equal(store.get('ANTHROPIC_API_KEY'), 'sk-ant')
  assert.deepEqual(stored, { ref: 'ANTHROPIC_API_KEY', configured: true, source: 'file', writable: true })
  const removed = await ops['credentials.unset']({ ref: 'ANTHROPIC_API_KEY' })
  assert.equal(removed.configured, false)
  assert.equal(store.has('ANTHROPIC_API_KEY'), false)
  assert.equal(store.get('OPENAI_API_KEY'), 'sk-secret')
  assert.equal(JSON.stringify([stored, removed]).includes('sk-ant'), false)
})

const modelSchema = {
  uid: 1,
  refs: {
    1: { type: 'object', dict: { providers: 2 } },
    2: { type: 'dict', inner: 3 },
    3: { type: 'object', dict: { api: 4, models: 8 } },
    4: { type: 'union', list: [5, { uid: 6 }, 7] },
    5: { type: 'const', value: 'openai-completions' },
    6: { type: 'const', value: 'anthropic-messages' },
    7: { type: 'const', value: null },
    8: { type: 'array', inner: 9, meta: { default: [{ id: 'schema-default' }] } },
    9: { type: 'object', dict: {} },
  },
}

test('models.providers uses mounted namespaces, effective profiles, user ownership, and schema protocols', async () => {
  const describedRefs = []
  const ops = modelsSettingsOps({
    llm: {
      listProviders: () => [{ id: 'live-only' }, { id: 'openai' }],
      listConfigurableProviders: () => [
        { provider: 'deepseek-official', settingsNs: 'llm-deepseek', settingsPath: [] },
        { provider: 'openai', settingsNs: 'llm-pi-ai', settingsPath: ['providers', 'openai'], declared: true },
        { provider: 'dormant', settingsNs: 'llm-pi-ai', settingsPath: ['providers', 'dormant'] },
        { provider: 'unmounted', settingsNs: 'missing', settingsPath: [] },
      ],
      listModels: () => { throw new Error('ordinary catalogs must load on demand') },
    },
    settingsController: {
      describe: () => ({
        writable: true,
        namespaces: [
          { ns: 'llm-deepseek', value: {}, user: {}, base: {} },
          {
            ns: 'llm-pi-ai', schema: modelSchema,
            value: { providers: { openai: { apiKeyEnv: 'CUSTOM_REF' } } },
            user: { providers: { openai: {} } }, base: {},
          },
        ],
      }),
    },
    credentials: {
      describe: async (ref) => {
        describedRefs.push(ref)
        return { configured: true, writable: true, value: 'must-not-leak' }
      },
    },
  })
  const listed = await ops['models.providers']()
  assert.deepEqual(listed.rows.map((row) => row.provider), ['deepseek-official', 'openai', 'dormant'])
  assert.equal(listed.rows[0].configured, true)
  assert.equal(listed.rows[0].active, false)
  assert.equal(listed.rows[0].removable, false)
  assert.equal(listed.rows[1].removable, true)
  assert.equal(listed.rows[1].declared, true)
  assert.equal(listed.rows[1].editable, true)
  assert.equal(listed.rows[1].credential.configured, true)
  assert.equal(listed.rows[2].configured, false)
  assert.equal(listed.rows[2].apiKeyEnv, undefined)
  assert.equal(listed.rows[2].derivedCredential.configured, true)
  assert.deepEqual(describedRefs, ['DEEPSEEK_OFFICIAL_API_KEY', 'CUSTOM_REF', 'DORMANT_API_KEY'])
  assert.deepEqual(listed.protocols, ['openai-completions', 'anthropic-messages'])
  assert.equal(JSON.stringify(listed).includes('must-not-leak'), false)
})

test('account route is hidden for empty or failed catalogs without loading other provider catalogs', async () => {
  let models = []
  let fail = false
  const reads = []
  const ops = modelsSettingsOps({
    llm: {
      listProviders: () => [{ id: 'deepseek-account' }, { id: 'openai' }],
      listConfigurableProviders: () => [
        { provider: 'deepseek-account', settingsNs: 'account', settingsPath: [] },
        { provider: 'openai', settingsNs: 'pi', settingsPath: [] },
      ],
      listModels: async (provider) => {
        reads.push(provider)
        if (fail) throw new Error('account unavailable')
        return models
      },
    },
    settingsController: {
      describe: () => ({ writable: false, namespaces: [
        { ns: 'account', value: {} }, { ns: 'pi', value: {} },
      ] }),
    },
  })
  assert.deepEqual((await ops['models.providers']()).rows.map((row) => row.provider), ['openai'])
  models = [{ id: 'deepseek-v4' }]
  const listed = await ops['models.providers']()
  assert.equal(listed.rows[0].provider, 'deepseek-account')
  assert.equal(listed.rows[0].accountAvailable, true)
  assert.equal(listed.rows[0].editable, false)
  assert.equal(listed.rows[0].apiKeyEnv, undefined)
  fail = true
  assert.deepEqual((await ops['models.providers']()).rows.map((row) => row.provider), ['openai'])
  assert.deepEqual(reads, ['deepseek-account', 'deepseek-account', 'deepseek-account'])
})

test('providerGet loads one catalog and returns inherited models independently of user overrides', async () => {
  const reads = []
  const namespace = {
    ns: 'llm-pi-ai', revision: 9, schema: modelSchema,
    value: { providers: { openai: { models: [{ id: 'user-model' }] } } },
    user: { providers: { openai: { models: [{ id: 'user-model' }] } } },
    base: { providers: { openai: { models: [{ id: 'base-model' }] } } },
  }
  const ops = modelsSettingsOps({
    llm: {
      listProviders: () => [{ id: 'openai' }],
      listConfigurableProviders: () => [{
        provider: 'openai', settingsNs: 'llm-pi-ai', settingsPath: ['providers', 'openai'],
      }],
      listModels: async (provider) => { reads.push(provider); return [{ id: 'user-model' }] },
    },
    settingsController: { describe: () => ({ writable: true, namespaces: [namespace] }) },
    credentials: { describe: async () => { throw new Error('credential backend unavailable') } },
  })
  const listed = await ops['models.providers']()
  assert.equal(listed.rows[0].removable, false)
  assert.equal(listed.rows[0].configured, true)
  assert.equal(listed.credentialError, 'credentials unavailable')
  assert.deepEqual(reads, [])
  const detail = await ops['models.providerGet']({ provider: 'openai' })
  assert.deepEqual(reads, ['openai'])
  assert.deepEqual(detail.catalog, [{ id: 'user-model' }])
  assert.deepEqual(detail.inheritedModels, [{ id: 'base-model' }])
  assert.equal(detail.namespace.revision, 9)
  assert.equal(detail.namespace.user, namespace.user)
  delete namespace.base.providers.openai.models
  assert.deepEqual((await ops['models.providerGet']({ provider: 'openai' })).inheritedModels, [{ id: 'schema-default' }])
  await assert.rejects(ops['models.providerGet']({ provider: 'unknown' }), /unknown provider/)
})

test('model discovery forwards the draft and cancellation without storing its one-shot key', async () => {
  const controller = new AbortController()
  const request = { provider: 'openai', api: 'openai-completions', apiKey: 'one-shot-secret' }
  const models = [{ id: 'discovered', inputModalities: ['text', 'image'] }]
  const ops = modelsSettingsOps({
    llm: {
      discoverModels: async (ns, draft, signal) => {
        assert.equal(ns, 'llm-pi-ai')
        assert.equal(draft, request)
        assert.equal(signal, controller.signal)
        return models
      },
    },
    credentials: { set: () => { throw new Error('discovery must not persist a key') } },
  })
  const result = await ops['models.discover']({ settingsNs: 'llm-pi-ai', request }, controller.signal)
  assert.deepEqual(result, { models })
  assert.equal(JSON.stringify(result).includes('one-shot-secret'), false)
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
