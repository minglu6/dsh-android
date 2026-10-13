/**
 * Desktop settings Host surfaces for the phone: models providers, plugins,
 * schedules, and credentials.
 *
 * Data shapes and Host call patterns follow deepseek-harness client modules
 * (ui-settings-models, ui-plugin-manager, ui-schedule, settings-controller).
 * Mobile nav (list → full-screen detail) follows the dsh-mobile panel pattern
 * (Apache-2.0; see docs/THIRD_PARTY_NOTICES.md) without copying WebView UI.
 */

const str = (v) => typeof v === 'string' && v.length > 0
const bad = (m) => Object.assign(new Error(m), { code: 'bad-request' })

/** Conventional credential ref for a provider route (ui-settings-models deriveKeyRef). */
export function deriveKeyRef(provider) {
  return `${String(provider).toUpperCase().replace(/[^A-Z0-9]+/g, '_')}_API_KEY`
}

/** Walk a plain object by string path segments. */
export function getPath(value, path) {
  let cur = value
  for (const key of path ?? []) {
    if (cur === undefined || cur === null || typeof cur !== 'object') return undefined
    cur = cur[key]
  }
  return cur
}

/** Join configurable directory with live routes (ui-settings-models joinProviderDirectory). */
export function joinProviderDirectory(registered, directory) {
  const active = new Set((registered ?? []).map((p) => p.id))
  const declared = new Set((directory ?? []).map((e) => e.provider))
  const rows = (directory ?? []).map((entry) => ({
    provider: entry.provider,
    displayName: entry.displayName ?? entry.provider,
    settingsNs: entry.settingsNs ?? '',
    settingsPath: [...(entry.settingsPath ?? [])],
    active: active.has(entry.provider),
    ...(entry.declared === undefined ? {} : { declared: entry.declared }),
    ...(entry.error === undefined ? {} : { error: entry.error }),
  }))
  for (const provider of registered ?? []) {
    if (declared.has(provider.id)) continue
    rows.push({
      provider: provider.id,
      displayName: provider.name ?? provider.id,
      settingsNs: '',
      settingsPath: [],
      active: true,
    })
  }
  const rank = (id) => (id === 'deepseek-account' ? 0 : id === 'deepseek-official' ? 1 : 2)
  return rows.sort((a, b) => rank(a.provider) - rank(b.provider))
}

function projectCredentialInfo(info) {
  if (!info || typeof info !== 'object') return { configured: false, writable: false }
  return {
    configured: Boolean(info.configured),
    ...(info.source === undefined ? {} : { source: info.source }),
    writable: Boolean(info.writable),
  }
}

function namespaceView(descriptor) {
  return {
    ns: descriptor.ns,
    revision: descriptor.revision,
    value: descriptor.value,
    user: descriptor.user,
    base: descriptor.base,
    schema: descriptor.schema,
  }
}

/**
 * Models settings ops over llm + settings + credentials Host services.
 * Session model picker stays on existing models.catalog / models.select.
 */
