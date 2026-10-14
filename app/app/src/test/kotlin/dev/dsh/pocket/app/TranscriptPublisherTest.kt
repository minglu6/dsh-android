package dev.dsh.pocket.app

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class TranscriptPublisherTest {
    private data class View(val session: String, val text: String, val running: Boolean)

    @Test fun continuousDeltasPublishTheCompleteLatestTextWithoutDebouncingForever() = runTest {
        var state = View("A", "", true)
        val painted = mutableListOf<View>()
        val publisher = TranscriptPublisher(this) { painted += state }
        repeat(5) {
            state = state.copy(text = state.text + it)
            publisher.schedule()
            runCurrent()
            advanceTimeBy(10)
        }
        assertTrue(painted.isEmpty())
        runCurrent()
        assertEquals(listOf(View("A", "01234", true)), painted)
        state = state.copy(text = state.text + "5")
        publisher.schedule()
        runCurrent()
        advanceTimeBy(50)
        runCurrent()
        assertEquals(View("A", "012345", true), painted.last())
        assertEquals(2, painted.size)
    }

    @Test fun completionFlushIncludesPendingTextAndOldTickCannotReopenRunningState() = runTest {
        var state = View("A", "partial", true)
        val painted = mutableListOf<View>()
        val publisher = TranscriptPublisher(this) { painted += state }
        publisher.schedule()
        runCurrent()
        advanceTimeBy(20)
        state = View("A", "complete answer", false)
        publisher.flush()
        assertEquals(listOf(state), painted)
        advanceTimeBy(100)
        runCurrent()
        assertEquals(listOf(View("A", "complete answer", false)), painted)
    }

    @Test fun detachedGenerationCannotPaintIntoTheNextSession() = runTest {
        var state = View("A", "old answer", true)
        val painted = mutableListOf<View>()
        val publisher = TranscriptPublisher(this) { painted += state }
        publisher.schedule()
        runCurrent()
        advanceTimeBy(25)
        publisher.cancel()
        state = View("B", "new answer", true)
        publisher.schedule()
        runCurrent()
        advanceTimeBy(25)
        runCurrent()
        assertTrue(painted.isEmpty())
        advanceTimeBy(25)
        runCurrent()
        assertEquals(listOf(View("B", "new answer", true)), painted)
    }
}
