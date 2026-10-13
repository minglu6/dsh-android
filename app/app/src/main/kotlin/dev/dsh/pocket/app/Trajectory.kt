package dev.dsh.pocket.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dsh.pocket.protocol.CompactionStatus
import dev.dsh.pocket.protocol.Item
import dev.dsh.pocket.protocol.StepRecord
import dev.dsh.pocket.protocol.ToolStatus
import dev.dsh.pocket.protocol.TrajectoryTimelineCell
import dev.dsh.pocket.protocol.TrajectoryTimelineMode
import dev.dsh.pocket.protocol.TrajectoryTimelineModel
import dev.dsh.pocket.protocol.deriveTrajectoryTimeline
import kotlinx.coroutines.flow.distinctUntilChanged

/** One row in the Trajectory tab (desktop ui-trajectory cell kinds). */
data class TrajRow(
    val key: String,
    val depth: Int,
    val kind: String,
    val title: String,
    val input: String = "",
    val output: String = "",
    val think: String = "",
    val time: String = "",
    val running: Boolean = false,
    val detail: Item? = null,
    /** Full reasoning text for the row detail (desktop `thinkingDetail`). */
    val thinkingDetail: String? = null,
    /** Desktop `inputDetail` (tool args / user text). */
    val inputDetail: String? = null,
    /** Desktop `outputDetail` (tool result / assistant reply). */
    val outputDetail: String? = null,
    /** Desktop `schemaDetail` (pretty tool schema JSON). */
    val schemaDetail: String? = null,
    /** Markdown / system / context body for non-tool detail tabs. */
    val rawDetail: String? = null,
    /** Desktop `messageSource` JSON for 来源 tab. */
    val messageSourceJson: String? = null,
    /** Previous system prompt for 差异 tab. */
    val previousText: String? = null,
    /** Tools catalog JSON for system 工具 tab. */
    val toolsJson: String? = null,
    val previousToolsJson: String? = null,
    /** Wire tool name (for `run_code` / CodeProgram). */
    val toolName: String? = null,
    /** Inline `→` result preview on the ledger (desktop RecordListText). */
    val resultPreview: String? = null,
    /** Assistant tool-call-only placeholder (desktop muted `（仅工具调用）`). */
    val toolCallOnly: Boolean = false,
    /** Desktop `assistantMetrics.firstTokenTime`. */
    val firstTokenTime: Long? = null,
    /** Turn / step for the inspector header (`第 N 轮 · 第 M 步`). */
    val turn: Int? = null,
    val step: Int? = null,
    /** Session-event start time (ms) for swimlane projection. */
    val startedAt: Long? = null,
    /** Own duration in seconds for swimlane; null when unknown. */
    val timeSeconds: Double? = null,
    val isError: Boolean = false,
    /** Extra haystack for the desktop search index (kind + title already searched). */
    val searchText: String = "",
)

data class TrajTurn(val index: Int, val rows: List<TrajRow>)

private fun firstLine(text: String): String =
    text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()

/**
 * Desktop `trajectoryPreviewText` (simplified): collapse whitespace, cap length.
 * Full markdown plain-text extraction is not required for the phone ledger line.
 */
fun trajectoryPreviewText(text: String, max: Int = 512): String =
    text.replace(Regex("\\s+"), " ").trim().take(max).let {
        if (text.replace(Regex("\\s+"), " ").trim().length > max) "$it…" else it
    }

/** Desktop ledger preview: one-line ellipsis of injected / multi-line content. */
private fun oneLinePreview(text: String, max: Int = 96): String =
    trajectoryPreviewText(text, max)

private fun assistantTitle(reply: String, reasoning: String): Pair<String, Boolean> {
    val fromReply = firstLine(reply)
    if (fromReply.isNotEmpty()) return fromReply to false
    val fromThink = firstLine(reasoning)
    if (fromThink.isNotEmpty()) return fromThink to false
    return TrajL.layoutToolCallOnly to true
}

private fun assistantRow(step: StepRecord): TrajRow {
    val start = step.stepStartTime
    val end = step.messageTime
    val elapsedMs = if (start != null && end != null) (end - start).coerceAtLeast(0) else null
    val elapsed = elapsedMs?.let { formatElapsed(it) }.orEmpty()
    val input = step.usage.inputTokens?.let { kfmt(it) }.orEmpty()
    val output = step.usage.outputTokens?.let { kfmt(it) }.orEmpty()
    val think = step.usage.reasoningTokens?.let { kfmt(it) } ?: "—"
    val (title, toolOnly) = assistantTitle(step.replyText, step.reasoningText)
    return TrajRow(
        key = "a${step.seq}",
        depth = 0,
        kind = TrajL.kindAssistant,
        title = title,
        input = input,
        output = output,
        think = think,
        time = elapsed,
        detail = step.replyText.takeIf { it.isNotBlank() }?.let { Item.Text("a${step.seq}", it, live = false) },
        thinkingDetail = step.reasoningText.takeIf { it.isNotBlank() },
        outputDetail = step.replyText.takeIf { it.isNotBlank() },
        rawDetail = listOfNotNull(
            step.reasoningText.takeIf { it.isNotBlank() },
            step.replyText.takeIf { it.isNotBlank() },
        ).joinToString("\n\n").ifBlank { null },
        toolCallOnly = toolOnly,
        firstTokenTime = step.firstTokenTime,
        turn = step.turn,
        step = step.step,
        startedAt = start,
        timeSeconds = elapsedMs?.let { it / 1000.0 },
        isError = step.interrupted,
        searchText = listOf(step.replyText, step.reasoningText, step.model, step.provider).joinToString("\n"),
    )
}

