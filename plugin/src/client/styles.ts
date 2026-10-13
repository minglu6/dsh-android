import type { CSSProperties } from 'react'

const text = 'var(--dsw-alias-label-primary)'
const muted = 'var(--dsw-alias-label-secondary)'
const border = '1px solid var(--dsw-alias-border-l2)'
const surface = 'var(--dsw-alias-bg-module-platform, var(--dsw-alias-bg-base))'
const button: CSSProperties = {
  appearance: 'none', display: 'inline-flex', alignItems: 'center', justifyContent: 'center',
  gap: 8, border: 'none', borderRadius: 8, background: 'transparent', color: muted,
  cursor: 'pointer', font: 'inherit', fontSize: 13, lineHeight: '20px', padding: '8px 10px',
}

export const styles = {
  settingsRoot: { width: '100%', padding: '8px 0 24px', color: text, containerType: 'inline-size' },
  header: { display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 16, marginBottom: 24 },
  title: { margin: 0, fontSize: 24, fontWeight: 650, lineHeight: '32px' },
  service: { display: 'inline-flex', alignItems: 'center', gap: 6, fontSize: 12, color: muted },
  pairingCard: { border, borderRadius: 16, background: surface, padding: '24px 24px 0' },
  pairingTop: { display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 24, minHeight: 168, paddingBottom: 24 },
  pairControls: { display: 'flex', flexDirection: 'column', alignItems: 'flex-start', gap: 18, minWidth: 0 },
  rowTitle: { margin: 0, fontSize: 16, lineHeight: '24px', fontWeight: 600, color: text },
  illustration: { width: 180, maxWidth: '30%', flexShrink: 0, color: 'var(--dsw-alias-label-tertiary)', opacity: .55, marginRight: 20 },
  segments: { display: 'inline-flex', padding: 3, borderRadius: 10, gap: 2, background: 'var(--dsw-alias-bg-overlay)' },
  segment: { ...button, minWidth: 88, padding: '6px 16px' },
  segmentActive: { color: text, background: 'var(--dsw-alias-bg-base)', boxShadow: '0 1px 4px rgba(0,0,0,.08)' },
  footer: { display: 'flex', flexWrap: 'wrap', alignItems: 'center', justifyContent: 'space-between', gap: '8px 16px', borderTop: border, padding: '14px 0' },
  address: { display: 'flex', flexWrap: 'wrap', alignItems: 'center', gap: 8, minWidth: 0, fontSize: 12, color: muted },
  addressValue: { fontFamily: 'ui-monospace, SFMono-Regular, Menlo, Consolas, monospace', fontSize: 12, color: text, overflowWrap: 'anywhere' },
  footerActions: { display: 'flex', alignItems: 'center', gap: 10, marginLeft: 'auto' },
  divider: { width: 1, height: 16, background: 'var(--dsw-alias-border-l2)', flexShrink: 0 },
  iconBtn: { ...button, padding: 8 },
  textBtn: button,
  primaryBtn: { ...button, height: 40, padding: '0 18px', fontSize: 14, fontWeight: 550, borderRadius: 10, background: 'var(--dsw-alias-button-primary-fill)', color: 'var(--dsw-alias-label-primary-foreground, var(--dsw-alias-bg-base))' },
  outlineBtn: { ...button, border, color: text },
  outlineBtnSm: { ...button, padding: '5px 8px', color: text },
  destructiveBtn: { ...button, background: 'var(--dsw-alias-state-error-primary)', color: '#fff', padding: '8px 14px' },
  ghostBtn: { ...button, border, color: text },
  hint: { margin: 0, fontSize: 12, lineHeight: '20px', color: muted },
  errorText: { margin: '0 0 16px', fontSize: 12, lineHeight: '20px', color: 'var(--dsw-alias-state-error-primary)' },
  stateDotSlot: (state: 'done' | 'error' | 'idle'): CSSProperties => ({
    display: 'inline-flex', alignItems: 'center', justifyContent: 'center', width: 10, height: 10, flexShrink: 0,
    color: state === 'done' ? 'var(--dsw-alias-state-success-primary)' : state === 'error' ? 'var(--dsw-alias-state-error-primary)' : 'var(--dsw-alias-label-tertiary)',
  }),
  stateDotCore: { width: 6, height: 6, borderRadius: '50%', background: 'currentColor' },
  pairingContent: { display: 'flex', flexWrap: 'wrap', alignItems: 'center', gap: 24, padding: '0 0 24px' },
  code: { fontFamily: 'ui-monospace, SFMono-Regular, Menlo, Consolas, monospace', fontSize: 28, letterSpacing: '.16em', fontWeight: 600, margin: '4px 0 8px' },
  qrBox: { padding: 10, borderRadius: 10, background: '#fff', lineHeight: 0 },
  actionRow: { display: 'flex', flexWrap: 'wrap', gap: 8, marginTop: 16 },
  devicesSection: { marginTop: 30 },
  devicesHeader: { display: 'flex', alignItems: 'center', gap: 10, marginBottom: 16 },
  count: { background: surface, color: muted, borderRadius: 8, minWidth: 24, textAlign: 'center', fontSize: 12, padding: '2px 4px' },
  deviceList: { display: 'flex', flexDirection: 'column', gap: 10 },
  deviceRow: { display: 'flex', flexWrap: 'wrap', alignItems: 'center', gap: 14, padding: '18px 20px', border, borderRadius: 14 },
  deviceIcon: { display: 'flex', alignItems: 'center', justifyContent: 'center', width: 44, height: 48, borderRadius: 11, background: surface, color: muted },
  deviceInfo: { flex: '1 1 120px', minWidth: 0 },
  deviceHeading: { display: 'flex', alignItems: 'center', gap: 10, minWidth: 0 },
  deviceName: { fontSize: 14, lineHeight: '22px', fontWeight: 550, color: text, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' },
  deviceMeta: { marginTop: 4, fontSize: 12, lineHeight: '18px', color: muted },
  deviceActions: { display: 'flex', alignItems: 'center', gap: 6, marginLeft: 'auto' },
  input: { boxSizing: 'border-box', width: '100%', minWidth: 0, height: 34, border, borderRadius: 8, padding: '0 10px', background: 'var(--dsw-alias-bg-base)', color: text, font: 'inherit', fontSize: 13 },
  dialogMask: { position: 'fixed', inset: 0, zIndex: 1100, display: 'flex', alignItems: 'center', justifyContent: 'center', pointerEvents: 'auto', background: 'rgba(0,0,0,.45)', padding: 24 },
  dialogCard: { width: 'min(560px, calc(100vw - 48px))', maxHeight: '80vh', overflow: 'auto', borderRadius: 14, border, background: 'var(--dsw-alias-bg-base)', color: text, boxShadow: '0 12px 40px rgba(0,0,0,.2)', padding: 24, display: 'flex', flexDirection: 'column', gap: 12 },
  dialogTitle: { margin: 0, fontSize: 16, fontWeight: 600, lineHeight: '24px' },
  dialogBody: { margin: 0, fontSize: 14, lineHeight: '22px', color: muted },
  dialogActions: { display: 'flex', justifyContent: 'flex-end', gap: 8, marginTop: 8 },
  report: { margin: 0, whiteSpace: 'pre-wrap', overflowWrap: 'anywhere', fontSize: 12, lineHeight: '20px', color: muted },
} satisfies Record<string, CSSProperties | ((state: 'done' | 'error' | 'idle') => CSSProperties)>

export const panelCss = `
[data-pocket-panel] button:disabled { opacity: .45; cursor: not-allowed !important; }
[data-pocket-panel] button:not(:disabled):hover { filter: brightness(.94); }
[data-pocket-panel] button:focus-visible { outline: 2px solid var(--dsw-alias-label-secondary); outline-offset: 3px; }
@container (max-width: 440px) {
  [data-pocket-panel] .pocket-illustration { display: none; }
  [data-pocket-panel] .pocket-device-actions { width: 100%; justify-content: flex-end; }
}
`
