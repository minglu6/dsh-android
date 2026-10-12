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

/**
 * Workspace archive / pin / unpin + snapshot of archive/pin sets.
 * Prefers Host `workspaceController` remotes; falls back to `workspaceRegistry`.
 */
export function workspaceOps(workspaceController, workspaceRegistry) {
  const registry = () => {
    const r = workspaceRegistry
    if (!r) throw Object.assign(new Error('workspace registry unavailable'), { code: 'unavailable' })
    return r
  }
  const controller = () => workspaceController
  return {
    'workspace.state': () => {
      const r = registry()
      return {
        archivedSessionIds: [...(r.archivedSessionIds ?? [])],
        pinnedSessionIds: [...(r.pinnedSessionIds ?? [])],
      }
    },
    'workspace.archiveSession': async (a) => {
      if (!str(a.sessionId)) throw bad('sessionId required')
      const c = controller()
      if (c?.archiveSession) {
        return c.archiveSession({
          sessionId: a.sessionId,
          ...(a.stopActivity === true ? { stopActivity: true } : {}),
        })
      }
      await registry().archiveSession(a.sessionId, a.stopActivity === true ? { stopActivity: true } : {})
      return { archivedSessionIds: [...registry().archivedSessionIds] }
    },
    'workspace.unarchiveSession': async (a) => {
      if (!str(a.sessionId)) throw bad('sessionId required')
      const c = controller()
      if (c?.unarchiveSession) return c.unarchiveSession({ sessionId: a.sessionId })
      await registry().unarchiveSession(a.sessionId)
      return { archivedSessionIds: [...registry().archivedSessionIds] }
    },
    'workspace.pinSession': async (a) => {
      if (!str(a.sessionId)) throw bad('sessionId required')
      const c = controller()
      if (c?.pinSession) return c.pinSession({ sessionId: a.sessionId })
      await registry().pinSession(a.sessionId)
      return { pinnedSessionIds: [...registry().pinnedSessionIds] }
    },
    'workspace.unpinSession': async (a) => {
      if (!str(a.sessionId)) throw bad('sessionId required')
      const c = controller()
      if (c?.unpinSession) return c.unpinSession({ sessionId: a.sessionId })
      await registry().unpinSession(a.sessionId)
      return { pinnedSessionIds: [...registry().pinnedSessionIds] }
    },
  }
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
