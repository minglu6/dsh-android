package dev.dsh.pocket.app

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
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
import dev.dsh.pocket.protocol.ModelCatalog
import dev.dsh.pocket.protocol.ToolStatus
import dev.dsh.pocket.protocol.arr
import dev.dsh.pocket.protocol.get
import dev.dsh.pocket.protocol.str
import kotlinx.coroutines.launch

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
fun ModelSheet(catalog: ModelCatalog?, cur: ModelChoice?, onDismiss: () -> Unit, onPick: (ModelChoice) -> Unit) {
    var q by remember { mutableStateOf("") }
    PocketSheet(onDismiss) {
        SheetTitle("模型与推理等级")
        Row(Modifier.padding(horizontal = 18.dp, vertical = 8.dp).fillMaxWidth().clip(T.R12).background(T.Fill2).padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(D.search, null, tint = T.Caption, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
            Box { if (q.isEmpty()) Text("搜索模型…", color = T.Caption, fontSize = 15.sp)
                BasicTextField(q, { q = it }, singleLine = true, textStyle = MaterialTheme.typography.bodyMedium, cursorBrush = SolidColor(T.Accent), modifier = Modifier.fillMaxWidth()) }
        }
        if (catalog == null || catalog.groups.isEmpty()) { Text("暂无可用模型，请在桌面端配置提供方", color = T.Text3, fontSize = 14.sp, modifier = Modifier.padding(22.dp)); return@PocketSheet }
        SectionLabel("模型")
        catalog.groups.forEach { g ->
            val ms = g.models.filter { q.isBlank() || it.name.contains(q, true) || it.id.contains(q, true) }
            if (ms.isEmpty()) return@forEach
            Text(g.name, fontSize = 13.sp, color = T.Text3, modifier = Modifier.padding(horizontal = 22.dp, vertical = 4.dp))
            ms.forEach { m ->
                OptionRow(m.name, m.description, D.data, selected = cur?.provider == m.provider && cur.model == m.id) {
                    onPick(ModelChoice(m.provider, m.id, m.name, m.efforts.firstOrNull { it.id == cur?.effort }?.id ?: m.defaultEffort))
                }
            }
        }
        val m = cur?.let { catalog.find(it.provider, it.model) }
        if (m != null && m.efforts.isNotEmpty()) {
            SectionLabel("推理等级")
            m.efforts.forEach { e -> OptionRow(e.name, e.description, selected = cur.effort == e.id) { onPick(cur.copy(effort = e.id)) } }
        }
    }
}


@Composable
fun PresetSheet(cur: String, editable: Boolean, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val avail = Pocket.presets.collectAsState().value.ifEmpty { PRESET_LABEL.keys.toList() }
    PocketSheet(onDismiss) {
        SheetTitle("Agent 预设")
        if (!editable) Text("预设在创建会话时确定", fontSize = 13.sp, color = T.Text3, modifier = Modifier.padding(horizontal = 22.dp))
        avail.forEach { id -> val l = PRESET_LABEL[id]; OptionRow(l?.first ?: id, l?.second, selected = cur == id, enabled = editable) { onPick(id); onDismiss() } }
    }
}

@Composable
fun WorkspaceSheet(list: List<String>, cur: String?, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    PocketSheet(onDismiss) {
        SheetTitle("工作区")
        if (list.isEmpty()) Text("使用桌面端默认工作区", fontSize = 14.sp, color = T.Text3, modifier = Modifier.padding(22.dp))
        list.forEach { w -> OptionRow(w.substringAfterLast('/'), w, D.folderOpen, selected = w == cur) { onPick(w); onDismiss() } }
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
    val cmds = "commands.execute" in Pocket.ops.value
    val export = "session.export" in Pocket.ops.value
    val inChat = Pocket.chat.collectAsState().value != null
    fun pick(then: () -> Unit) {
        // Clear a leftover `/` if the slash sheet was dismissed without picking.
        Pocket.consumeComposerSlash()
        then()
    }
    PocketSheet(onDismiss) {
        SectionLabel(L.sectionAdd())
        OptionRow(L.file(), icon = D.paperclip, code = "file", enabled = inChat) { pick { onFile(); onDismiss() } }
        OptionRow("目标", icon = D.goal, code = "goal", trailing = "设置或查看长期任务目标", enabled = cmds && inChat) { pick { onGoal() } }
        OptionRow("计划", icon = D.plan, code = "plan", trailing = "进入或退出计划模式", enabled = cmds && inChat) { pick { onPlan(); onDismiss() } }
        OptionRow("反馈", icon = D.paperPlane, code = "feedback", trailing = "发送关于当前会话的反馈", enabled = cmds && inChat) { pick { onFeedback() } }
        SectionLabel(L.sectionCommands())
        OptionRow("压缩", icon = D.compact, code = "compact", trailing = "压缩以上对话内容", enabled = cmds && inChat) { pick { onCompact(); onDismiss() } }
        OptionRow("权限", icon = D.permission("danger-full-access"), code = "permission", trailing = "切换权限预设") { pick { onPerm() } }
        OptionRow("模型", icon = D.data, code = "model", trailing = "选择本会话使用的模型") { pick { onModel() } }
        OptionRow("下载日志", icon = D.download, code = "export", trailing = "将当前会话导出为 ZIP", enabled = export && inChat) { pick { onExport(); onDismiss() } }
    }
}

@Composable
fun MoreSheet(chat: ChatState?, onDismiss: () -> Unit, onFeedback: () -> Unit, onExport: () -> Unit) {
    var renaming by remember { mutableStateOf(false) }
    val export = "session.export" in Pocket.ops.value
    val cmds = "commands.execute" in Pocket.ops.value
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
            SectionLabel("会话统计")
            val u = chat.usage
            Row(Modifier.padding(horizontal = 22.dp, vertical = 4.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Stat("轮次", "${u.turns}"); Stat("步数", "${u.steps}"); Stat("输入", kfmt(u.inputTokens)); Stat("输出", kfmt(u.outputTokens)); Stat("缓存命中", u.cacheHitPercent?.let { "$it%" } ?: "—")
            }
            Spacer(Modifier.height(8.dp))
        }
        // Official dsh Session Header ⋯ (dsh-session-log-export HeaderAction): download always listed;
        // feedback when that path is available. Need a session → disable (never hide into an empty sheet).
        OptionRow("下载 Session 日志", icon = D.download, enabled = export && inChat) { onExport(); onDismiss() }
        if (cmds) OptionRow("反馈", icon = D.paperPlane, enabled = inChat) { onFeedback() }
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
fun GoalSheet(onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    PocketSheet(onDismiss) {
        SheetTitle("目标")
        OutlinedTextField(text, { text = it }, placeholder = { Text("设置或查看长期任务目标") }, shape = T.R12,
            modifier = Modifier.padding(horizontal = 22.dp).fillMaxWidth(), minLines = 3)
        Spacer(Modifier.height(10.dp))
        OptionRow("查看当前目标", icon = D.goal) { Pocket.executeCommand("/goal"); onDismiss() }
        OptionRow("清除目标", icon = D.trash) { Pocket.executeCommand("/goal clear"); onDismiss() }
        Button({
            val t = text.trim(); if (t.isNotEmpty()) Pocket.executeCommand("/goal $t"); onDismiss()
        }, Modifier.padding(22.dp).fillMaxWidth().height(50.dp), enabled = text.isNotBlank(), shape = T.R12, colors = PrimaryColors()) {
            Text("设置目标")
        }
    }
}

@Composable
fun FeedbackSheet(onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    PocketSheet(onDismiss) {
        SheetTitle("反馈")
        OutlinedTextField(text, { text = it }, placeholder = { Text("发送关于当前会话的反馈") }, shape = T.R12,
            modifier = Modifier.padding(horizontal = 22.dp).fillMaxWidth(), minLines = 4)
        Button({
            val t = text.trim(); if (t.isNotEmpty()) Pocket.executeCommand("/feedback $t"); onDismiss()
        }, Modifier.padding(22.dp).fillMaxWidth().height(50.dp), enabled = text.isNotBlank(), shape = T.R12, colors = PrimaryColors()) {
            Text("发送")
        }
    }
}

@Composable private fun Stat(k: String, v: String) = Column { Text(v, fontSize = 17.sp, fontWeight = FontWeight.Medium); Text(k, fontSize = 12.sp, color = T.Text3) }


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


@Composable
fun JobsSheet(sessionId: String, onDismiss: () -> Unit) {
    var jobs by remember { mutableStateOf<List<Triple<String, String, String>>>(emptyList()) }
    var err by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    fun refresh() {
        scope.launch {
            runCatching {
                val r = Pocket.listJobs(sessionId)
                jobs = r["jobs"].arr().orEmpty().mapNotNull { j ->
                    val id = j["id"].str() ?: j["jobId"].str() ?: return@mapNotNull null
                    val status = j["status"].str() ?: j["state"].str() ?: ""
                    val label = j["label"].str() ?: j["command"].str() ?: j["name"].str() ?: id
                    Triple(id, label, status)
                }
                err = null
            }.onFailure { err = it.message }
        }
    }
    LaunchedEffect(sessionId) { refresh() }
    PocketSheet(onDismiss) {
        SheetTitle("后台任务")
        err?.let { Text(it, color = T.Err, fontSize = 13.sp, modifier = Modifier.padding(22.dp)) }
        if (jobs.isEmpty() && err == null) Text("0 个后台任务", fontSize = 14.sp, color = T.Text3, modifier = Modifier.padding(22.dp))
        jobs.forEach { (id, label, status) ->
            OptionRow(label, status.ifBlank { id }, D.clock, trailing = "终止") {
                Pocket.killJob(sessionId, id); refresh()
            }
        }
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
) {
    val cmds = "commands.execute" in Pocket.ops.value
    val export = "session.export" in Pocket.ops.value
    val inChat = Pocket.chat.collectAsState().value != null
    fun pick(then: () -> Unit) {
        Pocket.consumeComposerSlash()
        then()
    }
    PocketSheet(onDismiss) {
        SectionLabel(L.sectionAdd())
        OptionRow(L.file(), icon = D.paperclip, code = "file", enabled = inChat) { pick { onFile(); onDismiss() } }
        OptionRow("目标", icon = D.goal, code = "goal", trailing = "设置或查看长期任务目标", enabled = cmds && inChat) {
            pick { onGoal() }
        }
        OptionRow("计划", icon = D.plan, code = "plan", trailing = "进入或退出计划模式", enabled = cmds && inChat) {
            pick { onPlan(); onDismiss() }
        }
        OptionRow("反馈", icon = D.paperPlane, code = "feedback", trailing = "发送关于当前会话的反馈", enabled = cmds && inChat) {
            pick { onFeedback() }
        }
        SectionLabel(L.sectionCommands())
        OptionRow("压缩", icon = D.compact, code = "compact", trailing = "压缩以上对话内容", enabled = cmds && inChat) {
            pick { onCompact(); onDismiss() }
        }
        OptionRow("权限", icon = D.permission("danger-full-access"), code = "permission", trailing = "切换权限预设") {
            pick { onPerm() }
        }
        OptionRow("模型", icon = D.data, code = "model", trailing = "选择本会话使用的模型") {
            pick { onModel() }
        }
        OptionRow("下载日志", icon = D.download, code = "export", trailing = "将当前会话导出为 ZIP", enabled = export && inChat) {
            pick { onExport(); onDismiss() }
        }
        if (!cmds && !export) Text(L.noOptions(), fontSize = 14.sp, color = T.Text3, modifier = Modifier.padding(22.dp))
    }
}

/** `@` mention popup — files + sessions from Host reference services. */
@Composable
fun MentionPopupSheet(onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var files by remember { mutableStateOf<List<dev.dsh.pocket.protocol.FileReference>>(emptyList()) }
    var sessions by remember { mutableStateOf<List<dev.dsh.pocket.protocol.SessionReference>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    LaunchedEffect(query) {
        loading = true
        Pocket.lookupFileRefs(query) { files = it }
        Pocket.lookupSessionRefs(query) { sessions = it; loading = false }
    }
    PocketSheet(onDismiss) {
        SheetTitle("@")
        OutlinedTextField(
            query, { query = it },
            placeholder = { Text(L.searchEllipsis()) },
            singleLine = true, shape = T.R12,
            modifier = Modifier.padding(horizontal = 22.dp).fillMaxWidth(),
        )
        if (loading) {
            Text(L.searchingSessions(), fontSize = 13.sp, color = T.Caption, modifier = Modifier.padding(22.dp))
        }
        fun pick(token: String) {
            val t = token.trim()
            if (t.isNotEmpty()) Pocket.insertComposer(if (t.startsWith("@")) t else "@$t")
            onDismiss()
        }
        SectionLabel(L.filesAndFolders())
        if (files.isEmpty()) Text(L.noOptions(), fontSize = 13.sp, color = T.Caption, modifier = Modifier.padding(horizontal = 22.dp, vertical = 6.dp))
        files.take(12).forEach { f ->
            OptionRow(f.path.substringAfterLast('/'), f.path, D.folderOpen) { pick(f.path) }
        }
        SectionLabel(L.conversations())
        if (sessions.isEmpty()) Text(L.noOptions(), fontSize = 13.sp, color = T.Caption, modifier = Modifier.padding(horizontal = 22.dp, vertical = 6.dp))
        sessions.take(12).forEach { s ->
            OptionRow(s.title ?: s.mention, s.mention, D.chat) { pick(s.mention) }
        }
        SectionLabel(L.subagents())
        val subs = sessions.filter { it.kind == "subagent" || it.mention.contains("sub", ignoreCase = true) }
        if (subs.isEmpty()) Text(L.noOptions(), fontSize = 13.sp, color = T.Caption, modifier = Modifier.padding(horizontal = 22.dp, vertical = 6.dp))
        else subs.forEach { s -> OptionRow(s.title ?: s.mention, s.mention, D.user) { pick(s.mention) } }
    }
}

/** Dislike → feedback category + optional note (desktop feedback.dialog). */
@Composable
fun FeedbackCategorySheet(onDismiss: () -> Unit, onSubmit: (category: String?, note: String) -> Unit) {
    var category by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf("") }
    PocketSheet(onDismiss) {
        SheetTitle(L.submitFeedback())
        SectionLabel(L.feedbackCategory())
        L.feedbackCategories().forEach { (id, label) ->
            OptionRow(label, selected = category == id) { category = id }
        }
        SectionLabel(L.feedbackDetail())
        OutlinedTextField(
            note, { note = it },
            placeholder = { Text(L.feedbackHint()) },
            shape = T.R12,
            modifier = Modifier.padding(horizontal = 22.dp).fillMaxWidth(),
            minLines = 3,
        )
        Button(
            { onSubmit(category, note.trim()) },
            Modifier.padding(22.dp).fillMaxWidth().height(50.dp),
            shape = T.R12,
            colors = PrimaryColors(),
        ) { Text(L.submitFeedback()) }
    }
}

/** Black primary button; label/progress always white (also under OEM force-dark). */
@Composable
fun PrimaryColors() = ButtonDefaults.buttonColors(containerColor = T.PrimaryButton, contentColor = T.OnPrimary, disabledContainerColor = T.Caption, disabledContentColor = T.OnPrimary)