private fun liveAssistantRow(key: String, reply: String, reasoning: String): TrajRow {
    val (title, toolOnly) = assistantTitle(reply, reasoning)
    return TrajRow(
        key = key,
        depth = 0,
        kind = TrajL.kindAssistant,
        title = title,
        think = "—",
        time = "…",
        running = true,
        detail = reply.takeIf { it.isNotBlank() }?.let { Item.Text(key, it, live = true) },
        thinkingDetail = reasoning.takeIf { it.isNotBlank() },
        outputDetail = reply.takeIf { it.isNotBlank() },
        rawDetail = listOfNotNull(
            reasoning.takeIf { it.isNotBlank() },
            reply.takeIf { it.isNotBlank() },
        ).joinToString("\n\n").ifBlank { null },
        toolCallOnly = toolOnly,
        searchText = "$reply\n$reasoning",
    )
}

/** Desktop `summarizeResult` one-line preview after `→`. */
private fun toolResultPreview(item: Item.Tool): String? {
    if (item.error != null) return item.error
    val result = item.result?.trim().orEmpty()
    if (result.isEmpty()) {
        return if (item.status == ToolStatus.Ok) TrajL.recordNoOutput else null
    }
    return trajectoryPreviewText(result, 96)
}

private fun toolRow(item: Item.Tool): TrajRow {
    val running = item.status == ToolStatus.Running || item.status == ToolStatus.AwaitingApproval
    val started = item.startedAt
    val ended = item.endedAt
    val durMs = when {
        started != null && ended != null -> (ended - started).coerceAtLeast(0)
        else -> null
    }
    val dur = when {
        durMs != null -> formatElapsed(durMs)
        running -> "…"
        else -> ""
    }
    val program = codeProgram(item.name, item.arguments, item.schemaJson)
    val title = when {
        program != null -> "$PTC_TOOL_NAME · ${program.description.replace(Regex("\\s+"), " ")}"
        else -> {
            val arg = primaryArg(item.name, item.arguments)?.take(48).orEmpty()
            if (arg.isEmpty()) item.name else "${item.name} · $arg"
        }
    }
    val outputDetail = when {
        item.error != null -> listOfNotNull(item.error, item.result).joinToString("\n")
        item.result != null -> item.result
        else -> null
    }
    val isSub = !item.parentCallId.isNullOrBlank()
    return TrajRow(
        item.key,
        depth = if (isSub) 2 else 1,
        kind = if (isSub) TrajL.kindSubtool else TrajL.kindTool,
        title = title,
        time = dur,
        running = running,
        detail = item,
        inputDetail = item.arguments.takeIf { it.isNotBlank() },
        outputDetail = outputDetail,
        schemaDetail = item.schemaJson,
        toolName = item.name,
        resultPreview = toolResultPreview(item),
        turn = item.turn,
        step = item.step,
        startedAt = started,
        timeSeconds = when {
            running && durMs == null -> null
            durMs != null -> durMs / 1000.0
            else -> null
        },
        isError = item.status == ToolStatus.Error,
        searchText = listOf(item.name, item.arguments, item.result, item.error, item.schemaJson, item.parentCallId).joinToString("\n"),
    )
}

private fun compactedRow(item: Item.Compacted): TrajRow {
    val running = item.status == CompactionStatus.Running
    val title = when {
        running -> TrajL.layoutCompacting
        item.interrupted -> TrajL.layoutCompactionInterrupted
        item.status == CompactionStatus.Error -> item.error ?: TrajL.layoutCompactionFailed
        !item.summary.isNullOrBlank() -> oneLinePreview(item.summary!!)
        else -> TrajL.layoutCompacted
    }
    val startedAt = item.startedAt
    val endedAt = item.endedAt
    val durMs = if (startedAt != null && endedAt != null) {
        (endedAt - startedAt).coerceAtLeast(0)
    } else null
    return TrajRow(
        key = item.key,
        depth = 0,
        kind = TrajL.kindCompacted,
        title = title,
        time = when {
            durMs != null -> formatElapsed(durMs)
            running -> "…"
            else -> ""
        },
        running = running,
        detail = item,
        outputDetail = item.summary,
        rawDetail = item.summary ?: item.error,
        turn = item.turn,
        startedAt = item.startedAt,
        timeSeconds = if (running) null else durMs?.let { it / 1000.0 },
        isError = item.status == CompactionStatus.Error,
        searchText = listOf(item.summary, item.error, item.compactionId).joinToString("\n"),
    )
}

