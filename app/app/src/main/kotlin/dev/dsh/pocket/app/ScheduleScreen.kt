package dev.dsh.pocket.app

import android.animation.ValueAnimator
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dsh.pocket.protocol.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.json.*
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private fun scheduleFailure(error: Throwable): String {
    val raw = error.message ?: error.javaClass.simpleName
    val explanation = when (raw.substringBefore(':')) {
        "schedule_conflict" -> st("任务在编辑期间已发生变化，草稿已保留。请刷新后取消并重新编辑最新规则。", "This task changed while you were editing. Your draft is retained. Cancel and reopen after refresh to edit the latest rule.")
        "schedule_ended" -> st("已结束的任务为只读，无法修改时间", "Inactive tasks are read-only. Their timing cannot be edited.")
        "schedule_not_found" -> st("此任务当前不可用，草稿已保留。", "This task is no longer available. Your draft is retained.")
        "invalid_title" -> st("请输入不超过 120 个字符的任务名称", "Enter a task name of at most 120 characters.")
        "invalid_prompt" -> st("请输入任务内容", "Enter a task instruction.")
        "invalid_time_zone" -> st("请输入有效的 IANA 时区，例如 Asia/Shanghai。", "Enter a valid IANA time zone, for example Asia/Shanghai.")
        "invalid_cron" -> st("请输入 5 字段的 cron 表达式，例如 0 9 * * 1-5", "Enter a five-field cron expression, for example 0 9 * * 1-5.")
        "frequency_too_high" -> st("请输入至少 60 秒的整数秒间隔", "Enter an interval of at least 60 whole seconds.")
        "not_future" -> st("请选择未来的日期和时间。", "Choose a date and time in the future.")
        "invalid_rule" -> st("请输入有效的日期和时间，或检查运行时间字段。", "Enter a valid date and time, or check the timing fields.")
        "delivery_cursor_not_found" -> st("运行记录已更新，请刷新。", "Records were updated. Refresh to reload.")
        else -> null
    }
    return if (explanation == null) raw else "$explanation\n$raw"
}

private fun scheduleStamp(value: String?, zone: String? = null): String {
    if (value == null) return "—"
    return try {
        Instant.parse(value).atZone(ZoneId.of(zone ?: ZoneId.systemDefault().id))
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z"))
    } catch (_: Exception) { value }
}

@Composable
private fun scheduleClock(): Instant {
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(Unit) {
        if (!ValueAnimator.areAnimatorsEnabled()) return@LaunchedEffect
        while (true) { delay(30_000); now = Instant.now() }
    }
    return now
}

private fun scheduleNext(value: String?, zone: String?, now: Instant): String {
    val distance = scheduleRelativeTime(value, now) ?: return scheduleStamp(value, zone)
    val unit = when (distance.unit) {
        "day" -> st("天", if (distance.value == 1L) "day" else "days")
        "hour" -> st("小时", if (distance.value == 1L) "hour" else "hours")
        "minute" -> st("分钟", if (distance.value == 1L) "minute" else "minutes")
        else -> st("秒", if (distance.value == 1L) "second" else "seconds")
    }
    val relative = when (distance.direction) {
        0 -> st("现在到期", "Due now")
        1 -> st("${distance.value}${unit}后", "in ${distance.value} $unit")
        else -> st("已逾期 ${distance.value}$unit", "${distance.value} $unit overdue")
    }
    return "${scheduleStamp(value, zone)} ($relative)"
}

private fun scheduleRuleLabel(kind: String): String = when (kind) {
    "once", "at", "after" -> st("仅一次", "Once")
    "daily" -> st("每天", "Every day")
    "weekdays" -> st("周一至周五", "Monday to Friday")
    "weekly" -> st("每周", "Weekly")
    "every" -> st("固定间隔", "Fixed interval")
    "cron" -> st("自定义", "Custom")
    else -> kind
}

private fun scheduleSummary(row: JsonObject): String {
    val kind = row["kind"].str().orEmpty()
    val zone = row["timeZone"].str()
    val rule = when (kind) {
        "every" -> row["everySeconds"].long()?.let { seconds ->
            val unitSeconds = when {
                seconds % 86_400L == 0L -> 86_400L
                seconds % 3_600L == 0L -> 3_600L
                seconds % 60L == 0L -> 60L
                else -> 1L
            }
            val amount = seconds / unitSeconds
            val unit = when (unitSeconds) {
                86_400L -> st("天", if (amount == 1L) "day" else "days")
                3_600L -> st("小时", if (amount == 1L) "hour" else "hours")
                60L -> st("分钟", if (amount == 1L) "minute" else "minutes")
                else -> st("秒", if (amount == 1L) "second" else "seconds")
            }
            st("每 $amount $unit", "Every $amount $unit")
        } ?: "—"
        "daily" -> "${scheduleRuleLabel(kind)} ${row["time"].str()}"
        "weekly" -> "${scheduleRuleLabel(kind)} ${row["weekdays"].arr()?.joinToString { scheduleWeekday(it.int() ?: 1) }} ${row["time"].str()}"
        "cron" -> row["expression"].str().orEmpty()
        else -> scheduleRuleLabel(kind)
    }
    return if (zone == null) rule else "$rule · $zone"
}

