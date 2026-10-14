package dev.dsh.pocket.app

import android.animation.ValueAnimator
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.dsh.pocket.protocol.WorkspaceView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun WorkspaceDirectorySheet(onCreated: (WorkspaceView) -> Unit = {}, onDismiss: () -> Unit) {
    val ops by Pocket.ops.collectAsState()
    val host by Pocket.active.collectAsState()
    val status by Pocket.status.collectAsState()
    val online = host != null && status[host] == Dot.Online
    val browse = remember(host) { "workspace.directoryList" in ops }
    key(host) {
        WorkspaceDirectoryDialog(
            browse = browse,
            canCreate = online && "workspace.directoryCreate" in ops,
            enabled = online && (!browse || "workspace.directoryList" in ops),
            canOpen = online && "workspace.create" in ops,
            list = { WorkspaceDirectoryLevel.parse(Pocket.listWorkspaceDirectories(it)) },
            createDirectory = Pocket::createWorkspaceDirectory,
            open = { onCreated(Pocket.createWorkspace(it)) },
            onDismiss = onDismiss,
        )
    }
}

@Composable
internal fun WorkspaceDirectoryDialog(
    browse: Boolean,
    canCreate: Boolean,
    list: suspend (String?) -> WorkspaceDirectoryLevel,
    createDirectory: suspend (String, String) -> String,
    open: suspend (String) -> Unit,
    onDismiss: () -> Unit,
    enabled: Boolean = true,
    canOpen: Boolean = true,
) {
    val scope = rememberCoroutineScope()
    val state = remember { WorkspaceDirectoryState(scope, list) }
    val keyboard = LocalSoftwareKeyboardController.current
    var nativePath by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }
    var folderName by remember { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }
    var createError by remember { mutableStateOf<String?>(null) }
    val inert = saving || folderName != null
    val target = if (browse) state.target else nativePath.takeIf { it.isNotBlank() }
    val ready = enabled && target != null && !state.loading && !inert && state.draft == null
    val newFolder = navText("新建文件夹", "New folder")
    val editPath = navText("编辑路径", "Edit path")
    val horizontal = rememberScrollState()
    val crumbScroll = rememberScrollState()
    var slowScan by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { if (browse && enabled) state.navigate() }
    DisposableEffect(state) { onDispose { state.dispose() } }
    LaunchedEffect(state.loading) { slowScan = false; if (state.loading) { delay(300); slowScan = true } }
    LaunchedEffect(state.child?.path, horizontal.maxValue) {
        if (state.child != null) {
            if (ValueAnimator.areAnimatorsEnabled()) horizontal.animateScrollTo(horizontal.maxValue)
            else horizontal.scrollTo(horizontal.maxValue)
        } else horizontal.scrollTo(0)
    }
    LaunchedEffect(state.target, crumbScroll.maxValue) {
        if (ValueAnimator.areAnimatorsEnabled()) crumbScroll.animateScrollTo(crumbScroll.maxValue)
        else crumbScroll.scrollTo(crumbScroll.maxValue)
    }
    LaunchedEffect(state.target) { saveError = null }

    fun cancel() {
        if (folderName != null) {
            if (!creating) folderName = null
            return
        }
        if (saving) return
        if (state.draft != null) { state.cancelEdit(); keyboard?.hide() } else onDismiss()
    }
    fun submitPath() {
        if (!enabled || inert) return
        state.draft?.takeIf { it.isNotBlank() }?.let { state.navigate(it) }
    }
    fun openTarget() {
        val path = target ?: return
        if (!ready || !canOpen) return
        saving = true
        saveError = null
        scope.launch {
            try { open(path); onDismiss() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { saveError = e.message ?: e.toString() }
            finally { saving = false }
        }
    }
    fun createFolder() {
        val path = target ?: return
        val name = folderName?.takeIf { it.isNotBlank() } ?: return
        if (!enabled || !canCreate || creating) return
        creating = true
        createError = null
        scope.launch {
            try {
                val created = createDirectory(path, name)
                folderName = null
                keyboard?.hide()
                state.navigate(created)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { createError = e.message ?: e.toString() }
            finally { creating = false }
        }
    }

    Dialog(onDismissRequest = ::cancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(Modifier.fillMaxWidth().imePadding().padding(16.dp), contentAlignment = Alignment.Center) {
            val height = minOf(500.dp, maxHeight)
            Surface(Modifier.widthIn(max = 680.dp).fillMaxWidth().height(height).testTag("workspaceDirectoryDialog"),
                shape = T.R28, color = T.Surface2) {
                Column {
                    Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 14.dp, top = 16.dp, bottom = 8.dp)) {
                        Text(navText("选择工作区目录", "Select Workspace Directory"), fontSize = 16.sp,
                            fontWeight = FontWeight.Medium, color = T.Ink)
                        Spacer(Modifier.height(8.dp))
                        if (!browse || state.draft != null) {
                            DirectoryPathEditor(
                                value = if (browse) state.draft.orEmpty() else nativePath,
                                onChange = { if (browse) state.edit(it) else nativePath = it },
                                label = editPath, enabled = !inert,
                                onSubmit = { if (browse) submitPath() else openTarget() },
                            )
                        } else Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
                            Row(Modifier.weight(1f).horizontalScroll(crumbScroll), verticalAlignment = Alignment.CenterVertically) {
                                state.crumbs.forEachIndexed { index, crumb ->
                                    if (index > 0) Icon(D.chevronRight, null, Modifier.size(12.dp), tint = T.Text3)
                                    Text(if (crumb.path == state.parent?.home) navText("主目录", "Home") else crumb.name,
                                        fontSize = 13.sp, color = T.Text3, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.widthIn(max = 160.dp).clip(T.R4)
                                            .clickable(enabled = enabled && !inert) { state.navigate(crumb.path) }
                                            .padding(horizontal = 6.dp, vertical = 12.dp).testTag("directoryCrumb:${crumb.path}"))
                                }
                            }
                            IconButton({ state.beginEdit() }, enabled = !inert) { Icon(D.edit, editPath, Modifier.size(14.dp), tint = T.Text3) }
                        }
                    }
                    HorizontalDivider(color = T.Border, thickness = .5.dp)
                    Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                        if (browse) BoxWithConstraints(Modifier.fillMaxSize()) {
                            val twoPane = state.child != null
                            val columnWidth = if (twoPane) maxOf(256.dp, (maxWidth - 24.5.dp) / 2) else maxWidth
                            Row(Modifier.fillMaxSize().horizontalScroll(horizontal).testTag("directoryPanes"),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                state.parent?.let { level ->
                                    DirectoryColumn(level, state, enabled && !inert, Modifier.width(columnWidth).fillMaxHeight()) {
                                        state.navigate(it.path, level); keyboard?.hide()
                                    }
                                }
                                state.child?.let { level ->
                                    VerticalDivider(Modifier.fillMaxHeight(), color = T.Border, thickness = .5.dp)
                                    DirectoryColumn(level, state, enabled && !inert, Modifier.width(columnWidth).fillMaxHeight()) {
                                        state.navigate(it.path, level); keyboard?.hide()
                                    }
                                }
                            }
                        }
                        if (slowScan && state.loading) Surface(Modifier.align(Alignment.TopEnd), shape = T.R8, color = T.Surface3) {
                            Text(navText("加载中…", "Loading…"), fontSize = 12.sp, color = T.Text3, modifier = Modifier.padding(8.dp))
                        }
                    }
                    if (state.parent?.truncated == true || state.child?.truncated == true)
                        Text(navText("文件夹过多，仅显示开头部分。", "Too many folders to list; only the beginning is shown."),
                            color = T.Text3, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp))
                    (saveError ?: state.error)?.let {
                        Text(it, color = T.Err, fontSize = 12.sp, maxLines = 3, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp))
                    }
                    HorizontalDivider(color = T.Border, thickness = .5.dp)
                    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp)) {
                        val compact = maxWidth < 560.dp
                        val tools: @Composable RowScope.() -> Unit = {
                            if (browse) {
                                DirectoryDialogButton(newFolder, { folderName = ""; createError = null }, enabled = canCreate && ready, add = true)
                                TextButton({ state.showHidden = !state.showHidden }, enabled = !inert,
                                    contentPadding = PaddingValues(horizontal = 10.dp), modifier = Modifier.testTag("directoryHidden")) {
                                    Text(navText("显示隐藏文件", "Show hidden files"), fontSize = 12.sp, color = T.Text3, maxLines = 1)
                                    Spacer(Modifier.width(4.dp))
                                    Box(Modifier.size(14.dp)) { if (state.showHidden) Icon(D.check, null, Modifier.fillMaxSize(), tint = T.Text3) }
                                }
                            }
                        }
                        val actions: @Composable RowScope.() -> Unit = {
                            Spacer(Modifier.weight(1f))
                            DirectoryDialogButton(L.cancel(), onDismiss, enabled = !inert, modifier = Modifier.widthIn(min = 72.dp))
                            Spacer(Modifier.width(8.dp))
                            DirectoryDialogButton(navText("打开", "Open"), ::openTarget, enabled = ready && canOpen, primary = true,
                                modifier = Modifier.widthIn(min = 72.dp).testTag("directoryOpen"))
                        }
                        if (compact) Column {
                            if (browse) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, content = tools)
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, content = actions)
                        } else Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { tools(); actions() }
                    }
                }
            }
        }
    }
    if (folderName != null) Dialog(
        onDismissRequest = { if (!creating) folderName = null },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(Modifier.imePadding().padding(16.dp).widthIn(max = 380.dp).fillMaxWidth().testTag("createDirectoryDialog"),
            color = T.Surface2, shape = T.R28) {
            Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 22.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(newFolder, fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium, color = T.Ink)
                val name = state.selected?.name ?: state.crumbs.lastOrNull()?.let {
                    if (it.path == state.parent?.home) navText("主目录", "Home") else it.name
                }.orEmpty()
                Text(navText("在\"$name\"中新建文件夹", "New folder in \"$name\""), fontSize = 14.sp, lineHeight = 22.sp, color = T.Ink)
                DirectoryPathEditor(folderName.orEmpty(), { folderName = it }, navText("文件夹名称", "Folder name"), !creating, ::createFolder,
                    placeholder = navText("未命名文件夹", "Untitled folder"), folder = true)
                createError?.let { Text(it, color = T.Err, fontSize = 12.sp) }
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    DirectoryDialogButton(L.cancel(), { folderName = null }, enabled = !creating, modifier = Modifier.testTag("cancelCreateDirectory"))
                    Spacer(Modifier.width(8.dp))
                    DirectoryDialogButton(navText("创建", "Create"), ::createFolder, enabled = enabled && canCreate && !creating && !folderName.isNullOrBlank(), primary = true)
                }
            }
        }
    }
}

