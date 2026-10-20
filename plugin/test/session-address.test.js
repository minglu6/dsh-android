import { test } from 'node:test'
import assert from 'node:assert/strict'
import { createOps, createPocketServer, sessionAddress } from '../src/server.js'

const child = { sessionId: 'child', parentSessionId: 'parent', subagentMode: 'continuable' }
const address = { kind: 'subagent', parentSessionId: 'parent', childSessionId: 'child', mode: 'continuable' }
const signal = new AbortController().signal

test('root address remains unchanged and child address requires both valid routing fields', () => {
  assert.deepEqual(sessionAddress({ sessionId: 'root' }), { kind: 'session', sessionId: 'root' })
  for (const mode of ['continuable', 'one-shot', 'unknown']) {
    assert.deepEqual(sessionAddress({ ...child, subagentMode: mode }), { ...address, mode })
  }
  for (const args of [
    {}, { sessionId: '' }, { ...child, parentSessionId: '' },
    { ...child, parentSessionId: 'child' }, { ...child, parentSessionId: null },
    { ...child, parentSessionId: undefined }, { ...child, subagentMode: undefined },
    { ...child, subagentMode: 'external' }, { ...child, subagentMode: 'queue' },
  ]) assert.throws(() => sessionAddress(args), { code: 'bad-request' })
})

test('projections is capability gated, preserves official results and validates children before reading', async () => {
  assert.equal(Object.hasOwn(createOps({}), 'sessions.projections'), false)
  const calls = []
  let result = { kind: 'sequenced', asOfSeq: 17, values: { subagentCatalog: [] } }
  const ops = createOps({
    page: async (request, receivedSignal) => {
      assert.equal(receivedSignal, signal)
      calls.push(['page', request])
      if (request.address.parentSessionId !== 'parent') throw Object.assign(new Error('wrong parent'), { code: 'subagent/unauthorized' })
      return { records: [], hasMore: false }
    },
    projections: async (request, receivedSignal) => {
      assert.equal(receivedSignal, signal)
      calls.push(['projections', request])
      return result
    },
  })
  assert.equal(await ops['sessions.projections'](child, signal), result)
  assert.deepEqual(calls, [
    ['page', { address, throughSeq: -1, maxMessages: 1 }],
    ['projections', { sessionId: 'child' }],
  ])
  calls.length = 0
  await assert.rejects(ops['sessions.projections']({ ...child, parentSessionId: 'other' }, signal), { code: 'subagent/unauthorized' })
  assert.equal(calls.length, 1)
  for (result of [null, { kind: 'migration-required', values: {} }]) {
    calls.length = 0
    assert.equal(await ops['sessions.projections']({ sessionId: 'root' }, signal), result)
    assert.deepEqual(calls, [['projections', { sessionId: 'root' }]])
  }
})


test('invalid child controls never fall back to root and missing services fail explicitly', () => {
  const fail = () => assert.fail('invalid child control reached an official controller')
  let service = { prompt: fail, interruptByParent: fail }
  const ops = createOps({ prompt: fail, cancel: fail }, { getSubagents: () => service })
  for (const subagentMode of ['unknown', 'one-shot', 'external']) {
    assert.throws(() => ops.prompt({ ...child, subagentMode, mode: 'queue', text: 'hello' }, signal), { code: 'bad-request' })
    assert.throws(() => ops.cancel({ ...child, subagentMode }), { code: 'bad-request' })
  }
  assert.throws(() => ops.prompt({ ...child, mode: 'queue', files: [{ receiptId: 'file' }] }, signal), {
    code: 'subagent/attachment-invalid', details: { reason: 'SUBAGENT_FILE_UNSUPPORTED' },
  })
  service = undefined
  assert.throws(() => ops.prompt({ ...child, mode: 'queue', text: 'hello' }, signal), { code: 'subagent/control-unavailable' })
  assert.throws(() => ops.cancel(child), { code: 'subagent/control-unavailable' })
})

test('wire follow and reconnect gap paging preserve child address and projection baseline', { timeout: 10000 }, async t => {
  const calls = []
  const entry = seq => ({ event: { seq, type: 'test', time: 0, data: {} } })
  const projection = { asOfSeq: 5, values: { agentTeam: null, subagentCatalog: [] } }
  const server = createPocketServer({
    token: 'address-test',
    sessionController: {
      list: async () => ({ items: [] }),
      projections: async () => ({ kind: 'sequenced', ...projection }),
      async *follow(request, followSignal) {
        calls.push(['follow', request])
        yield { type: 'snapshot', cursor: 5, records: [entry(5)], hasMore: true, projections: projection }
        if (!followSignal.aborted) await new Promise(resolve => followSignal.addEventListener('abort', resolve, { once: true }))
      },
      async page(request) {
        calls.push(['page', request])
        return { records: [entry(2), entry(3), entry(4)], hasMore: true }
      },
    },
  })
  t.after(() => server.close())
  const messages = []
  let resolveFrame
  const frameReady = new Promise(resolve => { resolveFrame = resolve })
  const pipe = server.attachPipe({ send(message) {
    messages.push(message)
    if (message.ev === 'follow') resolveFrame(message.frame)
  }, close() {} })
  t.after(() => pipe.close())
  await pipe.onMessage({ id: 1, op: 'hello', args: { proto: 1, token: 'address-test' } })
  assert.ok(messages.find(message => message.id === 1).result.ops.includes('sessions.projections'))
  await pipe.onMessage({ id: 2, op: 'follow', args: { ...child, subagentMode: 'external' } })
  assert.equal(messages.find(message => message.id === 2).error.code, 'bad-request')
  assert.equal(calls.length, 0)
  await pipe.onMessage({ id: 3, op: 'follow', args: { ...child, afterSeq: 1, maxMessages: 2 } })
  const frame = await frameReady
  assert.equal(frame.type, 'resume')
  assert.deepEqual(frame.records.map(record => record.event.seq), [2, 3, 4, 5])
  assert.deepEqual(frame.projections, projection)
})