private fun scheduleWeekday(day: Int): String = if (Pocket.prefs.language == AppLanguage.En) {
    listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")[day - 1]
} else listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")[day - 1]

@Composable
private fun ScheduleButton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    OutlinedButton(onClick, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp), shape = RoundedCornerShape(6.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant)) { Text(label) }
}

@Composable
private fun ScheduleTab(label: String, selected: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(Modifier.heightIn(min = 48.dp).selectable(selected, role = Role.Tab, onClick = onClick),
        contentAlignment = Alignment.Center) {
        Text(label, modifier = Modifier.background(if (selected) cs.surfaceVariant else cs.background, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
            color = if (selected) cs.onSurface else cs.onSurfaceVariant, fontSize = 13.sp)
    }
}

@Composable
private fun ScheduleCatalogScaffold(onBack: () -> Unit, onNewTask: () -> Unit, canCreate: Boolean, content: @Composable ColumnScope.() -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize().background(cs.background).systemBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 6.dp, end = 18.dp).heightIn(min = 60.dp),
            verticalAlignment = Alignment.CenterVertically) {
            IconButton(onBack) { Icon(D.chevronLeft, st("返回", "Back")) }
            Text(st("自动化任务", "Automation tasks"), fontSize = 20.sp, modifier = Modifier.weight(1f))
            Button(onNewTask, enabled = canCreate, modifier = Modifier.heightIn(min = 48.dp),
                shape = RoundedCornerShape(8.dp), contentPadding = PaddingValues(horizontal = 12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = cs.onSurface, contentColor = cs.surface)) {
                Text("+ ${st("新建", "New")}", fontSize = 12.sp)
            }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), content = content)
    }
}

@Composable
private fun ScheduleCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 6.dp),
        shape = RoundedCornerShape(6.dp), border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
        color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

@Composable
private fun ScheduleField(label: String, value: String, enabled: Boolean, multiline: Boolean = false, onChange: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(value, onChange, enabled = enabled, singleLine = !multiline,
            minLines = if (multiline) 3 else 1, modifier = Modifier.fillMaxWidth().semantics { contentDescription = label }, shape = RoundedCornerShape(6.dp),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = MaterialTheme.colorScheme.onSurface,
                cursorColor = MaterialTheme.colorScheme.onSurface))
    }
}

@Composable
fun ScheduleSettingsScreen(onBack: () -> Unit, onNewTask: () -> Unit, onOpenSession: (String) -> Unit) {
    val profile by Pocket.active.collectAsState()
    key(profile) { ScheduleCatalogScreen(onBack, onNewTask, onOpenSession) }
}

