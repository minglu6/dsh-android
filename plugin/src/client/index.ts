/**
 * Official desktop pairing section, or the complete Host client in a phone
 * WebView. pocket-ui is a window-local presentation flag, not authentication.
 * Phone windows never register the loopback-only pairing management page.
 * All other plugin sections and provider extensions stay with their owners.
 */
import type { Context as ClientContext } from '@deepseek-ai/cordis'
import { createPocketApi } from './api.ts'
import { zh, en } from './locales.ts'
import { installPocketNavIcon } from './nav-icon.ts'
import { PocketSettings } from './PocketSettings.tsx'
import { installHostUi } from './HostUiLauncher.tsx'
import { installCompletionSync } from './completion-sync.js'

/** Must be a static array — cordis rejects function-form inject. */
export const inject = ['slots', 'locale'] as const

const NS = 'pocket'

export function apply(ctx: ClientContext): void {
  // The phone proxy excludes management routes; its native client owns presence.
  if (installHostUi(ctx)) return
  ctx.inject(['uiSession', 'sessions', 'layout'], (scope) => {
    const uiSession = scope.get('uiSession')
    const sessions = scope.get('sessions')
    if (typeof uiSession.setCompletionUnreadSource !== 'function') {
      console.warn('Pocket completion sync requires a Host client with uiSession.setCompletionUnreadSource')
      return
    }
    scope.effect(() => installCompletionSync({
      uiSession, sessions, layout: scope.get('layout'), slots: scope.slots,
    }), 'pocket:completion-sync')
  })
  ctx.effect(() => ctx.locale.register(NS, { zh, en }), 'pocket:locale')
  ctx.effect(() => installPocketNavIcon(), 'pocket:nav-icon')
  const t = ctx.locale.bind(NS)
  const api = createPocketApi()

  ctx.slots.inject('settings.section', () =>
    ctx.slots.register(
      {
        name: 'settings.section',
        id: 'pocket',
        order: 55,
        label: () => t('entry'),
        locale: NS,
        inject: () => ({ api }),
      },
      PocketSettings,
    ))
}
