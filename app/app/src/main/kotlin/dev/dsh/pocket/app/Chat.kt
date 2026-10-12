package dev.dsh.pocket.app

import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ViewSidebar
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dsh.pocket.protocol.Approval
import dev.dsh.pocket.protocol.Item
import dev.dsh.pocket.protocol.ToolStatus
import kotlinx.coroutines.delay

enum class Sheet { None, Model, Permission, FullAccess, Preset, Plus, More, Panel, Team, Workspace }

@Composable
fun ChatRoot(onMenu: () -> Unit) {
    val chat by Pocket.chat.collectAsState()
    val approvals by Pocket.approvals.collectAsState()
    val catalog by Pocket.catalog.collectAsState()
    val models by Pocket.model.collectAsState()
    val perms by Pocket.permission.collectAsState()
    val sessions by Pocket.sessions.collectAsState()
    val active by Pocket.active.collectAsState()
    val status by Pocket.status.collectAsState()
    var sheet by remember { mutableStateOf(Sheet.None) }
    var tool by remember { mutableStateOf<Item.Tool?>(null) }
    // New-chat drafts (applied when the session gets created).
    var draftPreset by remember { mutableStateOf("standard") }
    var draftPerm by remember { mutableStateOf(Pocket.prefs.defaultPermission) }
    var draftModel by remember { mutableStateOf<ModelChoice?>(null) }
    var draftCwd by remember { mutableStateOf<String?>(null) }

    val sid = chat?.sessionId
    val defaultModel = catalog?.let { c -> c.find(c.defaultProvider, c.defaultModel) ?: c.groups.firstOrNull()?.models?.firstOrNull() }
        ?.let { ModelChoice(it.provider, it.id, it.name, it.defaultEffort) }
    val curModel = (if (sid != null) models[sid] else draftModel) ?: defaultModel
    val curPerm = if (sid != null) perms[sid] ?: "workspace-write" else draftPerm
    val summary = sessions.firstOrNull { it.sessionId == sid }
    val dot = status[active]
    val workspaces = sessions.mapNotNull { it.cwd }.distinct()

    Column(Modifier.fillMaxSize().background(Color.White).systemBarsPadding().imePadding()) {
        TopBar(summary?.title ?: chat?.title, sid != null, dot, chat?.running == true, onMenu, onMore = { sheet = Sheet.More }, onPanel = { sheet = Sheet.Panel },
            onTeam = { sheet = Sheet.Team }, onPreset = { sheet = Sheet.Preset }, preset = "standard", onNew = { Pocket.closeChat() })
        if (chat == null) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { Hero() }
            Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Chip(D.folderOpen, (draftCwd ?: workspaces.firstOrNull())?.substringAfterLast('/') ?: "默认工作区") { sheet = Sheet.Workspace }
                Chip(D.agentPreset, presetLabel(draftPreset)) { sheet = Sheet.Preset }
            }
            Composer("描述你想要构建的内容, / 调用指令, @ 文件或对话", false, curPerm, curModel, enabled = dot == Dot.Online,
                onPlus = { sheet = Sheet.Plus }, onPerm = { sheet = Sheet.Permission }, onModel = { sheet = Sheet.Model },
                onSend = { Pocket.newSession(it, draftPreset, draftPerm, draftModel, draftCwd ?: workspaces.firstOrNull()) }, onStop = {})
            Spacer(Modifier.height(10.dp))
        } else {
            val st = chat!!
            var tab by remember(st.sessionId) { mutableIntStateOf(0) }
            Tabs(tab) { tab = it }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (tab == 0) Transcript(st, onTool = { tool = it }) else EmptyState(D.inspect, "暂无轨迹", "轨迹视图将在后续版本提供")
            }
            approvals.firstOrNull { it.sessionId == null || it.sessionId == st.sessionId }?.let { ApprovalCard(it) }
            Composer("发消息或创建任务, / 调用指令, @ 文件或对话", st.running, curPerm, curModel, enabled = dot == Dot.Online,
                onPlus = { sheet = Sheet.Plus }, onPerm = { sheet = Sheet.Permission }, onModel = { sheet = Sheet.Model },
                onSend = { Pocket.send(it) }, onStop = { Pocket.cancel() })
            Footer(st)
        }
    }

    tool?.let { ToolSheet(it) { tool = null } }
    when (sheet) {
        Sheet.Model -> ModelSheet(catalog, curModel, onDismiss = { sheet = Sheet.None }) { m -> if (sid == null) draftModel = m else Pocket.selectModel(sid, m) }
        Sheet.Permission -> PermissionSheet(curPerm, onDismiss = { sheet = Sheet.None }) { p ->
            if (p == "danger-full-access") sheet = Sheet.FullAccess else { if (sid == null) draftPerm = p else Pocket.setPermission(sid, p); sheet = Sheet.None }
        }
        Sheet.FullAccess -> FullAccessSheet(onDismiss = { sheet = Sheet.None }) { if (sid == null) draftPerm = "danger-full-access" else Pocket.setPermission(sid, "danger-full-access"); sheet = Sheet.None }
        Sheet.Preset -> PresetSheet(if (sid == null) draftPreset else "standard", editable = sid == null, onDismiss = { sheet = Sheet.None }) { draftPreset = it }
        Sheet.Plus -> PlusSheet(onDismiss = { sheet = Sheet.None }, onPerm = { sheet = Sheet.Permission }, onModel = { sheet = Sheet.Model })
        Sheet.More -> MoreSheet(chat, onDismiss = { sheet = Sheet.None })
        Sheet.Panel -> PanelSheet(summary?.cwd, onDismiss = { sheet = Sheet.None })
        Sheet.Team -> TeamSheet(onDismiss = { sheet = Sheet.None })
        Sheet.Workspace -> WorkspaceSheet(workspaces, draftCwd ?: workspaces.firstOrNull(), onDismiss = { sheet = Sheet.None }) { draftCwd = it }
        Sheet.None -> {}
    }
}

