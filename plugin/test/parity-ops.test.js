import { test } from 'node:test'
import assert from 'node:assert/strict'
import {
  searchOps, workspaceOps, feedbackOps, fileReferenceOps, sessionReferenceOps,
  attachmentOps, workspaceChangesOps, uploadOps, userQuestionOps, goalOps, directoryOps,
} from '../src/parity-ops.js'
import { createUserQuestionHook } from '../src/user-question-hook.js'

test('sessions.search forwards query to sessionController.search', async () => {
  const calls = []
  const ops = searchOps({
    search: async (req, signal) => {
      calls.push({ req, aborted: signal?.aborted })
      return { items: [{ sessionId: 's1', snippet: 'hi' }], hasMore: false }
    },
  })
  const r = await ops['sessions.search']({ query: 'needle' }, AbortSignal.timeout(1000))
  assert.equal(calls[0].req.query, 'needle')
  assert.equal(r.items[0].sessionId, 's1')
  await assert.rejects(() => ops['sessions.search']({}), /query required/)
})

test('native and unknown picker capabilities do not advertise directory browsing', () => {
  let invoked = false
  const controller = { list: () => { invoked = true; throw new Error('native picker has no browse capability') } }
  for (const kind of ['native', 'external-picker']) {
    const ops = directoryOps(controller, { capability: () => ({ kind }) })
    assert.equal(Object.hasOwn(ops, 'workspace.directoryList'), false)
    assert.deepEqual(Object.keys(ops), [])
  }
  assert.equal(invoked, false)
})

test('browse capability exposes the official controller and preserves its cancellation and failures', async () => {
  const signal = new AbortController().signal
  const listing = { path: '/workspace', home: '/home', crumbs: [], entries: [], truncated: false }
  const refused = Object.assign(new Error('cannot list directory'), { code: 'directory-picker/unreadable' })
  const ops = directoryOps({
    async list(path, callerSignal) {
      assert.equal(callerSignal, signal)
      if (path === '/denied') throw refused
      assert.equal(path, undefined)
      return listing
    },
  }, { capability: () => ({ kind: 'browse' }) })
  assert.equal(await ops['workspace.directoryList']({}, signal), listing)
  await assert.rejects(ops['workspace.directoryList']({ path: '/denied' }, signal), (error) => error === refused)
})

test('workspace baseline retains entity identity/order and closes the source after reading', async () => {
  const baseline = {
    items: [{ workspaceId: 'w1', path: '/project', title: 'Project', sessionIds: ['fork', 'root'] }],
    archivedSessionIds: ['old'], pinnedSessionIds: ['root'],
  }
  let closed = false
  let signal
  const ops = workspaceOps({
    async *follow(s) {
      signal = s
      try { yield { type: 'baseline', value: baseline } }
      finally { closed = true }
    },
  })
  assert.deepEqual(await ops['workspace.state']({}, AbortSignal.timeout(1000)), baseline)
  assert.equal(closed, true)
  assert.equal(signal.aborted, true)
  assert.equal('workspace.create' in ops, false)
})

test('workspace archive preserves admission refusal and explicit stop consent', async () => {
  let archived = false
  const activity = [{ kind: 'job', id: 'bash-1' }]
  const refusal = Object.assign(new Error('active work'), {
    code: 'workspace/session-active', details: { sessionId: 's1', activity },
  })
  const ops = workspaceOps({
    archiveSession: async (request) => {
      if (request.stopActivity !== true) throw refusal
      archived = true
      return { archivedSessionIds: [request.sessionId] }
    },
  })
  await assert.rejects(ops['workspace.archiveSession']({ sessionId: 's1' }), (error) => error === refusal)
  assert.equal(archived, false)
  assert.deepEqual(await ops['workspace.archiveSession']({ sessionId: 's1', stopActivity: true }), { archivedSessionIds: ['s1'] })
  assert.equal(archived, true)
})

test('goal mutation requires a real revision fence and preserves stale-revision refusal', () => {
  const conflict = Object.assign(new Error('goal changed'), { code: 'goal/conflict' })
  let writes = 0
  const ops = goalOps({
    get: () => undefined,
    pause: (_agent, ref) => {
      if (ref.revision !== 2) throw conflict
      writes++
      return { ...ref, revision: 3, phase: 'paused' }
    },
  }, { get: () => ({ id: 's1' }) })
  assert.deepEqual(ops['goals.get']({ sessionId: 's1' }), { goal: null })
  assert.throws(() => ops['goals.pause']({ sessionId: 's1', ref: { id: 'g1' } }), /id\/revision required/)
  assert.throws(() => ops['goals.pause']({ sessionId: 's1', ref: { id: 'g1', revision: 1 } }), (error) => error === conflict)
  assert.equal(writes, 0)
  assert.equal(ops['goals.pause']({ sessionId: 's1', ref: { id: 'g1', revision: 2 } }).goal.phase, 'paused')
  assert.equal(writes, 1)
})

