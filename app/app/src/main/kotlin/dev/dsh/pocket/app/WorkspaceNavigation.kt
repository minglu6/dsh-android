package dev.dsh.pocket.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dsh.pocket.protocol.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

@Composable
internal fun WorkspaceNavigationGroups(
    workspaces: List<WorkspaceView>, sessions: List<SessionSummary>, tree: Boolean,
    collapsed: Set<String>, onToggle: (String) -> Unit, onPick: (String) -> Unit,
    renderSessions: @Composable (String, List<SessionSummary>) -> Unit,
) {
    val chat by Pocket.chat.collectAsState()
    val draftPath by Pocket.draftWorkspacePath.collectAsState()
    val currentId = chat?.sessionId
    val currentWorkspace = if (currentId != null) workspaces.firstOrNull { currentId in it.sessionIds }
        else if (draftPath != null) workspaces.firstOrNull { it.path == draftPath }
        else workspaces.firstOrNull()
    val currentPath = currentWorkspace?.let { workspacePathKey(it.path) }
    fun containsCurrent(branch: WorkspaceBranch): Boolean =
        branch.path == currentPath || branch.children.any(::containsCurrent)
    val assigned = workspaces.flatMap { it.sessionIds }.toSet()
    val orphanGroups = sessions.filter { it.sessionId !in assigned }.groupBy { "" }
    val paths = (workspaces.map { workspacePathKey(it.path) } + orphanGroups.keys).distinct()
    @Composable fun group(path: String, children: List<WorkspaceBranch>, depth: Int) {
        val registered = workspaces.filter { workspacePathKey(it.path) == path }
        val fallback = orphanGroups[path].orEmpty()
        Column(Modifier.fillMaxWidth()) {
            if (registered.isEmpty()) {
                val key = "path:$path"
                WorkspaceGroupHeader(path.ifEmpty { L.ungrouped() }, path, key in collapsed, { onToggle(key) }, depth = depth,
                    active = fallback.any { it.sessionId == currentId } || children.any(::containsCurrent))
                if (key !in collapsed) {
                    renderSessions(key, fallback)
                    children.forEach { group(it.path, it.children, depth + 1) }
                }
            } else {
                registered.forEachIndexed { index, workspace ->
                    val key = "workspace:${workspace.workspaceId}"
                    WorkspaceGroupHeader(workspaceDisplayTitle(workspace.title, workspace.path), workspace.path,
                        key in collapsed, { onToggle(key) }, workspace, onPick, depth,
                        active = workspace.workspaceId == currentWorkspace?.workspaceId || (index == 0 && children.any(::containsCurrent)))
                    if (key !in collapsed) {
                        if (index == 0) children.forEach { group(it.path, it.children, depth + 1) }
                        renderSessions(key, sessions.filter { it.sessionId in workspace.sessionIds } + if (index == 0) fallback else emptyList())
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
    active: Boolean = false,
) {
    val ops by Pocket.ops.collectAsState()
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf(false) }
    var remove by remember { mutableStateOf(false) }
    var draft by remember(workspace?.title, title) { mutableStateOf(workspace?.title?.takeIf { it.isNotBlank() } ?: title) }
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
        Row(Modifier.weight(1f).heightIn(min = 48.dp)
            .clickable(onClickLabel = if (collapsed) L.expand() else L.collapse(), onClick = onToggle)
            .padding(start = (12 + minOf(depth, 4) * 12).dp, end = 12.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(if (collapsed) R.drawable.dsh_folder_close else R.drawable.dsh_folder_selected), null,
                Modifier.size(16.dp).testTag("workspaceIcon:${workspace?.workspaceId ?: path}"),
                tint = if (active) T.Accent else T.Text3)
            Spacer(Modifier.width(8.dp))
            Text(title, modifier = Modifier.weight(1f), fontSize = 14.sp, color = T.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (workspace != null) {
            if ("workspace.rename" in ops || "workspace.delete" in ops) Box {
                IconButton({ menu = !menu }, Modifier.size(48.dp).testTag("workspaceMenu:${workspace.workspaceId}"), enabled = !busy) {
                    Icon(D.ellipsis, L.moreActions(), Modifier.size(16.dp), tint = T.Text2)
                }
                DropdownMenu(menu, { menu = false }, modifier = Modifier.widthIn(min = 192.dp),
                    containerColor = T.Surface, shape = T.R16) {
                    if ("workspace.rename" in ops) DropdownMenuItem(
                        text = { Text(L.rename()) },
                        leadingIcon = { Icon(D.edit, null, Modifier.size(16.dp)) },
                        onClick = {
                            menu = false
                            draft = workspace.title.takeIf { it.isNotBlank() } ?: title
                            error = null
                            rename = true
                        },
                    )
                    if ("workspace.delete" in ops) DropdownMenuItem(
                        text = { Text(navText("删除工作区", "Delete workspace")) },
                        leadingIcon = { Icon(D.trash, null, Modifier.size(16.dp)) },
                        colors = MenuDefaults.itemColors(textColor = T.Err, leadingIconColor = T.Err),
                        onClick = { menu = false; error = null; remove = true },
                    )
                }
            }
            IconButton(
                { action { onPick(Pocket.startWorkspaceSession(workspace.workspaceId)) } },
                Modifier.size(48.dp).testTag("workspaceNewSession:${workspace.workspaceId}"),
                enabled = !busy,
            ) {
                Icon(D.newChat, navText("新会话", "New session"), Modifier.size(18.dp), tint = T.Text2)
            }
        }
    }
    error?.let { Text(it, color = T.Err, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 12.dp)) }
    if (rename && workspace != null) AlertDialog(
        onDismissRequest = { if (!busy) rename = false }, title = { Text(L.rename()) },
        text = { Column { OutlinedTextField(draft, { draft = it; error = null }, singleLine = true, enabled = !busy); error?.let { Text(it, color = T.Err) } } },
        confirmButton = { TextButton({ action { Pocket.renameWorkspace(workspace.workspaceId, draft.trim()); rename = false } }, enabled = !busy && draft.isNotBlank()) { Text(L.save()) } },
        dismissButton = { TextButton({ rename = false }, enabled = !busy) { Text(L.cancel()) } },
    )
    if (remove && workspace != null) AlertDialog(
        onDismissRequest = { if (!busy) remove = false }, title = { Text(navText("删除工作区", "Delete workspace")) },
        text = { Column { Text(navText("将把“$title”从工作区列表中移除。文件夹与会话记录会保留，其会话将显示在“未分组”下。", "This removes “$title” from the workspace list. The folder and session logs will be kept. Its sessions will appear under Ungrouped.")); error?.let { Text(it, color = T.Err) } } },
        confirmButton = { TextButton({ action { Pocket.deleteWorkspace(workspace.workspaceId); remove = false } }, enabled = !busy,
            colors = ButtonDefaults.textButtonColors(contentColor = T.Err)) { Text(navText("删除工作区", "Delete workspace")) } },
        dismissButton = { TextButton({ remove = false }, enabled = !busy) { Text(L.cancel()) } },
    )
}
