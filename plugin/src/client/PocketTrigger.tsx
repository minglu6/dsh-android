import type { JSX } from 'react'
import { PhoneIcon } from './PhoneIcon.tsx'
import { styles } from './styles.ts'

export function PocketTrigger({
  wide, t, open,
}: {
  wide: boolean
  t: (key: string) => string
  open: () => void
}): JSX.Element {
  if (!wide) {
    return (
      <button type="button" aria-label={t('entry')} title={t('entry')} onClick={open} style={styles.triggerRail}>
        <PhoneIcon />
      </button>
    )
  }
  return (
    <button type="button" aria-label={t('entry')} onClick={open} style={styles.trigger}>
      <PhoneIcon />
      <span>{t('entry')}</span>
    </button>
  )
}
