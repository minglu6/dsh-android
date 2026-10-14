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

test('commands catalog is scoped to the requested live agent', () => {
  const ops = commandOps({
    list: (agent) => agent.id === 'standard'
      ? [{ name: 'goal', definitionId: '@deepseek-ai/dsh-command-goal', description: 'Objective', input: { hint: 'text' } }]
      : [],
  }, { get: (id) => ['standard', 'minimal'].includes(id) ? { id } : undefined })
  assert.equal(ops['commands.list']({ sessionId: 'minimal' }).commands.length, 0)
  assert.equal(ops['commands.list']({ sessionId: 'standard' }).commands[0].input.hint, 'text')
  assert.throws(() => ops['commands.list']({ sessionId: 'gone' }), /no live agent/)
})

test('files.list builds scope from session header cwd', async () => {
  const ops = filesOps({
    list: async (scope, path) => ({ path, scope, entries: [{ name: 'a.ts', type: 'file' }] }),
    read: async () => ({ text: 'hi' }),
    readBytes: async () => ({ absolutePath: '/tmp/ws/a.png', version: 'v1', bytes: 3, offset: 0, eof: true, data: Uint8Array.from([1, 2, 3]) }),
  }, { get: (id) => id === 's1' ? { header: { cwd: '/tmp/ws' } } : undefined })
  const listed = await ops['files.list']({ sessionId: 's1', path: 'src' }, AbortSignal.timeout(1000))
  assert.equal(listed.scope.workspaceRoot, '/tmp/ws')
  assert.equal(listed.path, 'src')
  assert.equal(listed.entries[0].name, 'a.ts')
})

test('files.readBytes base64-encodes host Uint8Array for JSON transport', async () => {
  const ops = filesOps({
    list: async () => ({ path: '.', entries: [] }),
    read: async () => ({ text: '' }),
    readBytes: async (scope, path) => ({
      absolutePath: `${scope.workspaceRoot}/${path}`,
      version: 'v1',
      bytes: 4,
      offset: 0,
      eof: true,
      data: Uint8Array.from([0x89, 0x50, 0x4e, 0x47]),
    }),
  }, { get: (id) => id === 's1' ? { header: { cwd: '/tmp/ws' } } : undefined })
  const r = await ops['files.readBytes']({ sessionId: 's1', path: 'a.png' }, AbortSignal.timeout(1000))
  assert.equal(r.encoding, 'base64')
  assert.equal(r.eof, true)
  assert.equal(Buffer.from(r.data, 'base64').equals(Buffer.from([0x89, 0x50, 0x4e, 0x47])), true)
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

test('jobs.list empty rows frame is an empty roster, not unavailable', async () => {
  const ops = jobsOps({
    list: async function* () { yield { type: 'rows', jobs: [] } },
  })
  const r = await ops['jobs.list']({ sessionId: 's1' })
  assert.deepEqual(r.jobs, [])
})

test('jobs.list abort and timeout throw instead of an empty roster', async () => {
  const hang = async function* (_req, signal) {
    yield { type: 'heartbeat' }
    await new Promise((_, reject) => {
      const fail = () => reject(Object.assign(new Error('aborted'), { name: 'AbortError' }))
      if (signal.aborted) fail()
      else signal.addEventListener('abort', fail, { once: true })
    })
  }
  const ops = jobsOps({ list: hang })
  await assert.rejects(
    ops['jobs.list']({ sessionId: 's1' }, AbortSignal.abort()),
    (err) => {
      assert.equal(err.code, 'jobs/unavailable')
      assert.match(err.message, /timed out or was aborted/)
      return true
    },
  )
  const ac = new AbortController()
  const timer = setTimeout(() => ac.abort(), 20)
  try {
    await assert.rejects(
      ops['jobs.list']({ sessionId: 's1' }, ac.signal),
      (err) => err.code === 'jobs/unavailable',
    )
  } finally {
    clearTimeout(timer)
  }
})

test('jobs.list throws when the Host stream ends without a rows frame', async () => {
  const ops = jobsOps({
    list: async function* () { yield { type: 'heartbeat' } },
  })
  await assert.rejects(
    ops['jobs.list']({ sessionId: 's1' }),
    (err) => err.code === 'jobs/unavailable' && /without a rows frame/.test(err.message),
  )
})

test('job output subscription forwards retained offsets and cancels its source on unfollow', async () => {
  let disposed
  const closed = new Promise((resolve) => { disposed = resolve })
  let observed
  const opened = new Promise((resolve) => { observed = resolve })
  const ops = jobsOps({
    async *follow(request, signal) {
      try {
        yield { type: 'opened', job: { id: request.jobId }, from: request.from }
        await new Promise((resolve) => {
          signal.addEventListener('abort', resolve, { once: true })
          observed()
        })
      } finally { disposed() }
    },
  })
  const pushed = []
  const client = { nextSub: 1, subs: new Map(), push: (frame) => pushed.push(frame) }
  const receipt = ops['jobs.follow']({ sessionId: 's1', jobId: 'bash-1', from: 24 }, null, client)
  await opened
  assert.equal(pushed[0].frame.from, 24)
  client.subs.get(receipt.subId).abort()
  await closed
  await new Promise((resolve) => setImmediate(resolve))
  assert.equal(client.subs.has(receipt.subId), false)
  assert.equal(pushed.some((frame) => frame.ev === 'job.end'), false)
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
  assert.equal((await ops['terminal.list']({ sessionId: 's1' })).platform, process.platform)
  const created = await ops['terminal.create']({ sessionId: 's1', cols: 40, rows: 12 }, AbortSignal.timeout(1000))
  assert.equal(created.terminal.state, 'running')
  assert.equal(created.platform, process.platform)

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

test('terminal roster keeps owner scope and retained metadata without requiring a live agent', () => {
  const retained = { id: 'same-id', title: 'Retained shell', cwd: '/prior-workspace', state: 'exited' }
  const queried = []
  const ops = terminalOps({
    list: (sessionId) => {
      queried.push(sessionId)
      return sessionId === 'prior' ? [retained] : []
    },
  }, { get: () => undefined })
  assert.deepEqual(ops['terminal.list']({ sessionId: 'current' }).terminals, [])
  assert.deepEqual(ops['terminal.list']({ sessionId: 'prior' }), {
    terminals: [retained], platform: process.platform,
  })
  assert.deepEqual(queried, ['current', 'prior'])
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
