import { useCallback, useEffect, useRef, useState, type JSX } from 'react'
import type { ConnectionDiagnostics, DeviceRow, NetworkType, PocketApi, PocketStatus } from './api.ts'
import { ConfirmDialog } from './ConfirmDialog.tsx'
import { qrSvg } from './qr.ts'
import { panelCss, styles } from './styles.ts'

type TFn = (key: string, vars?: Record<string, string | number>) => string

function Icon({ name, size = 18 }: { name: 'phone' | 'copy' | 'refresh' | 'plus' | 'check', size?: number }): JSX.Element {
  const paths = {
    phone: <><rect x="6" y="2" width="12" height="20" rx="3" /><path d="M11 18h2" /></>,
    copy: <><rect x="8" y="8" width="12" height="14" rx="2" /><path d="M15 4V2H4v14h1" /></>,
    refresh: <><path d="M20 7a9 9 0 1 0 0 10M20 2v5h-5" /></>,
    plus: <path d="M12 4v16M4 12h16" />,
    check: <path d="m5 12 4 4L19 6" />,
  }
  return <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">{paths[name]}</svg>
}

function StatusDot({ state }: { state: 'done' | 'error' | 'idle' }): JSX.Element {
  return <span style={styles.stateDotSlot(state)} aria-hidden="true"><span style={styles.stateDotCore} /></span>
}

function DeviceItem({ device, t, api, onChanged, onError }: {
  device: DeviceRow
  t: TFn
  api: PocketApi
  onChanged: (devices: DeviceRow[]) => void
  onError: (message: string) => void
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
    } catch (e) { onError(String((e as Error)?.message ?? e)) }
    finally { setBusy(false) }
  }
  const revoke = async () => {
    setBusy(true)
    try {
      const r = await api.revoke(device.id)
      onChanged(r.devices)
      setConfirmOpen(false)
    } catch (e) { onError(String((e as Error)?.message ?? e)) }
    finally { setBusy(false) }
  }
  const lastSeen = device.lastSeenAt == null ? t('neverSeen') : new Date(device.lastSeenAt).toLocaleString(undefined, {
    month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false,
  })

  return <div style={styles.deviceRow}>
    <div style={styles.deviceIcon}><Icon name="phone" size={28} /></div>
    <div style={styles.deviceInfo}>
      {editing ? <input style={styles.input} aria-label={t('rename')} value={name} disabled={busy}
        onChange={(e) => setName(e.target.value)} autoFocus
        onKeyDown={(e) => {
          if (e.key === 'Enter') void save()
          if (e.key === 'Escape') { setEditing(false); setName(device.name) }
        }} /> : <div style={styles.deviceHeading}>
          <span style={styles.deviceName} title={device.name}>{device.name}</span>
          <span style={styles.service}><StatusDot state={device.online ? 'done' : 'idle'} />{device.online ? t('online') : t('offline')}</span>
        </div>}
      <div style={styles.deviceMeta}>{t('lastSeen')} {lastSeen}</div>
    </div>
    <div className="pocket-device-actions" style={styles.deviceActions}>
      {editing ? <>
        <button type="button" style={styles.outlineBtnSm} disabled={busy} onClick={() => void save()}>{t('renameSave')}</button>
        <button type="button" style={styles.textBtn} disabled={busy} onClick={() => { setEditing(false); setName(device.name) }}>{t('renameCancel')}</button>
      </> : <>
        <button type="button" style={styles.textBtn} disabled={busy} onClick={() => setEditing(true)}>{t('rename')}</button>
        <span style={styles.divider} />
        <button type="button" style={styles.textBtn} disabled={busy} onClick={() => setConfirmOpen(true)}>{t('revoke')}</button>
      </>}
    </div>
    <ConfirmDialog open={confirmOpen} title={t('revokeTitle')}
      body={t('revokeBody', { name: device.name }).replaceAll('{name}', device.name)} cancelLabel={t('cancel')}
      confirmLabel={t('revoke')} busy={busy} onCancel={() => { if (!busy) setConfirmOpen(false) }} onConfirm={() => void revoke()} />
  </div>
}

