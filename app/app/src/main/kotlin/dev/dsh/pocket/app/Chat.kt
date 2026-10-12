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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.graphics.BitmapFactory
import dev.dsh.pocket.protocol.Approval
import dev.dsh.pocket.protocol.ImageRef
import dev.dsh.pocket.protocol.Item
import dev.dsh.pocket.protocol.PromptFile
import dev.dsh.pocket.protocol.PromptImage
import dev.dsh.pocket.protocol.ToolStatus
import dev.dsh.pocket.protocol.UserQuestion
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

enum class AttachStatus { Pending, Uploading, Ready, Failed }

data class PendingAttach(
    val name: String,
    val bytes: ByteArray,
    val status: AttachStatus = AttachStatus.Pending,
    val receiptId: String? = null,
)

enum class Sheet {
    None, Model, Permission, FullAccess, Preset, Plus, More, Panel, Team, Workspace, Goal, Feedback,
    Files, Jobs, Terminal, Todo, Commands, Mentions, FeedbackCategory,
}

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
    val ops by Pocket.ops.collectAsState()
    var sheet by remember { mutableStateOf(Sheet.None) }
    var tool by remember { mutableStateOf<Item.Tool?>(null) }
    // New-chat drafts (applied when the session gets created).
    var draftPreset by remember { mutableStateOf("standard") }
    var draftPerm by remember { mutableStateOf(Pocket.prefs.defaultPermission) }
    var draftModel by remember { mutableStateOf<ModelChoice?>(null) }
    var draftCwd by remember { mutableStateOf<String?>(null) }
    var pendingImages by remember { mutableStateOf<List<PromptImage>>(emptyList()) }
    var pendingFiles by remember { mutableStateOf<List<PendingAttach>>(emptyList()) }
    var feedbackTarget by remember { mutableStateOf<String?>(null) }
    val questions by Pocket.userQuestions.collectAsState()
    val feedbackMap by Pocket.feedback.collectAsState()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val pickFiles = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetMultipleContents(),
    ) { uris ->
        val st = Pocket.chat.value ?: return@rememberLauncherForActivityResult
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        val images = ArrayList<PromptImage>()
        val files = ArrayList<PendingAttach>()
        uris.forEach { uri ->
            runCatching {
                val mime = ctx.contentResolver.getType(uri) ?: "application/octet-stream"
                val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@runCatching
                val name = uri.lastPathSegment?.substringAfterLast('/') ?: "file"
                val b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                if (mime.startsWith("image/")) {
                    images += PromptImage(mime, b64, name)
                } else {
                    files += PendingAttach(name, bytes, status = AttachStatus.Pending)
                }
            }
        }
        if (images.isNotEmpty()) pendingImages = pendingImages + images
        if (files.isNotEmpty()) {
            pendingFiles = pendingFiles + files
            // Upload non-images via attachments.upload
            files.forEachIndexed { i, f ->
                val idx = pendingFiles.size - files.size + i
                scope.launch {
                    pendingFiles = pendingFiles.toMutableList().also {
                        if (idx in it.indices) it[idx] = it[idx].copy(status = AttachStatus.Uploading)
                    }
                    val receipt = Pocket.uploadFile(st.sessionId, f.bytes, f.name)
                    pendingFiles = pendingFiles.toMutableList().also {
                        if (idx in it.indices) {
                            it[idx] = if (receipt != null) it[idx].copy(status = AttachStatus.Ready, receiptId = receipt.receiptId)
                            else it[idx].copy(status = AttachStatus.Failed)
                        }
                    }
                }
            }
        }
    }
    fun doExport() {
        Pocket.exportSession { name, bytes ->
            runCatching {
                val out = java.io.File(ctx.cacheDir, name)
                out.writeBytes(bytes)
                val uri = androidx.core.content.FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", out)
                val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = "application/zip"
                    putExtra(android.content.Intent.EXTRA_STREAM, uri)
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                ctx.startActivity(android.content.Intent.createChooser(intent, name))
            }.onFailure { Pocket.errors.tryEmit(it.message ?: "导出失败") }
        }
    }

    val sid = chat?.sessionId
    val defaultModel = catalog?.let { c -> c.find(c.defaultProvider, c.defaultModel) ?: c.groups.firstOrNull()?.models?.firstOrNull() }
        ?.let { ModelChoice(it.provider, it.id, it.name, it.defaultEffort) }
    val curModel = (if (sid != null) models[sid] else draftModel) ?: defaultModel
    val curPerm = if (sid != null) perms[sid] ?: "workspace-write" else draftPerm
    val summary = sessions.firstOrNull { it.sessionId == sid }
    val dot = status[active]
    val workspaces = sessions.mapNotNull { it.cwd }.distinct()
    val showPanel = "files.list" in ops || "jobs.list" in ops || "terminal.create" in ops || (chat?.todos?.isNotEmpty() == true)

    Column(Modifier.fillMaxSize().background(Color.White).systemBarsPadding().imePadding()) {
        TopBar(summary?.title ?: chat?.title, sid != null, dot, onMenu, onMore = { sheet = Sheet.More },
            onPanel = if (showPanel) ({ sheet = Sheet.Panel }) else null,
            onTeam = { sheet = Sheet.Team }, onPreset = { sheet = Sheet.Preset }, preset = "standard", onNew = { Pocket.closeChat() })
        if (chat == null) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { Hero() }
            Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Chip(D.folderOpen, (draftCwd ?: workspaces.firstOrNull())?.substringAfterLast('/') ?: "默认工作区") { sheet = Sheet.Workspace }
                Chip(D.agentPreset, presetLabel(draftPreset)) { sheet = Sheet.Preset }
            }
            Composer(
                "描述你想要构建的内容, / 调用指令, @ 文件或对话", false, curPerm, curModel, enabled = dot == Dot.Online,
                pendingImages = pendingImages, pendingFiles = pendingFiles,
                onPlus = { sheet = Sheet.Plus }, onPerm = { sheet = Sheet.Permission }, onModel = { sheet = Sheet.Model },
                onRemoveImage = { i -> pendingImages = pendingImages.toMutableList().also { it.removeAt(i) } },
                onRemoveFile = { i -> pendingFiles = pendingFiles.toMutableList().also { it.removeAt(i) } },
                onRetryFile = { i ->
                    val f = pendingFiles.getOrNull(i) ?: return@Composer
                    val stId = Pocket.chat.value?.sessionId ?: return@Composer
                    pendingFiles = pendingFiles.toMutableList().also { it[i] = f.copy(status = AttachStatus.Uploading) }
                    scope.launch {
                        val receipt = Pocket.uploadFile(stId, f.bytes, f.name)
                        pendingFiles = pendingFiles.toMutableList().also {
                            if (i in it.indices) it[i] = if (receipt != null) f.copy(status = AttachStatus.Ready, receiptId = receipt.receiptId)
                            else f.copy(status = AttachStatus.Failed)
                        }
                    }
                },
                onSlash = { sheet = Sheet.Commands },
                onMention = { sheet = Sheet.Mentions },
                onSend = { text ->
                    Pocket.newSession(text, draftPreset, draftPerm, draftModel, draftCwd ?: workspaces.firstOrNull())
                    pendingImages = emptyList(); pendingFiles = emptyList()
                },
                onStop = {},
            )
            Spacer(Modifier.height(10.dp))
        } else {
            val st = chat!!
            var tab by remember(st.sessionId) { mutableIntStateOf(0) }
            Tabs(tab) { tab = it }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (tab == 0) Transcript(
                    st,
                    onTool = { tool = it },
                    canFork = "sessions.fork" in ops && !st.running,
                    onFork = { seq -> Pocket.forkSession(seq) },
                    feedback = feedbackMap,
                    onLike = { id ->
                        val cur = feedbackMap[id]
                        if (cur?.rating == "positive") Pocket.setMessageFeedback(id, null)
                        else Pocket.setMessageFeedback(id, "positive")
                    },
                    onDislike = { id ->
                        feedbackTarget = id
                        sheet = Sheet.FeedbackCategory
                    },
                ) else TrajectoryView(st)
            }
            questions.firstOrNull { it.sessionId == null || it.sessionId == st.sessionId }?.let { UserQuestionCard(it) }
            approvals.firstOrNull { it.sessionId == null || it.sessionId == st.sessionId }?.let { ApprovalCard(it) }
            Composer(
                "发消息或创建任务, / 调用指令, @ 文件或对话", st.running, curPerm, curModel, enabled = dot == Dot.Online,
                pendingImages = pendingImages, pendingFiles = pendingFiles,
                onPlus = { sheet = Sheet.Plus }, onPerm = { sheet = Sheet.Permission }, onModel = { sheet = Sheet.Model },
                onRemoveImage = { i -> pendingImages = pendingImages.toMutableList().also { it.removeAt(i) } },
                onRemoveFile = { i -> pendingFiles = pendingFiles.toMutableList().also { it.removeAt(i) } },
                onRetryFile = { i ->
                    val f = pendingFiles.getOrNull(i) ?: return@Composer
                    pendingFiles = pendingFiles.toMutableList().also { it[i] = f.copy(status = AttachStatus.Uploading) }
                    scope.launch {
                        val receipt = Pocket.uploadFile(st.sessionId, f.bytes, f.name)
                        pendingFiles = pendingFiles.toMutableList().also {
                            if (i in it.indices) it[i] = if (receipt != null) f.copy(status = AttachStatus.Ready, receiptId = receipt.receiptId)
                            else f.copy(status = AttachStatus.Failed)
                        }
                    }
                },
                onSlash = { sheet = Sheet.Commands },
                onMention = { sheet = Sheet.Mentions },
                onSend = { text ->
                    val ready = pendingFiles.filter { it.status == AttachStatus.Ready && it.receiptId != null }
                    val imgs = pendingImages
                    when {
                        imgs.isNotEmpty() || ready.isNotEmpty() ->
                            Pocket.sendWithFiles(text, imgs, ready.map { PromptFile(it.receiptId!!) })
                        else -> Pocket.send(text)
                    }
                    pendingImages = emptyList(); pendingFiles = emptyList()
                },
                onStop = { Pocket.cancel() },
            )
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
        Sheet.Plus -> PlusSheet(
            onDismiss = { sheet = Sheet.None },
            onPerm = { sheet = Sheet.Permission },
            onModel = { sheet = Sheet.Model },
            onFile = { pickFiles.launch("*/*") },
            onGoal = { sheet = Sheet.Goal },
            onPlan = { Pocket.executeCommand("/plan") },
            onFeedback = { sheet = Sheet.Feedback },
            onCompact = { Pocket.executeCommand("/compact") },
            onExport = { doExport() },
        )
        Sheet.More -> MoreSheet(chat, onDismiss = { sheet = Sheet.None }, onFeedback = { sheet = Sheet.Feedback }, onExport = { doExport() })
        Sheet.Panel -> PanelSheet(
            summary?.cwd,
            todos = chat?.todos.orEmpty(),
            onDismiss = { sheet = Sheet.None },
            onFiles = { sheet = Sheet.Files },
            onTerminal = { sheet = Sheet.Terminal },
            onJobs = { sheet = Sheet.Jobs },
            onTodo = { sheet = Sheet.Todo },
        )
        Sheet.Team -> TeamSheet(onDismiss = { sheet = Sheet.None })
        Sheet.Workspace -> WorkspaceSheet(workspaces, draftCwd ?: workspaces.firstOrNull(), onDismiss = { sheet = Sheet.None }) { draftCwd = it }
        Sheet.Goal -> GoalSheet(onDismiss = { sheet = Sheet.None })
        Sheet.Feedback -> FeedbackSheet(onDismiss = { sheet = Sheet.None })
        Sheet.FeedbackCategory -> FeedbackCategorySheet(
            onDismiss = { sheet = Sheet.None; feedbackTarget = null },
            onSubmit = { cat, note ->
                feedbackTarget?.let { Pocket.setMessageFeedback(it, "negative", cat, note) }
                sheet = Sheet.None; feedbackTarget = null
            },
        )
        Sheet.Commands -> CommandPopupSheet(onDismiss = { sheet = Sheet.None })
        Sheet.Mentions -> MentionPopupSheet(onDismiss = { sheet = Sheet.None })
        Sheet.Files -> sid?.let { FilesSheet(it, onDismiss = { sheet = Sheet.None }) } ?: run { sheet = Sheet.None }
        Sheet.Jobs -> sid?.let { JobsSheet(it, onDismiss = { sheet = Sheet.None }) } ?: run { sheet = Sheet.None }
        Sheet.Terminal -> sid?.let { TerminalSheet(it, onDismiss = { sheet = Sheet.None }) } ?: run { sheet = Sheet.None }
        Sheet.Todo -> TodoSheet(chat?.todos.orEmpty(), onDismiss = { sheet = Sheet.None })
        Sheet.None -> {}
    }
}

