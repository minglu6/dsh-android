package dev.dsh.pocket.app

import dev.dsh.pocket.protocol.jsonOf
import org.junit.Test
import kotlin.test.*

class JobPresentationTest {
    private fun job(id: String, status: String, start: Long = 1000, finish: Long? = null, output: Long = 0) =
        BackgroundJob(id, "bash", "command-$id", status, null, start, finish, output)

    @Test fun liveStartOrderAndNewestSettledOrderHaveDesktopDefaultFold() {
        val state = JobsPresentation()
        val token = state.activate()
        state.receive(token, listOf(
            job("old", "completed", 100, 200), job("later", "running", 500),
            job("tie-late", "failed", 300, 900), job("first", "stopping", 400),
            job("tie-early", "killed", 200, 900), job("newest", "completed", 600, 1000),
        ))
        assertEquals(listOf("first", "later"), state.liveRows.map { it.id })
        assertEquals(listOf("newest", "tie-early", "tie-late", "old"), state.settledRows.map { it.id })
        assertFalse(state.settledExpanded)
        state.receive(token, state.settledRows)
        assertTrue(state.settledExpanded)
        state.settledOpen = false
        state.receive(token, state.settledRows + job("new", "completed", 800, 1100))
        assertFalse(state.settledExpanded)
    }

    @Test fun clearHidesOnlyCurrentSettledIdsKeepsLiveOutputAndNewSettlementReappears() {
        val state = JobsPresentation()
        val token = state.activate()
        val live = job("live", "running")
        val done = job("done", "completed", finish = 4000, output = 12)
        state.receive(token, listOf(done, live))
        state.open = true
        state.toggle("live")
        state.clearSettled()
        assertEquals("live", state.observedId)
        assertEquals(listOf(live), state.liveRows)
        assertTrue(state.settledRows.isEmpty())
        state.receive(token, listOf(done, live.copy(status = "completed", finishedAt = 5000)))
        assertEquals(listOf("live"), state.settledRows.map { it.id })
        assertEquals(1, state.visibleCount)
        state.clearSettled()
        assertFalse(state.open)
        assertEquals(2, state.jobs!!.size) // registry snapshot is untouched
    }

    @Test fun observationRequiresVisibleObservableRowAndClearClosesFinishedOutput() {
        val state = JobsPresentation()
        val token = state.activate()
        state.receive(token, listOf(job("done", "completed", finish = 3000, output = 1), job("silent", "failed")))
        state.open = true
        state.toggle("silent")
        assertNull(state.observedId)
        state.toggle("done")
        assertEquals("done", state.observedId)
        state.settledOpen = false
        assertNull(state.observedId)
        state.settledOpen = true
        assertEquals("done", state.observedId)
        state.open = false
        assertNull(state.observedId)
        state.open = true
        state.clearSettled()
        assertNull(state.observedId)
        assertNull(state.expandedId)
    }

    @Test fun stopNeedsTwoPressesAndAdmissionWaitsForAuthoritativeStatus() {
        val state = JobsPresentation()
        val token = state.activate()
        val live = job("live", "running")
        state.receive(token, listOf(live, job("stopping", "stopping"), job("done", "completed"), job("pending", "pending")))
        var kills = 0
        var complete: ((Boolean) -> Unit)? = null
        val kill: ((Boolean) -> Unit) -> Unit = { kills++; complete = it }
        listOf("stopping", "done", "pending", "missing").forEach { id -> repeat(2) { state.pressKill(id, kill) } }
        assertEquals(0, kills)
        state.pressKill("live", kill)
        assertEquals(JobKillState.Armed, state.killPhase?.state)
        assertEquals(0, kills)
        state.pressKill("live", kill)
        assertEquals(1, kills)
        assertEquals(JobKillState.Pending, state.killPhase?.state)
        complete!!(true)
        assertEquals("running", state.liveRows.single { it.id == "live" }.status)
        assertEquals(JobKillState.Pending, state.killPhase?.state)
        state.pressKill("live", kill)
        assertEquals(1, kills)
        state.receive(token, listOf(live.copy(status = "stopping")))
        assertNull(state.killPhase)
        state.pressKill("live", kill)
        assertEquals(1, kills)
    }

    @Test fun failedStopExpiresAndStaleTimersCannotDisarmNewConfirmation() {
        val state = JobsPresentation()
        state.receive(state.activate(), listOf(job("a", "running"), job("b", "running")))
        var complete: ((Boolean) -> Unit)? = null
        val request: ((Boolean) -> Unit) -> Unit = { complete = it }
        state.pressKill("a", request)
        val armedA = state.killPhase!!
        state.pressKill("b", request)
        state.expireKill(armedA)
        assertEquals("b", state.killPhase?.id)
        state.pressKill("b", request)
        complete!!(false)
        assertEquals(JobKillState.Failed, state.killPhase?.state)
        state.expireKill(state.killPhase!!)
        assertNull(state.killPhase)
    }

    @Test fun reconnectFencesLateRosterAndKillRepliesWithoutLosingClientClear() {
        val state = JobsPresentation()
        val old = state.activate()
        val live = job("live", "running")
        val done = job("done", "completed")
        state.receive(old, listOf(live, done))
        state.clearSettled()
        var complete: ((Boolean) -> Unit)? = null
        val request: ((Boolean) -> Unit) -> Unit = { complete = it }
        state.pressKill("live", request)
        state.pressKill("live", request)
        state.open = true
        state.deactivate(old)
        assertFalse(state.active)
        assertFalse(state.open)
        assertNull(state.jobs)
        val fresh = state.activate()
        state.receive(fresh, listOf(live, done))
        state.pressKill("live", request)
        val current = state.killPhase
        complete!!(false)
        state.receive(old, listOf(job("foreign", "running")))
        state.deactivate(old)
        assertTrue(state.active)
        assertSame(current, state.killPhase)
        assertEquals(listOf("live"), state.liveRows.map { it.id })
        assertTrue(state.settledRows.isEmpty())
    }

    @Test fun jobProjectionUsesWireTimestampsOutputAndProducerExitDetail() {
        val row = BackgroundJob.parse(jsonOf(
            "id" to "bash-1", "kind" to "bash", "label" to "exit 7", "status" to "completed",
            "detail" to "exit code: 7", "startedAt" to 1000, "finishedAt" to 63_000,
            "output" to jsonOf("total" to 12, "earliest" to 0),
        ))!!
        assertTrue(row.observable)
        assertEquals("1m 2s", row.duration(Long.MAX_VALUE, true))
        val live = BackgroundJob.parse(jsonOf(
            "id" to "bash-2", "kind" to "bash", "label" to "build", "status" to "running",
            "progress" to "3/10", "detail" to "producer reason", "startedAt" to 1000,
        ))!!
        assertEquals("3/10", live.detail)
        assertEquals("1h 1m", live.duration(3_662_000, true))
        assertNull(live.copy(startedAt = null).duration(3_662_000, true))
        assertNull(BackgroundJob.parse(jsonOf("status" to "running")))
    }
}