@Composable
private fun ScheduleCatalogScreen(onBack: () -> Unit, onNewTask: () -> Unit, onOpenSession: (String) -> Unit) {
    val ops by Pocket.ops.collectAsState()
    val statuses by Pocket.status.collectAsState()
    val profile by Pocket.active.collectAsState()
    val catalogRevision by Pocket.scheduleCatalogRevision.collectAsState()
    val online = profile != null && statuses[profile] == Dot.Online
    val scope = rememberCoroutineScope()
    var rows by remember { mutableStateOf<List<JsonObject>>(emptyList()) }
    var selected by remember { mutableStateOf<JsonObject?>(null) }
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("all") }
    var loading by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    val now = scheduleClock()
    suspend fun reload() {
        if (loading || !online) return
        loading = true
        try {
            check("schedule.catalog" in Pocket.ops.value) { L.unavailable() }
            val client = Pocket.clientOrNull() ?: error(st("连接已断开，请重新连接。", "Disconnected. Reconnect to continue."))
            val result = client.scheduleCatalog()
            check(Pocket.active.value == profile && Pocket.clientOrNull() === client) { st("连接已变化，请重试。", "Connection changed. Retry.") }
            val catalog = result as? JsonArray ?: error("Invalid schedule catalog response")
            rows = catalog.map { it as? JsonObject ?: error("Invalid schedule catalog record") }
            loaded = true
            error = null
        } catch (e: CancellationException) { throw e } catch (e: Exception) { error = scheduleFailure(e) }
        finally { loading = false }
    }
    LaunchedEffect(online, ops, catalogRevision) {
        snapshotFlow { loading }.first { !it }
        reload()
    }
    val selectedRow = selected
    if (selectedRow != null) {
        val current = rows.firstOrNull { it["id"] == selectedRow["id"] && it["sessionId"] == selectedRow["sessionId"] }
        ScheduleDetailScreen(current ?: selectedRow, authoritative = current != null && loaded,
            online = online, catalogError = error, loading = loading,
            onBack = { selected = null }, onRefresh = { scope.launch { reload() } },
            onCommitted = { record ->
                rows = rows.map { if (it["id"] == record["id"]) record else it }
                selected = record
                scope.launch { reload() }
            }, onDeleted = {
                rows = rows.filterNot { it["id"] == selectedRow["id"] }
                selected = null
                notice = st("任务已删除", "Task deleted.")
                scope.launch { reload() }
            }, onOpenSession = onOpenSession)
        return
    }
    val visible = rows.filter { row ->
        (filter == "all" || row["status"].str() == filter) &&
            listOf("title", "prompt", "sessionId").any { row[it].str().orEmpty().contains(query.trim(), ignoreCase = true) }
    }.sortedBy { row -> try { Instant.parse(row["scheduledAt"].str()) } catch (_: Exception) { Instant.MAX } }
    ScheduleCatalogScaffold(onBack, onNewTask, online) {
        if (!online) Text(st("主机未连接，连接恢复后可继续操作。", "Host disconnected. Reconnect to continue."),
            Modifier.padding(horizontal = 18.dp, vertical = 10.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("all" to st("全部", "All"), "active" to st("已开启", "Enabled"), "inactive" to st("已结束", "Inactive")).forEach { (value, label) ->
                ScheduleTab(label, filter == value) { filter = value }
            }
            Spacer(Modifier.weight(1f))
            TextButton({ scope.launch { reload() } }, enabled = online && !loading && "schedule.catalog" in ops,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
                contentPadding = PaddingValues(horizontal = 4.dp)) { Text(st("刷新", "Refresh"), fontSize = 12.sp) }
        }
        Box(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 6.dp)
            .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 11.dp)) {
            if (query.isEmpty()) Text(st("搜索自动化任务", "Search automation tasks"),
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
            BasicTextField(query, { query = it }, singleLine = true,
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = st("搜索自动化任务", "Search automation tasks") },
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.onSurface))
        }
        notice?.let { Text(it, Modifier.padding(18.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        error?.let { ErrorOrEmpty(it, retryEnabled = online && !loading && "schedule.catalog" in ops) { scope.launch { reload() } } }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(18.dp), color = MaterialTheme.colorScheme.onSurface)
        if (loaded && visible.isEmpty()) {
            ErrorOrEmpty(when {
                filter == "inactive" && query.isBlank() -> st("没有已结束的自动化任务", "No inactive automation tasks")
                rows.isEmpty() -> st("还没有自动化任务，在会话中创建的任务会显示在这里", "No tasks yet. Tasks created in your sessions appear here.")
                else -> st("没有匹配的自动化任务", "No matching tasks")
            })
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                ScheduleButton(st("新建自动化任务", "New automation task"), online, onNewTask)
            }
        }
        visible.forEach { row ->
            Column(Modifier.fillMaxWidth().clickable { selected = row }.padding(horizontal = 24.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(row["title"].str().orEmpty(), fontSize = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(scheduleSummary(row), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(if (row["status"].str() == "active") st("下次计划时间：", "Next scheduled time: ") + scheduleNext(row["scheduledAt"].str(), row["timeZone"].str(), now)
                    else st("已结束", "Inactive"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            HorizontalDivider(Modifier.padding(horizontal = 18.dp), thickness = 0.5.dp)
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ScheduleDetailScreen(row: JsonObject, authoritative: Boolean, online: Boolean, catalogError: String?, loading: Boolean,
    onBack: () -> Unit, onRefresh: () -> Unit, onCommitted: (JsonObject) -> Unit, onDeleted: () -> Unit, onOpenSession: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val sessions by Pocket.sessions.collectAsState()
    val archived by Pocket.archivedIds.collectAsState()
    val ops by Pocket.ops.collectAsState()
    var tab by remember(row["id"]) { mutableStateOf(0) }
    var editing by remember(row["id"]) { mutableStateOf<JsonObject?>(null) }
    var title by remember(row["id"]) { mutableStateOf("") }
    var prompt by remember(row["id"]) { mutableStateOf("") }
    var initial by remember(row["id"]) { mutableStateOf(scheduleTimingDraft(row)) }
    var draft by remember(row["id"]) { mutableStateOf(initial) }
    var busy by remember { mutableStateOf(false) }
    var error by remember(row["id"]) { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf(false) }
    val now = scheduleClock()
    val id = row["id"].str().orEmpty()
    val sessionId = row["sessionId"].str().orEmpty()
    val isActive = row["status"].str() == "active"
    val changed = editing != null && (title.trim() != editing!!["title"].str() || prompt.trim() != editing!!["prompt"].str() || draft != initial)
    val leave = { if (!busy) { if (changed) confirmLeave = true else onBack() } }
    BackHandler(onBack = leave)
    fun save() {
        if (busy) return
        val expected = editing ?: return
        val request = try { scheduleUpdateRequest(expected, title, prompt, initial, draft) }
        catch (e: Exception) { error = scheduleFailure(e); return }
        busy = true
        error = null
        scope.launch {
            try {
                val client = Pocket.clientOrNull() ?: error(st("连接已断开，请重新连接。", "Disconnected. Reconnect to continue."))
                val updated = scheduleUpdatedRecord(client.scheduleUpdate(request))
                check(Pocket.clientOrNull() === client) { st("连接已变化，请刷新后重试。", "Connection changed. Refresh before retrying.") }
                editing = null
                onCommitted(JsonObject(row + updated + ("sessionId" to JsonPrimitive(sessionId))))
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                error = scheduleFailure(e)
                if (e.message?.startsWith("schedule_") == true) onRefresh()
            } finally { busy = false }
        }
    }
    CompositionLocalProvider(LocalContentColor provides T.Ink) {
    Column(Modifier.fillMaxSize().background(T.Surface2).systemBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(leave) { Icon(D.chevronLeft, st("返回", "Back"), tint = T.Ink) }
            Text(row["title"].str().orEmpty(), fontSize = 20.sp, color = T.Ink, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (editing != null) TextButton(::save, enabled = !busy && online && changed && authoritative && isActive && "schedule.update" in ops) {
                Text(if (busy) st("保存中…", "Saving…") else st("保存", "Save"))
            }
        }
        if (!online) SettingsMessage(st("远程主机未连接，需连接的操作暂不可用", "Host disconnected. Actions requiring a connection are unavailable."))
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf(st("规则", "Rules"), st("任务运行记录", "Records")).forEachIndexed { index, label ->
                ScheduleTab(label, tab == index) { tab = index }
            }
        }
        catalogError?.let { ErrorOrEmpty(it, retryEnabled = online && !loading && "schedule.catalog" in ops, onRetry = onRefresh) }
        if (!authoritative && !loading) ErrorOrEmpty(st("任务已不可用，可能已被删除", "Task unavailable. It may have been deleted."),
            retryEnabled = online && "schedule.catalog" in ops, onRetry = onRefresh)
        error?.let { ErrorOrEmpty(it, retryEnabled = online && !loading && "schedule.catalog" in ops, onRetry = onRefresh) }
        if (tab == 1) {
            ScheduleHistoryScreen(sessionId, id, row["timeZone"].str(), row["lastDelivery"]["messageId"].str(), online)
        } else {
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            ScheduleCard {
                Text(if (isActive) st("已开启", "Enabled") else st("已结束", "Inactive"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (editing != null) {
                    ScheduleField(st("自动化任务名称", "Automation task name"), title, online && !busy && authoritative && isActive && "schedule.update" in ops, onChange = { title = it })
                    ScheduleField(st("自动化任务内容", "Automation task instruction"), prompt, online && !busy && authoritative && isActive && "schedule.update" in ops, multiline = true, onChange = { prompt = it })
                } else {
                    Text(st("自动化任务内容", "Automation task instruction"), style = MaterialTheme.typography.labelMedium)
                    Text(row["prompt"].str().orEmpty())
                    ScheduleButton(st("编辑", "Edit"), online && authoritative && isActive && !loading && "schedule.update" in ops) {
                        editing = row
                        title = row["title"].str().orEmpty()
                        prompt = row["prompt"].str().orEmpty()
                        initial = scheduleTimingDraft(row)
                        draft = initial
                        error = null
                    }
                }
            }
            ScheduleCard {
                Text(st("运行时间", "Run time"), style = MaterialTheme.typography.titleMedium)
                if (editing != null) ScheduleTimingEditor(draft, online && !busy && authoritative && isActive && "schedule.update" in ops) { draft = it }
                else {
                    Text(scheduleSummary(row))
                    HorizontalDivider(thickness = 0.5.dp)
                    Text(st("下次计划时间", "Next scheduled time"), style = MaterialTheme.typography.labelMedium)
                    Text(if (isActive) scheduleNext(row["scheduledAt"].str(), row["timeZone"].str(), now) else "—")
                }
                if (editing != null) {
                    if (changed) Text(st("有未保存的修改", "Unsaved changes"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    ScheduleButton(if (busy) st("保存中…", "Saving…") else st("保存修改", "Save changes"),
                        !busy && online && changed && authoritative && isActive && "schedule.update" in ops, ::save)
                    ScheduleButton(st("取消", "Cancel"), !busy) { editing = null; error = null }
                }
            }
            ScheduleCard {
                val session = sessions.firstOrNull { it.sessionId == sessionId }
                val isArchived = session?.archived == true || sessionId in archived
                Text(st("关联会话", "Linked session"), style = MaterialTheme.typography.labelMedium)
                Text(session?.title ?: sessionId)
                when {
                    isArchived -> Text(st("原会话已归档。", "The original session is archived."), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    session == null -> Text(st("原会话当前不可用。", "The original session is unavailable."), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else -> ScheduleButton(st("打开原会话", "Open original session"), !busy && online && !changed) {
                        val latest = Pocket.sessions.value.firstOrNull { it.sessionId == sessionId }
                        if (latest != null && latest.archived != true && sessionId !in Pocket.archivedIds.value) onOpenSession(sessionId)
                        else error = st("原会话当前不可用或已归档。", "The original session is unavailable or archived.")
                    }
                }
            }
            Column(Modifier.padding(horizontal = 18.dp)) {
                ScheduleButton(st("删除任务", "Delete task"), !busy && online && authoritative && "schedule.delete" in ops) { confirmDelete = true }
            }
            Spacer(Modifier.height(24.dp))
            }
        }
    }
    }
    if (confirmLeave) AlertDialog(onDismissRequest = { confirmLeave = false }, title = { Text(st("有未保存的修改", "Unsaved changes")) },
        text = { Text(st("离开将放弃未保存的更改。", "Leaving discards unsaved changes.")) },
        confirmButton = { TextButton({ confirmLeave = false; onBack() }) { Text(st("放弃更改", "Discard changes")) } },
        dismissButton = { TextButton({ confirmLeave = false }) { Text(st("取消", "Cancel")) } })
    if (confirmDelete) AlertDialog(onDismissRequest = { if (!busy) confirmDelete = false },
        title = { Text(st("删除此任务？", "Delete this task?")) },
        text = { Text(st("任务将停止触发，并连同其已保存的任务运行记录一并删除。原会话及其消息仍然保留；已排队的消息不会被撤回。",
            "This stops the task from triggering and deletes it together with its saved delivery records. The original session and its messages remain; queued messages are not withdrawn.")) },
        confirmButton = { TextButton(enabled = !busy && online && authoritative && "schedule.delete" in ops, onClick = {
            if (!busy) {
                busy = true
                error = null
                scope.launch {
                    try {
                        val client = Pocket.clientOrNull() ?: error(st("连接已断开，请重新连接。", "Disconnected. Reconnect to continue."))
                        scheduleConfirmDeleted(client.scheduleDelete(sessionId, id))
                        check(Pocket.clientOrNull() === client) { st("连接已变化，请刷新。", "Connection changed. Refresh.") }
                        confirmDelete = false
                        onDeleted()
                    } catch (e: CancellationException) { throw e } catch (e: Exception) { error = scheduleFailure(e); confirmDelete = false }
                    finally { busy = false }
                }
            }
        }) { Text(if (busy) st("正在删除…", "Deleting…") else st("确认删除", "Confirm deletion")) } },
        dismissButton = { TextButton({ confirmDelete = false }, enabled = !busy) { Text(st("取消", "Cancel")) } })
}

@Composable
private fun ScheduleTimingEditor(draft: ScheduleTimingDraft, enabled: Boolean, onChange: (ScheduleTimingDraft) -> Unit) {
    val context = LocalContext.current
    var nativePicker by remember { mutableStateOf<android.app.Dialog?>(null) }
    DisposableEffect(Unit) { onDispose { nativePicker?.dismiss() } }
    LaunchedEffect(enabled) { if (!enabled) nativePicker?.dismiss() }
    var preciseClock by remember { mutableStateOf(false) }
    var commonZoneMenu by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var zoneExpanded by remember { mutableStateOf(false) }
    var unitError by remember { mutableStateOf<String?>(null) }
    val choices = listOf("weekly", "weekdays", "daily", "every-hour", "every-minute", "every-second", "once", "cron")
    fun choiceLabel(choice: String): String = when (choice) {
        "every-hour" -> st("每 N 小时", "Every N hours")
        "every-minute" -> st("每 N 分钟", "Every N minutes")
        "every-second" -> st("每 N 秒", "Every N seconds")
        else -> scheduleRuleLabel(choice)
    }
    Box {
        ScheduleButton(st("重复：", "Repeat: ") + choiceLabel(if (draft.kind == "every") "every-${draft.unit}" else draft.kind), enabled) { menu = true }
        DropdownMenu(menu, { menu = false }) {
            choices.forEach { choice -> DropdownMenuItem(text = { Text(choiceLabel(choice)) }, enabled = enabled, onClick = {
                menu = false
                unitError = null
                if (choice.startsWith("every-")) {
                    val unit = choice.removePrefix("every-")
                    val quantity = scheduleConvertQuantity(draft.quantity, draft.unit, unit)
                    if (quantity == null && draft.kind == "every") unitError = st("当前间隔无法精确换算，请保留秒单位或先修改间隔。", "This interval cannot be converted exactly. Keep seconds or change the interval first.")
                    else onChange(draft.copy(kind = "every", unit = unit, quantity = quantity ?: "1"))
                } else onChange(draft.copy(kind = choice))
            }) }
        }
    }
    unitError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    when (draft.kind) {
        "every" -> {
            ScheduleField(st("重复间隔", "Repeat every") + " (${when (draft.unit) { "hour" -> st("小时", "hours"); "minute" -> st("分钟", "minutes"); else -> st("秒", "seconds") }})",
                draft.quantity, enabled, onChange = { onChange(draft.copy(quantity = it)) })
            Text(st("至少 60 秒。间隔从任务创建或最近一次规则变更开始计算，与时区无关。", "At least 60 seconds. The interval counts from task creation or the last rule change and is independent of time zones."),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        "cron" -> ScheduleCronEditor(draft.expression, enabled) { onChange(draft.copy(expression = it)) }
        else -> {
            if (draft.kind == "once") {
                ScheduleButton(st("日期：", "Date: ") + draft.date, enabled) {
                    val date = try { LocalDate.parse(draft.date) } catch (_: Exception) { LocalDate.now() }
                    DatePickerDialog(context, { _, year, month, day ->
                        onChange(draft.copy(date = LocalDate.of(year, month + 1, day).toString()))
                    }, date.year, date.monthValue - 1, date.dayOfMonth).also { nativePicker = it }.show()
                }
            }
            ScheduleButton(st("时间：", "Time: ") + draft.time, enabled) {
                val time = try { LocalTime.parse(draft.time) } catch (_: Exception) { LocalTime.of(9, 0) }
                TimePickerDialog(context, { _, hour, minute ->
                    onChange(draft.copy(time = schedulePickedTime(draft.time, hour, minute)))
                }, time.hour, time.minute, true).also { nativePicker = it }.show()
            }
            TextButton({ preciseClock = !preciseClock }, enabled = enabled,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant)) {
                Text(st(if (preciseClock) "收起精确输入" else "精确输入（含秒和毫秒）",
                    if (preciseClock) "Hide precise input" else "Precise input (seconds and milliseconds)"), fontSize = 12.sp)
            }
            if (preciseClock) {
                if (draft.kind == "once") ScheduleField(st("日期", "Date") + " (YYYY-MM-DD)", draft.date, enabled, onChange = { onChange(draft.copy(date = it)) })
                ScheduleField(st("时间", "Time") + " (HH:mm:ss.SSS)", draft.time, enabled, onChange = { onChange(draft.copy(time = it)) })
            }
            if (draft.kind == "weekly") {
                Text(st("星期", "Weekday"), style = MaterialTheme.typography.labelMedium)
                // Two rows keep seven touch targets inside narrow phone screens.
                (1..7).toList().chunked(4).forEach { days ->
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        days.forEach { day -> FilterChip(day in draft.weekdays, {
                            if (day !in draft.weekdays || draft.weekdays.size > 1)
                                onChange(draft.copy(weekdays = if (day in draft.weekdays) draft.weekdays - day else draft.weekdays + day))
                        }, label = { Text(scheduleWeekday(day), fontSize = 12.sp) }, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp),
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                                selectedLabelColor = MaterialTheme.colorScheme.onSurface)) }
                    }
                }
            }
        }
    }
    if (draft.kind != "every") {
        Box {
            ScheduleButton(st("选择时区", "Choose time zone"), enabled) { commonZoneMenu = true }
            DropdownMenu(commonZoneMenu, { commonZoneMenu = false }) {
                (listOf(ZoneId.systemDefault().id, "UTC", "Asia/Shanghai", "Asia/Hong_Kong", "Asia/Tokyo",
                    "Asia/Singapore", "Europe/London", "Europe/Berlin", "America/New_York",
                    "America/Los_Angeles", "Australia/Sydney") + draft.zone).distinct().forEach { zone ->
                    DropdownMenuItem(text = { Text(zone) }, enabled = enabled, onClick = {
                        onChange(draft.copy(zone = zone)); commonZoneMenu = false; zoneExpanded = false
                    })
                }
            }
        }
        ScheduleField(st("时区", "Time zone") + " (IANA)", draft.zone, enabled, onChange = { zoneExpanded = true; onChange(draft.copy(zone = it)) })
        val zones = remember { (ZoneId.getAvailableZoneIds() + "UTC").filter { it == "UTC" || it.contains('/') }.sorted() }
        if (zoneExpanded && enabled) {
            zones.asSequence().filter { it.contains(draft.zone, ignoreCase = true) }.take(5).forEach { zone ->
                TextButton(onClick = { onChange(draft.copy(zone = zone)); zoneExpanded = false }) { Text(zone) }
            }
        }
        if (draft.kind == "once") Text(st("单次任务只保存目标时刻，不保存时区。日期和时间按所选时区解释。", "A one-time task stores only its target moment, not a time zone. The date and time are interpreted in the selected time zone."),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ScheduleCronEditor(expression: String, enabled: Boolean, onChange: (String) -> Unit) {
    val context = LocalContext.current
    val shape = remember(expression) { scheduleCronShape(expression) }
    var advanced by remember { mutableStateOf(shape == null) }
    var menu by remember { mutableStateOf(false) }
    var picker by remember { mutableStateOf<android.app.Dialog?>(null) }
    DisposableEffect(Unit) { onDispose { picker?.dismiss() } }
    LaunchedEffect(enabled) { if (!enabled) picker?.dismiss() }
    fun label(kind: String) = when (kind) {
        "minutely" -> st("每 N 分钟", "Every N minutes")
        "hourly" -> st("每 N 小时", "Every N hours")
        "daily" -> st("每天", "Daily")
        "weekly" -> st("每周", "Weekly")
        else -> st("每月", "Monthly")
    }
    Box {
        ScheduleButton(st("常用规则：", "Common rule: ") + (shape?.let { label(it.kind) } ?: st("高级表达式", "Advanced expression")), enabled) { menu = true }
        DropdownMenu(menu, { menu = false }) {
            listOf("minutely", "hourly", "daily", "weekly", "monthly").forEach { kind ->
                DropdownMenuItem(text = { Text(label(kind)) }, enabled = enabled, onClick = {
                    menu = false
                    advanced = false
                    if (shape?.kind != kind) onChange(scheduleCronExpression(ScheduleCronShape(kind,
                        hour = shape?.hour ?: 9, minute = shape?.minute ?: 0)))
                })
            }
        }
    }
    if (!advanced && shape != null) {
        if (shape.kind == "minutely" || shape.kind == "hourly") {
            val max = if (shape.kind == "minutely") 59 else 23
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ScheduleButton("−", enabled && shape.step > 1) { onChange(scheduleCronExpression(shape.copy(step = shape.step - 1))) }
                Text(shape.step.toString())
                ScheduleButton("+", enabled && shape.step < max) { onChange(scheduleCronExpression(shape.copy(step = shape.step + 1))) }
            }
        }
        if (shape.kind == "hourly") {
            var minuteMenu by remember { mutableStateOf(false) }
            Box {
                ScheduleButton(st("每小时第 ${shape.minute} 分", "At minute ${shape.minute}"), enabled) { minuteMenu = true }
                DropdownMenu(minuteMenu, { minuteMenu = false }) {
                    (0..59).forEach { minute -> DropdownMenuItem(text = { Text(minute.toString()) }, enabled = enabled, onClick = {
                        minuteMenu = false
                        if (minute != shape.minute) onChange(scheduleCronExpression(shape.copy(minute = minute)))
                    }) }
                }
            }
        } else if (shape.kind != "minutely") {
            ScheduleButton(st("时间：", "Time: ") + "%02d:%02d".format(shape.hour, shape.minute), enabled) {
                TimePickerDialog(context, { _, hour, minute ->
                    if (hour != shape.hour || minute != shape.minute) onChange(scheduleCronExpression(shape.copy(hour = hour, minute = minute)))
                }, shape.hour, shape.minute, true).also { picker = it }.show()
            }
        }
        if (shape.kind == "weekly" || shape.kind == "monthly") {
            val weekly = shape.kind == "weekly"
            val selected = if (weekly) shape.weekdays else shape.days
            Text(if (weekly) st("星期", "Weekdays") else st("每月日期", "Days of month"), style = MaterialTheme.typography.labelMedium)
            (1..if (weekly) 7 else 31).toList().chunked(if (weekly) 3 else 4).forEach { days ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    days.forEach { day ->
                        FilterChip(day in selected, {
                            if (day !in selected || selected.size > 1) {
                                val values = if (day in selected) selected - day else selected + day
                                onChange(scheduleCronExpression(if (weekly) shape.copy(weekdays = values) else shape.copy(days = values)))
                            }
                        }, label = { Text(if (weekly) scheduleWeekday(day) else day.toString()) },
                            enabled = enabled, modifier = Modifier.heightIn(min = 48.dp))
                    }
                }
            }
            if (!weekly) Text(st("没有所选日期的月份会跳过；不是每隔固定天数。", "Months without the selected dates are skipped; this is not a fixed-day interval."),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    TextButton({ advanced = !advanced }, enabled = enabled && shape != null) {
        Text(if (advanced) st("使用常用规则", "Use common rule") else st("编辑高级表达式", "Edit advanced expression"))
    }
    if (advanced || shape == null) {
        ScheduleField(st("Cron 表达式", "Cron expression"), expression, enabled, onChange = onChange)
        Text(st("分 时 日 月 星期；日期与星期都受限时按任一条件匹配。切换视图不会改写原文。", "minute hour day-of-month month day-of-week. Restricted day fields match either condition. Switching views preserves the original expression."),
            style = MaterialTheme.typography.bodySmall)
    } else Text(expression, style = T.Mono, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
internal fun ScheduleHistoryScreen(sessionId: String, id: String, zone: String?, latestMessage: String?, online: Boolean) {
    key(sessionId, id) { ScheduleHistoryContent(sessionId, id, zone, latestMessage, online) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScheduleHistoryContent(sessionId: String, id: String, zone: String?, latestMessage: String?, online: Boolean) {
    val scope = rememberCoroutineScope()
    val ops by Pocket.ops.collectAsState()
    val canLoad = online && "schedule.history" in ops
    val list = rememberLazyListState()
    val pullState = rememberPullToRefreshState()
    var records by remember { mutableStateOf<List<JsonObject>>(emptyList()) }
    var nextBefore by remember { mutableStateOf<String?>(null) }
    var failedBefore by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var pruned by remember { mutableStateOf(false) }
    var unavailable by remember { mutableStateOf(false) }
    var retention by remember { mutableStateOf<JsonElement?>(null) }
    var showRetention by remember { mutableStateOf(false) }
    var requestEpoch by remember { mutableIntStateOf(0) }
    val attempted = remember { mutableSetOf<String>() }
    val completed = remember { mutableSetOf<String>() }
    suspend fun load(before: String? = null) {
        if (!canLoad || (loading && before != null)) return
        val epoch = ++requestEpoch
        loading = true
        refreshing = before == null
        error = null
        failedBefore = before
        if (before != null) attempted.add(before)
        try {
            val client = Pocket.clientOrNull() ?: error(st("连接已断开，请重新连接。", "Disconnected. Reconnect to continue."))
            val result = client.scheduleHistory(sessionId, id, 20, before)
            if (epoch != requestEpoch) return
            check(Pocket.clientOrNull() === client) { st("连接已变化，请重试。", "Connection changed. Retry.") }
            result["code"].str()?.let { code ->
                if (code == "delivery_cursor_not_found") failedBefore = null
                error(code)
            }
            val page = result["records"].arr() ?: error("Invalid delivery history response")
            val pageRows = page.map { record ->
                val row = record as? JsonObject ?: error("Invalid delivery record")
                check(!row["messageId"].str().isNullOrBlank()) { "Invalid delivery messageId" }
                row
            }.distinctBy { it["messageId"].str() }
            val merged = if (before == null) pageRows else (records + pageRows).distinctBy { it["messageId"].str() }
            val cursor = result["nextBefore"].str()
            if (before != null && cursor != null) {
                check(merged.size > records.size && cursor != before && cursor !in completed) {
                    st("运行记录分页未取得进展，请重试或下拉刷新。", "Delivery history did not advance. Retry or pull to refresh.")
                }
            }
            records = merged
            nextBefore = cursor
            if (before == null) {
                attempted.clear()
                completed.clear()
            } else completed.add(before)
            pruned = result["earlierRecordsPruned"].bool() == true
            unavailable = result["earlierRecordsUnavailable"].bool() == true
            retention = result["retention"]
            loaded = true
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            if (epoch == requestEpoch) error = scheduleFailure(e)
        } finally {
            if (epoch == requestEpoch) { loading = false; refreshing = false }
        }
    }
    DisposableEffect(Unit) { onDispose { requestEpoch++ } }
    // Only relevant capability transitions refresh; unrelated capability events must not retry errors.
    LaunchedEffect(latestMessage, canLoad) {
        requestEpoch++
        loading = false
        refreshing = false
        if (canLoad) load()
    }
    LaunchedEffect(canLoad) {
        var wasScrolling = false
        var newGesture = false
        snapshotFlow {
            val layout = list.layoutInfo
            val bottom = layout.totalItemsCount == records.size + 2 &&
                layout.visibleItemsInfo.lastOrNull()?.key == "footer"
            Triple(bottom, list.isScrollInProgress, Triple(nextBefore, loading, pullState.distanceFraction > 0f))
        }.collect { (bottom, scrolling, _) ->
            val endedGesture = !scrolling && wasScrolling && newGesture
            if (scrolling && !wasScrolling) newGesture = true
            if (!scrolling) newGesture = false
            wasScrolling = scrolling
            val cursor = nextBefore
            if (canLoad && loaded && bottom && !loading && cursor != null) {
                val retryBoundary = endedGesture && pullState.distanceFraction == 0f && failedBefore == cursor
                if (error != null && !retryBoundary) return@collect
                if (retryBoundary && error != null) {
                    attempted.remove(cursor)
                    newGesture = false
                }
                if (attempted.add(cursor)) {
                    newGesture = false
                    load(cursor)
                }
            }
        }
    }
    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = { if (canLoad && !loading) scope.launch { load() } },
        modifier = Modifier.fillMaxSize().testTag("scheduleHistoryRefresh"),
        state = pullState,
    ) {
        LazyColumn(state = list, modifier = Modifier.fillMaxSize().testTag("scheduleHistory"),
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item(key = "explanation") {
                Text(st("以下记录仅确认提醒已写入原会话收件箱，不代表模型已执行或任务已完成。", "These receipts confirm inbox delivery to the original session, not model execution or task completion."),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(records, key = { it["messageId"].str()!! }) { record ->
                var expanded by remember { mutableStateOf(false) }
                var overflow by remember { mutableStateOf(false) }
                Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(scheduleStamp(record["scheduledAt"].str(), zone), style = MaterialTheme.typography.labelLarge)
                    record["deliveredAt"].str()?.let { Text(st("已投递：", "Delivered: ") + scheduleStamp(it, zone),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    record["prompt"].str()?.let { savedPrompt ->
                        Text(savedPrompt, maxLines = if (expanded) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis,
                            onTextLayout = { if (!expanded) overflow = it.hasVisualOverflow })
                        if (expanded || overflow) TextButton({ expanded = !expanded }) { Text(if (expanded) st("收起", "Collapse") else st("展开", "Expand")) }
                    }
                    HorizontalDivider(thickness = 0.5.dp)
                }
            }
            item(key = "footer") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (loading && !refreshing) LinearProgressIndicator(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.onSurface)
                    error?.let { ErrorOrEmpty(it, retryEnabled = canLoad && !loading) { scope.launch { load(failedBefore) } } }
                    if (loaded && records.isEmpty() && error == null) ErrorOrEmpty(st("暂无任务运行记录", "No delivery record available"))
                    if (records.isNotEmpty() && nextBefore == null && !loading && error == null && (pruned || unavailable)) {
                        Text(if (pruned) st("更早的运行记录已清理", "Earlier delivery records have been cleared")
                            else st("更早的任务运行记录不可用", "Earlier delivery records are unavailable"), style = MaterialTheme.typography.bodySmall)
                        if (pruned) {
                            ScheduleButton(st("保留规则", "Retention rules")) { showRetention = !showRetention }
                            if (showRetention) Text(st("每个任务最多保留最近 ${retention["days"].int()} 天的 ${retention["records"].int()} 条运行记录。保存新记录时会自动清理超出限制的记录，清理不会停止任务。",
                                "Each task keeps up to ${retention["records"].int()} delivery records from the last ${retention["days"].int()} days. Older records are cleared when a new record is saved. Clearing records does not stop the task."),
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                }
            }
        }
    }
}