private fun userTitle(item: Item.User): String {
    val parts = ArrayList<String>()
    if (item.images > 0) parts += TrajL.layoutImageCount(item.images)
    val preview = oneLinePreview(item.text, 80)
    if (preview.isNotEmpty()) parts += preview
    return parts.joinToString(" · ").ifEmpty { TrajL.recordNoContent }
}

private fun itemSeq(item: Item): Long? = when (item) {
    is Item.Text, is Item.Reasoning -> {
        val body = item.key.drop(1) // t$seq.$i / r$seq.$i
        body.substringBefore('.').toLongOrNull()
    }
    else -> null
}

/**
 * Fold chat [Item]s and settled [StepRecord]s into turn-grouped trajectory rows.
 * Each assistant message is one 助手 row (desktop message cell); reasoning is detail, not a row.
 * System / context injections mirror desktop ledger kinds.
 */
fun buildTrajectory(items: List<Item>, steps: List<StepRecord> = emptyList()): List<TrajTurn> {
    if (items.isEmpty()) return emptyList()
    val turns = ArrayList<TrajTurn>()
    var turnIdx = 0
    var rows = ArrayList<TrajRow>()
    val pendingPrefix = ArrayList<TrajRow>()
    val emitted = BooleanArray(steps.size)
    val stepBySeq = HashMap<Long, Int>().also { map ->
        steps.forEachIndexed { i, s -> map[s.seq] = i }
    }
    val stepByTool = HashMap<String, Int>().also { map ->
        steps.forEachIndexed { i, s -> s.toolCallIds.forEach { id -> map[id] = i } }
    }

    fun flush() {
        if (rows.isEmpty()) return
        turns += TrajTurn(turnIdx, rows)
        rows = ArrayList()
    }

    fun emitStep(i: Int) {
        if (i !in steps.indices || emitted[i]) return
        emitted[i] = true
        rows += assistantRow(steps[i])
    }

    fun emitBySeq(seq: Long?) {
        seq ?: return
        stepBySeq[seq]?.let(::emitStep)
    }

    fun addPrefixOrRow(row: TrajRow) {
        if (turnIdx == 0) pendingPrefix += row else rows += row
    }

    var liveAssistantKey: String? = null
    for (item in items) {
        when (item) {
            is Item.Deliverable, is Item.Changes -> Unit // chat cards; trajectory uses tool/present rows
            is Item.System -> addPrefixOrRow(
                TrajRow(
                    item.key, 0, TrajL.kindSystem, item.label,
                    detail = item, rawDetail = item.text,
                    previousText = item.previousText,
                    toolsJson = item.toolsJson,
                    previousToolsJson = item.previousToolsJson,
                    startedAt = item.at, timeSeconds = 0.0,
                    searchText = listOf(item.label, item.text, item.toolsJson, item.previousText).joinToString("\n"),
                ),
            )
            is Item.Context -> addPrefixOrRow(
                TrajRow(
                    item.key, 0, TrajL.kindContext, oneLinePreview(item.text),
                    detail = item, inputDetail = item.text, rawDetail = item.text,
                    messageSourceJson = item.messageSourceJson,
                    startedAt = item.at, timeSeconds = 0.0,
                    searchText = listOf(item.text, item.sourceKind, item.messageSourceJson).joinToString("\n"),
                ),
            )
            is Item.Compacted -> addPrefixOrRow(compactedRow(item))
            is Item.User -> {
                flush()
                turnIdx++
                liveAssistantKey = null
                if (pendingPrefix.isNotEmpty()) {
                    rows.addAll(pendingPrefix)
                    pendingPrefix.clear()
                }
                rows += TrajRow(
                    item.key, 0, TrajL.kindUser, userTitle(item),
                    time = formatElapsed(0),
                    inputDetail = item.text, rawDetail = item.text,
                    messageSourceJson = item.messageSourceJson,
                    turn = turnIdx,
                    startedAt = item.at,
                    timeSeconds = 0.0,
                    searchText = listOf(item.text, item.messageSourceJson).joinToString("\n"),
                )
            }
            is Item.Reasoning -> {
                if (item.live) {
                    val key = liveAssistantKey ?: item.key
                    liveAssistantKey = key
                    val idx = rows.indexOfLast { it.key == key && it.kind == TrajL.kindAssistant }
                    val row = liveAssistantRow(key, reply = "", reasoning = item.text)
                    if (idx >= 0) rows[idx] = row else rows += row
                } else {
                    emitBySeq(itemSeq(item))
                }
            }
            is Item.Text -> {
                if (item.live) {
                    val key = liveAssistantKey ?: item.key
                    liveAssistantKey = key
                    val prev = rows.lastOrNull { it.key == key && it.kind == TrajL.kindAssistant }
                    val reasoning = prev?.thinkingDetail.orEmpty()
                    val row = liveAssistantRow(key, reply = item.text, reasoning = reasoning)
                    val idx = rows.indexOfLast { it.key == key && it.kind == TrajL.kindAssistant }
                    if (idx >= 0) rows[idx] = row else rows += row
                } else {
                    val seq = itemSeq(item)
                    val stepIdx = seq?.let { stepBySeq[it] }
                    when {
                        stepIdx != null -> emitStep(stepIdx)
                        steps.isEmpty() -> {
                            val (title, toolOnly) = assistantTitle(item.text, "")
                            rows += TrajRow(
                                item.key, 0, TrajL.kindAssistant, title,
                                think = "—", detail = item, rawDetail = item.text, outputDetail = item.text,
                                toolCallOnly = toolOnly,
                                searchText = item.text,
                            )
                        }
                    }
                }
            }
            is Item.Tool -> {
                stepByTool[item.callId]?.let(::emitStep)
                rows += toolRow(item)
            }
            // Desktop has no invented Notice ledger rows; keep only if host still emits them.
            is Item.Notice -> rows += TrajRow(
                item.key, 0, TrajL.kindSystem, oneLinePreview(item.text, 80),
                detail = item, rawDetail = item.text, searchText = item.text,
            )
        }
    }
    if (pendingPrefix.isNotEmpty() && turnIdx == 0) {
        // Orphan system/context with no user turn yet — still show under 第 1 轮 (desktop firstVisibleTurn).
        turnIdx = 1
        rows.addAll(0, pendingPrefix)
        pendingPrefix.clear()
    }
    flush()
    return turns
}

