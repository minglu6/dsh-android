/**
 * Browser half: official slot registrations for phone pairing / device management.
 *
 * Entry: `settings.section` — Settings nav「手机连接」(official additive page slot).
 * Panel: embedded in that section (status / pairing / devices).
 *
 * Nav icon: settings-general hardcodes glyphs by section id and defaults unknown
 * ids to the gear; `settings.section` has no `icon` option and primitives has no
 * phone glyph. `installPocketNavIcon` swaps our Medium-outline smartphone onto
 * the pocket nav cell (same size/stroke as shipped icons).
 *
 * Intentionally NOT registered:
 * - `sidebar.footer.action` — wrong surface for this product entry.
 * - Account/user menu row between 设置 and 意见反馈 — **no official list slot**
 *   exists on `AccountMenu` / `settings.launcher` in dsh 0.2.1-alpha.2 (menu
 *   items are a hardcoded array; `settings.launcher` is `kind: 'single'` and
 *   replacing it would shadow shipped AccountMenu). Do not DOM-hack.
 *
 * Every registration is wrapped in `ctx.slots.inject` so missing slots no-op
 * on older dsh builds instead of throwing.
 */
import type { Context as ClientContext } from '@deepseek-ai/cordis'
import { createPocketApi } from './api.ts'
import { zh, en } from './locales.ts'
import { installPocketNavIcon } from './nav-icon.ts'
import { PocketSettings } from './PocketSettings.tsx'

/** Must be a static array — cordis rejects function-form inject. */
export const inject = ['slots', 'locale'] as const

const NS = 'pocket'

export function apply(ctx: ClientContext): void {
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