export function modelsSettingsOps({ llm, settings, settingsController, credentials }) {
  const describeSettings = () => {
    if (settingsController?.describe) return settingsController.describe()
    if (!settings?.describe) throw Object.assign(new Error('settings unavailable'), { code: 'unavailable' })
    const raw = settings.describe({ redactSecrets: true })
    return {
      writable: settings.writable !== false,
      hasDocument: true,
      namespaces: (Array.isArray(raw) ? raw : []).map(namespaceView),
    }
  }

  const describeCreds = async (refs) => {
    if (!credentials?.describe) return {}
    const out = {}
    await Promise.all(refs.map(async (ref) => {
      out[ref] = projectCredentialInfo(await credentials.describe(ref))
    }))
    return out
  }

  return {
    'models.providers': async () => {
      if (!llm?.listProviders || !llm?.listConfigurableProviders) {
        throw Object.assign(new Error('llm service unavailable'), { code: 'unavailable' })
      }
      const registered = llm.listProviders()
      const directory = llm.listConfigurableProviders()
      const providers = joinProviderDirectory(registered, directory)
      const described = describeSettings()
      const namespaces = new Map((described.namespaces ?? []).map((n) => [n.ns, n]))
      const rows = providers.map((entry) => {
        const ns = namespaces.get(entry.settingsNs)
        const profile = ns ? getPath(ns.value, entry.settingsPath) : undefined
        const configured = entry.settingsPath.length === 0
          ? entry.active
          : profile !== undefined
        const apiKeyEnv = (profile && typeof profile === 'object' && str(profile.apiKeyEnv))
          ? profile.apiKeyEnv
          : (entry.provider === 'deepseek-account' ? undefined : deriveKeyRef(entry.provider))
        return {
          ...entry,
          configured,
          apiKeyEnv,
          credential: undefined,
          profile: profile && typeof profile === 'object'
            ? {
              ...(str(profile.apiKeyEnv) ? { apiKeyEnv: profile.apiKeyEnv } : {}),
              ...(Array.isArray(profile.models) ? { models: profile.models } : {}),
              ...(str(profile.baseUrl) ? { baseUrl: profile.baseUrl } : {}),
              ...(str(profile.api) ? { api: profile.api } : {}),
            }
            : undefined,
        }
      })
      const refs = [...new Set(rows.map((r) => r.apiKeyEnv).filter(str))]
      const credentialsMap = await describeCreds(refs)
      return {
        writable: described.writable !== false,
        rows: rows.map((row) => ({
          ...row,
          ...(row.apiKeyEnv && credentialsMap[row.apiKeyEnv]
            ? { credential: credentialsMap[row.apiKeyEnv] }
            : {}),
        })),
      }
    },

    'models.providerGet': async (a) => {
      if (!str(a.provider)) throw bad('provider required')
      const listed = await modelsSettingsOps({ llm, settings, settingsController, credentials })['models.providers']()
      const row = listed.rows.find((r) => r.provider === a.provider)
      if (!row) throw Object.assign(new Error(`unknown provider: ${a.provider}`), { code: 'not-found' })
      return { writable: listed.writable, provider: row }
    },

    'models.settingsMutate': async (a) => {
      if (!str(a.ns) || !Array.isArray(a.ops)) throw bad('ns and ops required')
      const controller = settingsController
      if (controller?.mutate) {
        return controller.mutate(a.ns, a.ops, a.expectedRevision)
      }
      if (!settings?.mutate) throw Object.assign(new Error('settings unavailable'), { code: 'unavailable' })
      await settings.mutate(a.ns, a.ops, a.expectedRevision)
      const view = settings.describe({ redactSecrets: true }).find((d) => d.ns === a.ns)
      return view ? namespaceView(view) : { ns: a.ns }
    },

    'models.discover': async (a, signal) => {
      if (!str(a.settingsNs)) throw bad('settingsNs required')
      if (!llm?.discoverModels) throw Object.assign(new Error('llm discovery unavailable'), { code: 'unavailable' })
      const models = await llm.discoverModels(a.settingsNs, a.request ?? {}, signal)
      return { models }
    },
  }
}

/**
 * Plugin manager ops. Install-from-local-path stays host-desktop-only (hide);
 * npm/git specs are reachable via plugins.install when pluginManager is present.
 */
export function pluginsOps(pluginManager) {
  if (!pluginManager) throw bad('pluginManager required')
  return {
    'plugins.list': async () => {
      const [bundles, plugins] = await Promise.all([
        pluginManager.listBundles(),
        pluginManager.listPlugins(),
      ])
      return {
        bundles: bundles ?? [],
        plugins: plugins ?? [],
        managementAvailable: true,
      }
    },
    'plugins.setEnabled': async (a) => {
      if (typeof a.enabled !== 'boolean') throw bad('enabled boolean required')
      if (str(a.bundle)) {
        return pluginManager.setBundleEnabled(a.bundle, a.enabled)
      }
      if (str(a.id) || str(a.entryId)) {
        return pluginManager.setPluginEnabled(a.id ?? a.entryId, a.enabled)
      }
      throw bad('bundle or id required')
    },
    /** Registry/git/tarball install — local OS directory picker stays hidden on phone. */
    'plugins.install': async (a) => {
      if (!str(a.spec)) throw bad('spec required')
      if (!pluginManager.installBundle) {
        throw Object.assign(new Error('plugin install unavailable'), { code: 'unavailable' })
      }
      return pluginManager.installBundle(a.spec, a.options)
    },
  }
}

/**
 * Built-in plugin inventory (settings → 内置插件), read-only roster plus enable
 * when pluginManager can address the row.
 */
export function pluginInventoryOps(pluginInventory, pluginManager) {
  return {
    'plugins.inventory': async () => {
      if (pluginInventory?.list) {
        const snap = await pluginInventory.list()
        return snap
      }
      if (pluginManager?.listPlugins) {
        const plugins = await pluginManager.listPlugins()
        return { entries: plugins, managementAvailable: true }
      }
      throw Object.assign(new Error('plugin inventory unavailable'), { code: 'unavailable' })
    },
  }
}

/**
 * Schedule / automation tasks. create() is Host in-process (not a Remote);
 * catalog/list/update/delete/history are Remotes. Desktop has no pause toggle —
 * inactive means ended after delivery.
 */
