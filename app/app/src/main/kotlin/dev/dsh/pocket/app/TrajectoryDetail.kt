package dev.dsh.pocket.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.testTag
import java.util.Locale
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

/** Native end inspector using the desktop trajectory header, tabs and overview grid. */
@Composable
fun TrajectoryDetailSheet(
    row: TrajRow,
    turnIndex: Int,
    parentRequest: TrajectoryRequest? = null,
    onOpenRequest: (TrajectoryRequest) -> Unit = {},
    onOpenRequestTiming: (TrajectoryRequest) -> Unit = onOpenRequest,
    toolCalls: List<TrajRow> = emptyList(),
    onOpenTool: (TrajRow) -> Unit = {},
    onDismiss: () -> Unit,
) {
    val tabs = remember(row) { trajDetailTabs(row) }
    var tab by remember(row.key) { mutableStateOf(tabs.first()) }
    TrajectoryInspectorFrame(
        title = trajDetailKindLabel(row.kind),
        location = trajDetailLocation(row, turnIndex),
        kind = row.kind,
        onDismiss = onDismiss,
        tabs = {
            TrajectoryInspectorTabs(tabs.map { it.label }, tabs.indexOf(tab)) { tab = tabs[it] }
        },
    ) {
        Spacer(Modifier.height(8.dp))
        key(row.key) {
        when (tab) {
            TrajDetailTab.Overview -> TrajOverview(row, tabs, parentRequest, onOpenRequest, onOpenRequestTiming, toolCalls, onOpenTool) { tab = it }
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
                    Text(TrajL.codeRunning, fontSize = 14.sp, color = T.Text3, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
                } else {
                    TrajPayload(row.outputDetail, missing = TrajL.recordNoResult, error = row.isError)
                }
            }
            TrajDetailTab.RawOutput -> TrajPayload(row.rawDetail ?: row.outputDetail, missing = TrajL.recordNoOutput)
            TrajDetailTab.Schema -> TrajSchema(row.schemaDetail)
            TrajDetailTab.Timing -> TrajTiming(row)
            TrajDetailTab.Preview -> TrajPreview(row, toolCalls, onOpenTool)
            TrajDetailTab.Raw -> TrajPreview(row, toolCalls, onOpenTool, rendered = false)
            TrajDetailTab.Thinking -> TrajPayload(row.thinkingDetail, missing = TrajL.recordNoContent)
            TrajDetailTab.Source -> TrajPayload(row.messageSourceJson, missing = TrajL.sourceNotRecorded)
            TrajDetailTab.Diff -> TrajSystemDiff(row)
            TrajDetailTab.SystemPrompt -> TrajPayload(row.rawDetail, missing = TrajL.recordSystemPromptMissing)
            TrajDetailTab.Tools -> TrajToolsCatalog(row.toolsJson)
        }
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
private fun TrajOverview(
    row: TrajRow,
    tabs: List<TrajDetailTab>,
    parentRequest: TrajectoryRequest?,
    onOpenRequest: (TrajectoryRequest) -> Unit,
    onOpenRequestTiming: (TrajectoryRequest) -> Unit,
    toolCalls: List<TrajRow>,
    onOpenTool: (TrajRow) -> Unit,
    onOpen: (TrajDetailTab) -> Unit,
) {
    val state = trajRowState(row)
    Column(Modifier.padding(horizontal = 14.dp)) {
        if (parentRequest != null) {
            TrajectoryOverviewLine(
                if (row.kind == TrajL.kindAssistant) TrajL.detailsSource else TrajL.detailsHierarchy,
                requestLabel(parentRequest), valueColor = T.Text2,
                onClick = { onOpenRequest(parentRequest) },
            )
        } else if (row.kind == TrajL.kindTool || row.kind == TrajL.kindSubtool) {
            TrajectoryOverviewLine(TrajL.detailsHierarchy, TrajL.detailsAssistantMessage)
        }
        TrajectoryOverviewLine(TrajL.detailsStatus, trajectoryStatusLabel(state),
            valueColor = if (state == TrajectoryRecordState.Error) T.Err else T.Ink)
        if (row.kind == TrajL.kindAssistant) {
            TrajectoryOverviewLine(TrajL.usageTokens, row.outputTokens?.let(TrajL::unitTokens) ?: "—")
            row.reasoningTokens?.let { reasoning ->
                TrajectoryOverviewLine(TrajL.usageReasoning, TrajL.unitTokens(reasoning))
                row.outputTokens?.let { output ->
                    TrajectoryOverviewLine(TrajL.usageContent, TrajL.unitTokens((output - reasoning).coerceAtLeast(0)))
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        if (TrajDetailTab.Preview in tabs) {
            TrajectoryOverviewSection(TrajL.tabPreview, onOpen = { onOpen(TrajDetailTab.Preview) }) {
                TrajPreviewContent(row, toolCalls, onOpenTool, preview = true)
            }
        }
        if (TrajDetailTab.Input in tabs || TrajDetailTab.Code in tabs) {
            val inputTab = if (TrajDetailTab.Code in tabs) TrajDetailTab.Code else TrajDetailTab.Input
            TrajectoryOverviewSection(inputTab.label, onOpen = { onOpen(inputTab) }) {
                if (inputTab == TrajDetailTab.Code) {
                    TrajCodePreview(codeProgram(row.toolName, row.inputDetail, row.schemaDetail)?.source ?: row.inputDetail.orEmpty())
                } else TrajPayload(row.inputDetail, missing = TrajL.recordNoPayload, inset = false)
            }
        }
        if (TrajDetailTab.Output in tabs) {
            TrajectoryOverviewSection(TrajL.tabResult, onOpen = { onOpen(TrajDetailTab.Output) }) {
                TrajPayload(row.outputDetail, missing = TrajL.recordNoResult, error = row.isError, inset = false)
            }
        }
        if (TrajDetailTab.Schema in tabs) {
            TrajectoryOverviewSection(TrajL.tabSchema, onOpen = { onOpen(TrajDetailTab.Schema) }) {
                val parsed = row.schemaDetail?.let { parseToolSchema(it) }
                if (parsed != null) {
                    Text(parsed.name, style = T.Mono.copy(fontWeight = FontWeight.SemiBold, fontSize = 13.sp))
                    Text(parsed.description, style = T.Mono.copy(color = T.Text2, fontSize = 12.sp, lineHeight = 18.sp))
                } else Text(row.schemaDetail ?: TrajL.recordSchemaUnavailable, style = T.Mono.copy(color = T.Text3, fontSize = 12.sp))
            }
        }
        if (row.kind == TrajL.kindAssistant) {
            Spacer(Modifier.height(12.dp))
            TrajectoryOverviewSection(
                TrajL.timingRequest,
                onOpen = parentRequest?.let { { onOpenRequestTiming(it) } },
            ) {
                AssistantTimingRows(row)
            }
        } else if (TrajDetailTab.Timing in tabs) {
            TrajectoryOverviewSection(TrajL.tabTiming, onOpen = { onOpen(TrajDetailTab.Timing) }) {
                TrajectoryOverviewLine(TrajL.timingStarted, formatStartedAt(row.startedAt))
                TrajectoryOverviewLine(TrajL.timingDuration, formatElapsedSeconds(row.timeSeconds))
            }
        }
        if (TrajDetailTab.RawOutput in tabs) {
            TrajectoryOverviewSection(TrajL.tabRawOutput, onOpen = { onOpen(TrajDetailTab.RawOutput) }) {
                TrajPayload(row.rawDetail ?: row.outputDetail, missing = TrajL.recordNoOutput, inset = false)
            }
        }
    }
}

/** Desktop AssistantTimingPanel uses seconds from 1000ms, unlike tool timeline durations. */
internal fun formatAssistantDuration(seconds: Double?): String {
    if (seconds == null || !seconds.isFinite()) return TrajL.timingNotAvailable
    val value = seconds.coerceAtLeast(0.0)
    return if (value < 1) TrajL.unitMilliseconds(kotlin.math.round(value * 1000).toLong().toString())
        else String.format(Locale.ROOT, if (value < 10) "%.2f 秒" else "%.1f 秒", value)
}

fun formatTtft(row: TrajRow): String {
    val start = row.startedAt ?: return TrajL.timingFirstTokenUnavailable
    val first = row.firstTokenTime ?: return TrajL.timingFirstTokenUnavailable
    return formatAssistantDuration((first - start).coerceAtLeast(0) / 1000.0)
}

fun formatGeneration(row: TrajRow): String {
    val first = row.firstTokenTime ?: return TrajL.timingFirstTokenUnavailable
    val end = row.startedAt?.let { start ->
        row.timeSeconds?.let { (start + (it * 1000).toLong()) }
    } ?: return if (row.running) TrajL.statusPending else TrajL.timingNotAvailable
    return formatAssistantDuration((end - first).coerceAtLeast(0) / 1000.0)
}

fun formatThroughput(row: TrajRow): String {
    val first = row.firstTokenTime ?: return TrajL.timingFirstTokenUnavailable
    if (row.running) return TrajL.statusPending
    val end = row.startedAt?.let { start ->
        row.timeSeconds?.let { (start + (it * 1000).toLong()) }
    } ?: return TrajL.timingNotAvailable
    val seconds = (end - first) / 1000.0
    if (seconds <= 0) return "时长过短"
    val tokens = row.outputTokens ?: return TrajL.timingNotAvailable
    return TrajL.unitTokensPerSecond(String.format(Locale.ROOT, "%.1f", tokens / seconds))
}

@Composable
private fun TrajPreview(row: TrajRow, toolCalls: List<TrajRow>, onOpenTool: (TrajRow) -> Unit, rendered: Boolean = true) {
    Column(Modifier.padding(horizontal = 14.dp)) {
        TrajPreviewContent(row, toolCalls, onOpenTool, rendered = rendered)
    }
}

@Composable
private fun TrajPreviewContent(
    row: TrajRow,
    toolCalls: List<TrajRow>,
    onOpenTool: (TrajRow) -> Unit,
    preview: Boolean = false,
    rendered: Boolean = true,
) {
    var thinkingExpanded by remember(row.key) { mutableStateOf(false) }
    row.thinkingDetail?.takeIf { it.isNotBlank() }?.let { thinking ->
        Row(Modifier.heightIn(min = 24.dp).clickable { thinkingExpanded = !thinkingExpanded },
            verticalAlignment = Alignment.CenterVertically) {
            Icon(if (thinkingExpanded) D.chevronDown else D.chevronRight, null, Modifier.size(12.dp), tint = T.Text3)
            Text(TrajL.recordThinking, color = T.Text3, fontSize = 12.sp)
        }
        if (thinkingExpanded) {
            if (rendered) MarkdownView(thinking, bodyFontSize = 13.sp) else TrajCodePreview(thinking)
        }
    }
    val body = if (row.kind == TrajL.kindAssistant) row.outputDetail else row.rawDetail
    body?.takeIf { it.isNotBlank() }?.let {
        if (rendered) MarkdownView(it, bodyFontSize = 13.sp)
        else SelectableOutput { Text(it.breakAnywhere(), style = T.Mono.copy(fontSize = 12.sp)) }
    }
    toolCalls.forEach { call ->
        Row(
            Modifier.fillMaxWidth().heightIn(min = 24.dp)
                .testTag("trajectory-preview-${call.key}").clickable { onOpenTool(call) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(D.trajectoryTool, null, Modifier.size(13.dp), tint = T.Caption)
            Spacer(Modifier.width(4.dp))
            Text(call.title, style = T.Mono.copy(fontSize = 12.sp, color = T.Text3),
                maxLines = if (preview) 1 else Int.MAX_VALUE, overflow = TextOverflow.Ellipsis)
        }
    }
    if (body.isNullOrBlank() && row.thinkingDetail.isNullOrBlank() && toolCalls.isEmpty()) {
        Text(TrajL.recordNoContent, fontSize = 13.sp, color = T.Text3)
    }
}

@Composable
internal fun AssistantTimingRows(row: TrajRow) {
    TrajectoryOverviewLine(TrajL.timingStarted, formatStartedAt(row.startedAt))
    TrajectoryOverviewLine(TrajL.timingTotalDuration, if (row.running) TrajL.statusPending else formatAssistantDuration(row.timeSeconds))
    TrajectoryOverviewLine(TrajL.timingTtft, formatTtft(row))
    TrajectoryOverviewLine(TrajL.timingGeneration, formatGeneration(row))
    TrajectoryOverviewLine(TrajL.timingThroughput, formatThroughput(row))
}

@Composable
private fun TrajCodeProgram(program: CodeProgram) {
    SelectableOutput {
        Column(Modifier.padding(horizontal = 14.dp)) {
            if (program.language != null) {
                Text(program.language, fontSize = 12.sp, color = T.Text3, modifier = Modifier.padding(bottom = 6.dp))
            }
            Text(
                program.source.breakAnywhere(),
                style = T.Mono.copy(lineHeight = 20.sp),
                softWrap = true,
                modifier = Modifier.fillMaxWidth().clip(T.R12).background(T.Fill2).padding(14.dp),
            )
        }
    }
}

@Composable
private fun TrajSystemDiff(row: TrajRow) {
    Column(Modifier.padding(horizontal = 14.dp)) {
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
        SelectableOutput {
            Text(TrajL.recordToolsMissing, fontSize = 14.sp, color = T.Text3, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
        }
        return
    }
    TrajPayload(toolsJson, missing = TrajL.recordToolsMissing)
}


@Composable
private fun TrajPayload(value: String?, missing: String, error: Boolean = false, inset: Boolean = true) {
    if (value.isNullOrBlank()) {
        SelectableOutput {
            Text(missing, fontSize = 14.sp, color = T.Text3, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
        }
        return
    }
    TrajectoryJsonValue(value, modifier = Modifier.padding(horizontal = if (inset) 14.dp else 0.dp),
        error = error, collapsedStringLines = if (inset) 12 else 3)
}


@Composable
private fun TrajSchema(schemaDetail: String?) {
    if (schemaDetail.isNullOrBlank()) {
        Text(TrajL.recordSchemaUnavailable, fontSize = 14.sp, color = T.Text3, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
        return
    }
    val parsed = parseToolSchema(schemaDetail)
    if (parsed == null) {
        TrajPayload(schemaDetail, missing = TrajL.recordSchemaUnavailable)
        return
    }
    SelectableOutput {
        Column(Modifier.padding(horizontal = 14.dp)) {
            Text(parsed.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = T.Ink)
            Text(parsed.description, fontSize = 13.sp, color = T.Text2, lineHeight = 20.sp, modifier = Modifier.padding(top = 4.dp, bottom = 10.dp))
            Text(TrajL.recordParameters, fontSize = 12.sp, color = T.Text3, modifier = Modifier.padding(bottom = 6.dp))
            TrajectoryJsonValue(parsed.parameters.toString())
        }
    }
}


@Composable
private fun TrajTiming(row: TrajRow) {
    Column(Modifier.padding(horizontal = 14.dp)) {
        TrajectoryOverviewLine(TrajL.timingStarted, formatStartedAt(row.startedAt))
        TrajectoryOverviewLine(TrajL.timingDuration, formatElapsedSeconds(row.timeSeconds))
        TrajectoryOverviewLine(
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
    SelectableOutput {
        Text(
            text.breakAnywhere(),
            style = T.Mono.copy(fontSize = 12.sp, lineHeight = 18.sp, color = T.Ink),
            softWrap = true,
            maxLines = 8,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