/** Flatten turns into timeline cells (desktop layout → timeline projection). */
fun trajectoryTimelineCells(turns: List<TrajTurn>): List<TrajectoryTimelineCell> =
    turns.flatMap { turn ->
        turn.rows.map { row ->
            TrajectoryTimelineCell(
                key = row.key,
                kind = row.kind,
                label = row.title,
                turn = turn.index,
                startedAt = row.startedAt,
                timeSeconds = row.timeSeconds,
                isError = row.isError,
            )
        }
    }

/** Desktop search: trim → lowercase → whitespace-split → AND of includes. */
fun trajectorySearchMatches(turns: List<TrajTurn>, query: String): Set<String>? {
    val terms = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (terms.isEmpty()) return null
    val matches = LinkedHashSet<String>()
    for (turn in turns) {
        for (row in turn.rows) {
            val hay = buildString {
                append(row.kind); append('\n'); append(row.title); append('\n')
                append(row.input); append('\n'); append(row.output); append('\n')
                append(row.think); append('\n'); append(row.time); append('\n')
                append(row.searchText)
            }.lowercase()
            if (terms.all { term -> hay.contains(term) }) matches += row.key
        }
    }
    return matches
}

@Composable
fun TrajectoryView(st: ChatState) {
    val turns = remember(st.items, st.steps) { buildTrajectory(st.items, st.steps) }
    val requests = remember(st.items, st.steps) { buildTrajectoryRequests(st.items, st.steps) }
    val boundaryByRow = remember(requests, turns) {
        requests.mapNotNull { req ->
            requestBoundaryRowKey(req, turns)?.let { it to req }
        }.toMap()
    }
    if (!st.loaded) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = T.Accent, strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
        }
        return
    }
    if (turns.isEmpty() && !st.hasMore) {
        EmptyState(D.inspect, TrajL.emptyTrajectory)
        return
    }
    var selected by remember { mutableStateOf<TrajRow?>(null) }
    var selectedTurn by remember { mutableIntStateOf(0) }
    var selectedRequest by remember { mutableStateOf<TrajectoryRequest?>(null) }
    var actualDuration by remember { mutableStateOf(false) }
    var turnsCollapsed by remember { mutableStateOf(false) }
    var callsCollapsed by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val mode = if (actualDuration) TrajectoryTimelineMode.Duration else TrajectoryTimelineMode.Sequence
    val cells = remember(turns) { trajectoryTimelineCells(turns) }
    val timeline = remember(cells, mode) { deriveTrajectoryTimeline(cells, mode) }
    val searchMatches = remember(turns, searchQuery) { trajectorySearchMatches(turns, searchQuery) }
    val rowByKey = remember(turns) { turns.flatMap { t -> t.rows.map { it.key to (it to t.index) } }.toMap() }

    fun selectRow(row: TrajRow, turn: Int) {
        selectedRequest = null
        selected = row
        selectedTurn = turn
    }

    // Same arming as Chat: avoid auto-fetch on first composition when the list starts at top.
    var historyArmed by remember(st.sessionId) { mutableStateOf(false) }
    LaunchedEffect(listState) {
        snapshotFlow {
            listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
        }
            .distinctUntilChanged()
            .collect { (index, offset) ->
                if (index > 0 || offset > 0) historyArmed = true
            }
    }
    // Desktop: near-top scroll loads one earlier page; phone reuses Pocket.loadOlderHistory (history.page).
    LaunchedEffect(listState, st.hasMore, st.loadingOlder, st.loaded, historyArmed) {
        snapshotFlow {
            listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
        }
            .distinctUntilChanged()
            .collect { (index, offset) ->
                if (historyArmed && st.loaded && st.hasMore && !st.loadingOlder && index == 0 && offset < 48) {
                    historyArmed = false
                    Pocket.loadOlderHistory()
                }
            }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val showMetrics = maxWidth >= 600.dp
        Column(Modifier.fillMaxSize()) {
        TrajectoryToolbar(
            actualDuration = actualDuration,
            onActualDurationChange = { actualDuration = it },
            turnsCollapsed = turnsCollapsed,
            onToggleTurns = { turnsCollapsed = !turnsCollapsed },
            callsCollapsed = callsCollapsed,
            onToggleCalls = { callsCollapsed = !callsCollapsed },
            searchQuery = searchQuery,
            onSearchQueryChange = { searchQuery = it },
        )
        timeline?.let { model ->
            SwimlaneChart(model, mode, selectedKey = selected?.key) { key ->
                rowByKey[key]?.let { (row, turn) -> selectRow(row, turn) }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.End) {
            if (showMetrics) {
                Text(TrajL.columnInput, style = monoCap(), modifier = Modifier.width(44.dp))
                Text(TrajL.columnOutput, style = monoCap(), modifier = Modifier.width(40.dp))
                Text(TrajL.columnThink, style = monoCap(), modifier = Modifier.width(40.dp))
            }
            Text(TrajL.columnTime, style = monoCap(), modifier = Modifier.width(40.dp))
        }
        HorizontalDivider(color = T.Fill)
        LazyColumn(Modifier.weight(1f), state = listState) {
            if (st.hasMore || st.loadingOlder) {
                item(key = "history-load-earlier") {
                    HistoryLoadEarlierRow(
                        loading = st.loadingOlder,
                        enabled = st.hasMore && !st.loadingOlder,
                        onClick = { Pocket.loadOlderHistory() },
                    )
                }
            }
            if (turns.isEmpty()) {
                item(key = "empty-after-load") {
                    Text(
                        TrajL.emptyTrajectory,
                        fontSize = 13.sp,
                        color = T.Caption,
                        modifier = Modifier.padding(24.dp),
                    )
                }
            }
            turns.forEach { turn ->
                val visibleRows = turn.rows.filter { row ->
                    (searchMatches == null || row.key in searchMatches) &&
                        !(callsCollapsed && (row.kind == TrajL.kindTool || row.kind == TrajL.kindSubtool))
                }
                if (searchMatches != null && visibleRows.isEmpty()) return@forEach
                item(key = "turn-${turn.index}") {
                    Text(
                        TrajL.turnLabel(turn.index),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = T.Text3,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
                if (!turnsCollapsed) {
                    itemsIndexed(visibleRows, key = { _, r -> r.key }) { _, row ->
                        val boundary = boundaryByRow[row.key]
                        TrajRowView(
                            row = row,
                            hl = selected?.key == row.key,
                            requestBoundary = boundary,
                            requestSelected = selectedRequest != null && selectedRequest?.identity == boundary?.identity,
                            showMetrics = showMetrics,
                            onRequestClick = { req ->
                                selected = null
                                selectedRequest = req
                            },
                            onClick = { selectRow(row, turn.index) },
                        )
                    }
                }
            }
        }
        }
    }
    selected?.let { row ->
        TrajectoryDetailSheet(
            row = row,
            turnIndex = selectedTurn,
            parentRequest = requestForRow(row, requests),
            onOpenRequest = { req ->
                selected = null
                selectedRequest = req
            },
            onDismiss = { selected = null },
        )
    }
    selectedRequest?.let { req ->
        TrajectoryRequestSheet(
            request = req,
            onOpenResult = { key ->
                rowByKey[key]?.let { (row, turn) -> selectRow(row, turn) }
            },
            onDismiss = { selectedRequest = null },
        )
    }
}

/** Desktop history load-earlier control at the top of the loaded ledger window. */
@Composable
private fun HistoryLoadEarlierRow(loading: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (loading) {
            CircularProgressIndicator(
                color = T.Accent,
                strokeWidth = 2.dp,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(TrajL.historyLoadingEarlier, fontSize = 12.sp, color = T.Text2)
        } else {
            Text(TrajL.historyLoadEarlier, fontSize = 12.sp, color = T.Accent)
        }
    }
    HorizontalDivider(color = T.Fill.copy(alpha = 0.8f))
}

@Composable
private fun TrajectoryToolbar(
    actualDuration: Boolean,
    onActualDurationChange: (Boolean) -> Unit,
    turnsCollapsed: Boolean,
    onToggleTurns: () -> Unit,
    callsCollapsed: Boolean,
    onToggleCalls: () -> Unit,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
) {
    val actions: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ToolbarChip(actualDuration, TrajL.toolbarDuration, onClick = { onActualDurationChange(!actualDuration) }) {
                Icon(D.clock, null, Modifier.size(14.dp), tint = T.Text2)
            }
            ToolbarChip(turnsCollapsed, TrajL.toolbarTurns, onToggleTurns) {
                Text(if (turnsCollapsed) "⊞" else "⊟", fontSize = 14.sp, color = T.Text2)
            }
            ToolbarChip(callsCollapsed, TrajL.toolbarCalls, onToggleCalls) {
                Text(if (callsCollapsed) "⊞" else "⊟", fontSize = 14.sp, color = T.Text2)
            }
        }
    }
    val search: @Composable (Modifier) -> Unit = { modifier ->
        Row(
            modifier.heightIn(min = 48.dp)
                .background(T.Fill2, T.R8)
                .border(1.dp, T.Border, T.R8)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(D.search, null, tint = T.Caption, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(6.dp))
            BasicTextField(
                value = searchQuery,
                onValueChange = onSearchQueryChange,
                singleLine = true,
                textStyle = T.Mono.copy(fontSize = 13.sp),
                cursorBrush = SolidColor(T.Accent),
                modifier = Modifier.weight(1f).semantics { contentDescription = TrajL.toolbarSearch },
                decorationBox = { field ->
                    if (searchQuery.isEmpty()) Text(TrajL.toolbarSearchPlaceholder, fontSize = 13.sp, color = T.Caption)
                    field()
                },
            )
        }
    }
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        if (maxWidth < 440.dp) {
            Column {
                actions()
                search(Modifier.fillMaxWidth())
                Spacer(Modifier.height(4.dp))
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                actions()
                search(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ToolbarChip(pressed: Boolean, label: String, onClick: () -> Unit, icon: @Composable () -> Unit) {
    Box(
        Modifier.heightIn(min = 48.dp)
            .semantics { selected = pressed }
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier.border(1.dp, T.Border, T.R8)
                .background(if (pressed) T.Fill else Color.Transparent, T.R8)
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            icon()
            Text(label, fontSize = 12.sp, color = if (pressed) T.Ink else T.Text2)
        }
    }
}

/** Hit testing follows the painted lane as well as time, including zero-duration markers. */
internal fun trajectorySpanAt(
    model: TrajectoryTimelineModel,
    x: Float,
    y: Float,
    width: Float,
    minSpanWidth: Float,
    pointWidth: Float,
    topPadding: Float,
    laneHeight: Float,
): String? {
    if (x < 0f || x > width || y < topPadding || laneHeight <= 0f) return null
    val lane = ((y - topPadding) / laneHeight).toInt()
    if (lane !in 0..2) return null
    val domain = (model.end - model.start).takeIf { it > 0 } ?: 1.0
    for (index in model.spans.lastIndex downTo 0) {
        val span = model.spans[index]
        if (span.lane != lane) continue
        val left = ((span.start - model.start) / domain * width).toFloat()
        val rawWidth = ((span.end - span.start) / domain * width).toFloat()
        val spanWidth = if (span.end <= span.start || rawWidth < minSpanWidth) pointWidth else rawWidth
        if (x in left..(left + spanWidth)) return span.key
    }
    return null
}

/** Desktop three-lane overview: 输入 / 模型 / 工具. Tap a segment → select matching ledger row. */
@Composable
private fun SwimlaneChart(
    model: TrajectoryTimelineModel,
    mode: TrajectoryTimelineMode,
    selectedKey: String? = null,
    onSpanClick: (String) -> Unit = {},
) {
    val domain = (model.end - model.start).takeIf { it > 0 } ?: 1.0
    val density = LocalDensity.current
    val minSpanPx = with(density) { 2.dp.toPx() }
    val equalSpanPx = with(density) { 8.dp.toPx() }
    val labelWidth = 44.dp
    val plotHeight = 50.dp
    val scroll = rememberScrollState()
    // Fit phone width; allow horizontal scroll when many equal-width slots would be too cramped.
    val minContentWidth: Dp = if (mode == TrajectoryTimelineMode.Sequence && model.spans.size > 12) {
        (model.spans.size * 14).dp
    } else {
        0.dp
    }

    Row(
        Modifier.fillMaxWidth().height(plotHeight)
            .background(T.Fill2)
            .border(width = 0.5.dp, color = T.Border),
    ) {
        Column(
            Modifier.width(labelWidth).fillMaxHeight().padding(end = 3.dp),
            verticalArrangement = Arrangement.SpaceEvenly,
            horizontalAlignment = Alignment.End,
        ) {
            listOf(TrajL.columnInput, TrajL.columnModel, TrajL.columnTools).forEach { label ->
                Text(label, fontSize = 10.sp, color = T.Caption, lineHeight = 10.sp)
            }
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxHeight()) {
            val plotWidth = maxOf(minContentWidth, maxWidth)
            val widthPxTarget = with(density) { plotWidth.toPx() }
            Box(Modifier.fillMaxHeight().horizontalScroll(scroll)) {
                Canvas(
                    Modifier
                        .width(plotWidth)
                        .fillMaxHeight()
                        .pointerInput(model, mode, widthPxTarget, selectedKey) {
                            detectTapGestures { offset ->
                                trajectorySpanAt(
                                    model, offset.x, offset.y, size.width.toFloat(),
                                    minSpanPx, equalSpanPx, 7.dp.toPx(), 14.dp.toPx(),
                                )?.let(onSpanClick)
                            }
                        },
                ) {
                    val widthPx = size.width
                    // Turn boundaries
                    for (b in model.turnBoundaries) {
                        val x = ((b.time - model.start) / domain * widthPx).toFloat()
                        drawLine(T.Border, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
                    }
                    val topPad = 7.dp.toPx()
                    val laneH = 14.dp.toPx()
                    val barH = 8.dp.toPx()
                    for (span in model.spans) {
                        val left = ((span.start - model.start) / domain * widthPx).toFloat()
                        val rawW = ((span.end - span.start) / domain * widthPx).toFloat()
                        val point = span.end <= span.start || rawW < minSpanPx
                        val w = when {
                            point -> equalSpanPx
                            else -> maxOf(rawW, minSpanPx)
                        }
                        val gap = minOf(w * 0.08f, 1.dp.toPx())
                        val top = topPad + span.lane * laneH
                        val selected = selectedKey != null && span.key == selectedKey
                        drawRoundRect(
                            color = spanColor(span.kind, span.isError),
                            topLeft = Offset(left + gap, top),
                            size = Size(maxOf(w - gap * 2, minSpanPx), barH),
                            cornerRadius = CornerRadius(1.dp.toPx(), 1.dp.toPx()),
                            alpha = when {
                                selected -> 1f
                                span.isError -> 1f
                                span.kind == "message" || span.kind == "tool" || span.kind == "subtool" -> 1f
                                else -> 0.78f
                            },
                        )
                        if (selected) {
                            drawRoundRect(
                                color = T.Accent,
                                topLeft = Offset(left + gap, top - 1.dp.toPx()),
                                size = Size(maxOf(w - gap * 2, minSpanPx), barH + 2.dp.toPx()),
                                cornerRadius = CornerRadius(1.dp.toPx(), 1.dp.toPx()),
                                alpha = 0.25f,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** CSS color-mix(in srgb), not Compose's perceptual-space lerp. */
private fun trajectoryColorMix(first: Color, second: Color, firstWeight: Float): Color {
    val secondWeight = 1f - firstWeight
    return Color(
        first.red * firstWeight + second.red * secondWeight,
        first.green * firstWeight + second.green * secondWeight,
        first.blue * firstWeight + second.blue * secondWeight,
        first.alpha * firstWeight + second.alpha * secondWeight,
    )
}

/** Exact ui-trajectory semantic aliases, resolved in the active light/dark palette. */
private fun spanColor(kind: String, isError: Boolean): Color {
    val colors = ThemeState.colors
    if (isError) return colors.StateErrorPrimary
    return when (kind) {
        "user" -> colors.StateBusinessPrimary
        "context" -> trajectoryColorMix(colors.StateSuccessPrimary, colors.LabelSecondary, 0.68f)
        "message" -> trajectoryColorMix(colors.BrandPrimaryNewColorprimaryNewColor, colors.StateErrorSecondary, 0.60f)
        "tool", "subtool" -> colors.StateWarnLabel
        else -> colors.LabelSecondary
    }
}

@Composable
private fun TrajRowView(
    row: TrajRow,
    hl: Boolean,
    requestBoundary: TrajectoryRequest? = null,
    requestSelected: Boolean = false,
    onRequestClick: (TrajectoryRequest) -> Unit = {},
    onClick: () -> Unit,
    showMetrics: Boolean = true,
) {
    val statusGlyph = when {
        row.running -> "◌"
        row.isError -> "✗"
        else -> "✓"
    }
    val statusColor = when {
        row.running -> T.Accent
        row.isError -> T.Err
        else -> T.Ok
    }
    Row(
        Modifier.fillMaxWidth()
            .heightIn(min = 48.dp)
            .background(if (hl || requestSelected) T.Tint.copy(alpha = 0.45f) else Color.Transparent)
            .then(if (hl || requestSelected) Modifier.drawSelectedBar() else Modifier)
            .clickable(onClick = onClick)
            .padding(start = (12 + row.depth * 14).dp, end = 12.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(statusGlyph, fontSize = 12.sp, color = statusColor, modifier = Modifier.width(14.dp))
        if (requestBoundary != null) {
            // Desktop request-boundary control → phone chip opening Request inspector.
            Text(
                TrajL.requestLabel(requestBoundary.number),
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (requestSelected) T.Ink else T.Accent,
                maxLines = 1,
                modifier = Modifier
                    .background(if (requestSelected) T.Fill else T.Tint, RoundedCornerShape(3.dp))
                    .clickable { onRequestClick(requestBoundary) }
                    .padding(horizontal = 5.dp, vertical = 2.dp),
            )
            Spacer(Modifier.width(4.dp))
        }
        KindBadge(row.kind)
        Spacer(Modifier.width(6.dp))
        // Long-press selects title/preview/metrics; short tap still opens the detail sheet.
        Box(Modifier.weight(1f)) {
            SelectableOutput {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (row.toolCallOnly) TrajL.recordToolCallOnly else row.title,
                        style = T.Mono.copy(
                            fontSize = 13.sp,
                            color = if (row.toolCallOnly) T.Caption else T.Ink,
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    row.resultPreview?.let { preview ->
                        Text(" → ", style = T.Mono.copy(fontSize = 12.sp, color = T.Caption))
                        Text(
                            preview,
                            style = T.Mono.copy(fontSize = 12.sp, color = if (row.isError) T.Err else T.Text2),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                    }
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        SelectableOutput {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (showMetrics) {
                    Text(row.input, style = monoCap(), modifier = Modifier.width(44.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(row.output, style = monoCap(), modifier = Modifier.width(40.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(row.think, style = monoCap(), modifier = Modifier.width(40.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(row.time, style = monoCap(), modifier = Modifier.width(40.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
    HorizontalDivider(color = T.Fill.copy(alpha = 0.8f))
}

/** Desktop selection: thick accent bar on the left edge of the highlighted ledger row. */
private fun Modifier.drawSelectedBar(): Modifier = this.then(
    Modifier.drawBehind {
        drawRect(color = T.Accent, topLeft = Offset.Zero, size = Size(3.dp.toPx(), size.height))
    },
)

@Composable
private fun KindBadge(kind: String) {
    val colors = ThemeState.colors
    val (fg, bg) = remember(kind, colors) {
        when (kind) {
            TrajL.kindUser -> colors.StateSuccessPrimary to colors.StateSuccessTertiary
            TrajL.kindContext -> trajectoryColorMix(colors.StateSuccessPrimary, colors.LabelSecondary, 0.68f) to colors.StateSuccessTertiary
            TrajL.kindAssistant -> trajectoryColorMix(colors.BrandPrimaryNewColorprimaryNewColor, colors.StateErrorSecondary, 0.60f) to
                trajectoryColorMix(trajectoryColorMix(colors.BrandPrimaryNewColorprimaryNewColor, colors.StateErrorSecondary, 0.55f), colors.BgLayer1, 0.15f)
            TrajL.kindTool -> colors.StateWarnLabel to colors.StateWarnTertiary
            TrajL.kindSubtool -> trajectoryColorMix(colors.StateWarnLabel, colors.LabelTertiary, 0.62f) to
                trajectoryColorMix(colors.StateWarnTertiary, colors.BgLayer1, 0.58f)
            TrajL.kindSystem, TrajL.kindCompacted -> colors.LabelSecondary to colors.BgModulePlatform
            else -> colors.LabelTertiary to colors.BgModulePlatform
        }
    }
    Text(
        kind,
        fontSize = 10.sp,
        fontWeight = FontWeight.SemiBold,
        color = fg,
        maxLines = 1,
        modifier = Modifier
            .background(bg, RoundedCornerShape(3.dp))
            .padding(horizontal = 5.dp, vertical = 2.dp),
    )
}

@Composable
private fun Seg(on: Boolean, label: String, onClick: () -> Unit) {
    Text(
        label,
        fontSize = 12.sp,
        color = if (on) T.Ink else T.Text2,
        modifier = Modifier
            .background(T.Fill, RoundedCornerShape(10.dp))
            .padding(3.dp)
            .background(if (on) T.Surface else Color.Transparent, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

@Composable
private fun monoCap() = T.Mono.copy(fontSize = 11.sp, color = T.Caption)
