import { useEffect, type JSX, type ReactNode } from 'react'
import { createPortal } from 'react-dom'
import { styles } from './styles.ts'

/**
 * Lightweight confirm dialog matching dsh Modal / SignOutDialog chrome
 * (mask + card + title + body + action row), built with theme tokens only.
 * Does not `require` `@deepseek-ai/dsh-client-ui-primitives` (plugin hard rule).
 */
export function ConfirmDialog({
  open,
  title,
  body,
  cancelLabel,
  confirmLabel,
  busy = false,
  onCancel,
  onConfirm,
}: {
  open: boolean
  title: string
  body: ReactNode
  cancelLabel: string
  confirmLabel: string
  busy?: boolean
  onCancel: () => void
  onConfirm: () => void
}): JSX.Element | null {
  useEffect(() => {
    if (!open) return
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape' && !busy) onCancel()
    }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [open, busy, onCancel])

  if (!open || typeof document === 'undefined') return null

  return createPortal(
    <div
      style={styles.dialogMask}
      role="presentation"
      onClick={(e) => {
        if (e.target === e.currentTarget && !busy) onCancel()
      }}
    >
      <div
        role="dialog"
        aria-modal="true"
        aria-label={title}
        style={styles.dialogCard}
        onClick={(e) => e.stopPropagation()}
      >
        <h2 style={styles.dialogTitle}>{title}</h2>
        <div style={styles.dialogBody}>{body}</div>
        <div style={styles.dialogActions}>
          <button
            type="button"
            style={styles.ghostBtn}
            disabled={busy}
            data-modal-autofocus
            onClick={onCancel}
          >
            {cancelLabel}
          </button>
          <button
            type="button"
            style={styles.destructiveBtn}
            disabled={busy}
            onClick={onConfirm}
          >
            {confirmLabel}
          </button>
        </div>
      </div>
    </div>,
    document.body,
  )
}
