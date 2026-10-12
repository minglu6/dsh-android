import type { JSX } from 'react'
import type { PocketApi } from './api.ts'
import { ManagePanel } from './ManagePanel.tsx'
import type { PanelStore } from './panel-store.ts'
import { styles } from './styles.ts'

export function PocketOverlay({
  t, api, panel,
}: {
  t: (key: string, vars?: Record<string, string | number>) => string
  api: PocketApi
  panel: PanelStore
}): JSX.Element | null {
  const open = panel.useOpen()
  if (!open) return null
  return (
    <div
      style={styles.mask}
      onClick={(e) => { if (e.target === e.currentTarget) panel.close() }}
      role="presentation"
    >
      <ManagePanel t={t} api={api} onClose={() => panel.close()} />
    </div>
  )
}
