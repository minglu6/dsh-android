/**
 * Desktop settings Host surfaces for the phone: models providers, plugins,
 * schedules, and model API-key writes.
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
  const rows = (directory ?? []).map((entry) => ({
    provider: entry.provider,
    displayName: entry.displayName ?? entry.provider,
    settingsNs: entry.settingsNs ?? '',
    settingsPath: [...(entry.settingsPath ?? [])],
    active: active.has(entry.provider),
    ...(entry.declared === undefined ? {} : { declared: entry.declared }),
    ...(entry.error === undefined ? {} : { error: entry.error }),
  }))
  const rank = (id) => (id === 'deepseek-account' ? 0 : id === 'deepseek-official' ? 1 : 2)
  return rows.sort((a, b) => rank(a.provider) - rank(b.provider))
}

function resolveSchemaNode(schema, raw) {
  const uid = raw && typeof raw === 'object' ? raw.uid : raw
  return uid !== undefined && schema?.refs ? schema.refs[uid] : raw
}

/** Read the Host's serialized Schemastery graph without loading client services. */
function schemaNodeAtPath(schema, path) {
  const resolve = (raw) => resolveSchemaNode(schema, raw)
  let node = resolve(schema)
  for (const key of path) {
    if (node?.type === 'object') node = resolve(node.dict?.[key])
    else if (node?.type === 'dict' || node?.type === 'array') node = resolve(node.inner)
    else return undefined
  }
  return node
}

function protocolChoices(namespace) {
  const schema = namespace?.schema
  const node = schemaNodeAtPath(schema, ['providers', '\u0000probe', 'api'])
  if (node?.type !== 'union' || !Array.isArray(node.list)) return []
  return node.list.map((raw) => resolveSchemaNode(schema, raw)?.value)
    .filter((value) => typeof value === 'string')
}

