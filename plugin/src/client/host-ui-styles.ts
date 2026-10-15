/** Scoped to this WebView window; no generated classes or plugin-specific selectors. */
const css = `
@media (max-width: 700px) {
  html[data-pocket-ui] [data-shortcut-modal="settings"] {
    box-sizing: border-box;
    flex-direction: column;
    width: 100%;
    max-width: 100%;
    height: 100dvh;
    max-height: 100dvh;
    padding: env(safe-area-inset-top) env(safe-area-inset-right) env(safe-area-inset-bottom) env(safe-area-inset-left);
    border-radius: 0;
  }
  html[data-pocket-ui] [data-shortcut-modal="settings"] > nav {
    width: 100%;
    min-width: 0;
    padding: 12px 12px 0;
    gap: 8px;
    border-bottom: 1px solid var(--dsw-alias-border-l2);
  }
  html[data-pocket-ui] [data-shortcut-modal="settings"] > nav > div:last-child {
    flex-direction: row;
    overflow-x: auto;
    overflow-y: hidden;
    padding-bottom: 8px;
    overscroll-behavior-x: contain;
  }
  html[data-pocket-ui] [data-shortcut-modal="settings"] > nav button {
    flex: 0 0 auto;
    min-height: 44px;
    white-space: nowrap;
  }
  html[data-pocket-ui] [data-shortcut-modal="settings"] > nav + div {
    min-width: 0;
    min-height: 0;
    width: 100%;
  }
  html[data-pocket-ui] [data-shortcut-modal="settings"] > nav + div > div:last-child {
    min-width: 0;
    min-height: 0;
    padding-left: 16px;
    padding-right: 16px;
    overflow: auto;
    overscroll-behavior: contain;
  }
}
`

export function installHostUiStyles(): () => void {
  const root = document.documentElement
  const previous = root.getAttribute('data-pocket-ui')
  const style = document.createElement('style')
  style.dataset.pocketUiStyles = ''
  style.textContent = css
  root.setAttribute('data-pocket-ui', '')
  document.head.append(style)
  return () => {
    style.remove()
    if (previous === null) root.removeAttribute('data-pocket-ui')
    else root.setAttribute('data-pocket-ui', previous)
  }
}
