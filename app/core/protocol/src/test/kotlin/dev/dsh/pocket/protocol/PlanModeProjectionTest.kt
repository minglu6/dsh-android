package dev.dsh.pocket.protocol

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Host `plan` projection + `plan/mode` events drive the composer PlanChip. */
class PlanModeProjectionTest {
    private fun el(s: String) = PocketJson.parseToJsonElement(s)

    @Test fun planModeEventSetsActive() {
        val r = TranscriptReducer()
        assertFalse(r.planMode)
        r.apply(el("""{"type":"event","event":{"type":"plan/mode","seq":1,"time":1,"data":{"active":true}}}"""))
        assertTrue(r.planMode)
        r.apply(el("""{"type":"event","event":{"type":"plan/mode","seq":2,"time":2,"data":{"active":false}}}"""))
        assertFalse(r.planMode)
    }

    @Test fun projectionsPlanActiveOnSnapshot() {
        val r = TranscriptReducer()
        r.apply(el("""{"type":"snapshot","cursor":0,"hasMore":false,"records":[],"projections":{"values":{"plan":{"active":true,"pending":false}}}}"""))
        assertTrue(r.planMode)
    }

    @Test fun snapshotResetsPlanMode() {
        val r = TranscriptReducer()
        r.apply(el("""{"type":"event","event":{"type":"plan/mode","seq":1,"time":1,"data":{"active":true}}}"""))
        assertTrue(r.planMode)
        r.apply(el("""{"type":"snapshot","cursor":0,"hasMore":false,"records":[],"projections":{"values":{}}}"""))
        assertFalse(r.planMode)
    }
}
