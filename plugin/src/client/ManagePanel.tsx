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

// Used only if locale.bind returns the raw key without substituting vars.
const zhFallback = { revokeBody: '将撤销「{name}」。之后该手机需重新配对才能连接。' }

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
      <span style={styles.dot(device.online)} title={device.online ? t('online') : t('offline')} />
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
          <div style={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', fontSize: 13 }}>
            {device.name}
          </div>
        )}
        <div style={styles.muted}>
          {device.online ? t('online') : t('offline')}
          {' · '}
          {t('lastSeen')}: {formatLastSeen(t, device.lastSeenAt)}
        </div>
      </div>
      {editing ? (
        <>
          <button type="button" style={styles.ghostBtn} disabled={busy} onClick={() => void save()}>{t('renameSave')}</button>
          <button type="button" style={styles.ghostBtn} disabled={busy} onClick={() => { setEditing(false); setName(device.name) }}>{t('renameCancel')}</button>
        </>
      ) : (
        <>
          <button type="button" style={styles.ghostBtn} disabled={busy} onClick={() => setEditing(true)}>{t('rename')}</button>
          <button type="button" style={styles.dangerBtn} disabled={busy} onClick={() => setConfirmOpen(true)}>
            {t('revoke')}
          </button>
        </>
      )}
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

  const copyUri = async () => {
    const uri = status?.pairing.uri
    if (!uri) return
    try {
      await navigator.clipboard.writeText(uri)
      setCopied(true)
      setTimeout(() => setCopied(false), 1500)
    } catch { /* ignore */ }
  }

  const pairing = status?.pairing
  const expires = pairing?.open ? Math.max(0, pairing.expiresInSec ?? 0) : 0
  // tick forces countdown re-render while open
  void tick

  return (
    <div style={styles.settingsRoot}>
      {loading && !status ? <div style={styles.muted}>{t('loading')}</div> : null}
      {error ? <div style={{ ...styles.muted, color: 'var(--dsw-alias-state-error-primary)' }}>{t('loadFailed')}: {error}</div> : null}

      {status ? (
        <section>
          <div style={styles.sectionLabel}>{t('statusTitle')}</div>
          <div style={styles.row}>
            <span>{status.running ? t('statusRunning') : t('statusStopped')}</span>
            <button type="button" style={styles.ghostBtn} onClick={() => void refresh()}>{t('refresh')}</button>
          </div>
          <div style={{ ...styles.muted, marginTop: 6 }}>
            {t('listen')}: {status.listen.map((l) => l.address).join(', ') || `${status.host}:${status.port}`}
          </div>
          <div style={styles.muted}>{t('version')}: {status.version}</div>
        </section>
      ) : null}

      <section>
        <div style={styles.sectionLabel}>{t('code')}</div>
        {pairing?.open && pairing.code ? (
          <>
            <div style={styles.code}>{pairing.code}</div>
            <div style={{ ...styles.muted, textAlign: 'center' }}>
              {t('expiresIn', { n: expires })}
              {' · '}
              {t('expiresMin', { n: Math.max(1, Math.round(expires / 60)) })}
            </div>
            {pairing.uri ? (
              <>
                <div style={styles.qrWrap}>
                  <div
                    style={styles.qrBox}
                    dangerouslySetInnerHTML={{ __html: qrSvg(pairing.uri, 168) }}
                  />
                </div>
                <div style={{ ...styles.muted, textAlign: 'center' }}>{t('scanHint')}</div>
                <div style={{ display: 'flex', gap: 8, justifyContent: 'center', marginTop: 8 }}>
                  <button type="button" style={styles.ghostBtn} onClick={() => void copyUri()}>
                    {copied ? t('copied') : t('copy')}
                  </button>
                  <button type="button" style={styles.ghostBtn} onClick={() => void closePair()}>{t('pairClose')}</button>
                  <button type="button" style={styles.primaryBtn} onClick={() => void openPair()}>{t('pairAgain')}</button>
                </div>
              </>
            ) : null}
          </>
        ) : (
          <>
            <div style={styles.muted}>{t('noCode')}</div>
            <div style={{ marginTop: 10 }}>
              <button type="button" style={styles.primaryBtn} onClick={() => void openPair()}>{t('pair')}</button>
            </div>
          </>
        )}
      </section>

      <section>
        <div style={styles.sectionLabel}>{t('devices')}</div>
        {(status?.devices.length ?? 0) === 0 ? (
          <div style={styles.muted}>{t('empty')}</div>
        ) : (
          status!.devices.map((d) => (
            <DeviceItem
              key={d.id}
              device={d}
              t={t}
              api={api}
              onChanged={(devices) => setStatus((s) => (s ? { ...s, devices } : s))}
            />
          ))
        )}
      </section>
    </div>
  )
}
