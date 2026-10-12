/**
 * Connection diagnostics snapshot (secrets redacted).
 *
 * Adapted from dsh-mobile src/diagnostics.ts
 * (https://github.com/saya-ch/dsh-mobile), Apache-2.0.
 */
import { networkInterfaces } from 'node:os'
import { createConnection } from 'node:net'
import { isTailscaleHost } from './advertise-host.js'
import { DISCOVERY_PROTOCOL } from './mdns-advertise.js'

/**
 * @param {string | undefined} host
 * @returns {string}
 */
function maskHost(host) {
  if (!host) return '未分配'
  if (host.endsWith('.ts.net')) return '*.ts.net'
  const octets = host.split('.')
  if (octets.length === 4 && octets.every((p) => /^\d+$/.test(p))) {
    return `${octets[0]}.${octets[1]}.${octets[2]}.x`
  }
  return '地址已脱敏'
}

/**
 * List non-internal IPv4 interfaces with addresses partially redacted.
 * @returns {{ name: string, address: string, family: string, internal: boolean }[]}
 */
export function listInterfacesRedacted() {
  const out = []
  for (const [name, list] of Object.entries(networkInterfaces())) {
    for (const n of list ?? []) {
      const family = n.family === 4 || n.family === 'IPv4' ? 'IPv4' : String(n.family)
      if (family !== 'IPv4') continue
      out.push({
        name,
        address: n.internal ? n.address : maskHost(n.address),
        family,
        internal: Boolean(n.internal),
        tailscale: isTailscaleHost(n.address),
      })
    }
  }
  return out
}

/**
 * @param {string} host
 * @param {number} port
 * @param {number} [timeoutMs]
 * @returns {Promise<{ ok: boolean, latencyMs?: number, error?: string }>}
 */
export function probePortReachability(host, port, timeoutMs = 2_500) {
  return new Promise((resolve) => {
    const started = Date.now()
    const socket = createConnection({ host, port })
    let settled = false
    const finish = (result) => {
      if (settled) return
      settled = true
      socket.destroy()
      resolve(result)
    }
    socket.setTimeout(timeoutMs)
    socket.once('connect', () => finish({ ok: true, latencyMs: Date.now() - started }))
    socket.once('timeout', () => finish({ ok: false, error: 'timeout' }))
    socket.once('error', (err) => finish({ ok: false, error: err.code || err.message || 'error' }))
  })
}

/**
 * Detect whether a Tailscale IPv4 / MagicDNS address is present on this machine.
 * @returns {{ present: boolean, host?: string }}
 */
export function tailscaleState() {
  for (const list of Object.values(networkInterfaces())) {
    for (const n of list ?? []) {
      const family = n.family
      if (family !== 'IPv4' && family !== 4) continue
      if (n.internal) continue
      if (isTailscaleHost(n.address)) return { present: true, host: maskHost(n.address) }
    }
  }
  return { present: false }
}

/**
 * @param {{
 *   version: string,
 *   plugin: string,
 *   transport: string,
 *   host: string,
 *   port: number,
 *   instanceId: string,
 *   connectionType: string,
 *   running: boolean,
 *   mdns?: boolean,
 *   deviceName?: string,
 *   probes?: {
 *     reachability?: (host: string, port: number) => Promise<{ ok: boolean, latencyMs?: number, error?: string }>,
 *   },
 * }} snapshot
 */
export async function collectConnectionDiagnostics(snapshot) {
  const reach = snapshot.probes?.reachability ?? probePortReachability
  const interfaces = listInterfacesRedacted()
  const ts = tailscaleState()
  const reachability = snapshot.running
    ? await reach(snapshot.host === '0.0.0.0' ? '127.0.0.1' : snapshot.host, snapshot.port)
    : { ok: false, error: 'not-running' }

  const checks = [
    {
      id: 'versions',
      status: 'ok',
      label: '版本',
      detail: `插件 ${snapshot.plugin} ${snapshot.version}`,
    },
    {
      id: 'transport',
      status: 'ok',
      label: '传输',
      detail: `transport=${snapshot.transport}; connectionType=${snapshot.connectionType}`,
    },
    {
      id: 'interfaces',
      status: interfaces.some((i) => !i.internal) ? 'ok' : 'warning',
      label: '网卡',
      detail: interfaces.length === 0
        ? '未发现可用 IPv4 网卡'
        : interfaces.map((i) => `${i.name}=${i.address}${i.tailscale ? ' (tailscale)' : ''}`).join('; '),
    },
    {
      id: 'tailscale',
      status: ts.present ? 'ok' : 'info',
      label: 'Tailscale',
      detail: ts.present ? `本机存在 Tailscale 地址 ${ts.host}` : '本机未检测到 Tailscale 地址（局域网仍可用）',
    },
    {
      id: 'port',
      status: reachability.ok ? 'ok' : 'error',
      label: '端口可达性',
      detail: reachability.ok
        ? `本机 ${maskHost(snapshot.host)}:${snapshot.port} 可达，约 ${reachability.latencyMs ?? 0} ms`
        : `无法连接本机监听端口（${reachability.error ?? 'error'}）`,
    },
    {
      id: 'mdns',
      status: snapshot.mdns ? 'ok' : 'info',
      label: '局域网发现',
      detail: snapshot.mdns
        ? `正在广播 _dshpocket._tcp（${snapshot.deviceName ?? 'dsh'}）`
        : 'mDNS 未运行',
    },
  ]

  const overall = checks.some((c) => c.status === 'error')
    ? 'error'
    : checks.some((c) => c.status === 'warning') ? 'attention' : 'ok'
  const summary = overall === 'ok' ? '连接基础检查正常。' : overall === 'attention' ? '发现需要留意的项目。' : '发现会影响连接的问题。'
  const report = [
    'DSH Pocket 诊断报告',
    `生成时间: ${new Date().toISOString()}`,
    `版本: plugin=${snapshot.plugin}@${snapshot.version}`,
    `传输: ${snapshot.transport} / ${snapshot.connectionType}`,
    `实例: ${snapshot.instanceId.slice(0, 8)}…`,
    `端点: ${maskHost(snapshot.host)}:${snapshot.port}`,
    `协议: discovery=${DISCOVERY_PROTOCOL}`,
    ...checks.map((c) => `[${c.status.toUpperCase()}] ${c.label}: ${c.detail}`),
  ].join('\n')

  return Object.freeze({
    version: 1,
    generatedAt: Date.now(),
    overall,
    summary,
    checks: Object.freeze(checks),
    report,
    facts: Object.freeze({
      plugin: snapshot.plugin,
      pluginVersion: snapshot.version,
      transport: snapshot.transport,
      connectionType: snapshot.connectionType,
      host: maskHost(snapshot.host),
      port: snapshot.port,
      instanceIdPrefix: snapshot.instanceId.slice(0, 8),
      running: snapshot.running,
      mdns: Boolean(snapshot.mdns),
      interfaces,
      tailscale: ts,
      reachability: {
        ok: reachability.ok,
        ...(reachability.latencyMs === undefined ? {} : { latencyMs: reachability.latencyMs }),
        ...(reachability.error === undefined ? {} : { error: reachability.error }),
      },
    }),
  })
}
