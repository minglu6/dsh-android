import { useCallback, useEffect, useState, type JSX } from 'react'
import type { DeviceRow, PocketApi, PocketStatus } from './api.ts'
import { ConfirmDialog } from './ConfirmDialog.tsx'
import { qrSvg } from './qr.ts'
import { styles } from './styles.ts'

type TFn = (key: string, vars?: Record<string, string | number>) => string

function formatLastSeen(t: TFn, at: number | null): string {
  if (at == null) return t('neverSeen')
  try {
    return new Date(at).toLocaleString()
  } catch {
    return t('neverSeen')
  }
}

/** Prefer framework interpolation; fall back to `{name}` replacement. */
function tr(t: TFn, key: string, vars: Record<string, string | number>): string {
  const out = t(key, vars)
  if (out !== key && !/\{name\}/.test(out)) return out
  return Object.entries(vars).reduce(
    (s, [k, v]) => s.replaceAll(`{${k}}`, String(v)),
    out === key ? String((zhFallback as Record<string, string>)[key] ?? key) : out,
  )
}

const zhFallback = { revokeBody: '将撤销「{name}」。之后该手机需重新配对才能连接。' }

/** Visual twin of primitives `StateDot` (10px slot / 6px core); no package require. */
function StatusDot({ state }: { state: 'done' | 'error' | 'idle' }): JSX.Element {
  return (
    <span style={styles.stateDotSlot(state)} aria-hidden="true">
      <span style={styles.stateDotCore} />
    </span>
  )
}

function DeviceItem({
  device, t, api, onChanged,
}: {
  device: DeviceRow
  t: TFn
  api: PocketApi
  onChanged: (devices: DeviceRow[]) => void
}): JSX.Element {
  const [editing, setEditing] = useState(false)
  const [name, setName] = useState(device.name)
  const [confirmOpen, setConfirmOpen] = useState(false)
  const [busy, setBusy] = useState(false)

  useEffect(() => { setName(device.name) }, [device.name])

  const save = async () => {
    const trimmed = name.trim()
    if (!trimmed || trimmed === device.name) { setEditing(false); return }
    setBusy(true)
    try {
      const r = await api.rename(device.id, trimmed)
      onChanged(r.devices)
      setEditing(false)
    } finally { setBusy(false) }
  }

  const confirmRevoke = async () => {
    setBusy(true)
    try {
      const r = await api.revoke(device.id)
      onChanged(r.devices)
      setConfirmOpen(false)
    } finally { setBusy(false) }
  }

  return (
    <div style={styles.deviceRow}>
      <StatusDot state={device.online ? 'done' : 'idle'} />
      <div style={{ flex: 1, minWidth: 0 }}>
        {editing ? (
          <input
            style={styles.input}
            value={name}
            disabled={busy}
            onChange={(e) => setName(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter') void save()
              if (e.key === 'Escape') { setEditing(false); setName(device.name) }
            }}
            autoFocus
          />
        ) : (
          <div style={styles.deviceName}>{device.name}</div>
        )}
        <div style={styles.deviceMeta}>
          {device.online ? t('online') : t('offline')}
          {' · '}
          {t('lastSeen')}: {formatLastSeen(t, device.lastSeenAt)}
        </div>
      </div>
      <div style={styles.deviceActions}>
        {editing ? (
          <>
            <button type="button" style={styles.outlineBtnSm} disabled={busy} onClick={() => void save()}>{t('renameSave')}</button>
            <button type="button" style={styles.outlineBtnSm} disabled={busy} onClick={() => { setEditing(false); setName(device.name) }}>{t('renameCancel')}</button>
          </>
        ) : (
          <>
            <button type="button" style={styles.outlineBtnSm} disabled={busy} onClick={() => setEditing(true)}>{t('rename')}</button>
            <button type="button" style={styles.dangerBtn} disabled={busy} onClick={() => setConfirmOpen(true)}>
              {t('revoke')}
            </button>
          </>
        )}
      </div>
      <ConfirmDialog
        open={confirmOpen}
        title={t('revokeTitle')}
        body={tr(t, 'revokeBody', { name: device.name })}
        cancelLabel={t('cancel')}
        confirmLabel={t('revoke')}
        busy={busy}
        onCancel={() => { if (!busy) setConfirmOpen(false) }}
        onConfirm={() => void confirmRevoke()}
      />
    </div>
  )
}

