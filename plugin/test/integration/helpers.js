import { spawn, spawnSync } from 'node:child_process'
import { once } from 'node:events'
import { mkdtempSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join, resolve } from 'node:path'
import WebSocket from 'ws'

export const hasDsh = spawnSync('dsh', ['--version'], { encoding: 'utf8' }).status === 0
export const PLUGIN_DIR = resolve(import.meta.dirname, '../..')

/** Boot `dsh web` in a throwaway DSH_HOME with the given plugin dirs and patch YAML. */
export async function bootDsh(t, { plugins, patch, home: existingHome }) {
  const home = existingHome ?? mkdtempSync(join(tmpdir(), 'dshhome-'))
  const env = { ...process.env, DSH_HOME: home }
  for (const dir of plugins) {
    const add = spawnSync('dsh', ['plugin', '--profile', 'web', 'add', dir], { env, encoding: 'utf8' })
    if (add.status !== 0) throw new Error(`dsh plugin add ${dir} failed:\n${add.stderr}${add.stdout}`)
  }
  writeFileSync(join(home, 'profiles/web/cordis.patch.yml'), patch)
  const proc = spawn('dsh', ['web', '--no-open', '--port', '0'], { env, stdio: ['ignore', 'pipe', 'pipe'] })
  const stop = async () => {
    if (proc.exitCode !== null || proc.signalCode !== null) return
    const exited = once(proc, 'exit')
    proc.kill()
    await exited
  }
  t.after(stop)
  let log = ''
  const webUrl = await new Promise((ok, fail) => {
    const timer = setTimeout(() => fail(new Error('dsh did not start:\n' + log)), 120_000)
    const on = (d) => {
      log += d
      const m = log.match(/dsh web: (http:\/\/\S+\?token=\S+)/)
      // Ready once dsh web URL is up and pocket has bound its loopback WS (or legacy "listening" log).
      if (m && (log.includes('[pocket] local ws://') || log.includes('[pocket] listening') || log.includes('[pocket] ws listening'))) {
        clearTimeout(timer); ok(m[1])
      }
    }
    proc.stdout.on('data', on); proc.stderr.on('data', on)
    proc.on('exit', (c) => fail(new Error(`dsh exited ${c}:\n${log}`)))
  })
  return { home, webUrl, stop, log: () => log }
}

/** JSON-over-WS client for the pocket protocol. */
export async function pocketClient(url) {
  const ws = new WebSocket(url)
  await new Promise((r, j) => { ws.on('open', r); ws.on('error', j) })
  const q = []; const w = []
  ws.on('message', (d) => { const m = JSON.parse(d); const i = w.findIndex((x) => x.p(m)); if (i >= 0) w.splice(i, 1)[0].r(m); else q.push(m) })
  const next = (p = () => true, ms = 15_000) => {
    const i = q.findIndex(p)
    if (i >= 0) return Promise.resolve(q.splice(i, 1)[0])
    return new Promise((r, j) => { const e = { p, r }; w.push(e); setTimeout(() => { const k = w.indexOf(e); if (k >= 0) { w.splice(k, 1); j(new Error('timeout waiting for message')) } }, ms) })
  }
  let id = 0
  const call = (op, args = {}) => { const i = ++id; ws.send(JSON.stringify({ id: i, op, args: { proto: 1, ...args } })); return next((m) => m.id === i) }
  const ok = async (op, args) => { const r = await call(op, args); if (!r.ok) throw new Error(`${op}: ${JSON.stringify(r.error)}`); return r.result }
  return { ws, next, call, ok, close: () => ws.close() }
}

/** Minimal dsh web-UI stand-in: browser cookie + /api/remote.mux `$events` stream. */
export async function webUiClient(launchUrl) {
  const res = await fetch(launchUrl, { redirect: 'manual' })
  const cookie = res.headers.getSetCookie()[0]?.split(';', 1)[0]
  if (!cookie) throw new Error(`no auth cookie (HTTP ${res.status})`)
  const origin = new URL(launchUrl).origin
  const ws = new WebSocket(`${origin.replace('http', 'ws')}/api/remote.mux`, { headers: { cookie } })
  await new Promise((r, j) => { ws.on('open', r); ws.on('error', j) })
  const frames = []; const w = []
  ws.on('message', (d) => {
    const m = JSON.parse(d)
    if (m.type !== 'item' || m.streamId !== 'ev') return
    const v = m.value; const i = w.findIndex((x) => x.p(v)); if (i >= 0) w.splice(i, 1)[0].r(v); else frames.push(v)
  })
  const next = (p, ms = 15_000) => {
    const i = frames.findIndex(p)
    if (i >= 0) return Promise.resolve(frames.splice(i, 1)[0])
    return new Promise((r, j) => { const e = { p, r }; w.push(e); setTimeout(() => { const k = w.indexOf(e); if (k >= 0) { w.splice(k, 1); j(new Error('web: timeout')) } }, ms) })
  }
  ws.send(JSON.stringify({ type: 'open', streamId: 'ev', endpoint: '$events', payload: { args: {} } }))
  const ready = await next((v) => v.type === 'ready')
  const answer = async (eventId, value) => {
    const r = await fetch(`${origin}/api/$events/result`, {
      method: 'POST', headers: { 'content-type': 'application/json', cookie },
      body: JSON.stringify({ type: 'client-request', rpcId: crypto.randomUUID(), method: '$events/result', payload: { args: { clientId: ready.clientId, eventId, outcome: { kind: 'result', value } } } }),
    })
    return r.json()
  }
  return { ws, next, answer, origin, cookie, clientId: ready.clientId, close: () => ws.close() }
}