test('feedback.get/set map to messageFeedback list/put/delete', async () => {
  const calls = []
  const ops = feedbackOps({
    list: async (req) => { calls.push(['list', req]); return { ok: true, value: { items: [{ messageId: 'm1', rating: 'positive', version: 'v1' }] } } },
    put: async (req) => { calls.push(['put', req]); return { ok: true, value: { messageId: req.messageId, rating: req.rating, version: 'v2' } } },
    delete: async (req) => { calls.push(['delete', req]); return { ok: true, value: { absent: true } } },
  })
  const listed = await ops['feedback.get']({ sessionId: 's1' })
  assert.equal(listed.items[0].rating, 'positive')
  const put = await ops['feedback.set']({ sessionId: 's1', messageId: 'm1', rating: 'negative', category: 'task-result', ifVersion: 'v1' })
  assert.equal(put.rating, 'negative')
  const cleared = await ops['feedback.set']({ sessionId: 's1', messageId: 'm1', rating: null, ifVersion: 'v2' })
  assert.equal(cleared.absent, true)
  assert.equal(calls[1][0], 'put')
  assert.equal(calls[2][0], 'delete')
})

test('references.files / references.sessions forward to Host services', async () => {
  const agents = { get: (id) => id === 's1' ? { id: 's1' } : undefined }
  const fileOps = fileReferenceOps({
    list: async (agent, query) => [{ path: `${query}.ts`, kind: 'file', agent: agent.id }],
  }, agents)
  const sessionOps = sessionReferenceOps({
    candidates: async (_agent, query) => [{ mention: `@${query}`, title: query }],
  }, agents)
  const files = await fileOps['references.files']({ sessionId: 's1', query: 'src/a' }, AbortSignal.timeout(500))
  assert.equal(files.items[0].path, 'src/a.ts')
  const sessions = await sessionOps['references.sessions']({ sessionId: 's1', query: 'chat' }, AbortSignal.timeout(500))
  assert.equal(sessions.items[0].mention, '@chat')
})

test('workspaceChanges.summary forwards Host summary', () => {
  const ops = workspaceChangesOps({
    summary: (sessionId, seq) => ({ turn: 1, total: 2, files: [{ path: 'a.ts', display: 'a.ts' }], sessionId, seq }),
  })
  const r = ops['workspaceChanges.summary']({ sessionId: 's1', seq: 42 })
  assert.equal(r.summary.total, 2)
  assert.equal(r.summary.seq, 42)
  assert.throws(() => ops['workspaceChanges.summary']({ sessionId: 's1' }), /seq required/)
})

test('session.attachment and attachments.upload forward', async () => {
  const att = attachmentOps({
    attachment: async (req) => ({ attachment: { attachmentId: req.attachmentId }, data: 'QQ==' }),
  })
  const r = await att['session.attachment']({ sessionId: 's1', attachmentId: 'a1' })
  assert.equal(r.data, 'QQ==')

  const agents = { get: (id) => id === 's1' ? { id: 's1' } : undefined }
  const up = uploadOps({
    upload: async (agent, req) => ({ receiptId: 'r1', file: { attachmentId: 'f1', name: req.name, bytes: 4 }, agent: agent.id }),
  }, agents)
  const u = await up['attachments.upload']({ sessionId: 's1', data: 'AAAA', name: 'note.bin' }, AbortSignal.timeout(500))
  assert.equal(u.receiptId, 'r1')
  assert.equal(u.file.name, 'note.bin')
})

test('userQuestions.answer prefers waterfall settle then Host', () => {
  const hostCalls = []
  const agents = { get: (id) => id === 's1' ? { id: 's1' } : undefined }
  const settled = []
  const ops = userQuestionOps({
    answer: (agent, callId, answer) => { hostCalls.push({ agent, callId, answer }); return true },
  }, agents, {
    settleOffer: (callId, answer) => {
      if (callId === 'open1') { settled.push(answer); return true }
      return false
    },
  })
  const viaWaterfall = ops['userQuestions.answer']({
    sessionId: 's1', callId: 'open1', answers: { answers: [{ id: 'q1', selected: ['A'] }] },
  })
  assert.equal(viaWaterfall.via, 'waterfall')
  assert.equal(hostCalls.length, 0)
  const viaHost = ops['userQuestions.answer']({
    sessionId: 's1', callId: 'cont1', answers: [{ id: 'q1', selected: ['B'] }],
  })
  assert.equal(viaHost.via, 'host')
  assert.equal(hostCalls[0].callId, 'cont1')
})

test('user-question hook races phone against chain', async () => {
  let resolveOffer
  const server = {
    hasDevices: () => true,
    offerUserQuestion: () => {
      const answer = new Promise((r) => { resolveOffer = r })
      return {
        answer,
        close: () => {},
      }
    },
  }
  const hook = createUserQuestionHook(server)
  const req = { questions: [{ id: 'q1', question: 'Go?' }], agent: { id: 's1' } }
  const pending = hook(req, async () => ({ answers: [{ id: 'q1', selected: ['web'] }] }))
  resolveOffer({ answer: { answers: [{ id: 'q1', selected: ['phone'] }] }, by: 'device:d1' })
  const result = await pending
  assert.equal(result.answers[0].selected[0], 'phone')
})
