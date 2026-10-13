package dev.dsh.pocket.protocol

/**
 * Pure reconnect delay policy for the phone WS loop.
 * Starts at [INITIAL_MS], doubles after each failure, capped at [MAX_MS].
 */
object ReconnectBackoffPolicy {
    const val INITIAL_MS = 1_000L
    const val MAX_MS = 30_000L

    fun reset(): Long = INITIAL_MS

    fun next(currentMs: Long): Long = (currentMs * 2).coerceAtMost(MAX_MS)
}
