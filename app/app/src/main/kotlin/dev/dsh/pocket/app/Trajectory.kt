package dev.dsh.pocket.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dsh.pocket.protocol.Item
import dev.dsh.pocket.protocol.StepRecord
import dev.dsh.pocket.protocol.ToolStatus

/** One row in the Trajectory tab (desktop ui-trajectory cell kinds, simplified). */
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
)

data class TrajTurn(val index: Int, val rows: List<TrajRow>)

private fun firstLine(text: String): String =
    text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()

private fun assistantTitle(reply: String, reasoning: String): String =
    firstLine(reply).ifEmpty { firstLine(reasoning) }.ifEmpty { "仅工具调用" }

private fun assistantRow(step: StepRecord): TrajRow {
    val start = step.stepStartTime
    val end = step.messageTime
    val elapsed = if (start != null && end != null) formatElapsed((end - start).coerceAtLeast(0)) else ""
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
    )

private fun toolRow(item: Item.Tool): TrajRow {
    val running = item.status == ToolStatus.Running || item.status == ToolStatus.AwaitingApproval
    val started = item.startedAt
    val ended = item.endedAt
    val dur = when {
        started != null && ended != null -> formatElapsed((ended - started).coerceAtLeast(0))
        running -> "…"
        else -> ""
    }
    val arg = primaryArg(item.name, item.arguments)?.take(48).orEmpty()
    return TrajRow(
        item.key, 1, "工具", "${item.name}${if (arg.isNotEmpty()) " $arg" else ""}",
        time = dur, running = running, detail = item,
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
 */
fun buildTrajectory(items: List<Item>, steps: List<StepRecord> = emptyList()): List<TrajTurn> {
    if (items.isEmpty()) return emptyList()
    val turns = ArrayList<TrajTurn>()
    var turnIdx = 0
    var rows = ArrayList<TrajRow>()
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

    var liveAssistantKey: String? = null
    for (item in items) {
        when (item) {
            is Item.User -> {
                flush()
                turnIdx++
                liveAssistantKey = null
                rows += TrajRow(
                    item.key, 0, "用户", item.text.replace('\n', ' ').take(80),
                    time = formatElapsed(0),
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
                            think = "—", detail = item,
                        )
                    }
                }
            }
            is Item.Tool -> {
                stepByTool[item.callId]?.let(::emitStep)
                rows += toolRow(item)
            }
            is Item.Notice -> rows += TrajRow(item.key, 0, "系统", item.text.take(80), detail = item)
        }
    }
    flush()
    return turns
}

@Composable
fun TrajectoryView(st: ChatState, onTool: (Item.Tool) -> Unit) {
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
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Seg(true, "时长")
            Spacer(Modifier.width(6.dp))
            Text("轮次", fontSize = 12.sp, color = T.Text2, modifier = Modifier.border(1.dp, T.Border, T.R8).padding(horizontal = 10.dp, vertical = 6.dp))
            Spacer(Modifier.width(6.dp))
            Text("调用", fontSize = 12.sp, color = T.Text2, modifier = Modifier.border(1.dp, T.Border, T.R8).padding(horizontal = 10.dp, vertical = 6.dp))
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
                item(key = "turn-${turn.index}") {
                    Text("第 ${turn.index} 轮", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = T.Ink,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
                }
                itemsIndexed(turn.rows, key = { _, r -> r.key }) { _, row ->
                    TrajRowView(row, selected?.key == row.key) {
                        selected = row
                        (row.detail as? Item.Tool)?.let(onTool)
                    }
                }
            }
        }
    }
}

@Composable
private fun TrajRowView(row: TrajRow, hl: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .background(if (hl) T.Tint.copy(alpha = 0.45f) else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(onClick = onClick)
            .padding(start = (12 + row.depth * 14).dp, end = 12.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(if (row.running) "◌" else "✓", fontSize = 12.sp, color = if (row.running) T.Accent else T.Ok, modifier = Modifier.width(14.dp))
        Text(row.kind, fontSize = 11.sp, color = T.Text3, modifier = Modifier.width(28.dp))
        Text(row.title, fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = T.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text(row.input, style = monoCap(), modifier = Modifier.width(44.dp), maxLines = 1)
        Text(row.output, style = monoCap(), modifier = Modifier.width(40.dp), maxLines = 1)
        Text(row.think, style = monoCap(), modifier = Modifier.width(40.dp), maxLines = 1)
        Text(row.time, style = monoCap(), modifier = Modifier.width(40.dp), maxLines = 1)
    }
    HorizontalDivider(color = T.Fill.copy(alpha = 0.8f))
}

@Composable private fun Seg(on: Boolean, label: String) {
    Text(label, fontSize = 12.sp, color = if (on) T.Ink else T.Text2,
        modifier = Modifier.background(T.Fill, RoundedCornerShape(10.dp)).padding(3.dp)
            .background(if (on) androidx.compose.ui.graphics.Color.White else androidx.compose.ui.graphics.Color.Transparent, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp))
}

@Composable private fun monoCap() = androidx.compose.ui.text.TextStyle(
    fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = T.Caption,
)
