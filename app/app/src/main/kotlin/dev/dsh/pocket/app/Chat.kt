package dev.dsh.pocket.app

import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.animation.ValueAnimator
import android.graphics.BitmapFactory
import dev.dsh.pocket.protocol.Approval
import dev.dsh.pocket.protocol.CompatibilityIssue
import dev.dsh.pocket.protocol.CompatibilityIssueKind
import dev.dsh.pocket.protocol.ImageRef
import dev.dsh.pocket.protocol.Item
import dev.dsh.pocket.protocol.PromptFile
import dev.dsh.pocket.protocol.PromptImage
import dev.dsh.pocket.protocol.QueuedPrompt
import dev.dsh.pocket.protocol.QueueAction
import dev.dsh.pocket.protocol.ToolStatus
import dev.dsh.pocket.protocol.UserQuestion
import dev.dsh.pocket.protocol.bool
import dev.dsh.pocket.protocol.get
import dev.dsh.pocket.protocol.str
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
    None, Model, Permission, PermissionRisk, Preset, Plus, More, Panel, Team, Workspace, Goal, Feedback,
    Files, Jobs, Terminal, Browser, SessionInspector, Todo, Commands, Mentions, FeedbackCategory, FilePreview,
}

/** Themed handshake hint — warn tint matching approval cards. */
@Composable
fun CompatibilityBanner(issue: CompatibilityIssue?) {
    if (issue == null) return
    val text = when (issue.kind) {
        CompatibilityIssueKind.PLUGIN_TOO_OLD -> L.pluginTooOld(issue.requiredVersion)
        CompatibilityIssueKind.APP_TOO_OLD -> L.appTooOld(issue.requiredVersion)
        CompatibilityIssueKind.PROTOCOL_UNSUPPORTED -> L.protocolUnsupported()
    }
    Row(
        Modifier
            .fillMaxWidth()
            .background(T.WarnTint)
            .border(1.dp, T.WarnBorder)
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .testTag("compatibilityBanner"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(T.WarnBorder))
        Spacer(Modifier.width(8.dp))
        Text(text, fontSize = 13.sp, color = T.WarnText, lineHeight = 18.sp)
    }
}

