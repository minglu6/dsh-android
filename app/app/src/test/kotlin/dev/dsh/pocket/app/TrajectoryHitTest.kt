package dev.dsh.pocket.app

import dev.dsh.pocket.protocol.TrajectoryTimelineModel
import dev.dsh.pocket.protocol.TrajectoryTimelineSpan
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TrajectoryHitTest {
    private val model = TrajectoryTimelineModel(
        start = 0.0,
        end = 10.0,
        spans = listOf(
            TrajectoryTimelineSpan("input", "user", 0, 1.0, 8.0),
            TrajectoryTimelineSpan("reply", "message", 1, 1.0, 8.0),
            TrajectoryTimelineSpan("tool", "tool", 2, 1.0, 8.0),
            TrajectoryTimelineSpan("point", "tool", 2, 9.0, 9.0),
        ),
        turnBoundaries = emptyList(),
    )

    private fun hit(x: Float, y: Float) = trajectorySpanAt(model, x, y, 100f, 2f, 8f, 7f, 14f)

    @Test fun overlappingDurationsSelectTheTouchedLane() {
        assertEquals("input", hit(20f, 10f))
        assertEquals("reply", hit(20f, 25f))
        assertEquals("tool", hit(20f, 40f))
    }

    @Test fun markersUseTheirPaintedWidthWithoutSelectingEmptySpace() {
        assertEquals("point", hit(91f, 40f))
        assertNull(hit(95f, 40f))
        assertNull(hit(99f, 40f))
        assertNull(hit(95f, 25f))
        assertNull(hit(20f, 0f))
        assertNull(hit(20f, 50f))
        assertNull(hit(-1f, 10f))
        assertNull(hit(20f, 18f))
        assertNull(hit(10f, 10f))
        assertNull(hit(80f, 10f))
    }
}
