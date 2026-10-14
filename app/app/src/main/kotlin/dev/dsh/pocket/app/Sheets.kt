package dev.dsh.pocket.app

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.SecureFlagPolicy
import dev.dsh.pocket.protocol.Item
import dev.dsh.pocket.protocol.ToolStatus
import dev.dsh.pocket.protocol.arr
import dev.dsh.pocket.protocol.get
import dev.dsh.pocket.protocol.str
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PocketSheet(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    ModalBottomSheet(
        onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = T.Sheet,
        containerColor = T.Surface,
        scrimColor = T.Scrim,
        tonalElevation = 0.dp,
        // Never inherit/set FLAG_SECURE — screenshots & recording stay allowed on every sheet.
        properties = ModalBottomSheetProperties(securePolicy = SecureFlagPolicy.SecureOff),
        dragHandle = { Box(Modifier.padding(top = 10.dp, bottom = 14.dp).size(40.dp, 4.dp).clip(CircleShape).background(T.Border)) },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
            content = content,
        )
    }
}

/**
 * Connection / pairing bottom sheet — same desktop sheet chrome as [PocketSheet]
 * (`--dsw-alias-bg-layer-1`, panel radius, mask scrim).
 */
@Composable
fun ConnSheet(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) =
    PocketSheet(onDismiss, content)

@Composable fun SheetTitle(t: String) = Text(t, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = T.Ink, modifier = Modifier.padding(horizontal = 22.dp, vertical = 6.dp))
@Composable fun ConnSheetTitle(t: String) = SheetTitle(t)
@Composable fun SectionLabel(t: String) = Text(t, fontSize = 13.sp, color = T.Text3, modifier = Modifier.padding(start = 22.dp, end = 22.dp, top = 14.dp, bottom = 6.dp))
@Composable fun ConnSectionLabel(t: String) = SectionLabel(t)

@Composable
fun ConnOptionRow(
    title: String,
    desc: String? = null,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    onClick: () -> Unit = {},
) = OptionRow(title = title, desc = desc, icon = icon, enabled = enabled, onClick = onClick)

@Composable
fun OptionRow(title: String, desc: String? = null, icon: ImageVector? = null, selected: Boolean = false, enabled: Boolean = true, trailing: String? = null, code: String? = null, onClick: () -> Unit = {}) {
    // Disabled = 0.45 via *color* alpha — not Modifier.alpha. Modifier.alpha creates a graphics layer
    // that some OEM GPUs (e.g. ColorOS without GMS) fail to composite, leaving an empty-looking sheet.
    val a = if (enabled) 1f else 0.45f
    Row(Modifier.padding(horizontal = 12.dp).fillMaxWidth().clip(T.R12).background(if (selected) T.Fill2 else Color.Transparent)
        .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 10.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        icon?.let { Icon(it, null, tint = T.Ink.copy(alpha = a), modifier = Modifier.size(18.dp)); Spacer(Modifier.width(14.dp)) }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, fontSize = 16.sp, color = T.Ink.copy(alpha = a))
                code?.let { Spacer(Modifier.width(10.dp)); Text(it, style = T.Mono.copy(color = T.Caption.copy(alpha = a))) }
                Spacer(Modifier.weight(1f))
                trailing?.let { Text(it, fontSize = 13.sp, color = T.Text3.copy(alpha = a), maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            desc?.let { Text(it, fontSize = 13.sp, color = T.Text3.copy(alpha = a), lineHeight = 19.sp, modifier = Modifier.padding(top = 2.dp)) }
        }
        if (selected) { Spacer(Modifier.width(10.dp)); Icon(D.check, null, tint = T.Ink.copy(alpha = a), modifier = Modifier.size(18.dp)) }
    }
}