@Composable
fun ChatRoot(
    onMenu: () -> Unit,
    showMenuButton: Boolean = true,
    pendingPreset: String? = null,
    onPresetConsumed: () -> Unit = {},
) {
    val chat by Pocket.chat.collectAsState()
    val firstPrompt by Pocket.firstPrompt.collectAsState()
    // Local-only presentation; Host operations always use chat/sessionId, never this draft ID.
    val visibleChat = chat ?: firstPrompt?.let { prompt ->
        ChatState(
            sessionId = "draft-${prompt.requestId}",
            items = listOf(Item.User("local-${prompt.requestId}", prompt.text, pending = true, requestId = prompt.requestId)),
            loaded = true,
        )
    }
    val approvals by Pocket.approvals.collectAsState()
    val catalog by Pocket.catalog.collectAsState()
    val models by Pocket.model.collectAsState()
    val modelLoading by Pocket.catalogLoading.collectAsState()
    val modelError by Pocket.catalogError.collectAsState()
    val modelPending by Pocket.modelPending.collectAsState()
    val perms by Pocket.permission.collectAsState()
    val sessions by Pocket.sessions.collectAsState()
    val active by Pocket.active.collectAsState()
    val status by Pocket.status.collectAsState()
    val ops by Pocket.ops.collectAsState()
    val compatibility by Pocket.compatibility.collectAsState()
    val permissionDirectory by Pocket.permissionCatalog.collectAsState()
    val permissionLoading by Pocket.permissionCatalogLoading.collectAsState()
    val permissionError by Pocket.permissionCatalogError.collectAsState()
    val permissionChanging by Pocket.permissionChanging.collectAsState()
    var sheet by remember(active) { mutableStateOf(Sheet.None) }
    var previousSession by remember(active) { mutableStateOf(chat?.sessionId) }
    var sidebarDraft by remember(active) { mutableStateOf<TextFieldValue?>(null) }
    var tool by remember { mutableStateOf<Item.Tool?>(null) }
    var riskPermission by remember(active, chat?.sessionId) { mutableStateOf<String?>(null) }
    var permissionPickerAttempt by remember { mutableIntStateOf(0) }
    var modelDirect by remember { mutableStateOf(false) }
    // New-chat drafts (applied when the session gets created).
    var draftPreset by remember(active) { mutableStateOf("") }
    LaunchedEffect(active, pendingPreset) {
        if (pendingPreset != null) {
            draftPreset = pendingPreset
            onPresetConsumed()
        }
    }
    val presetRoster by Pocket.presetRoster.collectAsState()
    val effectivePreset = draftPreset.ifBlank {
        (presetRoster.firstOrNull { it["isDefault"].bool() == true } ?: presetRoster.firstOrNull())?.get("id").str().orEmpty()
    }
    var draftPermissionOverride by remember(active, chat?.sessionId) { mutableStateOf<String?>(null) }
    val draftPerm = draftPermissionOverride ?: Pocket.prefs.defaultPermission
    var draftModel by remember(active, chat?.sessionId) { mutableStateOf<ModelChoice?>(null) }
    val draftCwd by Pocket.draftWorkspacePath.collectAsState()
    var pendingImages by remember { mutableStateOf<List<PromptImage>>(emptyList()) }
    var pendingFiles by remember { mutableStateOf<List<PendingAttach>>(emptyList()) }
    var feedbackTarget by remember { mutableStateOf<String?>(null) }
    var feedbackRating by remember { mutableStateOf("negative") }
    var hiddenQuestion by remember(active, chat?.sessionId) { mutableStateOf<String?>(null) }
    var composerDraft by rememberSaveable(active, stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue("")) }
    var previewPath by remember { mutableStateOf<String?>(null) }
    val questions by Pocket.userQuestions.collectAsState()
    val questionDrafts = rememberSaveable(active, chat?.sessionId, saver = questionDraftSaver) {
        mutableMapOf<String, SnapshotStateMap<String, QuestionDraft>>()
    }
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
    val inspectorPicking = InspectorNavigation.sessionId == sid && InspectorNavigation.picking
    DisposableEffect(active) {
        onDispose { InspectorNavigation.reset() }
    }
    LaunchedEffect(sid) {
        if (sid != previousSession) {
            if (previousSession == null && sidebarDraft != null) {
                composerDraft = sidebarDraft!!
            } else if (previousSession == null && firstPrompt != null) {
                // Preserve text already being composed while the first session is created.
                sheet = Sheet.None
            } else {
                composerDraft = TextFieldValue("")
                sheet = Sheet.None
            }
            sidebarDraft = null
            previousSession = sid
        }
    }
    val sessionPanel = sheet == Sheet.Files || sheet == Sheet.Terminal || sheet == Sheet.SessionInspector
    LaunchedEffect(active, sheet) {
        if (sessionPanel && Pocket.chat.value == null && firstPrompt == null) {
            sidebarDraft = composerDraft
            try {
                Pocket.prepareSidebarSession(
                    cwd = draftCwd ?: sessions.firstNotNullOfOrNull { it.cwd } ?: Pocket.workspaces.value.firstOrNull()?.path,
                    preset = effectivePreset.takeIf { it.isNotBlank() },
                    pendingPermission = draftPerm.takeIf { "permissions.set" in ops && permissionDirectory?.find(it) != null },
                    pendingModel = draftModel,
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                sidebarDraft = null
                throw e
            } catch (e: Exception) {
                sidebarDraft = null
                sheet = Sheet.None
                Pocket.errors.tryEmit(e.message ?: e.toString())
            }
        }
    }
    val defaultModel = catalog?.let { c -> c.find(c.defaultProvider, c.defaultModel) }
        ?.let { ModelChoice(it.provider, it.id, it.name, catalog?.defaultEffort ?: it.defaultEffort) }
    val curModel = (if (sid != null) models[sid] else draftModel) ?: defaultModel
    val curPerm = if (sid != null) perms[sid].orEmpty() else if ("permissions.catalog" in ops) draftPerm else ""
    val currentPermissionLabel = permissionLabel(curPerm, permissionDirectory?.find(curPerm))
    val summary = sessions.firstOrNull { it.sessionId == sid }
    val dot = status[active]
    val workspaceCatalog by Pocket.workspaces.collectAsState()
    val draftWorkspace = if (draftCwd == null) workspaceCatalog.firstOrNull()
        else workspaceCatalog.firstOrNull { it.path == draftCwd }
    val draftWorkspacePath = draftCwd ?: draftWorkspace?.path
    val modelMenu: @Composable () -> Unit = {
        if (sheet == Sheet.Model) {
            ModelPicker(
                catalog, curModel, onDismiss = { sheet = Sheet.None },
                loading = modelLoading, error = modelError, pending = modelPending,
                enabled = dot == Dot.Online, openModels = modelDirect,
                onReload = { Pocket.reloadModelCatalog() },
                onPick = { choice, complete ->
                    if (sid == null) {
                        draftModel = choice
                        complete(true)
                    } else Pocket.selectModel(sid, choice, complete)
                },
            )
        }
    }
    LaunchedEffect(sheet) {
        if (sheet == Sheet.Permission) Pocket.reloadPermissionCatalog()
        if (sheet == Sheet.Model) Pocket.reloadModelCatalog()
    }
    LaunchedEffect(permissionDirectory, dot, sid) {
        val directory = permissionDirectory
        if (sid == null && directory != null && directory.find(draftPerm) == null) {
            draftPermissionOverride = directory.defaultPreset
        }
        if (sheet == Sheet.PermissionRisk && (riskPermission == null || dot != Dot.Online ||
                directory?.find(riskPermission.orEmpty()) == null)) {
            riskPermission = null
            sheet = Sheet.None
        }
    }
    fun selectPermission(value: String) {
        if (Pocket.permissionCatalog.value?.find(value) == null || dot != Dot.Online) return
        if (sid == null) {
            draftPermissionOverride = value
            riskPermission = null
            sheet = Sheet.None
        } else {
            Pocket.setPermission(sid, value) { success ->
                if (Pocket.chat.value?.sessionId == sid) {
                    riskPermission = null
                    permissionPickerAttempt++
                    sheet = if (success) Sheet.None else Sheet.Permission
                }
            }
        }
    }

    // Connection status lives in the drawer only (desktop session header has no pocket status dot).
    CompositionLocalProvider(LocalOpenFilePreview provides { path ->
        previewPath = path
        sheet = Sheet.FilePreview
    }) {
    InspectorChatSurface(sid) {
    Column(Modifier.fillMaxSize().background(T.Bg).then(if (showMenuButton) Modifier.systemBarsPadding() else Modifier).imePadding()) {
        TopBar(
            summary?.title ?: chat?.title, sid != null,
            onMenu = if (showMenuButton) onMenu else null,
            onMore = { if (firstPrompt == null) sheet = Sheet.More },
            onPanel = { if (firstPrompt == null) sheet = Sheet.Panel },
            onTeam = { if (firstPrompt == null) sheet = Sheet.Team }, onPreset = { if (firstPrompt == null) sheet = Sheet.Preset }, preset = presetLabel(chat?.agentPreset, presetRoster), onNew = { Pocket.closeChat() },
        )
        CompatibilityBanner(compatibility)
        val question = questions.firstOrNull { chat != null && (it.sessionId == null || it.sessionId == sid) }
        val approval = approvals.firstOrNull { chat != null && (it.sessionId == null || it.sessionId == sid) }
        val questionVisible = question != null && question.questions.isNotEmpty() && hiddenQuestion != question.questionId
        if (visibleChat == null) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { Hero() }
            Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Chip(D.folderOpen, draftWorkspacePath?.let { workspaceDisplayTitle(draftWorkspace?.title, it) }
                    ?: navText("选择工作区", "Choose workspace")) { sheet = Sheet.Workspace }
                if (presetRoster.isNotEmpty()) presetLabel(effectivePreset, presetRoster)?.let { label ->
                    Chip(D.agentPreset, label) { sheet = Sheet.Preset }
                }
            }
        } else {
            val st = visibleChat
            var tab by remember(st.sessionId) { mutableIntStateOf(0) }
            LaunchedEffect(Pocket.prefs.showCodeWorkView) { if (!Pocket.prefs.showCodeWorkView) tab = 0 }
            LaunchedEffect(inspectorPicking, InspectorNavigation.revealItemKey) {
                if (inspectorPicking || InspectorNavigation.sessionId == sid && InspectorNavigation.revealItemKey != null) tab = 0
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { Tabs(tab) { tab = it } }
                if (chat != null) JobsAction(active, st.sessionId, dot == Dot.Online && "jobs.list" in ops) { sheet = Sheet.Jobs }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (tab == 0) Transcript(
                    st,
                    onTool = { tool = it },
                    canFork = chat != null && firstPrompt == null && "sessions.fork" in ops && !st.running,
                    onFork = { seq -> Pocket.forkSession(seq) },
                    feedback = feedbackMap,
                    onLike = { id ->
                        if (feedbackMap[id]?.rating == "positive") Pocket.setMessageFeedback(id, null)
                        else { feedbackTarget = id; feedbackRating = "positive"; sheet = Sheet.FeedbackCategory }
                    },
                    onDislike = { id ->
                        if (feedbackMap[id]?.rating == "negative") Pocket.setMessageFeedback(id, null)
                        else { feedbackTarget = id; feedbackRating = "negative"; sheet = Sheet.FeedbackCategory }
                    },
                ) else TrajectoryView(st)
            }
            if (questionVisible) UserQuestionCard(question!!, questionDrafts.getOrPut(question.questionId) { mutableStateMapOf() },
                onDismiss = { hiddenQuestion = question.questionId })
            else if (approval != null) ApprovalCard(approval)
            if (question != null && !questionVisible) TextButton({ hiddenQuestion = null }, Modifier.fillMaxWidth()) {
                Text("问题等待回答 · 重新打开")
            }
            if (st.todos.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().clickable { sheet = Sheet.Todo }.padding(horizontal = 20.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(D.plan, null, tint = T.Text3, modifier = Modifier.size(16.dp))
                    Text("任务", color = T.Text2, fontSize = 13.sp, modifier = Modifier.padding(start = 8.dp).weight(1f))
                    Text("${st.todos.count { it.status == "completed" }}/${st.todos.size}", color = T.Text3, fontSize = 12.sp)
                    Icon(D.chevronRight, null, tint = T.Text3, modifier = Modifier.padding(start = 8.dp).size(16.dp))
                }
            }
            GoalBar(st) { sheet = Sheet.Goal }
            PromptQueue(
                sessionId = st.sessionId, prompts = st.queuedPrompts, running = st.running,
                enabled = dot == Dot.Online && st.loaded && "queue.update" in ops && !inspectorPicking,
                onAction = { id, action, complete -> Pocket.updateQueuedPrompt(st.sessionId, id, action, complete) },
            )
        }
        // Keep one composer mounted from the welcome page through session creation (including IME focus).
        if (!questionVisible && approval == null) {
            firstPrompt?.let { prompt ->
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp).testTag("firstPromptStatus"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        prompt.error?.let { "发送失败：$it" } ?: "发送中",
                        color = if (prompt.error != null) T.Err else T.Text3,
                        fontSize = 12.sp,
                        modifier = Modifier.weight(1f),
                    )
                    if (prompt.error != null) TextButton(onClick = {
                        composerDraft = TextFieldValue(prompt.text, selection = androidx.compose.ui.text.TextRange(prompt.text.length))
                        previousSession = null
                        Pocket.dismissFailedFirstPrompt()
                    }) { Text("重新编辑", color = T.Ink) }
                }
            }
            Composer(
                if (visibleChat == null) "描述你想要构建的内容, / 调用指令, @ 文件或对话" else "发消息或创建任务, / 调用指令, @ 文件或对话",
                visibleChat?.running == true, curPerm, curModel,
                enabled = dot == Dot.Online && !inspectorPicking && firstPrompt == null &&
                    (chat != null || curPerm.isEmpty() || (!permissionLoading && permissionDirectory?.find(curPerm) != null)),
                draft = composerDraft, onDraftChange = { composerDraft = it },
                planMode = visibleChat?.planMode == true,
                queueAttached = visibleChat?.queuedPrompts?.isNotEmpty() == true,
                pendingImages = pendingImages, pendingFiles = pendingFiles,
                permissionName = currentPermissionLabel,
                onPlus = { if (firstPrompt == null) sheet = Sheet.Plus }, onPerm = { if (firstPrompt == null) sheet = Sheet.Permission }, onModel = { if (firstPrompt == null) { modelDirect = false; sheet = Sheet.Model } },
                modelMenu = modelMenu, modelEffort = modelEffortLabel(catalog, curModel),
                modelBusy = modelPending != null, modelExpanded = sheet == Sheet.Model,
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
                onSlash = { if (firstPrompt == null) sheet = Sheet.Commands },
                onMention = { if (firstPrompt == null) sheet = Sheet.Mentions },
                onExitPlan = { Pocket.executeCommand("/plan off") },
                onSend = { text ->
                    if (InspectorNavigation.picking || firstPrompt != null) return@Composer
                    val ready = pendingFiles.filter { it.status == AttachStatus.Ready && it.receiptId != null }
                    val imgs = pendingImages
                    when {
                        chat == null -> Pocket.newSession(text, effectivePreset.takeIf { it.isNotBlank() },
                            curPerm.takeIf { it.isNotEmpty() }, draftModel, draftWorkspacePath)
                        imgs.isNotEmpty() || ready.isNotEmpty() ->
                            Pocket.sendWithFiles(text, imgs, ready.map { PromptFile(it.receiptId!!) })
                        else -> Pocket.send(text)
                    }
                    pendingImages = emptyList(); pendingFiles = emptyList()
                },
                onStop = { if (!InspectorNavigation.picking) Pocket.cancel() },
            )
            if (visibleChat != null) ComposerStats(visibleChat.composerMetrics)
            else Spacer(Modifier.height(10.dp))
        }
    }
    }

    tool?.let { ToolSheet(it) { tool = null } }
    if (sessionPanel && sid == null) {
        val title = when (sheet) {
            Sheet.Files -> if (Pocket.prefs.language == AppLanguage.En) "Workspace files" else "工作区文件"
            Sheet.Terminal -> if (Pocket.prefs.language == AppLanguage.En) "Terminal" else "终端"
            else -> if (Pocket.prefs.language == AppLanguage.En) "Session Log" else "会话数据诊断"
        }
        RightPanelFrame(title, onDismiss = { sheet = Sheet.None }, onBack = { sheet = Sheet.Panel }) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = T.Accent)
            }
        }
    } else when (sheet) {
        Sheet.Model -> Unit // The composer owns the anchored menu.
        Sheet.Permission -> key(permissionPickerAttempt) {
            PermissionPickerSheet(
                current = curPerm, catalog = permissionDirectory, loading = permissionLoading, error = permissionError,
                onRefresh = { Pocket.reloadPermissionCatalog() }, onDismiss = { sheet = Sheet.None },
                enabled = dot == Dot.Online, busy = permissionChanging,
                onPick = { value ->
                    if (value == "danger-full-access" || value == "auto") {
                        riskPermission = value
                        sheet = Sheet.PermissionRisk
                    } else selectPermission(value)
                },
            )
        }
        Sheet.PermissionRisk -> riskPermission?.let { value ->
            PermissionRiskSheet(
                preset = value, onDismiss = { riskPermission = null; sheet = Sheet.None },
                onConfirm = { selectPermission(value) },
                enabled = dot == Dot.Online && !permissionChanging && !permissionLoading &&
                    permissionDirectory?.find(value) != null,
            )
        }
        Sheet.Preset -> PresetSheet(if (sid == null) effectivePreset else chat?.agentPreset.orEmpty(), editable = sid == null, onDismiss = { sheet = Sheet.None }) { draftPreset = it }
        Sheet.Plus -> PlusSheet(
            onDismiss = { sheet = Sheet.None },
            onPerm = { sheet = Sheet.Permission },
            onModel = { modelDirect = true; sheet = Sheet.Model },
            onFile = { pickFiles.launch("*/*") },
            onGoal = { sheet = Sheet.Goal },
            onPlan = { Pocket.executeCommand("/plan") },
            onFeedback = { sheet = Sheet.Feedback },
            onCompact = { Pocket.executeCommand("/compact") },
            onExport = { doExport() },
        )
        Sheet.More -> MoreSheet(chat, onDismiss = { sheet = Sheet.None }, onFeedback = { sheet = Sheet.Feedback }, onExport = { doExport() })
        Sheet.Panel -> PanelSheet(
            onDismiss = { sheet = Sheet.None },
            onFiles = { sheet = Sheet.Files },
            onTerminal = { sheet = Sheet.Terminal },
            onBrowser = { sheet = Sheet.Browser },
            onInspector = { sheet = Sheet.SessionInspector },
        )
        Sheet.Team -> TeamSheet(onDismiss = { sheet = Sheet.None })
        Sheet.Workspace -> WorkspaceSheet(draftWorkspacePath, onDismiss = { sheet = Sheet.None }) { Pocket.draftWorkspacePath.value = it }
        Sheet.Goal -> GoalSheet(onDismiss = { sheet = Sheet.None })
        Sheet.Feedback -> FeedbackSheet(onDismiss = { sheet = Sheet.None })
        Sheet.FeedbackCategory -> FeedbackCategorySheet(
            onDismiss = { sheet = Sheet.None; feedbackTarget = null },
            onSubmit = { cat, note, complete ->
                val target = feedbackTarget
                if (target == null) complete(false) else Pocket.setMessageFeedback(target, feedbackRating, cat, note, complete)
            },
        )
        Sheet.Commands -> CommandPopupSheet(
            onDismiss = { sheet = Sheet.None },
            onFile = { pickFiles.launch("*/*") },
            onGoal = { sheet = Sheet.Goal },
            onPlan = { Pocket.executeCommand("/plan") },
            onFeedback = { sheet = Sheet.Feedback },
            onCompact = { Pocket.executeCommand("/compact") },
            onPerm = { sheet = Sheet.Permission },
            onModel = { modelDirect = true; sheet = Sheet.Model },
            onExport = { doExport() },
            initialQuery = composerTrigger(composerDraft.text, composerDraft.selection.end)?.query.orEmpty(),
        )
        Sheet.Mentions -> MentionPopupSheet(onDismiss = { sheet = Sheet.None }, initialQuery = composerTrigger(composerDraft.text, composerDraft.selection.end)?.query.orEmpty())
        Sheet.Files -> sid?.let { FilesSheet(it, onDismiss = { sheet = Sheet.None }, onBack = { sheet = Sheet.Panel }) }
        Sheet.Jobs -> sid?.let { JobsSheet(it, onDismiss = { sheet = Sheet.None }) } ?: run { sheet = Sheet.None }
        Sheet.Terminal -> sid?.let { TerminalSheet(it, onDismiss = { sheet = Sheet.None }, onBack = { sheet = Sheet.Panel }) }
        Sheet.Browser -> BrowserSheet(
            sessionKey = "${active.orEmpty()}:${sid.orEmpty()}",
            onDismiss = { sheet = Sheet.None }, onBack = { sheet = Sheet.Panel },
        )
        Sheet.SessionInspector -> sid?.let {
            SessionInspectorSheet(it, onDismiss = { sheet = Sheet.None }, onBack = { sheet = Sheet.Panel })
        }
        Sheet.Todo -> TodoSheet(chat?.todos.orEmpty(), onDismiss = { sheet = Sheet.None })
        Sheet.FilePreview -> {
            val p = previewPath
            if (sid != null && p != null) {
                FilePreviewSheet(sid, p, onDismiss = { sheet = Sheet.None; previewPath = null })
            } else {
                sheet = Sheet.None; previewPath = null
            }
        }
        Sheet.None -> {}
    }
    } // CompositionLocalProvider
}

