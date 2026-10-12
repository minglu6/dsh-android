package dev.dsh.pocket.protocol

/**
 * Operation-sequence and recorded-time projections for the trajectory swimlane.
 * Mirrors `@deepseek-ai/dsh-client-ui-trajectory` `timeline.ts` (`deriveTrajectoryTimeline`).
 */

/** Horizontal projection used by the trajectory timeline. */
enum class TrajectoryTimelineMode {
    /** Equal-width ops (时长 off). */
    Sequence,
    /** Recorded duration with idle gaps compressed (时长 on). */
    Duration,
    /** Recorded duration retaining wall-clock idle gaps (desktop 实际时间+时长; hidden in toolbar). */
    Actual,
}

/** Inclusive selection / span in the active timeline projection's domain. */
data class TrajectoryTimeRange(val start: Double, val end: Double)

/** One ledger record projected into the active timeline domain. */
data class TrajectoryTimelineSpan(
    val key: String,
    val kind: String,
    val lane: Int,
    val start: Double,
    val end: Double,
    val isError: Boolean = false,
    val label: String = "",
)

/** One turn boundary in the active timeline domain. */
data class TrajectoryTimelineTurnBoundary(val turn: Int, val time: Double)

/** Full-domain model used by the overview. */
data class TrajectoryTimelineModel(
    val start: Double,
    val end: Double,
    val spans: List<TrajectoryTimelineSpan>,
    val turnBoundaries: List<TrajectoryTimelineTurnBoundary>,
)

/**
 * Desktop `laneFor`: tools → lane 2 (工具), assistant/compacted → lane 1 (模型), else lane 0 (输入).
 */
fun trajectoryLaneFor(kind: String): Int = when (kind) {
    "tool", "subtool", "工具", "子工具" -> 2
    "message", "compacted", "助手", "已压缩" -> 1
    else -> 0
}

/** Map Chinese row kind labels to the desktop `data-timeline-span` token. */
fun trajectorySpanKind(kind: String): String = when (kind) {
    "系统" -> "system"
    "用户" -> "user"
    "上下文" -> "context"
    "助手" -> "message"
    "工具" -> "tool"
    "子工具" -> "subtool"
    "已压缩" -> "compacted"
    else -> kind
}

/** Input cell for timeline projection (one visible ledger row). */
data class TrajectoryTimelineCell(
    val key: String,
    val kind: String,
    val label: String,
    val turn: Int?,
    /** Unix epoch ms when the operation started, when known. */
    val startedAt: Long?,
    /** Own duration in seconds, or null when no duration is known. */
    val timeSeconds: Double?,
    val isError: Boolean = false,
    /** Desktop `requestOnly` anchors are omitted from the overview. */
    val requestOnly: Boolean = false,
)

/**
 * Project every visible record into a stable three-lane timeline.
 * @return null when no record is visible.
 */
fun deriveTrajectoryTimeline(
    cells: List<TrajectoryTimelineCell>,
    mode: TrajectoryTimelineMode = TrajectoryTimelineMode.Sequence,
): TrajectoryTimelineModel? {
    if (mode != TrajectoryTimelineMode.Sequence) {
        return deriveTimedTimeline(
            cells,
            actualDuration = mode == TrajectoryTimelineMode.Duration || mode == TrajectoryTimelineMode.Actual,
            compressIdle = mode == TrajectoryTimelineMode.Duration,
        )
    }
    val spans = ArrayList<TrajectoryTimelineSpan>()
    val turnBoundaries = ArrayList<TrajectoryTimelineTurnBoundary>()
    var lastTurn: Int? = null
    for (cell in cells) {
        if (cell.requestOnly) continue
        val turn = cell.turn
        if (turn != null && turn != lastTurn) {
            turnBoundaries += TrajectoryTimelineTurnBoundary(turn, spans.size.toDouble())
            lastTurn = turn
        }
        val start = spans.size.toDouble()
        spans += TrajectoryTimelineSpan(
            key = cell.key,
            kind = trajectorySpanKind(cell.kind),
            lane = trajectoryLaneFor(cell.kind),
            start = start,
            end = start + 1.0,
            isError = cell.isError,
            label = cell.label,
        )
    }
    if (spans.isEmpty()) return null
    return TrajectoryTimelineModel(0.0, spans.size.toDouble(), spans, turnBoundaries)
}

private fun finite(value: Double?): Boolean = value != null && value.isFinite()
private fun finite(value: Long?): Boolean = value != null

private fun cellRange(cell: TrajectoryTimelineCell): TrajectoryTimeRange? {
    if (!finite(cell.startedAt)) return null
    val durationMs = if (finite(cell.timeSeconds)) maxOf(0.0, cell.timeSeconds!! * 1_000.0) else 0.0
    val start = cell.startedAt!!.toDouble()
    return TrajectoryTimeRange(start, start + durationMs)
}

private fun deriveTimedTimeline(
    cells: List<TrajectoryTimelineCell>,
    actualDuration: Boolean,
    compressIdle: Boolean,
): TrajectoryTimelineModel? {
    data class Raw(val turn: Int?, val span: TrajectoryTimelineSpan)

    val rawSpans = ArrayList<Raw>()
    for (cell in cells) {
        if (cell.requestOnly) continue
        val range = cellRange(cell) ?: continue
        rawSpans += Raw(
            cell.turn,
            TrajectoryTimelineSpan(
                key = cell.key,
                kind = trajectorySpanKind(cell.kind),
                lane = trajectoryLaneFor(cell.kind),
                start = range.start,
                end = range.end,
                isError = cell.isError,
                label = cell.label,
            ),
        )
    }
    if (rawSpans.isEmpty()) return null

    val removedIdleBySpan = HashMap<TrajectoryTimelineSpan, Double>()
    var removedIdle = 0.0
    var coveredUntil: Double? = null
    for (span in rawSpans.map { it.span }.sortedWith(compareBy({ it.start }, { it.end }))) {
        if (compressIdle && coveredUntil != null && span.start > coveredUntil) {
            removedIdle += span.start - coveredUntil
        }
        removedIdleBySpan[span] = removedIdle
        coveredUntil = if (coveredUntil == null) span.end else maxOf(coveredUntil, span.end)
    }

    val spans = ArrayList<TrajectoryTimelineSpan>()
    val turnBoundaries = ArrayList<TrajectoryTimelineTurnBoundary>()
    var lastTurn: Int? = null
    for ((turn, span) in rawSpans) {
        val offset = removedIdleBySpan[span] ?: 0.0
        val projected = span.copy(
            start = span.start - offset,
            end = (if (actualDuration) span.end else span.start) - offset,
        )
        spans += projected
        if (turn != null && turn != lastTurn) {
            // Desktop uses min start among the turn's projected spans; we emit at first span of the turn.
            turnBoundaries += TrajectoryTimelineTurnBoundary(turn, projected.start)
            lastTurn = turn
        }
    }

    return TrajectoryTimelineModel(
        start = spans.minOf { it.start },
        end = spans.maxOf { it.end },
        spans = spans,
        turnBoundaries = turnBoundaries,
    )
}
