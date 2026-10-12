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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.math.round

/**
 * Desktop TrajectoryTable inspector tab ids / labels (locales `tab.*`).
 */
enum class TrajDetailTab(val label: String) {
    Overview(TrajL.tabSummary),
    Input(TrajL.tabPayload),
    Code(TrajL.codeSource),
    Output(TrajL.tabResult),
    RawOutput(TrajL.tabRawOutput),
    Schema(TrajL.tabSchema),
    Timing(TrajL.tabTiming),
    Preview(TrajL.tabPreview),
    Raw(TrajL.tabRaw),
    Thinking(TrajL.recordThinking),
    Source(TrajL.tabSource),
    Diff(TrajL.tabDiff),
    SystemPrompt(TrajL.tabSystemPrompt),
    Tools(TrajL.tabTools),
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
            TrajDetailTab.Input, TrajDetailTab.Code -> {
                val program = codeProgram(row.toolName, row.inputDetail, row.schemaDetail)
                if (program != null && tab == TrajDetailTab.Code) {
                    TrajCodeProgram(program)
                } else {
                    TrajPayload(row.inputDetail, missing = TrajL.recordNoPayload)
                }
            }
            TrajDetailTab.Output -> {
                val program = codeProgram(row.toolName, row.inputDetail, row.schemaDetail)
                if (program != null && row.outputDetail == null && row.running) {
                    Text(TrajL.codeRunning, fontSize = 14.sp, color = T.Text3, modifier = Modifier.padding(horizontal = 22.dp, vertical = 8.dp))
                } else {
                    TrajPayload(row.outputDetail, missing = TrajL.recordNoResult, error = row.isError)
                }
            }
            TrajDetailTab.RawOutput -> TrajPayload(row.rawDetail ?: row.outputDetail, missing = TrajL.recordNoOutput)
            TrajDetailTab.Schema -> TrajSchema(row.schemaDetail)
            TrajDetailTab.Timing -> TrajTiming(row)
            TrajDetailTab.Preview -> TrajPreview(row)
            TrajDetailTab.Raw -> TrajPayload(row.rawDetail, missing = TrajL.recordNoContent)
            TrajDetailTab.Thinking -> TrajPayload(row.thinkingDetail, missing = TrajL.recordNoContent)
            TrajDetailTab.Source -> TrajPayload(row.messageSourceJson, missing = TrajL.sourceNotRecorded)
            TrajDetailTab.Diff -> TrajSystemDiff(row)
            TrajDetailTab.SystemPrompt -> TrajPayload(row.rawDetail, missing = TrajL.recordSystemPromptMissing)
            TrajDetailTab.Tools -> TrajToolsCatalog(row.toolsJson)
        }
    }
}

/**
 * Desktop `detailTabs` — system / compacted / markdown / tool branches.
 */
fun trajDetailTabs(row: TrajRow): List<TrajDetailTab> = when (row.kind) {
    TrajL.kindSystem -> when {
        row.previousText != null -> listOf(TrajDetailTab.Diff, TrajDetailTab.SystemPrompt, TrajDetailTab.Tools)
        row.toolsJson != null -> listOf(TrajDetailTab.SystemPrompt, TrajDetailTab.Tools)
        else -> listOf(TrajDetailTab.SystemPrompt)
    }
    TrajL.kindCompacted -> listOf(TrajDetailTab.Overview, TrajDetailTab.RawOutput)
    TrajL.kindTool, TrajL.kindSubtool -> buildList {
        add(TrajDetailTab.Overview)
        val program = codeProgram(row.toolName, row.inputDetail, row.schemaDetail)
        if (row.inputDetail != null) {
            add(if (program != null) TrajDetailTab.Code else TrajDetailTab.Input)
        }
        if (row.outputDetail != null || program != null) add(TrajDetailTab.Output)
        add(TrajDetailTab.Schema)
        add(TrajDetailTab.Timing)
    }
    TrajL.kindAssistant -> buildList {
        add(TrajDetailTab.Overview)
        add(TrajDetailTab.Preview)
        add(TrajDetailTab.Raw)
        if (row.messageSourceJson != null) add(TrajDetailTab.Source)
        add(TrajDetailTab.Timing)
    }
    else -> buildList {
        // user / context (markdown records)
        add(TrajDetailTab.Overview)
        add(TrajDetailTab.Preview)
        add(TrajDetailTab.Raw)
        if (row.messageSourceJson != null) add(TrajDetailTab.Source)
    }
}