@Composable
private fun TopBar(title: String?, inChat: Boolean, dot: Dot?, onMenu: () -> Unit, onMore: () -> Unit, onPanel: (() -> Unit)?, onTeam: () -> Unit, onPreset: () -> Unit, preset: String, onNew: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onMenu) { Icon(D.panelLeft, L.menu(), tint = T.Ink, modifier = Modifier.size(20.dp)) }
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
        if (onPanel != null) IconButton(onPanel, Modifier.size(40.dp)) { Icon(D.panelRight, "右侧面板", tint = T.Ink, modifier = Modifier.size(20.dp)) }
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
    val c = when (dot) {
        Dot.Online -> T.Ok
        Dot.Connecting -> T.WarnBorder
        Dot.Unauthorized -> T.Err
        else -> T.Caption
    }
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
private fun Transcript(
    st: ChatState,
    onTool: (Item.Tool) -> Unit,
    canFork: Boolean = false,
    onFork: (Long?) -> Unit = {},
    feedback: Map<String, dev.dsh.pocket.protocol.MessageFeedback> = emptyMap(),
    onLike: (String) -> Unit = {},
    onDislike: (String) -> Unit = {},
) {
    val list = rememberLazyListState()
    // Prefs are SharedPreferences-backed; read each composition for mode switches.
    val livePolicy = presentationPolicyFor(Pocket.prefs.transcriptView, Pocket.prefs.collapseTiming)
    val rows = remember(st.items, st.running, livePolicy) {
        foldChatItems(st.items, livePolicy, st.running)
    }
    val n = rows.size
    val lastLen = (st.items.lastOrNull() as? Item.Text)?.text?.length ?: 0
    // Desktop: branch only on the last message of a completed turn.
    val branchKey = rows.lastOrNull { it is ChatRow.User || it is ChatRow.Answer }?.key
    LaunchedEffect(n, lastLen, st.running) { if (n > 0 && !st.loadingOlder) list.animateScrollToItem((n - 1).coerceAtLeast(0)) }
    // Scroll-to-top loads older history (no extra chrome — same LazyColumn).
    var historyArmed by remember(st.sessionId) { mutableStateOf(false) }
    LaunchedEffect(list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset) {
        if (list.firstVisibleItemIndex > 0 || list.firstVisibleItemScrollOffset > 0) historyArmed = true
    }
    LaunchedEffect(list.firstVisibleItemIndex, st.hasMore, st.loadingOlder, st.loaded, n) {
        if (historyArmed && st.loaded && st.hasMore && !st.loadingOlder && n > 0 &&
            list.firstVisibleItemIndex == 0 && list.firstVisibleItemScrollOffset == 0
        ) {
            historyArmed = false
            Pocket.loadOlderHistory()
        }
    }
    if (st.loaded && st.items.isEmpty() && !st.running) { EmptyState(D.chat, "开始对话", "发送第一条消息"); return }
    if (!st.loaded) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = T.Accent, strokeWidth = 2.dp, modifier = Modifier.size(24.dp)) }; return }
    LazyColumn(state = list, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items(rows, key = { it.key }) { row ->
            when (row) {
                is ChatRow.User -> {
                    val branchHere = canFork && row.key == branchKey
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
                        if (row.item.imageRefs.isNotEmpty()) {
                            row.item.imageRefs.forEach { ref ->
                                ImageBubble(st.sessionId, ref)
                                Spacer(Modifier.height(6.dp))
                            }
                        } else if (row.item.images > 0) {
                            Text(L.image() + " ×${row.item.images}", fontSize = 13.sp, color = T.Text3, modifier = Modifier.padding(bottom = 4.dp))
                        }
                        if (row.item.text.isNotBlank()) {
                            SelectableOutput {
                                Text(
                                    row.item.text,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = T.Ink.copy(alpha = if (row.item.pending) 0.6f else 1f),
                                    modifier = Modifier.padding(start = 56.dp).clip(T.R20).background(T.Fill).padding(horizontal = 16.dp, vertical = 10.dp),
                                )
                            }
                        }
                        MessageActions(
                            text = row.item.text,
                            timeMs = row.item.at,
                            clock = MessageClock.Start,
                            branchEnabled = branchHere,
                            branchUnavailable = canFork && !branchHere,
                            onBranch = if (canFork) ({ onFork(row.item.seq) }) else null,
                        )
                    }
                }
                is ChatRow.Answer -> {
                    val branchHere = canFork && row.key == branchKey && !row.item.live
                    val mid = row.item.messageId
                    val rating = mid?.let { feedback[it]?.rating }
                    Column(Modifier.fillMaxWidth()) {
                        MarkdownView(row.item.text, streaming = row.item.live)
                        if (!row.item.live) MessageActions(
                            text = row.item.text,
                            timeMs = row.item.at,
                            clock = MessageClock.End,
                            branchEnabled = branchHere,
                            branchUnavailable = canFork && !branchHere,
                            onBranch = if (canFork) ({ onFork(row.item.seq) }) else null,
                            messageId = mid,
                            rating = rating,
                            onLike = mid?.let { { onLike(it) } },
                            onDislike = mid?.let { { onDislike(it) } },
                        )
                    }
                }
                is ChatRow.Notice -> SelectableOutput {
                    Text(row.item.text.breakAnywhere(), fontSize = 13.sp, color = T.Text3, softWrap = true, modifier = Modifier.fillMaxWidth())
                }
                is ChatRow.Tool -> ToolCard(row.item) { onTool(row.item) }
                is ChatRow.Reasoning -> ReasoningRow(row.item)
                is ChatRow.Process -> ProcessGroupRow(row, onTool)
                is ChatRow.Deliverable -> DeliverableCard(row.item)
                is ChatRow.Changes -> ChangesCard(row.item)
            }
        }
        if (st.running) item(key = "status") { RunningLine(st.runStartedAt) }
    }
}

