package dev.dsh.pocket.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dsh.pocket.protocol.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonElement

@Composable
internal fun WorkspaceNavigationGroups(
    workspaces: List<WorkspaceView>, sessions: List<SessionSummary>, tree: Boolean,
    collapsed: Set<String>, onToggle: (String) -> Unit, onPick: (String) -> Unit,
    renderSessions: @Composable (List<SessionSummary>) -> Unit,
) {
    val assigned = workspaces.flatMap { it.sessionIds }.toSet()
    val orphanGroups = sessions.filter { it.sessionId !in assigned }.groupBy { "" }
    val paths = (workspaces.map { workspacePathKey(it.path) } + orphanGroups.keys).distinct()
    @Composable fun group(path: String, children: List<WorkspaceBranch>, depth: Int) {
        val registered = workspaces.filter { workspacePathKey(it.path) == path }
        val fallback = orphanGroups[path].orEmpty()
        Column(Modifier.fillMaxWidth()) {
            if (registered.isEmpty()) {
                val key = "path:$path"
                WorkspaceGroupHeader(path.ifEmpty { L.ungrouped() }, path, key in collapsed, { onToggle(key) }, depth = depth)
                if (key !in collapsed) {
                    renderSessions(fallback)
                    children.forEach { group(it.path, it.children, depth + 1) }
                }
            } else {
                registered.forEachIndexed { index, workspace ->
                    val key = "workspace:${workspace.workspaceId}"
                    WorkspaceGroupHeader(workspace.title?.takeIf { it.isNotBlank() } ?: workspace.path.substringAfterLast('/').ifEmpty { workspace.path }, workspace.path,
                        key in collapsed, { onToggle(key) }, workspace, onPick, depth)
                    if (key !in collapsed) {
                        if (index == 0) children.forEach { group(it.path, it.children, depth + 1) }
                        renderSessions(sessions.filter { it.sessionId in workspace.sessionIds } + if (index == 0) fallback else emptyList())
                    }
                }
            }
        }
    }
    if (tree) {
        workspaceBranches(paths).forEach { group(it.path, it.children, 0) }
        if ("" in paths) group("", emptyList(), 0)
    } else paths.forEach { group(it, emptyList(), 0) }
}

@Composable
private fun WorkspaceGroupHeader(
    title: String, path: String, collapsed: Boolean, onToggle: () -> Unit,
    workspace: WorkspaceView? = null, onPick: (String) -> Unit = {},
    depth: Int = 0,
) {
    val ops by Pocket.ops.collectAsState()
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf(false) }
    var remove by remember { mutableStateOf(false) }
    var draft by remember(title) { mutableStateOf(title) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    fun action(block: suspend () -> Unit) {
        busy = true; error = null
        scope.launch {
            try { block() } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message }
            finally { busy = false }
        }
    }
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("workspaceCollapse:${workspace?.workspaceId ?: path}"), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).heightIn(min = 48.dp).clickable(onClick = onToggle).padding(start = (12 + minOf(depth, 4) * 12).dp, end = 12.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (collapsed) D.chevronRight else D.folderOpen, if (collapsed) L.expand() else L.collapse(), Modifier.size(18.dp), tint = T.Accent)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 14.sp, color = T.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (path.isNotEmpty()) Text(path, fontSize = 10.sp, color = T.Caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (workspace != null) Box {
            IconButton({ menu = true }, enabled = !busy) { Icon(D.ellipsis, L.moreActions(), Modifier.size(18.dp)) }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem(text = { Text(navText("新会话", "New session")) }, onClick = {
                    menu = false
                    action { onPick(Pocket.startWorkspaceSession(workspace.workspaceId)) }
                })
                if ("workspace.rename" in ops) DropdownMenuItem(text = { Text(L.rename()) }, onClick = { menu = false; rename = true })
                if ("workspace.delete" in ops) DropdownMenuItem(text = { Text(navText("移除工作区", "Remove workspace")) }, onClick = { menu = false; remove = true })
            }
        }
    }
    error?.let { Text(it, color = T.Err, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 12.dp)) }
    if (rename && workspace != null) AlertDialog(
        onDismissRequest = { if (!busy) rename = false }, title = { Text(L.rename()) },
        text = { Column { OutlinedTextField(draft, { draft = it }, singleLine = true, enabled = !busy); error?.let { Text(it, color = T.Err) } } },
        confirmButton = { TextButton({ action { Pocket.renameWorkspace(workspace.workspaceId, draft.trim()); rename = false } }, enabled = !busy && draft.isNotBlank()) { Text(L.save()) } },
        dismissButton = { TextButton({ rename = false }, enabled = !busy) { Text(L.cancel()) } },
    )
    if (remove && workspace != null) AlertDialog(
        onDismissRequest = { if (!busy) remove = false }, title = { Text(navText("移除工作区？", "Remove workspace?")) },
        text = { Column { Text(navText("仅移除工作区登记，不会删除目录或会话记录。", "Only the workspace registration is removed. Directories and session history are kept.")); error?.let { Text(it, color = T.Err) } } },
        confirmButton = { TextButton({ action { Pocket.deleteWorkspace(workspace.workspaceId); remove = false } }, enabled = !busy) { Text(L.delete()) } },
        dismissButton = { TextButton({ remove = false }, enabled = !busy) { Text(L.cancel()) } },
    )
}

