const never = new Promise(() => {})

/**
 * Race phone answers against the web UI waterfall for `user-questions/request`.
 * First answer wins (same pattern as approval-hook.js).
 */
export function createUserQuestionHook(server, { log = () => {} } = {}) {
  return async function onUserQuestionRequest(req, next) {
    if (!server.hasDevices?.()) return next()
    const orig = req.signal
    if (orig?.aborted) return next()
    const ours = new AbortController()
    try {
      req.signal = orig ? AbortSignal.any([orig, ours.signal]) : ours.signal
    } catch (error) {
      log('cannot attach user-question withdraw signal:', error)
    }
    const sessionId = req.agent?.session?.id ?? req.agent?.id
    const callId = req.wait?.callId
    const offer = server.offerUserQuestion({
      sessionId,
      callId,
      questions: req.questions ?? [],
      wait: req.wait ? { callId: req.wait.callId, timed: Boolean(req.wait.timed) } : undefined,
    })
    const chain = Promise.resolve().then(next)
    const web = chain.then(
      (answer) => (answer === undefined || answer === 'unavailable' ? never : { answer, by: 'chain' }),
      () => never,
    )
    const aborted = orig ? new Promise((r) => {
      if (orig.aborted) r({ answer: null, by: 'signal', aborted: true })
      orig.addEventListener('abort', () => r({ answer: null, by: 'signal', aborted: true }), { once: true })
    }) : never
    const winner = await Promise.race([offer.answer, web, aborted])
    if (winner.by?.startsWith?.('device:')) ours.abort(new Error('answered on a paired device'))
    chain.catch(() => {})
    offer.close(winner.aborted ? 'aborted' : 'answered', winner.by)
    if (winner.aborted) throw Object.assign(new Error('user-question aborted'), { code: 'ASK_ABORTED' })
    return winner.answer
  }
}
