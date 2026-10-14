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
 */
export function feedbackOps(messageFeedback) {
  return {
    'feedback.get': async (a) => {
      if (!str(a.sessionId)) throw bad('sessionId required')
      const r = await messageFeedback.list({ sessionId: a.sessionId })
      // RemoteResult shape `{ok,value}` or plain `{items}`.
      if (r && typeof r === 'object' && 'ok' in r) {
        if (!r.ok) {
          const err = r.error ?? {}
          throw Object.assign(new Error(err.message ?? 'feedback list failed'), { code: err.code ?? 'error' })
        }
        return r.value
      }
      return r
    },
    'feedback.set': async (a) => {
      if (!str(a.sessionId) || !str(a.messageId)) throw bad('sessionId and messageId required')
      if (a.rating == null) {
        const r = await messageFeedback.delete({
          sessionId: a.sessionId,
          messageId: a.messageId,
          ifVersion: a.ifVersion ?? null,
        })
        if (r && typeof r === 'object' && 'ok' in r && !r.ok) {
          const err = r.error ?? {}
          throw Object.assign(new Error(err.message ?? 'feedback delete failed'), { code: err.code ?? 'error' })
        }
        return r?.value ?? r ?? { absent: true }
      }
      if (a.rating !== 'positive' && a.rating !== 'negative') {
        throw bad('rating must be positive, negative, or null')
      }
      const r = await messageFeedback.put({
        sessionId: a.sessionId,
        messageId: a.messageId,
        rating: a.rating,
        ...(a.note !== undefined ? { note: a.note } : {}),
        ...(a.category !== undefined ? { category: a.category } : {}),
        ifVersion: a.ifVersion ?? null,
      })
      if (r && typeof r === 'object' && 'ok' in r) {
        if (!r.ok) {
          const err = r.error ?? {}
          throw Object.assign(new Error(err.message ?? 'feedback put failed'), { code: err.code ?? 'error' })
        }
        return r.value
      }
      return r
    },
  }
}

/** `@` file mention candidates (`ctx.fileReferences`). */
export function fileReferenceOps(fileReferences, agents) {
  return {
    'references.files': async (a, signal) => {
      const agent = agentOf(agents, a.sessionId)
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
export function sessionReferenceOps(sessionReferenceResolver, agents) {
  return {
    'references.sessions': async (a, signal) => {
      const agent = agentOf(agents, a.sessionId)
      if (!sessionReferenceResolver?.candidates) {
        throw Object.assign(new Error('sessionReferenceResolver unavailable'), { code: 'unavailable' })
      }
      const query = typeof a.query === 'string' ? a.query : ''
      const items = await sessionReferenceResolver.candidates(agent, query, signal)
      return { items: items ?? [] }
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