export function scheduleOps(schedule) {
  if (!schedule) throw bad('schedule service required')
  return {
    'schedule.catalog': () => schedule.catalog(),
    'schedule.list': async (a) => {
      if (!str(a.sessionId)) throw bad('sessionId required')
      return schedule.list({ sessionId: a.sessionId })
    },
    'schedule.create': async (a, signal) => {
      if (!str(a.sessionId)) throw bad('sessionId required')
      if (!str(a.title) || !str(a.prompt)) throw bad('title and prompt required')
      if (typeof schedule.create !== 'function') {
        throw Object.assign(new Error('schedule.create unavailable'), { code: 'unavailable' })
      }
      const request = {
        title: a.title,
        prompt: a.prompt,
        ...(a.after_seconds !== undefined ? { after_seconds: a.after_seconds } : {}),
        ...(a.at !== undefined ? { at: a.at } : {}),
        ...(a.every_seconds !== undefined ? { every_seconds: a.every_seconds } : {}),
        ...(a.daily !== undefined ? { daily: a.daily } : {}),
        ...(a.weekly !== undefined ? { weekly: a.weekly } : {}),
        ...(a.cron !== undefined ? { cron: a.cron } : {}),
      }
      return schedule.create(a.sessionId, request, signal)
    },
    'schedule.update': async (a, signal) => {
      if (!str(a.sessionId) || !str(a.id)) throw bad('sessionId and id required')
      if (!a.expected || typeof a.expected !== 'object') throw bad('expected record required')
      return schedule.update({
        sessionId: a.sessionId,
        id: a.id,
        expected: a.expected,
        ...(a.title !== undefined ? { title: a.title } : {}),
        ...(a.prompt !== undefined ? { prompt: a.prompt } : {}),
        ...(a.change !== undefined ? { change: a.change } : {}),
      }, signal)
    },
    'schedule.delete': async (a, signal) => {
      if (!str(a.sessionId) || !str(a.id)) throw bad('sessionId and id required')
      return schedule.delete({ sessionId: a.sessionId, id: a.id }, signal)
    },
    'schedule.history': async (a) => {
      if (!str(a.sessionId) || !str(a.id)) throw bad('sessionId and id required')
      const limit = Number.isSafeInteger(a.limit) ? a.limit : 20
      return schedule.history({
        sessionId: a.sessionId,
        id: a.id,
        limit,
        ...(str(a.before) ? { before: a.before } : {}),
      })
    },
  }
}

/**
 * Credentials refs (API keys). Values never leave the Host — only configured /
 * source / writable, matching desktop masking. Refs are discovered from model
 * providers (desktop has no ref enumeration API); callers may also pass refs.
 */
export function credentialsOps({ credentials, llm, settings, settingsController }) {
  if (!credentials) throw bad('credentials service required')

  const discoverRefs = async () => {
    const refs = new Set()
    if (llm?.listConfigurableProviders && llm?.listProviders) {
      try {
        const ops = modelsSettingsOps({ llm, settings, settingsController, credentials })
        const listed = await ops['models.providers']()
        for (const row of listed.rows) if (str(row.apiKeyEnv)) refs.add(row.apiKeyEnv)
      } catch {
        /* provider discovery optional for a bare credential list */
      }
    }
    return [...refs]
  }

  return {
    'credentials.list': async (a) => {
      const extra = Array.isArray(a?.refs) ? a.refs.filter(str) : []
      const discovered = await discoverRefs()
      const refs = [...new Set([...discovered, ...extra])]
      const items = []
      for (const ref of refs) {
        items.push({ ref, ...projectCredentialInfo(await credentials.describe(ref)) })
      }
      let records = []
      if (typeof credentials.listRecords === 'function') {
        records = (await credentials.listRecords()).map((e) => ({
          key: e.key,
          kind: e.kind,
        }))
      }
      return { items, records }
    },
    'credentials.describe': async (a) => {
      const refs = Array.isArray(a?.refs) ? a.refs.filter(str) : []
      if (refs.length === 0) throw bad('refs required')
      if (refs.length > 64) throw bad('at most 64 refs')
      const out = {}
      for (const ref of refs) out[ref] = projectCredentialInfo(await credentials.describe(ref))
      return out
    },
    'credentials.set': async (a) => {
      if (!str(a.ref) || !str(a.value)) throw bad('ref and non-empty value required')
      await credentials.set(a.ref, a.value)
      return { ref: a.ref, ...projectCredentialInfo(await credentials.describe(a.ref)) }
    },
    'credentials.unset': async (a) => {
      if (!str(a.ref)) throw bad('ref required')
      await credentials.unset(a.ref)
      return { ref: a.ref, ...projectCredentialInfo(await credentials.describe(a.ref)) }
    },
    'credentials.deleteRecord': async (a) => {
      if (!str(a.key)) throw bad('key required')
      if (typeof credentials.deleteRecord !== 'function') {
        throw Object.assign(new Error('credential records unavailable'), { code: 'unavailable' })
      }
      await credentials.deleteRecord(a.key)
      return { key: a.key, deleted: true }
    },
  }
}
