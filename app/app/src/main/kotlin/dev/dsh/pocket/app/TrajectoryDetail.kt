package dev.dsh.pocket.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dsh.pocket.protocol.TrajectoryRecordState
import dev.dsh.pocket.protocol.formatElapsedSeconds
import dev.dsh.pocket.protocol.formatStartedAt
import dev.dsh.pocket.protocol.parseToolSchema
import dev.dsh.pocket.protocol.trajectoryStatusLabel
import dev.dsh.pocket.protocol.trajectoryToolState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * Desktop TrajectoryTable inspector tab ids / labels (locales `tab.*`).
 * Tool rows: 概述 / 参数 / 结果 / Schema / 计时 — matching screenshot & TrajectoryTable.detailTabs.
 */
enum class TrajDetailTab(val label: String) {
    Overview("概述"),
    Input("参数"),
    Output("结果"),
    Schema("Schema"),
    Timing("计时"),
    Preview("预览"),
    Raw("原始内容"),
    Thinking("思考"),
}

/**
 * Phone adaptation of the desktop right-hand detail panel.
 * Opens as a themed [PocketSheet] (no AlertDialog / FLAG_SECURE).
 */
@Composable
fun TrajectoryDetailSheet(row: TrajRow, turnIndex: Int, onDismiss: () -> Unit) {
    val tabs = remember(row) { trajDetailTabs(row) }
    var tab by remember(row.key) { mutableStateOf(tabs.first()) }
    PocketSheet(onDismiss) {
        Row(
            Modifier.padding(horizontal = 22.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    trajDetailKindLabel(row.kind),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = T.Ink,
                )
                Text(
                    trajDetailLocation(row, turnIndex),
                    fontSize = 13.sp,
                    color = T.Text3,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        ScrollableTabRow(
            selectedTabIndex = tabs.indexOf(tab).coerceAtLeast(0),
            edgePadding = 12.dp,
            containerColor = Color.White,
            contentColor = T.Ink,
            divider = { HorizontalDivider(color = T.Fill) },
            indicator = { positions ->
                val i = tabs.indexOf(tab).coerceAtLeast(0)
                if (i in positions.indices) {
                    TabRowDefaults.SecondaryIndicator(
                        Modifier.tabIndicatorOffset(positions[i]),
                        color = T.Accent,
                        height = 2.dp,
                    )
                }
            },
        ) {
            tabs.forEach { t ->
                Tab(
                    selected = tab == t,
                    onClick = { tab = t },
                    text = {
                        Text(
                            t.label,
                            fontSize = 13.sp,
                            color = if (tab == t) T.Accent else T.Text2,
                            maxLines = 1,
                        )
                    },
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        when (tab) {
            TrajDetailTab.Overview -> TrajOverview(row, tabs) { tab = it }
            TrajDetailTab.Input -> TrajPayload(row.inputDetail, missing = "未捕获参数")
            TrajDetailTab.Output -> TrajPayload(row.outputDetail, missing = "未捕获结果", error = row.isError)
            TrajDetailTab.Schema -> TrajSchema(row.schemaDetail)
            TrajDetailTab.Timing -> TrajTiming(row)
            TrajDetailTab.Preview, TrajDetailTab.Raw -> TrajPayload(row.rawDetail, missing = "无内容")
            TrajDetailTab.Thinking -> TrajPayload(row.thinkingDetail, missing = "无内容")
        }
    }
}

/** Desktop detailTabs for tool vs markdown/system-ish rows. */
fun trajDetailTabs(row: TrajRow): List<TrajDetailTab> = when (row.kind) {
    "工具", "子工具" -> buildList {
        add(TrajDetailTab.Overview)
        if (row.inputDetail != null) add(TrajDetailTab.Input)
        if (row.outputDetail != null) add(TrajDetailTab.Output)
        add(TrajDetailTab.Schema)
        add(TrajDetailTab.Timing)
    }
    "助手" -> buildList {
        add(TrajDetailTab.Overview)
        if (row.thinkingDetail != null) add(TrajDetailTab.Thinking)
        if (row.rawDetail != null) add(TrajDetailTab.Raw)
        add(TrajDetailTab.Timing)
    }
    "系统" -> listOf(TrajDetailTab.Overview, TrajDetailTab.Raw)
    else -> listOf(TrajDetailTab.Overview, TrajDetailTab.Preview, TrajDetailTab.Raw)
}

fun trajDetailKindLabel(kind: String): String = when (kind) {
    "助手" -> "助手"
    "工具" -> "工具"
    "子工具" -> "子工具"
    "用户" -> "用户"
    "上下文" -> "上下文"
    "系统" -> "系统"
    else -> kind
}

/** Desktop header location: `第 {turn} 轮 · 第 {step} 步` (or turn-only / 消息). */
fun trajDetailLocation(row: TrajRow, turnIndex: Int): String {
    val turn = row.turn ?: turnIndex.takeIf { it > 0 }
    val turnLabel = turn?.let { "第 $it 轮" } ?: "轮次之间"
    val step = row.step
    return if (step != null) "$turnLabel · 第 $step 步" else turnLabel
}

fun trajRowState(row: TrajRow): TrajectoryRecordState = when (row.kind) {
    "工具", "子工具" -> trajectoryToolState(row.isError, hasOutput = row.outputDetail != null)
    else -> when {
        row.isError -> TrajectoryRecordState.Error
        row.running -> TrajectoryRecordState.Running
        else -> TrajectoryRecordState.Complete
    }
}

@Composable
private fun TrajOverview(row: TrajRow, tabs: List<TrajDetailTab>, onOpen: (TrajDetailTab) -> Unit) {
    val state = trajRowState(row)
    Column(Modifier.padding(horizontal = 22.dp)) {
        // Desktop overview: 层级 → 助手消息 for tool rows under an assistant message.
        if (row.kind == "工具" || row.kind == "子工具") {
            OverviewKv("层级", "助手消息")
        }
        OverviewKv("状态", trajectoryStatusLabel(state), valueColor = if (state == TrajectoryRecordState.Error) T.Err else T.Ink)
        if (row.kind == "助手") {
            if (row.input.isNotEmpty()) OverviewKv("输入", row.input)
            if (row.output.isNotEmpty()) OverviewKv("输出", row.output)
            if (row.think.isNotEmpty()) OverviewKv("思考", row.think)
        }
        Spacer(Modifier.height(8.dp))
        if (TrajDetailTab.Input in tabs) {
            OverviewSection("参数", onOpen = { onOpen(TrajDetailTab.Input) }) {
                TrajCodePreview(row.inputDetail?.let { prettyJson(it) } ?: "未捕获参数")
            }
        }
        if (TrajDetailTab.Output in tabs) {
            OverviewSection("结果", onOpen = { onOpen(TrajDetailTab.Output) }) {
                TrajCodePreview(row.outputDetail ?: "未捕获结果")
            }
        }
        if (TrajDetailTab.Schema in tabs) {
            OverviewSection("Schema", onOpen = { onOpen(TrajDetailTab.Schema) }) {
                val parsed = row.schemaDetail?.let { parseToolSchema(it) }
                if (parsed != null) {
                    Text(parsed.name, style = T.Mono.copy(fontWeight = FontWeight.SemiBold, fontSize = 13.sp))
                    Text(parsed.description, style = T.Mono.copy(color = T.Text2, fontSize = 12.sp, lineHeight = 18.sp))
                } else {
                    Text(if (row.schemaDetail.isNullOrBlank()) "Schema 不可用" else row.schemaDetail!!, style = T.Mono.copy(color = T.Text3, fontSize = 12.sp))
                }
            }
        }
        if (TrajDetailTab.Timing in tabs || row.kind == "助手") {
            OverviewSection("计时", onOpen = { if (TrajDetailTab.Timing in tabs) onOpen(TrajDetailTab.Timing) }) {
                OverviewKv("开始时间", formatStartedAt(row.startedAt), compact = true)
                OverviewKv("时长", formatElapsedSeconds(row.timeSeconds), compact = true)
            }
        }
        if (TrajDetailTab.Thinking in tabs && row.thinkingDetail != null) {
            OverviewSection("思考", onOpen = { onOpen(TrajDetailTab.Thinking) }) {
                TrajCodePreview(row.thinkingDetail!!)
            }
        }
        if ((TrajDetailTab.Raw in tabs || TrajDetailTab.Preview in tabs) && row.rawDetail != null && row.kind != "工具") {
            OverviewSection(if (TrajDetailTab.Preview in tabs) "预览" else "原始内容", onOpen = {
                onOpen(if (TrajDetailTab.Preview in tabs) TrajDetailTab.Preview else TrajDetailTab.Raw)
            }) {
                TrajCodePreview(row.rawDetail!!)
            }
        }
    }
}

@Composable
private fun OverviewKv(label: String, value: String, valueColor: Color = T.Ink, compact: Boolean = false) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = if (compact) 2.dp else 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, fontSize = 13.sp, color = T.Text3, modifier = Modifier.padding(end = 12.dp))
        Text(value, fontSize = 13.sp, color = valueColor, modifier = Modifier.weight(1f, fill = false))
    }
}

@Composable
private fun OverviewSection(label: String, onOpen: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 6.dp)
            .clip(T.R8).border(1.dp, T.Border, T.R8)
            .clickable(onClick = onOpen).padding(12.dp),
    ) {
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = T.Text2)
        Spacer(Modifier.height(6.dp))
        content()
    }
}

