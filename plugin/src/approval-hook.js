const never = new Promise(() => {})

/**
 * Build the `approval/request` waterfall listener.
 *
 * Races the phone answer against the rest of the chain (`next()`: the web UI's
 * forwarded Remote event and any other answerer). First answer wins.
 *
 * When a phone wins we must also clear the web UI's pending card. The Gateway
 * withdraws a forwarded waterfall (sends `{type:'cancel'}` to browsers) only
 * when the request's own `signal` aborts, so before calling `next()` we replace
 * `req.signal` on the shared request object with `AbortSignal.any([orig, ours])`
 * and abort `ours` when the phone wins. ApprovalService keeps its own reference
 * to the original signal, so its cancellation semantics are unchanged.
 * `'unavailable'` from the chain means "no answerer there" and does not win.
 */
export function createApprovalHook(server, { log = () => {} } = {}) {
  return async function onApprovalRequest(req, next) {
    if (!server.hasDevices()) return next()
    const orig = req.signal
    if (orig?.aborted) return next()
    const ours = new AbortController()
    try {
      req.signal = orig ? AbortSignal.any([orig, ours.signal]) : ours.signal
    } catch (error) {
      log('cannot attach withdraw signal; web card may linger:', error)
    }
    const offer = server.offerApproval({
      sessionId: req.agent?.session?.id ?? req.agent?.id,
      toolName: req.toolName ?? '',
      ...(req.callId ? { callId: req.callId } : {}),
      ...(req.reason ? { reason: req.reason } : {}),
      ...(req.displayReason ? { displayReason: req.displayReason } : {}),
    })
    const chain = Promise.resolve().then(next)
    const web = chain.then(
      (outcome) => (outcome === 'unavailable' ? never : { outcome, by: 'chain' }),
      () => never,
    )
    const aborted = orig ? new Promise((r) => {
      if (orig.aborted) r({ outcome: 'cancelled', by: 'signal' })
      orig.addEventListener('abort', () => r({ outcome: 'cancelled', by: 'signal' }), { once: true })
    }) : never
    const winner = await Promise.race([offer.answer, web, aborted])
    if (winner.by.startsWith('device:')) ours.abort(new Error('answered on a paired device'))
    chain.catch(() => {})
    offer.close(winner.outcome, winner.by)
    return winner.outcome
  }
}
