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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dsh.pocket.protocol.Item
import dev.dsh.pocket.protocol.StepRecord
import dev.dsh.pocket.protocol.ToolStatus
import dev.dsh.pocket.protocol.TrajectoryTimelineCell
import dev.dsh.pocket.protocol.TrajectoryTimelineMode
import dev.dsh.pocket.protocol.TrajectoryTimelineModel
import dev.dsh.pocket.protocol.deriveTrajectoryTimeline

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

/** Desktop ledger preview: one-line ellipsis of injected / multi-line content. */
private fun oneLinePreview(text: String, max: Int = 96): String =
    text.replace('\n', ' ').replace(Regex("\\s+"), " ").trim().take(max)

private fun assistantTitle(reply: String, reasoning: String): String =
    firstLine(reply).ifEmpty { firstLine(reasoning) }.ifEmpty { "仅工具调用" }

private fun assistantRow(step: StepRecord): TrajRow {
    val start = step.stepStartTime
    val end = step.messageTime
    val elapsedMs = if (start != null && end != null) (end - start).coerceAtLeast(0) else null
    val elapsed = elapsedMs?.let { formatElapsed(it) }.orEmpty()
    val input = step.usage.inputTokens?.let { kfmt(it) }.orEmpty()
    val output = step.usage.outputTokens?.let { kfmt(it) }.orEmpty()
    val think = step.usage.reasoningTokens?.let { kfmt(it) } ?: "—"
    return TrajRow(
        key = "a${step.seq}",
        depth = 0,
        kind = "助手",
        title = assistantTitle(step.replyText, step.reasoningText),
        input = input,
        output = output,
        think = think,
        time = elapsed,
        detail = step.replyText.takeIf { it.isNotBlank() }?.let { Item.Text("a${step.seq}", it, live = false) },
        thinkingDetail = step.reasoningText.takeIf { it.isNotBlank() },
        outputDetail = step.replyText.takeIf { it.isNotBlank() },
        rawDetail = step.replyText.takeIf { it.isNotBlank() },
        turn = step.turn,
        step = step.step,
        startedAt = start,
        timeSeconds = elapsedMs?.let { it / 1000.0 },
        searchText = listOf(step.replyText, step.reasoningText, step.model, step.provider).joinToString("\n"),
    )
}