/** Official outline/primary buttons: neutral ink, 36px body and 12px corners. */
@Composable
private fun DirectoryDialogButton(
    title: String, onClick: () -> Unit, enabled: Boolean = true, primary: Boolean = false,
    add: Boolean = false, modifier: Modifier = Modifier,
) {
    val foreground = if (primary) T.OnPrimary else T.Ink
    val fill = if (primary) T.PrimaryButton else androidx.compose.ui.graphics.Color.Transparent
    Row(
        modifier.height(36.dp).clip(T.R12)
            .background(if (primary && !enabled) fill.copy(alpha = 0.4f) else fill)
            .then(if (primary) Modifier else Modifier.border(0.5.dp, ThemeState.colors.BorderL3, T.R12))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
    ) {
        if (add) { Icon(D.plus, null, Modifier.size(14.dp), tint = if (enabled) foreground else T.Caption); Spacer(Modifier.width(6.dp)) }
        Text(title, fontSize = 14.sp, lineHeight = 22.sp, fontWeight = FontWeight.Normal,
            color = if (enabled) foreground else if (primary) T.OnPrimary.copy(alpha = 0.6f) else T.Caption)
    }
}

@Composable
private fun DirectoryColumn(
    level: WorkspaceDirectoryLevel, state: WorkspaceDirectoryState, enabled: Boolean, modifier: Modifier,
    onPick: (WorkspaceDirectoryEntry) -> Unit,
) {
    key(level.path) {
        LazyColumn(modifier.testTag("directoryColumn:${level.path}"), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items(state.visibleEntries(level), key = { it.path }) { entry ->
                val selected = entry.path == state.selected?.path
                Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(T.R8)
                    .background(if (selected) T.HoverBg else androidx.compose.ui.graphics.Color.Transparent)
                    .selectable(selected, enabled = enabled, role = Role.Button) { onPick(entry) }
                    .padding(horizontal = 6.dp).testTag("directoryEntry:${entry.path}"),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(painterResource(if (selected) R.drawable.dsh_folder_selected else R.drawable.dsh_folder_close), null,
                        Modifier.size(16.dp), tint = if (!enabled) T.Caption else if (selected) T.Accent else T.Text3)
                    Spacer(Modifier.width(6.dp))
                    Text(entry.name, color = if (enabled) T.Ink else T.Caption, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Icon(D.chevronRight, null, Modifier.size(12.dp), tint = T.Text3)
                }
            }
        }
    }
}