export function ManagePanel({ t, api }: { t: TFn, api: PocketApi }): JSX.Element {
  const [status, setStatus] = useState<PocketStatus | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(true)
  const [busy, setBusy] = useState(false)
  const [copied, setCopied] = useState<string | null>(null)
  const [diagnostics, setDiagnostics] = useState<ConnectionDiagnostics | null>(null)
  const [diagBusy, setDiagBusy] = useState(false)
  const network = useRef<NetworkType | undefined>(undefined)
  const request = useRef(0)
  const copyTimer = useRef<number | undefined>(undefined)

  const refresh = useCallback(async (background = false) => {
    const id = ++request.current
    if (!background) setLoading(true)
    try {
      const s = await api.status(network.current)
      if (id !== request.current) return
      setStatus(s)
      setError(null)
    } catch (e) {
      if (id !== request.current) return
      setStatus(null)
      setError(String((e as Error)?.message ?? e))
    } finally { if (id === request.current) setLoading(false) }
  }, [api])

  useEffect(() => {
    void refresh()
    return () => { request.current++; clearTimeout(copyTimer.current) }
  }, [refresh])
  useEffect(() => {
    if (!status?.pairing.open) return
    const timer = setInterval(() => { void refresh(true) }, 1000)
    return () => clearInterval(timer)
  }, [status?.pairing.open, refresh])
  useEffect(() => {
    if (!diagnostics) return
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') setDiagnostics(null) }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [diagnostics])

  const changeNetwork = (next: NetworkType) => {
    network.current = next
    setCopied(null)
    // Hide the old QR immediately; it must never appear under a new selection.
    setStatus((s) => s ? { ...s, pairing: { open: false } } : s)
    void refresh()
  }
  const updatePairing = async (open: boolean) => {
    setBusy(true)
    setError(null)
    try {
      if (open) await api.openPair(network.current)
      else await api.closePair()
      await refresh()
    } catch (e) { setError(String((e as Error)?.message ?? e)) }
    finally { setBusy(false) }
  }
  const copy = async (text: string, key: string) => {
    try {
      await navigator.clipboard.writeText(text)
      clearTimeout(copyTimer.current)
      setCopied(key)
      copyTimer.current = window.setTimeout(() => setCopied(null), 1500)
    } catch (e) { setError(String((e as Error)?.message ?? e)) }
  }
  const diagnose = async () => {
    setDiagBusy(true)
    try { setDiagnostics(await api.diagnostics()) }
    catch (e) { setError(String((e as Error)?.message ?? e)) }
    finally { setDiagBusy(false) }
  }

  const pairing = status?.pairing
  const selected = status?.connectionType
  const pairReady = Boolean(status?.running && status.networks.length && status.host)
  const pairingOpen = Boolean(pairReady && pairing?.open && pairing.code && pairing.uri)
  const address = status?.host ? `${status.host}:${status.port}` : ''
  const disabled = loading || busy

  return <div data-pocket-panel style={styles.settingsRoot}>
    <style>{panelCss}</style>
    <header style={styles.header}>
      <h2 style={styles.title}>{t('title')}</h2>
      <span style={styles.service}><StatusDot state={pairReady ? 'done' : 'idle'} />{loading && !status ? t('loading') : t(pairReady ? 'statusRunning' : 'statusStopped')}</span>
    </header>
    {error ? <div role="alert" style={styles.errorText}>{error}</div> : null}
    <section style={styles.pairingCard} aria-label={t('newDevice')}>
      <div style={{ ...styles.pairingTop, ...(pairingOpen ? { minHeight: 0 } : {}) }}>
        <div style={styles.pairControls}>
          <h3 style={styles.rowTitle}>{t('newDevice')}</h3>
          {status && status.networks.length > 1 ? <div style={styles.segments} role="group" aria-label={t('network')}>
            {status.networks.map((n) => <button type="button" key={n.type} aria-pressed={selected === n.type}
              style={{ ...styles.segment, ...(selected === n.type ? styles.segmentActive : {}) }} disabled={disabled}
              onClick={() => changeNetwork(n.type)}>{t(n.type)}</button>)}
          </div> : null}
          {!pairingOpen ? <button type="button" style={styles.primaryBtn} disabled={disabled || !pairReady} onClick={() => void updatePairing(true)}>
            <Icon name="plus" />{t('pair')}
          </button> : null}
        </div>
        {!pairingOpen ? <svg className="pocket-illustration" style={styles.illustration} viewBox="0 0 180 130" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
          <path d="M20 94V22a8 8 0 0 1 8-8h105a8 8 0 0 1 8 8v11M121 94H7v8h114" />
          <rect x="132" y="43" width="40" height="75" rx="8" /><path d="M148 50h9m-8 59h7" />
        </svg> : null}
      </div>
      {pairingOpen ? <div style={styles.pairingContent}>
        <div style={styles.qrBox} role="img" aria-label={t('code')} dangerouslySetInnerHTML={{ __html: qrSvg(pairing!.uri!, 168) }} />
        <div>
          <div style={styles.code}>{pairing!.code}</div>
          <p style={styles.hint}>{t('expiresIn', { n: Math.max(0, pairing!.expiresInSec ?? 0) })}</p>
          <div style={styles.actionRow}>
            <button type="button" style={styles.outlineBtn} onClick={() => void copy(pairing!.pairingString || pairing!.uri!, 'pair')}>{t(copied === 'pair' ? 'copied' : 'copy')}</button>
            <button type="button" style={styles.textBtn} disabled={disabled} onClick={() => void updatePairing(false)}>{t('pairClose')}</button>
            <button type="button" style={styles.textBtn} disabled={disabled} onClick={() => void updatePairing(true)}>{t('pairAgain')}</button>
          </div>
        </div>
      </div> : null}
      <footer style={styles.footer}>
        <div style={styles.address}>
          {address ? <>
            <span>{t(selected === 'tailscale' ? 'tailscaleAddress' : 'lanAddress')}</span>
            <code style={styles.addressValue}>{address}</code>
            <button type="button" style={styles.iconBtn} title={t(copied === 'address' ? 'copied' : 'copyAddress')} aria-label={t(copied === 'address' ? 'copied' : 'copyAddress')} onClick={() => void copy(address, 'address')}><Icon name={copied === 'address' ? 'check' : 'copy'} size={16} /></button>
          </> : <span>{t(loading ? 'loading' : 'noNetwork')}</span>}
        </div>
        <div style={styles.footerActions}>
          <button type="button" style={styles.iconBtn} disabled={disabled} title={t('refresh')} aria-label={t('refresh')}
            onClick={() => { if (!status) network.current = undefined; void refresh() }}><Icon name="refresh" /></button>
          <span style={styles.divider} />
          <button type="button" style={styles.textBtn} disabled={diagBusy} onClick={() => void diagnose()}>{t('diagnostics')}</button>
        </div>
      </footer>
    </section>
    <section style={styles.devicesSection} aria-label={t('devices')}>
      <div style={styles.devicesHeader}><h3 style={styles.rowTitle}>{t('devices')}</h3><span style={styles.count}>{status?.devices.length ?? 0}</span></div>
      <div style={styles.deviceList}>
        {status?.devices.length ? status.devices.map((device) => <DeviceItem key={device.id} device={device} t={t} api={api} onError={setError}
          onChanged={(devices) => setStatus((s) => s ? { ...s, devices } : s)} />) : <p style={styles.hint}>{t(loading ? 'loading' : 'empty')}</p>}
      </div>
    </section>
    {diagnostics ? <div style={styles.dialogMask} onClick={(e) => { if (e.target === e.currentTarget) setDiagnostics(null) }}>
      <div role="dialog" aria-modal="true" aria-label={t('diagnostics')} style={styles.dialogCard}>
        <h3 style={styles.dialogTitle}>{t('diagnostics')}</h3>
        <pre style={styles.report}>{diagnostics.report}</pre>
        <div style={styles.dialogActions}>
          <button type="button" style={styles.outlineBtn} onClick={() => void copy(diagnostics.report, 'diagnostics')}>{t(copied === 'diagnostics' ? 'copied' : 'diagnosticsCopy')}</button>
          <button type="button" style={styles.primaryBtn} autoFocus onClick={() => setDiagnostics(null)}>{t('close')}</button>
        </div>
      </div>
    </div> : null}
  </div>
}