@Composable
private fun TopBar(title: String?, inChat: Boolean, onMenu: (() -> Unit)?, onMore: () -> Unit, onPanel: (() -> Unit)?, onTeam: () -> Unit, onPreset: () -> Unit, preset: String?, onNew: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onMenu != null) IconButton(onMenu) { Icon(D.panelLeft, L.menu(), tint = T.Ink, modifier = Modifier.size(20.dp)) }
        else Spacer(Modifier.width(12.dp))
        // One weighted region: the title takes what the gray links leave (no competing spacer).
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            if (inChat) {
                Text(title ?: "新会话", style = MaterialTheme.typography.titleMedium, color = T.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Spacer(Modifier.width(10.dp))
                // Icon-only on phones: the session title must never be squeezed to "…".
                GrayLink(D.users, "智能体团队", onTeam)
                preset?.let { GrayLink(D.agentPreset, it, onPreset) }
            }
        }
        IconButton(onMore, Modifier.size(48.dp)) { Icon(D.ellipsis, "更多", tint = T.Ink) }
        if (!inChat) IconButton(onNew, Modifier.size(48.dp)) { Icon(D.newChat, "新会话", tint = T.Ink) }
        if (onPanel != null) IconButton(onPanel, Modifier.size(48.dp)) { Icon(D.panelRight, "右侧面板", tint = T.Text2, modifier = Modifier.size(15.dp)) }
    }
    HorizontalDivider(color = T.Fill)
}

