package dev.dsh.pocket.app

import dev.dsh.pocket.protocol.CompactionStatus
import dev.dsh.pocket.protocol.Item
import dev.dsh.pocket.protocol.StepRecord
import dev.dsh.pocket.protocol.StepUsage
import dev.dsh.pocket.protocol.TrajectoryRecordState

/**
 * Desktop `TrajectoryRequestNumber` — one session-global numbered request
 * (assistant step or compaction) for the Request inspector.
 */
data class TrajectoryRequest(
    val number: Int,
    val identity: String,
    val purpose: Purpose,
    val turn: Int?,
    val step: Int?,
    val group: String,
    val status: TrajectoryRecordState,
    val startedAt: Long? = null,
    val completedAt: Long? = null,
    val provider: String? = null,
    val model: String? = null,
    val requestConfigJson: String? = null,
    val usage: StepUsage? = null,
    val cumulativeUsage: StepUsage? = null,
    val toolCallCount: Int = 0,
    val subtoolCallCount: Int = 0,
    val error: String? = null,
    val errorCode: String? = null,
    val retry: Int? = null,
    val maxRetries: Int? = null,
    val retryDelayMs: Long? = null,
    /** Ledger row key for the result (assistant / compacted). */
    val resultKey: String? = null,
) {
    enum class Purpose { Assistant, Compaction }
}

/**
 * Build session-global numbered requests from settled steps + compaction items
 * (desktop TrajectoryView `requestNumbers` memo).
 */
fun buildTrajectoryRequests(
    items: List<Item>,
    steps: List<StepRecord>,
): List<TrajectoryRequest> {
    data class Entry(val seq: Long, val build: (number: Int, cumulative: StepUsage?) -> TrajectoryRequest)
    val entries = ArrayList<Entry>()

    for (step in steps) {
        val tools = items.filterIsInstance<Item.Tool>().filter { it.turn == step.turn && it.step == step.step }
        val toolCount = tools.count { it.parentCallId == null }
        val subCount = tools.count { it.parentCallId != null }
        val status = when {
            step.interrupted -> TrajectoryRecordState.Error
            else -> TrajectoryRecordState.Complete
        }
        entries += Entry(step.seq) { number, cumulative ->
            TrajectoryRequest(
                number = number,
                identity = "assistant:${step.turn}:${step.step}:${step.seq}",
                purpose = TrajectoryRequest.Purpose.Assistant,
                turn = step.turn,
                step = step.step,
                group = TrajL.groupStep(step.step),
                status = status,
                startedAt = step.stepStartTime,
                completedAt = step.messageTime,
                provider = step.provider,
                model = step.model,
                requestConfigJson = step.requestConfigJson,
                usage = step.usage,
                cumulativeUsage = cumulative,
                toolCallCount = toolCount,
                subtoolCallCount = subCount,
                error = step.lastAttemptError.takeIf { step.interrupted || step.retry != null },
                retry = step.retry,
                maxRetries = step.maxRetries,
                retryDelayMs = step.retryDelayMs,
                resultKey = "a${step.seq}",
            )
        }
    }

    for (item in items.filterIsInstance<Item.Compacted>()) {
        val status = when (item.status) {
            CompactionStatus.Running -> TrajectoryRecordState.Running
            CompactionStatus.Error -> TrajectoryRecordState.Error
            CompactionStatus.Ok -> TrajectoryRecordState.Complete
        }
        val seq = item.key.removePrefix("cmp").toLongOrNull() ?: item.compactionId.hashCode().toLong()
        entries += Entry(seq) { number, cumulative ->
            TrajectoryRequest(
                number = number,
                identity = "compaction:${item.compactionId}",
                purpose = TrajectoryRequest.Purpose.Compaction,
                turn = item.turn,
                step = null,
                group = TrajL.groupCompaction(seq),
                status = status,
                startedAt = item.startedAt,
                completedAt = item.endedAt,
                usage = null,
                cumulativeUsage = cumulative,
                error = item.error,
                errorCode = if (item.interrupted) "interrupted" else null,
                resultKey = item.key,
            )
        }
    }

    entries.sortBy { it.seq }
    var cumulative: StepUsage? = null
    return entries.mapIndexed { index, entry ->
        // Cumulative usage only advances on assistant requests that report usage.
        val req = entry.build(index + 1, cumulative)
        if (req.purpose == TrajectoryRequest.Purpose.Assistant && req.usage != null) {
            cumulative = addUsage(cumulative, req.usage)
        }
        req.copy(cumulativeUsage = cumulative)
    }
}

/** Desktop `addUsage` for request cumulative token totals. */
fun addUsage(a: StepUsage?, b: StepUsage?): StepUsage? {
    if (a == null) return b
    if (b == null) return a
    fun Long?.plusOr(other: Long?) = when {
        this == null && other == null -> null
        else -> (this ?: 0) + (other ?: 0)
    }
    return StepUsage(
        inputTokens = a.inputTokens.plusOr(b.inputTokens),
        cacheReadTokens = a.cacheReadTokens.plusOr(b.cacheReadTokens),
        cacheWriteTokens = a.cacheWriteTokens.plusOr(b.cacheWriteTokens),
        outputTokens = a.outputTokens.plusOr(b.outputTokens),
        reasoningTokens = a.reasoningTokens.plusOr(b.reasoningTokens),
    )
}

/** Map a ledger row to its owning request (assistant step or compaction). */
fun requestForRow(row: TrajRow, requests: List<TrajectoryRequest>): TrajectoryRequest? {
    requests.firstOrNull { it.resultKey == row.key }?.let { return it }
    if (row.kind == TrajL.kindTool || row.kind == TrajL.kindSubtool) {
        val turn = row.turn
        val step = row.step
        if (turn != null && step != null) {
            return requests.firstOrNull {
                it.purpose == TrajectoryRequest.Purpose.Assistant && it.turn == turn && it.step == step
            }
        }
    }
    if (row.kind == TrajL.kindAssistant && row.turn != null && row.step != null) {
        return requests.firstOrNull {
            it.purpose == TrajectoryRequest.Purpose.Assistant && it.turn == row.turn && it.step == row.step
        }
    }
    return null
}

/** First ledger row key that should show the Request boundary control for [request]. */
fun requestBoundaryRowKey(request: TrajectoryRequest, turns: List<TrajTurn>): String? {
    request.resultKey?.let { key ->
        if (turns.any { t -> t.rows.any { it.key == key } }) return key
    }
    if (request.purpose == TrajectoryRequest.Purpose.Assistant) {
        val turn = request.turn ?: return null
        val step = request.step ?: return null
        val rows = turns.firstOrNull { it.index == turn }?.rows.orEmpty()
        return rows.firstOrNull { it.kind == TrajL.kindAssistant && it.step == step }?.key
            ?: rows.firstOrNull {
                (it.kind == TrajL.kindTool || it.kind == TrajL.kindSubtool) && it.step == step
            }?.key
    }
    return null
}

fun requestLabel(request: TrajectoryRequest): String = when (request.purpose) {
    TrajectoryRequest.Purpose.Compaction -> TrajL.requestLabelCompaction(request.number)
    TrajectoryRequest.Purpose.Assistant -> TrajL.requestLabel(request.number)
}
