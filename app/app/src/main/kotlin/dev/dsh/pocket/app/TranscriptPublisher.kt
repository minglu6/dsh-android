package dev.dsh.pocket.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Actor-owned presentation clock. Events are already reduced before requesting a publication. */
internal class TranscriptPublisher(
    private val scope: CoroutineScope,
    private val publish: () -> Unit,
) {
    private var pending: Job? = null

    /** Fixed window, not debounce: continuous output cannot keep postponing the next paint. */
    fun schedule() {
        if (pending != null) return
        pending = scope.launch {
            delay(50)
            pending = null
            publish()
        }
    }

    /** Structural/terminal state includes all preceding deltas and supersedes a scheduled paint. */
    fun flush() {
        cancel()
        publish()
    }

    /** A detached subscription must never publish into its replacement. */
    fun cancel() {
        pending?.cancel()
        pending = null
    }
}
