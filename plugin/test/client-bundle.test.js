import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { createRequire } from 'node:module'
import { runInNewContext } from 'node:vm'

const require = createRequire(import.meta.url)

function loadClient(search = '') {
  let exported
  const effects = []
  const states = []
  const attributes = new Map()
  const styles = []
  const document = {
    body: {},
    documentElement: {
      getAttribute: name => attributes.get(name) ?? null,
      setAttribute: (name, value) => attributes.set(name, value),
      removeAttribute: name => attributes.delete(name),
    },
    createElement: () => ({ dataset: {}, remove() { styles.splice(styles.indexOf(this), 1) } }),
    head: { append: style => styles.push(style) },
  }
  runInNewContext(readFileSync(resolve(import.meta.dirname, '../lib/client.js'), 'utf8'), {
    URLSearchParams,
    queueMicrotask,
    document,
    window: {
      location: { search },
      __ModuleLoader__: {
        load({ id, factory }) {
          assert.equal(id, 'dsh-plugin-pocket')
          exported = factory((name) => {
            assert.ok(['react', 'react-dom', 'react/jsx-runtime', '@deepseek-ai/cordis'].includes(name), `unsupported browser dependency: ${name}`)
            if (name === 'react') return {
              ...require(name),
              useEffect: effect => effects.push(effect),
              useState: value => [value, next => states.push(next)],
            }
            if (name === 'react-dom') return { ...require(name), createPortal: child => child }
            return require(name)
          })
        },
      },
    },
  })
  return { exported, effects, states, styles, attributes }
}

function clientContext() {
  const registrations = []
  const cleanups = []
  const listeners = new Map()
  const entries = new Map()
  const ctx = {
    inject: (_names, apply) => apply(ctx),
    sessions: { list: {} },
    effect(effect, name) {
      // The desktop-only icon observer is unrelated to the launcher contract.
      if (name !== 'pocket:nav-icon') cleanups.push(effect())
    },
    locale: { register: () => () => {}, bind: () => key => key },
    slots: {
      inject: (_name, register) => register(),
      register(options, component) {
        const entry = { options, component, removed: false }
        registrations.push(entry)
        return () => { entry.removed = true }
      },
      entries: name => entries.get(name) ?? [],
      subscribe(name, listener) {
        if (!listeners.has(name)) listeners.set(name, new Set())
        listeners.get(name).add(listener)
        return () => listeners.get(name).delete(listener)
      },
    },
  }
  const publish = (name, rows) => {
    entries.set(name, rows)
    for (const listener of listeners.get(name) ?? []) listener()
  }
  return { ctx, registrations, cleanups, listeners, publish }
}

test('client bundle loads with the public browser dependencies and Cordis injection contract', () => {
  const { exported } = loadClient()
  assert.deepEqual([...exported.inject], ['slots', 'locale'])
  assert.equal(typeof exported.apply, 'function')
})

test('ordinary desktop windows retain the existing pairing section', () => {
  const client = loadClient('?section=models')
  const host = clientContext()
  client.exported.apply(host.ctx)
  assert.deepEqual(host.registrations.map(entry => entry.options.name), ['settings.section'])
  assert.equal(host.registrations[0].options.id, 'pocket')
  assert.equal(client.styles.length, 0)
})

test('phone full-client mode adds scoped styles without replacing any official slot', () => {
  const client = loadClient('?pocket-ui=1')
  const host = clientContext()
  client.exported.apply(host.ctx)
  assert.equal(host.registrations.length, 0)
  assert.equal(client.styles.length, 1)
  assert.equal(client.attributes.has('data-pocket-ui'), true)
  for (const cleanup of host.cleanups) cleanup?.()
  assert.equal(client.styles.length, 0)
  assert.equal(client.attributes.has('data-pocket-ui'), false)
})

test('phone deep links wait for arbitrary sections and navigate the real owner store once', async () => {
  const client = loadClient('?pocket-ui=1&section=third-party-settings')
  const host = clientContext()
  const opened = []
  const instance = { actions: { openSection: id => opened.push(id) } }
  host.publish('sidebar.settings', [{
    children: { 'settings.launcher': { kind: 'single', scope: 'root' } },
    store: { create: () => instance },
  }])
  client.exported.apply(host.ctx)
  assert.deepEqual(host.registrations.map(entry => entry.options.name), ['settings.launcher'])
  const launcher = host.registrations[0]
  launcher.component({ ...launcher.options.inject(), openSettings() {}, t: key => key, useSessionReadiness: select => select({ phase: 'ready' }) })
  const cleanup = client.effects[0]()
  assert.deepEqual(opened, [])
  assert.equal(client.states.at(-1), 'waiting')
  host.publish('settings.section', [{ options: { id: 'third-party-settings' } }])
  await Promise.resolve()
  assert.deepEqual(opened, ['third-party-settings'])
  assert.equal(launcher.removed, true)
  host.publish('settings.section', [{ options: { id: 'third-party-settings' } }])
  assert.deepEqual(opened, ['third-party-settings'])
  cleanup()
  assert.equal(host.listeners.get('settings.section').size, 0)
  assert.equal(host.listeners.get('sidebar.settings').size, 0)
})

test('an incompatible settings owner is explicit and rechecked when the owner changes', async () => {
  const client = loadClient('?pocket-ui=1&section=models')
  const host = clientContext()
  host.publish('settings.section', [{ options: { id: 'models' } }])
  client.exported.apply(host.ctx)
  const launcher = host.registrations[0]
  launcher.component({ ...launcher.options.inject(), openSettings() {}, t: key => key, useSessionReadiness: select => select({ phase: 'ready' }) })
  const cleanup = client.effects[0]()
  assert.equal(client.states.at(-1), 'unsupported')
  assert.equal(launcher.removed, false)
  const opened = []
  host.publish('sidebar.settings', [{
    children: { 'settings.launcher': {} },
    store: { create: () => ({ actions: { openSection: id => opened.push(id) } }) },
  }])
  await Promise.resolve()
  assert.deepEqual(opened, ['models'])
  cleanup()
})

test('initial navigation waits for the session baseline and follows the shell onboarding close effect', async () => {
  const client = loadClient('?pocket-ui=1&section=models')
  const host = clientContext()
  let active
  host.publish('settings.section', [{ options: { id: 'models' } }])
  host.publish('sidebar.settings', [{
    children: { 'settings.launcher': {} },
    store: { create: () => ({ actions: { openSection: id => { active = id } } }) },
  }])
  client.exported.apply(host.ctx)
  const launcher = host.registrations[0]
  let phase = 'loading'
  const props = { ...launcher.options.inject(), openSettings() {}, t: key => key, useSessionReadiness: select => select({ phase }) }
  launcher.component(props)
  const cleanup = client.effects[0]()
  await Promise.resolve()
  assert.equal(active, undefined)
  cleanup()
  phase = 'ready'
  launcher.component(props)
  client.effects[1]()
  active = undefined // The parent shell's effects close settings on its first session snapshot.
  await Promise.resolve()
  assert.equal(active, 'models')
  assert.equal(launcher.removed, true)
})
