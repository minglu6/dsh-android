import { test } from 'node:test'
import assert from 'node:assert/strict'
import { zipSync, strToU8, unzipSync, strFromU8 } from 'fflate'
import { commandOps, filesOps, jobsOps, exportOps } from '../src/extra-ops.js'

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
