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
import dev.dsh.pocket.protocol.ToolStatus

/** One row in the Trajectory tab (desktop ui-trajectory cell kinds, simplified). */
data class TrajRow(
    val key: String,
    val depth: Int,
    val kind: String,
    val title: String,
    val input: String = "",
    val output: String = "",
    val time: String = "",
    val running: Boolean = false,
    val detail: Item? = null,
)

data class TrajTurn(val index: Int, val rows: List<TrajRow>)

/** Fold chat [Item]s into turn-grouped trajectory rows (no separate Host API). */
fun buildTrajectory(items: List<Item>): List<TrajTurn> {
    if (items.isEmpty()) return emptyList()
    val turns = ArrayList<TrajTurn>()
    var turnIdx = 0
    var rows = ArrayList<TrajRow>()
    var step = 0
    fun flush() {
        if (rows.isEmpty()) return
        turns += TrajTurn(turnIdx, rows)
        rows = ArrayList()
    }
    for (item in items) {
        when (item) {
            is Item.User -> {
                flush()
                turnIdx++
                step = 0
                rows += TrajRow(item.key, 0, "用户", item.text.replace('\n', ' ').take(80))
            }
            is Item.Reasoning -> {
                val t = item.durationMs?.let { "${"%.1f".format(it / 1000.0)}s" } ?: if (item.live) "…" else ""
                rows += TrajRow(item.key, 1, "思考", "reasoning", output = "", time = t, running = item.live, detail = item)
            }
            is Item.Tool -> {
                val dur = if (item.startedAt != null && item.endedAt != null)
                    "${"%.2f".format((item.endedAt!! - item.startedAt!!) / 1000.0)}s"
                else if (item.status == ToolStatus.Running || item.status == ToolStatus.AwaitingApproval) "…"
                else ""
                val arg = primaryArg(item.name, item.arguments)?.take(48).orEmpty()
                rows += TrajRow(
                    item.key, 1, "工具", "${item.name}${if (arg.isNotEmpty()) " $arg" else ""}",
                    time = dur, running = item.status == ToolStatus.Running || item.status == ToolStatus.AwaitingApproval,
                    detail = item,
                )
            }
            is Item.Text -> {
                step++
                rows += TrajRow(item.key, 0, "助手", "第 $step 步", running = item.live, detail = item)
            }
            is Item.Notice -> rows += TrajRow(item.key, 0, "系统", item.text.take(80), detail = item)
        }
    }
    flush()
    return turns
}

@Composable
fun TrajectoryView(st: ChatState, onTool: (Item.Tool) -> Unit) {
    val turns = remember(st.items) { buildTrajectory(st.items) }
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
