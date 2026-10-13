import { test } from 'node:test'
import assert from 'node:assert/strict'
import {
  deriveKeyRef, getPath, joinProviderDirectory,
  modelsSettingsOps, pluginsOps, scheduleOps, credentialsOps,
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

test('models.settingsMutate prefers settingsController.mutate', async () => {
  const calls = []
  const ops = modelsSettingsOps({
    llm: { listProviders: () => [], listConfigurableProviders: () => [] },
    settingsController: {
      mutate: async (ns, opsIn, rev) => {
        calls.push({ ns, opsIn, rev })
        return { ns, revision: (rev ?? 0) + 1, value: {}, user: {}, base: {}, schema: {} }
      },
    },
  })
  const view = await ops['models.settingsMutate']({
    ns: 'llm-pi-ai',
    ops: [{ op: 'set', path: ['providers', 'openai', 'models'], value: [] }],
    expectedRevision: 3,
  })
  assert.equal(calls[0].ns, 'llm-pi-ai')
  assert.equal(calls[0].rev, 3)
  assert.equal(view.revision, 4)
})

test('plugins.list / setEnabled map pluginManager Remotes', async () => {
  const calls = []
  const ops = pluginsOps({
    listBundles: async () => [{ name: 'dsh-mobile', enabled: true, rows: [] }],
    listPlugins: async () => [{ entryId: 'e1', moduleName: 'x', enabled: true, patchId: 'p1' }],
    setBundleEnabled: async (name, enabled) => { calls.push(['bundle', name, enabled]); return { application: 'applied' } },
    setPluginEnabled: async (id, enabled) => { calls.push(['plugin', id, enabled]); return { application: 'applied' } },
  })
  const listed = await ops['plugins.list']()
  assert.equal(listed.bundles[0].name, 'dsh-mobile')
  assert.equal(listed.plugins[0].entryId, 'e1')
  await ops['plugins.setEnabled']({ bundle: 'dsh-mobile', enabled: false })
  await ops['plugins.setEnabled']({ id: 'e1', enabled: false })
  assert.deepEqual(calls, [['bundle', 'dsh-mobile', false], ['plugin', 'e1', false]])
})

test('schedule CRUD + history forward to Host schedule service', async () => {
  const calls = []
  const record = {
    id: 'schedule-1', kind: 'after', title: 'Ping', prompt: 'say hi',
    afterSeconds: 60, scheduledAt: '2026-01-01T00:01:00.000Z',
  }
  const ops = scheduleOps({
    catalog: async () => [{ ...record, sessionId: 's1', status: 'active' }],
    list: async (req) => { calls.push(['list', req]); return [record] },
    create: async (sessionId, req) => { calls.push(['create', sessionId, req]); return { ...record, title: req.title } },
    update: async (req) => { calls.push(['update', req]); return { id: req.id, updated: true, record } },
    delete: async (req) => { calls.push(['delete', req]); return { id: req.id, deleted: true } },
    history: async (req) => { calls.push(['history', req]); return { id: req.id, records: [], hasMore: false } },
  })
  assert.equal((await ops['schedule.catalog']())[0].status, 'active')
  await ops['schedule.list']({ sessionId: 's1' })
  await ops['schedule.create']({ sessionId: 's1', title: 'Ping', prompt: 'say hi', after_seconds: 60 })
  await ops['schedule.update']({ sessionId: 's1', id: 'schedule-1', expected: record, title: 'Ping2' })
  await ops['schedule.delete']({ sessionId: 's1', id: 'schedule-1' })
  await ops['schedule.history']({ sessionId: 's1', id: 'schedule-1', limit: 10 })
  assert.equal(calls[0][0], 'list')
  assert.equal(calls[1][0], 'create')
  assert.equal(calls[1][2].after_seconds, 60)
  assert.equal(calls[2][0], 'update')
  assert.equal(calls[3][0], 'delete')
  assert.equal(calls[4][0], 'history')
  await assert.rejects(() => ops['schedule.create']({ sessionId: 's1', title: 'x' }), /prompt/)
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