private fun liveAssistantRow(key: String, reply: String, reasoning: String): TrajRow =
    TrajRow(
        key = key,
        depth = 0,
        kind = "助手",
        title = assistantTitle(reply, reasoning),
        think = "—",
        time = "…",
        running = true,
        detail = reply.takeIf { it.isNotBlank() }?.let { Item.Text(key, it, live = true) },
        thinkingDetail = reasoning.takeIf { it.isNotBlank() },
        outputDetail = reply.takeIf { it.isNotBlank() },
        rawDetail = reply.takeIf { it.isNotBlank() },
        searchText = "$reply\n$reasoning",
    )

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
    val arg = primaryArg(item.name, item.arguments)?.take(48).orEmpty()
    val outputDetail = when {
        item.error != null -> listOfNotNull(item.error, item.result).joinToString("\n")
        item.result != null -> item.result
        else -> null
    }
    return TrajRow(
        item.key, 1, "工具", "${item.name}${if (arg.isNotEmpty()) " $arg" else ""}",
        time = dur, running = running, detail = item,
        inputDetail = item.arguments.takeIf { it.isNotBlank() },
        outputDetail = outputDetail,
        schemaDetail = item.schemaJson,
        turn = item.turn,
        step = item.step,
        startedAt = started,
        timeSeconds = durMs?.let { it / 1000.0 },
        isError = item.status == ToolStatus.Error,
        searchText = listOf(item.name, item.arguments, item.result, item.error, item.schemaJson).joinToString("\n"),
    )
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
            is Item.System -> addPrefixOrRow(
                TrajRow(
                    item.key, 0, "系统", item.label,
                    detail = item, rawDetail = item.text,
                    startedAt = item.at, timeSeconds = 0.0,
                    searchText = "${item.label}\n${item.text}",
                ),
            )
            is Item.Context -> addPrefixOrRow(
                TrajRow(
                    item.key, 0, "上下文", oneLinePreview(item.text),
                    detail = item, inputDetail = item.text, rawDetail = item.text,
                    startedAt = item.at, timeSeconds = 0.0,
                    searchText = item.text,
                ),
            )
            is Item.User -> {
                flush()
                turnIdx++
                liveAssistantKey = null
                if (pendingPrefix.isNotEmpty()) {
                    rows.addAll(pendingPrefix)
                    pendingPrefix.clear()
                }
                rows += TrajRow(
                    item.key, 0, "用户", oneLinePreview(item.text, 80),
                    time = formatElapsed(0),
                    inputDetail = item.text, rawDetail = item.text,
                    turn = turnIdx,
                    startedAt = item.at,
                    timeSeconds = 0.0,
                    searchText = item.text,
                )
            }
            is Item.Reasoning -> {
                if (item.live) {
                    val key = liveAssistantKey ?: item.key
                    liveAssistantKey = key
                    val idx = rows.indexOfLast { it.key == key && it.kind == "助手" }
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
                    val prev = rows.lastOrNull { it.key == key && it.kind == "助手" }
                    val reasoning = prev?.thinkingDetail.orEmpty()
                    val row = liveAssistantRow(key, reply = item.text, reasoning = reasoning)
                    val idx = rows.indexOfLast { it.key == key && it.kind == "助手" }
                    if (idx >= 0) rows[idx] = row else rows += row
                } else {
                    val seq = itemSeq(item)
                    val stepIdx = seq?.let { stepBySeq[it] }
                    when {
                        stepIdx != null -> emitStep(stepIdx)
                        steps.isEmpty() -> rows += TrajRow(
                            item.key, 0, "助手", firstLine(item.text).ifEmpty { "仅工具调用" },
                            think = "—", detail = item, rawDetail = item.text, outputDetail = item.text,
                            searchText = item.text,
                        )
                    }
                }
            }
            is Item.Tool -> {
                stepByTool[item.callId]?.let(::emitStep)
                rows += toolRow(item)
            }
            is Item.Notice -> rows += TrajRow(
                item.key, 0, "系统", item.text.take(80),
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
    if (!st.loaded) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            androidx.compose.material3.CircularProgressIndicator(color = T.Accent, strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
        }
        return
    }
    if (turns.isEmpty()) {
        EmptyState(D.inspect, "暂无轨迹")
        return
    }
    var selected by remember { mutableStateOf<TrajRow?>(null) }
    var selectedTurn by remember { mutableIntStateOf(0) }
    var actualDuration by remember { mutableStateOf(false) }
    var turnsCollapsed by remember { mutableStateOf(false) }
    var callsCollapsed by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    val mode = if (actualDuration) TrajectoryTimelineMode.Duration else TrajectoryTimelineMode.Sequence
    val cells = remember(turns) { trajectoryTimelineCells(turns) }
    val timeline = remember(cells, mode) { deriveTrajectoryTimeline(cells, mode) }
    val searchMatches = remember(turns, searchQuery) { trajectorySearchMatches(turns, searchQuery) }
    val rowByKey = remember(turns) { turns.flatMap { t -> t.rows.map { it.key to (it to t.index) } }.toMap() }

    fun selectRow(row: TrajRow, turn: Int) {
        selected = row
        selectedTurn = turn
    }

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
            Text("输入", style = monoCap(), modifier = Modifier.width(44.dp))
            Text("输出", style = monoCap(), modifier = Modifier.width(40.dp))
            Text("思考", style = monoCap(), modifier = Modifier.width(40.dp))
            Text("时间", style = monoCap(), modifier = Modifier.width(40.dp))
        }
        HorizontalDivider(color = T.Fill)
        LazyColumn(Modifier.weight(1f)) {
            turns.forEach { turn ->
                val visibleRows = turn.rows.filter { row ->
                    (searchMatches == null || row.key in searchMatches) &&
                        !(callsCollapsed && row.kind == "工具")
                }
                if (searchMatches != null && visibleRows.isEmpty()) return@forEach
                item(key = "turn-${turn.index}") {
                    // Desktop locales: turn.label →「第 {turn} 轮」
                    Text(
                        "第 ${turn.index} 轮",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = T.Text3,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
                if (!turnsCollapsed) {
                    itemsIndexed(visibleRows, key = { _, r -> r.key }) { _, row ->
                        TrajRowView(row, selected?.key == row.key) {
                            selectRow(row, turn.index)
                        }
                    }
                }
            }
        }
    }
    selected?.let { row ->
        TrajectoryDetailSheet(row, turnIndex = selectedTurn) { selected = null }
    }
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
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Seg(actualDuration, "时长") { onActualDurationChange(!actualDuration) }
        Spacer(Modifier.width(6.dp))
        ToolbarChip(turnsCollapsed, "轮次", onToggleTurns)
        Spacer(Modifier.width(6.dp))
        ToolbarChip(callsCollapsed, "调用", onToggleCalls)
        Spacer(Modifier.width(8.dp))
        Row(
            Modifier.weight(1f).height(32.dp)
                .background(T.Fill2, RoundedCornerShape(8.dp))
                .border(1.dp, T.Border, RoundedCornerShape(8.dp))
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Search, contentDescription = "搜索轨迹", tint = T.Caption, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
            Box(Modifier.weight(1f)) {
                if (searchQuery.isEmpty()) {
                    Text("搜索", fontSize = 12.sp, color = T.Caption)
                }
                BasicTextField(
                    value = searchQuery,
                    onValueChange = onSearchQueryChange,
                    singleLine = true,
                    textStyle = T.Mono.copy(fontSize = 12.sp),
                    cursorBrush = SolidColor(T.Accent),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun ToolbarChip(pressed: Boolean, label: String, onClick: () -> Unit) {
    Text(
        label,
        fontSize = 12.sp,
        color = if (pressed) T.Ink else T.Text2,
        modifier = Modifier
            .border(1.dp, T.Border, T.R8)
            .background(if (pressed) T.Fill else Color.Transparent, T.R8)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
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
            listOf("输入", "模型", "工具").forEach { label ->
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
                                val widthPx = size.width.toFloat().coerceAtLeast(1f)
                                val hit = model.spans.asReversed().firstOrNull { span ->
                                    val left = ((span.start - model.start) / domain * widthPx).toFloat()
                                    val rawW = ((span.end - span.start) / domain * widthPx).toFloat()
                                    val point = span.end <= span.start || rawW < minSpanPx
                                    val w = if (point) equalSpanPx else maxOf(rawW, minSpanPx)
                                    offset.x in left..(left + w)
                                }
                                hit?.let { onSpanClick(it.key) }
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
                                else -> 0.9f
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

/** Desktop timeline span colors (business / success / violet / warn). */
private fun spanColor(kind: String, isError: Boolean): Color {
    if (isError) return T.Err
    return when (kind) {
        "user" -> T.Accent
        "context" -> Color(0xFF4CAF6A) // success mixed toward secondary
        "message" -> Color(0xFF8B5CF6) // assistant violet
        "tool", "subtool" -> T.WarnBorder
        "system", "compacted" -> T.Text3
        else -> T.Text3
    }
}

@Composable
private fun TrajRowView(row: TrajRow, hl: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .background(if (hl) T.Tint.copy(alpha = 0.45f) else Color.Transparent)
            .then(if (hl) Modifier.drawSelectedBar() else Modifier)
            .clickable(onClick = onClick)
            .padding(start = (12 + row.depth * 14).dp, end = 12.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(if (row.running) "◌" else "✓", fontSize = 12.sp, color = if (row.running) T.Accent else T.Ok, modifier = Modifier.width(14.dp))
        KindBadge(row.kind)
        Spacer(Modifier.width(6.dp))
        Text(
            row.title,
            style = T.Mono.copy(fontSize = 13.sp, color = T.Ink),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(row.input, style = monoCap(), modifier = Modifier.width(44.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(row.output, style = monoCap(), modifier = Modifier.width(40.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(row.think, style = monoCap(), modifier = Modifier.width(40.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(row.time, style = monoCap(), modifier = Modifier.width(40.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
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
    val (fg, bg) = when (kind) {
        "用户" -> T.Accent to T.Tint
        "上下文" -> Color(0xFF3D8B55) to Color(0xFFE8F6EC)
        "助手" -> Color(0xFF7C3AED) to Color(0xFFF3E8FF)
        "工具" -> T.WarnText to T.WarnTint
        "系统" -> T.Text2 to T.Fill
        else -> T.Text3 to T.Fill
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
            .background(if (on) Color.White else Color.Transparent, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

@Composable
private fun monoCap() = T.Mono.copy(fontSize = 11.sp, color = T.Caption)