fun trajDetailKindLabel(kind: String): String = when (kind) {
    TrajL.kindAssistant -> TrajL.kindAssistant
    TrajL.kindTool -> TrajL.kindTool
    TrajL.kindSubtool -> TrajL.kindSubtool
    TrajL.kindUser -> TrajL.kindUser
    TrajL.kindContext -> TrajL.kindContext
    TrajL.kindSystem -> TrajL.kindSystem
    TrajL.kindCompacted -> TrajL.kindCompacted
    else -> kind
}

/** Desktop header location: `第 {turn} 轮 · 第 {step} 步` (or turn-only / 轮次之间). */
fun trajDetailLocation(row: TrajRow, turnIndex: Int): String {
    val turn = row.turn ?: turnIndex.takeIf { it > 0 }
    val turnLabel = turn?.let { TrajL.turnLabel(it) } ?: TrajL.sectionBetweenTurns
    val step = row.step
    return if (step != null) "$turnLabel · ${TrajL.groupStep(step)}" else turnLabel
}

fun trajRowState(row: TrajRow): TrajectoryRecordState = when (row.kind) {
    TrajL.kindTool, TrajL.kindSubtool -> trajectoryToolState(row.isError, hasOutput = row.outputDetail != null)
    TrajL.kindCompacted -> when {
        row.isError -> TrajectoryRecordState.Error
        row.running || row.timeSeconds == null -> TrajectoryRecordState.Running
        else -> TrajectoryRecordState.Complete
    }
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
        if (row.kind == TrajL.kindTool || row.kind == TrajL.kindSubtool) {
            OverviewKv(TrajL.detailsHierarchy, TrajL.detailsAssistantMessage)
        }
        OverviewKv(
            TrajL.detailsStatus,
            trajectoryStatusLabel(state),
            valueColor = if (state == TrajectoryRecordState.Error) T.Err else T.Ink,
        )
        if (row.kind == TrajL.kindAssistant) {
            OverviewKv(TrajL.usageTokens, row.output.ifEmpty { "—" }.let { if (it == "—") it else TrajL.unitTokens(parseKfmtApprox(it)) })
            if (row.think.isNotEmpty() && row.think != "—") {
                OverviewKv(TrajL.usageReasoning, "${row.think} tok")
            }
            if (row.input.isNotEmpty()) OverviewKv(TrajL.usageInput, row.input)
        }
        val program = codeProgram(row.toolName, row.inputDetail, row.schemaDetail)
        if (program != null) {
            OverviewKv(TrajL.codeSource, program.description)
        }
        Spacer(Modifier.height(8.dp))
        if (TrajDetailTab.Input in tabs || TrajDetailTab.Code in tabs) {
            val inputTab = if (TrajDetailTab.Code in tabs) TrajDetailTab.Code else TrajDetailTab.Input
            OverviewSection(inputTab.label, onOpen = { onOpen(inputTab) }) {
                TrajCodePreview(row.inputDetail?.let { prettyJson(it) } ?: TrajL.recordNoPayload)
            }
        }
        if (TrajDetailTab.Output in tabs) {
            OverviewSection(TrajL.tabResult, onOpen = { onOpen(TrajDetailTab.Output) }) {
                TrajCodePreview(row.outputDetail ?: TrajL.recordNoResult)
            }
        }
        if (TrajDetailTab.Schema in tabs) {
            OverviewSection(TrajL.tabSchema, onOpen = { onOpen(TrajDetailTab.Schema) }) {
                val parsed = row.schemaDetail?.let { parseToolSchema(it) }
                if (parsed != null) {
                    Text(parsed.name, style = T.Mono.copy(fontWeight = FontWeight.SemiBold, fontSize = 13.sp))
                    Text(parsed.description, style = T.Mono.copy(color = T.Text2, fontSize = 12.sp, lineHeight = 18.sp))
                } else {
                    Text(
                        if (row.schemaDetail.isNullOrBlank()) TrajL.recordSchemaUnavailable else row.schemaDetail!!,
                        style = T.Mono.copy(color = T.Text3, fontSize = 12.sp),
                    )
                }
            }
        }
        if (TrajDetailTab.Timing in tabs || row.kind == TrajL.kindAssistant) {
            OverviewSection(TrajL.tabTiming, onOpen = { if (TrajDetailTab.Timing in tabs) onOpen(TrajDetailTab.Timing) }) {
                OverviewKv(TrajL.timingStarted, formatStartedAt(row.startedAt), compact = true)
                OverviewKv(TrajL.timingDuration, formatElapsedSeconds(row.timeSeconds), compact = true)
                if (row.kind == TrajL.kindAssistant) {
                    OverviewKv(TrajL.timingTtft, formatTtft(row), compact = true)
                    OverviewKv(TrajL.timingGeneration, formatGeneration(row), compact = true)
                    OverviewKv(TrajL.timingThroughput, formatThroughput(row), compact = true)
                }
            }
        }
        if (TrajDetailTab.Preview in tabs && row.rawDetail != null) {
            OverviewSection(TrajL.tabPreview, onOpen = { onOpen(TrajDetailTab.Preview) }) {
                TrajCodePreview(row.rawDetail!!)
            }
        }
        if (TrajDetailTab.Thinking in tabs && row.thinkingDetail != null) {
            OverviewSection(TrajL.recordThinking, onOpen = { onOpen(TrajDetailTab.Thinking) }) {
                TrajCodePreview(row.thinkingDetail!!)
            }
        }
        if (TrajDetailTab.RawOutput in tabs) {
            OverviewSection(TrajL.tabRawOutput, onOpen = { onOpen(TrajDetailTab.RawOutput) }) {
                TrajCodePreview(row.rawDetail ?: row.outputDetail ?: TrajL.recordNoOutput)
            }
        }
    }
}