@Composable
fun ToolSheet(t: Item.Tool, onDismiss: () -> Unit) {
    val clip = LocalClipboardManager.current
    PocketSheet(onDismiss) {
        Row(Modifier.padding(horizontal = 22.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(t.name, fontSize = 18.sp, fontWeight = FontWeight.SemiBold); Spacer(Modifier.width(10.dp))
            val (label, color) = when (t.status) { ToolStatus.Ok -> "成功" to T.Ok; ToolStatus.Error -> "失败" to T.Err; ToolStatus.AwaitingApproval -> "等待审批" to T.WarnText; else -> "运行中" to T.Accent }
            Text(label, fontSize = 12.sp, color = color, modifier = Modifier.clip(T.R4).background(color.copy(alpha = 0.1f)).padding(horizontal = 6.dp, vertical = 2.dp))
            if (t.startedAt != null && t.endedAt != null) { Spacer(Modifier.width(8.dp)); Text("${"%.1f".format((t.endedAt!! - t.startedAt!!) / 1000.0)}s", fontSize = 12.sp, color = T.Text3) }
        }
        SectionLabel("参数")
        CodeBox(prettyJson(t.arguments).ifBlank { "{}" })
        SectionLabel("结果")
        CodeBox(t.error?.let { e -> listOfNotNull(e, t.result).joinToString("\n") } ?: t.result ?: "—", maxLines = 40)
        Row(Modifier.padding(horizontal = 22.dp, vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton({ clip.setText(AnnotatedString(t.result.orEmpty())) }, Modifier.weight(1f).height(48.dp), shape = T.R12, border = BorderStroke(1.dp, T.Border),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = T.Ink), enabled = t.result != null) { Text("复制结果") }
            OutlinedButton({ clip.setText(AnnotatedString(t.arguments)) }, Modifier.weight(1f).height(48.dp), shape = T.R12, border = BorderStroke(1.dp, T.Border),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = T.Ink)) { Text("复制参数") }
        }
    }
}

@Composable
private fun CodeBox(s: String, maxLines: Int = 20) {
    SelectableOutput {
        Text(
            s.breakAnywhere(),
            style = T.Mono.copy(lineHeight = 20.sp),
            softWrap = true,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 22.dp).fillMaxWidth().clip(T.R12).background(T.Fill2).padding(14.dp),
        )
    }
}



@Composable
fun PresetSheet(cur: String, editable: Boolean, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val roster by Pocket.presetRoster.collectAsState()
    val avail = roster.filter { Pocket.prefs.showCodeWorkView || it["id"].str() !in setOf("minimal", "ptc") || it["id"].str() == cur }
    PocketSheet(onDismiss) {
        SheetTitle("Agent 预设")
        if (!editable) Text("预设在创建会话时确定", fontSize = 13.sp, color = T.Text3, modifier = Modifier.padding(horizontal = 22.dp))
        if (avail.isEmpty()) Text("主机未提供预设目录", color = T.Text3, modifier = Modifier.padding(22.dp))
        avail.forEach { preset ->
            val id = preset["id"].str() ?: return@forEach
            OptionRow(presetName(preset), presetDescription(preset), selected = cur == id, enabled = editable) { onPick(id); onDismiss() }
        }
    }
}

@Composable
fun WorkspaceSheet(cur: String?, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val workspaces by Pocket.workspaces.collectAsState()
    val ops by Pocket.ops.collectAsState()
    var adding by remember { mutableStateOf(false) }
    if (adding) {
        WorkspaceDirectorySheet(onCreated = { onPick(it.path); onDismiss() }, onDismiss = { adding = false })
        return
    }
    PocketSheet(onDismiss) {
        SheetTitle("选择工作区")
        workspaces.forEach { workspace ->
            OptionRow(workspaceDisplayTitle(workspace.title, workspace.path), workspace.path, D.folderOpen, selected = workspace.path == cur) {
                onPick(workspace.path); onDismiss()
            }
        }
        if (workspaces.isEmpty()) Text("尚未添加工作区", color = T.Text3, modifier = Modifier.padding(22.dp))
        if ("workspace.create" in ops) OptionRow("选择文件夹…", icon = D.folderOpen) { adding = true }
    }
}

