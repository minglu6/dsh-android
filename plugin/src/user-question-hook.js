const never = new Promise(() => {})
const questionError = (message, code) => Object.assign(new Error(message), { name: 'UserQuestionError', code })

/**
 * Keep the mobile offer alongside the desktop waterfall, including reconnects.
 * A timed mobile claim has the Host's remaining duration, never an indefinite hold.
 */
export function createUserQuestionHook(server, { userQuestions } = {}) {
  return async function onUserQuestionRequest(req, next) {
    const sessionId = req.agent?.session?.id ?? req.agent?.id
    if (!sessionId || req.signal?.aborted) return next()
    const original = req.signal
    const withdraw = new AbortController()
    req.signal = original ? AbortSignal.any([original, withdraw.signal]) : withdraw.signal
    let rejectLifecycle
    const lifecycle = new Promise((_, reject) => { rejectLifecycle = reject })
    let claim
    let timer
    let deadline
    let finished = false
    const offer = server.offerUserQuestion({
      sessionId,
      callId: req.wait?.callId,
      questions: req.questions ?? [],
      wait: req.wait ? { callId: req.wait.callId, timed: Boolean(req.wait.timed) } : undefined,
    }, { isActive: () => !finished && !original?.aborted && (deadline === undefined || Date.now() < deadline) })
    const fail = (error) => {
      if (finished) return
      offer.close('aborted', 'host')
      rejectLifecycle(error)
    }
    const aborted = () => fail(original.reason ?? questionError('user-question aborted', 'ASK_ABORTED'))
    original?.addEventListener('abort', aborted, { once: true })
    const releaseClaim = () => {
      clearTimeout(timer)
      deadline = undefined
      claim?.abort()
      claim = undefined
    }
    const updateClaim = () => {
      if (finished || !req.wait?.timed || !userQuestions?.attachWait) return
      if (!server.hasDevices()) { releaseClaim(); return }
      if (claim) return
      const lifetime = new AbortController()
      claim = lifetime
      const signal = original ? AbortSignal.any([original, lifetime.signal]) : lifetime.signal
      void (async () => {
        try {
          for await (const frame of userQuestions.attachWait(req.agent, req.wait.callId, signal)) {
            if (signal.aborted || finished || claim !== lifetime) return
            deadline = Date.now() + frame.remainingMs
            timer = setTimeout(() => fail(questionError('ask_user_question timed out before the user answered', 'ASK_TIMED_OUT')), frame.remainingMs)
          }
          if (!signal.aborted && !finished && claim === lifetime) {
            fail(questionError('the foreground question wait ended', 'ASK_ABORTED'))
          }
        } catch (error) {
          if (!signal.aborted && !finished && claim === lifetime) fail(error)
        }
      })()
    }
    const unwatch = server.onDevicePresence?.(updateClaim)
    updateClaim()
    const chain = Promise.resolve().then(next).then(
      (answer) => {
        if (answer === undefined || answer === 'unavailable') return never
        offer.close('answered', 'chain')
        return { answer, by: 'chain' }
      },
      (error) => {
        // NO_PROVIDER only means the desktop declined; mobile may still answer.
        if (error?.code === 'NO_PROVIDER' && (req.wait?.timed || server.hasDevices())) return never
        offer.close('aborted', 'chain')
        throw error
      },
    )
    try {
      const winner = await Promise.race([offer.answer, chain, lifecycle])
      offer.close('answered', winner.by)
      return winner.answer
    } finally {
      finished = true
      unwatch?.()
      clearTimeout(timer)
      original?.removeEventListener('abort', aborted)
      withdraw.abort(questionError('question answerer withdrawn', 'ASK_ABORTED'))
      offer.close('aborted', 'host')
      // An accepted answer must reach askTimed before its last claim is released.
      if (claim && original && !original.aborted) original.addEventListener('abort', releaseClaim, { once: true })
      else releaseClaim()
    }
  }
}