/** Best-effort reverse of [kfmt] for overview token unit labels. */
private fun parseKfmtApprox(s: String): Long {
    val t = s.trim().lowercase()
    if (t.endsWith("k")) return round(t.dropLast(1).toDoubleOrNull()?.times(1000.0) ?: 0.0).toLong()
    return t.toLongOrNull() ?: 0L
}

fun formatTtft(row: TrajRow): String {
    val start = row.startedAt ?: return TrajL.timingFirstTokenUnavailable
    val first = row.firstTokenTime ?: return TrajL.timingFirstTokenUnavailable
    return formatElapsedSeconds((first - start).coerceAtLeast(0) / 1000.0)
}

fun formatGeneration(row: TrajRow): String {
    val first = row.firstTokenTime ?: return TrajL.timingFirstTokenUnavailable
    val end = row.startedAt?.let { start ->
        row.timeSeconds?.let { (start + (it * 1000).toLong()) }
    } ?: return if (row.running) TrajL.statusPending else TrajL.timingNotAvailable
    return formatElapsedSeconds((end - first).coerceAtLeast(0) / 1000.0)
}

fun formatThroughput(row: TrajRow): String {
    val first = row.firstTokenTime ?: return TrajL.timingFirstTokenUnavailable
    if (row.running) return TrajL.statusPending
    val end = row.startedAt?.let { start ->
        row.timeSeconds?.let { (start + (it * 1000).toLong()) }
    } ?: return TrajL.timingNotAvailable
    val seconds = (end - first).coerceAtLeast(1) / 1000.0
    val tokens = row.output.toLongOrNull()
        ?: row.output.removeSuffix("k").toDoubleOrNull()?.times(1000)?.toLong()
        ?: return TrajL.timingNotAvailable
    val rate = tokens / seconds
    return TrajL.unitTokensPerSecond("%.1f".format(rate))
}

@Composable
private fun TrajPreview(row: TrajRow) {
    Column(Modifier.padding(horizontal = 22.dp)) {
        row.thinkingDetail?.takeIf { it.isNotBlank() }?.let { thinking ->
            Text(TrajL.recordThinking, fontSize = 12.sp, color = T.Text3, modifier = Modifier.padding(bottom = 4.dp))
            TrajPayload(thinking, missing = TrajL.recordNoContent)
            Spacer(Modifier.height(10.dp))
        }
        TrajPayload(row.outputDetail ?: row.rawDetail, missing = TrajL.recordNoContent)
    }
}

@Composable
private fun TrajCodeProgram(program: CodeProgram) {
    Column(Modifier.padding(horizontal = 22.dp)) {
        if (program.language != null) {
            Text(program.language, fontSize = 12.sp, color = T.Text3, modifier = Modifier.padding(bottom = 6.dp))
        }
        Text(
            program.source,
            style = T.Mono.copy(lineHeight = 20.sp),
            modifier = Modifier.fillMaxWidth().clip(T.R12).background(T.Fill2)
                .horizontalScroll(rememberScrollState()).padding(14.dp),
        )
    }
}

