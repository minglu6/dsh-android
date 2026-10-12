package dev.dsh.pocket.app

import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

/**
 * Adapted from dsh-mobile DeviceStatusPollerTest.kt
 * (https://github.com/saya-ch/dsh-mobile), Apache-2.0.
 */
class DeviceStatusPollerTest {
    private class QueuedExecutor : Executor {
        val work = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { work.add(command) }
        fun next() { work.removeFirst().run() }
    }

    private fun device(seed: Char = 'a', host: String = "192.168.1.10") = Profile(
        id = "id-$seed",
        name = "Computer $seed",
        host = host,
        port = 7690,
        instanceId = seed.toString().repeat(64),
        deviceId = "d$seed",
    )

    private fun result(device: Profile) =
        DeviceStatusPoller.ProbeOutcome(reachable = true)

    @Test fun repeatedListTicksKeepOneProbeAndAcceptItsSlowCurrentResult() {
        val executor = QueuedExecutor()
        val observed = mutableListOf<String>()
        var calls = 0
        val poller = DeviceStatusPoller(executor, { it() }, { calls++; result(it) }, { row, _ -> observed += row.id })
        repeat(30) { poller.refresh(listOf(device())) }
        assertEquals(1, executor.work.size)
        executor.next()
        assertEquals(1, calls)
        assertEquals(listOf(device().id), observed)
        assertTrue(executor.work.isEmpty())
    }

    @Test fun twoBlockedProbesLeaveTheThirdExistingRestoreThreadAvailable() {
        val executor = Executors.newFixedThreadPool(3)
        val started = CountDownLatch(2)
        val release = CountDownLatch(1)
        val completed = CountDownLatch(4)
        val active = AtomicInteger()
        val maxActive = AtomicInteger()
        val calls = AtomicInteger()
        val poller = DeviceStatusPoller(executor, { it() }, { row ->
            calls.incrementAndGet()
            val current = active.incrementAndGet()
            maxActive.accumulateAndGet(current, ::maxOf)
            started.countDown()
            try {
                check(release.await(5, TimeUnit.SECONDS)) { "probe release was not signalled" }
                result(row)
            } finally { active.decrementAndGet() }
        }, { _, _ -> completed.countDown() })
        try {
            val devices = listOf(device('a'), device('b'), device('c'), device('d'))
            poller.refresh(devices)
            assertTrue(started.await(3, TimeUnit.SECONDS))
            repeat(30) { poller.refresh(devices) }
            assertEquals(2, calls.get())
            assertEquals("interactive restore", executor.submit<String> { "interactive restore" }.get(3, TimeUnit.SECONDS))
            release.countDown()
            assertTrue(completed.await(3, TimeUnit.SECONDS))
            assertEquals(4, calls.get())
            assertEquals(2, maxActive.get())
        } finally {
            poller.stop()
            release.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS))
        }
    }

    @Test fun stopWithdrawsQueuedIoAndUiResultsUntilANewOwnerResumes() {
        val executor = QueuedExecutor()
        val ui = ArrayDeque<() -> Unit>()
        var calls = 0
        var delivered = 0
        val poller = DeviceStatusPoller(executor, { ui.add(it) }, { calls++; result(it) }, { _, _ -> delivered++ })
        poller.refresh(listOf(device('a'), device('b')))
        executor.next()
        poller.stop()
        ui.removeFirst()()
        executor.next()
        assertEquals(1, calls)
        assertEquals(0, delivered)
        poller.refresh(listOf(device('a')))
        executor.next()
        ui.removeFirst()()
        assertEquals(2, calls)
        assertEquals(1, delivered)
    }

    @Test fun probeAndExecutorFailuresSettleTheirSlotsAndAllowALaterRetry() {
        val executor = QueuedExecutor()
        val observed = mutableListOf<Result<DeviceStatusPoller.ProbeOutcome>>()
        var failing = true
        val poller = DeviceStatusPoller(executor, { it() }, {
            if (failing) error("timeout")
            result(it)
        }, { _, response -> observed += response })
        poller.refresh(listOf(device()))
        executor.next()
        assertTrue(observed.single().isFailure)
        failing = false
        poller.refresh(listOf(device()))
        executor.next()
        assertTrue(observed.last().isSuccess)

        val rejected = mutableListOf<Result<DeviceStatusPoller.ProbeOutcome>>()
        DeviceStatusPoller(Executor { throw RejectedExecutionException("closed owned executor") }, { it() }, ::result, { _, response -> rejected += response })
            .refresh(listOf(device('a'), device('b'), device('c')))
        assertEquals(3, rejected.size)
        assertTrue(rejected.all { it.exceptionOrNull() is RejectedExecutionException })
    }
}
