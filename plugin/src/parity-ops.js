/**
 * Remaining desktop-parity ops: search, workspace archive/pin, message feedback,
 * @-references, attachments, file upload, user-questions answer.
 */
const str = (v) => typeof v === 'string' && v.length > 0
const bad = (m) => Object.assign(new Error(m), { code: 'bad-request' })

function agentOf(agents, sessionId) {
  if (!str(sessionId)) throw bad('sessionId required')
  const agent = agents.get(sessionId)
  if (!agent) throw Object.assign(new Error('session has no live agent'), { code: 'session/not-found' })
  return agent
}

/** Reference discovery must resume persisted sessions, not only inspect the live Agent map. */
async function resolveReferenceAgent(sessionController, sessionId, signal) {
  if (!str(sessionId)) throw bad('sessionId required')
  signal?.throwIfAborted()
  const result = await sessionController.resolveAgent(sessionId)
  if ('error' in result) throw result.error
  signal?.throwIfAborted()
  return result.agent
}

/** `sessions.search` over sessionController.search. */
export function searchOps(sc) {
  return {
    'sessions.search': async (a, signal) => {
      if (!str(a.query)) throw bad('query required')
      return sc.search({ query: a.query }, signal)
    },
  }
}

/** Workspace mutations use the official controller; state comes from its baseline. */
export function workspaceOps(workspaceController) {
  const c = workspaceController
  const ops = {
    'workspace.state': async (_a, signal) => {
      const ac = new AbortController()
      const abort = () => ac.abort()
      signal?.addEventListener('abort', abort, { once: true })
      if (signal?.aborted) ac.abort()
      try {
        for await (const frame of c.follow(ac.signal)) {
          if (frame.type === 'baseline') return frame.value
        }
        throw Object.assign(new Error('workspace baseline unavailable'), { code: 'unavailable' })
      } finally {
        ac.abort()
        signal?.removeEventListener('abort', abort)
      }
    },
  }
  for (const name of ['create', 'rename', 'delete', 'insertBefore', 'insertSessionBefore',
    'archiveSession', 'unarchiveSession', 'pinSession', 'unpinSession']) {
    if (typeof c?.[name] === 'function') ops[`workspace.${name}`] = (a) => c[name](a)
  }
  return ops
}

/** Only advertise browse when the composed Host picker actually serves it. */
export function directoryOps(controller, directoryPicker) {
  if (directoryPicker.capability().kind !== 'browse') return {}
  const ops = {
    'workspace.directoryList': (a, signal) => controller.list(str(a.path) ? a.path : undefined, signal),
  }
  if (typeof controller.createDirectory === 'function') {
    ops['workspace.directoryCreate'] = async (a) => {
      if (!str(a?.path) || !a.path.trim()) throw bad('path required')
      if (!str(a?.name) || !a.name.trim()) throw bad('name required')
      return controller.createDirectory(a.path, a.name)
    }
  }
  return ops
}

/** Goal refs are the official id/revision CAS fence, never inferred from an objective. */
export function goalOps(goals, agents) {
  const ops = { 'goals.get': (a) => ({ goal: goals.get(agentOf(agents, a.sessionId)) ?? null }) }
  for (const name of ['edit', 'pause', 'resume', 'clear']) {
    ops[`goals.${name}`] = (a) => {
      if (!str(a.ref?.id) || !Number.isSafeInteger(a.ref?.revision) || a.ref.revision < 1) throw bad('goal id/revision required')
      const value = goals[name](agentOf(agents, a.sessionId), a.ref, a.request)
      return name === 'clear' ? { cleared: value } : { goal: value }
    }
  }
  return ops
}

/**
 * Message like/dislike — Host `messageFeedback.list|put|delete`.
 * Pocket names: feedback.get / feedback.set (set deletes when rating is null).
 * The injected Host service returns the business Result directly, without a
 * Typert RemoteResult carrier. Preserve the complete failure for Pocket clients.
 */
export function feedbackOps(messageFeedback) {
  const unwrap = (result) => {
    if (!result.ok) {
      throw Object.assign(new Error(result.error.code), { code: result.error.code, details: result.error })
    }
    return result.value
  }
  return {
    'feedback.get': async (a) => {
      if (!str(a.sessionId)) throw bad('sessionId required')
      return unwrap(await messageFeedback.list({ sessionId: a.sessionId }))
    },
    'feedback.set': async (a) => {
      if (!str(a.sessionId) || !str(a.messageId)) throw bad('sessionId and messageId required')
      if (a.rating == null) {
        return unwrap(await messageFeedback.delete({
          sessionId: a.sessionId,
          messageId: a.messageId,
          ifVersion: a.ifVersion ?? null,
        }))
      }
      if (a.rating !== 'positive' && a.rating !== 'negative') {
        throw bad('rating must be positive, negative, or null')
      }
      return unwrap(await messageFeedback.put({
        sessionId: a.sessionId,
        messageId: a.messageId,
        rating: a.rating,
        ...(a.note !== undefined ? { note: a.note } : {}),
        ...(a.category !== undefined ? { category: a.category } : {}),
        ifVersion: a.ifVersion ?? null,
      }))
    },
  }
}

