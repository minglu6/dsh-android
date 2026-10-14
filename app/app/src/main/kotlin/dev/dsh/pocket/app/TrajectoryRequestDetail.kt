package dev.dsh.pocket.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dsh.pocket.protocol.StepUsage
import dev.dsh.pocket.protocol.TrajectoryRecordState
import dev.dsh.pocket.protocol.formatDurationMillis
import dev.dsh.pocket.protocol.formatElapsedSeconds
import dev.dsh.pocket.protocol.formatStartedAt
import dev.dsh.pocket.protocol.trajectoryStatusLabel

/** Desktop REQUEST_TABS: 概述 / 选项 / 用量 / 计时. */
enum class TrajRequestTab(val label: String) {
    Overview(TrajL.tabSummary),
    Options(TrajL.tabOptions),
    Usage(TrajL.tabUsage),
    Timing(TrajL.tabTiming),
}

fun trajRequestTabs(request: TrajectoryRequest): List<TrajRequestTab> = buildList {
    add(TrajRequestTab.Overview)
    if (request.requestConfigJson != null) add(TrajRequestTab.Options)
    add(TrajRequestTab.Usage)
    add(TrajRequestTab.Timing)
}

/** Desktop request inspector with a fixed header and independently scrolling body. */
@Composable
fun TrajectoryRequestSheet(
    request: TrajectoryRequest,
    onOpenResult: (resultKey: String) -> Unit = {},
    initialTab: TrajRequestTab = TrajRequestTab.Overview,
    assistantRow: TrajRow? = null,
    onDismiss: () -> Unit,
) {
    val tabs = remember(request) { trajRequestTabs(request) }
    var tab by remember(request.identity, initialTab) {
        mutableStateOf(initialTab.takeIf { it in tabs } ?: TrajRequestTab.Overview)
    }
    TrajectoryInspectorFrame(
        title = when (request.purpose) {
            TrajectoryRequest.Purpose.Compaction -> TrajL.requestLabelCompaction(request.number)
            TrajectoryRequest.Purpose.Assistant -> TrajL.requestLabel(request.number)
        },
        location = when (request.purpose) {
            TrajectoryRequest.Purpose.Compaction -> {
                val section = request.turn?.let { TrajL.turnLabel(it) } ?: TrajL.sectionBetweenTurns
                TrajL.requestCompactionSection(section)
            }
            TrajectoryRequest.Purpose.Assistant -> {
                val turn = request.turn?.let { TrajL.turnLabel(it) } ?: TrajL.sectionBetweenTurns
                "$turn · ${request.group}"
            }
        },
        onDismiss = onDismiss,
        tabs = {
            TrajectoryInspectorTabs(
                labels = tabs.map { it.label },
                selectedIndex = tabs.indexOf(tab).coerceAtLeast(0),
                onSelect = { tab = tabs[it] },
            )
        },
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            when (tab) {
                TrajRequestTab.Overview -> RequestOverview(request, tabs, assistantRow, onOpenResult) { tab = it }
                TrajRequestTab.Options -> RequestOptions(request.requestConfigJson)
                TrajRequestTab.Usage -> RequestUsage(request.usage, request.cumulativeUsage)
                TrajRequestTab.Timing -> RequestTiming(request, assistantRow)
            }
        }
    }
}

@Composable
private fun RequestOverview(
    request: TrajectoryRequest,
    tabs: List<TrajRequestTab>,
    assistantRow: TrajRow?,
    onOpenResult: (String) -> Unit,
    onOpen: (TrajRequestTab) -> Unit,
) {
    Column {
        TrajectoryOverviewLine(
            TrajL.detailsStatus,
            trajectoryStatusLabel(request.status),
            valueColor = if (request.status == TrajectoryRecordState.Error) T.Err else T.Ink,
        )
        if (request.purpose == TrajectoryRequest.Purpose.Compaction) {
            TrajectoryOverviewLine(TrajL.detailsPurpose, TrajL.requestCompactionPurpose)
        }
        request.provider?.let { TrajectoryOverviewLine(TrajL.detailsProvider, it) }
        request.model?.let { TrajectoryOverviewLine(TrajL.detailsModel, it) }
        TrajectoryOverviewLine(TrajL.detailsToolCalls, "${request.toolCallCount}")
        if (request.subtoolCallCount > 0) {
            TrajectoryOverviewLine(TrajL.detailsSubtoolCalls, "${request.subtoolCallCount}")
        }
        request.error?.let { err ->
            val label = if (request.status != TrajectoryRecordState.Error && request.retry != null) {
                TrajL.detailsLastAttemptError
            } else {
                TrajL.detailsError
            }
            val shown = when (request.errorCode) {
                "AUTH" -> TrajL.detailsFailureAuth
                "interrupted" -> TrajL.layoutCompactionInterrupted
                else -> err
            }
            TrajectoryOverviewLine(label, shown, valueColor = if (request.status == TrajectoryRecordState.Error) T.Err else T.Ink)
        }
        request.retry?.let { retry ->
            val value = request.maxRetries?.let { TrajL.requestRetryProgress(retry, it) } ?: "$retry"
            TrajectoryOverviewLine(TrajL.detailsRetry, value)
        }
        request.retryDelayMs?.let {
            TrajectoryOverviewLine(TrajL.detailsRetryDelay, formatDurationMillis(it.toDouble()))
        }
        request.resultKey?.let { key ->
            TrajectoryOverviewLine(
                TrajL.detailsResult,
                when (request.purpose) {
                    TrajectoryRequest.Purpose.Compaction -> TrajL.detailsCompacted
                    TrajectoryRequest.Purpose.Assistant -> TrajL.detailsAssistantMessage
                },
                valueColor = T.Accent,
                onClick = { onOpenResult(key) },
            )
        }
        Spacer(Modifier.height(8.dp))
        if (TrajRequestTab.Options in tabs && request.requestConfigJson != null) {
            TrajectoryOverviewSection(TrajL.tabOptions, onOpen = { onOpen(TrajRequestTab.Options) }) {
                Text(
                    prettyJson(request.requestConfigJson).lineSequence().take(4).joinToString("\n"),
                    style = T.Mono.copy(fontSize = 11.sp, color = T.Text2, lineHeight = 16.sp),
                    maxLines = 4,
                )
            }
        }
        TrajectoryOverviewSection(TrajL.tabUsage, onOpen = { onOpen(TrajRequestTab.Usage) }) {
            UsageBlock(request.usage)
        }
        TrajectoryOverviewSection(TrajL.tabTiming, onOpen = { onOpen(TrajRequestTab.Timing) }) {
            RequestTiming(request, assistantRow, preview = true)
        }
    }
}

