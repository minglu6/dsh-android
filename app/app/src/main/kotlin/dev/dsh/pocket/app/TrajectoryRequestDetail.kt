package dev.dsh.pocket.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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

/**
 * Phone adaptation of the desktop Request inspector (click request boundary).
 * Bottom sheet — Tokyo Night / Meslo; locales from desktop `tab.*` / `details.*`.
 */
@Composable
fun TrajectoryRequestSheet(
    request: TrajectoryRequest,
    onOpenResult: (resultKey: String) -> Unit = {},
    onDismiss: () -> Unit,
) {
    val tabs = remember(request) { trajRequestTabs(request) }
    var tab by remember(request.identity) { mutableStateOf(tabs.first()) }
    PocketSheet(onDismiss) {
        Column(Modifier.padding(horizontal = 22.dp)) {
            Text(
                when (request.purpose) {
                    TrajectoryRequest.Purpose.Compaction -> TrajL.requestLabelCompaction(request.number)
                    TrajectoryRequest.Purpose.Assistant -> TrajL.requestLabel(request.number)
                },
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                color = T.Ink,
            )
            Text(
                when (request.purpose) {
                    TrajectoryRequest.Purpose.Compaction -> {
                        val section = request.turn?.let { TrajL.turnLabel(it) } ?: TrajL.sectionBetweenTurns
                        TrajL.requestCompactionSection(section)
                    }
                    TrajectoryRequest.Purpose.Assistant -> {
                        val turn = request.turn?.let { TrajL.turnLabel(it) } ?: TrajL.sectionBetweenTurns
                        "$turn · ${request.group}"
                    }
                },
                fontSize = 13.sp,
                color = T.Text3,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        ScrollableTabRow(
            selectedTabIndex = tabs.indexOf(tab).coerceAtLeast(0),
            edgePadding = 22.dp,
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
                        Text(t.label, fontSize = 13.sp, color = if (tab == t) T.Accent else T.Text2, maxLines = 1, softWrap = false)
                    },
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        when (tab) {
            TrajRequestTab.Overview -> RequestOverview(request, tabs, onOpenResult) { tab = it }
            TrajRequestTab.Options -> RequestOptions(request.requestConfigJson)
            TrajRequestTab.Usage -> RequestUsage(request.usage, request.cumulativeUsage)
            TrajRequestTab.Timing -> RequestTiming(request)
        }
    }
}

@Composable
private fun RequestOverview(
    request: TrajectoryRequest,
    tabs: List<TrajRequestTab>,
    onOpenResult: (String) -> Unit,
    onOpen: (TrajRequestTab) -> Unit,
) {
    Column(Modifier.padding(horizontal = 22.dp)) {
        OverviewLine(
            TrajL.detailsStatus,
            trajectoryStatusLabel(request.status),
            valueColor = if (request.status == TrajectoryRecordState.Error) T.Err else T.Ink,
        )
        if (request.purpose == TrajectoryRequest.Purpose.Compaction) {
            OverviewLine(TrajL.detailsPurpose, TrajL.requestCompactionPurpose)
        }
        request.provider?.let { OverviewLine(TrajL.detailsProvider, it) }
        request.model?.let { OverviewLine(TrajL.detailsModel, it) }
        OverviewLine(TrajL.detailsToolCalls, "${request.toolCallCount}")
        if (request.subtoolCallCount > 0) {
            OverviewLine(TrajL.detailsSubtoolCalls, "${request.subtoolCallCount}")
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
            OverviewLine(label, shown, valueColor = if (request.status == TrajectoryRecordState.Error) T.Err else T.Ink)
        }
        request.retry?.let { retry ->
            val value = request.maxRetries?.let { TrajL.requestRetryProgress(retry, it) } ?: "$retry"
            OverviewLine(TrajL.detailsRetry, value)
        }
        request.retryDelayMs?.let {
            OverviewLine(TrajL.detailsRetryDelay, formatDurationMillis(it.toDouble()))
        }
        request.resultKey?.let { key ->
            OverviewLine(
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
            RequestOverviewSection(TrajL.tabOptions, onOpen = { onOpen(TrajRequestTab.Options) }) {
                Text(
                    prettyJson(request.requestConfigJson).lineSequence().take(4).joinToString("\n"),
                    style = T.Mono.copy(fontSize = 11.sp, color = T.Text2, lineHeight = 16.sp),
                    maxLines = 4,
                )
            }
        }
        RequestOverviewSection(TrajL.tabUsage, onOpen = { onOpen(TrajRequestTab.Usage) }) {
            UsagePreview(request.usage)
        }
        RequestOverviewSection(TrajL.tabTiming, onOpen = { onOpen(TrajRequestTab.Timing) }) {
            OverviewLine(TrajL.timingStarted, formatStartedAt(request.startedAt), compact = true)
            val durationSec = if (request.startedAt != null && request.completedAt != null) {
                (request.completedAt - request.startedAt).coerceAtLeast(0) / 1000.0
            } else null
            OverviewLine(
                TrajL.timingDuration,
                when {
                    request.status == TrajectoryRecordState.Running -> TrajL.statusPending
                    durationSec != null -> formatElapsedSeconds(durationSec)
                    else -> TrajL.timingNotAvailable
                },
                compact = true,
            )
        }
    }
}

@Composable
private fun RequestOptions(configJson: String?) {
    if (configJson.isNullOrBlank()) {
        SelectableOutput {
            Text(TrajL.optionsNotRecorded, fontSize = 14.sp, color = T.Text3, modifier = Modifier.padding(horizontal = 22.dp, vertical = 8.dp))
        }
        return
    }
    SelectableOutput {
        Column(Modifier.padding(horizontal = 22.dp)) {
            Text(TrajL.optionsJson, fontSize = 12.sp, color = T.Text3, modifier = Modifier.padding(bottom = 6.dp))
            Text(
                prettyJson(configJson).breakAnywhere(),
                style = T.Mono.copy(lineHeight = 20.sp),
                softWrap = true,
                modifier = Modifier.fillMaxWidth().clip(T.R12).background(T.Fill2).padding(14.dp),
            )
        }
    }
}

@Composable
private fun RequestUsage(usage: StepUsage?, cumulative: StepUsage?) {
    Column(Modifier.padding(horizontal = 22.dp)) {
        Text(TrajL.usageThisRequest, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = T.Text2)
        UsageBlock(usage)
        Spacer(Modifier.height(12.dp))
        Text(TrajL.usageSessionCumulative, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = T.Text2)
        UsageBlock(cumulative)
    }
}

@Composable
private fun UsagePreview(usage: StepUsage?) {
    if (usage == null || listOfNotNull(usage.inputTokens, usage.cacheReadTokens, usage.cacheWriteTokens, usage.outputTokens, usage.reasoningTokens).isEmpty()) {
        Text(TrajL.usageNotReported, fontSize = 12.sp, color = T.Text3)
        return
    }
    usage.inputTokens?.let { OverviewLine(TrajL.usageInput, TrajL.unitTokens(it), compact = true) }
    usage.outputTokens?.let { OverviewLine(TrajL.usageOutput, TrajL.unitTokens(it), compact = true) }
}

@Composable
private fun UsageBlock(usage: StepUsage?) {
    if (usage == null || listOfNotNull(usage.inputTokens, usage.cacheReadTokens, usage.cacheWriteTokens, usage.outputTokens, usage.reasoningTokens).isEmpty()) {
        Text(TrajL.usageNotReported, fontSize = 13.sp, color = T.Text3, modifier = Modifier.padding(top = 4.dp))
        return
    }
    usage.inputTokens?.let { OverviewLine(TrajL.usageInput, TrajL.unitTokens(it)) }
    usage.cacheReadTokens?.let { OverviewLine(TrajL.usageCached, TrajL.unitTokens(it)) }
    usage.cacheWriteTokens?.let { OverviewLine(TrajL.usageCacheCreated, TrajL.unitTokens(it)) }
    usage.outputTokens?.let { OverviewLine(TrajL.usageOutput, TrajL.unitTokens(it)) }
    usage.reasoningTokens?.let { OverviewLine(TrajL.usageReasoning, TrajL.unitTokens(it)) }
}

@Composable
private fun RequestTiming(request: TrajectoryRequest) {
    Column(Modifier.padding(horizontal = 22.dp)) {
        OverviewLine(TrajL.timingStarted, formatStartedAt(request.startedAt))
        val durationSec = if (request.startedAt != null && request.completedAt != null) {
            (request.completedAt - request.startedAt).coerceAtLeast(0) / 1000.0
        } else null
        OverviewLine(
            TrajL.timingDuration,
            when {
                request.status == TrajectoryRecordState.Running -> TrajL.statusPending
                durationSec != null -> formatElapsedSeconds(durationSec)
                else -> TrajL.timingNotAvailable
            },
        )
        OverviewLine(
            TrajL.timingSource,
            when {
                durationSec == null && request.status != TrajectoryRecordState.Running -> TrajL.timingNotAvailable
                request.status == TrajectoryRecordState.Running -> TrajL.timingSessionTimestampsRunning
                else -> TrajL.timingSessionTimestamps
            },
        )
    }
}

@Composable
private fun RequestOverviewSection(label: String, onOpen: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
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
private fun OverviewLine(
    label: String,
    value: String,
    valueColor: Color = T.Ink,
    compact: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    SelectableOutput {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = if (compact) 2.dp else 4.dp)
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, fontSize = 13.sp, color = T.Text3, modifier = Modifier.padding(end = 12.dp))
            Text(
                value.breakAnywhere(),
                fontSize = 13.sp,
                color = valueColor,
                softWrap = true,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
    }
}
