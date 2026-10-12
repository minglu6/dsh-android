import { randomInt } from 'node:crypto'

export const PAIRING_TTL_MS = 600_000
export const PAIRING_MAX_ATTEMPTS = 3
export const PAIR_NO_WINDOW = 'pair/no-window'
export const PAIR_EXPIRED = 'pair/expired'
export const PAIR_TOO_MANY = 'pair/too-many-attempts'
export const PAIR_BAD_CODE = 'pair/bad-code'

/** One pairing window at a time: 6-digit code, 10 min TTL, 3 attempts (same rules as dsh-tether). */
export function createPairing({ now = Date.now, ttlMs = PAIRING_TTL_MS } = {}) {
  let win
  return {
    open() {
      win = { code: String(randomInt(0, 1_000_000)).padStart(6, '0'), deadline: now() + ttlMs, attempts: 0 }
      return { code: win.code, expiresInSec: Math.round(ttlMs / 1000) }
    },
    close() { win = undefined },
    status: () => (win && now() < win.deadline ? { open: true, expiresInSec: Math.round((win.deadline - now()) / 1000), attemptsLeft: PAIRING_MAX_ATTEMPTS - win.attempts } : { open: false }),
    /** @returns {null | string} null on success (window consumed), else a reason code */
    attempt(code) {
      if (!win) return PAIR_NO_WINDOW
      if (now() >= win.deadline) { win = undefined; return PAIR_EXPIRED }
      if (win.attempts >= PAIRING_MAX_ATTEMPTS) { win = undefined; return PAIR_TOO_MANY }
      win.attempts++
      if (typeof code === 'string' && code === win.code) { win = undefined; return null }
      if (win.attempts >= PAIRING_MAX_ATTEMPTS) { win = undefined; return PAIR_TOO_MANY }
      return PAIR_BAD_CODE
    },
  }
}