@Composable
fun PlusSheet(
    onDismiss: () -> Unit,
    onPerm: () -> Unit,
    onModel: () -> Unit,
    onFile: () -> Unit,
    onGoal: () -> Unit,
    onPlan: () -> Unit,
    onFeedback: () -> Unit,
    onCompact: () -> Unit,
    onExport: () -> Unit,
) {
    CommandPopupSheet(onDismiss, onFile, onGoal, onPlan, onFeedback, onCompact, onPerm, onModel, onExport)
}

@Composable
fun MoreSheet(chat: ChatState?, onDismiss: () -> Unit, onFeedback: () -> Unit, onExport: () -> Unit) {
    var renaming by remember { mutableStateOf(false) }
    val export = "session.export" in Pocket.ops.value
    val inChat = chat != null
    PocketSheet(onDismiss) {
        if (renaming && chat != null) {
            var t by remember { mutableStateOf(chat.title.orEmpty()) }
            SheetTitle("重命名会话")
            OutlinedTextField(t, { t = it }, singleLine = true, shape = T.R12, modifier = Modifier.padding(horizontal = 22.dp).fillMaxWidth())
            Button({ Pocket.rename(chat.sessionId, t.trim()); onDismiss() }, Modifier.padding(22.dp).fillMaxWidth().height(50.dp), enabled = t.isNotBlank(), shape = T.R12,
                colors = PrimaryColors()) { Text("保存") }
            return@PocketSheet
        }
        // Session-only extras (mobile keeps rename here; desktop puts it on the sidebar row menu).
        if (chat != null) {
            OptionRow("重命名", icon = D.edit) { renaming = true }
            val metrics = chat.composerMetrics
            SectionLabel(if (metrics.activityIsWholeSession) "会话统计" else "已加载历史统计")
            Column(Modifier.padding(horizontal = 22.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                metrics.activity?.let { Text("${it.turns} 轮 · ${it.steps} 步", color = T.Text2, fontSize = 13.sp) }
                metrics.tokens?.let {
                    Text("输入 ${kfmt(it.billedInputTokens)} · 输出 ${kfmt(it.outputTokens)}", color = T.Text2, fontSize = 13.sp)
                    it.cacheHitPercent?.let { percent -> Text("缓存命中 $percent%", color = T.Text3, fontSize = 12.sp) }
                }
                if (metrics.activity == null && metrics.tokens == null) Text("主机未提供统计数据", color = T.Text3, fontSize = 13.sp)
            }
            Spacer(Modifier.height(8.dp))
        }
        // Official dsh Session Header ⋯ (dsh-session-log-export HeaderAction): download always listed;
        // feedback when that path is available. Need a session → disable (never hide into an empty sheet).
        OptionRow("下载 Session 日志", icon = D.download, enabled = export && inChat) { onExport(); onDismiss() }
        if ("sessionFeedback.record" in Pocket.ops.value) OptionRow("反馈", icon = D.paperPlane, enabled = inChat) { onFeedback() }
        val fork = "sessions.fork" in Pocket.ops.value
        if (fork && chat != null) {
            OptionRow(
                "在新对话中分支",
                "从当前会话前缀创建分支",
                D.newChat,
                enabled = !chat.running,
            ) {
                Pocket.forkSession(null)
                onDismiss()
            }
        }
    }
}

@Composable
fun GoalBar(chat: ChatState, onEdit: () -> Unit) {
    val goal = chat.goal ?: return
    if (goal["phase"].str() == "complete") return
    val activation by Pocket.goalActivation.collectAsState()
    LaunchedEffect(chat.sessionId, goal["revision"], chat.running) {
        try { Pocket.getGoal(chat.sessionId) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { Pocket.errors.tryEmit(e.message ?: "目标状态读取失败") }
    }
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = onEdit).padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(D.goal, null, tint = T.Text3, modifier = Modifier.size(16.dp))
        Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
            Text(goalPhaseLabel(goal["phase"].str(), activation), color = T.Text3, fontSize = 12.sp)
            Text(goal["objective"].str().orEmpty(), color = T.Text2, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(D.chevronRight, "编辑目标", tint = T.Text3, modifier = Modifier.size(16.dp))
    }
}

private fun goalPhaseLabel(phase: String?, activation: String?): String = when (phase) {
    "active" -> if (activation == "disarmed") "已暂停的目标" else "进行中的目标"
    "paused" -> "已暂停的目标"
    "blocked" -> "受阻的目标"
    "complete" -> "已完成的目标"
    else -> "目标状态未知"
}

@Composable
fun GoalSheet(onDismiss: () -> Unit) {
    val chat by Pocket.chat.collectAsState()
    val activation by Pocket.goalActivation.collectAsState()
    val goal = chat?.goal
    var text by remember(goal?.get("id")) { mutableStateOf(goal?.get("objective").str().orEmpty()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    fun mutate(action: String) {
        val st = chat ?: return
        val current = goal ?: return
        if (busy) return
        val ref = buildJsonObject {
            current["id"]?.let { put("id", it) }
            current["revision"]?.let { put("revision", it) }
        }
        busy = true; error = null
        scope.launch {
            try {
                Pocket.mutateGoal(st.sessionId, action, ref, if (action == "edit") text.trim() else null)
                onDismiss()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "目标修改失败" }
            finally { busy = false }
        }
    }
    PocketSheet({ if (!busy) onDismiss() }) {
        SheetTitle("目标")
        goal?.let {
            SectionLabel(goalPhaseLabel(it["phase"].str(), activation))
            it["blockedReason"]["message"].str()?.let { reason -> Text(reason, color = T.WarnText, modifier = Modifier.padding(horizontal = 22.dp)) }
        }
        OutlinedTextField(text, { text = it }, enabled = !busy, placeholder = { Text("长期任务目标") }, shape = T.R12,
            modifier = Modifier.padding(horizontal = 22.dp).fillMaxWidth(), minLines = 3)
        error?.let { Text(it, color = T.Err, modifier = Modifier.padding(22.dp)) }
        if (goal != null) {
            val phase = goal["phase"].str()
            if (phase == "active" && activation == "armed") OptionRow("暂停目标", enabled = !busy) { mutate("pause") }
            if (phase == "paused" || (phase == "active" && activation == "disarmed")) OptionRow("恢复目标", enabled = !busy) { mutate("resume") }
            OptionRow("清除目标", icon = D.trash, enabled = !busy) { mutate("clear") }
        }
        Button({
            if (goal != null) mutate("edit")
            else {
                val id = chat?.sessionId ?: return@Button
                busy = true; error = null
                scope.launch {
                    try {
                        val command = Pocket.commandCatalog(id).firstOrNull { builtinCommandName(it["definitionId"].str()) == "goal" }
                            ?: throw IllegalStateException("主机没有提供目标指令")
                        Pocket.executeCommand("/${command["name"].str()} ${text.trim()}") { success ->
                            busy = false
                            if (success) onDismiss() else error = "目标创建失败，内容已保留"
                        }
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { busy = false; error = e.message }
                }
            }
        }, Modifier.padding(22.dp).fillMaxWidth().heightIn(min = 48.dp), enabled = !busy && text.isNotBlank() && chat != null,
            shape = T.R12, colors = PrimaryColors()) { Text(if (busy) "提交中…" else if (goal == null) "设置目标" else "保存目标") }
    }
}

@Composable
fun FeedbackSheet(onDismiss: () -> Unit) {
    FeedbackCategorySheet(onDismiss) { category, note, complete ->
        Pocket.recordSessionFeedback(category, note, complete)
    }
}



/** Desktop TodoPanel — locale `todo.title` / status / progress. */
@Composable
fun TodoSheet(todos: List<dev.dsh.pocket.protocol.TodoItem>, onDismiss: () -> Unit) {
    PocketSheet(onDismiss) {
        SheetTitle("任务")
        if (todos.isEmpty()) {
            Text("任务清单为空", fontSize = 14.sp, color = T.Text3, modifier = Modifier.padding(22.dp))
            return@PocketSheet
        }
        val done = todos.count { it.status == "completed" }
        val active = todos.count { it.status == "in_progress" }
        val pending = todos.size - done - active
        val progress = buildList {
            if (done > 0) add("$done 已完成")
            if (active > 0) add("$active 进行中")
            if (pending > 0) add("$pending 待处理")
        }.joinToString(" · ")
        SelectableOutput {
            Column {
            Text(progress, fontSize = 13.sp, color = T.Text3, modifier = Modifier.padding(horizontal = 22.dp, vertical = 4.dp))
            todos.forEach { item ->
                Row(
                    Modifier.padding(horizontal = 22.dp, vertical = 10.dp).fillMaxWidth(),
                    verticalAlignment = Alignment.Top,
                ) {
                    val mark = when (item.status) {
                        "completed" -> "✓"
                        "in_progress" -> "●"
                        else -> "○"
                    }
                    val color = when (item.status) {
                        "completed" -> T.Ok
                        "in_progress" -> T.Accent
                        else -> T.Caption
                    }
                    Text(mark, color = color, fontSize = 14.sp, modifier = Modifier.padding(top = 2.dp, end = 10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(item.content, fontSize = 15.sp, color = T.Ink, lineHeight = 22.sp)
                        Text(todoStatusLabel(item.status), fontSize = 12.sp, color = T.Text3, modifier = Modifier.padding(top = 2.dp))
                    }
                }
            }
            }
        }
    }
}


@Composable
fun JobsSheet(sessionId: String, onDismiss: () -> Unit) {
    var jobs by remember(sessionId) { mutableStateOf<List<JsonObject>>(emptyList()) }
    var err by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var attempt by remember { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf<JsonObject?>(null) }
    var stopping by remember { mutableStateOf<JsonObject?>(null) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(sessionId, attempt) {
        loading = true; err = null
        try { jobs = Pocket.listJobs(sessionId)["jobs"].arr().orEmpty().filterIsInstance<JsonObject>() }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { err = e.message ?: "任务加载失败" }
        finally { loading = false }
    }
    val detail = selected
    val output = remember(detail?.get("id")) { mutableStateListOf<String>() }
    var detailStatus by remember(detail) { mutableStateOf(detail?.get("status").str().orEmpty()) }
    var detailError by remember(detail) { mutableStateOf<String?>(null) }
    var followAttempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(detail, followAttempt) {
        val job = detail ?: return@LaunchedEffect
        val id = job["id"].str() ?: job["jobId"].str() ?: return@LaunchedEffect
        output.clear(); detailError = null
        fun appendOutput(text: String) {
            if (text.isEmpty()) return
            if (output.isEmpty()) output.add("")
            val lines = text.split('\n')
            output[output.lastIndex] = output.last() + lines.first()
            for (index in 1 until lines.size) output.add(lines[index])
        }
        try {
            Pocket.followJob(sessionId, id).collect { frame ->
                when (frame["type"].str()) {
                    "opened", "status" -> detailStatus = frame["job"]["status"].str().orEmpty()
                    "output" -> {
                        val chunks = frame["chunks"].arr().orEmpty()
                        if (frame["lossy"].toString() == "true") appendOutput("\n[部分历史输出已过期]\n")
                        chunks.forEach {
                            if (it["gapBefore"].toString() == "true") appendOutput("\n[此处输出存在缺失]\n")
                            appendOutput(it["text"].str().orEmpty())
                        }
                    }
                }
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { detailError = e.message ?: "输出读取失败" }
    }
    androidx.activity.compose.BackHandler(enabled = detail != null) { selected = null }
    PocketSheet(onDismiss) {
        if (detail != null) {
            TextButton({ selected = null }) { Text("返回后台任务") }
            SheetTitle(detail["label"].str() ?: detail["name"].str() ?: detail["id"].str().orEmpty())
            SectionLabel(detailStatus)
            detail["detail"].str()?.let { CodeBox(it) }
            detailError?.let { Text(it, color = T.Err, modifier = Modifier.padding(22.dp)); TextButton({ followAttempt++ }) { Text("重试") } }
            if (output.isEmpty() && detailError == null) Text("等待任务输出…", color = T.Text3, modifier = Modifier.padding(22.dp))
            else SelectableOutput {
                LazyColumn(Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 420.dp).padding(horizontal = 22.dp)) {
                    items(output.size) { index ->
                        Text(output[index], style = T.Mono, color = T.Text2, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        } else {
            SheetTitle("后台任务")
            TextButton({ attempt++ }, enabled = !loading) { Text("刷新") }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            err?.let { Text(it, color = T.Err, modifier = Modifier.padding(22.dp)) }
            if (!loading && jobs.isEmpty() && err == null) Text("0 个后台任务", color = T.Text3, modifier = Modifier.padding(22.dp))
            jobs.forEach { job ->
                val id = job["id"].str() ?: job["jobId"].str() ?: return@forEach
                val status = job["status"].str() ?: job["state"].str().orEmpty()
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { OptionRow(job["label"].str() ?: job["command"].str() ?: job["name"].str() ?: id, status, D.clock) { selected = job } }
                    if (status == "running" || status == "pending") TextButton({ stopping = job }) { Text("终止", color = T.Err) }
                }
            }
        }
    }
    stopping?.let { job ->
        AlertDialog(onDismissRequest = { if (!busy) stopping = null }, title = { Text("终止后台任务？") },
            text = { Text(job["label"].str() ?: job["id"].str().orEmpty()) },
            dismissButton = { TextButton({ stopping = null }, enabled = !busy) { Text("取消") } },
            confirmButton = { TextButton({
                if (!busy) {
                    busy = true
                    Pocket.killJob(sessionId, job["id"].str() ?: job["jobId"].str().orEmpty()) { success ->
                        busy = false
                        if (success) { stopping = null; attempt++ } else err = "终止失败，请重试"
                    }
                }
            }, enabled = !busy) { Text("终止", color = T.Err) } })
    }
}

@Composable
fun TeamSheet(onDismiss: () -> Unit) {
    val chat = Pocket.chat.collectAsState().value
    val sessions = Pocket.sessions.collectAsState().value
    val children = sessions.filter { it.parentSessionId != null && it.parentSessionId == chat?.sessionId }
    PocketSheet(onDismiss) {
        SheetTitle("智能体团队")
        OptionRow(chat?.title ?: "主智能体", "主会话", D.user, selected = true)
        if (children.isEmpty()) Text("尚无子智能体", fontSize = 14.sp, color = T.Text3, modifier = Modifier.padding(horizontal = 22.dp, vertical = 12.dp))
        children.forEach { s -> OptionRow(s.title ?: "子智能体", if (s.running) "运行中" else "空闲", D.user) { Pocket.openSession(s.sessionId); onDismiss() } }
    }
}

/**
 * `/` command popup — same sections/rows as desktop `ui-commands` (add + commands).
 * Each pick consumes the typed `/` (desktop `slash/input-consume-token`) then runs the
 * matching action (pickers / sheets / host execute), mirroring [PlusSheet].
 */
@Composable
fun CommandPopupSheet(
    onDismiss: () -> Unit,
    onFile: () -> Unit,
    onGoal: () -> Unit,
    onPlan: () -> Unit,
    onFeedback: () -> Unit,
    onCompact: () -> Unit,
    onPerm: () -> Unit,
    onModel: () -> Unit,
    onExport: () -> Unit,
    initialQuery: String = "",
) {
    val chat by Pocket.chat.collectAsState()
    var query by remember { mutableStateOf(initialQuery) }
    var commands by remember { mutableStateOf<List<JsonObject>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(chat?.sessionId, attempt) {
        loading = true; error = null
        try {
            val id = chat?.sessionId
            commands = if (id == null) emptyList() else Pocket.commandCatalog(id)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: "无法加载指令" }
        finally { loading = false }
    }
    PocketSheet(onDismiss) {
        SheetTitle("指令")
        OutlinedTextField(query, { query = it }, placeholder = { Text("搜索指令") }, singleLine = true,
            modifier = Modifier.padding(horizontal = 22.dp).fillMaxWidth())
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { Text(it, color = T.Err, modifier = Modifier.padding(22.dp)); TextButton({ attempt++ }) { Text("重试") } }
        if (chat == null) Text("请先打开会话以读取主机指令目录", color = T.Text3, modifier = Modifier.padding(22.dp))
        SectionLabel(L.sectionAdd())
        if ("file".contains(query, true) || L.file().contains(query, true)) OptionRow(L.file(), icon = D.paperclip, code = "file", enabled = chat != null && "attachments.upload" in Pocket.ops.value) {
            Pocket.consumeComposerSlash(); onFile(); onDismiss()
        }
        if ("model".contains(query, true) || "模型".contains(query)) OptionRow("模型", icon = D.data, code = "model") { Pocket.consumeComposerSlash(); onModel() }
        if (chat == null && ("permission".contains(query, true) || "权限".contains(query))) OptionRow("权限", icon = D.permission("danger-full-access"), code = "permission") {
            Pocket.consumeComposerSlash(); onPerm()
        }
        SectionLabel(L.sectionCommands())
        val filtered = commands.filter { it["name"].str().orEmpty().contains(query, true) || it["description"].str().orEmpty().contains(query, true) }
        if (!loading && error == null && chat != null && filtered.isEmpty()) Text(L.noOptions(), color = T.Text3, modifier = Modifier.padding(22.dp))
        filtered.forEach { command ->
            val name = command["name"].str() ?: return@forEach
            OptionRow("/$name", command["description"].str()) {
                // Only decorate commands that the Host actually advertised. Others return
                // the real token to the composer so arguments can be reviewed before send.
                when (builtinCommandName(command["definitionId"].str())) {
                    "goal" -> { Pocket.consumeComposerSlash(); onGoal() }
                    "plan" -> {
                        Pocket.consumeComposerSlash()
                        if (name == "plan") onPlan() else Pocket.executeCommand("/$name")
                        onDismiss()
                    }
                    "feedback" -> { Pocket.consumeComposerSlash(); onFeedback() }
                    "compact" -> {
                        Pocket.consumeComposerSlash()
                        if (name == "compact") onCompact() else Pocket.executeCommand("/$name")
                        onDismiss()
                    }
                    "permission" -> { Pocket.consumeComposerSlash(); onPerm() }
                    "export" -> { Pocket.consumeComposerSlash(); onExport(); onDismiss() }
                    else -> { Pocket.insertComposer("/$name"); onDismiss() }
                }
            }
        }
    }
}

internal fun builtinCommandName(definitionId: String?): String? = when (definitionId) {
    "@deepseek-ai/dsh-command-goal" -> "goal"
    "@deepseek-ai/dsh-plan-mode" -> "plan"
    "@deepseek-ai/dsh-command-feedback" -> "feedback"
    "@deepseek-ai/dsh-command-compact" -> "compact"
    "@deepseek-ai/dsh-permission-presets" -> "permission"
    "@deepseek-ai/dsh-session-log-export" -> "export"
    else -> null
}

/** `@` mention popup — files + sessions from Host reference services. */
@Composable
fun MentionPopupSheet(onDismiss: () -> Unit, initialQuery: String = "") {
    val chat by Pocket.chat.collectAsState()
    var query by remember { mutableStateOf(initialQuery) }
    var files by remember { mutableStateOf<List<dev.dsh.pocket.protocol.FileReference>>(emptyList()) }
    var sessions by remember { mutableStateOf<List<dev.dsh.pocket.protocol.SessionReference>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(chat?.sessionId, query, attempt) {
        loading = true; error = null; files = emptyList(); sessions = emptyList()
        try {
            val id = chat?.sessionId ?: throw IllegalStateException("请先打开会话")
            val results = kotlinx.coroutines.coroutineScope {
                val fileRequest = async { Pocket.referenceFiles(id, query) }
                val sessionRequest = async { Pocket.referenceSessions(id, query) }
                fileRequest.await() to sessionRequest.await()
            }
            files = results.first
            sessions = results.second
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: "搜索失败" }
        finally { loading = false }
    }
    PocketSheet(onDismiss) {
        SheetTitle("@ 文件或对话")
        OutlinedTextField(query, { query = it }, placeholder = { Text(L.searchEllipsis()) },
            singleLine = true, shape = T.R12, modifier = Modifier.padding(horizontal = 22.dp).fillMaxWidth())
        if (query.contains('/')) TextButton({ query = query.trimEnd('/').substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" } }) { Text("上一级目录") }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { Text(it, color = T.Err, modifier = Modifier.padding(22.dp)); TextButton({ attempt++ }) { Text("重试") } }
        fun pick(token: String) { Pocket.insertComposer("@${token.removePrefix("@")}"); onDismiss() }
        SectionLabel(L.filesAndFolders())
        if (!loading && files.isEmpty() && error == null) Text(L.noOptions(), color = T.Caption, modifier = Modifier.padding(horizontal = 22.dp))
        files.forEach { f ->
            val directory = f.kind == "directory" || f.kind == "folder"
            val token = fileReferenceToken(f.path, directory) ?: return@forEach
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    OptionRow(f.path.trimEnd('/').substringAfterLast('/'), f.path, if (directory) D.folderOpen else D.document) {
                        if (directory) query = "${f.path.trimEnd('/')}/" else pick(token)
                    }
                }
                if (directory) TextButton({ pick(token) }) { Text("引用") }
            }
        }
        val conversations = sessions.filter { it.kind != "subagent" }
        val subagents = sessions.filter { it.kind == "subagent" }
        listOf(L.conversations() to conversations, L.subagents() to subagents).forEach { (title, rows) ->
            SectionLabel(title)
            if (!loading && rows.isEmpty() && error == null) Text(L.noOptions(), color = T.Caption, modifier = Modifier.padding(horizontal = 22.dp))
            rows.forEach { s -> OptionRow(s.title ?: s.mention, s.mention, if (s.kind == "subagent") D.user else D.chat) { pick(s.mention) } }
        }
    }
}

/** Dislike → feedback category + optional note (desktop feedback.dialog). */
@Composable
fun FeedbackCategorySheet(onDismiss: () -> Unit, onSubmit: (category: String?, note: String, complete: (Boolean) -> Unit) -> Unit) {
    var category by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    PocketSheet({ if (!busy) onDismiss() }) {
        SheetTitle(L.submitFeedback())
        SectionLabel(L.feedbackCategory())
        L.feedbackCategories().forEach { (id, label) ->
            OptionRow(label, selected = category == id, enabled = !busy) { category = if (category == id) null else id }
        }
        SectionLabel(L.feedbackDetail())
        OutlinedTextField(
            note, { note = it },
            enabled = !busy,
            placeholder = { Text(L.feedbackHint()) },
            shape = T.R12,
            modifier = Modifier.padding(horizontal = 22.dp).fillMaxWidth(),
            minLines = 3,
        )
        if (error) Text("提交失败，内容已保留，请重试", color = T.Err, modifier = Modifier.padding(horizontal = 22.dp))
        Button(
            { if (!busy) { busy = true; error = false; onSubmit(category, note.trim()) { success -> busy = false; error = !success; if (success) onDismiss() } } },
            Modifier.padding(22.dp).fillMaxWidth().height(50.dp),
            enabled = !busy,
            shape = T.R12,
            colors = PrimaryColors(),
        ) { Text(L.submitFeedback()) }
    }
}

/** Black primary button; label/progress always white (also under OEM force-dark). */
@Composable
fun PrimaryColors() = ButtonDefaults.buttonColors(containerColor = T.PrimaryButton, contentColor = T.OnPrimary, disabledContainerColor = T.Caption, disabledContentColor = T.OnPrimary)
