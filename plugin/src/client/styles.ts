/**
 * Inline styles aligned with dsh settings chrome / ui-primitives tokens.
 * Does not `require` `@deepseek-ai/dsh-client-ui-primitives` (plugin hard rule);
 * geometry mirrors SettingsForm fields, DeveloperToolsRow, Button, StateDot.
 */
export const styles = {
  /** Settings content column — same stacking as GeneralSection. */
  settingsRoot: {
    display: 'flex',
    flexDirection: 'column' as const,
    width: '100%',
    padding: '0 0 24px',
  },

  /**
   * One settings row: title + optional description on the left, control on the
   * right — matches ui-settings-general DeveloperToolsRow (16px pad, 0.5px rule).
   */
  settingsRow: {
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'space-between',
    gap: 24,
    padding: '16px 0',
    borderBottom: '0.5px solid var(--dsw-alias-border-l2)',
  },
  /** Stacked body rows (status meta, pairing body) under a section head. */
  settingsBlock: {
    display: 'flex',
    flexDirection: 'column' as const,
    gap: 0,
    padding: '16px 0',
    borderBottom: '0.5px solid var(--dsw-alias-border-l2)',
  },
  rowTitle: {
    fontSize: 14,
    lineHeight: '20px',
    color: 'var(--dsw-alias-label-primary)',
  },
  rowDescription: {
    marginTop: 4,
    fontSize: 12,
    lineHeight: '18px',
    color: 'var(--dsw-alias-label-secondary)',
  },
  hint: {
    margin: 0,
    fontSize: 12,
    lineHeight: '18px',
    color: 'var(--dsw-alias-label-tertiary)',
  },
  errorText: {
    margin: '0 0 12px',
    fontSize: 12,
    lineHeight: '18px',
    color: 'var(--dsw-alias-state-error-primary)',
  },

  /** Value line next to a row title (e.g. Running + StateDot). */
  valueLine: {
    display: 'inline-flex',
    alignItems: 'center',
    gap: 8,
    fontSize: 14,
    lineHeight: '20px',
    color: 'var(--dsw-alias-label-primary)',
  },

  /**
   * Mirror of primitives StateDot solid appearance:
   * 10px layout slot, 6px currentColor core via inset 20% (soft visual weight).
   */
  stateDotSlot: (state: 'done' | 'error' | 'idle') => ({
    position: 'relative' as const,
    display: 'inline-block',
    flex: 'none',
    width: 10,
    height: 10,
    color:
      state === 'done'
        ? 'var(--dsw-alias-state-success-primary)'
        : state === 'error'
          ? 'var(--dsw-alias-state-error-primary)'
          : 'var(--dsw-alias-state-idle-primary, var(--dsw-alias-label-tertiary))',
  }),
  /** Inner core for StateDot-style slot (absolute inset 20% → ~6px). */
  stateDotCore: {
    position: 'absolute' as const,
    inset: '20%',
    borderRadius: '50%',
    background: 'currentColor',
  },

  metaStack: {
    display: 'flex',
    flexDirection: 'column' as const,
    gap: 4,
    marginTop: 10,
  },
  metaLine: {
    fontSize: 12,
    lineHeight: '18px',
    color: 'var(--dsw-alias-label-tertiary)',
  },

  /** Official Button md primary. */
  primaryBtn: {
    appearance: 'none' as const,
    boxSizing: 'border-box' as const,
    display: 'inline-flex',
    alignItems: 'center',
    justifyContent: 'center',
    height: 36,
    padding: '0 14px',
    border: 'none',
    borderRadius: 'var(--dsw-radius-md, 8px)',
    background: 'var(--dsw-alias-button-primary-fill)',
    color: 'var(--dsw-alias-label-primary-foreground, var(--dsw-alias-bg-base))',
    cursor: 'pointer',
    font: 'inherit',
    fontSize: 14,
    lineHeight: '22px',
  },
  /** Official Button md outline. */
  outlineBtn: {
    appearance: 'none' as const,
    boxSizing: 'border-box' as const,
    display: 'inline-flex',
    alignItems: 'center',
    justifyContent: 'center',
    height: 36,
    padding: '0 14px',
    border: '0.5px solid var(--dsw-alias-border-l3)',
    borderRadius: 'var(--dsw-radius-md, 8px)',
    background: 'transparent',
    color: 'var(--dsw-alias-label-primary)',
    cursor: 'pointer',
    font: 'inherit',
    fontSize: 14,
    lineHeight: '22px',
  },
  /** Official Button sm outline — compact row actions. */
  outlineBtnSm: {
    appearance: 'none' as const,
    boxSizing: 'border-box' as const,
    display: 'inline-flex',
    alignItems: 'center',
    justifyContent: 'center',
    height: 28,
    padding: '0 10px',
    border: '0.5px solid var(--dsw-alias-border-l3)',
    borderRadius: 'var(--dsw-radius-sm, 6px)',
    background: 'transparent',
    color: 'var(--dsw-alias-label-primary)',
    cursor: 'pointer',
    font: 'inherit',
    fontSize: 12,
    lineHeight: '18px',
  },
  /** Textual destructive action (no fill). */
  dangerBtn: {
    appearance: 'none' as const,
    boxSizing: 'border-box' as const,
    display: 'inline-flex',
    alignItems: 'center',
    justifyContent: 'center',
    height: 28,
    padding: '0 10px',
    border: 'none',
    borderRadius: 'var(--dsw-radius-sm, 6px)',
    background: 'transparent',
    color: 'var(--dsw-alias-state-error-primary)',
    cursor: 'pointer',
    font: 'inherit',
    fontSize: 12,
    lineHeight: '18px',
  },
  destructiveBtn: {
    appearance: 'none' as const,
    boxSizing: 'border-box' as const,
    display: 'inline-flex',
    alignItems: 'center',
    justifyContent: 'center',
    height: 36,
    padding: '0 14px',
    border: 'none',
    borderRadius: 'var(--dsw-radius-md, 8px)',
    background: 'var(--dsw-alias-state-error-primary)',
    color: 'var(--dsw-alias-label-primary-foreground, #fff)',
    cursor: 'pointer',
    font: 'inherit',
    fontSize: 14,
    lineHeight: '22px',
    fontWeight: 500,
  },
  /** Alias kept for ConfirmDialog cancel. */
  ghostBtn: {
    appearance: 'none' as const,
    boxSizing: 'border-box' as const,
    display: 'inline-flex',
    alignItems: 'center',
    justifyContent: 'center',
    height: 36,
    padding: '0 14px',
    border: '0.5px solid var(--dsw-alias-border-l3)',
    borderRadius: 'var(--dsw-radius-md, 8px)',
    background: 'transparent',
    color: 'var(--dsw-alias-label-primary)',
    cursor: 'pointer',
    font: 'inherit',
    fontSize: 14,
    lineHeight: '22px',
  },

  dialogMask: {
    position: 'fixed' as const,
    inset: 0,
    zIndex: 1100,
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'center',
    pointerEvents: 'auto' as const,
    background: 'rgba(0, 0, 0, 0.45)',
    padding: 24,
  },
  dialogCard: {
    width: 'min(400px, calc(100vw - 48px))',
    borderRadius: 'var(--dsw-radius-panel, 12px)',
    border: '0.5px solid var(--dsw-alias-border-l2)',
    background: 'var(--dsw-alias-bg-base)',
    color: 'var(--dsw-alias-label-primary)',
    boxShadow: 'var(--dsw-elevation-prominent, 0 12px 40px rgba(0,0,0,0.25))',
    padding: '20px 22px 18px',
    display: 'flex',
    flexDirection: 'column' as const,
    gap: 12,
  },
  dialogTitle: {
    margin: 0,
    fontSize: 16,
    fontWeight: 500,
    lineHeight: '24px',
  },
  dialogBody: {
    margin: 0,
    fontSize: 14,
    lineHeight: '22px',
    color: 'var(--dsw-alias-label-secondary)',
  },
  dialogActions: {
    display: 'flex',
    justifyContent: 'flex-end',
    gap: 8,
    marginTop: 8,
  },

  code: {
    fontFamily: 'ui-monospace, SFMono-Regular, Menlo, Consolas, monospace',
    fontSize: 28,
    letterSpacing: '0.16em',
    fontWeight: 600,
    textAlign: 'center' as const,
    color: 'var(--dsw-alias-label-primary)',
    padding: '12px 0 4px',
  },
  qrWrap: {
    display: 'flex',
    justifyContent: 'center',
    padding: '12px 0 4px',
  },
  qrBox: {
    padding: 10,
    borderRadius: 'var(--dsw-radius-md, 8px)',
    background: '#fff',
    border: '0.5px solid var(--dsw-alias-border-l3)',
    lineHeight: 0,
  },
  actionRow: {
    display: 'flex',
    flexWrap: 'wrap' as const,
    gap: 8,
    marginTop: 12,
  },

  deviceRow: {
    display: 'flex',
    alignItems: 'center',
    gap: 12,
    padding: '16px 0',
    borderBottom: '0.5px solid var(--dsw-alias-border-l2)',
  },
  deviceName: {
    fontSize: 14,
    lineHeight: '20px',
    color: 'var(--dsw-alias-label-primary)',
    overflow: 'hidden',
    textOverflow: 'ellipsis',
    whiteSpace: 'nowrap' as const,
  },
  deviceMeta: {
    marginTop: 4,
    fontSize: 12,
    lineHeight: '18px',
    color: 'var(--dsw-alias-label-secondary)',
  },
  deviceActions: {
    display: 'flex',
    alignItems: 'center',
    gap: 8,
    flex: 'none',
  },

  input: {
    boxSizing: 'border-box' as const,
    flex: 1,
    minWidth: 0,
    height: 34,
    border: '0.5px solid var(--dsw-alias-border-l4)',
    borderRadius: 'var(--dsw-radius-md, 8px)',
    padding: '0 12px',
    background: 'var(--dsw-alias-bg-layer-3, var(--dsw-alias-bg-base))',
    color: 'var(--dsw-alias-label-primary)',
    font: 'inherit',
    fontSize: 13,
    lineHeight: '1.5',
  },
} as const