@Composable
internal fun WorkspaceDirectorySheet(onCreated: (WorkspaceView) -> Unit = {}, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val ops by Pocket.ops.collectAsState()
    var path by remember { mutableStateOf("") }
    var listing by remember { mutableStateOf<JsonElement?>(null) }
    var loading by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showHidden by remember { mutableStateOf(false) }
    fun browse(target: String?) {
        loading = true; error = null
        scope.launch {
            try { listing = Pocket.listWorkspaceDirectories(target); path = listing["path"].str().orEmpty() }
            catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message }
            finally { loading = false }
        }
    }
    LaunchedEffect(Unit) { if ("workspace.directoryList" in ops) browse(null) }
    PocketSheet(onDismiss) {
        SheetTitle(L.addWorkspace())
        OutlinedTextField(path, { path = it }, singleLine = true, enabled = !saving && !loading,
            placeholder = { Text(L.workspacePathPlaceholder()) }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
        if ("workspace.directoryList" in ops) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton({ browse(path.takeIf { it.isNotBlank() }) }, enabled = !saving && !loading) { Text(navText("浏览目录", "Browse")) }
                Spacer(Modifier.weight(1f))
                Text(navText("隐藏目录", "Hidden"), fontSize = 12.sp)
                Switch(showHidden, { showHidden = it })
            }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
            Column(Modifier.fillMaxWidth().heightIn(max = 280.dp).verticalScroll(rememberScrollState())) {
                val crumbs = listing["crumbs"].arr().orEmpty()
                crumbs.dropLast(1).lastOrNull()?.let { parent ->
                    TextButton({ browse(parent["path"].str()) }, enabled = !loading && !saving) { Text(navText("上一级", "Parent") + " · " + parent["path"].str().orEmpty()) }
                }
                listing["entries"].arr().orEmpty().filter { showHidden || it["hidden"].bool() != true }.forEach { entry ->
                    TextButton({ browse(entry["path"].str()) }, enabled = !loading && !saving, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Icon(D.folderOpen, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(entry["name"].str().orEmpty(), Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (!loading && listing != null && listing["entries"].arr().orEmpty().isEmpty()) Text(navText("没有子目录", "No subdirectories"), color = T.Caption, modifier = Modifier.padding(16.dp))
                if (listing["truncated"].bool() == true) Text(navText("目录较多，列表未完整显示；可输入完整路径。", "More directories exist. Enter the full path if needed."), modifier = Modifier.padding(16.dp), color = T.Caption)
            }
        }
        error?.let { Text(it, color = T.Err, modifier = Modifier.padding(16.dp)) }
        Button({
            saving = true; error = null
            scope.launch {
                try { val workspace = Pocket.createWorkspace(path.trim()); onCreated(workspace); onDismiss() }
                catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message }
                finally { saving = false }
            }
        }, enabled = path.isNotBlank() && !saving && !loading, modifier = Modifier.fillMaxWidth().padding(16.dp).heightIn(min = 48.dp), colors = PrimaryColors()) {
            Text(if (saving) navText("添加中…", "Adding…") else L.addWorkspace())
        }
    }
}
