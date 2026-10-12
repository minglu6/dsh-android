package dev.dsh.pocket.app

import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Adapted from saya-ch/dsh-mobile (Apache-2.0) RestoreAttemptGateTest.kt. */
class RestoreAttemptGateTest {
    @Test
    fun aCurrentResultStillRunsNormally() {
        val gate = RestoreAttemptGate()
        val generation = gate.begin()
        val delivered = AtomicInteger()
        gate.callback(generation) { delivered.incrementAndGet() }.run()
        assertEquals(1, delivered.get())
        assertTrue(gate.isCurrent(generation))
    }

    @Test
    fun invalidationRejectsHeldCallbacks() {
        val gate = RestoreAttemptGate()
        val generation = gate.begin()
        val delivered = AtomicInteger()
        val result = gate.callback(generation) { delivered.incrementAndGet() }
        gate.invalidate()
        result.run()
        assertEquals(0, delivered.get())
        assertFalse(gate.isCurrent(generation))
    }

    @Test
    fun aNewAttemptInvalidatesThePreviousGeneration() {
        val gate = RestoreAttemptGate()
        val first = gate.begin()
        val second = gate.begin()
        assertFalse(gate.isCurrent(first))
        assertTrue(gate.isCurrent(second))
        assertEquals(second, gate.generation)
    }
}