@Composable
private fun GrayLink(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, onClick: () -> Unit) {
    Box(Modifier.size(48.dp).clip(T.R8).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
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
        (if (Pocket.prefs.showCodeWorkView) listOf("对话", "轨迹") else listOf("对话")).forEachIndexed { i, t ->
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
internal fun Transcript(
    st: ChatState,
    onTool: (Item.Tool) -> Unit,
    canFork: Boolean = false,
    onFork: (Long?) -> Unit = {},
    feedback: Map<String, dev.dsh.pocket.protocol.MessageFeedback> = emptyMap(),
    onLike: (String) -> Unit = {},
    onDislike: (String) -> Unit = {},
) {
    var followBottom by remember(st.sessionId) { mutableStateOf(true) }
    val scrollScope = rememberCoroutineScope()
    val revealItemKey = InspectorNavigation.revealItemKey.takeIf { InspectorNavigation.sessionId == st.sessionId }
    var highlightedRow by remember(st.sessionId) { mutableStateOf<String?>(null) }
    // Shared presentation preferences are read from the connected Host.
    val livePolicy = presentationPolicyFor(Pocket.prefs.transcriptView, Pocket.prefs.collapseTiming)
    val en = Pocket.prefs.language == AppLanguage.En
    val allRows = remember(st.items, st.turns, st.running, livePolicy, en) {
        foldChatItems(st.items, livePolicy, st.running, turnRecords = st.turns, en = en)
    }
    val openTurns = remember(st.sessionId, livePolicy.mode, livePolicy.collapseTiming) { mutableStateMapOf<String, Boolean>() }
    LaunchedEffect(revealItemKey, allRows) {
        val target = revealItemKey ?: return@LaunchedEffect
        val targetRow = allRows.firstOrNull { it.key == target || it is ChatRow.Process && it.members.any { member -> member.key == target } }
        allRows.filterIsInstance<ChatRow.Process>().forEach { control ->
            if (control.turnSummary && targetRow != null && targetRow.key in control.controlledKeys) openTurns[control.key] = true
        }
    }
    val rows = visibleChatRows(allRows, openTurns)
    val lastAnswers = remember(allRows) {
        allRows.filterIsInstance<ChatRow.Answer>().mapNotNull { row -> row.item.turn?.let { it to row.key } }.toMap()
    }
    val revealIndex = remember(rows, revealItemKey) { revealItemKey?.let { inspectorRowIndex(rows, it) } ?: -1 }
    val n = rows.size
    val lastLen = (st.items.lastOrNull() as? Item.Text)?.text?.length ?: 0
    // Desktop: branch only on the last message of a completed turn.
    val branchKey = rows.lastOrNull { it is ChatRow.User || it is ChatRow.Answer }?.key
    val inspection = LocalInspectionMode.current
    val skipMotion = inspection || !ValueAnimator.areAnimatorsEnabled()
    // Inspection/tests: pin a new LazyListState at the target. scrollToItem and
    // requestScrollToItem both measure-loop once Robolectric NATIVE is dirty.
    // Production animator-off still uses requestScrollToItem so follow-bottom
    // stays a snap, not a list recreation that would ignore a user scroll-up.
    val snapIndex = when {
        revealIndex >= 0 -> revealIndex
        n > 0 -> n
        else -> 0
    }
    val list = remember(st.sessionId, if (inspection) snapIndex else 0) {
        LazyListState(firstVisibleItemIndex = if (inspection) snapIndex else 0)
    }
    InspectorTranscriptScroll { delta -> followBottom = false; list.dispatchRawDelta(delta) }
    LaunchedEffect(revealItemKey, revealIndex, st.loaded, st.loadingOlder, st.hasMore, skipMotion, inspection) {
        highlightedRow = null
        val target = revealItemKey ?: return@LaunchedEffect
        followBottom = false
        val index = revealIndex
        if (index < 0) {
            // The reveal effect first opens the containing Turn; do not page or clear it meanwhile.
            if (inspectorRowIndex(allRows, target) >= 0) return@LaunchedEffect
            if (st.loaded && st.hasMore && !st.loadingOlder) Pocket.loadOlderHistory()
            else if (st.loaded && !st.hasMore && !st.loadingOlder) InspectorNavigation.revealItemKey = null
            return@LaunchedEffect
        }
        if (inspection) {
            highlightedRow = rows[index].key
            return@LaunchedEffect
        }
        if (skipMotion) list.requestScrollToItem(index) else list.scrollToItem(index)
        highlightedRow = rows[index].key
        if (!skipMotion) delay(1400)
        highlightedRow = null
        if (InspectorNavigation.sessionId == st.sessionId && InspectorNavigation.revealItemKey == target) {
            InspectorNavigation.revealItemKey = null
        }
    }
    LaunchedEffect(n, lastLen, st.running, skipMotion, inspection) {
        if (inspection || !followBottom || n == 0 || st.loadingOlder) return@LaunchedEffect
        if (skipMotion) list.requestScrollToItem(n) else list.animateScrollToItem(n)
    }
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
    LaunchedEffect(list.isScrollInProgress, skipMotion) {
        if (skipMotion) return@LaunchedEffect
        if (!list.isScrollInProgress) followBottom = !list.canScrollForward
    }
    if (st.loaded && st.items.isEmpty() && !st.running) { EmptyState(D.chat, "开始对话", "发送第一条消息"); return }
    if (!st.loaded && st.items.isEmpty()) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = T.Accent, strokeWidth = 2.dp, modifier = Modifier.size(24.dp)) }; return }
    Box(Modifier.fillMaxSize()) {
    LazyColumn(state = list, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
        itemsIndexed(rows, key = { _, row -> row.key }) { index, row ->
            val previous = rows.getOrNull(index - 1)
            val gap = when {
                previous == null -> 0
                row is ChatRow.Process && row.turnSummary || previous is ChatRow.Process && previous.turnSummary -> 16
                (row is ChatRow.Process || row is ChatRow.Tool || row is ChatRow.Reasoning) &&
                    (previous is ChatRow.Process || previous is ChatRow.Tool || previous is ChatRow.Reasoning) -> 6
                else -> 12
            }
            Column(Modifier.fillMaxWidth().padding(top = gap.dp)) {
            InspectorChatTarget(inspectorPickKey(row), highlightedRow == row.key, revealItemKey == row.key) {
            when (row) {
                is ChatRow.User -> {
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
                            branchEnabled = false,
                            branchUnavailable = false,
                            onBranch = null,
                        )
                    }
                }
                is ChatRow.Answer -> {
                    val branchHere = canFork && row.key == branchKey && !row.item.live
                    val mid = row.item.messageId
                    val rating = mid?.let { feedback[it]?.rating }
                    val turn = row.item.turn
                    val showActions = !row.item.live && if (turn == null) !st.running else
                        st.turns.firstOrNull { it.turn == turn }?.closed == true && lastAnswers[turn] == row.key
                    Column(Modifier.fillMaxWidth()) {
                        MarkdownView(row.item.text, streaming = row.item.live)
                        if (showActions) MessageActions(
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
                is ChatRow.Process -> ProcessGroupRow(
                    row, revealItemKey,
                    turnOpen = if (row.turnSummary) openTurns[row.key] ?: row.defaultOpen else null,
                    onTurnOpenChange = { openTurns[row.key] = it },
                    onTool = onTool,
                )
                is ChatRow.Deliverable -> DeliverableCard(row.item)
                is ChatRow.Changes -> if (Pocket.prefs.showCodeWorkView) ChangesCard(row.item)
            }
            }
            }
        }
        if (st.running) {
            item(key = "status") {
                Box(Modifier.padding(top = if (rows.isEmpty()) 0.dp else 12.dp)) {
                    RunningStatus(st.runStartedAt, rows.lastOrNull()?.let { it !is ChatRow.User } == true)
                }
            }
        }
        item(key = "bottom") { Spacer(Modifier.height(1.dp)) }
    }
        // Inspection/tests: a dirty Robolectric NATIVE Choreographer can make
        // canScrollForward flicker, which would recompose this button forever.
        if (!LocalInspectionMode.current && list.canScrollForward) FilledTonalIconButton(
            onClick = {
                followBottom = true
                scrollScope.launch {
                    val target = list.layoutInfo.totalItemsCount - 1
                    if (skipMotion) list.requestScrollToItem(target)
                    else list.animateScrollToItem(target)
                }
            },
            modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp).size(48.dp),
        ) { Icon(D.chevronDown, "回到底部") }
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
            .height(48.dp)
            .fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (clock == MessageClock.Start) Arrangement.End else Arrangement.Start,
    ) {
        if (clock == MessageClock.Start && timeMs != null) {
            Text(
                formatMessageClock(timeMs),
                fontSize = 13.sp,
                color = T.Text3,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.padding(end = 8.dp).testTag("messageClock"),
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ActionIconButton(
                icon = if (copied) D.check else D.copy,
                label = if (copied) L.copied() else L.copy(),
                tint = T.Text3,
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
                    tint = T.Text3,
                    iconSize = 15.dp,
                    testTag = "messageLike",
                    onClick = onLike,
                )
                val dislikeLabel = if (rating == "negative") L.undoRating() else L.dislike()
                ActionIconButton(
                    icon = if (rating == "negative") D.dislikeFill else D.dislike,
                    label = dislikeLabel,
                    tint = T.Text3,
                    iconSize = 15.dp,
                    testTag = "messageDislike",
                    onClick = onDislike,
                )
            }
            if (onBranch != null) {
                val tip = if (branchUnavailable) L.branchUnavailable() else L.branch()
                ActionIconButton(
                    icon = D.branch,
                    label = tip,
                    ariaLabel = L.branch(),
                    tint = T.Text3,
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
                color = T.Text3,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.padding(start = 8.dp).testTag("messageClock"),
            )
        }
    }
}

/** Compact horizontal rhythm with a 48dp-high touch area and unchanged official glyph sizes. */
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
                .width(36.dp)
                .height(48.dp)
                .clip(T.R8)
                .then(if (enabled) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
                .semantics {
                    contentDescription = ariaLabel
                    if (!enabled) disabled()
                    if (ariaLabel != label) stateDescription = label
                }
                .then(if (testTag != null) Modifier.testTag(testTag) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = tint.copy(alpha = alpha), modifier = Modifier.size(iconSize))
        }
    }
}

