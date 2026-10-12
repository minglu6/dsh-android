import { test } from 'node:test'
import assert from 'node:assert/strict'
import { zipSync, strToU8, unzipSync, strFromU8 } from 'fflate'
import { commandOps, filesOps, jobsOps, exportOps, terminalOps } from '../src/extra-ops.js'

test('commands.execute prefixes slash and returns Host result', async () => {
  const calls = []
  const ops = commandOps({
    execute: async (agent, line, attachments) => {
      calls.push({ agent, line, attachments })
      return { commandId: 'c1', result: { kind: 'success', text: 'ok' } }
    },
  }, { get: (id) => id === 's1' ? { session: {} } : undefined })
  const r = await ops['commands.execute']({ sessionId: 's1', line: 'compact' }, AbortSignal.timeout(1000))
  assert.deepEqual(calls[0].line, '/compact')
  assert.equal(r.result.kind, 'success')
  await assert.rejects(() => ops['commands.execute']({ sessionId: 'missing', line: '/plan' }), /no live agent/)
})

test('files.list builds scope from session header cwd', async () => {
  const ops = filesOps({
    list: async (scope, path) => ({ path, scope, entries: [{ name: 'a.ts', type: 'file' }] }),
    read: async () => ({ text: 'hi' }),
  }, { get: (id) => id === 's1' ? { header: { cwd: '/tmp/ws' } } : undefined })
  const listed = await ops['files.list']({ sessionId: 's1', path: 'src' }, AbortSignal.timeout(1000))
  assert.equal(listed.scope.workspaceRoot, '/tmp/ws')
  assert.equal(listed.path, 'src')
  assert.equal(listed.entries[0].name, 'a.ts')
})

test('jobs.list returns first rows frame then stops', async () => {
  const ops = jobsOps({
    list: async function* () {
      yield { type: 'rows', jobs: [{ id: 'j1', status: 'running' }] }
      yield { type: 'rows', jobs: [{ id: 'j1', status: 'done' }] }
    },
    kill: ({ sessionId, jobId }) => ({ outcome: 'requested', sessionId, jobId }),
  })
  const r = await ops['jobs.list']({ sessionId: 's1' }, AbortSignal.timeout(1000))
  assert.equal(r.jobs.length, 1)
  assert.equal(r.jobs[0].id, 'j1')
  const k = ops['jobs.kill']({ sessionId: 's1', jobId: 'j1' })
  assert.equal(k.outcome, 'requested')
})

test('terminal.create/write/follow pumps frames then unfollow aborts', async () => {
  const frames = [
    { type: 'snapshot', sequence: 1, screen: '$ ', info: { id: 'term1', state: 'running' } },
    { type: 'output', sequence: 2, data: 'hi\n' },
  ]
  const wrote = []
  const ops = terminalOps({
    list: () => [{ id: 'term1', state: 'running' }],
    create: async (_agent, req) => ({ id: req.id, state: 'running', cols: req.cols, rows: req.rows }),
    write: async (_a, id, att, data) => { wrote.push({ id, att, data }) },
    resize: async () => {},
    close: async () => {},
    follow: async function* (_agent, id, attachmentId, signal) {
      for (const f of frames) {
        if (signal.aborted) return
        yield f
        await new Promise((r) => setTimeout(r, 5))
      }
      await new Promise((r) => signal.addEventListener('abort', r))
    },
  }, { get: (id) => id === 's1' ? { session: {} } : undefined })

  assert.equal((await ops['terminal.list']({ sessionId: 's1' })).terminals[0].id, 'term1')
  const created = await ops['terminal.create']({ sessionId: 's1', cols: 40, rows: 12 }, AbortSignal.timeout(1000))
  assert.equal(created.terminal.state, 'running')

  const pushed = []
  const client = {
    nextSub: 1,
    subs: new Map(),
    push: (m) => pushed.push(m),
  }
  const sub = await ops['terminal.follow']({ sessionId: 's1', id: created.terminal.id }, null, client)
  assert.ok(sub.subId.startsWith('t'))
  assert.ok(sub.attachmentId)
  await new Promise((r) => setTimeout(r, 40))
  assert.ok(pushed.some((m) => m.ev === 'terminal' && m.frame?.type === 'snapshot'))
  assert.ok(pushed.some((m) => m.ev === 'terminal' && m.frame?.type === 'output'))

  await ops['terminal.write']({ sessionId: 's1', id: created.terminal.id, attachmentId: sub.attachmentId, data: 'ls\r' })
  assert.equal(wrote[0].data, 'ls\r')

  client.subs.get(sub.subId).abort()
})

test('session.export pages history into a zip of jsonl', async () => {
  const pages = [
    { records: [{ type: 'event', event: { type: 'user/message', seq: 2 } }, { type: 'event', event: { type: 'assistant/message', seq: 3 } }], hasMore: false },
  ]
  const ops = exportOps({
    page: async () => pages.shift(),
  }, { zipSync, strToU8 })
  const r = await ops['session.export']({ sessionId: 'abc' }, AbortSignal.timeout(1000))
  assert.match(r.filename, /^dsh-session-abc\.zip$/)
  assert.equal(r.mediaType, 'application/zip')
  const files = unzipSync(Buffer.from(r.data, 'base64'))
  const text = strFromU8(files['abc.jsonl'])
  assert.match(text, /user\/message/)
  assert.match(text, /assistant\/message/)
})