@Composable
private fun RequestOptions(configJson: String?) {
    if (configJson.isNullOrBlank()) {
        SelectableOutput {
            Text(TrajL.optionsNotRecorded, fontSize = 13.sp, color = T.Text3)
        }
        return
    }
    SelectableOutput {
        Column {
            Text(TrajL.optionsJson, fontSize = 12.sp, color = T.Text3, modifier = Modifier.padding(bottom = 6.dp))
            Text(
                prettyJson(configJson).breakAnywhere(),
                style = T.Mono.copy(lineHeight = 20.sp),
                softWrap = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun RequestUsage(usage: StepUsage?, cumulative: StepUsage?) {
    Column {
        Text(TrajL.usageThisRequest, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = T.Text2)
        UsageBlock(usage)
        Spacer(Modifier.height(12.dp))
        Text(TrajL.usageSessionCumulative, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = T.Text2)
        UsageBlock(cumulative)
    }
}

@Composable
private fun UsageBlock(usage: StepUsage?) {
    if (usage == null || listOfNotNull(usage.inputTokens, usage.cacheReadTokens, usage.cacheWriteTokens, usage.outputTokens, usage.reasoningTokens).isEmpty()) {
        Text(TrajL.usageNotReported, fontSize = 13.sp, color = T.Text3, modifier = Modifier.padding(top = 4.dp))
        return
    }
    usage.inputTokens?.let { TrajectoryOverviewLine(TrajL.usageInput, TrajL.unitTokens(it)) }
    usage.cacheReadTokens?.let { TrajectoryOverviewLine(TrajL.usageCached, TrajL.unitTokens(it)) }
    usage.cacheWriteTokens?.let { TrajectoryOverviewLine(TrajL.usageCacheCreated, TrajL.unitTokens(it)) }
    usage.outputTokens?.let { TrajectoryOverviewLine(TrajL.usageOutput, TrajL.unitTokens(it)) }
    usage.reasoningTokens?.let { TrajectoryOverviewLine(TrajL.usageReasoning, TrajL.unitTokens(it)) }
}

@Composable
private fun RequestTiming(
    request: TrajectoryRequest,
    assistantRow: TrajRow?,
    preview: Boolean = false,
) {
    Column {
        if (request.purpose == TrajectoryRequest.Purpose.Assistant && assistantRow != null) {
            AssistantTimingRows(assistantRow)
        } else {
            TrajectoryOverviewLine(TrajL.timingStarted, formatStartedAt(request.startedAt))
            val durationSec = if (request.startedAt != null && request.completedAt != null) {
                (request.completedAt - request.startedAt).coerceAtLeast(0) / 1000.0
            } else null
            TrajectoryOverviewLine(
                TrajL.timingDuration,
                when {
                    request.status == TrajectoryRecordState.Running -> TrajL.statusPending
                    durationSec != null -> formatElapsedSeconds(durationSec)
                    else -> TrajL.timingNotAvailable
                },
            )
            if (!preview) {
                TrajectoryOverviewLine(
                    TrajL.timingSource,
                    when {
                        durationSec == null && request.status != TrajectoryRecordState.Running -> TrajL.timingNotAvailable
                        request.status == TrajectoryRecordState.Running -> TrajL.timingSessionTimestampsRunning
                        else -> TrajL.timingSessionTimestamps
                    },
                )
            }
        }
    }
}