@Composable
internal fun ProcessGroupRow(
    proc: ChatRow.Process,
    revealItemKey: String? = null,
    turnOpen: Boolean? = null,
    onTurnOpenChange: (Boolean) -> Unit = {},
    onTool: (Item.Tool) -> Unit,
) {
    var expanded by rememberSaveable(proc.key, proc.grouped) { mutableStateOf(proc.defaultOpen) }
    LaunchedEffect(revealItemKey) {
        if (revealItemKey != null && proc.members.any { it.key == revealItemKey }) expanded = true
    }
    if (!proc.grouped && !proc.turnSummary) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            proc.members.forEach { member -> key(member.key) { ProcessMember(member, revealItemKey, onTool) } }
        }
        return
    }
    val open = if (proc.turnSummary) turnOpen ?: expanded else expanded
    val hasContent = if (proc.turnSummary) proc.controlledKeys.isNotEmpty() else proc.members.isNotEmpty()
    val fontSize = Pocket.prefs.fontSize
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth()
                .then(if (proc.canCollapse && hasContent) Modifier.clickable(role = Role.Button) {
                    if (proc.turnSummary && turnOpen != null) onTurnOpenChange(!open) else expanded = !open
                } else Modifier)
                .heightIn(min = (fontSize + 8).dp)
                .semantics { if (proc.canCollapse && hasContent) stateDescription = if (open) "已展开" else "已收起" }
                .testTag("processHeader:${proc.key}"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!proc.turnSummary) {
                Icon(
                    if (open) D.chevronDown else processActivityIcon(proc.activityKind), null, tint = T.Text3,
                    modifier = Modifier.size((fontSize + 2).dp).then(if (open) Modifier.rotate(180f) else Modifier),
                )
                Spacer(Modifier.width(6.dp))
            }
            ProcessShimmer(!proc.closed, Modifier.weight(1f, fill = false)) {
                val title = if (proc.detail.isNullOrBlank()) proc.title else "${proc.title} · ${proc.detail}"
                Text(title, fontSize = fontSize.sp, color = T.Text3, maxLines = 1,
                    lineHeight = (fontSize + 8).sp, overflow = TextOverflow.Ellipsis)
            }
            if (proc.turnSummary && proc.canCollapse && hasContent) {
                Spacer(Modifier.width(4.dp))
                Icon(D.chevronDown, null, tint = T.Text3,
                    modifier = Modifier.size(fontSize.dp).rotate(if (open) 180f else 0f))
            }
        }
        if (proc.turnSummary) {
            Spacer(Modifier.height(8.dp))
            HorizontalDivider(color = T.Border, thickness = 0.5.dp)
        } else if (open && hasContent) {
            val inspection = LocalInspectionMode.current
            if (inspection) {
                // heightIn+verticalScroll measure-loops on a dirty NATIVE Choreographer.
                Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    proc.members.forEach { member -> key(member.key) { ProcessMember(member, revealItemKey, onTool) } }
                }
            } else {
                val scroll = rememberScrollState()
                val maxHeight = minOf(400, androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp / 2).dp
                LaunchedEffect(open, proc.members.size) {
                    if (open && !proc.closed && ValueAnimator.areAnimatorsEnabled()) scroll.scrollTo(scroll.maxValue)
                }
                Column(Modifier.padding(top = 8.dp).heightIn(max = maxHeight).verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    proc.members.forEach { member -> key(member.key) { ProcessMember(member, revealItemKey, onTool) } }
                }
            }
        }
    }
}

@Composable
private fun processActivityIcon(kind: String) = when (kind) {
    "thinking" -> D.think
    "read", "readImage", "webFetch" -> D.browse
    "search" -> D.search
    "edit", "write" -> D.edit
    "commands" -> D.api
    "code" -> D.code
    "webSearch" -> D.globe
    "subagents" -> D.agentPreset
    "plan" -> D.plan
    "questions" -> D.question
    else -> D.sparkle
}

@Composable
private fun ProcessMember(item: Item, revealItemKey: String?, onTool: (Item.Tool) -> Unit) {
    InspectorChatTarget(item.key, reveal = item.key == revealItemKey) {
    when (item) {
        is Item.Reasoning -> ReasoningRow(item)
        is Item.Tool -> ToolCard(item) { onTool(item) }
        else -> Unit
    }
    }
}

private val reasoningParagraphBreak = Regex("\\r?\\n(?:[\\t ]*\\r?\\n)+")

internal fun reasoningPreview(text: String, running: Boolean): String {
    if (!running) return text.substringBefore('\n').replace("**", "")
    var summary = ""
    var start = 0
    for (separator in reasoningParagraphBreak.findAll(text)) {
        val newline = text.indexOf('\n', start)
        if (newline >= start && newline <= separator.range.first + separator.value.indexOf('\n')) {
            text.substring(start, newline).trim().takeIf { it.isNotEmpty() }?.let { summary = it }
        }
        start = separator.range.last + 1
    }
    val newline = text.indexOf('\n', start)
    if (newline >= start) text.substring(start, newline).trim().takeIf { it.isNotEmpty() }?.let { summary = it }
    return summary.replace("**", "")
}

