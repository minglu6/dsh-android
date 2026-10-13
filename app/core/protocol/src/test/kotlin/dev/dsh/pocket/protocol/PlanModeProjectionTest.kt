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

    @Test fun livePlanProjectionCannotBeOverwrittenByOlderEventOrResume() {
        val r = TranscriptReducer()
        r.apply(el("""{"type":"projection","key":"plan","seq":10,"value":{"active":true}}"""))
        r.apply(el("""{"type":"event","event":{"type":"plan/mode","seq":9,"data":{"active":false}}}"""))
        r.apply(el("""{"type":"resume","cursor":9,"records":[],"projections":{"asOfSeq":9,"values":{"plan":{"active":false}}}}"""))
        assertTrue(r.planMode)
        r.apply(el("""{"type":"projection","key":"plan","seq":11,"value":null}"""))
        assertFalse(r.planMode)
    }

    @Test fun snapshotResetsPlanMode() {
        val r = TranscriptReducer()
        r.apply(el("""{"type":"event","event":{"type":"plan/mode","seq":1,"time":1,"data":{"active":true}}}"""))
        assertTrue(r.planMode)
        r.apply(el("""{"type":"snapshot","cursor":0,"hasMore":false,"records":[],"projections":{"values":{}}}"""))
        assertFalse(r.planMode)
    }
}