export function ManagePanel({
  t, api,
}: {
  t: TFn
  api: PocketApi
}): JSX.Element {
  const [status, setStatus] = useState<PocketStatus | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(true)
  const [copied, setCopied] = useState(false)
  const [diagCopied, setDiagCopied] = useState(false)
  const [diagBusy, setDiagBusy] = useState(false)
  const [tick, setTick] = useState(0)

  const refresh = useCallback(async () => {
    setError(null)
    try {
      const s = await api.status()
      setStatus(s)
    } catch (e) {
      setError(String((e as Error)?.message ?? e))
    } finally {
      setLoading(false)
    }
  }, [api])

  useEffect(() => { void refresh() }, [refresh])

  useEffect(() => {
    if (!status?.pairing.open) return
    const id = setInterval(() => {
      setTick((n) => n + 1)
      void refresh()
    }, 1000)
    return () => clearInterval(id)
  }, [status?.pairing.open, refresh])

  const openPair = async () => {
    setLoading(true)
    try {
      await api.openPair()
      await refresh()
    } catch (e) {
      setError(String((e as Error)?.message ?? e))
    } finally {
      setLoading(false)
    }
  }

  const closePair = async () => {
    try {
      await api.closePair()
      await refresh()
    } catch (e) {
      setError(String((e as Error)?.message ?? e))
    }
  }

  const copyPairing = async () => {
    const line = status?.pairing.pairingString || status?.pairing.uri
    if (!line) return
    try {
      await navigator.clipboard.writeText(line)
      setCopied(true)
      setTimeout(() => setCopied(false), 1500)
    } catch { /* ignore */ }
  }

  const copyDiagnostics = async () => {
    setDiagBusy(true)
    try {
      const d = await api.diagnostics()
      await navigator.clipboard.writeText(d.report)
      setDiagCopied(true)
      setTimeout(() => setDiagCopied(false), 1500)
    } catch (e) {
      setError(String((e as Error)?.message ?? e))
    } finally {
      setDiagBusy(false)
    }
  }

  const pairing = status?.pairing
  const expires = pairing?.open ? Math.max(0, pairing.expiresInSec ?? 0) : 0
  void tick

  const pairReady = Boolean(status?.listen?.length)
  const connectorOk = pairReady && !error

  return (
    <div style={styles.settingsRoot}>
      {loading && !status ? <div style={styles.hint}>{t('loading')}</div> : null}
      {error ? <div style={styles.errorText}>{t('loadFailed')}: {error}</div> : null}

      {status ? (
        <div style={{ ...styles.settingsRow, alignItems: 'flex-start' }}>
          <div style={{ flex: 1, minWidth: 0 }}>
            <div style={styles.rowTitle}>{t('statusTitle')}</div>
            <div style={{ ...styles.valueLine, marginTop: 6 }}>
              {t('wsReady')}
              <StatusDot state={connectorOk ? 'done' : 'error'} />
            </div>
            <div style={styles.metaStack}>
              <div style={styles.metaLine}>
                {t('advertise')}: {status.host}:{status.port}
              </div>
              <div style={styles.metaLine}>{t('version')}: {status.version}</div>
              <div style={styles.metaLine}>{t('wsHint')}</div>
            </div>
          </div>
          <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
            <button type="button" style={styles.outlineBtnSm} onClick={() => void refresh()}>{t('refresh')}</button>
            <button type="button" style={styles.outlineBtnSm} disabled={diagBusy} onClick={() => void copyDiagnostics()} aria-label={t('openDiagnostics')}>
              {diagCopied ? t('diagnosticsCopied') : t('diagnostics')}
            </button>
          </div>
        </div>
      ) : null}

      <div style={styles.settingsBlock}>
        <div style={styles.rowTitle}>{t('code')}</div>
        {pairing?.open && pairing.code && pairing.uri ? (
          <>
            <div style={styles.code}>{pairing.code}</div>
            <div style={{ ...styles.hint, textAlign: 'center' }}>
              {t('expiresIn', { n: expires })}
              {' · '}
              {t('expiresMin', { n: Math.max(1, Math.round(expires / 60)) })}
            </div>
            <div style={styles.qrWrap}>
              <div
                style={styles.qrBox}
                dangerouslySetInnerHTML={{ __html: qrSvg(pairing.uri, 168) }}
              />
            </div>
            <div style={{ ...styles.rowDescription, textAlign: 'center' }}>{t('scanHint')}</div>
            {pairing.pairingString ? (
              <div style={{ ...styles.metaLine, textAlign: 'center', wordBreak: 'break-all', marginTop: 8 }}>
                {pairing.pairingString}
              </div>
            ) : null}
            <div style={{ ...styles.actionRow, justifyContent: 'center' }}>
              <button type="button" style={styles.outlineBtn} onClick={() => void copyPairing()}>
                {copied ? t('copied') : t('copy')}
              </button>
              <button type="button" style={styles.outlineBtn} onClick={() => void closePair()}>{t('pairClose')}</button>
              <button type="button" style={styles.primaryBtn} onClick={() => void openPair()}>{t('pairAgain')}</button>
            </div>
          </>
        ) : (
          <>
            <div style={{ ...styles.rowDescription, marginTop: 4 }}>
              {pairReady ? t('noCode') : t('wsHint')}
            </div>
            <div style={styles.actionRow}>
              <button type="button" style={styles.primaryBtn} disabled={!pairReady} onClick={() => void openPair()}>{t('pair')}</button>
            </div>
          </>
        )}
      </div>

      <div style={styles.settingsBlock}>
        <div style={styles.rowTitle}>{t('devices')}</div>
        {(status?.devices.length ?? 0) === 0 ? (
          <div style={{ ...styles.rowDescription, marginTop: 8 }}>{t('empty')}</div>
        ) : (
          <div style={{ marginTop: 4 }}>
            {status!.devices.map((d) => (
              <DeviceItem
                key={d.id}
                device={d}
                t={t}
                api={api}
                onChanged={(devices) => setStatus((s) => (s ? { ...s, devices } : s))}
              />
            ))}
          </div>
        )}
      </div>
    </div>
  )
}
