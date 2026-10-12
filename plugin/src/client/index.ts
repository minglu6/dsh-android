/**
 * Browser half: official slot registrations for phone pairing / device management.
 *
 * - `sidebar.footer.action` — discoverable entry「手机连接」
 * - `shell.overlay` — pairing code + QR + device list modal
 * - `settings.section` — same panel inside Settings
 *
 * Every registration is wrapped in `ctx.slots.inject` so missing slots no-op
 * on older dsh builds instead of throwing.
 */
import type { Context as ClientContext } from '@deepseek-ai/cordis'
import { createPocketApi } from './api.ts'
import { zh, en } from './locales.ts'
import { createPanelStore } from './panel-store.ts'
import { PocketOverlay } from './PocketOverlay.tsx'
import { PocketSettings } from './PocketSettings.tsx'
import { PocketTrigger } from './PocketTrigger.tsx'

/** Must be a static array — cordis rejects function-form inject. */
export const inject = ['slots', 'locale'] as const

const NS = 'pocket'

export function apply(ctx: ClientContext): void {
  ctx.effect(() => ctx.locale.register(NS, { zh, en }), 'pocket:locale')
  const t = ctx.locale.bind(NS)
  const api = createPocketApi()
  const panel = createPanelStore(false)

  ctx.slots.inject('sidebar.footer.action', () =>
    ctx.slots.register(
      {
        name: 'sidebar.footer.action',
        id: 'pocket',
        order: 40,
        locale: NS,
        inject: () => ({ open: () => panel.open() }),
      },
      PocketTrigger,
    ))

  ctx.slots.inject('shell.overlay', () =>
    ctx.slots.register(
      {
        name: 'shell.overlay',
        id: 'pocket-manage',
        order: 40,
        locale: NS,
        inject: () => ({ api, panel }),
      },
      PocketOverlay,
    ))

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
