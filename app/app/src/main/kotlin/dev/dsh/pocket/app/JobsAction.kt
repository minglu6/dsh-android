package dev.dsh.pocket.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.dsh.pocket.protocol.arr
import dev.dsh.pocket.protocol.get
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonObject

internal fun jobText(zh: String, en: String) = if (Pocket.prefs.language == AppLanguage.En) en else zh

/** One foreground roster drives both the header count and the anchored list. */
@Composable
internal fun JobsAction(hostId: String?, sessionId: String, available: Boolean) {
    val state = remember(hostId, sessionId) { JobsPresentation() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(state, available, lifecycle) {
        if (!available) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            val token = state.activate()
            try {
                while (true) {
                    try {
                        val roster = Pocket.listJobs(sessionId)["jobs"].arr()
                            ?: error("Missing jobs roster")
                        val next = roster.map { BackgroundJob.parse(it as? JsonObject ?: error("Invalid job row"))
                            ?: error("Missing job id") }
                        coroutineContext.ensureActive()
                        state.receive(token, next)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        state.receive(token, null)
                    }
                    delay(3_000)
                }
            } finally {
                state.deactivate(token)
            }
        }
    }
    val phase = state.killPhase
    LaunchedEffect(state, phase) {
        if (phase == null || phase.state == JobKillState.Pending) return@LaunchedEffect
        delay(if (phase.state == JobKillState.Armed) 3_000 else 4_000)
        state.expireKill(phase)
    }
    if (!available || (!state.failed && state.visibleCount == 0)) return
    val liveCount = state.liveRows.size
    val en = Pocket.prefs.language == AppLanguage.En
    val label = when {
        state.failed -> jobText("任务状态不可用", "Job status unavailable")
        liveCount > 0 -> if (en) "$liveCount background ${if (liveCount == 1) "job" else "jobs"} running" else "$liveCount 个后台任务运行中"
        else -> if (en) "${state.visibleCount} background ${if (state.visibleCount == 1) "job" else "jobs"}" else "${state.visibleCount} 个后台任务"
    }
    Box {
        Row(Modifier.padding(end = 8.dp).clip(T.R4).clickable { state.open = !state.open }
            .testTag("jobsAction").semantics {
                contentDescription = label
                stateDescription = jobText(if (state.open) "已展开" else "已收起", if (state.open) "Expanded" else "Collapsed")
            }.heightIn(min = 28.dp).padding(horizontal = 2.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            if (liveCount > 0) JobStateDot("running")
            Text(label, color = if (state.failed) T.Err else T.Text3, fontSize = 12.sp, lineHeight = 18.sp, modifier = Modifier.padding(horizontal = 5.dp))
            Icon(D.chevronDown, null, tint = T.Text3, modifier = Modifier.size(12.dp).rotate(if (state.open) 180f else 0f))
        }
        if (state.open && state.active) HeaderPopup(500, 480, { state.open = false }) {
            JobsPopover(sessionId, state)
        }
    }
}

@Composable
private fun JobsPopover(sessionId: String, state: JobsPresentation) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val hasLive = state.liveRows.isNotEmpty()
    LaunchedEffect(hasLive) {
        if (!hasLive) return@LaunchedEffect
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    Column(Modifier.testTag("jobsPopover").verticalScroll(rememberScrollState()).padding(4.dp),
        verticalArrangement = Arrangement.spacedBy(1.dp)) {
        if (state.failed) Text(jobText("任务状态不可用", "Job status unavailable"), color = T.Err, fontSize = 12.sp, modifier = Modifier.padding(8.dp))
        if (hasLive) {
            Text(jobText("进行中", "Running"), color = T.Text3, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp))
            state.liveRows.forEach { job -> key(job.id) { JobRow(sessionId, job, state, now) } }
        }
        val settled = state.settledRows
        if (settled.isNotEmpty()) {
            if (hasLive) androidx.compose.material3.HorizontalDivider(color = T.Border, thickness = .5.dp, modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.clip(T.R4).clickable { state.settledOpen = !state.settledExpanded }
                    .testTag("jobsSettledToggle").semantics {
                        stateDescription = jobText(if (state.settledExpanded) "已展开" else "已收起", if (state.settledExpanded) "Expanded" else "Collapsed")
                    }.padding(horizontal = 4.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    Icon(D.chevronDown, null, tint = T.Text3, modifier = Modifier.size(12.dp).rotate(if (state.settledExpanded) 0f else -90f))
                    Text(jobText("已结束 ${settled.size}", "Finished ${settled.size}"), color = T.Text3, fontSize = 11.sp)
                }
                Spacer(Modifier.weight(1f))
                Text(jobText("清空", "Clear"), color = T.Text3, fontSize = 11.sp,
                    modifier = Modifier.clip(T.R4).clickable { state.clearSettled() }.testTag("jobsClear").padding(horizontal = 6.dp, vertical = 6.dp))
            }
            if (state.settledExpanded) settled.forEach { job -> key(job.id) { JobRow(sessionId, job, state, now) } }
        }
    }
}

