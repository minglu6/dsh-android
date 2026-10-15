import { useEffect } from 'react'
import type { Context as ClientContext } from '@deepseek-ai/cordis'
import { installHostUiStyles } from './host-ui-styles.ts'

/** Public launcher owner props in the shipped Host. openSection is NOT a launcher prop. */
type LauncherOwnerProps = {
  wide: boolean
  settingsOpen: boolean
  settingsShortcut?: { readonly keys: readonly string[]; readonly aria?: string | undefined }
  openSettings: () => void
  openOnboarding: (id: string) => void
}

type LauncherProps = LauncherOwnerProps & {
  section: string
  slots: ClientContext['slots']
  finish: () => void
  useSessionReadiness: <T>(select: (state: { phase: string }) => T) => T
}

type SettingsStore = { create: () => { actions: { openSection: (id: string) => void } } }

/**
 * The shell publishes its store on the settings.launcher-owning sidebar entry.
 * Its create() returns the SAME root-scoped instance used by the renderer and
 * keyboard command (ui-settings-general), not a second private React tree.
 */
function sectionNavigator(slots: ClientContext['slots']): ((id: string) => void) | undefined {
  const owner = slots.entries('sidebar.settings').find(entry => entry.children?.['settings.launcher'])
  const store: unknown = owner?.store
  if (typeof store !== 'object' || store === null || !('create' in store) || typeof store.create !== 'function') return undefined
  const instance = (store as SettingsStore).create()
  if (typeof instance?.actions?.openSection !== 'function') return undefined
  return id => { instance.actions.openSection(id) }
}

/** A temporary launcher occupant; the official launcher resumes after navigation. */
export function HostUiLauncher({ section, slots, finish, openSettings, useSessionReadiness }: LauncherProps) {
  const ready = useSessionReadiness(state => state.phase === 'ready')
  useEffect(() => {
    let finished = false
    let shown = false
    const navigate = () => {
      if (!ready || finished) return
      if (!shown) {
        shown = true
        queueMicrotask(() => { if (shown) openSettings() })
      }
      if (!slots.entries('settings.section').some(entry => entry.options.id === section)) return
      const openSection = sectionNavigator(slots)
      if (!openSection) return
      finished = true
      // The official shell closes settings when its initial session snapshot
      // introduces onboarding. Navigate after that render's effects, not before it.
      queueMicrotask(() => {
        if (!finished) return
        openSection(section)
        finish()
      })
    }
    // Subscribe before inspecting: sections may arrive after the shell mounts.
    const offSections = slots.subscribe('settings.section', navigate)
    const offShell = slots.subscribe('sidebar.settings', navigate)
    navigate()
    return () => {
      finished = false
      shown = false
      offSections()
      offShell()
    }
  }, [section, slots, finish, openSettings, ready])

  return null
}

/** Returns false on ordinary desktop windows, leaving all existing registrations intact. */
export function installHostUi(ctx: ClientContext): boolean {
  const query = new URLSearchParams(window.location.search)
  if (query.get('pocket-ui') !== '1') return false
  ctx.effect(() => installHostUiStyles(), 'pocket:host-ui-styles')
  const section = query.get('section')
  // Without a deep link keep the entire official client, including its account launcher.
  if (!section) return true
  let completed = false
  ctx.inject(['sessions'], (scope) => {
    scope.slots.inject('settings.launcher', () => {
      if (completed) return
      const finish = () => {
        completed = true
        unregister()
      }
      const unregister = scope.slots.register({
        name: 'settings.launcher',
        priority: -100,
        inject: () => ({ section, slots: scope.slots, finish, hooks: { sessionReadiness: scope.sessions.list } }),
      }, HostUiLauncher)
      return unregister
    })
  })
  return true
}