@Composable
internal fun ReasoningRow(r: Item.Reasoning) {
    var open by remember(r.key) { mutableStateOf(false) }
    val preview = remember(r.text, r.live) { reasoningPreview(r.text, r.live) }
    val showPreview = !open && preview.isNotEmpty() &&
        (r.live || presentationPolicyFor(Pocket.prefs.transcriptView).settledReasoningPreview)
    val en = Pocket.prefs.language == AppLanguage.En
    val fontSize = Pocket.prefs.fontSize
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = (fontSize + 8).dp)
                .clickable(role = Role.Button) { open = !open }
                .semantics { stateDescription = if (open) "已展开" else "已收起" }
                .testTag("reasoningHeader:${r.key}"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(if (open) D.chevronDown else D.think, null, tint = T.Text3,
                modifier = Modifier.size(fontSize.dp).then(if (open) Modifier.rotate(180f) else Modifier))
            Spacer(Modifier.width(6.dp))
            ProcessShimmer(r.live, Modifier.weight(1f)) {
                Text(if (en) "Think" else "思考",
                    fontSize = (fontSize - 1).sp, lineHeight = (fontSize + 8).sp, color = T.Text3)
                if (showPreview) {
                    Box(Modifier.padding(horizontal = 8.dp).size(2.dp).background(T.Caption, CircleShape))
                    Text(preview, fontSize = (fontSize - 1).sp, color = T.Text3, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                }
            }
        }
        if (open) {
            Box(Modifier.padding(start = 22.dp, top = 4.dp, bottom = 4.dp).testTag("reasoningBody:${r.key}")) {
                MarkdownView(r.text, streaming = r.live, bodyColor = T.Text2)
            }
        }
    }
}

@Composable
private fun ToolCard(t: Item.Tool, onClick: () -> Unit) {
    val en = Pocket.prefs.language == AppLanguage.En
    val summary = remember(t, en) { toolProcessSummary(t, en) }
    val warn = t.status == ToolStatus.AwaitingApproval
    val fontSize = Pocket.prefs.fontSize
    val tint = when (t.status) {
        ToolStatus.Error -> T.Err
        ToolStatus.AwaitingApproval -> T.WarnText
        else -> T.Text3
    }
    Row(
        Modifier.fillMaxWidth().heightIn(min = (fontSize + 8).dp).clip(T.R8)
            .then(if (warn) Modifier.background(T.WarnTint) else Modifier)
            .clickable(role = Role.Button, onClick = onClick)
            .testTag("processTool:${t.key}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (t.status == ToolStatus.Error || warn) D.warning else toolProcessIcon(summary.icon),
            null, tint = tint, modifier = Modifier.size(fontSize.dp))
        Spacer(Modifier.width(6.dp))
        ProcessShimmer(t.status == ToolStatus.Running, Modifier.weight(1f)) {
            Text(summary.title, fontSize = (fontSize - 1).sp, color = tint, maxLines = 1,
                lineHeight = (fontSize + 8).sp, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            if (!summary.summary.isNullOrBlank()) {
                Box(Modifier.padding(horizontal = 8.dp).size(2.dp).background(T.Caption, CircleShape))
                Text(summary.summary, fontSize = (fontSize - 1).sp, color = tint, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            }
        }
    }
}


@Composable
internal fun ApprovalCard(a: Approval, onAnswer: (String, String, (Boolean) -> Unit) -> Unit = { id, outcome, done -> Pocket.answer(id, outcome, done) }) {
    var busy by remember(a.approvalId) { mutableStateOf(false) }
    fun answer(outcome: String) {
        if (busy) return
        busy = true
        onAnswer(a.approvalId, outcome) { success -> if (!success) busy = false }
    }
    Column(Modifier.padding(horizontal = 14.dp, vertical = 6.dp).fillMaxWidth().heightIn(max = 420.dp)
        .clip(T.R16).border(1.dp, T.WarnBorder, T.R16).verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth().background(T.WarnTint).padding(horizontal = 18.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(T.WarnBorder)); Spacer(Modifier.width(8.dp))
            Text("等待审批", fontSize = 14.sp, color = T.WarnText)
        }
        Column(Modifier.padding(18.dp)) {
            SelectableOutput {
                Column {
                Text("工具 ${a.toolName} 请求越权执行", fontSize = 16.sp, color = T.Ink)
                (approvalCommand(a) ?: a.reason)?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        it.breakAnywhere(),
                        style = T.Mono.copy(color = T.Text3, fontSize = 14.sp, lineHeight = 21.sp),
                        softWrap = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                    a.reason?.takeIf { it != approvalCommand(a) }?.let { Text(it, color = T.WarnText, fontSize = 13.sp) }
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton({ answer("rejected") }, Modifier.weight(1f).height(52.dp), enabled = !busy, shape = T.R12, border = BorderStroke(1.dp, T.Border),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = T.Ink)) { Text("拒绝", fontSize = 16.sp) }
                Button({ answer("allowed-once") }, Modifier.weight(1f).height(52.dp), enabled = !busy, shape = T.R12,
                    colors = PrimaryColors()) { Text(if (busy) "提交中…" else "允许一次", fontSize = 16.sp) }
            }
        }
    }
}

@Composable
private fun ImageBubble(sessionId: String, ref: ImageRef) {
    var bitmap by remember(ref.attachmentId) { mutableStateOf<ImageBitmap?>(null) }
    var imageBytes by remember(ref.attachmentId) { mutableStateOf<ByteArray?>(null) }
    var preview by remember(ref.attachmentId) { mutableStateOf(false) }
    var failed by remember(ref.attachmentId) { mutableStateOf(false) }
    var loading by remember(ref.attachmentId) { mutableStateOf(true) }
    var attempt by remember(ref.attachmentId) { mutableIntStateOf(0) }
    LaunchedEffect(ref.attachmentId, attempt) {
        loading = true; failed = false; bitmap = null
        val bytes = Pocket.loadAttachmentBytes(sessionId, ref.attachmentId)
        imageBytes = bytes
        if (bytes == null) { failed = true; loading = false; return@LaunchedEffect }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 660) sample *= 2
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
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
                Image(bitmap!!, L.viewOriginal(), Modifier.widthIn(max = 220.dp).heightIn(max = 220.dp)
                    .clickable { preview = true }, contentScale = ContentScale.Fit)
                TextButton({ preview = true }) { Text(L.viewOriginal(), fontSize = 12.sp, color = T.Accent) }
            }
        }
    }
    if (preview) imageBytes?.let { FullScreenImagePreview(it) { preview = false } }
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
            FileLinkChip(f.path, f.path.substringAfterLast('/'))
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

internal data class QuestionDraft(val selected: Set<String> = emptySet(), val custom: String = "") {
    val answered: Boolean get() = selected.isNotEmpty() || custom.isNotBlank()
}

private val questionDraftSaver = listSaver<MutableMap<String, SnapshotStateMap<String, QuestionDraft>>, ArrayList<String>>(
    save = { requests -> requests.flatMap { (requestId, answers) ->
        answers.map { (id, draft) -> ArrayList(listOf(requestId, id, draft.custom) + draft.selected) }
    } },
    restore = { records ->
        mutableMapOf<String, SnapshotStateMap<String, QuestionDraft>>().apply {
            records.forEach { fields -> getOrPut(fields[0]) { mutableStateMapOf() }[fields[1]] = QuestionDraft(fields.drop(3).toSet(), fields[2]) }
        }
    },
)

internal fun questionAnswers(q: UserQuestion, drafts: Map<String, QuestionDraft>) = q.questions.map { item ->
    val draft = drafts[item.id] ?: QuestionDraft()
    buildJsonObject {
        put("id", item.id)
        putJsonArray("selected") { draft.selected.forEach { add(JsonPrimitive(it)) } }
        if (draft.custom.isNotBlank()) put("custom", draft.custom.trim())
    }
}