function hasPath(value, path) {
  if (path.length === 0) return value !== undefined
  const parent = getPath(value, path.slice(0, -1))
  return parent !== null && typeof parent === 'object' && Object.hasOwn(parent, path.at(-1))
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
    if (!credentials?.describe) return { values: {}, error: refs.length ? 'credentials unavailable' : null }
    try {
      const entries = await Promise.all(refs.map(async (ref) => [
        ref, projectCredentialInfo(await credentials.describe(ref)),
      ]))
      return { values: Object.fromEntries(entries), error: null }
    } catch {
      // Credential state enriches the page; an unavailable backend must not hide settings.
      return { values: {}, error: 'credentials unavailable' }
    }
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
      const rows = providers.filter((entry) => namespaces.has(entry.settingsNs)).map((entry) => {
        const ns = namespaces.get(entry.settingsNs)
        const profile = getPath(ns.value, entry.settingsPath)
        const configured = entry.settingsPath.length === 0 || profile !== undefined
        const apiKeyEnv = entry.provider !== 'deepseek-account' && str(profile?.apiKeyEnv)
          ? profile.apiKeyEnv
          : undefined
        return {
          ...entry,
          configured,
          editable: described.writable !== false,
          removable: entry.settingsPath.length > 0
            && hasPath(ns.user, entry.settingsPath) && !hasPath(ns.base, entry.settingsPath),
          apiKeyEnv,
          profile: profile && typeof profile === 'object' ? profile : undefined,
        }
      })
      const account = rows.find((row) => row.provider === 'deepseek-account')
      if (account) {
        try {
          account.accountAvailable = account.active && (await llm.listModels(account.provider)).length > 0
        } catch {
          account.accountAvailable = false
        }
      }
      const refs = [...new Set(rows.filter((row) => row.provider !== 'deepseek-account')
        .map((row) => row.apiKeyEnv ?? deriveKeyRef(row.provider)))]
      const { values: credentialsMap, error: credentialError } = await describeCreds(refs)
      return {
        writable: described.writable !== false && typeof settingsController?.mutate === 'function',
        namespaces: described.namespaces ?? [],
        protocols: protocolChoices(namespaces.get('llm-pi-ai')),
        credentialError,
        rows: rows.filter((row) => row.provider !== 'deepseek-account' || row.accountAvailable).map((row) => ({
          ...row,
          ...(row.provider === 'deepseek-account' ? {} : row.apiKeyEnv
            ? { credential: credentialsMap[row.apiKeyEnv] }
            : { derivedCredential: credentialsMap[deriveKeyRef(row.provider)] }),
        })),
      }
    },

    'models.providerGet': async (a) => {
      if (!str(a.provider)) throw bad('provider required')
      const listed = await modelsSettingsOps({ llm, settings, settingsController, credentials })['models.providers']()
      const row = listed.rows.find((r) => r.provider === a.provider)
      if (!row) throw Object.assign(new Error(`unknown provider: ${a.provider}`), { code: 'not-found' })
      const namespace = listed.namespaces.find((ns) => ns.ns === row.settingsNs)
      const modelsPath = [...row.settingsPath, 'models']
      let catalog = []
      let catalogError = null
      if (row.active) {
        try {
          catalog = await llm.listModels(row.provider)
        } catch {
          catalogError = 'model catalog unavailable'
        }
      }
      return {
        writable: listed.writable, provider: row, namespace, protocols: listed.protocols,
        catalog, catalogError,
        inheritedModels: getPath(namespace?.base, modelsPath)
          ?? schemaNodeAtPath(namespace?.schema, modelsPath)?.meta?.default,
      }
    },

    'models.settingsMutate': async (a) => {
      if (!str(a.ns) || !Array.isArray(a.ops)) throw bad('ns and ops required')
      if (!Number.isSafeInteger(a.expectedRevision) || a.expectedRevision < 0) throw bad('expectedRevision required')
      const controller = settingsController
      if (controller?.mutate) {
        return controller.mutate(a.ns, a.ops, a.expectedRevision)
      }
      throw Object.assign(new Error('safe settings controller unavailable'), { code: 'unavailable' })
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
 * Keep the official manager's business outcomes intact: an acknowledged RPC can
 * still require restart, be overridden, or fail. Only advertise live methods.
 */
export function pluginsOps(pluginManager) {
  if (!pluginManager) throw bad('pluginManager required')
  const ops = {
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
      if (str(a.bundle) && (str(a.id) || str(a.entryId))) throw bad('choose bundle or id, not both')
      if (str(a.id) && str(a.entryId) && a.id !== a.entryId) throw bad('conflicting plugin ids')
      if (str(a.bundle)) {
        return pluginManager.setBundleEnabled(a.bundle, a.enabled)
      }
      if (str(a.id) || str(a.entryId)) {
        return pluginManager.setPluginEnabled(a.id ?? a.entryId, a.enabled)
      }
      throw bad('bundle or id required')
    },
  }
  const methods = [
    ['plugins.install', 'installBundle', 'spec'],
    ['plugins.uninstall', 'removeBundle', 'bundle'],
    ['plugins.inspect', 'inspect', 'spec'],
    ['plugins.cancelInstall', 'cancelInstall', 'requestId'],
    ['plugins.waitForInstall', 'waitForInstall', 'requestId'],
  ]
  for (const [op, method, field] of methods) {
    if (typeof pluginManager[method] !== 'function') continue
    ops[op] = async (a, signal) => {
      if (!str(a[field]) || !a[field].trim()) throw bad(`${field} required`)
      if (op === 'plugins.inspect') return pluginManager.inspect(a.spec.trim(), a.options, signal)
      if (op === 'plugins.install') return pluginManager.installBundle(a.spec.trim(), a.options)
      return pluginManager[method](a[field])
    }
  }
  if (typeof pluginManager.registries === 'function') {
    ops['plugins.registries'] = () => pluginManager.registries()
  }
  return ops
}

/** Use the official redacted settings controller, including revision conflicts. */
export function pluginSettingsOps(settingsController) {
  return {
    'plugins.settingsDescribe': () => settingsController.describe(),
    'plugins.settingsMutate': async (a) => {
      if (!str(a.ns) || !Array.isArray(a.ops)) throw bad('ns and ops required')
      if (!Number.isSafeInteger(a.expectedRevision) || a.expectedRevision < 0) {
        throw bad('expectedRevision required')
      }
      return settingsController.mutate(a.ns, a.ops, a.expectedRevision)
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

/** Model API-key writes return only metadata; Host credential records stay private. */
export function credentialsOps({ credentials }) {
  if (!credentials) throw bad('credentials service required')

  return {
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
  }
}
