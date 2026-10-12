import { phoneIconSvgHtml } from './PhoneIcon.tsx'

const LABELS = new Set(['手机连接', 'Phone connection'])

/**
 * Settings nav icons are hardcoded by section id in
 * `@deepseek-ai/dsh-client-ui-settings-general` (`navIcon`): unknown ids fall
 * back to the gear. `settings.section` has no `icon` register option, and the
 * primitives icon set has no phone glyph. Swap the gear SVG on our nav cell
 * for a Medium-outline smartphone that matches shipped stroke/size.
 */
export function installPocketNavIcon(): () => void {
  const sync = () => {
    for (const cell of document.querySelectorAll<HTMLElement>('[class*="navCell"]')) {
      const label = (cell.innerText || '').trim()
      if (!LABELS.has(label)) continue
      const svg = cell.querySelector('svg')
      if (!svg || svg.getAttribute('data-pocket-icon') === '1') continue
      const cls = svg.getAttribute('class') || ''
      const wrap = document.createElement('span')
      wrap.innerHTML = phoneIconSvgHtml(cls)
      const next = wrap.firstElementChild
      if (next) svg.replaceWith(next)
    }
  }

  const mo = new MutationObserver(() => sync())
  mo.observe(document.documentElement, { childList: true, subtree: true, characterData: true })
  sync()
  return () => mo.disconnect()
}
