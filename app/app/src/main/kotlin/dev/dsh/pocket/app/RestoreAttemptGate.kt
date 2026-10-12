package dev.dsh.pocket.app

/**
 * Adapted from saya-ch/dsh-mobile (Apache-2.0) RestoreAttemptGate.kt.
 * Collapses rapid NetworkCallback bursts into one reconnect generation.
 */
class RestoreAttemptGate {
    @Volatile
    var generation: Int = 0
        private set

    /** Start another restore attempt; previous generation callbacks become stale. */
    fun begin(): Int {
        generation += 1
        return generation
    }

    fun invalidate() {
        generation += 1
    }

    fun isCurrent(expectedGeneration: Int): Boolean = generation == expectedGeneration

    /** Deliver a result only to the restore generation which requested it. */
    fun callback(expectedGeneration: Int, operation: () -> Unit): Runnable = Runnable {
        if (isCurrent(expectedGeneration)) operation()
    }
}