@Composable
internal fun UserQuestionCard(
    q: UserQuestion,
    drafts: MutableMap<String, QuestionDraft>,
    onDismiss: () -> Unit,
    onAnswer: (String, List<kotlinx.serialization.json.JsonObject>, (Boolean) -> Unit) -> Unit = { id, answers, done -> Pocket.answerUserQuestion(id, answers, done) },
) {
    if (q.questions.isEmpty()) return
    var index by rememberSaveable(q.questionId) { mutableIntStateOf(0) }
    var busy by remember(q.questionId) { mutableStateOf(false) }
    var err by remember(q.questionId) { mutableStateOf<String?>(null) }
    val item = q.questions[index.coerceIn(q.questions.indices)]
    val draft = drafts[item.id] ?: QuestionDraft()
    val isPlan = q.questions.size == 1 && item.intentKind == "plan-review" &&
        !item.multiSelect && item.options.size <= 2 && item.options.any { it.label == item.approveLabel }
    fun submit() {
        if (busy) return
        busy = true; err = null
        onAnswer(q.callId ?: q.questionId, questionAnswers(q, drafts)) { success ->
            if (!success) { busy = false; err = "提交失败，答案已保留，请重试" }
        }
    }
    Column(Modifier.padding(horizontal = 14.dp, vertical = 6.dp).fillMaxWidth().heightIn(max = 420.dp)
        .clip(T.R16).border(1.dp, T.Border, T.R16).verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (isPlan) L.planReview() else "${item.header ?: "问题"} · ${index + 1}/${q.questions.size}",
                fontSize = 14.sp, color = T.Text2, modifier = Modifier.weight(1f))
            TextButton(onClick = onDismiss, enabled = !busy) { Text("收起") }
        }
        Column(Modifier.padding(horizontal = 18.dp, vertical = 8.dp)) {
            SelectableOutput {
                Column {
                    Text(item.question, fontSize = 16.sp, color = T.Ink)
                    item.detail?.takeIf { it.isNotBlank() }?.let {
                        Spacer(Modifier.height(8.dp))
                        MarkdownView(it)
                    }
                }
            }
            if (!isPlan) {
                Spacer(Modifier.height(12.dp))
                item.options.forEach { opt ->
                    val selected = opt.label in draft.selected
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(T.R8)
                        .border(1.dp, if (selected) T.Accent else T.Border, T.R8)
                        .clickable(enabled = !busy) {
                            drafts[item.id] = draft.copy(selected = if (item.multiSelect) {
                                if (selected) draft.selected - opt.label else draft.selected + opt.label
                            } else setOf(opt.label))
                            err = null
                        }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(opt.label, fontSize = 15.sp, color = T.Ink)
                            opt.description?.let { Text(it, fontSize = 12.sp, color = T.Text3) }
                        }
                        if (selected) Icon(D.check, "已选择", tint = T.Accent, modifier = Modifier.size(18.dp))
                    }
                    Spacer(Modifier.height(6.dp))
                }
                OutlinedTextField(draft.custom, { drafts[item.id] = draft.copy(custom = it); err = null },
                    enabled = !busy, placeholder = { Text(L.typeYourAnswer()) }, shape = T.R12,
                    modifier = Modifier.fillMaxWidth(), maxLines = 4)
            }
            err?.let { Text(it, fontSize = 12.sp, color = T.Err, modifier = Modifier.padding(top = 6.dp)) }
            if (busy) Text("正在提交…", color = T.Text3, modifier = Modifier.padding(vertical = 8.dp))
            else if (q.state == "continued") Text("智能体已继续工作，仍可补充答案", fontSize = 12.sp, color = T.Text3)
            Spacer(Modifier.height(10.dp))
            if (isPlan) {
                Button({
                    drafts[item.id] = QuestionDraft(setOf(item.approveLabel!!)); submit()
                }, Modifier.fillMaxWidth().heightIn(min = 48.dp), enabled = !busy, shape = T.R12, colors = PrimaryColors()) { Text(L.approvePlan()) }
                OutlinedButton(onDismiss, Modifier.fillMaxWidth().heightIn(min = 48.dp), enabled = !busy, shape = T.R12) {
                    Text("继续讨论")
                }
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (index > 0) TextButton({ index--; err = null }, enabled = !busy) { Text("上一题") }
                    TextButton({
                        drafts[item.id] = QuestionDraft()
                        if (index < q.questions.lastIndex) index++ else submit()
                    }, enabled = !busy) { Text(L.skip()) }
                    Spacer(Modifier.weight(1f))
                    Button({
                        if (!draft.answered) { err = L.completeQuestionFirst() }
                        else if (index < q.questions.lastIndex) { index++; err = null }
                        else submit()
                    }, enabled = !busy, shape = T.R12, colors = PrimaryColors()) {
                        Text(if (index < q.questions.lastIndex) L.nextQuestion() else "提交答案")
                    }
                }
            }
        }
    }
}

