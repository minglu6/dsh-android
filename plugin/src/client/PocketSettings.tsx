import type { JSX } from 'react'
import type { PocketApi } from './api.ts'
import { ManagePanel } from './ManagePanel.tsx'

export function PocketSettings({
  t, api,
}: {
  t: (key: string, vars?: Record<string, string | number>) => string
  api: PocketApi
  close: () => void
}): JSX.Element {
  return <ManagePanel t={t} api={api} />
}
