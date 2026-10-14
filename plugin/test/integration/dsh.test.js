// Boots a real `dsh web` with this plugin linked into a throwaway DSH_HOME.
// Requires `dsh` (0.2.1-alpha.2) on PATH. Skips when absent.
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { spawn, spawnSync } from 'node:child_process'
import { mkdtempSync, writeFileSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join, resolve } from 'node:path'
import WebSocket from 'ws'

const hasDsh = spawnSync('dsh', ['--version'], { encoding: 'utf8' }).status === 0
const TOKEN = 'integration-token-0123456789'
const PORT = 17690 + Math.floor(Math.random() * 200)

test('real dsh: hello, list, create, rename, follow, models, select, cancel', { skip: !hasDsh, timeout: 240_000 }, async (t) => {
  const home = mkdtempSync(join(tmpdir(), 'dshhome-'))
  const env = { ...process.env, DSH_HOME: home }
  const add = spawnSync('dsh', ['plugin', '--profile', 'web', 'add', resolve(import.meta.dirname, '../..')], { env, encoding: 'utf8' })
  assert.equal(add.status, 0, add.stderr)
  writeFileSync(join(home, 'profiles/web/cordis.patch.yml'), `- id: dsh-pocket\n  config:\n    port: ${PORT}\n    token: ${TOKEN}\n`)
  const proc = spawn('dsh', ['web', '--no-open', '--port', '0'], { env, stdio: ['ignore', 'pipe', 'pipe'] })
  t.after(() => proc.kill())
  let log = ''
  await new Promise((ok, fail) => {
    const timer = setTimeout(() => fail(new Error('pocket did not start:\n' + log)), 120_000)
    const on = (d) => {
      log += d
      if (log.includes('[pocket] local ws://') || log.includes('[pocket] listening') || log.includes('[pocket] ws listening')) {
        clearTimeout(timer); ok()
      }
    }
    proc.stdout.on('data', on); proc.stderr.on('data', on)
    proc.on('exit', (c) => fail(new Error(`dsh exited ${c}:\n${log}`)))
  })
  const ws = new WebSocket(`ws://127.0.0.1:${PORT}`)
  await new Promise((r, j) => { ws.on('open', r); ws.on('error', j) })
  let id = 0; const pend = new Map(); const events = []
  ws.on('message', (d) => { const m = JSON.parse(d); if (pend.has(m.id)) { pend.get(m.id)(m); pend.delete(m.id) } else events.push(m) })
  const call = (op, args = {}) => new Promise((r) => { const i = ++id; pend.set(i, r); ws.send(JSON.stringify({ id: i, op, args })) })
  const ok = async (op, args) => { const r = await call(op, args); assert.equal(r.ok, true, `${op}: ${JSON.stringify(r.error)}`); return r.result }

  await ok('hello', { proto: 1, token: TOKEN })
  assert.ok(Array.isArray((await ok('sessions.list')).items))
  const { sessionId } = await ok('sessions.create', { cwd: home })
  assert.match(sessionId, /^session-/)
  assert.equal((await ok('sessions.rename', { sessionId, title: 'pocket smoke' })).title, 'pocket smoke')
  await ok('follow', { sessionId })
  const deadline = Date.now() + 10_000
  while (!events.some((e) => e.ev === 'follow' && e.frame.type === 'snapshot') && Date.now() < deadline) await new Promise((r) => setTimeout(r, 100))
  assert.ok(events.some((e) => e.ev === 'follow' && e.frame.type === 'snapshot'), 'snapshot frame')
  const cat = await ok('models.catalog')
  assert.ok(cat.groups.length > 0)
  const g = cat.groups[0]; const m = g.models[0]
  assert.equal((await ok('models.select', { sessionId, provider: g.id, model: m.id })).selected.model, m.id)
  assert.equal((await ok('cancel', { sessionId })).accepted, true)
  const listed = await ok('sessions.list')
  assert.ok(listed.items.some((s) => s.sessionId === sessionId))
  assert.ok(events.some((e) => e.ev === 'session' && e.kind === 'added'), 'session added event')
  ws.close()
})