@Composable
private fun TrajPayload(value: String?, missing: String, error: Boolean = false) {
    if (value.isNullOrBlank()) {
        Text(missing, fontSize = 14.sp, color = T.Text3, modifier = Modifier.padding(horizontal = 22.dp, vertical = 8.dp))
        return
    }
    val shown = runCatching { prettyJson(value) }.getOrDefault(value)
    Text(
        shown,
        style = T.Mono.copy(lineHeight = 20.sp, color = if (error) T.Err else T.Ink),
        modifier = Modifier
            .padding(horizontal = 22.dp)
            .fillMaxWidth()
            .clip(T.R12)
            .background(if (error) T.Err.copy(alpha = 0.06f) else T.Fill2)
            .horizontalScroll(rememberScrollState())
            .padding(14.dp),
    )
}

@Composable
private fun TrajSchema(schemaDetail: String?) {
    if (schemaDetail.isNullOrBlank()) {
        Text("Schema 不可用", fontSize = 14.sp, color = T.Text3, modifier = Modifier.padding(horizontal = 22.dp, vertical = 8.dp))
        return
    }
    val parsed = parseToolSchema(schemaDetail)
    if (parsed == null) {
        TrajPayload(schemaDetail, missing = "Schema 不可用")
        return
    }
    Column(Modifier.padding(horizontal = 22.dp)) {
        Text(parsed.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = T.Ink)
        Text(parsed.description, fontSize = 13.sp, color = T.Text2, lineHeight = 20.sp, modifier = Modifier.padding(top = 4.dp, bottom = 10.dp))
        Text("参数", fontSize = 12.sp, color = T.Text3, modifier = Modifier.padding(bottom = 6.dp))
        Text(
            SchemaPrettyJson.encodeToString(JsonElement.serializer(), parsed.parameters),
            style = T.Mono.copy(lineHeight = 20.sp),
            modifier = Modifier.fillMaxWidth().clip(T.R12).background(T.Fill2)
                .horizontalScroll(rememberScrollState()).padding(14.dp),
        )
    }
}

private val SchemaPrettyJson = Json { prettyPrint = true; ignoreUnknownKeys = true }

@Composable
private fun TrajTiming(row: TrajRow) {
    Column(Modifier.padding(horizontal = 22.dp)) {
        OverviewKv("开始时间", formatStartedAt(row.startedAt))
        OverviewKv("时长", formatElapsedSeconds(row.timeSeconds))
        OverviewKv(
            "计时来源",
            when {
                row.timeSeconds == null -> "不可用"
                row.running -> "会话时间戳（运行中）"
                else -> "会话时间戳"
            },
        )
    }
}

@Composable
private fun TrajCodePreview(text: String) {
    Text(
        text,
        style = T.Mono.copy(fontSize = 12.sp, lineHeight = 18.sp, color = T.Ink),
        maxLines = 8,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth(),
    )
}