@Composable
private fun JobRow(sessionId: String, job: BackgroundJob, state: JobsPresentation, now: Long) {
    val expanded = state.observedId == job.id
    val en = Pocket.prefs.language == AppLanguage.En
    Column(Modifier.testTag("jobItem:${job.id}")) {
        Row(Modifier.fillMaxWidth().clip(T.R8).then(if (job.live) Modifier.background(T.Fill2).padding(start = 2.dp, end = 8.dp, top = 4.dp, bottom = 4.dp) else Modifier),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.weight(1f).clip(T.R4).clickable(enabled = job.observable) { state.toggle(job.id) }
                .testTag("jobRow:${job.id}").semantics {
                    contentDescription = if (job.observable) jobText("${if (expanded) "收起" else "展开"} ${job.label} 的实时输出", "${if (expanded) "Hide" else "Show"} live output of ${job.label}") else job.label
                    stateDescription = jobStatusLabel(job.status)
                }.padding(horizontal = 7.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                JobStateDot(job.status)
                if (job.live) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(job.label, style = T.Mono.copy(fontSize = 12.sp), color = T.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            JobProvider(job.kind, true)
                            job.detail?.let { Text(it, color = T.Text3, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false)) }
                            JobDuration(job, now, en)
                        }
                    }
                } else {
                    JobProvider(job.kind, false)
                    Text(job.label, style = T.Mono.copy(fontSize = 12.sp), color = T.Text3, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text(job.detail ?: jobStatusLabel(job.status), color = T.Text3, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 140.dp))
                    JobDuration(job, now, en)
                }
                if (job.observable) Box(Modifier.size(20.dp).clip(T.R4).background(T.Surface), contentAlignment = Alignment.Center) {
                    Icon(D.chevronDown, null, tint = T.Text3, modifier = Modifier.size(12.dp).rotate(if (expanded) 180f else 0f))
                }
            }
            if (job.status == "running") JobStop(job, state) { callback -> Pocket.killJob(sessionId, job.id, callback) }
        }
        if (expanded) JobOutput(sessionId, job)
    }
}

@Composable
private fun JobProvider(kind: String, live: Boolean) {
    Text(kind, color = T.Text2, fontSize = 10.sp, lineHeight = 16.sp, maxLines = 1,
        modifier = Modifier.clip(T.R4).background(if (live) T.Surface else T.Fill2).padding(horizontal = 5.dp))
}

@Composable
private fun JobDuration(job: BackgroundJob, now: Long, en: Boolean) {
    job.duration(now, en)?.let { duration ->
        Text(duration, color = T.Text3, fontSize = 11.sp, maxLines = 1,
            modifier = Modifier.semantics { contentDescription = if (job.live) jobText("已运行 $duration", "Running for $duration") else jobText("耗时 $duration", "Took $duration") })
    }
}

internal fun jobStatusLabel(status: String) = when (status) {
    "running" -> jobText("运行中", "running")
    "stopping" -> jobText("正在停止", "stopping")
    "completed" -> jobText("已完成", "completed")
    "killed" -> jobText("已取消", "cancelled")
    "failed" -> jobText("已失败", "failed")
    else -> status
}

@Composable
private fun JobStateDot(status: String) {
    if (status == "running") SidebarActivityIndicator(SidebarActivity.Running, Modifier.size(14.dp))
    else {
        val color = when (status) {
            "completed" -> T.Ok
            "stopping", "killed" -> ThemeState.colors.StateWarnPrimary
            "failed" -> T.Err
            else -> ThemeState.colors.StateIdlePrimary
        }
        Canvas(Modifier.size(10.dp).semantics { contentDescription = jobStatusLabel(status) }) {
            drawCircle(color, radius = size.minDimension * .3f)
        }
    }
}

@Composable
private fun JobStop(job: BackgroundJob, state: JobsPresentation, request: ((Boolean) -> Unit) -> Unit) {
    val phase = state.killPhase?.takeIf { it.id == job.id }?.state
    val armed = phase == JobKillState.Armed
    val failed = phase == JobKillState.Failed
    val color = if (armed || failed) T.Err else T.Text2
    val label = when {
        armed -> jobText("再次点击确认停止", "Click again to confirm")
        failed -> jobText("停止失败", "Stop failed")
        else -> jobText("停止任务 ${job.label}", "Stop task ${job.label}")
    }
    Row(Modifier.heightIn(min = 28.dp).clip(T.R4).background(if (armed) T.Err.copy(alpha = .12f) else T.Surface)
        .clickable(enabled = phase != JobKillState.Pending) { state.pressKill(job.id, request) }
        .testTag("jobStop:${job.id}").semantics {
            contentDescription = label
            stateDescription = phase?.name ?: "Idle"
        }.padding(horizontal = 7.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Canvas(Modifier.size(10.dp)) { drawRect(color) }
        if (armed) Text(jobText("确认停止", "Confirm stop"), color = color, fontSize = 11.sp, maxLines = 1)
    }
}