private enum class MessageClock { Start, End }

/**
 * Desktop MessageIconActions row: icon-only copy / feedback / branch + clock.
 * Order matches desktop: (user clock?) → copy → like/dislike → branch → (assistant clock?).
 * Locale strings live in contentDescription / long-press tooltip only.
 */
@Composable
private fun MessageActions(
    text: String,
    timeMs: Long?,
    clock: MessageClock,
    branchEnabled: Boolean,
    branchUnavailable: Boolean,
    onBranch: (() -> Unit)?,
    messageId: String? = null,
    rating: String? = null,
    onLike: (() -> Unit)? = null,
    onDislike: (() -> Unit)? = null,
) {
    val clip = androidx.compose.ui.platform.LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    val canFeedback = messageId != null && "feedback.set" in Pocket.ops.value
    val iconDp = if (clock == MessageClock.End) 17.dp else 15.dp
    LaunchedEffect(copied) {
        if (copied) {
            kotlinx.coroutines.delay(1000)
            copied = false
        }
    }
    Row(
        Modifier
            .padding(top = 4.dp)
            .height(28.dp)
            .fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (clock == MessageClock.Start) Arrangement.End else Arrangement.Start,
    ) {
        if (clock == MessageClock.Start && timeMs != null) {
            Text(
                formatMessageClock(timeMs),
                fontSize = 12.sp,
                color = T.Caption,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.padding(end = 12.dp),
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ActionIconButton(
                icon = if (copied) D.check else D.copy,
                label = if (copied) L.copied() else L.copy(),
                tint = T.Caption,
                iconSize = iconDp,
                enabled = !copied,
                testTag = "messageCopy",
            ) {
                clip.setText(androidx.compose.ui.text.AnnotatedString(text))
                copied = true
            }
            // Desktop order: copy → feedback (extraActions) → branch
            if (canFeedback && onLike != null && onDislike != null) {
                val likeLabel = if (rating == "positive") L.undoRating() else L.like()
                ActionIconButton(
                    icon = if (rating == "positive") D.likeFill else D.like,
                    label = likeLabel,
                    tint = if (rating == "positive") T.Accent else T.Caption,
                    iconSize = iconDp,
                    testTag = "messageLike",
                    onClick = onLike,
                )
                val dislikeLabel = if (rating == "negative") L.undoRating() else L.dislike()
                ActionIconButton(
                    icon = if (rating == "negative") D.dislikeFill else D.dislike,
                    label = dislikeLabel,
                    tint = if (rating == "negative") T.Err else T.Caption,
                    iconSize = iconDp,
                    testTag = "messageDislike",
                ) {
                    if (rating == "negative") Pocket.setMessageFeedback(messageId!!, null) else onDislike()
                }
            }
            if (onBranch != null) {
                val tip = if (branchUnavailable) L.branchUnavailable() else L.branch()
                ActionIconButton(
                    icon = D.branch,
                    label = tip,
                    ariaLabel = L.branch(),
                    tint = T.Caption,
                    iconSize = iconDp,
                    enabled = branchEnabled,
                    alpha = if (branchUnavailable) 0.4f else 1f,
                    testTag = "messageBranch",
                    onClick = onBranch,
                )
            }
        }
        if (clock == MessageClock.End && timeMs != null) {
            Text(
                formatMessageClock(timeMs),
                fontSize = 12.sp,
                color = T.Caption,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

/** 28dp hit target matching desktop `.action`; tooltip = long-press / contentDescription. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ActionIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: Color,
    iconSize: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
    ariaLabel: String = label,
    enabled: Boolean = true,
    alpha: Float = 1f,
    testTag: String? = null,
    onClick: () -> Unit,
) {
    val tipState = rememberTooltipState()
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(label, fontSize = 12.sp) } },
        state = tipState,
    ) {
        Box(
            modifier
                .size(28.dp)
                .clip(T.R8)
                .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
                .semantics { contentDescription = ariaLabel }
                .then(if (testTag != null) Modifier.testTag(testTag) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = tint.copy(alpha = alpha), modifier = Modifier.size(iconSize))
        }
    }
}

@Composable
private fun ProcessGroupRow(proc: ChatRow.Process, onTool: (Item.Tool) -> Unit) {
    var open by remember(proc.key, proc.defaultOpen) { mutableStateOf(proc.defaultOpen) }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { open = !open }
                .padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val label = when {
                !proc.closed -> proc.title
                proc.durationMs != null && proc.tools.isNotEmpty() ->
                    "已完成，用时 ${seconds(proc.durationMs)}秒"
                else -> proc.title
            }
            Text(label, fontSize = 14.sp, color = T.Text3, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            Text(if (open) " ⌄" else " ›", fontSize = 14.sp, color = T.Text3)
        }
        if (open) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                proc.reasoning.forEach { ReasoningRow(it, forceOpen = true) }
                proc.tools.forEach { ToolCard(it) { onTool(it) } }
            }
        }
        HorizontalDivider(color = T.Border, thickness = 0.5.dp)
    }
}

@Composable
private fun ReasoningRow(r: Item.Reasoning, forceOpen: Boolean = false) {
    val verbose = Pocket.prefs.transcriptView == TranscriptView.Verbose
    var open by remember(r.key, verbose, forceOpen) { mutableStateOf(forceOpen || verbose) }
    Row(Modifier.height(IntrinsicSize.Min)) {
        Box(Modifier.width(2.dp).fillMaxHeight().background(T.Fill))
        Column(Modifier.padding(start = 12.dp)) {
            // Fold chrome stays clickable; body is independently selectable.
            Text(
                if (r.live) "深度求索中…"
                else "已思考${r.durationMs?.let { " ${seconds(it)} 秒" } ?: ""} ${if (open) "⌄" else "›"}",
                fontSize = 14.sp, color = T.Text3,
                modifier = Modifier.clickable { open = !open },
            )
            if (open || r.live) {
                SelectableOutput {
                    Text(
                        r.text.breakAnywhere(),
                        fontSize = Pocket.prefs.fontSize.sp,
                        lineHeight = (Pocket.prefs.fontSize + 8).sp,
                        color = T.Text3,
                        softWrap = true,
                        modifier = Modifier.padding(top = 6.dp).fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun ToolCard(t: Item.Tool, onClick: () -> Unit) {
    val warn = t.status == ToolStatus.AwaitingApproval
    val showArg = Pocket.prefs.transcriptView != TranscriptView.Compact
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
        if (showArg) Text(primaryArg(t.name, t.arguments).orEmpty(), style = T.Mono.copy(color = T.Text3), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        else Spacer(Modifier.weight(1f))
        Icon(D.chevronRight, null, tint = T.Caption, modifier = Modifier.size(18.dp))
    }
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
            SelectableOutput {
                Text("工具 ${a.toolName} 请求越权执行", fontSize = 16.sp, color = T.Ink)
                (approvalCommand(a) ?: a.reason)?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        it.breakAnywhere(),
                        style = T.Mono.copy(color = T.Text3, fontSize = 14.sp, lineHeight = 21.sp),
                        softWrap = true,
                        maxLines = 8,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
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
private fun ImageBubble(sessionId: String, ref: ImageRef) {
    var bitmap by remember(ref.attachmentId) { mutableStateOf<ImageBitmap?>(null) }
    var failed by remember(ref.attachmentId) { mutableStateOf(false) }
    var loading by remember(ref.attachmentId) { mutableStateOf(true) }
    var attempt by remember(ref.attachmentId) { mutableIntStateOf(0) }
    LaunchedEffect(ref.attachmentId, attempt) {
        loading = true; failed = false; bitmap = null
        val bytes = Pocket.loadAttachmentBytes(sessionId, ref.attachmentId)
        if (bytes == null) { failed = true; loading = false; return@LaunchedEffect }
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        if (bmp == null) { failed = true; loading = false; return@LaunchedEffect }
        bitmap = bmp.asImageBitmap(); loading = false
    }
    Column(Modifier.padding(start = 56.dp).clip(T.R12).background(T.Fill).padding(8.dp), horizontalAlignment = Alignment.End) {
        when {
            loading -> Text(L.imageLoading(), fontSize = 13.sp, color = T.Text3)
            failed -> Text(
                L.imageLoadFailed(),
                fontSize = 13.sp,
                color = T.Err,
                modifier = Modifier.clickable { attempt++ },
            )
            bitmap != null -> {
                Image(bitmap!!, L.image(), Modifier.widthIn(max = 220.dp).heightIn(max = 220.dp), contentScale = ContentScale.Fit)
                Text(L.viewOriginal(), fontSize = 12.sp, color = T.Accent, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

@Composable
private fun DeliverableCard(item: Item.Deliverable) {
    SelectableOutput {
        Column(Modifier.fillMaxWidth().clip(T.R12).border(1.dp, T.Border, T.R12).padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(L.deliverablesTitle(), fontSize = 14.sp, fontWeight = FontWeight.Medium, color = T.Ink)
                Spacer(Modifier.weight(1f))
                Text(L.delivered(), fontSize = 12.sp, color = T.Ok)
            }
            item.files.forEach { f ->
                Spacer(Modifier.height(8.dp))
                Text(f.path.substringAfterLast('/'), style = T.Mono.copy(fontSize = 13.sp, color = T.Ink), maxLines = 1, overflow = TextOverflow.Ellipsis)
                f.description?.takeIf { it.isNotBlank() }?.let {
                    Text(it, fontSize = 12.sp, color = T.Text3, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun ChangesCard(item: Item.Changes) {
    var count by remember(item.key) { mutableIntStateOf(item.count) }
    var name by remember(item.key) { mutableStateOf(item.name) }
    LaunchedEffect(item.key, item.seq) {
        val seq = item.seq ?: return@LaunchedEffect
        if (count > 0 && name != null) return@LaunchedEffect
        if ("workspaceChanges.summary" !in Pocket.ops.value) return@LaunchedEffect
        Pocket.fetchChangesSummary(seq) { triple ->
            if (triple == null) return@fetchChangesSummary
            count = triple.first
            if (triple.second != null) name = triple.second
        }
    }
    val title = when {
        name != null -> L.editedName(name!!)
        else -> L.editedCount(count.coerceAtLeast(0))
    }
    SelectableOutput {
        Row(
            Modifier.fillMaxWidth().clip(T.R12).border(1.dp, T.Border, T.R12).padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, fontSize = 14.sp, color = T.Ink, modifier = Modifier.weight(1f))
            Text("T${item.turn}", fontSize = 12.sp, color = T.Caption, style = T.Mono)
        }
    }
}

@Composable
private fun UserQuestionCard(q: UserQuestion) {
    var index by remember(q.questionId) { mutableIntStateOf(0) }
    val item = q.questions.getOrElse(index) { q.questions.first() }
    val isPlan = item.intentKind == "plan-review"
    var selected by remember(q.questionId, item.id) { mutableStateOf(setOf<String>()) }
    var custom by remember(q.questionId, item.id) { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }
    fun submit(skip: Boolean = false) {
        if (!skip && selected.isEmpty() && custom.isBlank()) {
            err = L.selectOrCustom(); return
        }
        val answers = q.questions.mapIndexed { i, qi ->
            buildJsonObject {
                put("id", qi.id)
                if (i == index) {
                    putJsonArray("selected") { selected.forEach { add(JsonPrimitive(it)) } }
                    if (custom.isNotBlank()) put("custom", custom)
                } else {
                    putJsonArray("selected") {}
                }
            }
        }
        Pocket.answerUserQuestion(q.callId ?: q.questionId, answers)
    }
    Column(Modifier.padding(horizontal = 14.dp, vertical = 6.dp).fillMaxWidth().clip(T.R16).border(1.dp, T.WarnBorder, T.R16)) {
        Row(Modifier.fillMaxWidth().background(T.WarnTint).padding(horizontal = 18.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(T.WarnBorder)); Spacer(Modifier.width(8.dp))
            Text(if (isPlan) L.planReview() else (item.header ?: "问题 ${index + 1}/${q.questions.size}"), fontSize = 14.sp, color = T.WarnText)
        }
        Column(Modifier.padding(18.dp)) {
            SelectableOutput {
                Text(item.question, fontSize = 16.sp, color = T.Ink)
                item.detail?.takeIf { it.isNotBlank() }?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, fontSize = 13.sp, color = T.Text3)
                }
            }
            Spacer(Modifier.height(12.dp))
            item.options.forEach { opt ->
                val rec = opt.label.contains("推荐") || opt.label.contains("recommended", ignoreCase = true)
                val display = opt.label.replace(Regex("""\s*[（(](?:推荐|recommended)[)）]\s*$""", RegexOption.IGNORE_CASE), "")
                val on = opt.label in selected
                Row(
                    Modifier.fillMaxWidth().clip(T.R8).border(1.dp, if (on) T.Accent else T.Border, T.R8)
                        .clickable {
                            selected = if (item.multiSelect) {
                                if (on) selected - opt.label else selected + opt.label
                            } else setOf(opt.label)
                            err = null
                        }.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(display, fontSize = 15.sp, color = T.Ink, modifier = Modifier.weight(1f))
                    if (rec) Text(L.recommended(), fontSize = 11.sp, color = T.Accent)
                }
                Spacer(Modifier.height(6.dp))
            }
            OutlinedTextField(
                custom, { custom = it; err = null },
                placeholder = { Text(L.typeYourAnswer()) },
                shape = T.R12,
                modifier = Modifier.fillMaxWidth(),
            )
            err?.let { Spacer(Modifier.height(6.dp)); Text(it, fontSize = 12.sp, color = T.Err) }
            Spacer(Modifier.height(14.dp))
            if (isPlan) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton({
                        val decline = item.options.firstOrNull { it.label != item.approveLabel }?.label
                        selected = decline?.let { setOf(it) }.orEmpty(); submit()
                    }, Modifier.weight(1f).height(48.dp), shape = T.R12, border = BorderStroke(1.dp, T.Border),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = T.Ink)) { Text(L.declinePlan()) }
                    Button({
                        item.approveLabel?.let { selected = setOf(it) }; submit()
                    }, Modifier.weight(1f).height(48.dp), shape = T.R12, colors = PrimaryColors()) { Text(L.approvePlan()) }
                }
                TextButton({ submit(skip = false); /* discuss = custom */ }, Modifier.fillMaxWidth()) {
                    Text(L.requestChanges(), color = T.Text3)
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton({ submit(skip = true) }, Modifier.weight(1f).height(48.dp), shape = T.R12,
                        border = BorderStroke(1.dp, T.Border), colors = ButtonDefaults.outlinedButtonColors(contentColor = T.Ink)) {
                        Text(L.skip())
                    }
                    if (index < q.questions.lastIndex) {
                        Button({
                            if (selected.isEmpty() && custom.isBlank()) { err = L.completeQuestionFirst(); return@Button }
                            index++
                        }, Modifier.weight(1f).height(48.dp), shape = T.R12, colors = PrimaryColors()) { Text(L.nextQuestion()) }
                    } else {
                        Button({ submit() }, Modifier.weight(1f).height(48.dp), shape = T.R12, colors = PrimaryColors()) { Text(L.nextQuestion()) }
                    }
                }
            }
        }
    }
}

