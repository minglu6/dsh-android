import { useEffect, useState } from 'react'
import { createPortal } from 'react-dom'
import type { Context as ClientContext } from '@deepseek-ai/cordis'
import { installHostUiStyles } from './host-ui-styles.ts'

const NS = 'pocket.host-ui'
const messages = {
  zh: {
    waiting: '请求的设置页“{section}”尚未注册。插件就绪后将自动打开；也可以浏览当前已注册的设置。',
    unsupported: '此 Host 的设置导航接口不兼容，无法直接打开“{section}”。请从设置导航中选择页面。',
    settings: '浏览已注册的设置',
    dismiss: '返回完整界面',
  },
  en: {
    waiting: 'The requested settings section “{section}” is not registered yet. It will open when its plugin is ready, or you can browse the currently registered settings.',
    unsupported: 'This Host does not expose compatible settings navigation for “{section}”. Please select the page from Settings.',
    settings: 'Browse registered settings',
    dismiss: 'Return to the full interface',
  },
}

type MessageKey = keyof typeof messages.en

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
  t: (key: MessageKey) => string
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
export function HostUiLauncher({ section, slots, finish, openSettings, t, useSessionReadiness }: LauncherProps) {
  const ready = useSessionReadiness(state => state.phase === 'ready')
  const [problem, setProblem] = useState<MessageKey>('waiting')
  useEffect(() => {
    let finished = false
    const navigate = () => {
      if (!ready) return
      if (finished) return
      if (!slots.entries('settings.section').some(entry => entry.options.id === section)) {
        setProblem('waiting')
        return
      }
      const openSection = sectionNavigator(slots)
      if (!openSection) {
        setProblem('unsupported')
        return
      }
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
      offSections()
      offShell()
    }
  }, [section, slots, finish, ready])

  return createPortal(
    <aside data-pocket-ui-notice="" role="status" aria-live="polite">
      <p>{t(problem).replace('{section}', () => section)}</p>
      <div>
        <button type="button" onClick={() => { finish(); openSettings() }}>{t('settings')}</button>
        <button type="button" onClick={finish}>{t('dismiss')}</button>
      </div>
    </aside>,
    document.body,
  )
}

/** Returns false on ordinary desktop windows, leaving all existing registrations intact. */
export function installHostUi(ctx: ClientContext): boolean {
  const query = new URLSearchParams(window.location.search)
  if (query.get('pocket-ui') !== '1') return false
  ctx.effect(() => installHostUiStyles(), 'pocket:host-ui-styles')
  const section = query.get('section')
  // Without a deep link keep the entire official client, including its account launcher.
  if (!section) return true
  ctx.effect(() => ctx.locale.register(NS, messages), 'pocket:host-ui-locale')
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
        locale: NS,
        inject: () => ({ section, slots: scope.slots, finish, hooks: { sessionReadiness: scope.sessions.list } }),
      }, HostUiLauncher)
      return unregister
    })
  })
  return true
}
