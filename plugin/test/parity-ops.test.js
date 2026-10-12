import { test } from 'node:test'
import assert from 'node:assert/strict'
import {
  searchOps, workspaceOps, feedbackOps, fileReferenceOps, sessionReferenceOps,
  attachmentOps, uploadOps, userQuestionOps,
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

test('workspace archive/pin use registry fallback and return id sets', async () => {
  const state = { archivedSessionIds: [], pinnedSessionIds: [] }
  const registry = {
    get archivedSessionIds() { return state.archivedSessionIds },
    get pinnedSessionIds() { return state.pinnedSessionIds },
    async archiveSession(id, opts = {}) {
      if (!state.archivedSessionIds.includes(id)) state.archivedSessionIds = [...state.archivedSessionIds, id]
      state.pinnedSessionIds = state.pinnedSessionIds.filter((x) => x !== id)
      this._lastArchiveOpts = opts
    },
    async unarchiveSession(id) {
      state.archivedSessionIds = state.archivedSessionIds.filter((x) => x !== id)
    },
    async pinSession(id) {
      if (!state.pinnedSessionIds.includes(id)) state.pinnedSessionIds = [id, ...state.pinnedSessionIds]
    },
    async unpinSession(id) {
      state.pinnedSessionIds = state.pinnedSessionIds.filter((x) => x !== id)
    },
  }
  const ops = workspaceOps(null, registry)
  assert.deepEqual(ops['workspace.state'](), { archivedSessionIds: [], pinnedSessionIds: [] })
  await ops['workspace.pinSession']({ sessionId: 'a' })
  assert.deepEqual(ops['workspace.state']().pinnedSessionIds, ['a'])
  const archived = await ops['workspace.archiveSession']({ sessionId: 'a', stopActivity: true })
  assert.deepEqual(archived.archivedSessionIds, ['a'])
  assert.deepEqual(ops['workspace.state']().pinnedSessionIds, [])
  assert.equal(registry._lastArchiveOpts.stopActivity, true)
  await ops['workspace.unarchiveSession']({ sessionId: 'a' })
  assert.deepEqual(ops['workspace.state']().archivedSessionIds, [])
})

test('workspace ops prefer workspaceController when present', async () => {
  const calls = []
  const ops = workspaceOps({
    archiveSession: async (req) => { calls.push(['archive', req]); return { archivedSessionIds: [req.sessionId] } },
    pinSession: async (req) => { calls.push(['pin', req]); return { pinnedSessionIds: [req.sessionId] } },
  }, { archivedSessionIds: [], pinnedSessionIds: [] })
  await ops['workspace.archiveSession']({ sessionId: 's1', stopActivity: true })
  await ops['workspace.pinSession']({ sessionId: 's2' })
  assert.deepEqual(calls[0], ['archive', { sessionId: 's1', stopActivity: true }])
  assert.deepEqual(calls[1], ['pin', { sessionId: 's2' }])
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