@Composable
private fun TopBar(title: String?, inChat: Boolean, dot: Dot?, running: Boolean, onMenu: () -> Unit, onMore: () -> Unit, onPanel: () -> Unit, onTeam: () -> Unit, onPreset: () -> Unit, preset: String, onNew: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onMenu) { Icon(Icons.Rounded.Menu, "菜单", tint = T.Ink) }
        // One weighted region: the title takes what the gray links leave (no competing spacer).
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            if (inChat) {
                Text(title ?: "新会话", style = MaterialTheme.typography.titleMedium, color = T.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Spacer(Modifier.width(10.dp))
                // Icon-only on phones: the session title must never be squeezed to "…".
                GrayLink(D.users, "智能体团队", onTeam)
                GrayLink(D.agentPreset, presetLabel(preset), onPreset)
            }
        }
        StatusDot(dot, Modifier.padding(horizontal = 6.dp))
        IconButton(onMore, Modifier.size(40.dp)) { Icon(D.ellipsis, "更多", tint = T.Ink) }
        if (!inChat) IconButton(onNew, Modifier.size(40.dp)) { Icon(D.newChat, "新会话", tint = T.Ink) }
        IconButton(onPanel, Modifier.size(40.dp)) { Icon(D.panelRight, "右侧面板", tint = T.Ink, modifier = Modifier.size(20.dp)) }
    }
    HorizontalDivider(color = T.Fill)
}

@Composable
private fun GrayLink(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, onClick: () -> Unit) {
    Box(Modifier.size(32.dp).clip(T.R8).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, text, tint = T.Text3, modifier = Modifier.size(18.dp))
    }
}

@Composable
fun StatusDot(dot: Dot?, modifier: Modifier = Modifier) {
    val c = when (dot) { Dot.Online -> T.Ok; Dot.Connecting -> T.WarnBorder; Dot.Unauthorized -> T.Err; else -> T.Caption }
    Box(modifier.size(8.dp).clip(CircleShape).background(c))
}