@Composable
internal fun PromptQueue(
    sessionId: String,
    prompts: List<QueuedPrompt>,
    running: Boolean,
    enabled: Boolean,
    onAction: (String, QueueAction, (Boolean) -> Unit) -> Unit,
) {
    var collapsed by remember(sessionId) { mutableStateOf(true) }
    var editingId by remember(sessionId) { mutableStateOf<String?>(null) }
    var draft by remember(sessionId) { mutableStateOf("") }
    var busyId by remember(sessionId) { mutableStateOf<String?>(null) }
    LaunchedEffect(prompts) {
        if (prompts.isEmpty()) collapsed = true
        if (editingId != null && prompts.none { it.id == editingId && it.editable && !it.sending }) {
            editingId = null
            draft = ""
        }
    }
    if (prompts.isEmpty()) return
    val en = Pocket.prefs.language == AppLanguage.En
    val interacting = editingId != null || busyId != null
    val expanded = !collapsed || interacting
    val single = prompts.size == 1
    val sendingLabel = if (en) "Sending…" else "发送中…"
    val shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)
    val borderColor = T.Border
    fun apply(id: String, action: QueueAction) {
        busyId = id
        onAction(id, action) { success ->
            if (busyId == id) busyId = null
            if (success && action is QueueAction.Edit && editingId == id) {
                editingId = null
                draft = ""
            }
        }
    }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 30.dp).clip(shape)
            .background(T.Fill2).drawWithCache {
                val radius = 12.dp.toPx()
                val edge = 0.25.dp.toPx()
                val border = Path().apply {
                    moveTo(edge, size.height)
                    lineTo(edge, radius)
                    quadraticTo(edge, edge, radius, edge)
                    lineTo(size.width - radius, edge)
                    quadraticTo(size.width - edge, edge, size.width - edge, radius)
                    lineTo(size.width - edge, size.height)
                }
                onDrawBehind { drawPath(border, borderColor, style = Stroke(0.5.dp.toPx())) }
            }
            .testTag("promptQueue").padding(vertical = 2.dp),
    ) {
        if (!single) Row(
            Modifier.fillMaxWidth().height(36.dp)
                .clickable(enabled = !interacting, role = Role.Button) { collapsed = !collapsed }
                .testTag("promptQueueToggle").semantics {
                    stateDescription = if (expanded) {
                        if (en) "Expanded" else "已展开"
                    } else {
                        if (en) "Collapsed" else "已折叠"
                    }
                }.padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(D.queue, null, tint = T.Text3, modifier = Modifier.size(14.dp))
            Text(
                if (en) "${prompts.size} queued messages" else "${prompts.size} 条排队消息",
                color = T.Text2, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f).testTag("promptQueueCount"),
            )
            if (!expanded && prompts.any { it.sending }) Text(sendingLabel, color = T.Text3, fontSize = 13.sp)
            Icon(D.chevronDown, null, tint = T.Text3, modifier = Modifier.size(14.dp).rotate(if (expanded) 0f else 180f))
        }
        if (single || expanded) Column(
            Modifier.heightIn(max = 180.dp).verticalScroll(rememberScrollState()).testTag("promptQueueList"),
        ) {
            prompts.forEachIndexed { index, prompt ->
                key(prompt.id) {
                    if (index > 0) HorizontalDivider(thickness = 0.5.dp, color = T.Border)
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 36.dp)
                            .testTag("queuedPrompt:${prompt.id}").padding(start = 12.dp, end = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (single) {
                            Icon(D.queue, null, tint = T.Text3, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(10.dp))
                        }
                        val editing = editingId == prompt.id
                        val actionable = enabled && !prompt.sending && busyId == null
                        if (editing) BasicTextField(
                            value = draft, onValueChange = { draft = it },
                            enabled = enabled && busyId == null,
                            textStyle = MaterialTheme.typography.bodySmall.copy(color = T.Ink, fontSize = 13.sp),
                            cursorBrush = SolidColor(T.Accent),
                            modifier = Modifier.weight(1f).heightIn(min = 28.dp, max = 108.dp)
                                .padding(vertical = 5.dp).testTag("queueDraft:${prompt.id}")
                                .semantics { contentDescription = if (en) "Edit queued message" else "编辑排队消息" },
                        ) else {
                            val preview = remember(prompt.text, prompt.images, prompt.files, en) {
                                buildList {
                                    if (prompt.images > 0) add(if (en) "${prompt.images} image${if (prompt.images == 1) "" else "s"}" else "${prompt.images} 张图片")
                                    if (prompt.files > 0) add(if (en) "${prompt.files} file${if (prompt.files == 1) "" else "s"}" else "${prompt.files} 个文件")
                                    val text = prompt.text.replace(Regex("\\s+"), " ").trim()
                                    if (text.isNotEmpty()) add(text)
                                }.joinToString(" · ")
                            }
                            Text(
                                preview, color = T.Text2, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        if (prompt.sending) Text(
                            sendingLabel, color = T.Text3, fontSize = 13.sp,
                            modifier = Modifier.padding(start = 6.dp),
                        )
                        Row(Modifier.padding(start = 4.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            if (editing) {
                                QueueButton(D.check, if (en) "Save queued message" else "保存排队消息",
                                    "queueSave:${prompt.id}", actionable && draft.isNotBlank()) {
                                    apply(prompt.id, QueueAction.Edit(draft))
                                }
                                QueueButton(D.close, if (en) "Cancel editing" else "取消编辑",
                                    "queueCancel:${prompt.id}", busyId == null) { editingId = null; draft = "" }
                            } else {
                                QueueButton(D.edit, if (en) "Edit queued message" else "编辑排队消息",
                                    "queueEdit:${prompt.id}", actionable && prompt.editable) {
                                    editingId = prompt.id
                                    draft = prompt.text
                                }
                                QueueButton(D.trash, if (en) "Remove queued message" else "删除排队消息",
                                    "queueRemove:${prompt.id}", actionable) { apply(prompt.id, QueueAction.Remove) }
                                QueueButton(D.send, if (en) "Steer queued message" else "插话发送",
                                    "queueSteer:${prompt.id}", actionable && running) { apply(prompt.id, QueueAction.Steer) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QueueButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tag: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        Modifier.size(32.dp).clip(CircleShape).testTag(tag)
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = label, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = T.Text3.copy(alpha = if (enabled) 1f else 0.45f), modifier = Modifier.size(14.dp))
    }
}

@Composable
private fun Composer(
    placeholder: String,
    running: Boolean,
    perm: String,
    model: ModelChoice?,
    enabled: Boolean,
    draft: TextFieldValue,
    onDraftChange: (TextFieldValue) -> Unit,
    planMode: Boolean = false,
    queueAttached: Boolean = false,
    permissionName: String = permissionLabel(perm),
    pendingImages: List<PromptImage> = emptyList(),
    pendingFiles: List<PendingAttach> = emptyList(),
    onPlus: () -> Unit,
    onPerm: () -> Unit,
    onModel: () -> Unit,
    modelMenu: @Composable () -> Unit = {},
    modelEffort: String? = null,
    modelBusy: Boolean = false,
    modelExpanded: Boolean = false,
    onRemoveImage: (Int) -> Unit = {},
    onRemoveFile: (Int) -> Unit = {},
    onRetryFile: (Int) -> Unit = {},
    onSlash: () -> Unit = {},
    onMention: () -> Unit = {},
    onExitPlan: () -> Unit = {},
    onSend: (String) -> Unit,
    onStop: () -> Unit,
) {
    val text = draft.text
    val latestDraft by rememberUpdatedState(draft)
    val latestChange by rememberUpdatedState(onDraftChange)
    LaunchedEffect(Unit) {
        Pocket.composerInsert.collect { token ->
            // Empty → consume slash trigger; leading `/` → claim/replace draft; else @-mention insert.
            val current = latestDraft
            val next = replaceComposerToken(current.text, current.selection.end, token)
            latestChange(TextFieldValue(next.first, TextRange(next.second)))
        }
    }
    val hasPending = pendingImages.isNotEmpty() || pendingFiles.isNotEmpty()
    val filesReady = pendingFiles.none { it.status == AttachStatus.Uploading || it.status == AttachStatus.Failed || it.status == AttachStatus.Pending }
    val sendLabel = composerSendLabel(running)
    Column(Modifier.padding(start = 14.dp, end = 14.dp, top = if (queueAttached) 0.dp else 4.dp, bottom = 4.dp).fillMaxWidth().clip(T.R20).border(1.dp, T.Border, T.R20).padding(start = 16.dp, end = 10.dp, top = 14.dp, bottom = 10.dp)) {
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
        if (planMode) TextButton(onExitPlan, Modifier.heightIn(min = 48.dp).testTag("planChip")) {
            Icon(D.plan, L.planModeOn(), tint = T.Accent, modifier = Modifier.size(16.dp))
            Text(L.planChip(), color = T.Accent, modifier = Modifier.padding(start = 6.dp))
            Text("退出", color = T.Text3, fontSize = 12.sp, modifier = Modifier.padding(start = 6.dp))
        }
        Box {
            if (text.isEmpty()) Text(placeholder, fontSize = 16.sp, color = T.Caption, maxLines = 2, overflow = TextOverflow.Ellipsis)
            BasicTextField(
                draft,
                { next ->
                    onDraftChange(next)
                    val trigger = composerTrigger(next.text, next.selection.end)
                    if (trigger?.kind == '/') onSlash()
                    if (trigger?.kind == '@') onMention()
                },
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = T.Ink),
                cursorBrush = SolidColor(T.Accent),
                modifier = Modifier.fillMaxWidth().heightIn(max = 160.dp).testTag("composerInput"),
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).clip(CircleShape).background(T.Fill).clickable(onClick = onPlus), contentAlignment = Alignment.Center) { Icon(D.plus, L.sectionAdd(), tint = T.Ink, modifier = Modifier.size(18.dp)) }
            Spacer(Modifier.width(8.dp))
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                val danger = perm == "danger-full-access"
                // Keep the current mode accessible without taking width from the model name.
                if (perm.isNotEmpty()) IconButton(
                    onClick = onPerm,
                    modifier = Modifier.size(48.dp).testTag("composerPermission")
                        .semantics { contentDescription = permissionName },
                ) {
                    val icon = D.permission(perm)
                    if (icon != null) {
                        Icon(icon, null, tint = if (danger) T.WarnText else T.Text2, modifier = Modifier.size(18.dp))
                    } else {
                        Text(permissionName, fontSize = 12.sp, color = T.Text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                    Row(
                        Modifier.heightIn(min = 48.dp).clip(T.R8).clickable(onClick = onModel).testTag("composerModel")
                            .semantics { contentDescription = listOfNotNull(model?.name ?: "选择模型", modelEffort).joinToString(" · ") }
                            .padding(horizontal = 6.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(model?.name ?: "选择模型", fontSize = 14.sp, color = T.Text2, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false).testTag("composerModelName"))
                        modelEffort?.let { Spacer(Modifier.width(4.dp)); Text(it, fontSize = 12.sp, color = T.Caption, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 48.dp)) }
                        if (modelBusy) CircularProgressIndicator(Modifier.padding(start = 4.dp).size(14.dp), strokeWidth = 1.5.dp, color = T.Text3)
                        else Icon(D.chevronDown, null, tint = T.Text2, modifier = Modifier.size(16.dp).rotate(if (modelExpanded) 180f else 0f))
                    }
                    modelMenu()
                }
            }
            Spacer(Modifier.width(8.dp))
            val canSend = enabled && filesReady && (text.isNotBlank() || hasPending)
            val primaryFill = T.Accent.copy(alpha = if (canSend || running) 1f else 0.4f)
            if (running && text.isBlank() && !hasPending) {
                Box(
                    Modifier.size(48.dp).clip(CircleShape).background(primaryFill).clickable(onClick = onStop)
                        .semantics { contentDescription = "终止" },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(Color.White))
                }
            } else {
                Box(
                    Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(primaryFill)
                        .clickable(enabled = canSend) { onSend(text.trim()); onDraftChange(TextFieldValue("")) }
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
