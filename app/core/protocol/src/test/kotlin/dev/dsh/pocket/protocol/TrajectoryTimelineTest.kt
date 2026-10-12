package dev.dsh.pocket.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Lane and segment projection — ported from desktop
 * `@deepseek-ai/dsh-client-ui-trajectory` `views.client.spec.tsx` timeline cases.
 */
class TrajectoryTimelineTest {
    @Test fun laneAssignmentMatchesDesktop() {
        assertEquals(0, trajectoryLaneFor("system"))
        assertEquals(0, trajectoryLaneFor("用户"))
        assertEquals(0, trajectoryLaneFor("上下文"))
        assertEquals(1, trajectoryLaneFor("助手"))
        assertEquals(1, trajectoryLaneFor("message"))
        assertEquals(1, trajectoryLaneFor("compacted"))
        assertEquals(2, trajectoryLaneFor("工具"))
        assertEquals(2, trajectoryLaneFor("tool"))
        assertEquals(2, trajectoryLaneFor("subtool"))
    }

    @Test fun sequenceModeUsesEqualWidthSlotsAndSemanticLanes() {
        val cells = listOf(
            TrajectoryTimelineCell("a", "助手", "assistant", turn = 1, startedAt = null, timeSeconds = null),
            TrajectoryTimelineCell("t", "工具", "bash", turn = 1, startedAt = null, timeSeconds = null),
            TrajectoryTimelineCell("u", "用户", "unknown", turn = 1, startedAt = null, timeSeconds = null),
        )
        val model = assertNotNull(deriveTrajectoryTimeline(cells, TrajectoryTimelineMode.Sequence))
        assertEquals(0.0, model.start)
        assertEquals(3.0, model.end)
        assertEquals(
            listOf(
                TrajectoryTimelineSpan("a", "message", lane = 1, start = 0.0, end = 1.0, label = "assistant"),
                TrajectoryTimelineSpan("t", "tool", lane = 2, start = 1.0, end = 2.0, label = "bash"),
                TrajectoryTimelineSpan("u", "user", lane = 0, start = 2.0, end = 3.0, label = "unknown"),
            ),
            model.spans,
        )
        assertEquals(listOf(TrajectoryTimelineTurnBoundary(1, 0.0)), model.turnBoundaries)
    }

    @Test fun durationModeCompressesIdleGapsWhileActualRetainsWallTime() {
        val cells = listOf(
            TrajectoryTimelineCell("m1", "助手", "first", turn = 1, startedAt = 1_000, timeSeconds = 1.0),
            TrajectoryTimelineCell("t1", "工具", "within-turn gap", turn = 1, startedAt = 4_000, timeSeconds = 1.0),
            TrajectoryTimelineCell("m2", "助手", "after user idle", turn = 2, startedAt = 40_000, timeSeconds = 1.0),
        )
        val sequence = assertNotNull(deriveTrajectoryTimeline(cells, TrajectoryTimelineMode.Sequence))
        assertEquals(listOf(0.0 to 1.0, 1.0 to 2.0, 2.0 to 3.0), sequence.spans.map { it.start to it.end })

        val duration = assertNotNull(deriveTrajectoryTimeline(cells, TrajectoryTimelineMode.Duration))
        assertEquals(1_000.0, duration.start)
        assertEquals(4_000.0, duration.end)
        assertEquals(
            listOf(1_000.0 to 2_000.0, 2_000.0 to 3_000.0, 3_000.0 to 4_000.0),
            duration.spans.map { it.start to it.end },
        )
        assertEquals(
            listOf(TrajectoryTimelineTurnBoundary(1, 1_000.0), TrajectoryTimelineTurnBoundary(2, 3_000.0)),
            duration.turnBoundaries,
        )

        val actual = assertNotNull(deriveTrajectoryTimeline(cells, TrajectoryTimelineMode.Actual))
        assertEquals(1_000.0, actual.start)
        assertEquals(41_000.0, actual.end)
        assertEquals(
            listOf(1_000.0 to 2_000.0, 4_000.0 to 5_000.0, 40_000.0 to 41_000.0),
            actual.spans.map { it.start to it.end },
        )
    }

    @Test fun emptyTimelineIsNull() {
        assertNull(deriveTrajectoryTimeline(emptyList()))
    }
}