@Composable
private fun Tabs(sel: Int, onSel: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp)) {
        listOf("对话", "轨迹").forEachIndexed { i, t ->
            Column(Modifier.padding(end = 26.dp).clickable { onSel(i) }, horizontalAlignment = Alignment.CenterHorizontally) {
                Text(t, fontSize = 15.sp, color = if (i == sel) T.Accent else T.Text2, modifier = Modifier.padding(vertical = 10.dp))
                Box(Modifier.height(2.dp).width(28.dp).background(if (i == sel) T.Accent else Color.Transparent))
            }
        }
    }
    HorizontalDivider(color = T.Fill)
}

@Composable
private fun Hero() {
    // Match desktop EmptyHero: fish + headline + preview badge only — no brand title above.
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(D.fish, null, tint = T.Ink, modifier = Modifier.width(34.dp).height(25.dp))
        Spacer(Modifier.width(10.dp))
        Text("探索未至之境", fontSize = 26.sp, lineHeight = 32.sp, fontWeight = FontWeight.Medium, color = T.Ink)
        Spacer(Modifier.width(7.dp))
        Text("预览版", fontSize = 12.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium, fontFamily = FontFamily.Monospace, color = T.Bluish,
            modifier = Modifier.align(Alignment.Top).padding(top = 2.dp).clip(RoundedCornerShape(50)).background(T.Tint)
                .border(0.5.dp, T.HoverBg, RoundedCornerShape(50)).padding(start = 7.dp, end = 7.dp, top = 1.dp))
    }
}

@Composable
private fun Chip(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, onClick: () -> Unit) {
    Row(Modifier.clip(T.R8).clickable(onClick = onClick).padding(vertical = 6.dp, horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = T.Ink, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(5.dp))
        Text(text, fontSize = 14.sp, color = T.Ink); Icon(D.chevronDown, null, tint = T.Ink, modifier = Modifier.size(18.dp))
    }
}

@Composable
fun EmptyState(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, body: String? = null, action: (@Composable () -> Unit)? = null) {
    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(Modifier.size(56.dp).clip(CircleShape).background(T.Fill2), contentAlignment = Alignment.Center) { Icon(icon, null, tint = T.Text3) }
        Spacer(Modifier.height(14.dp)); Text(title, fontSize = 16.sp, color = T.Ink, fontWeight = FontWeight.Medium)
        body?.let { Spacer(Modifier.height(6.dp)); Text(it, fontSize = 13.sp, color = T.Text3) }
        action?.let { Spacer(Modifier.height(18.dp)); it() }
    }
}

@Composable
private fun Transcript(st: ChatState, onTool: (Item.Tool) -> Unit) {
    val list = rememberLazyListState()
    val n = st.items.size
    val lastLen = (st.items.lastOrNull() as? Item.Text)?.text?.length ?: 0
    LaunchedEffect(n, lastLen, st.running) { if (n > 0) list.animateScrollToItem(n) }
    if (st.loaded && n == 0 && !st.running) { EmptyState(D.chat, "开始对话", "发送第一条消息"); return }
    if (!st.loaded) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = T.Accent, strokeWidth = 2.dp, modifier = Modifier.size(24.dp)) }; return }
    LazyColumn(state = list, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(st.items, key = { it.key }) { item ->
            when (item) {
                is Item.User -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Text(item.text, style = MaterialTheme.typography.bodyLarge, color = T.Ink.copy(alpha = if (item.pending) 0.6f else 1f), modifier = Modifier.padding(start = 56.dp).clip(T.R20).background(T.Fill).padding(horizontal = 16.dp, vertical = 10.dp))
                }
                is Item.Reasoning -> ReasoningRow(item)
                is Item.Text -> Markdownish(item.text)
                is Item.Tool -> ToolCard(item) { onTool(item) }
                is Item.Notice -> Text(item.text, fontSize = 13.sp, color = T.Text3)
            }
        }
        if (st.running) item(key = "status") { RunningLine(st.runStartedAt) }
    }
}