@Composable
private fun Composer(
    placeholder: String,
    running: Boolean,
    perm: String,
    model: ModelChoice?,
    enabled: Boolean,
    pendingImages: List<PromptImage> = emptyList(),
    pendingFiles: List<PendingAttach> = emptyList(),
    onPlus: () -> Unit,
    onPerm: () -> Unit,
    onModel: () -> Unit,
    onRemoveImage: (Int) -> Unit = {},
    onRemoveFile: (Int) -> Unit = {},
    onRetryFile: (Int) -> Unit = {},
    onSlash: () -> Unit = {},
    onMention: () -> Unit = {},
    onSend: (String) -> Unit,
    onStop: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        Pocket.composerInsert.collect { token ->
            val base = text.trimEnd()
            text = when {
                base.isEmpty() || base == "@" || base.endsWith("@") -> {
                    val bare = token.removePrefix("@")
                    if (base.endsWith("@") || base == "@") base.removeSuffix("@") + "@$bare "
                    else "@$bare "
                }
                else -> "$base ${if (token.startsWith("@")) token else "@$token"} "
            }
        }
    }
    val hasPending = pendingImages.isNotEmpty() || pendingFiles.isNotEmpty()
    val filesReady = pendingFiles.none { it.status == AttachStatus.Uploading || it.status == AttachStatus.Failed || it.status == AttachStatus.Pending }
    val sendLabel = composerSendLabel(running)
    Column(Modifier.padding(horizontal = 14.dp, vertical = 4.dp).fillMaxWidth().clip(T.R20).border(1.dp, T.Border, T.R20).padding(start = 16.dp, end = 10.dp, top = 14.dp, bottom = 10.dp)) {
        if (hasPending) {
            Text(L.pendingFiles(), fontSize = 12.sp, color = T.Text3)
            Spacer(Modifier.height(6.dp))
            pendingImages.forEachIndexed { i, img ->
                Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(img.name ?: L.image(), fontSize = 13.sp, color = T.Ink, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(L.removeFile(img.name ?: L.image()), fontSize = 12.sp, color = T.Text3, modifier = Modifier.clickable { onRemoveImage(i) })
                }
            }
            pendingFiles.forEachIndexed { i, f ->
                Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(f.name, fontSize = 13.sp, color = T.Ink, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    when (f.status) {
                        AttachStatus.Uploading, AttachStatus.Pending -> Text(L.uploading(), fontSize = 12.sp, color = T.Caption)
                        AttachStatus.Failed -> Text(L.uploadFailed(), fontSize = 12.sp, color = T.Err, modifier = Modifier.clickable { onRetryFile(i) })
                        AttachStatus.Ready -> Text(L.removeFile(f.name), fontSize = 12.sp, color = T.Text3, modifier = Modifier.clickable { onRemoveFile(i) })
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        Box {
            if (text.isEmpty()) Text(placeholder, fontSize = 16.sp, color = T.Caption, maxLines = 2, overflow = TextOverflow.Ellipsis)
            BasicTextField(
                text,
                {
                    text = it
                    if (it == "/") onSlash()
                    if (it == "@" || it.endsWith(" @") || (it.startsWith("@") && !it.contains(' '))) onMention()
                },
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = T.Ink),
                cursorBrush = SolidColor(T.Accent),
                modifier = Modifier.fillMaxWidth().heightIn(max = 160.dp).testTag("composerInput"),
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(32.dp).clip(CircleShape).background(T.Fill).clickable(onClick = onPlus), contentAlignment = Alignment.Center) { Icon(D.plus, L.sectionAdd(), tint = T.Ink, modifier = Modifier.size(18.dp)) }
            Spacer(Modifier.width(8.dp))
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                val danger = perm == "danger-full-access"
                // Desktop always shows shield + permission label (e.g. 工作区内修改).
                Row(Modifier.clip(T.R8).clickable(onClick = onPerm).padding(horizontal = 6.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (danger) D.warning else D.shield, permissionLabel(perm), tint = if (danger) T.WarnText else T.Text2, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(5.dp))
                    Text(permissionLabel(perm), fontSize = 14.sp, color = T.Text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Icon(D.chevronDown, null, tint = T.Text2, modifier = Modifier.size(16.dp))
                }
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End) {
                    Row(Modifier.clip(T.R8).clickable(onClick = onModel).padding(horizontal = 6.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(model?.let { shortModelName(it.name) } ?: "选择模型", fontSize = 14.sp, color = T.Text2, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        model?.effort?.let { Spacer(Modifier.width(5.dp)); Text(it.replaceFirstChar(Char::uppercase), fontSize = 14.sp, color = T.Caption, maxLines = 1, softWrap = false) }
                        Icon(D.chevronDown, null, tint = T.Text2, modifier = Modifier.size(16.dp))
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            val canSend = enabled && (text.isNotBlank() || (hasPending && filesReady && pendingFiles.none { it.status != AttachStatus.Ready } && (pendingImages.isNotEmpty() || pendingFiles.isNotEmpty())))
            val primaryFill = T.Accent.copy(alpha = if (canSend || running) 1f else 0.4f)
            if (running && text.isBlank() && !hasPending) {
                Box(
                    Modifier.size(34.dp).clip(CircleShape).background(primaryFill).clickable(onClick = onStop)
                        .semantics { contentDescription = "终止" },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(Color.White))
                }
            } else {
                Box(
                    Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(primaryFill)
                        .clickable(enabled = canSend) { onSend(text.trim()); text = "" }
                        .semantics { contentDescription = sendLabel }
                        .testTag("composerSend"),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(D.send, sendLabel, tint = Color.White, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

@Composable
private fun Footer(st: ChatState) {
    val u = st.usage
    val detailed = Pocket.prefs.performanceUsage == PerformanceUsage.Detailed
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        Text("${u.turns} 轮 ${u.steps} 步", fontSize = 12.sp, color = T.Text3)
        if (detailed) u.cacheHitPercent?.let { Spacer(Modifier.width(14.dp)); Text("缓存命中 $it%", fontSize = 12.sp, color = T.Text3) }
    }
}
