// LOCAL ONLY real-model end-to-end: needs DEEPSEEK_API_KEY in the environment (never committed / never in CI).
// Streams a real prompt through the pocket plugin, then lets the model trigger a real bash approval twice:
// A) phone answers first, B) web UI answers first. Writes follow frames to $E2E_FRAMES (for the Kotlin reducer test).
import { writeFileSync, mkdtempSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { bootDsh, pocketClient, webUiClient, PLUGIN_DIR } from './helpers.js'

if (!process.env.DEEPSEEK_API_KEY) { console.log('SKIP: DEEPSEEK_API_KEY not set'); process.exit(0) }
const TOKEN = 'real-model-token-0123456789'
const PORT = 19900 + Math.floor(Math.random() * 90)
const cleanups = []
const log = (...a) => console.log('[e2e]', ...a)
const until = async (f, ms, what) => { const t = Date.now(); while (Date.now() - t < ms) { const v = f(); if (v) return v; await new Promise((r) => setTimeout(r, 200)) } throw new Error('timeout: ' + what) }
try {
  const { webUrl } = await bootDsh({ after: (f) => cleanups.push(f) }, { plugins: [PLUGIN_DIR], patch: `- id: dsh-pocket\n  config:\n    port: ${PORT}\n    token: ${TOKEN}\n` })
  const phone = await pocketClient(`ws://127.0.0.1:${PORT}`)
  const frames = []; const approvals = []; const closed = []
  phone.ws.on('message', (d) => { const m = JSON.parse(d); if (m.ev === 'follow') frames.push(m.frame); if (m.ev === 'approval') approvals.push(m); if (m.ev === 'approval.closed') closed.push(m) })
  await phone.ok('hello', { token: TOKEN })
  const web = await webUiClient(webUrl)
  const cwd = mkdtempSync(join(tmpdir(), 'pocket-ws-'))
  const { sessionId } = await phone.ok('sessions.create', { cwd })
  const cat = await phone.ok('models.catalog')
  const groups = cat.groups ?? cat.providers ?? []
  log('catalog groups:', groups.length)
  await phone.ok('follow', { sessionId })
  const turnEnds = () => frames.filter((f) => f.type === 'event' && f.event.type === 'turn/end').length

  // 1) streaming
  const t0 = Date.now()
  await phone.ok('prompt', { sessionId, mode: 'queue', text: '用一句中文介绍你自己，不要调用任何工具。' })
  await until(() => turnEnds() >= 1, 120_000, 'turn 1 end')
  const deltas = frames.filter((f) => f.type === 'assistant-stream' && f.frame.type === 'chunk' && f.frame.chunk?.type === 'text-delta').length
  const reasoning = frames.filter((f) => f.type === 'assistant-stream' && f.frame.type === 'chunk' && f.frame.chunk?.type === 'reasoning-delta').length
  const msg = frames.find((f) => f.type === 'event' && f.event.type === 'assistant/message')
  const text = (msg?.event.data.message.content ?? []).filter((b) => b.type === 'text').map((b) => b.text).join('')
  log(`turn1: ${deltas} text-delta, ${reasoning} reasoning-delta chunks, ${Date.now() - t0} ms; reply: ${text.slice(0, 60)}`)
  if (deltas < 2) throw new Error('expected streamed text deltas')

  const ask = (n) => `请调用 bash 工具执行命令 \`touch /tmp/pocket-e2e-${n}-${PORT}\`。这个路径在工作区之外，请直接以提权方式（require escalated）请求执行，不要换成别的路径或别的工具。执行后回复"完成"。`
  // 2A) phone answers first
  await phone.ok('prompt', { sessionId, mode: 'queue', text: ask('a') })
  const a = await until(() => approvals[0], 180_000, 'approval A')
  const webA = await web.next((v) => v.type === 'waterfall' && v.event === 'approval/request', 30_000)
  log(`approval A: tool=${a.toolName} reason=${a.reason ?? ''}`)
  await phone.ok('approval.answer', { approvalId: a.approvalId, outcome: 'allowed-once' })
  await web.next((v) => v.type === 'cancel' && v.eventId === webA.eventId, 15_000)
  const ca = await until(() => closed.find((c) => c.approvalId === a.approvalId), 15_000, 'closed A')
  log(`A closed by=${ca.by} outcome=${ca.outcome}; web UI got cancel`)
  await until(() => turnEnds() >= 2, 180_000, 'turn 2 end')
  const resA = frames.filter((f) => f.type === 'event' && f.event.type === 'tool/result').at(-1)
  log('A tool/result error=', JSON.stringify(resA?.event.data.error ?? null))

  // 2B) web answers first
  await phone.ok('prompt', { sessionId, mode: 'queue', text: ask('b') })
  const b = await until(() => approvals.find((x) => x.approvalId !== a.approvalId), 180_000, 'approval B')
  const webB = await web.next((v) => v.type === 'waterfall' && v.event === 'approval/request', 30_000)
  await web.answer(webB.eventId, 'rejected')
  const cb = await until(() => closed.find((c) => c.approvalId === b.approvalId), 15_000, 'closed B')
  const late = await phone.call('approval.answer', { approvalId: b.approvalId, outcome: 'allowed-once' })
  log(`B closed by=${cb.by} outcome=${cb.outcome}; late phone answer ok=${late.ok} ${late.error?.code ?? ''}`)
  await until(() => turnEnds() >= 3, 180_000, 'turn 3 end')

  if (process.env.E2E_FRAMES) writeFileSync(process.env.E2E_FRAMES, JSON.stringify(frames))
  log(`frames captured: ${frames.length}`)
  phone.close(); web.close()
  log('PASS')
} catch (e) { console.error('[e2e] FAIL', e.message); process.exitCode = 1 } finally { for (const f of cleanups) f() }
process.exit(process.exitCode ?? 0)