@Composable
private fun ReasoningRow(r: Item.Reasoning) {
    var open by remember { mutableStateOf(false) }
    Row(Modifier.height(IntrinsicSize.Min)) {
        Box(Modifier.width(2.dp).fillMaxHeight().background(T.Fill))
        Column(Modifier.padding(start = 12.dp).clickable { open = !open }) {
            Text(if (r.live) "深度思考中…" else "已深度思考${r.durationMs?.let { " ${seconds(it)} 秒" } ?: ""} ${if (open) "⌄" else "›"}", fontSize = 14.sp, color = T.Text3)
            if (open || r.live) Text(r.text, fontSize = 14.sp, lineHeight = 22.sp, color = T.Text3, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

@Composable
private fun ToolCard(t: Item.Tool, onClick: () -> Unit) {
    val warn = t.status == ToolStatus.AwaitingApproval
    Row(Modifier.fillMaxWidth().clip(T.R12).border(1.dp, if (warn) T.WarnBorder else T.Border, T.R12).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        when (t.status) {
            ToolStatus.Ok -> Text("✓", color = T.Ok, fontSize = 16.sp)
            ToolStatus.Error -> Text("✕", color = T.Err, fontSize = 15.sp)
            ToolStatus.AwaitingApproval -> Text("!", color = T.WarnText, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            ToolStatus.Running -> CircularProgressIndicator(Modifier.size(14.dp), color = T.Accent, strokeWidth = 1.5.dp)
        }
        Spacer(Modifier.width(10.dp))
        Text(t.name, fontSize = 15.sp, color = T.Ink); Spacer(Modifier.width(10.dp))
        Text(primaryArg(t.name, t.arguments).orEmpty(), style = T.Mono.copy(color = T.Text3), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Icon(D.chevronRight, null, tint = T.Caption, modifier = Modifier.size(18.dp))
    }
}

/** Minimal markdown: ``` fences as code blocks, `inline` code, **bold**. */
@Composable
private fun Markdownish(text: String) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        text.split("```").forEachIndexed { i, part ->
            if (i % 2 == 1) {
                val code = part.substringAfter('\n', part).trimEnd()
                Text(code, style = T.Mono.copy(lineHeight = 21.sp), modifier = Modifier.fillMaxWidth().clip(T.R12).background(T.Fill2).horizontalScroll(rememberScrollState()).padding(14.dp))
            } else if (part.isNotBlank()) {
                Text(inline(part.trim()), style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

private fun inline(s: String) = buildAnnotatedString {
    var i = 0
    val re = Regex("`([^`]+)`|\\*\\*([^*]+)\\*\\*")
    for (m in re.findAll(s)) {
        append(s.substring(i, m.range.first))
        if (m.groups[1] != null) withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = T.Fill, fontSize = 14.sp)) { append(" ${m.groupValues[1]} ") }
        else withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(m.groupValues[2]) }
        i = m.range.last + 1
    }
    append(s.substring(i))
}

@Composable
private fun RunningLine(since: Long?) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(1000); now = System.currentTimeMillis() } }
    val dots = rememberInfiniteTransition(label = "dots").animateFloat(0f, 3f, infiniteRepeatable(tween(1200, easing = LinearEasing)), label = "d")
    val secs = since?.let { (now - it) / 1000 } ?: 0
    Text("深度求索中，用时 ${secs}s " + "·".repeat(dots.value.toInt() + 1), fontSize = 14.sp, color = T.Text3)
}

@Composable
private fun ApprovalCard(a: Approval) {
    Column(Modifier.padding(horizontal = 14.dp, vertical = 6.dp).fillMaxWidth().clip(T.R16).border(1.dp, T.WarnBorder, T.R16)) {
        Row(Modifier.fillMaxWidth().background(T.WarnTint).padding(horizontal = 18.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(T.WarnBorder)); Spacer(Modifier.width(8.dp))
            Text("等待审批", fontSize = 14.sp, color = T.WarnText)
        }
        Column(Modifier.padding(18.dp)) {
            Text("工具 ${a.toolName} 请求越权执行", fontSize = 16.sp, color = T.Ink)
            (approvalCommand(a) ?: a.reason)?.let { Spacer(Modifier.height(8.dp)); Text(it, style = T.Mono.copy(color = T.Text3, fontSize = 14.sp, lineHeight = 21.sp), maxLines = 4, overflow = TextOverflow.Ellipsis) }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton({ Pocket.answer(a.approvalId, "rejected") }, Modifier.weight(1f).height(52.dp), shape = T.R12, border = BorderStroke(1.dp, T.Border),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = T.Ink)) { Text("拒绝", fontSize = 16.sp) }
                Button({ Pocket.answer(a.approvalId, "allowed-once") }, Modifier.weight(1f).height(52.dp), shape = T.R12,
                    colors = PrimaryColors()) { Text("允许一次", fontSize = 16.sp) }
            }
        }
    }
}

@Composable
private fun Composer(placeholder: String, running: Boolean, perm: String, model: ModelChoice?, enabled: Boolean,
                     onPlus: () -> Unit, onPerm: () -> Unit, onModel: () -> Unit, onSend: (String) -> Unit, onStop: () -> Unit) {
    var text by remember { mutableStateOf("") }
    Column(Modifier.padding(horizontal = 14.dp, vertical = 4.dp).fillMaxWidth().clip(T.R20).border(1.dp, T.Border, T.R20).padding(start = 16.dp, end = 10.dp, top = 14.dp, bottom = 10.dp)) {
        Box {
            if (text.isEmpty()) Text(placeholder, fontSize = 16.sp, color = T.Caption, maxLines = 2, overflow = TextOverflow.Ellipsis)
            BasicTextField(text, { text = it }, textStyle = MaterialTheme.typography.bodyLarge, cursorBrush = SolidColor(T.Accent),
                modifier = Modifier.fillMaxWidth().heightIn(max = 160.dp))
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(32.dp).clip(CircleShape).background(T.Fill).clickable(onClick = onPlus), contentAlignment = Alignment.Center) { Icon(D.plus, "添加", tint = T.Ink, modifier = Modifier.size(18.dp)) }
            Spacer(Modifier.width(8.dp))
            // Narrow phones: access mode collapses to its icon (the sheet shows the full label); the model keeps priority.
            BoxWithConstraints(Modifier.weight(1f)) {
                val roomy = maxWidth >= 300.dp
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val danger = perm == "danger-full-access"
                    Row(Modifier.clip(T.R8).clickable(onClick = onPerm).padding(horizontal = 6.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (danger) D.warning else D.shield, permissionLabel(perm), tint = if (danger) T.WarnText else T.Text2, modifier = Modifier.size(17.dp))
                        if (roomy) { Spacer(Modifier.width(5.dp)); Text(permissionLabel(perm), fontSize = 14.sp, color = T.Text2, maxLines = 1) }
                        Icon(D.chevronDown, null, tint = T.Text2, modifier = Modifier.size(16.dp))
                    }
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End) { Row(Modifier.clip(T.R8).clickable(onClick = onModel).padding(horizontal = 6.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(model?.let { shortModelName(it.name) } ?: "选择模型", fontSize = 14.sp, color = T.Text2, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        model?.effort?.let { Spacer(Modifier.width(5.dp)); Text(it.replaceFirstChar(Char::uppercase), fontSize = 14.sp, color = T.Caption, maxLines = 1, softWrap = false) }
                        Icon(D.chevronDown, null, tint = T.Text2, modifier = Modifier.size(16.dp))
                    } }
                }
            }
            Spacer(Modifier.width(8.dp))
            val canSend = enabled && text.isNotBlank()
            if (running && text.isBlank()) {
                Box(Modifier.size(38.dp).clip(CircleShape).background(T.Ink).clickable(onClick = onStop), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(11.dp).clip(T.R4).background(Color.White))
                }
            } else {
                Box(Modifier.size(38.dp).clip(CircleShape).background(if (canSend) T.Accent else T.SendDisabled).clickable(enabled = canSend) { onSend(text.trim()); text = "" }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.ArrowUpward, "发送", tint = Color.White, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

@Composable
private fun Footer(st: ChatState) {
    val u = st.usage
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        Text("${u.turns} 轮 ${u.steps} 步", fontSize = 12.sp, color = T.Text3)
        u.cacheHitPercent?.let { Spacer(Modifier.width(14.dp)); Text("缓存命中 $it%", fontSize = 12.sp, color = T.Text3) }
    }
}