test('real dsh: permissions + presets ops', { skip: !hasDsh, timeout: 240_000 }, async (t) => {
  const { bootDsh, pocketClient, PLUGIN_DIR } = await import('./helpers.js')
  const port = 19000 + Math.floor(Math.random() * 500)
  await bootDsh(t, { plugins: [PLUGIN_DIR], patch: `- id: dsh-pocket\n  config:\n    port: ${port}\n    token: ${TOKEN}\n` })
  const c = await pocketClient(`ws://127.0.0.1:${port}`)
  const h = await c.ok('hello', { token: TOKEN })
  assert.ok(h.ops.includes('permissions.set') && h.ops.includes('presets.list'), h.ops.join())
  const cat = await c.ok('permissions.catalog')
  assert.ok(cat.options.length > 0)
  for (const option of cat.options) {
    assert.equal(typeof option.value, 'string')
    assert.equal(typeof option.name, 'string')
    if ('description' in option) assert.equal(typeof option.description, 'string')
  }
  assert.ok(cat.defaultOptions.some((option) => option.value === cat.defaultPreset))
  assert.ok(cat.defaultOptions.every((option) => option.value !== 'auto' && option.value !== 'custom'))
  const { sessionId } = await c.ok('sessions.create', {})
  const other = cat.defaultOptions.find((option) => option.value !== cat.defaultPreset)?.value ?? cat.defaultPreset
  const set = await c.ok('permissions.set', { sessionId, name: other })
  assert.equal(set.current, other)
  assert.equal((await c.ok('permissions.get', { sessionId })).current, other)
  const roster = await c.ok('presets.list')
  assert.ok(roster, JSON.stringify(roster))
  c.close()
})

test('real dsh: file and session reference contracts', { skip: !hasDsh, timeout: 240_000 }, async (t) => {
  const { bootDsh, pocketClient, PLUGIN_DIR } = await import('./helpers.js')
  const port = 19500 + Math.floor(Math.random() * 500)
  const patch = `- id: dsh-pocket\n  config:\n    host: 127.0.0.1\n    port: ${port}\n    token: ${TOKEN}\n`
  const firstHost = await bootDsh(t, { plugins: [PLUGIN_DIR], patch })
  const { home } = firstHost
  writeFileSync(join(home, 'reference-contract.txt'), 'reference contract fixture\n')
  const c = await pocketClient(`ws://127.0.0.1:${port}`)
  t.after(() => c.close())
  await c.ok('hello', { token: TOKEN })
  const source = await c.ok('sessions.create', { cwd: home })
  const target = await c.ok('sessions.create', { cwd: home })
  const targetFollow = await c.ok('follow', { sessionId: target.sessionId })
  await c.next(e => e.ev === 'follow' && e.subId === targetFollow.subId && e.frame.type === 'snapshot')
  await c.ok('sessions.rename', { sessionId: target.sessionId, title: 'Reference contract target' })
  const sourceFollow = await c.ok('follow', { sessionId: source.sessionId })
  await c.next(e => e.ev === 'follow' && e.subId === sourceFollow.subId && e.frame.type === 'snapshot')

  const files = await c.ok('references.files', { sessionId: source.sessionId, query: 'reference-contract' })
  assert.ok(files.items.some(item => item.path === 'reference-contract.txt' && item.kind === 'file'))
  const sessions = await c.ok('references.sessions', { sessionId: source.sessionId, query: 'Reference contract target' })
  const candidate = sessions.items.find(item => item.sessionId === target.sessionId)
  assert.ok(candidate, 'real resolver returns the renamed target')
  assert.equal(candidate.title, 'Reference contract target')
  assert.equal(candidate.kind, 'session')
  const encodedId = Buffer.from(JSON.stringify(target.sessionId)).toString('base64url')
  assert.equal(candidate.mention, `@[Reference contract target](dsh-session:${encodedId})`)
  assert.ok(!sessions.items.some(item => item.sessionId === source.sessionId), 'self is excluded')

  c.close()
  await firstHost.stop()
  const restarted = await bootDsh(t, { plugins: [], patch, home })
  t.after(async () => { await restarted.stop(); rmSync(home, { recursive: true, force: true }) })
  const cold = await pocketClient(`ws://127.0.0.1:${port}`)
  t.after(() => cold.close())
  await cold.ok('hello', { token: TOKEN })
  // A history snapshot alone does not activate an Agent after a Host restart.
  const coldFollow = await cold.ok('follow', { sessionId: source.sessionId })
  await cold.next(e => e.ev === 'follow' && e.subId === coldFollow.subId && e.frame.type === 'snapshot')
  const [resumedFiles, resumedSessions] = await Promise.all([
    cold.ok('references.files', { sessionId: source.sessionId, query: 'reference-contract' }),
    cold.ok('references.sessions', { sessionId: source.sessionId, query: target.sessionId }),
  ])
  assert.ok(resumedFiles.items.some(item => item.path === 'reference-contract.txt'))
  const resumedTarget = resumedSessions.items.find(item => item.sessionId === target.sessionId)
  assert.ok(resumedTarget, 'a cold session remains referenceable before any prompt')
  // Discovery may fall back to the id before the cold title projection is cached.
  assert.ok(resumedTarget.mention.endsWith(`](dsh-session:${encodedId})`), 'canonical identity survives restart')
  const missing = await cold.call('references.sessions', { sessionId: 'session-missing-reference-contract', query: '' })
  assert.equal(missing.ok, false)
  assert.equal(missing.error.code, 'session/not-found')
})