/** `@` file mention candidates (`ctx.fileReferences`). */
export function fileReferenceOps(fileReferences, sessionController) {
  return {
    'references.files': async (a, signal) => {
      const agent = await resolveReferenceAgent(sessionController, a.sessionId, signal)
      if (!fileReferences?.list) {
        throw Object.assign(new Error('fileReferences unavailable'), { code: 'unavailable' })
      }
      const query = typeof a.query === 'string' ? a.query : ''
      const items = await fileReferences.list(agent, query, signal)
      return { items: items ?? [] }
    },
  }
}

/** `@` session / subagent mention candidates (`ctx.sessionReferenceResolver`). */
export function sessionReferenceOps(sessionReferenceResolver, sessionController, sessionQuery) {
  return {
    'references.sessions': async (a, signal) => {
      const agent = await resolveReferenceAgent(sessionController, a.sessionId, signal)
      if (!sessionReferenceResolver?.remoteExportCandidates) {
        throw Object.assign(new Error('sessionReferenceResolver unavailable'), { code: 'unavailable' })
      }
      const query = typeof a.query === 'string' ? a.query : ''
      // `candidates` is the Remote name, not the Host service method.
      const candidates = await sessionReferenceResolver.remoteExportCandidates(agent, query, signal)
      if (candidates.length === 0) return { items: [] }
      const records = await sessionQuery.listSessions(signal)
      const children = new Set(records
        .filter(({ header }) => header.origin === 'subagent' && header.parentSession === agent.id)
        .map(({ header }) => header.id))
      return {
        items: candidates.map(candidate => ({
          ...candidate,
          title: candidate.displayTitle ?? candidate.label,
          kind: children.has(candidate.sessionId) ? 'subagent' : 'session',
        })),
      }
    },
  }
}

/** Load image bytes for a session attachment (transcript bubbles). */
export function attachmentOps(sc) {
  return {
    'session.attachment': async (a, signal) => {
      if (!str(a.sessionId) || !str(a.attachmentId)) throw bad('sessionId and attachmentId required')
      return sc.attachment({ sessionId: a.sessionId, attachmentId: a.attachmentId }, signal)
    },
  }
}

/** Serve Host-retained turn summaries and comparisons; only publish supported methods. */
export function workspaceChangesOps(workspaceChanges) {
  const ops = {}
  if (typeof workspaceChanges?.summary === 'function') {
    ops['workspaceChanges.summary'] = (a) => {
      if (!str(a.sessionId) || !Number.isSafeInteger(a.seq)) throw bad('sessionId and seq required')
      return { summary: workspaceChanges.summary(a.sessionId, a.seq) ?? null }
    }
  }
  if (typeof workspaceChanges?.diff === 'function') {
    ops['workspaceChanges.diff'] = async (a, signal) => {
      if (!str(a.sessionId) || !Number.isSafeInteger(a.seq)) throw bad('sessionId and seq required')
      if (!Number.isSafeInteger(a.index) || a.index < 0) throw bad('non-negative safe index required')
      return { diff: await workspaceChanges.diff(a.sessionId, a.seq, a.index, signal) ?? null }
    }
  }
  return ops
}

/**
 * Stage a non-image file for the next prompt via Host `fileUploads.upload`.
 * Returns `{receiptId, file}` for `prompt` content `{type:'file', receiptId}`.
 */
export function uploadOps(fileUploads, agents) {
  return {
    'attachments.upload': async (a, signal) => {
      if (!str(a.sessionId) || !str(a.data)) throw bad('sessionId and data required')
      const agent = agentOf(agents, a.sessionId)
      if (!fileUploads?.upload) {
        throw Object.assign(new Error('fileUploads unavailable'), { code: 'unavailable' })
      }
      return fileUploads.upload(agent, {
        data: a.data,
        ...(str(a.name) ? { name: a.name } : {}),
      }, signal)
    },
  }
}

/**
 * Answer an open / continued user-question.
 * - If a phone waterfall offer is pending for `callId`, settle it (open ask).
 * - Else forward to Host `userQuestions.answer` (continued projection).
 */
export function userQuestionOps(userQuestions, agents, { settleOffer } = {}) {
  return {
    'userQuestions.answer': (a) => {
      if (!str(a.sessionId) || !str(a.callId)) throw bad('sessionId and callId required')
      if (!a.answers || !Array.isArray(a.answers.answers ?? a.answers)) {
        throw bad('answers required')
      }
      const answer = Array.isArray(a.answers.answers) ? a.answers : { answers: a.answers }
      if (typeof settleOffer === 'function') {
        const settled = settleOffer(a.callId, answer)
        if (settled) return { accepted: true, via: 'waterfall' }
      }
      const agent = agentOf(agents, a.sessionId)
      if (!userQuestions?.answer) {
        throw Object.assign(new Error('userQuestions unavailable'), { code: 'unavailable' })
      }
      const ok = userQuestions.answer(agent, a.callId, answer)
      return { accepted: Boolean(ok), via: 'host' }
    },
  }
}