@Composable
private fun TrajSystemDiff(row: TrajRow) {
    Column(Modifier.padding(horizontal = 22.dp)) {
        Text(TrajL.recordSystemPrompt, fontSize = 12.sp, color = T.Text3, modifier = Modifier.padding(bottom = 6.dp))
        val prev = row.previousText.orEmpty()
        val curr = row.rawDetail.orEmpty()
        if (prev.isEmpty() && curr.isEmpty()) {
            Text(TrajL.recordSystemPromptMissing, fontSize = 14.sp, color = T.Text3)
        } else {
            TrajPayload("— previous —\n$prev\n\n— current —\n$curr", missing = TrajL.recordNoContent)
        }
        val addedRemoved = toolCatalogDiff(row.previousToolsJson, row.toolsJson)
        if (addedRemoved != null) {
            Spacer(Modifier.height(12.dp))
            Text(TrajL.recordTools, fontSize = 12.sp, color = T.Text3, modifier = Modifier.padding(bottom = 6.dp))
            TrajPayload(addedRemoved, missing = TrajL.recordToolsMissing)
        }
    }
}

/** Simple name-set diff for system tools catalogs (desktop tools added/removed). */
fun toolCatalogDiff(previousJson: String?, currentJson: String?): String? {
    val prev = toolNames(previousJson)
    val curr = toolNames(currentJson)
    if (prev.isEmpty() && curr.isEmpty()) return null
    val added = curr - prev
    val removed = prev - curr
    if (added.isEmpty() && removed.isEmpty()) return null
    return buildString {
        if (added.isNotEmpty()) appendLine("新增：${added.sorted().joinToString(", ")}")
        if (removed.isNotEmpty()) append("移除：${removed.sorted().joinToString(", ")}")
    }.trim()
}

private fun toolNames(json: String?): Set<String> {
    if (json.isNullOrBlank()) return emptySet()
    val el = runCatching { PocketJsonish(json) }.getOrNull() ?: return emptySet()
    val arr = el as? JsonArray ?: return emptySet()
    return arr.mapNotNull { (it as? JsonObject)?.get("name")?.let { n -> (n as? JsonPrimitive)?.contentOrNull } }.toSet()
}

private fun PocketJsonish(s: String): JsonElement =
    Json.parseToJsonElement(s)

@Composable
private fun TrajToolsCatalog(toolsJson: String?) {
    if (toolsJson.isNullOrBlank()) {
        Text(TrajL.recordToolsMissing, fontSize = 14.sp, color = T.Text3, modifier = Modifier.padding(horizontal = 22.dp, vertical = 8.dp))
        return
    }
    TrajPayload(toolsJson, missing = TrajL.recordToolsMissing)
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
        Text(TrajL.recordSchemaUnavailable, fontSize = 14.sp, color = T.Text3, modifier = Modifier.padding(horizontal = 22.dp, vertical = 8.dp))
        return
    }
    val parsed = parseToolSchema(schemaDetail)
    if (parsed == null) {
        TrajPayload(schemaDetail, missing = TrajL.recordSchemaUnavailable)
        return
    }
    Column(Modifier.padding(horizontal = 22.dp)) {
        Text(parsed.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = T.Ink)
        Text(parsed.description, fontSize = 13.sp, color = T.Text2, lineHeight = 20.sp, modifier = Modifier.padding(top = 4.dp, bottom = 10.dp))
        Text(TrajL.recordParameters, fontSize = 12.sp, color = T.Text3, modifier = Modifier.padding(bottom = 6.dp))
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
        OverviewKv(TrajL.timingStarted, formatStartedAt(row.startedAt))
        if (row.kind == TrajL.kindAssistant) {
            OverviewKv(TrajL.timingTotalDuration, formatElapsedSeconds(row.timeSeconds))
            OverviewKv(TrajL.timingTtft, formatTtft(row))
            OverviewKv(TrajL.timingGeneration, formatGeneration(row))
            OverviewKv(TrajL.timingThroughput, formatThroughput(row))
        } else {
            OverviewKv(TrajL.timingDuration, formatElapsedSeconds(row.timeSeconds))
        }
        OverviewKv(
            TrajL.timingSource,
            when {
                row.timeSeconds == null && !row.running -> TrajL.timingNotAvailable
                row.running -> TrajL.timingSessionTimestampsRunning
                else -> TrajL.timingSessionTimestamps
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