@Composable
private fun DirectoryPathEditor(
    value: String, onChange: (String) -> Unit, label: String, enabled: Boolean, onSubmit: () -> Unit,
    placeholder: String = "", folder: Boolean = false,
) {
    val focus = remember { FocusRequester() }
    var field by remember { mutableStateOf(TextFieldValue(value, TextRange(value.length))) }
    LaunchedEffect(Unit) { focus.requestFocus() }
    BasicTextField(field.copy(text = value), { field = it; onChange(it.text) }, singleLine = true, enabled = enabled,
        textStyle = TextStyle(color = T.Ink, fontSize = if (folder) 14.sp else 13.sp, lineHeight = 22.sp), cursorBrush = SolidColor(T.Ink),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go), keyboardActions = KeyboardActions(onGo = { onSubmit() }),
        modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).focusRequester(focus)
            .semantics { contentDescription = label }.testTag("directoryInput:$label"),
        decorationBox = { input -> Box(Modifier.fillMaxWidth()
            .border(.5.dp, if (folder) T.BorderStrong else T.Border, if (folder) T.R16 else T.R8)
            .padding(horizontal = if (folder) 14.dp else 10.dp, vertical = if (folder) 7.dp else 10.dp)) {
            if (value.isEmpty()) Text(placeholder, color = T.Caption, fontSize = if (folder) 14.sp else 13.sp, lineHeight = 22.sp)
            input()
        } },
    )
}
